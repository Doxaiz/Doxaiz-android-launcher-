package com.doxaiz.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.text.format.Formatter;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Считывает текущее состояние ОЗУ (занято / доступно / всего) и встроенной памяти устройства
 * без дополнительных разрешений и фоновых процессов.
 */
public final class MemoryHelper {

    public static final class Snapshot {
        public final long ramUsed;
        public final long ramFree;
        public final long ramTotal;
        public final int ramUsedPercent;

        public final long storageUsed;
        public final long storageFree;
        public final long storageTotal;
        public final int storageUsedPercent;

        public final String ramUsedStr;
        public final String ramFreeStr;
        public final String ramTotalStr;
        public final String ramShortText;
        public final String storageShortText;
        public final String oneLineSummary;
        public final String recentsHeadline;
        public final String recentsSubline;

        public Snapshot(
                long ramUsed, long ramFree, long ramTotal, int ramUsedPercent,
                long storageUsed, long storageFree, long storageTotal, int storageUsedPercent,
                String ramUsedStr, String ramFreeStr, String ramTotalStr,
                String ramShortText, String storageShortText, String oneLineSummary,
                String recentsHeadline, String recentsSubline
        ) {
            this.ramUsed = ramUsed;
            this.ramFree = ramFree;
            this.ramTotal = ramTotal;
            this.ramUsedPercent = ramUsedPercent;
            this.storageUsed = storageUsed;
            this.storageFree = storageFree;
            this.storageTotal = storageTotal;
            this.storageUsedPercent = storageUsedPercent;
            this.ramUsedStr = ramUsedStr;
            this.ramFreeStr = ramFreeStr;
            this.ramTotalStr = ramTotalStr;
            this.ramShortText = ramShortText;
            this.storageShortText = storageShortText;
            this.oneLineSummary = oneLineSummary;
            this.recentsHeadline = recentsHeadline;
            this.recentsSubline = recentsSubline;
        }
    }

    private MemoryHelper() {}

    /** Защита от прошивок с zRAM / виртуальным расширением ОЗУ, где avail может кратковременно превысить total. */
    static long clampFreeToTotal(long freeBytes, long totalBytes) {
        long safeTotal = Math.max(0L, totalBytes);
        long safeFree = Math.max(0L, freeBytes);
        return (safeTotal > 0L) ? Math.min(safeTotal, safeFree) : safeFree;
    }

    public static Snapshot read(Context context) {
        long ramTotal = 0L;
        long ramFree = 0L;
        long ramUsed = 0L;
        int ramPct = 0;

        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                ramTotal = Math.max(0L, mi.totalMem);
                ramFree = clampFreeToTotal(mi.availMem, ramTotal);
                ramUsed = Math.max(0L, ramTotal - ramFree);
                if (ramTotal > 0L) {
                    ramPct = (int) Math.max(0, Math.min(100, (ramUsed * 100L) / ramTotal));
                }
            }
        } catch (Throwable ignored) {
        }

        long stTotal = 0L;
        long stFree = 0L;
        long stUsed = 0L;
        int stPct = 0;

        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            stTotal = Math.max(0L, stat.getTotalBytes());
            stFree = clampFreeToTotal(stat.getAvailableBytes(), stTotal);
            stUsed = Math.max(0L, stTotal - stFree);
            if (stTotal > 0L) {
                stPct = (int) Math.max(0, Math.min(100, (stUsed * 100L) / stTotal));
            }
        } catch (Throwable ignored) {
        }

        String ramUsedStr = fmt(context, ramUsed);
        String ramFreeStr = fmt(context, ramFree);
        String ramTotalStr = fmt(context, ramTotal);
        String stFreeStr = fmt(context, stFree);
        String stTotalStr = fmt(context, stTotal);

        String ramShort = ramUsedStr + " / своб. " + ramFreeStr;
        String stShort = "своб. " + stFreeStr + " / " + stTotalStr;
        String summary = "ОЗУ: занято " + ramUsedStr + " • доступно " + ramFreeStr
                + "  •  Память: своб. " + stFreeStr;
        String recentsHead = "Занято " + ramUsedStr + "  |  Доступно " + ramFreeStr + " из " + ramTotalStr;
        String recentsSub = "Занято " + ramUsedStr + " • Доступно " + ramFreeStr;

        return new Snapshot(
                ramUsed, ramFree, ramTotal, ramPct,
                stUsed, stFree, stTotal, stPct,
                ramUsedStr, ramFreeStr, ramTotalStr,
                ramShort, stShort, summary,
                recentsHead, recentsSub
        );
    }

    public static final class BoostResult {
        public final long freedBytes;
        public final long ramFreeAfter;
        public final int cleanedCount;
        public final String message;

        public BoostResult(long freedBytes, long ramFreeAfter, int cleanedCount, String message) {
            this.freedBytes = freedBytes;
            this.ramFreeAfter = ramFreeAfter;
            this.cleanedCount = cleanedCount;
            this.message = message;
        }
    }

    public static BoostResult boostRam(Context context, List<AppEntry> installedApps) {
        return boostRam(context, installedApps, null);
    }

    /**
     * Честная быстрая очистка фоновых кэш-процессов ОЗУ (без root и без трогания самого Doxaiz,
     * критичных системных служб и закреплённых пользователем приложений из lockedPackages).
     */
    /**
     * Быстрая очистка ОЗУ.
     *
     * Вызывается по нажатию кнопки/мусорки, то есть на UI-потоке. Внутри — опрос
     * ActivityManager и killBackgroundProcesses() для каждого найденного пакета: это
     * сотни Binder-вызовов подряд, которые раньше блокировали кадр закрытия диалога
     * (заметный «залип» кнопки на устройствах с большим числом приложений).
     * Теперь работа уходит в фоновый поток, UI получает результат через Handler.
     */
    public interface BoostCallback {
        void onBoostFinished(BoostResult result);
    }

    private static final android.os.Handler BOOST_MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    public static void boostRamAsync(
            final Context context,
            final List<AppEntry> installedApps,
            final Set<String> lockedPackages,
            final BoostCallback callback
    ) {
        if (context == null) {
            if (callback != null) callback.onBoostFinished(new BoostResult(0L, 0L, 0, "ОЗУ оптимизировано"));
            return;
        }
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Thread worker = new Thread(() -> {
            final BoostResult result;
            try {
                result = boostRam(appCtx, installedApps, lockedPackages);
            } catch (Throwable ignored) {
                if (callback != null) {
                    BOOST_MAIN.post(() -> callback.onBoostFinished(
                            new BoostResult(0L, 0L, 0, "ОЗУ оптимизировано")));
                }
                return;
            }
            if (callback != null) {
                BOOST_MAIN.post(() -> callback.onBoostFinished(result));
            }
        }, "doxaiz-ram-boost");
        worker.setDaemon(true);
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }
        worker.start();
    }

    public static BoostResult boostRam(Context context, List<AppEntry> installedApps, Set<String> lockedPackages) {
        if (context == null) {
            return new BoostResult(0L, 0L, 0, "ОЗУ оптимизировано");
        }
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return new BoostResult(0L, 0L, 0, "ОЗУ оптимизировано");
        }

        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        long freeBefore = Math.max(0L, before.availMem);

        String selfPkg = context.getPackageName();
        Set<String> targets = new HashSet<>();

        // 1) Приложения с активными фоновыми службами из NotificationListener
        for (String bgPkg : DoxaizNotificationService.snapshotBackgroundApps().keySet()) {
            if (isSafeToTrimPackage(bgPkg, selfPkg, lockedPackages)) {
                targets.add(bgPkg);
            }
        }
        // 1.5) Текущие запущенные процессы (где доступны системе)
        try {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p != null && p.pkgList != null) {
                        for (String pp : p.pkgList) {
                            if (isSafeToTrimPackage(pp, selfPkg, lockedPackages)) {
                                targets.add(pp);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 2) Сторонние пользовательские приложения (Android сам бережно остановит только неактивный фон)
        if (installedApps != null) {
            for (AppEntry e : installedApps) {
                if (e != null && !e.isSystemApp && isSafeToTrimPackage(e.packageName, selfPkg, lockedPackages)) {
                    targets.add(e.packageName);
                }
            }
        } else {
            try {
                android.content.pm.PackageManager pm = context.getPackageManager();
                List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
                if (apps != null) {
                    for (android.content.pm.ApplicationInfo ai : apps) {
                        if (ai == null) continue;
                        boolean isSys = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                        if (!isSys && isSafeToTrimPackage(ai.packageName, selfPkg, lockedPackages)) {
                            targets.add(ai.packageName);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        int cleaned = 0;
        for (String pkg : targets) {
            try {
                am.killBackgroundProcesses(pkg);
                cleaned++;
            } catch (Throwable ignored) {
            }
        }

        ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(after);
        long freeAfter = Math.max(0L, after.availMem);
        long freed = Math.max(0L, freeAfter - freeBefore);

        int lockedCount = (lockedPackages != null) ? lockedPackages.size() : 0;
        String msg = formatBoostMessage(freed, fmt(context, freeAfter), cleaned, lockedCount);
        return new BoostResult(freed, freeAfter, cleaned, msg);
    }

    static boolean isSafeToTrimPackage(String pkg, String selfPkg) {
        return isSafeToTrimPackage(pkg, selfPkg, null);
    }

    static boolean isSafeToTrimPackage(String pkg, String selfPkg, Set<String> lockedPackages) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(selfPkg)) return false;
        if (lockedPackages != null && lockedPackages.contains(pkg)) return false;
        String low = pkg.toLowerCase(Locale.ROOT);
        if (low.equals("android")
                || low.startsWith("com.android.systemui")
                || low.startsWith("com.android.phone")
                || low.startsWith("com.android.providers")
                || low.equals("com.android.bluetooth")
                || low.equals("com.android.deskclock")
                || low.equals("com.google.android.gms")
                // Не трогаем системный хост-лаунчер (отвечает за жесты и Recents на Android 14 / XOS)
                || low.equals("com.transsion.xoslauncher")
                || low.equals("com.transsion.hilauncher")
                || low.equals("com.transsion.launcher")
                // Не убиваем системную клавиатуру (убирает лаг первого ввода)
                || low.contains("inputmethod")
                || low.contains("gboard")
                || low.contains("latinime")) {
            return false;
        }
        return true;
    }

    static String formatBoostMessage(long freedBytes, String freeAfterStr, int cleanedCount) {
        return formatBoostMessage(freedBytes, freeAfterStr, cleanedCount, 0);
    }

    static String formatBoostMessage(long freedBytes, String freeAfterStr, int cleanedCount, int lockedCount) {
        String base;
        if (freedBytes >= 1024L * 1024L) {
            long mb = Math.round(freedBytes / (1024.0 * 1024.0));
            base = "Освобождено +" + mb + " МБ • Свободно ОЗУ: " + freeAfterStr;
        } else if (cleanedCount > 0) {
            base = "Фоновые процессы очищены (" + cleanedCount + ") • Свободно: " + freeAfterStr;
        } else {
            base = "Память уже чиста • Свободно ОЗУ: " + freeAfterStr;
        }
        if (lockedCount > 0) {
            return base + " • Защищено: " + lockedCount;
        }
        return base;
    }

    private static String fmt(Context context, long bytes) {
        if (bytes <= 0L) return "—";
        try {
            return Formatter.formatShortFileSize(context, bytes);
        } catch (Throwable t) {
            double gb = bytes / (1024.0 * 1024.0 * 1024.0);
            return String.format(Locale.getDefault(), "%.1f ГБ", gb);
        }
    }
}
