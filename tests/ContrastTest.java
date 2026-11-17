package com.doxaiz.launcher;

/**
 * Читаемость поверх обоев: контраст по WCAG 2.1.
 *
 * Проверяется то, что реально видел пользователь как дефект: белые подписи
 * рабочего стола на светлых системных обоях. Раньше фон-вью при системных обоях
 * пряталось целиком, а подпись шла без подложки — контраст 1,2:1 при пороге
 * 4,5:1.
 *
 * Здесь фиксируются три вещи:
 *  1. математика контраста считается правильно (известные пары);
 *  2. скрим поверх системных обоев даёт не хуже 4,5:1 даже на белой картинке;
 *  3. подобранная подложка под подпись действительно проходит порог, а не
 *     «выглядит нормально на глаз».
 */
public final class ContrastTest {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) {
        // 1. Известные значения WCAG: чёрный/белый = 21:1, одинаковые цвета = 1:1.
        double bw = Legibility.contrastRatio(0, 0, 0, 255, 255, 255);
        check(Math.abs(bw - 21.0) < 0.01, "чёрный на белом ~21:1, получено " + bw);
        double same = Legibility.contrastRatio(120, 130, 140, 120, 130, 140);
        check(Math.abs(same - 1.0) < 0.001, "одинаковые цвета = 1:1, получено " + same);
        double whiteWhite = Legibility.contrastRatio(255, 255, 255, 255, 255, 255);
        check(Math.abs(whiteWhite - 1.0) < 0.001, "белый на белом = 1:1");
        // Симметрия: порядок аргументов не важен.
        check(Math.abs(Legibility.contrastRatio(0, 0, 0, 255, 255, 255)
                - Legibility.contrastRatio(255, 255, 255, 0, 0, 0)) < 0.001,
                "контраст симметричен");

        // Именно этот случай был дефектом: белый текст прямо на белых обоях.
        double onWallpaper = Legibility.contrastRatio(255, 255, 255, 255, 255, 255);
        check(onWallpaper < 4.5, "белый на белых обоях не проходит порог (так и было)");

        // 2. Скрим при системных обоях. Худший случай — белая картинка под ним.
        for (int dim = 0; dim <= 2; dim++) {
            int[] a = AnimeBackdropView.computeScrimAlphas(dim, true);
            check(a.length == 3, "три зоны скрима");
            check(a[1] >= 150, "средняя зона при системных обоях плотнее: " + a[1]);
            // Белый текст поверх скрима (8/10/20) на белых обоях.
            double worst = Legibility.contrastOnLayer(
                    (a[1] << 24) | 0x00080A14, 255, 255, 255, 255, 255, 255);
            check(worst >= 4.5, "скрим dim=" + dim + " даёт " + worst + ":1, нужно 4,5:1");
        }
        // Встроенные обои не должны потемнеть «за компанию».
        int[] builtin = AnimeBackdropView.computeScrimAlphas(Prefs.WALLPAPER_DIM_MEDIUM, false);
        int[] system = AnimeBackdropView.computeScrimAlphas(Prefs.WALLPAPER_DIM_MEDIUM, true);
        check(system[1] > builtin[1], "системные обои притемняются сильнее встроенных");
        check(builtin[1] == 36, "плотность для встроенных обоев не изменилась");

        // 3. Подложка под подпись: подбирается из худшего случая и обязана пройти.
        int alpha = Legibility.requiredBackdropAlpha(8, 10, 20, 255, 255, 255, 4.5, 190);
        check(alpha > 0 && alpha <= 190, "альфа подложки в разумных пределах: " + alpha);
        double labelContrast = Legibility.contrastOnLayer(
                (alpha << 24) | 0x00080A14, 255, 255, 255, 255, 255, 255);
        check(labelContrast >= 4.5, "подпись на подложке: " + labelContrast + ":1");

        // Просили строже — подложка плотнее (монотонность).
        int stricter = Legibility.requiredBackdropAlpha(8, 10, 20, 255, 255, 255, 10.0, 255);
        check(stricter >= alpha, "более строгий порог не даёт более прозрачную подложку");

        // 4. Белый текст на светлой теме (карточки) тоже обязан читаться.
        // Тема CLASSIC_LIGHT: текст 0xFF151922 на карточке 0xFFFFFFFF.
        double lightTheme = Legibility.contrastRatio(0x15, 0x19, 0x22, 255, 255, 255);
        check(lightTheme >= 4.5, "текст светлой темы на карточке: " + lightTheme + ":1");

        System.out.println("CONTRAST_TESTS=" + n);
    }
}
