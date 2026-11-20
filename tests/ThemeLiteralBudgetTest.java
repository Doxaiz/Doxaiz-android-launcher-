package com.doxaiz.launcher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Храповик на тёмные литералы в UI-коде.
 *
 * <h3>Зачем</h3>
 * Светлая тема трижды оказывалась «завершённой» в README и незавершённой на
 * экране: §37 (шторка), §40 (каталог и диалоги), §41 (настройки и модальные
 * окна). Причина каждый раз одна — цвет поверхности задан числом прямо в коде,
 * а не взят из палитры. Такой литерал не зависит от темы: в светлой теме он
 * выглядит дефектом, в тёмной — нормой, и при разработке в тёмной его не видно.
 *
 * <p>Пиксельная самопроверка (§39–§41) ловит это на трёх экранах, но снимает
 * только их: шторка, каталог, настройки. Всё остальное — панели звука, плашка
 * уведомления, окно активного приложения, экран блокировки, плавающая капсула —
 * на эмуляторе не открывается. Этот тест закрывает остальное дешёвым способом:
 * он не смотрит на экран, а проверяет, что в UI-коде не появляется <b>новых</b>
 * тёмных заливок литералом.
 *
 * <h3>Что считается нарушением</h3>
 * Вызов заливки поверхности ({@code makeRoundedRect}, {@code makeCircle},
 * {@code makePill}, {@code makeGlassRect}, {@code setBackgroundColor},
 * {@code setColor}) с литералом цвета, яркость которого ниже порога, а
 * прозрачность выше 0x40 (полупрозрачный скрим законен — он и должен затемнять).
 * Цвет текста и иконок здесь не проверяется: там литерал бывает осознанным
 * (белый поверх акцентной заливки), и это ловит {@link ContrastTest}.
 */
public final class ThemeLiteralBudgetTest {

    private static final String[] UI_FILES = {
            "MainActivity.java",
            "QuickShadeHelper.java",
            "HeadsUpBannerHelper.java",
            "SoundPanelHelper.java",
            "DynamicBarHelper.java",
            "DoxaizLockService.java",
    };

    /**
     * Бюджет тёмных заливок литералом по файлам. Только уменьшать.
     *
     * На момент написания (§43) осталось три осознанных места, каждое с
     * объяснением в коде:
     * <ul>
     *   <li>DynamicBarHelper — «вырез камеры» в макете капсулы: это имитация
     *       дырки в корпусе телефона, а не элемент темы; цвет уже зависит от
     *       LIGHT_SURFACES, и литерал стоит только в тёмной ветке тернарника;</li>
     *   <li>MainActivity — скрим 0x66000000 под карточкой батареи: он обязан
     *       затемнять то, что под ним;</li>
     *   <li>VectorGlyphs — «вырезанная» буква B/F/A внутри нарисованного
     *       фонарика: это часть пиктограммы, её цвет не зависит от темы.</li>
     * </ul>
     */
    private static final Object[][] BUDGET = {
            {"MainActivity.java", 1},
            {"DynamicBarHelper.java", 1},
            {"VectorGlyphs.java", 1},
            // QuickShadeHelper, SoundPanelHelper, HeadsUpBannerHelper,
            // DoxaizLockService — ноль: были тёмные литералы, переведены на
            // SHADE_CARD / SHADE_TILE_OFF / SURFACE_ELEVATED / ACCENT_*.
    };

    /** Яркость (0..255) ниже этого значения считается «тёмной заливкой». */
    private static final int DARK_LUMA = 70;

    /** Минимальная прозрачность, при которой литерал считается заливкой. */
    private static final int MIN_ALPHA = 0x40;

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) throws Exception {
        File dir = sourceDir();
        check(dir != null, "каталог с исходниками не найден");

        int total = 0;
        StringBuilder report = new StringBuilder();
        for (String name : UI_FILES) {
            File f = new File(dir, name);
            if (!f.isFile()) continue;
            List<Integer> darkLines = darkFills(read(f));
            int budget = budgetFor(name);
            total += darkLines.size();
            check(darkLines.size() <= budget, "тёмных заливок литералом стало больше в "
                    + name + ": " + darkLines.size() + " при бюджете " + budget
                    + " (строки " + darkLines + ") — цвет поверхности нужно брать из палитры");
            if (!darkLines.isEmpty()) {
                report.append(name).append(": ").append(darkLines).append("; ");
            }
        }
        System.out.println("ТЁМНЫЕ_ЗАЛИВКИ_ЛИТЕРАЛОМ=" + total
                + (report.length() > 0 ? (" [" + report + "]") : ""));
        System.out.println("THEME_LITERAL_TESTS=" + n);
    }

    private static int budgetFor(String name) {
        for (Object[] row : BUDGET) {
            if (row[0].equals(name)) return (Integer) row[1];
        }
        return 0;
    }

    /**
     * Ищет вызовы заливки с литералом и тёмным цветом.
     *
     * Разбор намеренно простой (по строкам, без парсера): задача — не понять код,
     * а не пропустить появление новой тёмной заливки. Поэтому наличие
     * {@code GlassTheme.} в той же строке нарушением не считается: тернарник
     * {@code LIGHT_SURFACES ? светлый : тёмный} — законный способ.
     */
    static List<Integer> darkFills(String src) {
        List<Integer> lines = new ArrayList<>();
        String[] rows = src.split("\n");
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i];
            if (!isFillCall(row)) continue;
            if (row.contains("SLIDER_TRACK")) continue;
            for (String lit : aarrggbbLiterals(row)) {
                int v = (int) Long.parseLong(lit, 16);
                int alpha = (int) ((v >>> 24) & 0xFF);
                if (alpha < MIN_ALPHA) continue;
                int r = (v >> 16) & 0xFF;
                int g = (v >> 8) & 0xFF;
                int b = v & 0xFF;
                double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                if (luma < DARK_LUMA) {
                    lines.add(i + 1);
                    break;
                }
            }
        }
        return lines;
    }

    private static boolean isFillCall(String row) {
        return row.contains("makeRoundedRect(") || row.contains("makeCircle(")
                || row.contains("makePill(") || row.contains("makeGlassRect(")
                || row.contains("setBackgroundColor(") || row.contains(".setColor(");
    }

    /**
     * Все литералы вида 0xAARRGGBB в строке.
     *
     * Берутся литералы с любой прозрачностью, а не только {@code 0xFF...}:
     * первая версия этого теста искала именно {@code 0xFF} и поэтому не видела
     * скрим {@code 0x66000000} под карточкой батареи — то есть ровно тот случай,
     * ради которого проверка и нужна.
     */
    private static List<String> aarrggbbLiterals(String row) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 10 <= row.length(); i++) {
            if (row.charAt(i) != '0' || row.charAt(i + 1) != 'x') continue;
            String hex = row.substring(i + 2, i + 10);
            if (!hex.matches("[0-9A-Fa-f]{8}")) continue;
            out.add(hex);
            i += 9;
        }
        return out;
    }

    private static File sourceDir() {
        String[] candidates = {
                "src/main/java/com/doxaiz/launcher",
                "app/src/main/java/com/doxaiz/launcher",
                "../app/src/main/java/com/doxaiz/launcher",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isDirectory()) return f;
        }
        return null;
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
