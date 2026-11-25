package com.doxaiz.launcher.baselineprofile;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObjectNotFoundException;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;

/** Повторно используемые действия генератора профиля и макробенчмарков. */
final class LauncherProfileJourneys {

    private static final long UI_TIMEOUT_MS = 5_000L;

    private LauncherProfileJourneys() {}

    static UiDevice device() {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    /** Закрывает каталог, если он остался открытым после предыдущей итерации. */
    static void ensureDrawerClosed(UiDevice device) {
        UiObject drawerTitle = device.findObject(
                new UiSelector().text("Все приложения"));
        if (drawerTitle.waitForExists(500L)) {
            device.pressBack();
            device.waitForIdle(UI_TIMEOUT_MS);
        }
    }

    /** Открывает каталог кнопкой в доке, проверяет экран и прокручивает список. */
    static void openDrawerAndScroll(UiDevice device) {
        ensureDrawerClosed(device);
        UiObject openButton = device.findObject(
                new UiSelector().description("Все приложения"));
        if (!openButton.waitForExists(UI_TIMEOUT_MS)) {
            throw new AssertionError("На рабочем столе не найдена кнопка «Все приложения»");
        }
        try {
            if (!openButton.click()) {
                throw new AssertionError("Не удалось нажать кнопку «Все приложения»");
            }
        } catch (UiObjectNotFoundException e) {
            throw new AssertionError("Кнопка каталога исчезла до нажатия", e);
        }

        UiObject drawerTitle = device.findObject(
                new UiSelector().text("Все приложения"));
        if (!drawerTitle.waitForExists(UI_TIMEOUT_MS)) {
            throw new AssertionError("После нажатия не открылся каталог приложений");
        }
        device.waitForIdle(2_000L);

        UiScrollable drawerGrid = new UiScrollable(
                new UiSelector().className("android.widget.GridView"));
        drawerGrid.setAsVerticalList();
        if (!drawerGrid.waitForExists(UI_TIMEOUT_MS)) {
            throw new AssertionError("В каталоге не появился список приложений");
        }
        if (!drawerGrid.scrollForward()) {
            throw new AssertionError("Сетка приложений не прокручивается вниз");
        }
        device.waitForIdle(2_000L);
        if (!drawerGrid.scrollBackward()) {
            throw new AssertionError("Сетка приложений не прокручивается обратно вверх");
        }
        device.waitForIdle(2_000L);
    }
}
