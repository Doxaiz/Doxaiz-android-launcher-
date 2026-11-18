package com.doxaiz.launcher;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

/**
 * Снятие «Настроек с ограниченным доступом» — быстро, если есть Shizuku.
 *
 * <h3>Что происходит на экране</h3>
 * На Android 13+ приложение, установленное вручную из APK, не может включить
 * спецвозможности: в списке служб переключатель серый, а при нажатии система
 * показывает диалог «Настройки с ограниченным доступом» (см. скриншот). Это
 * состояние app-op {@code ACCESS_RESTRICTED_SETTINGS}. Снять его можно тремя
 * способами:
 *
 * <ol>
 *   <li>вручную: Настройки → Приложения → Doxaiz → «Разрешить ограниченные
 *       настройки» (несколько шагов, на части прошивок пункт спрятан в меню);</li>
 *   <li>ADB: {@code adb shell appops set <пакет> ACCESS_RESTRICTED_SETTINGS allow};</li>
 *   <li>через уже запущенный Shizuku — та же команда без компьютера.</li>
 * </ol>
 *
 * Этот класс делает третий путь: проверяет состояние app-op и отправляет
 * команду через {@link ShizukuBridge}. Если Shizuku нет — возвращает «нужен
 * ручной путь», и интерфейс показывает шаги. Никакой магии: без root и без
 * Shizuku ограничение снимет только человек.
 */
final class RestrictedSettingsFix {

    private static final String TAG = "RestrictedSettingsFix";

    /** Имя app-op: в AppOpsManager константы нет, строка стабильна с Android 13. */
    static final String OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings";

    /** Коды результата: интерфейс показывает по ним разный текст. */
    static final int RESULT_OK = 0;
    static final int RESULT_NOT_NEEDED = 1;
    static final int RESULT_NO_SHIZUKU = 2;
    static final int RESULT_NO_PERMISSION = 3;
    static final int RESULT_COMMAND_FAILED = 4;
    static final int RESULT_NOT_SUPPORTED = 5;

    private RestrictedSettingsFix() {
    }

    /**
     * Снято ли ограничение.
     *
     * <p>Проверка «мягкая»: на Android 13+, где app-op есть, читаем режим и
     * считаем ограничением только явный {@code MODE_IGNORED}/{@code MODE_ERRORED}.
     * Если система запретила чтение (SecurityException), честно отвечаем
     * {@code null} — «неизвестно», и интерфейс не врёт ни в одну сторону.
     *
     * @return TRUE — ограничение снято, FALSE — действует, null — неизвестно
     */
    static Boolean isUnrestricted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Boolean.TRUE;
        try {
            ApplicationInfo ai = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), 0);
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return null;
            int mode;
            if (Build.VERSION.SDK_INT >= 31) {
                mode = ops.unsafeCheckOpNoThrow(OP_ACCESS_RESTRICTED_SETTINGS, ai.uid,
                        context.getPackageName());
            } else {
                mode = ops.checkOpNoThrow(OP_ACCESS_RESTRICTED_SETTINGS, ai.uid,
                        context.getPackageName());
            }
            if (mode == AppOpsManager.MODE_ALLOWED) return Boolean.TRUE;
            if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) {
                return Boolean.FALSE;
            }
            // MODE_DEFAULT: система ещё не выносила решение — считаем, что
            // ограничение действует, иначе человек упрётся в серый переключатель.
            return Boolean.FALSE;
        } catch (Throwable t) {
            Diagnostics.record(TAG + ".isUnrestricted", t);
            return null;
        }
    }

    /** Есть ли Shizuku в системе (установлен ли). */
    static boolean isShizukuInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(ShizukuBridge.SHIZUKU_PACKAGE, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Команда, которую выполняет Shizuku. Вынесена, чтобы её проверял тест. */
    static String[] command(String packageName) {
        return new String[]{"appops", "set", packageName, "ACCESS_RESTRICTED_SETTINGS", "allow"};
    }

    /**
     * Попытка снять ограничение через Shizuku. Вызывать НЕ из главного потока:
     * запрос права и команда синхронные.
     *
     * @return один из {@code RESULT_*}
     */
    static int fixViaShizuku(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return RESULT_NOT_NEEDED;
        Boolean state = isUnrestricted(context);
        if (Boolean.TRUE.equals(state)) return RESULT_NOT_NEEDED;
        if (!isShizukuInstalled(context)) return RESULT_NO_SHIZUKU;
        if (!ShizukuBridge.isConnected()) return RESULT_NO_SHIZUKU;
        if (!ShizukuBridge.requestPermission(60_000L)) return RESULT_NO_PERMISSION;
        int code = ShizukuBridge.runShell(command(context.getPackageName()));
        if (code != 0) {
            Diagnostics.record(TAG + ".runShell", new IllegalStateException("exit=" + code));
            return RESULT_COMMAND_FAILED;
        }
        // Проверяем результат, а не верим коду возврата: appops может ответить
        // нулём и ничего не изменить на кастомной прошивке.
        Boolean after = isUnrestricted(context);
        if (Boolean.FALSE.equals(after)) return RESULT_COMMAND_FAILED;
        return RESULT_OK;
    }

    /** Текст для человека по коду результата. */
    static String message(int result) {
        switch (result) {
            case RESULT_OK:
                return "Ограничение снято через Shizuku. Теперь включите службу в спецвозможностях";
            case RESULT_NOT_NEEDED:
                return "Ограничение уже снято — включайте службу Doxaiz";
            case RESULT_NO_SHIZUKU:
                return "Shizuku не запущен: снять ограничение можно вручную или через ADB";
            case RESULT_NO_PERMISSION:
                return "Shizuku не дал разрешение — подтвердите запрос в его окне";
            case RESULT_COMMAND_FAILED:
                return "Shizuku не смог изменить настройку — попробуйте ручной путь";
            case RESULT_NOT_SUPPORTED:
            default:
                return "На этой версии Android ограничение не действует";
        }
    }

    /**
     * Готовая ADB-команда для показа человеку.
     *
     * Нужна, потому что часть прошивок (например, Transsion) вообще не
     * показывает пункт «Разрешить ограниченные настройки», и тогда ADB —
     * единственный путь, кроме Shizuku.
     */
    static String adbCommand(String packageName) {
        return "adb shell appops set " + packageName + " ACCESS_RESTRICTED_SETTINGS allow";
    }
}
