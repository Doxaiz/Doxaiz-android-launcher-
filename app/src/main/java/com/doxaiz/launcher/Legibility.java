package com.doxaiz.launcher;

/**
 * Читаемость текста поверх обоев: контраст по WCAG 2.1 и подбор подложки.
 *
 * Зачем отдельный класс. Подписи рабочего стола белые, а обои могут быть любыми:
 * при системных обоях фон-вью раньше вообще не рисовал скрим (ранний выход), и
 * белый текст лежал прямо на картинке. На светлых обоях это контраст 1,2:1 при
 * пороге 4,5:1 — подписи практически не читаются.
 *
 * Здесь только чистая математика без Android: её проверяют JVM-тесты
 * (ContrastTest), а решение «какой скрим и какая подложка» принимает вызывающий
 * код (AnimeBackdropView, MainActivity).
 */
public final class Legibility {

    private Legibility() {}

    /** Относительная яркость цвета RGB (0..1) по WCAG 2.1. */
    public static double luminance(int r, int g, int b) {
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
    }

    private static double channel(int c) {
        double v = clamp255(c) / 255.0;
        return (v <= 0.03928) ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int clamp255(int c) {
        return c < 0 ? 0 : (c > 255 ? 255 : c);
    }

    /**
     * Коэффициент контраста двух непрозрачных цветов: от 1:1 до 21:1.
     *
     * Порог WCAG AA для мелкого текста — 4,5:1, для крупного — 3:1. Подписи
     * иконок мелкие (10–12 sp), поэтому ориентир — 4,5:1.
     */
    public static double contrastRatio(int r1, int g1, int b1, int r2, int g2, int b2) {
        double l1 = luminance(r1, g1, b1);
        double l2 = luminance(r2, g2, b2);
        double hi = Math.max(l1, l2);
        double lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** Контраст полупрозрачного слоя (ARGB) поверх непрозрачного фона (RGB). */
    public static double contrastOnLayer(int argb, int bgR, int bgG, int bgB,
                                         int fgR, int fgG, int fgB) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        double k = a / 255.0;
        int mr = (int) Math.round(r * k + bgR * (1 - k));
        int mg = (int) Math.round(g * k + bgG * (1 - k));
        int mb = (int) Math.round(b * k + bgB * (1 - k));
        return contrastRatio(fgR, fgG, fgB, mr, mg, mb);
    }

    /**
     * Минимальная непрозрачность подложки, при которой белый текст даёт не хуже
     * {@code minRatio} на самом светлом фоне {@code worstBg}.
     *
     * Смысл: подписи должны быть читаемы при ЛЮБЫХ обоях, поэтому подложку
     * подбираем не «на глаз», а из худшего случая — белая картинка.
     *
     * @return альфа 0..255 (255 — подложка непрозрачна)
     */
    public static int requiredBackdropAlpha(int bgR, int bgG, int bgB,
                                            int fgR, int fgG, int fgB,
                                            double minRatio, int maxAlpha) {
        int hi = Math.min(255, Math.max(0, maxAlpha));
        for (int a = 0; a <= hi; a++) {
            double k = a / 255.0;
            int mr = (int) Math.round(bgR * k + 255 * (1 - k));
            int mg = (int) Math.round(bgG * k + 255 * (1 - k));
            int mb = (int) Math.round(bgB * k + 255 * (1 - k));
            // Самая светлая точка подложки — белые обои под ней.
            if (contrastRatio(fgR, fgG, fgB, mr, mg, mb) >= minRatio) return a;
        }
        return hi;
    }
}
