package com.doxaiz.launcher;

/**
 * Читаемость поверх обоев: контраст по WCAG 2.1.
 *
 * Проверяется то, что реально видел пользователь как дефект: белые подписи
 * рабочего стола на светлых системных обоях. Раньше фон-вью при системных обоях
 * пряталось целиком, а подпись шла без подложки — контраст 1,2:1 при пороге
 * 4,5:1.
 *
 * Здесь фиксируются четыре вещи:
 *  1. математика контраста считается правильно (известные пары);
 *  2. скрим поверх системных обоев даёт не хуже 4,5:1 даже на белой картинке;
 *  3. подобранная подложка под подпись действительно проходит порог, а не
 *     «выглядит нормально на глаз»;
 *  4. пары цветов самих палитр GlassTheme читаемы во всех четырёх стилях и на
 *     всех трёх уровнях непрозрачности. Именно эти пары были дефектом:
 *     белый текст на активном чипе #2B8BFF давал 3,37:1, «Удалить приложение»
 *     на карточке — 4,11:1, а подсказки в тёмной теме — 2,62:1.
 */
public final class ContrastTest {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    /** Порог с числом в сообщении: при падении сразу видно, сколько вышло. */
    private static void requireAtLeast(double value, double min, String label) {
        if (!(value >= min)) {
            throw new AssertionError(label + ": " + Math.round(value * 100) / 100.0
                    + ":1, нужно не меньше " + min + ":1");
        }
        n++;
    }

    /** Обратная проверка: значение обязано остаться ниже порога. */
    private static void requireBelow(double value, double max, String label) {
        if (!(value < max)) {
            throw new AssertionError(label + ": " + Math.round(value * 100) / 100.0
                    + ":1, ожидалось меньше " + max + ":1");
        }
        n++;
    }

    /**
     * Кладёт слой {@code fg} (ARGB) на слой {@code bg} (ARGB, поверх чёрного) и
     * считает контраст результата с фоном. Так проверяются полупрозрачные цвета
     * палитры — ровно то, что видит пользователь.
     */
    private static double textOn(int fg, int bg) {
        int[] b = flatten(bg, 0, 0, 0);
        int[] f = flatten(fg, b[0], b[1], b[2]);
        return Legibility.contrastRatio(f[0], f[1], f[2], b[0], b[1], b[2]);
    }

    private static int[] flatten(int argb, int br, int bg, int bb) {
        int a = (argb >>> 24) & 0xFF;
        double k = a / 255.0;
        int r = (int) Math.round(((argb >> 16) & 0xFF) * k + br * (1 - k));
        int g = (int) Math.round(((argb >> 8) & 0xFF) * k + bg * (1 - k));
        int b = (int) Math.round((argb & 0xFF) * k + bb * (1 - k));
        return new int[]{r, g, b};
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

        // 5. Палитры GlassTheme. Считаем по настоящим цветам: createResolved()
        // намеренно не трогает Prefs и android.graphics.Color, поэтому работает
        // и в unit-тестах (где Color.argb() вернул бы 0).
        //
        // Стили перебираем напрямую. Сейчас Кибер-Аниме и Сакура достижимы из
        // интерфейса (см. SimpleThemeTest), поэтому это не «запасная» проверка,
        // а условие: новая палитра не попадает в выбор темы, пока не проходит
        // пороги контраста по всем трём уровням непрозрачности.
        // Уровни непрозрачности: 0 — светлый, 1 — обычный, 2 — плотный.
        int[] styles = {
                Prefs.STYLE_CLASSIC_LIGHT, Prefs.STYLE_CYBER_ANIME,
                Prefs.STYLE_CLASSIC_DARK, Prefs.STYLE_SAKURA_JADE};
        String[] styleNames = {"светлая", "кибер-аниме", "графит", "сакура × нефрит"};
        for (int si = 0; si < styles.length; si++) {
            for (int op = 0; op <= 2; op++) {
                GlassTheme t = GlassTheme.createResolved(styles[si], op);
                String where = styleNames[si] + ", непрозрачность " + op;

                // Текст на карточке, подложке и пилюле — порог обычного текста.
                requireAtLeast(textOn(t.textPrimary, t.cardBgColor), 4.5,
                        "основной текст на карточке (" + where + ")");
                requireAtLeast(textOn(t.textSecondary, t.cardBgColor), 4.5,
                        "второстепенный текст на карточке (" + where + ")");
                requireAtLeast(textOn(t.textHint, t.cardBgColor), 4.5,
                        "подсказка на карточке (" + where + ")");
                requireAtLeast(textOn(t.textHint, t.overlayBgColor), 4.5,
                        "подсказка на подложке (" + where + ")");
                requireAtLeast(textOn(t.textHint, t.pillBgColor), 4.5,
                        "подсказка на пилюле (" + where + ")");
                requireAtLeast(textOn(t.textSecondary, t.pillBgColor), 4.5,
                        "второстепенный текст на пилюле (" + where + ")");
                requireAtLeast(textOn(t.textPrimary, t.chipInactiveBg), 4.5,
                        "текст неактивного чипа (" + where + ")");

                // Активный чип: подпись на заливке — это текст, 4,5:1.
                requireAtLeast(textOn(t.chipActiveText, t.chipActiveBg), 4.5,
                        "подпись активного чипа (" + where + ")");
                // Выбранный чип виден не только заливкой: в MainActivity активному
                // чипу ставится рамка chipActiveBorder, и именно она — нетекстовый
                // индикатор выбора. Проверяем её по порогу 3:1 к пилюле.
                // (Саму заливку к пилюле требовать нельзя: чтобы белый текст на ней
                // читался, она должна быть тёмной, а чтобы выделяться на тёмной
                // пилюле — светлой. Коридор есть только у очень узких оттенков,
                // поэтому выбор показываем рамкой.)
                requireAtLeast(textOn(t.chipActiveBorder, t.pillBgColor), 3.0,
                        "рамка активного чипа видна на пилюле (" + where + ")");

                // «Удалить приложение/папку» — красный текст на карточке.
                requireAtLeast(textOn(t.dangerColor, t.cardBgColor), 4.5,
                        "опасное действие на карточке (" + where + ")");
                requireAtLeast(textOn(t.accentColor, t.cardBgColor), 4.5,
                        "акцентный текст на карточке (" + where + ")");
            }
        }

        // 6. Регрессия конкретных дефектов: значения из истории правок.
        // #2B8BFF под белым текстом — 3,37:1; теперь активный чип тёмной темы
        // темнее. Проверяем, что «прежний» цвет действительно не проходит —
        // иначе тест выше ничего не значит.
        requireBelow(Legibility.contrastRatio(255, 255, 255, 0x2B, 0x8B, 0xFF), 4.5,
                "белый на #2B8BFF по-прежнему ниже порога (исторический дефект)");
        // И что новый активный чип эту пару закрывает.
        requireAtLeast(textOn(0xFFFFFFFF, 0xFF2563EB), 4.5, "белый на #2563EB проходит");

        // 7. Светлая шторка. Раньше её поверхности оставались тёмными «до аудита»,
        // то есть светлая тема была незавершённой. Теперь палитра светлая, и это
        // проверяется теми же порогами, что и остальные стили: значения берутся из
        // самой палитры, а не из литералов в тесте. Если кто-то поменяет цвет на
        // «похожий», тест это увидит.
        GlassTheme light = GlassTheme.createResolved(Prefs.STYLE_CLASSIC_LIGHT, 1);
        check(GlassTheme.LIGHT_SURFACES, "светлая палитра помечена как светлая");

        // Поверхности светлые: это и есть суть правки.
        requireBelow(textOn(GlassTheme.SHADE_BG, 0xFFFFFFFF), 3.0,
                "фон шторки близок к белому");
        requireAtLeast(textOn(GlassTheme.SHADE_BG, 0xFF000000), 3.0,
                "фон шторки далеко от чёрного");
        // Подписи на поверхностях — порог обычного текста.
        requireAtLeast(textOn(GlassTheme.TEXT_PRIMARY, GlassTheme.TILE_INACTIVE), 4.5,
                "подпись на неактивной плитке светлой шторки");
        requireAtLeast(textOn(GlassTheme.TEXT_PRIMARY, GlassTheme.TILE_SUB_BG), 4.5,
                "подпись на подложке плитки светлой шторки");
        requireAtLeast(textOn(light.textSecondary, GlassTheme.SHADE_BG), 4.5,
                "второстепенный текст на фоне шторки");
        requireAtLeast(textOn(light.textHint, GlassTheme.SHADE_BG), 4.5,
                "подсказка на фоне шторки");
        // Диалоги лаунчера строятся через createModalBox(), то есть поверхность
        // берётся из палитры (dialogBgColor), а подписи в них оставались белыми
        // литералами. На светлой палитре это 1,0:1 — заголовок просто исчезал.
        requireAtLeast(textOn(GlassTheme.TEXT_PRIMARY, light.dialogBgColor), 4.5,
                "заголовок диалога на его поверхности (светлая тема)");
        requireAtLeast(textOn(GlassTheme.TEXT_MUTED, light.dialogBgColor), 4.5,
                "подпись в диалоге на его поверхности (светлая тема)");
        requireAtLeast(textOn(GlassTheme.ACCENT_BLUE, light.dialogBgColor), 4.5,
                "акцент в диалоге на его поверхности (светлая тема)");
        // Обратная проверка: белый литерал на поверхности диалога не проходит.
        requireBelow(textOn(0xFFFFFFFF, light.dialogBgColor), 4.5,
                "прежний белый заголовок в светлом диалоге был нечитаем");

        // Включённая плитка залита акцентом: подпись на ней белая.
        requireAtLeast(textOn(0xFFFFFFFF, GlassTheme.TILE_ACTIVE_BLUE), 4.5,
                "белая подпись на включённой плитке (синий акцент)");
        requireAtLeast(textOn(0xFFFFFFFF, GlassTheme.TILE_ACTIVE_GREEN), 4.5,
                "белая подпись на включённой плитке (зелёный акцент)");
        // Иконка на неактивной плитке — нетекстовый элемент, порог 3:1.
        requireAtLeast(textOn(GlassTheme.TILE_ACTIVE_BLUE, GlassTheme.TILE_INACTIVE), 3.0,
                "иконка видна на неактивной плитке светлой шторки");
        // Опасное и предупреждающее действия в светлой теме.
        requireAtLeast(textOn(light.dangerColor, GlassTheme.SHADE_BG), 4.5,
                "опасное действие на светлой шторке");
        requireAtLeast(textOn(GlassTheme.ACCENT_AMBER, GlassTheme.SHADE_BG), 4.5,
                "предупреждающий акцент на светлой шторке");

        // Карточки и поля внутри шторки раньше красились литералами #1A1A1E /
        // #1F1F24 / #2E2E34 прямо в QuickShadeHelper — на светлой палитре это
        // давало чёрные карточки на белом фоне. Теперь цвет берётся из темы,
        // и подписи на этих поверхностях обязаны читаться.
        requireAtLeast(textOn(GlassTheme.TEXT_PRIMARY, GlassTheme.SHADE_CARD), 4.5,
                "подпись на карточке светлой шторки");
        requireAtLeast(textOn(GlassTheme.TEXT_PRIMARY, GlassTheme.SHADE_FIELD), 4.5,
                "подпись в поле ввода светлой шторки");
        requireAtLeast(textOn(GlassTheme.TEXT_PRIMARY, GlassTheme.SHADE_TILE_OFF), 4.5,
                "подпись на выключенной круглой плитке");
        requireAtLeast(textOn(GlassTheme.TEXT_SECONDARY, GlassTheme.SHADE_CARD), 4.5,
                "второстепенная подпись на карточке светлой шторки");
        requireAtLeast(textOn(GlassTheme.TEXT_MUTED, GlassTheme.SHADE_CARD), 4.5,
                "подсказка на карточке светлой шторки");
        requireAtLeast(textOn(GlassTheme.TEXT_MUTED, GlassTheme.SHADE_FIELD), 4.5,
                "подсказка в поле ввода светлой шторки");
        // Поверхности светлее фона шторки быть не обязаны, но быть тёмными — нет.
        requireAtLeast(textOn(GlassTheme.SHADE_CARD, 0xFF000000), 3.0,
                "карточка шторки не чёрная");
        requireAtLeast(textOn(GlassTheme.SHADE_FIELD, 0xFF000000), 3.0,
                "поле ввода шторки не чёрное");
        // Каталог и диалог страниц. Это та же ошибка, что была в шторке (§37):
        // подписи и иконки каталога были залиты литералами графита
        // (#9EA0A6, #8E9098, #93C5FD, #60A5FA). На светлой палитре они давали
        // 1,63–2,87:1 — то есть в светлой теме каталог был нечитаем, при том что
        // контраст шторки к этому моменту уже был посчитан.
        requireAtLeast(textOn(GlassTheme.TEXT_MUTED, GlassTheme.SHADE_BG), 4.5,
                "подсказка каталога на фоне светлой темы");
        requireAtLeast(textOn(GlassTheme.TEXT_MUTED, GlassTheme.POPUP_BG), 4.5,
                "подпись в диалоге страниц (фон — карточка темы)");
        requireAtLeast(textOn(GlassTheme.ACCENT_BLUE, GlassTheme.POPUP_BG), 4.5,
                "акцентная подпись в диалоге страниц");
        requireAtLeast(textOn(GlassTheme.ACCENT_GREEN, GlassTheme.POPUP_BG), 4.5,
                "метка «Главный» в диалоге страниц");
        // Обратная проверка: прежние литералы действительно не проходили порог.
        // Если кто-то вернёт их обратно, это должно быть видно здесь, а не на
        // экране телефона.
        requireBelow(textOn(0xFF93C5FD, GlassTheme.SHADE_BG), 4.5,
                "прежний литерал #93C5FD в светлой теме был нечитаем");
        requireBelow(textOn(0xFF8E9098, GlassTheme.SHADE_BG), 4.5,
                "прежний литерал #8E9098 в светлой теме был нечитаем");
        requireBelow(textOn(0xFF60A5FA, GlassTheme.SHADE_BG), 4.5,
                "прежний литерал #60A5FA в светлой теме был нечитаем");
        requireBelow(textOn(0xFF9EA0A6, GlassTheme.SHADE_BG), 4.5,
                "прежний литерал #9EA0A6 в светлой теме был нечитаем");

        // Экраны настроек: карточки — светлые (cardBgColor = #FFFFFF), и
        // подписи в них были белыми литералами. 1,0:1 на белом — текст исчезал.
        requireAtLeast(textOn(GlassTheme.TEXT_PRIMARY, light.cardBgColor), 4.5,
                "подпись в карточке настроек (светлая тема)");
        requireBelow(textOn(0xFFFFFFFF, light.cardBgColor), 4.5,
                "прежняя белая подпись в карточке настроек была нечитаема");
        // И ползунок выключенного переключателя: белый thumb на светлой дорожке.
        requireAtLeast(textOn(GlassTheme.SWITCH_OFF, light.cardBgColor), 1.4,
                "выключенный переключатель различим на карточке");

        // Плашка уведомления (heads-up) строится на POPUP_BG темы, то есть в
        // светлой теме она белая. Заголовок и кнопки действий в ней были
        // белыми литералами на тёмной плашке — на светлой это 1,0:1.
        requireAtLeast(textOn(GlassTheme.TEXT_PRIMARY, GlassTheme.POPUP_BG), 4.5,
                "заголовок плашки уведомления (светлая тема)");
        requireAtLeast(textOn(GlassTheme.TEXT_SECONDARY, GlassTheme.POPUP_BG), 4.5,
                "второстепенная подпись плашки уведомления");
        requireAtLeast(textOn(GlassTheme.ACCENT_BLUE_ON_SOFT, GlassTheme.SHADE_TILE_OFF), 4.5,
                "кнопка действия на плашке уведомления");
        // И обратная проверка: основной акцент на светлой подложке не проходит —
        // именно поэтому для подписей на мягкой подложке заведён отдельный цвет.
        requireBelow(textOn(GlassTheme.ACCENT_BLUE, GlassTheme.SHADE_TILE_OFF), 4.5,
                "основной акцент на мягкой подложке был нечитаем (4,09:1)");
        requireBelow(textOn(0xFFFFFFFF, GlassTheme.POPUP_BG), 4.5,
                "прежний белый заголовок плашки уведомления был нечитаем");

        // Опасное действие: белый текст на красном темы.
        requireAtLeast(textOn(0xFFFFFFFF, GlassTheme.ACCENT_RED), 4.5,
                "белая подпись на красной плашке светлой шторки");

        // Тёмные стили после правки не должны «посветлеть»: у них LIGHT_SURFACES
        // обязан оставаться false, иначе белые иконки заменятся на тёмные.
        int[] darkStyles = {Prefs.STYLE_CLASSIC_DARK, Prefs.STYLE_CYBER_ANIME, Prefs.STYLE_SAKURA_JADE};
        for (int st : darkStyles) {
            GlassTheme t = GlassTheme.createResolved(st, 1);
            check(!GlassTheme.LIGHT_SURFACES, "стиль " + st + " остаётся тёмным");
            requireBelow(Legibility.contrastRatio(
                    (GlassTheme.SHADE_BG >> 16) & 0xFF, (GlassTheme.SHADE_BG >> 8) & 0xFF,
                    GlassTheme.SHADE_BG & 0xFF, 0, 0, 0), 3.0,
                    "фон шторки стиля " + st + " тёмный");
            check(t.cardBgColor != 0, "палитра стиля " + st + " построилась");
            // В тёмных стилях поверхности шторки остались тёмными, а основной
            // текст — светлым. Если кто-то перепутает ветки, это всплывёт здесь.
            requireAtLeast(textOn(GlassTheme.TEXT_PRIMARY, GlassTheme.SHADE_CARD), 7.0,
                    "подпись на карточке тёмного стиля " + st);
            requireBelow(textOn(GlassTheme.SHADE_CARD, 0xFF000000), 3.0,
                    "карточка тёмного стиля " + st + " осталась тёмной");
        }

        System.out.println("CONTRAST_TESTS=" + n);
    }
}
