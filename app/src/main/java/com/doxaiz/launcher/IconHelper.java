package com.doxaiz.launcher;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;

/**
 * IconHelper (Doxaiz 4.2) — оптическое выравнивание и единый стиль иконок приложений:
 * - Сохраняет 100% оригинальную графику каждого приложения (без буквенных заглушек).
 * - Приводит и AdaptiveIconDrawable, и разнородные legacy-иконки (игры, банки, утилиты)
 *   к строго одинаковому оптическому размеру (92% холста) и единой плавной форме Squircle
 *   с гладким SRC_IN сглаживанием краёв.
 */
public final class IconHelper {

    static final int SHAPE_FULL_BLEED = 1; // Полностью заполненный квадрат / squircle
    static final int SHAPE_ROUND      = 2; // Круглая иконка с прозрачными углами
    static final int SHAPE_FREEFORM   = 3; // Фигурный логотип / нестандартный силуэт

    static final int BACKDROP_DARK  = 0xFF1F212A;
    static final int BACKDROP_LIGHT = 0xFFF2F4F8;
    private static final int EDGE_STROKE_COLOR = 0x1EFFFFFF;

    // 4% отступа с каждой стороны -> ровно 92% оптического заполнения для всех иконок и папок
    static final float ICON_MARGIN_RATIO = 0.04f;

    // Кэш готовой SRC_IN маски Squircle по sizePx (экономит >100 аллокаций Bitmap при прогреве иконок)
    private static final Object MASK_LOCK = new Object();
    private static int cachedMaskSize = -1;
    private static Bitmap cachedMaskBitmap = null;

    private IconHelper() {}

    private static Bitmap getOrCreateSquircleMask(int sizePx, RectF inner) {
        synchronized (MASK_LOCK) {
            if (cachedMaskSize == sizePx && cachedMaskBitmap != null && !cachedMaskBitmap.isRecycled()) {
                return cachedMaskBitmap;
            }
            Bitmap mask = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas mc = new Canvas(mask);
            Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            maskPaint.setColor(Color.WHITE);
            maskPaint.setStyle(Paint.Style.FILL);
            mc.drawPath(buildSquirclePath(inner), maskPaint);
            cachedMaskSize = sizePx;
            cachedMaskBitmap = mask;
            return mask;
        }
    }

    public static void clearMaskCache() {
        synchronized (MASK_LOCK) {
            // Обнуляем ссылку без немедленного recycle(), чтобы не уронить параллельный поток загрузки иконок
            cachedMaskBitmap = null;
            cachedMaskSize = -1;
        }
    }

    public static Drawable normalizeDrawable(Context ctx, Drawable raw) {
        if (raw == null) return defaultActivityIcon(ctx);
        try {
            int targetPx = Math.min(Math.max(GlassTheme.dp(ctx, 64), 160), 192);
            Bitmap normalized = normalizeToUniformSquircle(raw, targetPx);
            return new BitmapDrawable(ctx.getResources(), normalized);
        } catch (Throwable ignored) {
            return raw;
        }
    }

    public static Drawable loadOriginalIcon(Context ctx, LauncherActivityInfo info) {
        Drawable raw = null;
        try {
            // Берём чистую иконку без бейджа, чтобы точно распознать AdaptiveIconDrawable
            raw = info.getIcon(0);
        } catch (Throwable ignored) {
        }
        if (raw == null) {
            try {
                raw = info.getBadgedIcon(0);
            } catch (Throwable ignored) {
            }
        }
        if (raw == null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                raw = pm.getActivityIcon(info.getComponentName());
            } catch (Throwable ignored) {
            }
        }
        if (raw == null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                raw = pm.getApplicationIcon(info.getComponentName().getPackageName());
            } catch (Throwable ignored) {
            }
        }
        if (raw == null) {
            return defaultActivityIcon(ctx);
        }

        try {
            int targetPx = Math.min(Math.max(GlassTheme.dp(ctx, 64), 160), 192);
            Bitmap normalized = normalizeToUniformSquircle(raw, targetPx);
            Drawable result = new BitmapDrawable(ctx.getResources(), normalized);

            // Если приложение из рабочего профиля / клона — сохраняем системный бейдж поверх ровной иконки
            UserHandle user = info.getUser();
            if (user != null && !Process.myUserHandle().equals(user)) {
                try {
                    Drawable badged = ctx.getPackageManager().getUserBadgedIcon(result, user);
                    if (badged != null) {
                        Bitmap bBmp = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888);
                        Canvas bc = new Canvas(bBmp);
                        badged.setBounds(0, 0, targetPx, targetPx);
                        badged.draw(bc);
                        return new BitmapDrawable(ctx.getResources(), bBmp);
                    }
                } catch (Throwable ignored) {
                }
            }
            return result;
        } catch (Throwable ignored) {
            return raw;
        }
    }

    private static Bitmap normalizeToUniformSquircle(Drawable raw, int sizePx) {
        float margin = sizePx * ICON_MARGIN_RATIO;
        RectF inner = new RectF(margin, margin, sizePx - margin, sizePx - margin);

        Bitmap content = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(content);

        if (Build.VERSION.SDK_INT >= 26 && raw instanceof AdaptiveIconDrawable) {
            drawAdaptiveLayers(c, (AdaptiveIconDrawable) raw, inner, sizePx);
        } else {
            drawNormalizedLegacy(c, raw, inner, sizePx);
        }

        // Накладываем идеально сглаженную кэшированную SRC_IN маску Squircle (без «лесенок» clipPath)
        Bitmap mask = getOrCreateSquircleMask(sizePx, inner);

        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas oc = new Canvas(out);
        oc.drawBitmap(mask, 0f, 0f, null);

        Paint xfer = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        xfer.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        oc.drawBitmap(content, 0f, 0f, xfer);

        // Деликатная тонкая окантовка по краю Squircle: держит ровную форму даже у тёмных иконок
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, sizePx * 0.008f));
        stroke.setColor(EDGE_STROKE_COLOR);
        RectF strokeRect = new RectF(
                inner.left + 0.5f, inner.top + 0.5f,
                inner.right - 0.5f, inner.bottom - 0.5f
        );
        oc.drawPath(buildSquirclePath(strokeRect), stroke);

        content.recycle();
        return out;
    }

    private static void drawAdaptiveLayers(Canvas canvas, AdaptiveIconDrawable aid, RectF inner, int sizePx) {
        Drawable bg = aid.getBackground();
        Drawable fg = aid.getForeground();

        // Стандартная пропорция Android AdaptiveIcon: 108dp полный слой на 72dp видимое окно (1.5x)
        float innerW = inner.width();
        float layerSize = innerW * 1.5f;
        float cx = inner.centerX();
        float cy = inner.centerY();
        int l = Math.round(cx - layerSize * 0.5f);
        int t = Math.round(cy - layerSize * 0.5f);
        int r = Math.round(cx + layerSize * 0.5f);
        int b = Math.round(cy + layerSize * 0.5f);

        if (bg != null) {
            bg.setBounds(l, t, r, b);
            bg.draw(canvas);
        } else {
            // Если у адаптивной иконки отсутствует фон, подбираем контрастный по яркости переднего слоя
            int fallbackBg = BACKDROP_LIGHT;
            if (fg != null) {
                Bitmap probe = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
                Canvas pc = new Canvas(probe);
                fg.setBounds(-16, -16, 80, 80);
                fg.draw(pc);
                fallbackBg = chooseBackdropColor(measureAverageLuma(probe));
                probe.recycle();
            }
            canvas.drawColor(fallbackBg);
        }

        if (fg != null) {
            fg.setBounds(l, t, r, b);
            fg.draw(canvas);
        }
    }

    private static void drawNormalizedLegacy(Canvas canvas, Drawable raw, RectF inner, int sizePx) {
        Bitmap src = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(src);

        int iw = raw.getIntrinsicWidth();
        int ih = raw.getIntrinsicHeight();
        int dw = sizePx;
        int dh = sizePx;
        if (iw > 0 && ih > 0 && iw != ih) {
            if (iw > ih) dh = Math.max(1, Math.round(sizePx * (ih / (float) iw)));
            else dw = Math.max(1, Math.round(sizePx * (iw / (float) ih)));
        }
        int dl = (sizePx - dw) / 2;
        int dt = (sizePx - dh) / 2;
        raw.setBounds(dl, dt, dl + dw, dt + dh);
        raw.draw(sc);

        int[] pixels = new int[sizePx * sizePx];
        src.getPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx);

        int minX = sizePx, minY = sizePx, maxX = -1, maxY = -1;
        int opaqueCount = 0;
        double lumaSum = 0.0;

        for (int y = 0; y < sizePx; y++) {
            int rowOff = y * sizePx;
            for (int x = 0; x < sizePx; x++) {
                int px = pixels[rowOff + x];
                int a = (px >>> 24) & 0xFF;
                if (a >= 24) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    opaqueCount++;
                    int r = (px >>> 16) & 0xFF;
                    int g = (px >>> 8) & 0xFF;
                    int b = px & 0xFF;
                    lumaSum += (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
                }
            }
        }

        if (opaqueCount == 0 || maxX < minX || maxY < minY) {
            canvas.drawColor(BACKDROP_DARK);
            src.recycle();
            return;
        }

        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        float fillRatio = opaqueCount / (float) (bw * bh);
        float aspect = Math.min(bw, bh) / (float) Math.max(bw, bh);
        float avgLuma = (float) (lumaSum / opaqueCount);

        // Проверяем 4 угла реального bbox: у круглых иконок они прозрачные
        int cornerPadX = Math.max(1, Math.round(bw * 0.06f));
        int cornerPadY = Math.max(1, Math.round(bh * 0.06f));
        int cornersOpaque = 0;
        int[][] corners = {
                {minX + cornerPadX, minY + cornerPadY},
                {maxX - cornerPadX, minY + cornerPadY},
                {minX + cornerPadX, maxY - cornerPadY},
                {maxX - cornerPadX, maxY - cornerPadY}
        };
        for (int[] pt : corners) {
            int cx = Math.max(0, Math.min(sizePx - 1, pt[0]));
            int cy = Math.max(0, Math.min(sizePx - 1, pt[1]));
            if (((pixels[cy * sizePx + cx] >>> 24) & 0xFF) >= 40) cornersOpaque++;
        }

        int shapeKind = classifyLegacyShape(aspect, fillRatio, cornersOpaque);
        Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Rect srcRect = new Rect(minX, minY, maxX + 1, maxY + 1);

        if (shapeKind == SHAPE_FULL_BLEED) {
            // Квадратная/скруглённая полнозаливная иконка -> подгоняем строго 1-в-1 под единый Squircle
            float overscan = inner.width() * 0.015f;
            RectF dst = new RectF(
                    inner.left - overscan, inner.top - overscan,
                    inner.right + overscan, inner.bottom + overscan
            );
            canvas.drawBitmap(src, srcRect, dst, bmpPaint);
        } else if (shapeKind == SHAPE_ROUND) {
            // Круглая иконка -> подбираем чистый краевой цвет (если ободок однородный) для ровного Squircle
            int edgeColor = sampleCircleRimColor(pixels, sizePx, minX, minY, bw, bh);
            canvas.drawColor(edgeColor != 0 ? edgeColor : chooseBackdropColor(avgLuma));
            float fit = inner.width() * 0.88f;
            float cx = inner.centerX();
            float cy = inner.centerY();
            RectF dst = new RectF(cx - fit * 0.5f, cy - fit * 0.5f, cx + fit * 0.5f, cy + fit * 0.5f);
            canvas.drawBitmap(src, srcRect, dst, bmpPaint);
        } else {
            // Фигурный логотип / нестандартный силуэт -> чистая адаптивная подложка + оптический центр
            canvas.drawColor(chooseBackdropColor(avgLuma));
            float maxDim = inner.width() * 0.72f;
            float scale = maxDim / Math.max(bw, bh);
            float outW = bw * scale;
            float outH = bh * scale;
            float cx = inner.centerX();
            float cy = inner.centerY();
            RectF dst = new RectF(cx - outW * 0.5f, cy - outH * 0.5f, cx + outW * 0.5f, cy + outH * 0.5f);
            canvas.drawBitmap(src, srcRect, dst, bmpPaint);
        }

        src.recycle();
    }

    /**
     * Берёт 8 точек на ободке круглой иконки; если цвет однородный (дисперсия низкая),
     * возвращает его для бесшовного заполнения углов Squircle.
     */
    private static int sampleCircleRimColor(int[] pixels, int size, int minX, int minY, int bw, int bh) {
        float cx = minX + bw * 0.5f;
        float cy = minY + bh * 0.5f;
        float rx = bw * 0.44f;
        float ry = bh * 0.44f;
        int[] rs = new int[8];
        int[] gs = new int[8];
        int[] bs = new int[8];
        int valid = 0;

        for (int i = 0; i < 8; i++) {
            double ang = i * (Math.PI / 4.0);
            int x = Math.max(0, Math.min(size - 1, Math.round((float) (cx + Math.cos(ang) * rx))));
            int y = Math.max(0, Math.min(size - 1, Math.round((float) (cy + Math.sin(ang) * ry))));
            int px = pixels[y * size + x];
            if (((px >>> 24) & 0xFF) < 200) continue;
            rs[valid] = (px >>> 16) & 0xFF;
            gs[valid] = (px >>> 8) & 0xFF;
            bs[valid] = px & 0xFF;
            valid++;
        }
        if (valid < 6) return 0;

        int avgR = 0, avgG = 0, avgB = 0;
        for (int i = 0; i < valid; i++) {
            avgR += rs[i];
            avgG += gs[i];
            avgB += bs[i];
        }
        avgR /= valid;
        avgG /= valid;
        avgB /= valid;

        int maxDiff = 0;
        for (int i = 0; i < valid; i++) {
            int d = Math.abs(rs[i] - avgR) + Math.abs(gs[i] - avgG) + Math.abs(bs[i] - avgB);
            if (d > maxDiff) maxDiff = d;
        }
        return (maxDiff <= 72) ? Color.rgb(avgR, avgG, avgB) : 0;
    }

    private static float measureAverageLuma(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        double sum = 0.0;
        int cnt = 0;
        for (int c : px) {
            if (((c >>> 24) & 0xFF) >= 32) {
                sum += (0.299 * ((c >>> 16) & 0xFF) + 0.587 * ((c >>> 8) & 0xFF) + 0.114 * (c & 0xFF)) / 255.0;
                cnt++;
            }
        }
        return cnt > 0 ? (float) (sum / cnt) : 0.5f;
    }

    /**
     * Строит плавный суперэллипс (Squircle) с непрерывной кривизной — единый современный стиль.
     */
    static Path buildSquirclePath(RectF r) {
        Path p = new Path();
        float w = r.width();
        float h = r.height();
        float l = r.left;
        float t = r.top;
        float rt = r.right;
        float bt = r.bottom;
        // Гладкий squircle (кривизна ~26% от стороны, контрольные точки на 5.5% от угла)
        float c = Math.min(w, h) * 0.275f;
        float k = Math.min(w, h) * 0.055f;

        p.moveTo(l + c, t);
        p.lineTo(rt - c, t);
        p.cubicTo(rt - k, t, rt, t + k, rt, t + c);
        p.lineTo(rt, bt - c);
        p.cubicTo(rt, bt - k, rt - k, bt, rt - c, bt);
        p.lineTo(l + c, bt);
        p.cubicTo(l + k, bt, l, bt - k, l, bt - c);
        p.lineTo(l, t + c);
        p.cubicTo(l, t + k, l + k, t, l + c, t);
        p.close();
        return p;
    }

    static int classifyLegacyShape(float aspect, float fillRatio, int cornersOpaque) {
        if (aspect >= 0.85f && fillRatio >= 0.74f && cornersOpaque >= 2) {
            return SHAPE_FULL_BLEED;
        }
        if (aspect >= 0.88f && fillRatio >= 0.62f && fillRatio <= 0.88f && cornersOpaque == 0) {
            return SHAPE_ROUND;
        }
        if (aspect >= 0.86f && fillRatio >= 0.80f) {
            return SHAPE_FULL_BLEED;
        }
        return SHAPE_FREEFORM;
    }

    static int chooseBackdropColor(float avgLuma) {
        return (avgLuma >= 0.50f) ? BACKDROP_DARK : BACKDROP_LIGHT;
    }

    private static Drawable defaultActivityIcon(Context ctx) {
        try {
            return ctx.getPackageManager().getDefaultActivityIcon();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
