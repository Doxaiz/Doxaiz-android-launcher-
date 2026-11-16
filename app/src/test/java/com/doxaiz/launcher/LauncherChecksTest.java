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
    public void audit55Calculations() {
        run("Audit55Tests", () -> Audit55Tests.main(new String[0]));
    }

    @Test
    public void simpleThemeSanitizers() {
        run("SimpleThemeTest", () -> SimpleThemeTest.main(new String[0]));
    }

    @Test
    public void perf56Responsiveness() {
        run("Perf56Tests", () -> Perf56Tests.main(new String[0]));
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
