package com.doxaiz.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/**
 * Центральная система тем и стилей Doxaiz (без GPU-блюра, 120 FPS):
 * - Сакура × Нефрит (Аниме × Дунхуа, по умолчанию): чернильно-ночной обсидиан, сакура-рубин,
 *   духовный нефрит, золотая луна и восточные уголки свитков сянься.
 * - Кибер-Аниме Неон (Токио / Sci-Fi): глубокий индиго, электрик-циан, сакура-неон, звёздный фиолет и HUD-скобы.
 * - Классический графит: чистый чёрный #000000, графит #242427, синий #2B8BFF и зелёный #22C55E.
 */
public final class GlassTheme {
    // Живые цвета активного стиля (обновляются синхронно при вызове GlassTheme.create(...))
    public static volatile int ACTIVE_STYLE      = Prefs.STYLE_CLASSIC_DARK;
    public static volatile boolean ORNAMENTS_ON  = false;

    public static volatile int SHADE_BG          = 0xFF07080F;
    public static volatile int POPUP_BG          = 0xFF131523;
    public static volatile int SURFACE_ELEVATED  = 0xFF171928;
    public static volatile int TILE_ACTIVE_BLUE  = 0xFFD94680;
    public static volatile int TILE_ACTIVE_GREEN = 0xFF14B8A6;
    public static volatile int TILE_INACTIVE     = 0xFF1A1D2E;
    public static volatile int TILE_SUB_BG       = 0xFF23273D;
    public static volatile int NOTIF_CARD        = 0xFF181B2C;
    public static volatile int SLIDER_TRACK      = 0xFF202438;
    public static volatile int SLIDER_FILL       = 0xFFFFF8EC;
    public static volatile int ACCENT_BLUE       = 0xFFE94D8A;
    public static volatile int ACCENT_GREEN      = 0xFF2DD4BF;
    public static volatile int ACCENT_RED        = 0xFFF43F5E;
    public static volatile int ACCENT_AMBER      = 0xFFF5C451;
    /**
     * Полупрозрачная подложка акцентного действия (плашка «Отменить»,
     * чип стопки, поле быстрого ответа). Раньше эти места были залиты
     * литералом 0x2E2B8BFF — синим графита, поэтому в Сакуре и Кибере
     * плашки оставались чужими по цвету.
     */
    public static volatile int ACCENT_SOFT_BG    = 0x262B8BFF;
    public static volatile int SWITCH_ON         = 0xFF14B8A6;
    public static volatile int SWITCH_OFF        = 0xFF33374E;
    /**
     * Поверхности шторки светлые (тема «Классическая светлая»).
     *
     * Нужен для тех мест, где текст и иконки historically красились белым
     * литералом: на светлой палитре белое на белом не читается. Читается здесь,
     * а не в QuickShadeHelper, чтобы все проверки брали одно значение.
     */
    public static volatile boolean LIGHT_SURFACES  = false;

    /**
     * Основной цвет текста/иконок на поверхностях шторки.
     *
     * В тёмных стилях текст светлый, в светлой палитре — тёмный (#151922).
     * Отдельное статическое поле нужно потому, что шторка и статус-бар рисуются
     * до того, как под рукой окажется экземпляр {@code GlassTheme}.
     */
    /**
     * Поверхность карточек, полей поиска и строк внутри шторки.
     *
     * Исторически эти места красились литералами 0xFF1A1A1E / 0xFF1F1F24 /
     * 0xFF2E2E34 прямо в {@code QuickShadeHelper} — на светлой палитре это
     * давало чёрные карточки на белом фоне. Теперь цвет берётся отсюда.
     */
    public static volatile int SHADE_CARD        = 0xFF1A1A1E;
    /** Поле ввода / неактивная круглая кнопка на шторке. */
    public static volatile int SHADE_FIELD       = 0xFF1F1F24;
    /** Выключенная круглая плитка быстрых действий. */
    public static volatile int SHADE_TILE_OFF    = 0xFF2E2E34;
    public static volatile int TEXT_PRIMARY      = 0xFFF7F4F8;
    public static volatile int TEXT_SECONDARY    = 0xC4DCE0F5;
    public static volatile int TEXT_MUTED        = 0x8AA9B0D0;
    public static volatile int DIVIDER           = 0x24F5C451;
    public static volatile int ORNAMENT_COLOR    = 0x99F5C451;
    public static volatile int ORNAMENT_ACCENT   = 0xCC2DD4BF;
    public static volatile int GOLD_ACCENT       = 0xFFF5C451;
    public static volatile int FOLDER_SCRIM      = 0xE2070914;
    public static volatile int DRAWER_SEARCH_BG  = 0xFF191C2C;
    public static volatile int CARD_BORDER_LIVE  = 0x3DF5C451;

    public final int styleMode;
    public final boolean ornamentsEnabled;
    public final int homeScrimColor;
    public final int overlayBgColor;
    public final int cardBgColor;
    public final int cardBorderColor;
    public final int folderBgColor;
    public final int folderBorderColor;
    public final int pillBgColor;
    public final int pillBorderColor;
    public final int chipInactiveBg;
    public final int chipActiveBg;
    public final int chipActiveBorder;
    public final int chipActiveText;
    public final int dialogBgColor;
    public final int dialogBorderColor;
    public final int dividerColor;
    public final int textPrimary;
    public final int textSecondary;
    public final int textHint;
    public final int accentColor;
    public final int accentSoftBg;
    public final int dotActiveColor;
    public final int dotInactiveColor;
    public final int statusOkColor;
    public final int dangerColor;
    public final int switchTrackOff;
    public final int switchTrackOn;
    public final int switchThumb;
    public final int ornamentColor;
    public final int ornamentAccentColor;
    public final int sectionIconColor;

    private GlassTheme(
            int styleMode,
            boolean ornamentsEnabled,
            int homeScrimColor,
            int overlayBgColor,
            int cardBgColor,
            int cardBorderColor,
            int folderBgColor,
            int folderBorderColor,
            int pillBgColor,
            int pillBorderColor,
            int chipInactiveBg,
            int chipActiveBg,
            int chipActiveBorder,
            int chipActiveText,
            int dialogBgColor,
            int dialogBorderColor,
            int dividerColor,
            int textPrimary,
            int textSecondary,
            int textHint,
            int accentColor,
            int accentSoftBg,
            int dotActiveColor,
            int dotInactiveColor,
            int statusOkColor,
            int dangerColor,
            int switchTrackOff,
            int switchTrackOn,
            int switchThumb,
            int ornamentColor,
            int ornamentAccentColor,
            int sectionIconColor
    ) {
        this.styleMode = styleMode;
        this.ornamentsEnabled = ornamentsEnabled;
        this.homeScrimColor = homeScrimColor;
        this.overlayBgColor = overlayBgColor;
        this.cardBgColor = cardBgColor;
        this.cardBorderColor = cardBorderColor;
        this.folderBgColor = folderBgColor;
        this.folderBorderColor = folderBorderColor;
        this.pillBgColor = pillBgColor;
        this.pillBorderColor = pillBorderColor;
        this.chipInactiveBg = chipInactiveBg;
        this.chipActiveBg = chipActiveBg;
        this.chipActiveBorder = chipActiveBorder;
        this.chipActiveText = chipActiveText;
        this.dialogBgColor = dialogBgColor;
        this.dialogBorderColor = dialogBorderColor;
        this.dividerColor = dividerColor;
        this.textPrimary = textPrimary;
        this.textSecondary = textSecondary;
        this.textHint = textHint;
        this.accentColor = accentColor;
        this.accentSoftBg = accentSoftBg;
        this.dotActiveColor = dotActiveColor;
        this.dotInactiveColor = dotInactiveColor;
        this.statusOkColor = statusOkColor;
        this.dangerColor = dangerColor;
        this.switchTrackOff = switchTrackOff;
        this.switchTrackOn = switchTrackOn;
        this.switchThumb = switchThumb;
        this.ornamentColor = ornamentColor;
        this.ornamentAccentColor = ornamentAccentColor;
        this.sectionIconColor = sectionIconColor;
    }

    public static GlassTheme create(int opacityLevel) {
        return create(ACTIVE_STYLE, opacityLevel, ORNAMENTS_ON);
    }

    public static GlassTheme create(int styleMode, int opacityLevel, boolean ornamentsEnabled) {
        return createResolved(Prefs.sanitizeVisualStyle(styleMode), opacityLevel, ornamentsEnabled);
    }

    /**
     * Вариант по умолчанию: декоративные уголки включены (это же значение
     * возвращает {@code Prefs.isAnimeOrnamentsEnabled()}). Нужен тестам и тем
     * вызовам, которым важен только цвет.
     */
    static GlassTheme createResolved(int safeStyle, int opacityLevel) {
        return createResolved(safeStyle, opacityLevel, true);
    }

    /**
     * То же, что {@link #create(int, int, boolean)}, но принимает уже
     * нормализованный стиль и не трогает {@code Prefs} — поэтому считается
     * и в unit-тестах, где Android-классы недоступны.
     *
     * Видимость package-private: снаружи пользуйтесь {@code create(...)}.
     *
     * <h3>Про ornamentsRequested</h3>
     * Раньше третий аргумент {@code create(...)} молча игнорировался, а внутри
     * стояло жёсткое {@code false} — поэтому декоративные уголки (свитки Дунхуа
     * в Сакуре, HUD-скобы в Кибер-Аниме) не рисовались никогда, хотя для них
     * готово всё: цвета {@code ORNAMENT_*}, {@link AnimeStyleDrawable} и
     * настройка {@code Prefs.isAnimeOrnamentsEnabled()}. Настройка и параметр
     * были мёртвыми, а стили теряли ровно ту деталь, которая отличает их от
     * «просто скруглённого прямоугольника». Теперь запрос уважается; у
     * классического графита уголков нет по определению (там нет и цвета).
     */
    static GlassTheme createResolved(int safeStyle, int opacityLevel, boolean ornamentsRequested) {
        boolean orn = ornamentsRequested && safeStyle != 3;
        // Числа OPACITY_* продублированы: обращение к Prefs здесь сломало бы
        // расчёт в unit-тестах.
        int d = (opacityLevel == 0) ? -34 : (opacityLevel == 2 ? 32 : 0);

        int cardA = clampLocal(220 + d, 156, 250);
        int pillA = clampLocal(226 + d, 164, 252);
        int overlayA = clampLocal(238 + d / 3, 206, 252);

        ACTIVE_STYLE = safeStyle;
        ORNAMENTS_ON = orn;

        // Числа стилей намеренно захардкожены (Prefs.STYLE_* = 3/0/1/2):
        // так расчёт не зависит от Prefs и работает в unit-тестах.
        if (safeStyle == 3) { // STYLE_CLASSIC_LIGHT
            // Светлая шторка: поверхности светлые, значит и подписи на них должны
            // быть тёмными. Раньше здесь оставались тёмные поверхности «до аудита
            // контраста», из-за чего светлая тема выглядела незавершённой (шторка
            // была чёрной). Пороги посчитаны в tests/ContrastTest.java:
            //   белый текст на ACCENT_BLUE #2169CD — 5,29:1,
            //   на ACCENT_GREEN #167746 — 5,58:1,
            //   TEXT_PRIMARY #151922 на TILE_INACTIVE #E6E9EF — 14,46:1,
            //   TEXT_MUTED #5A6675 на SHADE_BG #F2F3F6 — 5,27:1,
            //   TEXT_SECONDARY #4F5968 на SHADE_BG — 6,39:1.
            SHADE_BG = 0xFFF2F3F6; POPUP_BG = 0xFFFFFFFF;
            SURFACE_ELEVATED = 0xFFE8EAF0; TILE_ACTIVE_BLUE = 0xFF2169CD;
            TILE_ACTIVE_GREEN = 0xFF167746; TILE_INACTIVE = 0xFFE6E9EF;
            TILE_SUB_BG = 0xFFDCE3EC; NOTIF_CARD = 0xF2FFFFFF;
            SLIDER_TRACK = 0xFFCAD0D8; SLIDER_FILL = 0xFF2169CD;
            ACCENT_BLUE = 0xFF2169CD; ACCENT_GREEN = 0xFF167746;
            ACCENT_RED = 0xFFBD2737; ACCENT_AMBER = 0xFF8A5A00;
            ACCENT_SOFT_BG = 0x222169CD;
            SWITCH_ON = 0xFF167746; SWITCH_OFF = 0xFFCAD0D8;
            // Подписи: непрозрачные, а не полупрозрачные белые — на светлом фоне
            // 0xB3FFFFFF давал 1,8:1.
            TEXT_SECONDARY = 0xFF4F5968;
            TEXT_MUTED = 0xFF5A6675;
            DIVIDER = 0x1F0B1220; ORNAMENT_COLOR = 0; ORNAMENT_ACCENT = 0;
            GOLD_ACCENT = 0xFF8A5A00; FOLDER_SCRIM = 0xDC050608;
            DRAWER_SEARCH_BG = 0xFFE6E8EC; CARD_BORDER_LIVE = 0x26313744;
            LIGHT_SURFACES = true;
            TEXT_PRIMARY = 0xFF151922; // тот же, что textPrimary светлой палитры
            SHADE_CARD    = 0xFFEDEFF3;
            SHADE_FIELD   = 0xFFE6E9EF;
            SHADE_TILE_OFF = 0xFFDCE3EC;
            return new GlassTheme(
                    safeStyle, false,
                    argb(24, 0, 0, 0),
                    0xFFF2F3F6,
                    0xFFFFFFFF,
                    0xFFD5D9E0,
                    0xE6F3F4F6,
                    0xFFD5D9E0,
                    0xFFE8EAF0,
                    0xFFD5D9E0,
                    0xFFE8EAF0,
                    0xFF2169CD,
                    0xFF2169CD,
                    0xFFFFFFFF,
                    0xFFFAFBFC,
                    0xFFD5D9E0,
                    0xFFDADDE4,
                    0xFF151922,
                    0xFF4F5968,
                    // 0xFF697383 на подложке #F2F3F6 давал 4,32:1, а на пилюле
                    // #E8EAF0 — 3,99:1. 0xFF5A6675 — 5,27 и 4,86:1.
                    0xFF5A6675,
                    0xFF167746,
                    0x22167746,
                    0xFFFFFFFF,
                    argb(86, 255, 255, 255),
                    0xFF167746,
                    0xFFBD2737,
                    0xFFCAD0D8,
                    0xFF167746,
                    0xFFFFFFFF,
                    0, 0, 0xFF2169CD
            );
        }

        if (safeStyle == 1) { // STYLE_CYBER_ANIME
            LIGHT_SURFACES    = false;
            // Кибер: светлый циан-белый на тёмных поверхностях.
            TEXT_PRIMARY      = 0xFFE9FBFF;
            SHADE_CARD        = 0xFF121A33;
            SHADE_FIELD       = 0xFF101A2E;
            SHADE_TILE_OFF    = 0xFF1B2440;
            SHADE_BG          = 0xFF060814;
            POPUP_BG          = 0xFF0E1326;
            SURFACE_ELEVATED  = 0xFF131932;
            TILE_ACTIVE_BLUE  = 0xFF0284C7;
            TILE_ACTIVE_GREEN = 0xFFDB2777;
            TILE_INACTIVE     = 0xFF151C36;
            TILE_SUB_BG       = 0xFF1D2748;
            NOTIF_CARD        = 0xFF121830;
            SLIDER_TRACK      = 0xFF18223D;
            SLIDER_FILL       = 0xFFE6FBFF;
            ACCENT_BLUE       = 0xFF00D4FF;
            ACCENT_GREEN      = 0xFFFF4D8D;
            ACCENT_RED        = 0xFFFF3366;
            ACCENT_AMBER      = 0xFFA855F7;
            ACCENT_SOFT_BG    = 0x2600D4FF;
            SWITCH_ON         = 0xFF00C2FF;
            SWITCH_OFF        = 0xFF283354;
            TEXT_SECONDARY    = 0xC4D4E6FF;
            TEXT_MUTED        = 0xC8A8C4F0;
            DIVIDER           = 0x2600E5FF;
            ORNAMENT_COLOR    = 0x9900E5FF;
            ORNAMENT_ACCENT   = 0xCCFF4D8D;
            GOLD_ACCENT       = 0xFF00E5FF;
            FOLDER_SCRIM      = 0xE6050817;
            DRAWER_SEARCH_BG  = 0xFF121830;
            CARD_BORDER_LIVE  = 0x4500E5FF;

            return new GlassTheme(
                    safeStyle, orn,
                    argb(18, 6, 8, 22),
                    argb(overlayA, 6, 8, 20),
                    argb(cardA, 16, 22, 42),
                    argb(70, 0, 229, 255),
                    argb(clampLocal(206 + d, 148, 244), 14, 20, 40),
                    argb(96, 0, 229, 255),
                    argb(pillA, 18, 25, 48),
                    argb(78, 0, 229, 255),
                    argb(36, 120, 190, 255),
                    // Белый текст на 0xFF0284C7 давал 4,10:1 — ниже порога для
                    // подписи чипа. 0xFF0369A1 — 5,93:1 и тот же бирюзовый тон.
                    0xFF0369A1,
                    0xFF00E5FF,
                    0xFFFFFFFF,
                    argb(252, 12, 16, 32),
                    argb(84, 0, 229, 255),
                    0x2600E5FF,
                    0xFFF8FCFF,
                    0xC4D4E6FF,
                    0xC8A8C4F0,
                    0xFF00D4FF,
                    0x3000D4FF,
                    0xFF00E5FF,
                    argb(92, 180, 225, 255),
                    0xFF00E5FF,
                    0xFFFF3366,
                    0xFF283354,
                    0xFF00C2FF,
                    0xFFFFFFFF,
                    ORNAMENT_COLOR,
                    ORNAMENT_ACCENT,
                    0xFF00E5FF
            );
        }

        if (safeStyle == 2) { // STYLE_CLASSIC_DARK
            LIGHT_SURFACES    = false;
            // Графит: белый текст на чёрном, как было.
            TEXT_PRIMARY      = 0xFFFFFFFF;
            SHADE_CARD        = 0xFF1A1A1E;
            SHADE_FIELD       = 0xFF1F1F24;
            SHADE_TILE_OFF    = 0xFF2E2E34;
            cardA = clampLocal(216 + d, 152, 248);
            pillA = clampLocal(222 + d, 160, 250);
            overlayA = clampLocal(244 + d / 3, 212, 255);
            SHADE_BG          = 0xFF000000;
            POPUP_BG          = 0xFF1C1C1E;
            SURFACE_ELEVATED  = 0xFF1A1A1E;
            TILE_ACTIVE_BLUE  = 0xFF2B8BFF;
            TILE_ACTIVE_GREEN = 0xFF22C55E;
            TILE_INACTIVE     = 0xFF242427;
            TILE_SUB_BG       = 0xFF38383C;
            NOTIF_CARD        = 0xFF2A2A2D;
            SLIDER_TRACK      = 0xFF2C2C2E;
            SLIDER_FILL       = 0xFFFFFFFF;
            ACCENT_BLUE       = 0xFF2B8BFF;
            ACCENT_GREEN      = 0xFF22C55E;
            ACCENT_RED        = 0xFFEF4444;
            ACCENT_AMBER      = 0xFFF59E0B;
            ACCENT_SOFT_BG    = 0x252B8BFF;
            SWITCH_ON         = 0xFF22C55E;
            SWITCH_OFF        = 0xFF3E3E44;
            TEXT_SECONDARY    = 0xB3FFFFFF;
            TEXT_MUTED        = 0x80FFFFFF;
            DIVIDER           = 0x1AFFFFFF;
            ORNAMENT_COLOR    = 0x00000000;
            ORNAMENT_ACCENT   = 0x00000000;
            GOLD_ACCENT       = 0xFFFBBF24;
            FOLDER_SCRIM      = 0xDC050608;
            DRAWER_SEARCH_BG  = 0xFF222226;
            CARD_BORDER_LIVE  = 0x26FFFFFF;

            return new GlassTheme(
                    safeStyle, false,
                    argb(24, 0, 0, 0),
                    argb(overlayA, 0, 0, 0),
                    argb(cardA, 36, 36, 39),
                    argb(38, 255, 255, 255),
                    argb(clampLocal(204 + d, 146, 242), 34, 34, 38),
                    argb(62, 255, 255, 255),
                    argb(pillA, 38, 38, 42),
                    argb(54, 255, 255, 255),
                    argb(30, 255, 255, 255),
                    // 0xFF2B8BFF под белым текстом давал 3,37:1 — ниже порога
                    // 4,5:1 для мелкого текста. 0xFF2563EB даёт 5,17:1 и остаётся
                    // узнаваемо синим.
                    0xFF2563EB,
                    // Рамка светлее заливки: заливка тёмная (под белый текст), а
                    // выбранный чип на пилюле показывает рамка — 5,99–6,69:1.
                    0xFF60A5FA,
                    0xFFFFFFFF,
                    argb(252, 24, 24, 27),
                    argb(46, 255, 255, 255),
                    argb(26, 255, 255, 255),
                    0xFFFFFFFF,
                    argb(190, 214, 214, 220),
                    // 132/255 белого-серого давало 2,62:1 на карточке —
                    // подсказки почти не читались. 0xC0 с более светлым
                    // оттенком — 4,63–5,69:1.
                    0xC0B0B0B8,
                    0xFF22C55E,
                    argb(38, 34, 197, 94),
                    0xFFFFFFFF,
                    argb(86, 255, 255, 255),
                    0xFF22C55E,
                    // 0xFFEF4444 на карточке 36/36/39 давал 4,11:1 — «Удалить
                    // приложение» читалось хуже остальных пунктов меню.
                    // 0xFFF87171 — 5,60:1 на той же карточке.
                    0xFFF87171,
                    argb(64, 255, 255, 255),
                    0xFF22C55E,
                    0xFFFFFFFF,
                    0,
                    0,
                    0xFF60A5FA
            );
        }

        LIGHT_SURFACES    = false;
        // Сакура: почти белый тёплый (argb(252,12,16,32) из палитры).
        TEXT_PRIMARY      = 0xFFF7F8FA;
        SHADE_CARD        = 0xFF1A1A1E;
        SHADE_FIELD       = 0xFF1F1F24;
        SHADE_TILE_OFF    = 0xFF2E2E34;
        // По умолчанию (0): STYLE_SAKURA_JADE — Синтез Аниме × Дунхуа (Ночной сакура-нефрит и золотая луна)
        SHADE_BG          = 0xFF07080F;
        POPUP_BG          = 0xFF131523;
        SURFACE_ELEVATED  = 0xFF171928;
        TILE_ACTIVE_BLUE  = 0xFFD94680;
        TILE_ACTIVE_GREEN = 0xFF14B8A6;
        TILE_INACTIVE     = 0xFF1A1D2E;
        TILE_SUB_BG       = 0xFF23273D;
        NOTIF_CARD        = 0xFF181B2C;
        SLIDER_TRACK      = 0xFF202438;
        SLIDER_FILL       = 0xFFFFF8EC;
        ACCENT_BLUE       = 0xFFE94D8A;
        ACCENT_GREEN      = 0xFF2DD4BF;
        ACCENT_RED        = 0xFFF43F5E;
        ACCENT_AMBER      = 0xFFF5C451;
        ACCENT_SOFT_BG    = 0x26E94D8A;
        SWITCH_ON         = 0xFF14B8A6;
        SWITCH_OFF        = 0xFF33374E;
        TEXT_SECONDARY    = 0xC4DCE0F5;
        TEXT_MUTED        = 0xBCA9B0D0;
        DIVIDER           = 0x24F5C451;
        ORNAMENT_COLOR    = 0x99F5C451;
        ORNAMENT_ACCENT   = 0xCC2DD4BF;
        GOLD_ACCENT       = 0xFFF5C451;
        FOLDER_SCRIM      = 0xE2070914;
        DRAWER_SEARCH_BG  = 0xFF191C2C;
        CARD_BORDER_LIVE  = 0x42F5C451;

        return new GlassTheme(
                safeStyle, orn,
                argb(14, 7, 8, 16),
                argb(overlayA, 7, 8, 16),
                argb(cardA, 22, 24, 38),
                argb(66, 245, 196, 81),
                argb(clampLocal(206 + d, 148, 244), 20, 23, 38),
                argb(88, 245, 196, 81),
                argb(pillA, 24, 27, 44),
                argb(74, 245, 196, 81),
                argb(34, 255, 228, 240),
                // Белый текст на 0xFFD94680 давал 4,09:1. 0xFFC22A6E —
                // 5,45:1, оттенок сакуры сохранён.
                0xFFC22A6E,
                0xFFF5C451,
                0xFFFFFFFF,
                argb(252, 18, 20, 34),
                argb(78, 245, 196, 81),
                0x24F5C451,
                0xFFFFFDF8,
                0xC4DCE0F5,
                0xBCA9B0D0,
                0xFF2DD4BF,
                0x302DD4BF,
                0xFFF5C451,
                argb(92, 255, 238, 210),
                0xFF2DD4BF,
                0xFFF43F5E,
                0xFF33374E,
                0xFF14B8A6,
                0xFFFFFFFF,
                ORNAMENT_COLOR,
                ORNAMENT_ACCENT,
                0xFFF5C451
        );
    }

    /** Возвращает название стиля на русском языке. */
    public static String getStyleTitle(int styleMode) {
        int safe = Prefs.sanitizeVisualStyle(styleMode);
        if (safe == Prefs.STYLE_CYBER_ANIME) return "Кибер-Аниме Неон";
        if (safe == Prefs.STYLE_CLASSIC_DARK) return "Классический графит";
        return "Сакура × Нефрит (Дунхуа)";
    }

    /**
     * Создаёт стилизованную карточку / попап с декоративными уголками свитков (Дунхуа) или HUD-скобами (Кибер-Аниме),
     * либо чистый скруглённый прямоугольник в классическом режиме / при выключенных рамках.
     */
    public static Drawable makeStyledCard(
            Context ctx,
            int fillColor,
            int borderColor,
            float radiusDp,
            float strokeDp,
            boolean withOrnaments
    ) {
        if (withOrnaments && ORNAMENTS_ON && ACTIVE_STYLE != Prefs.STYLE_CLASSIC_DARK) {
            float density = ctx.getResources().getDisplayMetrics().density;
            return new AnimeStyleDrawable(
                    ACTIVE_STYLE,
                    true,
                    fillColor,
                    borderColor,
                    ORNAMENT_COLOR,
                    ORNAMENT_ACCENT,
                    radiusDp * density,
                    strokeDp > 0f ? Math.max(1f, strokeDp * density) : 0f,
                    density
            );
        }
        return makeGlassRect(ctx, fillColor, borderColor, radiusDp, strokeDp);
    }

    /** Удобный конструктор фона для центрированных попап-карточек Шторки. */
    public static Drawable makeStyledPopup(Context ctx, float radiusDp) {
        return makeStyledCard(ctx, POPUP_BG, CARD_BORDER_LIVE, radiusDp,
                ACTIVE_STYLE == Prefs.STYLE_CLASSIC_DARK ? 0f : 1f, true);
    }

    /** Удобный конструктор фона для карточек уведомлений / плеера Шторки. */
    public static Drawable makeStyledSurface(Context ctx, int fillColor, float radiusDp, boolean withOrnaments) {
        boolean styled = ACTIVE_STYLE != Prefs.STYLE_CLASSIC_DARK;
        return makeStyledCard(ctx, fillColor, styled ? CARD_BORDER_LIVE : Color.TRANSPARENT,
                radiusDp, styled ? 0.85f : 0f, withOrnaments);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Копия {@link #clamp(int, int, int)} для чистого расчёта палитры. */
    private static int clampLocal(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /**
     * Копия {@code android.graphics.Color.argb}. Нужна, чтобы расчёт палитры в
     * {@link #createResolved} не зависел от Android-классов и проверялся тестом.
     */
    private static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int dp(Context ctx, float dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable makeGlassRect(
            Context ctx,
            int fillColor,
            int borderColor,
            float radiusDp,
            float strokeDp
    ) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(dp(ctx, radiusDp));
        if (strokeDp > 0f) {
            d.setStroke(Math.max(1, dp(ctx, strokeDp)), borderColor);
        }
        return d;
    }

    public static GradientDrawable makeRoundedRect(int fillColor, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable makeRoundedRect(int fillColor, int radiusPx, int strokeColor, int strokePx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) {
            d.setStroke(strokePx, strokeColor);
        }
        return d;
    }

    public static GradientDrawable makePill(int fillColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(999f);
        return d;
    }

    public static GradientDrawable makeCircle(int fillColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fillColor);
        return d;
    }
}
