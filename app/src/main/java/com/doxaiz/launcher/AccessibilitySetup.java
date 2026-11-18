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
 * На части прошивок (Transsion/Infinix, MIUI, EMUI) такая навигация упирается
 * в диалог «Настройки с ограниченным доступом»: система не даёт менять
 * спецвозможности из чужого приложения, и человек остаётся без объяснения.
 *
 * Теперь порядок такой:
 *   1. экран самой службы ({@code ACTION_ACCESSIBILITY_DETAILS_SETTINGS},
 *      Android 11+) — открывается сразу на нужном переключателе, а не на общем
 *      списке;
 *   2. если прошивка отказала — общий список спецвозможностей;
 *   3. если и это не открылось — настройки приложения, где видно разрешения;
 *   4. в любом случае показываем подсказку словами: что именно включить.
 *
 * Диалог «ограниченный доступ» принадлежит системным настройкам, а не лаунчеру:
 * приложение не может ни отключить его, ни обойти. Поэтому задача кода —
 * не «убрать диалог», а не приводить к нему без нужды и объяснять, что делать.
 */
final class AccessibilitySetup {

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

    /** Адрес настроек самого приложения — последний фолбэк. */
    static Intent appDetailsIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Открывает настройки и говорит, что включить.
     *
     * @param context  контекст активности или службы
     * @param why      короткая причина, зачем это нужно («блокировка экрана»)
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
     * Текст подсказки. Вынесен отдельно, чтобы формулировка была одна и её
     * можно было проверить тестом: человек должен видеть, ЧТО включить.
     */
    static String hint(Context context) {
        String label;
        try {
            label = context.getString(R.string.lock_service_label);
        } catch (Throwable t) {
            label = "Doxaiz — Панель ОЗУ и блокировка";
        }
        return "Включите «" + label + "» в списке служб. Если система пишет "
                + "«Настройки с ограниченным доступом» — это её защита от смены "
                + "спецвозможностей чужим приложением: диалог закрывается кнопкой "
                + "«ОК», после чего переключатель службы нужно включить вручную.";
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
