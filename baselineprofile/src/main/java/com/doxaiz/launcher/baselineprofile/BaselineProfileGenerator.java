package com.doxaiz.launcher.baselineprofile;

import androidx.benchmark.macro.MacrobenchmarkScope;
import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Генерирует baseline- и startup-профили из реальных действий пользователя.
 *
 * <p>Запуск: {@code ./gradlew :app:generateBaselineProfile}. Так как в
 * {@code :app} задано {@code mergeIntoMain = true}, файлы появляются в
 * {@code app/src/main/generated/baselineProfiles/}. Нужен подключённый
 * эмулятор или устройство.
 */
@RunWith(AndroidJUnit4.class)
public class BaselineProfileGenerator {

    private static final String PACKAGE_NAME = "com.doxaiz.launcher";

    @Rule
    public final BaselineProfileRule rule = new BaselineProfileRule();

    /**
     * В startup-профиль входит только путь до первого экрана.
     *
     * <p>Startup-профиль влияет на раскладку DEX, поэтому в него нельзя
     * включать действия после старта (например, прокрутку каталога): для них
     * остаётся общий baseline-профиль.
     */
    @Test
    public void startup() {
        rule.collect(
                PACKAGE_NAME,
                10,
                5,
                null,
                true,
                (MacrobenchmarkScope scope) -> {
                    scope.pressHome();
                    scope.startActivityAndWait();
                    UiDevice device = LauncherProfileJourneys.device();
                    device.waitForIdle(2_000L);
                    return kotlin.Unit.INSTANCE;
                });
    }

    /** Каталог и прокрутка попадают в baseline, но не в startup-профиль. */
    @Test
    public void drawerJourney() {
        rule.collect(
                PACKAGE_NAME,
                10,
                5,
                null,
                false,
                (MacrobenchmarkScope scope) -> {
                    scope.pressHome();
                    scope.startActivityAndWait();
                    LauncherProfileJourneys.openDrawerAndScroll(
                            LauncherProfileJourneys.device());
                    return kotlin.Unit.INSTANCE;
                });
    }
}
