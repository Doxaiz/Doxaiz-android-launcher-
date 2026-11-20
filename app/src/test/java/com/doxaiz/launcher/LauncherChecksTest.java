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
    public void themeLiteralsStayInPalette() {
        // §43: храповик на тёмные заливки литералом. Пиксельная самопроверка
        // видит три экрана; этот тест видит и панель звука, и плашку
        // уведомления, и экран блокировки, которые на эмуляторе не открыть.
        run("ThemeLiteralBudgetTest", () -> ThemeLiteralBudgetTest.main(new String[0]));
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void run(String name, Check check) {
        try {
            check.run();
        } catch (AssertionError e) {
            fail(name + " упал: " + e.getMessage());
        } catch (Exception e) {
            fail(name + " не выполнился: " + e);
        }
    }
}
