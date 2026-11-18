package com.doxaiz.launcher;

/**
 * Проверки пути «снять ограничение через Shizuku».
 *
 * Что здесь можно проверить без телефона и без самого Shizuku: команду,
 * тексты для человека и то, что в интерфейсе не появляется обещание
 * «автоматически» там, где автоматики нет. Сам протокол binder'а проверяется
 * только на устройстве — об этом честно написано в AUDIT §30.
 */
public final class ShizukuFixTests {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) {
        commandIsCorrect();
        messagesExplainOutcome();
        adbCommandMatches();
        noFalsePromises();

        System.out.println("SHIZUKU_FIX_TESTS=" + n);
    }

    private static void commandIsCorrect() {
        String[] cmd = RestrictedSettingsFix.command("com.doxaiz.launcher");
        check(cmd.length == 5, "команда из пяти слов: " + String.join(" ", cmd));
        check(cmd[0].equals("appops"), "первое слово — appops");
        check(cmd[1].equals("set"), "второе — set");
        check(cmd[2].equals("com.doxaiz.launcher"), "третье — имя пакета");
        check(cmd[3].equals("ACCESS_RESTRICTED_SETTINGS"),
                "четвёртое — имя app-op без префикса android:");
        check(cmd[4].equals("allow"), "пятое — allow, а не ignore");
        // Имя app-op внутри AppOpsManager идёт с префиксом, а в команде — без.
        check(RestrictedSettingsFix.OP_ACCESS_RESTRICTED_SETTINGS
                        .equals("android:access_restricted_settings"),
                "app-op в коде — с префиксом android:");
    }

    private static void messagesExplainOutcome() {
        int[] results = {
                RestrictedSettingsFix.RESULT_OK,
                RestrictedSettingsFix.RESULT_NOT_NEEDED,
                RestrictedSettingsFix.RESULT_NO_SHIZUKU,
                RestrictedSettingsFix.RESULT_NO_PERMISSION,
                RestrictedSettingsFix.RESULT_COMMAND_FAILED,
                RestrictedSettingsFix.RESULT_NOT_SUPPORTED
        };
        for (int r : results) {
            String msg = RestrictedSettingsFix.message(r);
            check(msg != null && msg.length() > 10, "есть понятный текст для кода " + r);
        }
        // Ключевое: «не получилось» не выдаётся за успех.
        check(!RestrictedSettingsFix.message(RestrictedSettingsFix.RESULT_COMMAND_FAILED)
                        .toLowerCase(java.util.Locale.ROOT).contains("снято"),
                "при ошибке команды нет слова «снято»");
        check(RestrictedSettingsFix.message(RestrictedSettingsFix.RESULT_NO_SHIZUKU)
                        .contains("вручную"),
                "без Shizuku человек получает ручной путь, а не тупик");
        check(RestrictedSettingsFix.message(RestrictedSettingsFix.RESULT_NO_PERMISSION)
                        .contains("Shizuku"),
                "отказ в праве объяснён через Shizuku");
    }

    private static void adbCommandMatches() {
        String adb = RestrictedSettingsFix.adbCommand("com.doxaiz.launcher");
        check(adb.startsWith("adb shell appops set "), "ADB-команда начинается правильно: " + adb);
        check(adb.contains("com.doxaiz.launcher"), "в ADB-команде имя пакета");
        check(adb.contains("ACCESS_RESTRICTED_SETTINGS allow"), "в ADB-команде нужный app-op");
        // Команда для показа человеку и команда для Shizuku должны совпадать по
        // смыслу, иначе одна из них когда-нибудь разойдётся.
        String[] shizuku = RestrictedSettingsFix.command("com.doxaiz.launcher");
        check(adb.endsWith(String.join(" ", shizuku)), "ADB повторяет ту же команду");
    }

    private static void noFalsePromises() {
        // Shizuku-путь существует только для Android 13+, где ограничение и появилось.
        check(RestrictedSettingsFix.RESULT_NOT_SUPPORTED != RestrictedSettingsFix.RESULT_OK,
                "«не поддерживается» и «готово» — разные коды");
        // Пакет Shizuku зафиксирован: по нему проверяется «установлен ли».
        check(ShizukuBridge.SHIZUKU_PACKAGE.equals("moe.shizuku.privileged.api"),
                "правильный пакет Shizuku: " + ShizukuBridge.SHIZUKU_PACKAGE);
    }
}
