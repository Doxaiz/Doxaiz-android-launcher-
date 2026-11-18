package com.doxaiz.launcher;

import android.content.Context;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Откуда приложение установлено — и что из этого следует для ограниченных настроек.
 *
 * <h3>Зачем это нужно</h3>
 * «Настройки с ограниченным доступом» (Android 13+) срабатывают не на любую
 * установку, а на конкретный путь: APK открыли из файлового менеджера, браузера
 * или мессенджера. Если тот же APK поставить командой {@code adb install}, система
 * считает установку доверенной и ограничение **не включается вовсе** — переключатель
 * спецвозможностей активен сразу.
 *
 * <p>Это и есть «обход без установки Shizuku»: не отдельное приложение-сервер, а
 * другой способ поставить этот же APK. Поэтому лаунчер честно определяет свой
 * источник установки и говорит человеку, чего ждать: «ограничения не будет»,
 * «ограничение действует, снимается вручную или через Shizuku» или «источник
 * неизвестен».
 *
 * <h3>Почему проверка — эвристика</h3>
 * Публичного API «приложение ограничено» в Android нет. {@code InstallSourceInfo}
 * даёт имена пакетов установщика и инициатора — по ним и различаем три случая.
 * Формулировки в интерфейсе это учитывают: мы не утверждаем, что ограничения нет,
 * а говорим, что при установке через ADB его не должно быть.
 */
final class InstallSource {

    /** Установлено через adb/system installer — ограничение не должно применяться. */
    static final int SOURCE_ADB = 0;
    /** Установлено магазином приложений. */
    static final int SOURCE_STORE = 1;
    /** Открыто из файла (файловый менеджер, браузер, мессенджер) — ограничение действует. */
    static final int SOURCE_SIDELOAD = 2;
    /** Определить не удалось (старый Android или система запретила чтение). */
    static final int SOURCE_UNKNOWN = 3;

    /** Магазины, которые Android считает доверенными установщиками. */
    private static final String[] STORE_PACKAGES = {
            "com.android.vending",                  // Google Play
            "com.google.android.feedback",
            "com.huawei.appmarket",
            "com.sec.android.app.samsungapps",
            "com.xiaomi.market",
            "com.oppo.market",
            "com.heytap.market",
            "com.bbk.appstore",
            "com.amazon.venezia",
            "org.fdroid.fdroid"
    };

    private InstallSource() {
    }

    /** Источник установки этого приложения. */
    static int detect(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // До Android 11 доступен только getInstallerPackageName, который для
            // adb тоже возвращает null — отличить «adb» от «неизвестно» нельзя.
            return SOURCE_UNKNOWN;
        }
        try {
            PackageManager pm = context.getPackageManager();
            InstallSourceInfo info = pm.getInstallSourceInfo(context.getPackageName());
            if (info == null) return SOURCE_UNKNOWN;
            return classify(info.getInstallingPackageName(),
                    info.getInitiatingPackageName(),
                    info.getOriginatingPackageName());
        } catch (Throwable t) {
            Diagnostics.record("InstallSource.detect", t);
            return SOURCE_UNKNOWN;
        }
    }

    /**
     * Чистая классификация по трём именам пакетов — проверяется тестом.
     *
     * <p>{@code adb install} не передаёт ни установщика, ни инициатора, ни
     * источника: все три null. Файловый менеджер передаёт себя как инициатора,
     * а браузер — себя и как установщика.
     */
    static int classify(String installing, String initiating, String originating) {
        if (installing == null && initiating == null && originating == null) {
            return SOURCE_ADB;
        }
        if (isStore(installing) || isStore(initiating)) {
            return SOURCE_STORE;
        }
        if (installing != null || initiating != null || originating != null) {
            return SOURCE_SIDELOAD;
        }
        return SOURCE_UNKNOWN;
    }

    private static boolean isStore(String pkg) {
        if (pkg == null) return false;
        for (String store : STORE_PACKAGES) {
            if (store.equals(pkg)) return true;
        }
        return false;
    }

    /** Ожидается ли ограничение при таком источнике установки. */
    static boolean restrictionExpected(int source) {
        return source == SOURCE_SIDELOAD;
    }

    /** Что сказать человеку про его способ установки. Один текст на весь проект. */
    static String explain(int source) {
        switch (source) {
            case SOURCE_ADB:
                return "APK установлен через ADB. При таком способе Android не включает "
                        + "ограничение: переключатель спецвозможностей должен быть активен "
                        + "сразу, снимать ничего не нужно.";
            case SOURCE_STORE:
                return "Установлено из магазина приложений — ограничение не применяется.";
            case SOURCE_SIDELOAD:
                return "APK открыт из файла (файловый менеджер, браузер или мессенджер). "
                        + "Именно в этом случае Android 13+ включает ограничение. Снять его "
                        + "можно вручную, командой ADB или кнопкой через Shizuku.";
            case SOURCE_UNKNOWN:
            default:
                return "Источник установки система не сообщает. Если переключатель серый, "
                        + "снимите ограничение вручную или переустановите APK через ADB.";
        }
    }

    /** Короткая пометка для экрана настроек. */
    static String label(int source) {
        switch (source) {
            case SOURCE_ADB:
                return "установлено через ADB";
            case SOURCE_STORE:
                return "установлено из магазина";
            case SOURCE_SIDELOAD:
                return "установлено из файла";
            default:
                return "источник установки неизвестен";
        }
    }
}
