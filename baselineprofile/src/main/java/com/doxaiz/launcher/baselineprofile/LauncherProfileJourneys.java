package com.doxaiz.launcher.baselineprofile;

import android.graphics.Point;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
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
        if (device.wait(Until.hasObject(By.text("Все приложения")), 500L)) {
            device.pressBack();
            device.waitForIdle(UI_TIMEOUT_MS);
        }
    }

    /** Открывает каталог кнопкой в доке, проверяет экран и прокручивает список. */
    static void openDrawerAndScroll(UiDevice device) {
        ensureDrawerClosed(device);
        boolean clicked = false;
        if (device.wait(Until.hasObject(By.desc("Все приложения")), UI_TIMEOUT_MS)) {
            UiObject2 openButton = device.findObject(By.desc("Все приложения"));
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
        if (!clicked) {
            throw new AssertionError("Не найдена/не нажата кнопка «Все приложения»; "
                    + describeCurrentUi(device));
        }

        if (!device.wait(Until.hasObject(By.text("Все приложения")), UI_TIMEOUT_MS)) {
            throw new AssertionError("После нажатия не открылся каталог приложений; "
                    + describeCurrentUi(device));
        }
        device.waitForIdle(2_000L);

        By gridSelector = By.clazz("android.widget.GridView");
        if (!device.wait(Until.hasObject(gridSelector), UI_TIMEOUT_MS)) {
            throw new AssertionError("В каталоге не появился список приложений; "
                    + describeCurrentUi(device));
        }
        UiObject2 drawerGrid = device.findObject(gridSelector);
        if (drawerGrid == null) {
            throw new AssertionError("Список приложений исчез после ожидания; "
                    + describeCurrentUi(device));
        }
        try {
            if (!drawerGrid.isScrollable()) {
                throw new AssertionError("Список приложений не помечен прокручиваемым; "
                        + describeCurrentUi(device));
            }
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
