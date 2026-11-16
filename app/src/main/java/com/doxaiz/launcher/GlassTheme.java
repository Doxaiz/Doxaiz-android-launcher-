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
    public static volatile int SWITCH_ON         = 0xFF14B8A6;
    public static volatile int SWITCH_OFF        = 0xFF33374E;
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
        int safeStyle = Prefs.sanitizeVisualStyle(styleMode);
        boolean orn = false;
        int d = (opacityLevel == Prefs.OPACITY_LIGHT) ? -34
                : (opacityLevel == Prefs.OPACITY_DENSE ? 32 : 0);

        int cardA = clamp(220 + d, 156, 250);
        int pillA = clamp(226 + d, 164, 252);
        int overlayA = clamp(238 + d / 3, 206, 252);

        ACTIVE_STYLE = safeStyle;
        ORNAMENTS_ON = orn;

        if (safeStyle == Prefs.STYLE_CLASSIC_LIGHT) {
            // Keep notification shade's legacy dark surfaces until each hardware popup
            // has an individually audited contrast-safe light palette.
            SHADE_BG = 0xFF000000; POPUP_BG = 0xFF1C1C1E;
            SURFACE_ELEVATED = 0xFF1A1A1E; TILE_ACTIVE_BLUE = 0xFF2B8BFF;
            TILE_ACTIVE_GREEN = 0xFF22C55E; TILE_INACTIVE = 0xFF242427;
            TILE_SUB_BG = 0xFF38383C; NOTIF_CARD = 0xFF2A2A2D;
            SLIDER_TRACK = 0xFF2C2C2E; SLIDER_FILL = 0xFFFFFFFF;
            ACCENT_BLUE = 0xFF2B8BFF; ACCENT_GREEN = 0xFF22C55E;
            ACCENT_RED = 0xFFEF4444; ACCENT_AMBER = 0xFFF59E0B;
            SWITCH_ON = 0xFF22C55E; SWITCH_OFF = 0xFF3E3E44;
            TEXT_SECONDARY = 0xB3FFFFFF; TEXT_MUTED = 0x80FFFFFF;
            DIVIDER = 0x1AFFFFFF; ORNAMENT_COLOR = 0; ORNAMENT_ACCENT = 0;
            GOLD_ACCENT = 0xFFFBBF24; FOLDER_SCRIM = 0xDC050608;
            DRAWER_SEARCH_BG = 0xFFE6E8EC; CARD_BORDER_LIVE = 0x26313744;
            return new GlassTheme(
                    safeStyle, false,
                    Color.argb(24, 0, 0, 0),
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
                    Color.WHITE,
                    0xFFFAFBFC,
                    0xFFD5D9E0,
                    0xFFDADDE4,
                    0xFF151922,
                    0xFF4F5968,
                    0xFF697383,
                    0xFF167746,
                    0x22167746,
                    Color.WHITE,
                    Color.argb(86, 255, 255, 255),
                    0xFF167746,
                    0xFFBD2737,
                    0xFFCAD0D8,
                    0xFF167746,
                    Color.WHITE,
                    0, 0, 0xFF2169CD
            );
        }

        if (safeStyle == Prefs.STYLE_CYBER_ANIME) {
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
            SWITCH_ON         = 0xFF00C2FF;
            SWITCH_OFF        = 0xFF283354;
            TEXT_SECONDARY    = 0xC4D4E6FF;
            TEXT_MUTED        = 0x8A8CA8D4;
            DIVIDER           = 0x2600E5FF;
            ORNAMENT_COLOR    = 0x9900E5FF;
            ORNAMENT_ACCENT   = 0xCCFF4D8D;
            GOLD_ACCENT       = 0xFF00E5FF;
            FOLDER_SCRIM      = 0xE6050817;
            DRAWER_SEARCH_BG  = 0xFF121830;
            CARD_BORDER_LIVE  = 0x4500E5FF;

            return new GlassTheme(
                    safeStyle, orn,
                    Color.argb(18, 6, 8, 22),
                    Color.argb(overlayA, 6, 8, 20),
                    Color.argb(cardA, 16, 22, 42),
                    Color.argb(70, 0, 229, 255),
                    Color.argb(clamp(206 + d, 148, 244), 14, 20, 40),
                    Color.argb(96, 0, 229, 255),
                    Color.argb(pillA, 18, 25, 48),
                    Color.argb(78, 0, 229, 255),
                    Color.argb(36, 120, 190, 255),
                    0xFF0284C7,
                    0xFF00E5FF,
                    Color.WHITE,
                    Color.argb(252, 12, 16, 32),
                    Color.argb(84, 0, 229, 255),
                    0x2600E5FF,
                    0xFFF8FCFF,
                    0xC4D4E6FF,
                    0x8A8CA8D4,
                    0xFF00D4FF,
                    0x3000D4FF,
                    0xFF00E5FF,
                    Color.argb(92, 180, 225, 255),
                    0xFF00E5FF,
                    0xFFFF3366,
                    0xFF283354,
                    0xFF00C2FF,
                    Color.WHITE,
                    ORNAMENT_COLOR,
                    ORNAMENT_ACCENT,
                    0xFF00E5FF
            );
        }

        if (safeStyle == Prefs.STYLE_CLASSIC_DARK) {
            cardA = clamp(216 + d, 152, 248);
            pillA = clamp(222 + d, 160, 250);
            overlayA = clamp(244 + d / 3, 212, 255);
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
                    Color.argb(24, 0, 0, 0),
                    Color.argb(overlayA, 0, 0, 0),
                    Color.argb(cardA, 36, 36, 39),
                    Color.argb(38, 255, 255, 255),
                    Color.argb(clamp(204 + d, 146, 242), 34, 34, 38),
                    Color.argb(62, 255, 255, 255),
                    Color.argb(pillA, 38, 38, 42),
                    Color.argb(54, 255, 255, 255),
                    Color.argb(30, 255, 255, 255),
                    0xFF2B8BFF,
                    0xFF60A5FA,
                    Color.WHITE,
                    Color.argb(252, 24, 24, 27),
                    Color.argb(46, 255, 255, 255),
                    Color.argb(26, 255, 255, 255),
                    Color.WHITE,
                    Color.argb(190, 214, 214, 220),
                    Color.argb(132, 160, 160, 168),
                    0xFF22C55E,
                    Color.argb(38, 34, 197, 94),
                    Color.WHITE,
                    Color.argb(86, 255, 255, 255),
                    0xFF22C55E,
                    0xFFEF4444,
                    Color.argb(64, 255, 255, 255),
                    0xFF22C55E,
                    Color.WHITE,
                    0,
                    0,
                    0xFF60A5FA
            );
        }

        // По умолчанию: STYLE_SAKURA_JADE — Синтез Аниме × Дунхуа (Ночной сакура-нефрит и золотая луна)
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
        SWITCH_ON         = 0xFF14B8A6;
        SWITCH_OFF        = 0xFF33374E;
        TEXT_SECONDARY    = 0xC4DCE0F5;
        TEXT_MUTED        = 0x8AA9B0D0;
        DIVIDER           = 0x24F5C451;
        ORNAMENT_COLOR    = 0x99F5C451;
        ORNAMENT_ACCENT   = 0xCC2DD4BF;
        GOLD_ACCENT       = 0xFFF5C451;
        FOLDER_SCRIM      = 0xE2070914;
        DRAWER_SEARCH_BG  = 0xFF191C2C;
        CARD_BORDER_LIVE  = 0x42F5C451;

        return new GlassTheme(
                safeStyle, orn,
                Color.argb(14, 7, 8, 16),
                Color.argb(overlayA, 7, 8, 16),
                Color.argb(cardA, 22, 24, 38),
                Color.argb(66, 245, 196, 81),
                Color.argb(clamp(206 + d, 148, 244), 20, 23, 38),
                Color.argb(88, 245, 196, 81),
                Color.argb(pillA, 24, 27, 44),
                Color.argb(74, 245, 196, 81),
                Color.argb(34, 255, 228, 240),
                0xFFD94680,
                0xFFF5C451,
                Color.WHITE,
                Color.argb(252, 18, 20, 34),
                Color.argb(78, 245, 196, 81),
                0x24F5C451,
                0xFFFFFDF8,
                0xC4DCE0F5,
                0x8AA9B0D0,
                0xFF2DD4BF,
                0x302DD4BF,
                0xFFF5C451,
                Color.argb(92, 255, 238, 210),
                0xFF2DD4BF,
                0xFFF43F5E,
                0xFF33374E,
                0xFF14B8A6,
                Color.WHITE,
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
