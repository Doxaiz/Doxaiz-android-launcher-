package com.doxaiz.launcher;

import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Обёртка над существующими «main-наборами» из ../tests.
 *
 * Раньше проверки запускались только вручную через `java com.doxaiz.launcher.X`.
 * Теперь они выполняются обычным `./gradlew test`, а падение любого набора
 * валит сборку — смысл регрессионных тестов наконец реализован.
 */
public class LauncherChecksTest {

    @Test
    public void v52Calculations() {
        run("V52Tests", () -> V52Tests.main(new String[0]));
    }

    @Test
    public void audit54BackupAndIcons() {
        // F-18: набор был исключён из сборки, потому что требовал настоящий
        // org.json. Теперь он в тестовом classpath, и проверки импорта бэкапа
        // (валидация раскладки, лимиты, приватность) снова выполняются.
        run("Audit54Tests", () -> Audit54Tests.main(new String[0]));
    }

    @Test
    public void audit55Calculations() {
        run("Audit55Tests", () -> Audit55Tests.main(new String[0]));
    }

    @Test
    public void simpleThemeSanitizers() {
        run("SimpleThemeTest", () -> SimpleThemeTest.main(new String[0]));
    }

    @Test
    public void crashGuardStateMachine() {
        run("CrashGuardTests", () -> CrashGuardTests.main(new String[0]));
    }

    @Test
    public void diagnosticsJournal() {
        run("DiagnosticsTests", () -> DiagnosticsTests.main(new String[0]));
    }

    @Test
    public void perf56Responsiveness() {
        run("Perf56Tests", () -> Perf56Tests.main(new String[0]));
    }

    @Test
    public void desktopBuildPolicy() {
        // §51.5: условие «не строить стол, пока не пришли приложения» нельзя
        // было проверить ничем, кроме устройства, а на эмуляторе всегда 19
        // приложений. Логика вынесена в чистую функцию и проверяется здесь.
        run("DesktopBuildPolicyTests", () -> DesktopBuildPolicyTests.main(new String[0]));
    }

    @Test
    public void contrastOnUnknownWallpaper() {
        run("ContrastTest", () -> ContrastTest.main(new String[0]));
    }

    @Test
    public void audit60LayoutAndImport() {
        run("Audit60Tests", () -> Audit60Tests.main(new String[0]));
    }

    @Test
    public void drawerGestureRules() {
        run("GestureRulesTests", () -> GestureRulesTests.main(new String[0]));
    }

    @Test
    public void pagerScrollRules() {
        run("PagerRulesTests", () -> PagerRulesTests.main(new String[0]));
    }

    @Test
    public void quickShadeFollowsTheme() {
        run("QuickShadeColorsTest", () -> QuickShadeColorsTest.main(new String[0]));
    }

    @Test
    public void desktopTileRefresh() {
        run("DesktopTileRefreshTests", () -> DesktopTileRefreshTests.main(new String[0]));
    }

    @Test
    public void customLabelsStorage() {
        run("CustomLabelsTests", () -> CustomLabelsTests.main(new String[0]));
    }

    @Test
    public void layoutSaveSnapshot() {
        run("LayoutSaveTests", () -> LayoutSaveTests.main(new String[0]));
    }

    @Test
    public void restrictedSettingsFix() {
        run("ShizukuFixTests", () -> ShizukuFixTests.main(new String[0]));
    }

    @Test
    public void silentCatchBudget() {
        // F-07: храповик на число «немых» catch. Тест читает исходники, поэтому
        // его задача — не пустить рост, а не доказать, что catch нет.
        run("SilentCatchBudgetTest", () -> SilentCatchBudgetTest.main(new String[0]));
    }

    @Test
    public void networkQualityRules() {
        run("NetworkQualityTests", () -> NetworkQualityTests.main(new String[0]));
    }

    @Test
    public void backupRollbackIsHonest() {
        // F-21: откат неудачного импорта больше не пишет preferences через
        // commit() на главном потоке и не прячет сбой в пустом catch. Тест
        // проверяет три исхода импорта и то, что откат действительно возвращает
        // настройки к снимку.
        run("BackupRollbackTest", () -> BackupRollbackTest.main(new String[0]));
    }

    @Test
    public void recentsCacheIsSafe() {
        // Панель «Активные приложения» открывается двумя жестами и кликом по ОЗУ,
        // и на каждый вызов разбирался JSON до 200 записей «скрытых из Недавних».
        // Тест доказывает счётчиком чтений, что разбор стал однократным, и что
        // правила отбора при этом не изменились.
        run("RecentsCacheTest", () -> RecentsCacheTest.main(new String[0]));
    }

    @Test
    public void appEntryKeysAreObservableInJvm() {
        // §46: тест на AppEntry «проходил», ничего не проверяя, потому что
        // ComponentName в юнит-тестовом android.jar — заглушка. Здесь факт
        // печатается прямо в отчёт JUnit (он виден в артефакте CI), чтобы при
        // разборе следующего зелёного прогона было видно, на каком наборе он
        // получен. Падением это не делается: неразличимость ключей в JVM —
        // свойство среды, а не дефект кода.
        AppEntry probe = new AppEntry(new android.content.ComponentName("com.a", "com.a.Main"),
                null, 0L, "com.a", null, true, true, 0, 0L);
        System.out.println("APP_ENTRY_КЛЮЧ_В_JVM=" + probe.key
                + " packageName=" + (probe.packageName == null ? "<null>" : "'" + probe.packageName + "'"));
    }

    @Test
    public void themeLiteralsStayInPalette() {
        // §43: храповик на тёмные заливки литералом. Пиксельная самопроверка
        // видит три экрана; этот тест видит и панель звука, и плашку
        // уведомления, и экран блокировки, которые на эмуляторе не открыть.
        run("ThemeLiteralBudgetTest", () -> ThemeLiteralBudgetTest.main(new String[0]));
    }

    /**
     * Правило «набор обязан сообщить число проверок» проверяется на строках,
     * а не на реальном наборе: иначе проверка правила зависела бы от того,
     * сколько проверок сегодня в конкретном файле.
     */
    @Test
    public void reportedChecksContract() {
        if (reportedChecks("Audit60Tests: 12 проверок пройдено") != 0) {
            fail("текст без токена не должен считаться доказательством");
        }
        if (reportedChecks("V52_TESTS=17") != 17) {
            fail("токен ИМЯ=<число> должен читаться");
        }
        if (reportedChecks("Промежуточное=3\nИТОГ=42") != 42) {
            fail("берётся последний токен: итог в конце");
        }
        if (reportedChecks("ИТОГ=0") != 0) {
            fail("ноль проверок — не доказательство");
        }
        if (reportedChecks("") != 0) {
            fail("пустой вывод — не доказательство");
        }
        if (reportedChecks(null) != 0) {
            fail("null — не доказательство");
        }
        // Уже многострочный вывод с числом в составной строке тоже годится:
        // наборы печатают маркеры и по ходу дела.
        if (reportedChecks("APP_ENTRY_КЛЮЧИ_В_JVM=/a | /b\nRECENTS_CACHE_TESTS=28") != 28) {
            fail("последний токен с числом побеждает");
        }
    }

    private interface Check {
        void run() throws Exception;
    }

    /**
     * Запускает набор и требует, чтобы он <b>доказал</b> выполнение проверок.
     *
     * <h3>Зачем это изменилось (AUDIT §48)</h3>
     * Раньше здесь был голый {@code check.run()}: успех означал «не бросил
     * исключение». Набор, из которого убрали все проверки, проходил так же
     * зелёным, как и полный, — та же болезнь, что в §47 (тест, зелёный на
     * заглушках), только с другой стороны: зелёный на пустоте.
     *
     * <p>Теперь вывод набора перехватывается, и в нём обязан быть токен
     * {@code ИМЯ=<число>} с положительным числом. Это ровно то, что набор печатает
     * последней строкой, а при нуле проверок он сам бросает исключение — то есть
     * соответствие проверяется с двух сторон.
     */
    private static void run(String name, Check check) {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            fail(name + " не выполнился: " + e);
            return;
        }
        try {
            check.run();
        } catch (AssertionError e) {
            fail(name + " упал: " + e.getMessage());
        } catch (Exception e) {
            fail(name + " не выполнился: " + e);
        } finally {
            System.setOut(original);
        }
        String output = captured.toString();
        // Вывод набора — в отчёт теста: при разборе прогона видно, какие числа
        // получились, а не только «прошло».
        original.print(output);
        int checks = reportedChecks(output);
        if (checks <= 0) {
            fail(name + " не сообщил число выполненных проверок (ожидался токен"
                    + " вида ИМЯ=<число>): набор мог не выполнить ни одной проверки."
                    + " Вывод: " + output.trim());
        }
    }

    /**
     * Число из последнего токена {@code ИМЯ=<число>} в выводе набора.
     *
     * Берётся последний: наборы печатают промежуточные строки, итог — в конце.
     */
    static int reportedChecks(String output) {
        if (output == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:^|\\s)([A-ZА-Я_0-9]{3,})=(\\d+)")
                .matcher(output);
        int last = -1;
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
                // Токен с числом не разобрался — считаем, что доказательства нет.
            }
        }
        return Math.max(last, 0);
    }
}
