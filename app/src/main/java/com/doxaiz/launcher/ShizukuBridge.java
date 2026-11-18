package com.doxaiz.launcher;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Минимальный клиент Shizuku без сторонних библиотек.
 *
 * <h3>Зачем свой, а не зависимость</h3>
 * Официальный {@code dev.rikka.shizuku:api} тянет androidx-аннотации, а
 * {@code ShizukuProvider} — ещё и код провайдера. Проект собирается не только
 * Gradle, но и скриптом {@code build.sh} (javac + d8 напрямую), поэтому лишние
 * зависимости ломают вторую схему сборки. Нужен ровно один сценарий —
 * выполнить shell-команду, — и он занимает несколько десятков строк протокола.
 *
 * <h3>Протокол (сверено с Shizuku-API API 13)</h3>
 * 1. Приложение объявляет {@code ContentProvider} с authority
 *    {@code <package>.shizuku}; сервер Shizuku сам присылает туда binder
 *    (метод {@code sendBinder}, ключ {@code moe.shizuku.privileged.api.intent.extra.BINDER}).
 * 2. После получения binder'а нужно «присоединиться»: транзакция
 *    {@code attachApplication} (18 в API 13) с интерфейсом
 *    {@code moe.shizuku.server.IShizukuApplication} и именем пакета.
 * 3. Право проверяется транзакцией 15 ({@code checkSelfPermission}),
 *    запрашивается 14 ({@code requestPermission}); ответ приходит в
 *    {@code dispatchRequestPermissionResult} с ключом
 *    {@code shizuku:request-permission-reply-allowed}.
 * 4. Команда выполняется транзакцией 7 ({@code newProcess}); обратно приходит
 *    binder процесса {@code moe.shizuku.server.IRemoteProcess}.
 *
 * <h3>Честная граница</h3>
 * Shizuku — отдельное приложение, которое человек запускает через ADB или root.
 * Само по себе оно ничего не «взламывает»: без Shizuku и без root ограничение
 * Android остаётся. В интерфейсе это не магия, а ускоренный путь для тех, у
 * кого Shizuku уже есть.
 */
final class ShizukuBridge {

    private static final String TAG = "ShizukuBridge";

    /** Пакет самого Shizuku (он же Sui) — для проверки «установлен ли». */
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private static final String DESCRIPTOR = "moe.shizuku.server.IShizukuService";
    private static final String APP_DESCRIPTOR = "moe.shizuku.server.IShizukuApplication";
    private static final String PROCESS_DESCRIPTOR = "moe.shizuku.server.IRemoteProcess";

    // Номера транзакций IShizukuService (Shizuku API 13).
    private static final int TX_NEW_PROCESS = 7;
    private static final int TX_REQUEST_PERMISSION = 14;
    private static final int TX_CHECK_SELF_PERMISSION = 15;
    private static final int TX_ATTACH_APPLICATION = 18;

    /** Метод bindApplication в IShizukuApplication (стабилен с API 11). */
    private static final int APP_TX_BIND_APPLICATION = 1;
    private static final int APP_TX_PERMISSION_RESULT = 2;

    // Номера транзакций IRemoteProcess.
    private static final int TX_WAIT_FOR = 4;
    private static final int TX_EXIT_VALUE = 5;

    private static final String KEY_REPLY_ALLOWED = "shizuku:request-permission-reply-allowed";
    private static final String KEY_ATTACH_API_VERSION = "shizuku:attach-api-version";
    private static final String KEY_ATTACH_PACKAGE = "shizuku:attach-package-name";
    private static final String KEY_ATTACH_PERMISSION = "shizuku:attach-reply-permission-granted";

    /** Версия API, которую объявляет клиент. 13 — актуальная у Shizuku 13.x. */
    private static final int CLIENT_API_VERSION = 13;

    private static volatile IBinder serviceBinder;
    private static volatile boolean attached;
    private static volatile boolean permissionGranted;
    /** Ответ на requestPermission приходит асинхронно — ждём его здесь. */
    private static final Object PERMISSION_LOCK = new Object();
    private static volatile Boolean pendingPermissionResult;

    private static final IBinder.DeathRecipient DEATH_RECIPIENT = () -> {
        serviceBinder = null;
        attached = false;
        permissionGranted = false;
    };

    private ShizukuBridge() {
    }

    /**
     * Binder сервера, полученный провайдером.
     *
     * @return true, если binder принят и присоединение удалось
     */
    static boolean onBinderReceived(IBinder binder, String packageName) {
        if (binder == null) return false;
        serviceBinder = binder;
        attached = false;
        try {
            binder.linkToDeath(DEATH_RECIPIENT, 0);
        } catch (Throwable ignored) {
            // Смерть сервера обнаружит следующий вызов.
        }
        attached = attachApplication(binder, packageName);
        if (!attached) permissionGranted = false;
        return attached;
    }

    /**
     * Транзакция attachApplication.
     *
     * Ответ приходит не в reply, а в {@code bindApplication} нашего
     * IShizukuApplication — читаем его и запоминаем право.
     */
    private static boolean attachApplication(IBinder binder, String packageName) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(new ApplicationBinder());
            data.writeInt(1);
            Bundle args = new Bundle();
            args.putInt(KEY_ATTACH_API_VERSION, CLIENT_API_VERSION);
            args.putString(KEY_ATTACH_PACKAGE, packageName);
            args.writeToParcel(data, 0);
            boolean ok = binder.transact(TX_ATTACH_APPLICATION, data, reply, 0);
            reply.readException();
            return ok;
        } catch (Throwable t) {
            Diagnostics.record(TAG + ".attach", t);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * «Присоединённое приложение»: сервер вызывает bindApplication и передаёт
     * Bundle с правами. Нам из него нужны два ключа.
     */
    private static final class ApplicationBinder extends android.os.Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code == APP_TX_BIND_APPLICATION) {
                data.enforceInterface(APP_DESCRIPTOR);
                Bundle bundle = readBundle(data);
                if (bundle != null) {
                    permissionGranted = bundle.getBoolean(KEY_ATTACH_PERMISSION, false);
                }
                return true;
            }
            if (code == APP_TX_PERMISSION_RESULT) {
                data.enforceInterface(APP_DESCRIPTOR);
                data.readInt(); // requestCode
                Bundle bundle = readBundle(data);
                boolean allowed = (bundle != null) && bundle.getBoolean(KEY_REPLY_ALLOWED, false);
                synchronized (PERMISSION_LOCK) {
                    pendingPermissionResult = allowed;
                    PERMISSION_LOCK.notifyAll();
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private Bundle readBundle(Parcel data) {
            try {
                return data.readBundle(ShizukuBridge.class.getClassLoader());
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** Сервер Shizuku доступен (binder жив и присоединение прошло). */
    static boolean isConnected() {
        IBinder binder = serviceBinder;
        return binder != null && binder.pingBinder() && attached;
    }

    /** Право уже выдано? Проверяется транзакцией checkSelfPermission. */
    static boolean hasPermission() {
        IBinder binder = serviceBinder;
        if (binder == null || !binder.pingBinder() || !attached) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            binder.transact(TX_CHECK_SELF_PERMISSION, data, reply, 0);
            reply.readException();
            permissionGranted = reply.readInt() != 0;
            return permissionGranted;
        } catch (Throwable t) {
            return permissionGranted;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Запрос права у Shizuku (человек подтвердит в его окне).
     *
     * @return true, если право уже есть или выдано в этом вызове
     */
    static boolean requestPermission(long timeoutMs) {
        if (hasPermission()) return true;
        IBinder binder = serviceBinder;
        if (binder == null || !binder.pingBinder()) return false;
        synchronized (PERMISSION_LOCK) {
            pendingPermissionResult = null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(1001);
            binder.transact(TX_REQUEST_PERMISSION, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Diagnostics.record(TAG + ".requestPermission", t);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
        synchronized (PERMISSION_LOCK) {
            long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
            while (pendingPermissionResult == null) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0L) break;
                try {
                    PERMISSION_LOCK.wait(left);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            boolean allowed = Boolean.TRUE.equals(pendingPermissionResult);
            permissionGranted = allowed;
            return allowed;
        }
    }

    /**
     * Выполнить shell-команду от имени Shizuku (uid 2000 — shell).
     *
     * @return код возврата или -1, если Shizuku недоступен
     */
    static int runShell(String... command) {
        IBinder binder = serviceBinder;
        if (binder == null || !binder.pingBinder() || !attached) return -1;
        if (command == null || command.length == 0) return -1;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        IBinder processBinder;
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStringArray(command);
            data.writeStringArray(null);   // env
            data.writeString(null);        // рабочий каталог
            binder.transact(TX_NEW_PROCESS, data, reply, 0);
            reply.readException();
            processBinder = reply.readStrongBinder();
        } catch (Throwable t) {
            Diagnostics.record(TAG + ".runShell", t);
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
        if (processBinder == null) return -1;
        return waitFor(processBinder);
    }

    /** IRemoteProcess: waitFor — 4, exitValue — 5. */
    private static int waitFor(IBinder process) {
        int exit;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PROCESS_DESCRIPTOR);
            process.transact(TX_WAIT_FOR, data, reply, 0);
            reply.readException();
            reply.readInt(); // результат waitpid, не код возврата
        } catch (Throwable t) {
            Diagnostics.record(TAG + ".waitFor", t);
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
        data = Parcel.obtain();
        reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PROCESS_DESCRIPTOR);
            process.transact(TX_EXIT_VALUE, data, reply, 0);
            reply.readException();
            exit = reply.readInt();
        } catch (Throwable t) {
            Diagnostics.record(TAG + ".exitValue", t);
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
        return exit;
    }

    /** Для тестов и диагностики: сбросить состояние привязки. */
    static void reset() {
        serviceBinder = null;
        attached = false;
        permissionGranted = false;
        pendingPermissionResult = null;
    }
}
