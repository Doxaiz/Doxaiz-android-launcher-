package com.doxaiz.launcher.baselineprofile;

import android.graphics.Point;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

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

    private static int[] parseBounds(String bounds) {
        if (bounds == null || bounds.isEmpty()) return null;
        String[] coordinates = bounds.replaceAll("[^0-9]+", " ").trim().split(" +");
        if (coordinates.length != 4) return null;
        try {
            return new int[]{
                    Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]), Integer.parseInt(coordinates[3])
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Ищет кнопку в полном UI dump, если BySelector не видит её напрямую. */
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
                    int[] bounds = parseBounds(xmlAttribute(line, "bounds"));
                    if (bounds != null) {
                        return new Point((bounds[0] + bounds[2]) / 2,
                                (bounds[1] + bounds[3]) / 2);
                    }
                }
            }
        } catch (Exception ignored) {
            // The caller emits the complete, bounded UI diagnostic on failure.
        }
        return null;
    }

    /**
     * Путь свайпа вверх внутри корневой области лаунчера. Кнопка каталога
     * опциональна и по умолчанию скрыта; свайп с пустой точки домашнего экрана
     * использует стандартное действие лаунчера «Все приложения».
     */
    private static Point[] findHomeSwipePath(UiDevice device) {
        File hierarchy = new File(
                InstrumentationRegistry.getInstrumentation().getContext().getCacheDir(),
                "baseline-profile-ui.xml");
        int[] rootBounds = null;
        long largestArea = 0L;
        try {
            device.dumpWindowHierarchy(hierarchy);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(hierarchy), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().startsWith("<node ")
                            || !"com.doxaiz.launcher".equals(xmlAttribute(line, "package"))) {
                        continue;
                    }
                    int[] bounds = parseBounds(xmlAttribute(line, "bounds"));
                    if (bounds == null) continue;
                    long area = (long) (bounds[2] - bounds[0]) * (bounds[3] - bounds[1]);
                    if (area > largestArea) {
                        largestArea = area;
                        rootBounds = bounds;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to the display bounds below.
        }

        int left;
        int top;
        int right;
        int bottom;
        if (rootBounds != null && rootBounds[2] > rootBounds[0]
                && rootBounds[3] > rootBounds[1]) {
            left = rootBounds[0];
            top = rootBounds[1];
            right = rootBounds[2];
            bottom = rootBounds[3];
        } else {
            int width = device.getDisplayWidth();
            int height = device.getDisplayHeight();
            if (width <= 0 || height <= 0) return null;
            left = 0;
            top = 0;
            right = width;
            bottom = (int) (height * 0.78f);
        }

        int height = bottom - top;
        if (height < 100) return null;
        int x = left + (right - left) / 2;
        int startY = top + (int) (height * 0.90f);
        int endY = top + (int) (height * 0.30f);
        return new Point[]{new Point(x, startY), new Point(x, endY)};
    }

    /** Закрывает каталог, если он остался открытым после предыдущей итерации. */
    static void ensureDrawerClosed(UiDevice device) {
        if (device.wait(Until.hasObject(By.text("Все приложения")), 500L)) {
            device.pressBack();
            device.waitForIdle(UI_TIMEOUT_MS);
        }
    }

    /** Открывает каталог кнопкой, если она включена, иначе штатным свайпом вверх. */
    static void openDrawerAndScroll(UiDevice device) {
        ensureDrawerClosed(device);
        boolean clicked = false;
        BySelector drawerButtonSelector = By.desc("Все приложения").clickable(true);
        if (device.wait(Until.hasObject(drawerButtonSelector), 750L)) {
            UiObject2 openButton = device.findObject(drawerButtonSelector);
            if (openButton != null) {
                try {
                    openButton.click();
                    clicked = true;
                } catch (RuntimeException ignored) {
                    // Узел мог устареть между поиском и жестом; ниже есть tap по UI dump.
                }
            }
        }
        if (!clicked) {
            Point center = findDrawerButtonCenter(device);
            clicked = center != null && device.click(center.x, center.y);
        }

        boolean drawerOpen = clicked
                && device.wait(Until.hasObject(By.text("Все приложения")), 1_500L);
        if (!drawerOpen) {
            Point[] swipePath = findHomeSwipePath(device);
            if (swipePath != null) {
                try {
                    device.swipe(swipePath[0].x, swipePath[0].y,
                            swipePath[1].x, swipePath[1].y, 20);
                } catch (RuntimeException e) {
                    throw new AssertionError("Сбой свайпа вверх для открытия каталога; "
                            + describeCurrentUi(device), e);
                }
                drawerOpen = device.wait(
                        Until.hasObject(By.text("Все приложения")), UI_TIMEOUT_MS);
            }
        }
        if (!drawerOpen) {
            throw new AssertionError("Не удалось открыть каталог: кнопка «Все приложения» "
                    + "отсутствует/не сработала, а свайп вверх не открыл экран; "
                    + describeCurrentUi(device));
        }
        device.waitForIdle(2_000L);

        BySelector gridSelector = By.clazz("android.widget.GridView");
        if (!device.wait(Until.hasObject(gridSelector), UI_TIMEOUT_MS)) {
            throw new AssertionError("В каталоге не появился список приложений; "
                    + describeCurrentUi(device));
        }
        BySelector scrollableGridSelector = By.clazz("android.widget.GridView").scrollable(true);
        if (!device.wait(Until.hasObject(scrollableGridSelector), UI_TIMEOUT_MS)) {
            throw new AssertionError("Сетка приложений не стала прокручиваемой; "
                    + describeCurrentUi(device));
        }
        UiObject2 drawerGrid = device.findObject(scrollableGridSelector);
        if (drawerGrid == null) {
            throw new AssertionError("Прокручиваемая сетка исчезла после ожидания; "
                    + describeCurrentUi(device));
        }
        try {
            // Fling возвращает false и при достижении края списка. Поэтому
            // успешность каждого жеста не трактуем как наличие следующей страницы:
            // выполняем несколько движений в обе стороны, как в Android perf-samples.
            for (int i = 0; i < 2; i++) {
                drawerGrid.fling(Direction.DOWN);
            }
            for (int i = 0; i < 2; i++) {
                drawerGrid.fling(Direction.UP);
            }
            device.waitForIdle(2_000L);
        } catch (RuntimeException e) {
            throw new AssertionError("Сбой жеста прокрутки каталога; "
                    + describeCurrentUi(device), e);
        }
    }
}
