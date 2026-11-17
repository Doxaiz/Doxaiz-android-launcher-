package com.doxaiz.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.util.List;

/**
 * Аварийный возврат на штатный (системный) лаунчер.
 *
 * <h3>Зачем это нужно</h3>
 * Doxaiz — это HOME-приложение. Если оно назначено лаунчером по умолчанию и
 * падает при старте, пользователь остаётся с чёрным экраном, а кнопка «Домой»
 * снова открывает падающее приложение. Иногда система сама предлагает выбрать
 * другой лаунчер, но рассчитывать на это нельзя. Значит, роль HOME нужно
 * освободить самостоятельно — но только когда сбой действительно повторяется.
 *
 * <h3>Машина состояний</h3>
 * Действие «приложение упало» изнутри упавшего процесса выполнить невозможно,
 * поэтому сбой восстанавливается на следующем запуске:
 *
 * <pre>
 *   запуск → onDesktopLaunchStart()
 *              отметка «запуск идёт» есть и она свежая?  → это было падение
 *              иначе                                      → обычный запуск
 *   рабочий стол показан → onStartupComplete()  → отметка и счётчик стираются
 *   сбой до показа       → отметка осталась      → следующий запуск увидит её
 * </pre>
 *
 * <h3>Политика (сознательно осторожная)</h3>
 * <ul>
 *   <li>Первый обнаруженный сбой — только аварийный экран с объяснением и
 *       кнопками. Ничего не меняется автоматически.</li>
 *   <li><b>Второй сбой подряд</b> — лаунчер сам открывает штатный рабочий стол
 *       (или системный выбор лаунчера), потому что пользователь уже дважды
 *       остался без рабочего стола.</li>
 *   <li>Успешный старт обнуляет счётчик: разовый сбой ничего не ломает.</li>
 *   <li>Штатный лаунчер запоминается, чтобы возвращать именно в него.</li>
 *   <li>Сбой считается только если процесс умер вскоре после старта
 *       ({@link #CRASH_WINDOW_MS}), а не был выгружен системой через час работы.</li>
 * </ul>
 *
 * <h3>Честное ограничение</h3>
 * Android не позволяет приложению «нажать Домой» за пользователя. Роль HOME
 * освобождается через {@link android.app.role.RoleManager} (Android 10+) с
 * диалогом, который показывает система, а на старых версиях — открытием
 * системного экрана выбора лаунчера. Поэтому кнопки на аварийном экране —
 * основной путь, а автоматика работает страховкой.
 */
public final class CrashGuard {

    /** Код запроса для диалога освобождения роли HOME (Android 10+). */
    static final int REQ_RELEASE_ROLE = 2001;

    /** Сколько падений подряд нужно, чтобы автоматически вернуть штатный лаунчер. */
    static final int CRASHES_TO_FALLBACK = 2;

    /**
     * Сбой засчитывается, только если процесс прожил меньше этого времени.
     * Иначе выгрузка приложения системой через час работы выглядела бы как падение.
     */
    static final long CRASH_WINDOW_MS = 60L * 1000L;

    private CrashGuard() {}

    /**
     * Вызывается в самом начале {@link Activity#onCreate} главного экрана.
     *
     * @return 0 — обычный запуск; n &gt; 0 — предыдущий запуск завершился падением
     *         до показа рабочего стола, и это n-й сбой подряд
     */
    public static int onDesktopLaunchStart(Prefs prefs) {
        if (prefs == null) return 0;
        try {
            long now = System.currentTimeMillis();
            long pending = prefs.getCrashGuardPending();
            int result = 0;
            if (pending > 0L) {
                long age = now - pending;
                if (age >= 0L && age <= CRASH_WINDOW_MS) {
                    result = prefs.getCrashGuardStreak() + 1;
                    prefs.setCrashGuardState(result, 0L);
                } else {
                    // Старая отметка: процесс не падал, а был выгружен или
                    // устройство перезагружалось. Счётчик начинаем заново.
                    prefs.setCrashGuardState(0, 0L);
                }
            }
            // Новая отметка «запуск идёт» ставится в любом случае, кроме
            // перехода на аварийный экран: тот уже не запуск рабочего стола.
            if (result == 0) prefs.setCrashGuardPending(now);
            return result;
        } catch (Throwable t) {
            Diagnostics.record("CrashGuard.onDesktopLaunchStart", t);
            return 0;
        }
    }

    /**
     * Рабочий стол построен и показан. Снимает отметку запуска и, если сбой был
     * разовым, обнуляет счётчик.
     *
     * @param keepStreak true, если в этом же запуске уже был обнаружен сбой —
     *                   тогда счётчик сохраняется, чтобы следующий запуск знал
     *                   о повторяющейся проблеме
     */
    public static void onStartupComplete(Prefs prefs, boolean keepStreak) {
        if (prefs == null) return;
        try {
            if (keepStreak) {
                prefs.setCrashGuardPending(0L);
            } else {
                prefs.clearCrashGuardMarks();
            }
        } catch (Throwable t) {
            Diagnostics.record("CrashGuard.onStartupComplete", t);
        }
    }

    /**
     * Пытается вернуть пользователя на штатный лаунчер.
     *
     * Порядок важен: сначала прямой запуск штатного лаунчера (он работает, если
     * роль уже не на нас), затем системный экран выбора домашнего экрана —
     * единственный путь, не зависящий от того, знаем ли мы имя пакета.
     */
    public static boolean switchToSystemLauncher(Context context, Prefs prefs) {
        if (context == null) return false;
        String fallbackPkg = findSystemLauncherPackage(context, prefs);
        if (fallbackPkg != null && prefs != null) {
            try {
                prefs.setCrashGuardFallback(fallbackPkg, System.currentTimeMillis());
            } catch (Throwable ignored) {
            }
        }

        if (fallbackPkg != null) {
            try {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setPackage(fallbackPkg);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(home);
                return true;
            } catch (Throwable t) {
                Diagnostics.record("CrashGuard.openSystemLauncher", t);
            }
        }

        try {
            Intent pick = new Intent(Settings.ACTION_HOME_SETTINGS);
            pick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(pick);
            return true;
        } catch (ActivityNotFoundException e) {
            Diagnostics.record("CrashGuard.homeSettings", e);
        } catch (Throwable t) {
            Diagnostics.record("CrashGuard.homeSettings", t);
        }
        return false;
    }

    /**
     * Открывает настройки самого приложения: после аварии пользователю нужно
     * ровно это — снять роль HOME или очистить данные, не входя в лаунчер.
     */
    public static boolean openAppSettings(Context context) {
        if (context == null) return false;
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", context.getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Diagnostics.record("CrashGuard.appSettings", t);
            return false;
        }
    }

    /**
     * Android 10+: просит систему освободить роль HOME (диалог показывает
     * система). Возвращает true, если диалог удалось запросить.
     */
    public static boolean requestRoleRelease(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 29) return false;
        try {
            android.app.role.RoleManager rm =
                    (android.app.role.RoleManager) activity.getSystemService(Context.ROLE_SERVICE);
            if (rm == null) return false;
            if (!rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) return false;
            if (!rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) return false;
            Intent intent = rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME);
            activity.startActivityForResult(intent, REQ_RELEASE_ROLE);
            return true;
        } catch (Throwable t) {
            Diagnostics.record("CrashGuard.requestRoleRelease", t);
            return false;
        }
    }

    /**
     * Ищет штатный лаунчер: сначала тот, на который уже переключались, потом
     * разрешённый по умолчанию (если это не мы), потом любой чужой HOME.
     *
     * @return имя пакета или null, если других лаунчеров в системе нет
     */
    static String findSystemLauncherPackage(Context context, Prefs prefs) {
        if (context == null) return null;
        String self;
        try {
            self = context.getPackageName();
        } catch (Throwable t) {
            return null;
        }

        String remembered = null;
        if (prefs != null) {
            try {
                remembered = prefs.getCrashGuardFallback();
            } catch (Throwable ignored) {
            }
        }
        if (remembered != null && !remembered.isEmpty() && !remembered.equals(self)
                && isInstalled(context, remembered)) {
            return remembered;
        }

        try {
            PackageManager pm = context.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            // MATCH_DEFAULT_ONLY: интересует пригодный к роли HOME, а не любая
            // активность с этим фильтром.
            ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null
                    && ri.activityInfo.packageName != null
                    && !ri.activityInfo.packageName.equals(self)) {
                return ri.activityInfo.packageName;
            }
        } catch (Throwable t) {
            Diagnostics.record("CrashGuard.resolveDefault", t);
        }

        try {
            PackageManager pm = context.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = pm.queryIntentActivities(home, 0);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(self)) continue;
                    return pkg;
                }
            }
        } catch (Throwable t) {
            Diagnostics.record("CrashGuard.findAnyHome", t);
        }
        return null;
    }

    /** Есть ли в системе другой лаунчер вообще: если нет — возвращаться некуда. */
    public static boolean hasAlternativeLauncher(Context context, Prefs prefs) {
        if (context == null) return false;
        String pkg = findSystemLauncherPackage(context, prefs);
        return pkg != null && !pkg.equals(context.getPackageName());
    }

    /** Настройка «аварийный возврат» в интерфейсе. */
    public static boolean isEnabled(Prefs prefs) {
        return prefs == null || prefs.isCrashGuardEnabled();
    }

    public static void setEnabled(Prefs prefs, boolean enabled) {
        if (prefs == null) return;
        prefs.setCrashGuardEnabled(enabled);
    }

    /** Ручной сброс счётчика падений (кнопки на аварийном экране и в диагностике). */
    public static void resetStreak(Prefs prefs) {
        if (prefs == null) return;
        try {
            prefs.clearCrashGuardMarks();
        } catch (Throwable t) {
            Diagnostics.record("CrashGuard.resetStreak", t);
        }
    }

    private static boolean isInstalled(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
