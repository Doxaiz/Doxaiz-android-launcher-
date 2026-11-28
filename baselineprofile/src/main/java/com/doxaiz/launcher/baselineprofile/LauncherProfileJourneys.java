package com.doxaiz.launcher.baselineprofile;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObjectNotFoundException;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Повторно используемые действия генератора профиля и макробенчмарков. */
final class LauncherProfileJourneys {

    private static final long UI_TIMEOUT_MS = 5_000L;

    private LauncherProfileJourneys() {}

    static UiDevice device() {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    /**
     * Короткое состояние экрана для ошибки профилирования. Эмулятор может
     * показывать системный диалог/штатный HOME вместо лаунчера; без иерархии
     * UI такой сбой выглядит как случайно пропавшая кнопка.
     */
    private static String describeCurrentUi(UiDevice device) {
        StringBuilder result = new StringBuilder("foreground=")
                .append(device.getCurrentPackageName());
        File hierarchy = new File(
                InstrumentationRegistry.getInstrumentation().getContext().getCacheDir(),
                "baseline-profile-ui.xml");
        try {
            device.dumpWindowHierarchy(hierarchy);
            StringBuilder xml = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(hierarchy), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && xml.length() < 1_000) {
                    xml.append(line.trim()).append(' ');
                }
            }
            result.append("; hierarchy=").append(xml);
        } catch (Exception e) {
            result.append("; hierarchy-dump=").append(e.getClass().getSimpleName())
                    .append(':').append(e.getMessage());
        }
        return result.toString();
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
            throw new AssertionError("На рабочем столе не найдена кнопка «Все приложения»; "
                    + describeCurrentUi(device));
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
        try {
            if (!drawerGrid.scrollForward()) {
                throw new AssertionError("Сетка приложений не прокручивается вниз");
            }
            device.waitForIdle(2_000L);
            if (!drawerGrid.scrollBackward()) {
                throw new AssertionError("Сетка приложений не прокручивается обратно вверх");
            }
            device.waitForIdle(2_000L);
        } catch (UiObjectNotFoundException e) {
            throw new AssertionError("Не удалось прокрутить сетку приложений", e);
        }
    }
}
