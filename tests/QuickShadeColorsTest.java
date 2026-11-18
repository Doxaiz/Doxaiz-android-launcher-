package com.doxaiz.launcher;

/**
 * Шторка должна следовать активной теме, а не цветам графита.
 *
 * Дефект, который здесь закрыт: акцентные ссылки, кнопки и плашки в шторке
 * были залиты литералами синего графита (0xFF93C5FD, 0xFF60A5FA, 0x2E2B8BFF).
 * До 6.0 это было незаметно, потому что Сакура и Кибер сворачивались в графит;
 * после того как стили стали доступны (AUDIT §27), шторка в них осталась
 * «чужой» по цвету — розовая тема с синими ссылками.
 *
 * Проверяется два свойства:
 *  1. у каждого стиля есть свой цвет подложки акцентного действия, и он не
 *     совпадает с синим графита;
 *  2. подпись акцента читается на этой подложке: WCAG не меньше 3:1
 *     (крупный текст и индикаторы), иначе «действие» в шторке не видно.
 *
 * Поверхности шторки берутся те же, что в коде: SHADE_BG и POPUP_BG палитры.
 */
public final class QuickShadeColorsTest {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) {
        int[] styles = {Prefs.STYLE_CLASSIC_DARK, Prefs.STYLE_CLASSIC_LIGHT,
                Prefs.STYLE_SAKURA_JADE, Prefs.STYLE_CYBER_ANIME};
        String[] names = {"графит", "светлая", "сакура", "кибер"};
        int graphiteSoft = 0;
        for (int i = 0; i < styles.length; i++) {
            GlassTheme t = GlassTheme.createResolved(styles[i], 1);
            check(t != null, "палитра строится: " + names[i]);
            if (styles[i] == Prefs.STYLE_CLASSIC_DARK) {
                graphiteSoft = GlassTheme.ACCENT_SOFT_BG;
                check(GlassTheme.ACCENT_SOFT_BG != 0, "у графита есть подложка акцента");
                continue;
            }
            // У остальных стилей подложка своя: иначе смысла в ней нет.
            check(GlassTheme.ACCENT_SOFT_BG != graphiteSoft,
                    "подложка акцента отличается от графита: " + names[i]);

            // Подпись акцентного действия на подложке должна читаться.
            double onSoft = Legibility.contrastOnLayer(
                    GlassTheme.ACCENT_SOFT_BG,
                    24, 24, 27,
                    (GlassTheme.ACCENT_BLUE >> 16) & 0xFF,
                    (GlassTheme.ACCENT_BLUE >> 8) & 0xFF,
                    GlassTheme.ACCENT_BLUE & 0xFF);
            check(onSoft >= 3.0, "акцент на своей подложке не меньше 3:1 ("
                    + names[i] + "): " + Math.round(onSoft * 100) / 100.0);

            // И сам акцент обязан читаться на поверхностях шторки.
            double onShade = textOn(GlassTheme.ACCENT_BLUE, GlassTheme.SHADE_BG);
            check(onShade >= 3.0, "акцент на фоне шторки не меньше 3:1 ("
                    + names[i] + "): " + Math.round(onShade * 100) / 100.0);
        }

        // Полупрозрачная подложка не должна быть полностью прозрачной: иначе
        // плашка «Отменить» визуально исчезает.
        for (int i = 0; i < styles.length; i++) {
            GlassTheme.createResolved(styles[i], 1);
            int alpha = (GlassTheme.ACCENT_SOFT_BG >>> 24) & 0xFF;
            check(alpha >= 0x18, "подложка акцента заметна (" + names[i]
                    + "), alpha=" + alpha);
        }

        // Белая подпись на плашке действия: заливка полупрозрачная, но текст
        // должен читаться. Порог обычного текста — 4,5:1.
        for (int i = 0; i < styles.length; i++) {
            GlassTheme.createResolved(styles[i], 1);
            double white = Legibility.contrastOnLayer(
                    GlassTheme.ACCENT_SOFT_BG, 24, 24, 27, 255, 255, 255);
            check(white >= 4.5, "белая подпись на плашке действия ("
                    + names[i] + "): " + Math.round(white * 100) / 100.0);
        }

        System.out.println("QUICK_SHADE_COLORS_TESTS=" + n);
    }

    /** Контраст полупрозрачного текста на непрозрачном фоне. */
    private static double textOn(int fg, int bg) {
        int[] b = flatten(bg, 0, 0, 0);
        int[] f = flatten(fg, b[0], b[1], b[2]);
        return Legibility.contrastRatio(f[0], f[1], f[2], b[0], b[1], b[2]);
    }

    private static int[] flatten(int argb, int br, int bg, int bb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return new int[]{
                (int) Math.round((r * a + br * (255 - a)) / 255.0),
                (int) Math.round((g * a + bg * (255 - a)) / 255.0),
                (int) Math.round((b * a + bb * (255 - a)) / 255.0)
        };
    }
}
