package com.doxaiz.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Помощник включения службы специальных возможностей.
 *
 * Зачем отдельный класс. Раньше все пути вели на общий экран «Специальные
 * возможности» ({@code Settings.ACTION_ACCESSIBILITY_SETTINGS}) и дальше
 * человек искал «Doxaiz — Панель ОЗУ и блокировка» в списке из десятка служб.
 * Теперь порядок такой:
 *   1. экран самой службы ({@code ACTION_ACCESSIBILITY_DETAILS_SETTINGS},
 *      Android 11+) — открывается сразу на нужном переключателе;
 *   2. если прошивка отказала — общий список спецвозможностей;
 *   3. если и это не открылось — настройки приложения.
 *
 * Отдельная история — «Настройки с ограниченным доступом». Это штатное
 * ограничение Android 13+, а не диалог прошивки и не ошибка приложения:
 * система запрещает включать спецвозможности приложению, установленному
 * вручную из APK (не через магазин и не через session-based установку).
 * Обходится оно в настройках самого приложения переключателем «Разрешить
 * ограниченные настройки» (на части прошивок — меню из трёх точек вверху).
 * Приложение не может включить это за человека: переключатель специально
 * находится вне досягаемости самого приложения. Поэтому задача кода —
 * привести ровно на нужный экран, назвать точное название пункта и объяснить,
 * что делать дальше. См. {@link #restrictedSettingsIntent(Context)},
 * {@link #restrictedSettingsStep()} и {@link #restrictedSettingsReason()}.
 */
final class AccessibilitySetup {

    /** Пункт в настройках приложения, снимающий ограничение (Android 13+). */
    static final String RESTRICTED_SETTINGS_TITLE = "Разрешить ограниченные настройки";

    private AccessibilitySetup() {
    }

    /** Класс службы, которую нужно включить (для адреса экрана деталей). */
    static ComponentName serviceComponent(Context context) {
        return new ComponentName(context, DoxaizLockService.class.getName());
    }

    /**
     * Адрес экрана «эта служба» в настройках. Для старых версий возвращает
     * null: там отдельного адреса нет, только общий список.
     *
     * Строка действия задана литералом осознанно: константы
     * {@code Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS} нет в compileSdk 34,
     * а значение стабильно с Android 11.
     */
    static Intent serviceDetailsIntent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        Intent intent = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        intent.putExtra("android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME",
                serviceComponent(context).flattenToString());
        // Uri тоже передаём: часть прошивок читает адрес именно отсюда.
        intent.setData(Uri.parse("package:" + serviceComponent(context).flattenToString()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /** Адрес общего списка спецвозможностей. */
    static Intent listIntent() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /** Адрес настроек самого приложения — там находится снятие ограничения. */
    static Intent appDetailsIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Экран, на котором снимается «Настройки с ограниченным доступом».
     *
     * На Android 13+ у этого экрана есть собственный адрес, и он открывается
     * сразу на переключателе — не нужно искать «три точки» в углу, которых на
     * части прошивок нет. Если прошивка адреса не знает, возвращаем обычные
     * настройки приложения: переключатель там же, ниже списка разрешений.
     */
    static Intent restrictedSettingsIntent(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent intent = new Intent("android.settings.APP_RESTRICTED_SETTINGS");
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, context.getPackageName());
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }
        return appDetailsIntent(context);
    }

    /**
     * Сколько шагов осталось до включения службы — для текста на экране.
     *
     * Разделение важнее, чем кажется: человек на скриншоте нажимает на строку
     * службы и получает отказ, потому что ограничение снимается НЕ в этом
     * списке, а в настройках приложения. Один шаг вместо двух — это и есть
     * разница между «работает» и «не работает».
     */
    static String restrictedSettingsStep() {
        return "Шаг 1. Настройки → Приложения → Doxaiz → «" + RESTRICTED_SETTINGS_TITLE + "»\n"
                + "Шаг 2. Специальные возможности → Doxaiz — Панель ОЗУ и блокировка → включить";
    }

    /** Почему система вообще это показывает: факт, а не отговорка. */
    static String restrictedSettingsReason() {
        return "Так поступает Android 13 и новее с приложениями, установленными вручную из APK: "
                + "система запрещает им включать спецвозможности, пока ограничение не снято "
                + "в настройках приложения. Это не ошибка Doxaiz и не настройка прошивки.";
    }

    /**
     * Открывает настройки и говорит, что включить.
     *
     * @param context контекст активности
     * @param why     короткая причина, зачем это нужно («блокировка экрана»)
     * @return true, если хоть один экран настроек удалось открыть
     */
    static boolean open(Context context, String why) {
        Intent details = serviceDetailsIntent(context);
        if (details != null) {
            try {
                context.startActivity(details);
                toast(context, why);
                return true;
            } catch (Throwable ignored) {
                // Прошивка не знает такого адреса — идём на общий список.
            }
        }
        try {
            context.startActivity(listIntent());
            toast(context, why);
            return true;
        } catch (Throwable ignored) {
            try {
                context.startActivity(appDetailsIntent(context));
                return true;
            } catch (Throwable ignoredToo) {
                return false;
            }
        }
    }

    /**
     * Открывает экран снятия ограничения. Это первый шаг, без которого
     * переключатель службы в спецвозможностях остаётся серым.
     */
    static boolean openRestrictedSettings(Context context) {
        try {
            context.startActivity(restrictedSettingsIntent(context));
            Toast.makeText(context, "Включите «" + RESTRICTED_SETTINGS_TITLE + "»",
                    Toast.LENGTH_LONG).show();
            return true;
        } catch (Throwable ignored) {
            try {
                context.startActivity(appDetailsIntent(context));
                Toast.makeText(context, "Прокрутите вниз до «" + RESTRICTED_SETTINGS_TITLE + "»",
                        Toast.LENGTH_LONG).show();
                return true;
            } catch (Throwable ignoredToo) {
                return false;
            }
        }
    }

    /** Текст подсказки после открытия спецвозможностей. */
    static String hint(Context context) {
        String label;
        try {
            label = context.getString(R.string.lock_service_label);
        } catch (Throwable t) {
            label = "Doxaiz — Панель ОЗУ и блокировка";
        }
        return "Включите «" + label + "» в списке служб. Если система пишет "
                + "«Настройки с ограниченным доступом» — сначала снимите ограничение "
                + "в настройках приложения («" + RESTRICTED_SETTINGS_TITLE + "»).";
    }

    private static void toast(Context context, String why) {
        String text = (why == null || why.isEmpty())
                ? hint(context)
                : why + ": включите службу Doxaiz в списке";
        try {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
