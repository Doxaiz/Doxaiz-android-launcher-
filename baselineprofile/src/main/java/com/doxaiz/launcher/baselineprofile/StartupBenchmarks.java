package com.doxaiz.launcher.baselineprofile;

import androidx.benchmark.macro.BaselineProfileMode;
import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.FrameTimingMetric;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.StartupTimingMetric;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

/**
 * Сравнивает холодный запуск и прокрутку каталога с профилем и без него.
 *
 * <p>Это диагностический Macrobenchmark. Значения с CI-эмулятора SwiftShader
 * не считаются скоростью на телефоне и не используются как порог прохождения.
 */
@RunWith(AndroidJUnit4.class)
public class StartupBenchmarks {

    private static final String PACKAGE_NAME = "com.doxaiz.launcher";
    private static final int STARTUP_ITERATIONS = 10;
    private static final int DRAWER_ITERATIONS = 5;

    @Rule
    public final MacrobenchmarkRule rule = new MacrobenchmarkRule();

    @Test
    public void coldStartupWithoutCompilation() {
        benchmarkStartup(new CompilationMode.None());
    }

    @Test
    public void coldStartupWithJitButWithoutBaselineProfile() {
        benchmarkStartup(new CompilationMode.Partial(BaselineProfileMode.Disable, 3));
    }

    @Test
    public void coldStartupWithBaselineProfile() {
        benchmarkStartup(new CompilationMode.Partial(BaselineProfileMode.Require, 0));
    }

    @Test
    public void drawerScrollWithoutCompilation() {
        benchmarkDrawer(new CompilationMode.None());
    }

    @Test
    public void drawerScrollWithJitButWithoutBaselineProfile() {
        benchmarkDrawer(new CompilationMode.Partial(BaselineProfileMode.Disable, 3));
    }

    @Test
    public void drawerScrollWithBaselineProfile() {
        benchmarkDrawer(new CompilationMode.Partial(BaselineProfileMode.Require, 0));
    }

    private void benchmarkStartup(CompilationMode compilationMode) {
        rule.measureRepeated(
                PACKAGE_NAME,
                Collections.singletonList(new StartupTimingMetric()),
                compilationMode,
                StartupMode.COLD,
                STARTUP_ITERATIONS,
                scope -> {
                    scope.pressHome();
                    return kotlin.Unit.INSTANCE;
                },
                scope -> {
                    scope.startActivityAndWait();
                    return kotlin.Unit.INSTANCE;
                });
    }

    private void benchmarkDrawer(CompilationMode compilationMode) {
        rule.measureRepeated(
                PACKAGE_NAME,
                Collections.singletonList(new FrameTimingMetric()),
                compilationMode,
                null,
                DRAWER_ITERATIONS,
                scope -> {
                    scope.pressHome();
                    scope.startActivityAndWait();
                    LauncherProfileJourneys.prepareScrollableDrawer(
                            LauncherProfileJourneys.device());
                    return kotlin.Unit.INSTANCE;
                },
                scope -> {
                    UiDevice device = LauncherProfileJourneys.device();
                    LauncherProfileJourneys.openDrawerAndScroll(device);
                    return kotlin.Unit.INSTANCE;
                });
    }
}
