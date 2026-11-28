package com.doxaiz.launcher.baselineprofile;

import android.graphics.Point;

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
import java.util.LinkedHashSet;
import java.util.Set;

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
            Set<String> packages = new LinkedHashSet<>();
            StringBuilder drawerMatches = new StringBuilder();
            StringBuilder appNodes = new StringBuilder();
            StringBuilder otherLabels = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(hierarchy), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().startsWith("<node ")) continue;
                    String packageName = xmlAttribute(line, "package");
                    String text = xmlAttribute(line, "text");
                    String description = xmlAttribute(line, "content-desc");
                    if (!packageName.isEmpty()) packages.add(packageName);
                    boolean appNode = "com.doxaiz.launcher".equals(packageName);
                    boolean clickable = "true".equals(xmlAttribute(line, "clickable"));
                    if (!appNode && text.isEmpty() && description.isEmpty()) continue;
                    if (appNode && text.isEmpty() && description.isEmpty() && !clickable) continue;

                    String label = !description.isEmpty() ? description : text;
                    String className = xmlAttribute(line, "class");
                    int lastPackageSeparator = className.lastIndexOf('.');
                    if (lastPackageSeparator >= 0) {
                        className = className.substring(lastPackageSeparator + 1);
                    }
                    String node = packageName + "/" + className
                            + "[" + label + "]@" + xmlAttribute(line, "bounds");
                    StringBuilder destination;
                    int maxLength;
                    if (appNode && label.contains("Все приложения")) {
                        destination = drawerMatches;
                        maxLength = 300;
                    } else if (appNode) {
                        destination = appNodes;
                        maxLength = 600;
                    } else {
                        destination = otherLabels;
                        maxLength = 300;
                    }
                    if (destination.length() < maxLength) {
                        if (destination.length() > 0) destination.append(' ');
                        destination.append(node);
                    }
                }
            }
            result.append("; packages=").append(packages)
                    .append("; drawerMatches=").append(drawerMatches)
                    .append("; appNodes=").append(appNodes)
                    .append("; otherLabels=").append(otherLabels);
        } catch (Exception e) {
            result.append("; hierarchy-dump=").append(e.getClass().getSimpleName())
                    .append(':').append(e.getMessage());
        }
        return result.toString();
    }

    private static String xmlAttribute(String node, String name) {
        String key = name + "=" + (char) 34;
        int start = node.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = node.indexOf((char) 34, start);
        return end < 0 ? "" : node.substring(start, end);
    }

    /** Ищет кнопку в полном UI dump, если UiSelector не видит её напрямую. */
    private static Point findDrawerButtonCenter(UiDevice device) {
        File hierarchy = new File(
                InstrumentationRegistry.getInstrumentation().getContext().getCacheDir(),
                "baseline-profile-ui.xml");
        try {
            device.dumpWindowHierarchy(hierarchy);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(hierarchy), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().startsWith("<node ")
                            || !"com.doxaiz.launcher".equals(xmlAttribute(line, "package"))
                            || !"Все приложения".equals(xmlAttribute(line, "content-desc"))) {
                        continue;
                    }
                    String[] coordinates = xmlAttribute(line, "bounds")
                            .replaceAll("[^0-9]+", " ").trim().split(" +");
                    if (coordinates.length == 4) {
                        int left = Integer.parseInt(coordinates[0]);
                        int top = Integer.parseInt(coordinates[1]);
                        int right = Integer.parseInt(coordinates[2]);
                        int bottom = Integer.parseInt(coordinates[3]);
                        return new Point((left + right) / 2, (top + bottom) / 2);
                    }
                }
            }
        } catch (Exception ignored) {
            // The caller emits the complete, bounded UI diagnostic on failure.
        }
        return null;
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
        boolean clicked = false;
        try {
            clicked = openButton.waitForExists(UI_TIMEOUT_MS) && openButton.click();
        } catch (UiObjectNotFoundException ignored) {
            // Try the accessible bounds from the same hierarchy dump below.
        }
        if (!clicked) {
            Point center = findDrawerButtonCenter(device);
            clicked = center != null && device.click(center.x, center.y);
        }
        if (!clicked) {
            throw new AssertionError("Не найдена/не нажата кнопка «Все приложения»; "
                    + describeCurrentUi(device));
        }

        UiObject drawerTitle = device.findObject(
                new UiSelector().text("Все приложения"));
        if (!drawerTitle.waitForExists(UI_TIMEOUT_MS)) {
            throw new AssertionError("После нажатия не открылся каталог приложений; "
                    + describeCurrentUi(device));
        }
        device.waitForIdle(2_000L);

        UiScrollable drawerGrid = new UiScrollable(
                new UiSelector().className("android.widget.GridView"));
        drawerGrid.setAsVerticalList();
        if (!drawerGrid.waitForExists(UI_TIMEOUT_MS)) {
            throw new AssertionError("В каталоге не появился список приложений; "
                    + describeCurrentUi(device));
        }
        try {
            if (!drawerGrid.scrollForward()) {
                throw new AssertionError("Сетка приложений не прокручивается вниз; "
                        + describeCurrentUi(device));
            }
            device.waitForIdle(2_000L);
            if (!drawerGrid.scrollBackward()) {
                throw new AssertionError("Сетка приложений не прокручивается обратно вверх; "
                        + describeCurrentUi(device));
            }
            device.waitForIdle(2_000L);
        } catch (UiObjectNotFoundException e) {
            throw new AssertionError("Не удалось прокрутить сетку приложений; "
                    + describeCurrentUi(device), e);
        }
    }
}
