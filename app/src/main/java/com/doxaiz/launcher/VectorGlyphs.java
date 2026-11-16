package com.doxaiz.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.widget.ImageView;

/**
 * Единая библиотека чётких векторных системных иконок Doxaiz (сетка 24x24, штрих 1.9dp,
 * закруглённые окончания) с встроенным LruCache готовых Bitmap:
 * каждый значок растеризуется 1 раз и далее читается мгновенно из кэша без перерисовки Path.
 */
public final class VectorGlyphs {

    // Кэш растеризованных векторных значков до 3 МБ (key = name|sizePx|color)
    private static final LruCache<String, Bitmap> GLYPH_CACHE = new LruCache<String, Bitmap>(3072) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getAllocationByteCount() / 1024);
        }
    };

    private VectorGlyphs() {}

    public static int getCachedGlyphCount() {
        return GLYPH_CACHE.snapshot().size();
    }

    public static void clearCache() {
        GLYPH_CACHE.evictAll();
    }

    public static ImageView makeIconView(Context ctx, String name, int sizeDp, int color) {
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(create(ctx, name, sizeDp, color));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    public static Drawable create(Context ctx, String name, int sizeDp, int color) {
        int px = Math.max(12, GlassTheme.dp(ctx, sizeDp));
        String safeName = (name != null) ? name : "";
        String cacheKey = safeName + "|" + px + "|" + color;
        Bitmap cached = GLYPH_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return new BitmapDrawable(ctx.getResources(), cached);
        }
        try {
            Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            GlyphDrawable gd = new GlyphDrawable(safeName, color, px);
            gd.setBounds(0, 0, px, px);
            gd.draw(c);
            GLYPH_CACHE.put(cacheKey, bmp);
            return new BitmapDrawable(ctx.getResources(), bmp);
        } catch (Throwable ignored) {
            return new GlyphDrawable(safeName, color, px);
        }
    }

    private static final class GlyphDrawable extends Drawable {
        private final String name;
        private final int sizePx;
        private final Paint stroke;
        private final Paint fill;
        private final Path path = new Path();
        private final RectF rf = new RectF();

        GlyphDrawable(String name, int color, int sizePx) {
            this.name = (name != null) ? name : "";
            this.sizePx = Math.max(12, sizePx);

            stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(color);

            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
        }

        @Override public int getIntrinsicWidth() { return sizePx; }
        @Override public int getIntrinsicHeight() { return sizePx; }

        @Override
        public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            stroke.setColorFilter(colorFilter);
            fill.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            float s = Math.min(w, h) / 24f;
            if (s <= 0f) return;

            c.save();
            c.translate(b.left + (w - 24f * s) * 0.5f, b.top + (h - 24f * s) * 0.5f);
            c.scale(s, s);
            stroke.setStrokeWidth(1.9f);
            path.reset();

            switch (name) {
                case "wifi":
                    rf.set(2.5f, 5.5f, 21.5f, 24.5f);
                    c.drawArc(rf, -135f, 90f, false, stroke);
                    rf.set(6f, 9f, 18f, 21f);
                    c.drawArc(rf, -135f, 90f, false, stroke);
                    rf.set(9.3f, 12.4f, 14.7f, 17.8f);
                    c.drawArc(rf, -135f, 90f, false, stroke);
                    c.drawCircle(12f, 18.4f, 1.35f, fill);
                    break;

                case "mobile":
                    // Стрелка вверх слева + стрелка вниз справа
                    c.drawLine(8.5f, 19f, 8.5f, 5.5f, stroke);
                    path.moveTo(5f, 9f); path.lineTo(8.5f, 5.2f); path.lineTo(12f, 9f);
                    c.drawPath(path, stroke);
                    path.reset();
                    c.drawLine(15.5f, 5f, 15.5f, 18.5f, stroke);
                    path.moveTo(12f, 15f); path.lineTo(15.5f, 18.8f); path.lineTo(19f, 15f);
                    c.drawPath(path, stroke);
                    break;

                case "torch":
                    path.moveTo(8f, 3.5f); path.lineTo(16f, 3.5f);
                    path.lineTo(16f, 7f); path.lineTo(14f, 10f);
                    path.lineTo(14f, 20.5f); path.lineTo(10f, 20.5f);
                    path.lineTo(10f, 10f); path.lineTo(8f, 7f); path.close();
                    c.drawPath(path, stroke);
                    c.drawLine(8.2f, 6.8f, 15.8f, 6.8f, stroke);
                    c.drawCircle(12f, 13.5f, 1.1f, fill);
                    break;

                case "bluetooth":
                    path.moveTo(7.5f, 7.5f); path.lineTo(16.5f, 16.2f);
                    path.lineTo(12f, 20.5f); path.lineTo(12f, 3.5f);
                    path.lineTo(16.5f, 7.8f); path.lineTo(7.5f, 16.5f);
                    c.drawPath(path, stroke);
                    break;

                case "sound":
                case "volume":
                    path.moveTo(4f, 9.5f); path.lineTo(8.2f, 9.5f);
                    path.lineTo(13f, 5.5f); path.lineTo(13f, 18.5f);
                    path.lineTo(8.2f, 14.5f); path.lineTo(4f, 14.5f); path.close();
                    c.drawPath(path, stroke);
                    rf.set(13.5f, 8.2f, 18.5f, 15.8f);
                    c.drawArc(rf, -52f, 104f, false, stroke);
                    rf.set(14.5f, 5.2f, 21.5f, 18.8f);
                    c.drawArc(rf, -54f, 108f, false, stroke);
                    break;

                case "vibrate":
                    rf.set(8.2f, 4.5f, 15.8f, 19.5f);
                    c.drawRoundRect(rf, 1.8f, 1.8f, stroke);
                    c.drawLine(5f, 8f, 5f, 16f, stroke);
                    c.drawLine(2.6f, 10f, 2.6f, 14f, stroke);
                    c.drawLine(19f, 8f, 19f, 16f, stroke);
                    c.drawLine(21.4f, 10f, 21.4f, 14f, stroke);
                    break;

                case "mute":
                    path.moveTo(4f, 9.5f); path.lineTo(8.2f, 9.5f);
                    path.lineTo(13f, 5.5f); path.lineTo(13f, 18.5f);
                    path.lineTo(8.2f, 14.5f); path.lineTo(4f, 14.5f); path.close();
                    c.drawPath(path, stroke);
                    c.drawLine(16.2f, 9.2f, 20.6f, 14.8f, stroke);
                    c.drawLine(20.6f, 9.2f, 16.2f, 14.8f, stroke);
                    break;

                case "dnd":
                    c.drawCircle(12f, 12f, 8.4f, stroke);
                    c.drawLine(7.8f, 12f, 16.2f, 12f, stroke);
                    break;

                case "hotspot":
                    c.drawCircle(12f, 13f, 1.5f, fill);
                    rf.set(7.5f, 8.5f, 16.5f, 17.5f);
                    c.drawArc(rf, 140f, 260f, false, stroke);
                    rf.set(3.8f, 4.8f, 20.2f, 21.2f);
                    c.drawArc(rf, 142f, 256f, false, stroke);
                    break;

                case "airplane":
                    path.moveTo(12f, 3.5f); path.lineTo(12f, 20f);
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(4f, 13.5f); path.lineTo(12f, 9.2f); path.lineTo(20f, 13.5f);
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(8.5f, 19.5f); path.lineTo(12f, 17.5f); path.lineTo(15.5f, 19.5f);
                    c.drawPath(path, stroke);
                    break;

                case "gps":
                    path.moveTo(12f, 20.5f);
                    path.cubicTo(12f, 20.5f, 18f, 14.6f, 18f, 10f);
                    path.cubicTo(18f, 6.7f, 15.3f, 4f, 12f, 4f);
                    path.cubicTo(8.7f, 4f, 6f, 6.7f, 6f, 10f);
                    path.cubicTo(6f, 14.6f, 12f, 20.5f, 12f, 20.5f);
                    c.drawPath(path, stroke);
                    c.drawCircle(12f, 10f, 2.1f, stroke);
                    break;

                case "autonet":
                    c.drawCircle(12f, 12f, 8.3f, stroke);
                    rf.set(8.2f, 3.7f, 15.8f, 20.3f);
                    c.drawOval(rf, stroke);
                    c.drawLine(4f, 9.2f, 20f, 9.2f, stroke);
                    c.drawLine(4f, 14.8f, 20f, 14.8f, stroke);
                    break;

                case "rotate":
                    rf.set(4.5f, 4.5f, 19.5f, 19.5f);
                    c.drawArc(rf, -45f, 285f, false, stroke);
                    path.moveTo(17.5f, 4f); path.lineTo(18.2f, 8.4f); path.lineTo(13.8f, 8.2f);
                    c.drawPath(path, stroke);
                    break;

                case "record":
                    rf.set(3.5f, 5.5f, 20.5f, 18.5f);
                    c.drawRoundRect(rf, 3f, 3f, stroke);
                    c.drawCircle(12f, 12f, 3.1f, fill);
                    break;

                case "qr":
                    // 4 уголка сканера + центральная линия
                    path.moveTo(4f, 8.5f); path.lineTo(4f, 5.5f); path.lineTo(7f, 5.5f);
                    path.moveTo(17f, 5.5f); path.lineTo(20f, 5.5f); path.lineTo(20f, 8.5f);
                    path.moveTo(4f, 15.5f); path.lineTo(4f, 18.5f); path.lineTo(7f, 18.5f);
                    path.moveTo(17f, 18.5f); path.lineTo(20f, 18.5f); path.lineTo(20f, 15.5f);
                    c.drawPath(path, stroke);
                    c.drawLine(6.5f, 12f, 17.5f, 12f, stroke);
                    break;

                case "battery":
                    rf.set(3.5f, 7.5f, 19f, 16.5f);
                    c.drawRoundRect(rf, 2.2f, 2.2f, stroke);
                    c.drawLine(21f, 10.2f, 21f, 13.8f, stroke);
                    path.moveTo(12.2f, 9.2f); path.lineTo(9.8f, 12.3f);
                    path.lineTo(12f, 12.3f); path.lineTo(10.6f, 15f);
                    c.drawPath(path, stroke);
                    break;

                case "dns":
                    path.moveTo(12f, 3.5f);
                    path.lineTo(19f, 6.2f);
                    path.lineTo(19f, 12.2f);
                    path.cubicTo(19f, 16.4f, 15.8f, 19.3f, 12f, 20.8f);
                    path.cubicTo(8.2f, 19.3f, 5f, 16.4f, 5f, 12.2f);
                    path.lineTo(5f, 6.2f); path.close();
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(9f, 12.2f); path.lineTo(11.1f, 14.3f); path.lineTo(15.4f, 9.8f);
                    c.drawPath(path, stroke);
                    break;

                case "dim":
                    path.moveTo(16.8f, 15.6f);
                    path.cubicTo(13.1f, 15.6f, 10.2f, 12.7f, 10.2f, 9f);
                    path.cubicTo(10.2f, 7.2f, 10.9f, 5.6f, 12.1f, 4.4f);
                    path.cubicTo(7.9f, 4.9f, 4.8f, 8.5f, 4.8f, 12.8f);
                    path.cubicTo(4.8f, 17.3f, 8.5f, 20.6f, 13f, 20.6f);
                    path.cubicTo(16.3f, 20.6f, 19.1f, 18.6f, 20.2f, 15.8f);
                    path.cubicTo(19.2f, 15.7f, 18f, 15.6f, 16.8f, 15.6f);
                    path.close();
                    c.drawPath(path, stroke);
                    break;

                case "screenshot":
                    rf.set(4.5f, 5.5f, 19.5f, 18.5f);
                    c.drawRoundRect(rf, 2.5f, 2.5f, stroke);
                    c.drawCircle(12f, 12f, 2.6f, stroke);
                    c.drawLine(8f, 5.5f, 9.2f, 3.6f, stroke);
                    c.drawLine(16f, 5.5f, 14.8f, 3.6f, stroke);
                    break;

                case "active_bg":
                case "bolt":
                    path.moveTo(13.5f, 3.2f); path.lineTo(6.5f, 13f);
                    path.lineTo(12f, 13f); path.lineTo(10.5f, 20.8f);
                    path.lineTo(17.5f, 11f); path.lineTo(12f, 11f); path.close();
                    c.drawPath(path, stroke);
                    break;

                case "dbar":
                case "island":
                    rf.set(3.2f, 8.2f, 20.8f, 15.8f);
                    c.drawRoundRect(rf, 3.8f, 3.8f, stroke);
                    c.drawCircle(8f, 12f, 1.5f, fill);
                    c.drawLine(12f, 12f, 17f, 12f, stroke);
                    break;

                case "lock":
                    rf.set(5.5f, 10.5f, 18.5f, 20f);
                    c.drawRoundRect(rf, 2.4f, 2.4f, stroke);
                    rf.set(8.2f, 4.5f, 15.8f, 13.5f);
                    c.drawArc(rf, 180f, 180f, false, stroke);
                    c.drawCircle(12f, 15.2f, 1.2f, fill);
                    break;

                case "settings":
                    c.drawCircle(12f, 12f, 3.3f, stroke);
                    c.drawCircle(12f, 12f, 7.2f, stroke);
                    for (int a = 0; a < 360; a += 45) {
                        double rad = Math.toRadians(a);
                        float x1 = 12f + (float) Math.cos(rad) * 7.2f;
                        float y1 = 12f + (float) Math.sin(rad) * 7.2f;
                        float x2 = 12f + (float) Math.cos(rad) * 9.5f;
                        float y2 = 12f + (float) Math.sin(rad) * 9.5f;
                        c.drawLine(x1, y1, x2, y2, stroke);
                    }
                    break;

                case "more":
                    c.drawCircle(12f, 6.2f, 1.45f, fill);
                    c.drawCircle(12f, 12f, 1.45f, fill);
                    c.drawCircle(12f, 17.8f, 1.45f, fill);
                    break;

                case "edit":
                    path.moveTo(4.8f, 19.2f); path.lineTo(9f, 18.2f);
                    path.lineTo(18.8f, 8.4f); path.lineTo(15.6f, 5.2f);
                    path.lineTo(5.8f, 15f); path.close();
                    c.drawPath(path, stroke);
                    break;

                case "bell":
                    path.moveTo(6.5f, 16.5f); path.lineTo(17.5f, 16.5f);
                    path.lineTo(16.5f, 14f); path.lineTo(16.5f, 10.2f);
                    path.cubicTo(16.5f, 7.3f, 14.5f, 5.2f, 12f, 5.2f);
                    path.cubicTo(9.5f, 5.2f, 7.5f, 7.3f, 7.5f, 10.2f);
                    path.lineTo(7.5f, 14f); path.close();
                    c.drawPath(path, stroke);
                    c.drawLine(10.2f, 19.5f, 13.8f, 19.5f, stroke);
                    break;

                case "trash":
                    c.drawLine(4.5f, 7f, 19.5f, 7f, stroke);
                    c.drawLine(9.5f, 4.5f, 14.5f, 4.5f, stroke);
                    path.moveTo(6.8f, 7f); path.lineTo(7.8f, 19.5f);
                    path.lineTo(16.2f, 19.5f); path.lineTo(17.2f, 7f);
                    c.drawPath(path, stroke);
                    c.drawLine(10.2f, 10.5f, 10.2f, 16f, stroke);
                    c.drawLine(13.8f, 10.5f, 13.8f, 16f, stroke);
                    break;

                case "sun":
                    c.drawCircle(12f, 12f, 4.1f, fill);
                    for (int a = 0; a < 360; a += 45) {
                        double rad = Math.toRadians(a);
                        float x1 = 12f + (float) Math.cos(rad) * 6.5f;
                        float y1 = 12f + (float) Math.sin(rad) * 6.5f;
                        float x2 = 12f + (float) Math.cos(rad) * 9.2f;
                        float y2 = 12f + (float) Math.sin(rad) * 9.2f;
                        c.drawLine(x1, y1, x2, y2, stroke);
                    }
                    break;

                case "drop":
                    path.moveTo(12f, 3.6f);
                    path.cubicTo(12f, 3.6f, 18f, 10.6f, 18f, 14.6f);
                    path.cubicTo(18f, 18f, 15.3f, 20.4f, 12f, 20.4f);
                    path.cubicTo(8.7f, 20.4f, 6f, 18f, 6f, 14.6f);
                    path.cubicTo(6f, 10.6f, 12f, 3.6f, 12f, 3.6f);
                    c.drawPath(path, stroke);
                    break;

                case "music":
                    path.moveTo(9.5f, 17f); path.lineTo(9.5f, 6.8f);
                    path.lineTo(18.2f, 5.2f); path.lineTo(18.2f, 15.2f);
                    c.drawPath(path, stroke);
                    c.drawCircle(7.5f, 17.5f, 2.2f, stroke);
                    c.drawCircle(16.2f, 15.7f, 2.2f, stroke);
                    break;

                case "phone":
                    path.moveTo(6.5f, 4.8f); path.lineTo(9.4f, 4.8f);
                    path.lineTo(10.6f, 9f); path.lineTo(8.5f, 10.8f);
                    path.cubicTo(9.6f, 13.1f, 11.4f, 14.8f, 13.6f, 15.8f);
                    path.lineTo(15.4f, 13.7f); path.lineTo(19.4f, 14.9f);
                    path.lineTo(19.4f, 18f);
                    path.cubicTo(19.4f, 19.2f, 18.2f, 20f, 16.8f, 19.8f);
                    path.cubicTo(10.2f, 19f, 5.2f, 13.8f, 4.6f, 7.2f);
                    path.cubicTo(4.4f, 5.8f, 5.3f, 4.8f, 6.5f, 4.8f);
                    c.drawPath(path, stroke);
                    break;

                case "mic":
                    rf.set(9f, 4f, 15f, 13.5f);
                    c.drawRoundRect(rf, 3f, 3f, stroke);
                    rf.set(6f, 7.5f, 18f, 16.5f);
                    c.drawArc(rf, 0f, 180f, false, stroke);
                    c.drawLine(12f, 16.5f, 12f, 20.2f, stroke);
                    c.drawLine(8.8f, 20.2f, 15.2f, 20.2f, stroke);
                    break;

                case "wallpaper":
                    rf.set(3.5f, 5f, 20.5f, 19f);
                    c.drawRoundRect(rf, 2.6f, 2.6f, stroke);
                    path.moveTo(4.5f, 17f); path.lineTo(9.5f, 11.8f);
                    path.lineTo(13.2f, 15.2f); path.lineTo(16.2f, 12.2f);
                    path.lineTo(19.5f, 16.5f);
                    c.drawPath(path, stroke);
                    c.drawCircle(8.4f, 8.6f, 1.3f, fill);
                    break;

                case "pages":
                    rf.set(4f, 5f, 14f, 19f);
                    c.drawRoundRect(rf, 2f, 2f, stroke);
                    rf.set(16.5f, 7f, 20.5f, 17f);
                    c.drawRoundRect(rf, 1.5f, 1.5f, stroke);
                    break;

                case "calc":
                    rf.set(5f, 3.5f, 19f, 20.5f);
                    c.drawRoundRect(rf, 2.4f, 2.4f, stroke);
                    c.drawLine(8f, 7.5f, 16f, 7.5f, stroke);
                    c.drawCircle(8.5f, 12f, 1.1f, fill);
                    c.drawCircle(12f, 12f, 1.1f, fill);
                    c.drawCircle(15.5f, 12f, 1.1f, fill);
                    c.drawCircle(8.5f, 16f, 1.1f, fill);
                    c.drawCircle(12f, 16f, 1.1f, fill);
                    c.drawCircle(15.5f, 16f, 1.1f, fill);
                    break;

                case "prev":
                    c.drawLine(6.2f, 6.8f, 6.2f, 17.2f, stroke);
                    path.moveTo(17.5f, 6.8f); path.lineTo(9.5f, 12f); path.lineTo(17.5f, 17.2f); path.close();
                    c.drawPath(path, fill);
                    break;

                case "play":
                    path.moveTo(8f, 6f); path.lineTo(18.5f, 12f); path.lineTo(8f, 18f); path.close();
                    c.drawPath(path, fill);
                    break;

                case "next":
                    c.drawLine(17.8f, 6.8f, 17.8f, 17.2f, stroke);
                    path.moveTo(6.5f, 6.8f); path.lineTo(14.5f, 12f); path.lineTo(6.5f, 17.2f); path.close();
                    c.drawPath(path, fill);
                    break;

                case "eq":
                    c.drawLine(6f, 7f, 18f, 7f, stroke);
                    c.drawLine(6f, 12f, 18f, 12f, stroke);
                    c.drawLine(6f, 17f, 18f, 17f, stroke);
                    c.drawCircle(9.5f, 7f, 1.6f, fill);
                    c.drawCircle(14.5f, 12f, 1.6f, fill);
                    c.drawCircle(10.5f, 17f, 1.6f, fill);
                    break;

                case "chevron_down":
                    path.moveTo(6.5f, 9.2f); path.lineTo(12f, 14.8f); path.lineTo(17.5f, 9.2f);
                    c.drawPath(path, stroke);
                    break;

                case "chevron_up":
                    path.moveTo(6.5f, 14.8f); path.lineTo(12f, 9.2f); path.lineTo(17.5f, 14.8f);
                    c.drawPath(path, stroke);
                    break;

                case "chevron_right":
                    path.moveTo(9.2f, 6.5f); path.lineTo(14.8f, 12f); path.lineTo(9.2f, 17.5f);
                    c.drawPath(path, stroke);
                    break;

                case "close":
                    c.drawLine(6.8f, 6.8f, 17.2f, 17.2f, stroke);
                    c.drawLine(17.2f, 6.8f, 6.8f, 17.2f, stroke);
                    break;

                case "minus":
                    c.drawLine(5.5f, 12f, 18.5f, 12f, stroke);
                    break;

                case "pause":
                    c.drawRoundRect(new RectF(6.5f, 5.5f, 10.2f, 18.5f), 1.4f, 1.4f, fill);
                    c.drawRoundRect(new RectF(13.8f, 5.5f, 17.5f, 18.5f), 1.4f, 1.4f, fill);
                    break;

                case "plus":
                    c.drawLine(12f, 5.5f, 12f, 18.5f, stroke);
                    c.drawLine(5.5f, 12f, 18.5f, 12f, stroke);
                    break;

                case "reply":
                    path.moveTo(9.5f, 8f); path.lineTo(4.5f, 12.5f); path.lineTo(9.5f, 17f);
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(5.2f, 12.5f);
                    path.quadTo(15.5f, 12f, 19.5f, 18.5f);
                    c.drawPath(path, stroke);
                    break;

                case "send":
                    path.moveTo(4f, 11.5f); path.lineTo(20f, 4.5f);
                    path.lineTo(14.5f, 20f); path.lineTo(11.2f, 13.3f); path.close();
                    c.drawPath(path, stroke);
                    c.drawLine(11.2f, 13.3f, 20f, 4.5f, stroke);
                    break;

                case "timer":
                    c.drawCircle(12f, 13f, 7.2f, stroke);
                    c.drawLine(9.5f, 3.5f, 14.5f, 3.5f, stroke);
                    c.drawLine(12f, 13f, 12f, 9f, stroke);
                    c.drawLine(12f, 13f, 15.2f, 14.5f, stroke);
                    break;

                case "sort":
                    c.drawLine(4.5f, 7f, 19.5f, 7f, stroke);
                    c.drawLine(4.5f, 12f, 15f, 12f, stroke);
                    c.drawLine(4.5f, 17f, 10.5f, 17f, stroke);
                    break;

                case "check":
                    path.moveTo(5.2f, 12.6f); path.lineTo(10f, 17.4f); path.lineTo(19f, 7.2f);
                    c.drawPath(path, stroke);
                    break;

                case "convert":
                    c.drawLine(5f, 8.5f, 19f, 8.5f, stroke);
                    path.moveTo(15.2f, 5f); path.lineTo(19f, 8.5f); path.lineTo(15.2f, 12f);
                    c.drawPath(path, stroke);
                    path.reset();
                    c.drawLine(19f, 15.5f, 5f, 15.5f, stroke);
                    path.moveTo(8.8f, 12f); path.lineTo(5f, 15.5f); path.lineTo(8.8f, 19f);
                    c.drawPath(path, stroke);
                    break;

                case "search":
                    c.drawCircle(10.8f, 10.8f, 6.2f, stroke);
                    c.drawLine(15.4f, 15.4f, 19.8f, 19.8f, stroke);
                    break;

                case "home":
                    path.moveTo(4f, 11.5f); path.lineTo(12f, 4.5f); path.lineTo(20f, 11.5f);
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(6.5f, 10.2f); path.lineTo(6.5f, 19.5f);
                    path.lineTo(17.5f, 19.5f); path.lineTo(17.5f, 10.2f);
                    c.drawPath(path, stroke);
                    break;

                case "grid":
                    c.drawRoundRect(new RectF(4.2f, 4.2f, 10.5f, 10.5f), 2f, 2f, stroke);
                    c.drawRoundRect(new RectF(13.5f, 4.2f, 19.8f, 10.5f), 2f, 2f, stroke);
                    c.drawRoundRect(new RectF(4.2f, 13.5f, 10.5f, 19.8f), 2f, 2f, stroke);
                    c.drawRoundRect(new RectF(13.5f, 13.5f, 19.8f, 19.8f), 2f, 2f, stroke);
                    break;

                case "gesture":
                    c.drawCircle(12f, 12f, 2.2f, fill);
                    path.moveTo(12f, 4.5f); path.lineTo(12f, 8.5f);
                    path.moveTo(12f, 15.5f); path.lineTo(12f, 19.5f);
                    path.moveTo(4.5f, 12f); path.lineTo(8.5f, 12f);
                    path.moveTo(15.5f, 12f); path.lineTo(19.5f, 12f);
                    c.drawPath(path, stroke);
                    break;

                case "backup":
                    path.moveTo(12f, 4.5f); path.lineTo(12f, 15f);
                    path.moveTo(8f, 11.5f); path.lineTo(12f, 15.5f); path.lineTo(16f, 11.5f);
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(5f, 17f); path.lineTo(5f, 19f); path.lineTo(19f, 19f); path.lineTo(19f, 17f);
                    c.drawPath(path, stroke);
                    break;

                case "history":
                    c.drawArc(new RectF(4.8f, 4.8f, 19.2f, 19.2f), -135f, 295f, false, stroke);
                    path.moveTo(4.5f, 5.2f); path.lineTo(4.8f, 9.5f); path.lineTo(9.0f, 8.8f);
                    c.drawPath(path, stroke);
                    c.drawLine(12f, 8.2f, 12f, 12.2f, stroke);
                    c.drawLine(12f, 12.2f, 15.2f, 14f, stroke);
                    break;

                case "headphones":
                    c.drawArc(new RectF(4.5f, 5f, 19.5f, 19.5f), 180f, 180f, false, stroke);
                    c.drawRoundRect(new RectF(4f, 12.5f, 7.8f, 19f), 1.8f, 1.8f, stroke);
                    c.drawRoundRect(new RectF(16.2f, 12.5f, 20f, 19f), 1.8f, 1.8f, stroke);
                    break;

                case "speaker":
                    c.drawRoundRect(new RectF(6f, 3.8f, 18f, 20.2f), 2.5f, 2.5f, stroke);
                    c.drawCircle(12f, 7.6f, 1.2f, fill);
                    c.drawCircle(12f, 14.5f, 3.1f, stroke);
                    break;

                case "onehand":
                    c.drawRoundRect(new RectF(6.2f, 3.5f, 17.8f, 20.5f), 2.4f, 2.4f, stroke);
                    c.drawLine(12f, 7.5f, 12f, 15.5f, stroke);
                    path.moveTo(9.2f, 12.8f); path.lineTo(12f, 15.8f); path.lineTo(14.8f, 12.8f);
                    c.drawPath(path, stroke);
                    break;

                case "loop":
                    c.drawArc(new RectF(4.5f, 5.5f, 19.5f, 18.5f), -160f, 140f, false, stroke);
                    path.moveTo(16.2f, 5.4f); path.lineTo(19.2f, 8.2f); path.lineTo(15.5f, 9.6f);
                    c.drawPath(path, stroke);
                    path.reset();
                    c.drawArc(new RectF(4.5f, 5.5f, 19.5f, 18.5f), 20f, 140f, false, stroke);
                    path.moveTo(7.8f, 18.6f); path.lineTo(4.8f, 15.8f); path.lineTo(8.5f, 14.4f);
                    c.drawPath(path, stroke);
                    break;

                case "stack":
                    path.moveTo(4.5f, 9f); path.lineTo(12f, 5f); path.lineTo(19.5f, 9f); path.lineTo(12f, 13f); path.close();
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(4.5f, 13.5f); path.lineTo(12f, 17.2f); path.lineTo(19.5f, 13.5f);
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(4.5f, 17f); path.lineTo(12f, 20.5f); path.lineTo(19.5f, 17f);
                    c.drawPath(path, stroke);
                    break;

                case "unlock":
                    c.drawRoundRect(new RectF(5.5f, 11f, 18.5f, 20.5f), 2.5f, 2.5f, stroke);
                    c.drawArc(new RectF(8.2f, 3.8f, 15.8f, 11.4f), 180f, 155f, false, stroke);
                    c.drawLine(8.2f, 7.6f, 8.2f, 11f, stroke);
                    break;

                case "folder_plus":
                    path.moveTo(3.5f, 7f); path.lineTo(9.5f, 7f); path.lineTo(11.3f, 9.2f);
                    path.lineTo(20.5f, 9.2f); path.lineTo(20.5f, 19.2f); path.lineTo(3.5f, 19.2f); path.close();
                    c.drawPath(path, stroke);
                    c.drawLine(12f, 11.8f, 12f, 16.6f, stroke);
                    c.drawLine(9.6f, 14.2f, 14.4f, 14.2f, stroke);
                    break;

                case "copy":
                    rf.set(8.5f, 8.5f, 19.5f, 19.5f);
                    c.drawRoundRect(rf, 2.2f, 2.2f, stroke);
                    path.moveTo(15.5f, 8.5f);
                    path.lineTo(15.5f, 6.2f);
                    path.quadTo(15.5f, 4.5f, 13.8f, 4.5f);
                    path.lineTo(6.2f, 4.5f);
                    path.quadTo(4.5f, 4.5f, 4.5f, 6.2f);
                    path.lineTo(4.5f, 13.8f);
                    path.quadTo(4.5f, 15.5f, 6.2f, 15.5f);
                    path.lineTo(8.5f, 15.5f);
                    c.drawPath(path, stroke);
                    break;

                case "torch_back":
                case "torch_front":
                case "torch_360": {
                    // Лучи света сверху (как на скриншоте 3-режимного фонарика)
                    c.drawLine(12f, 2.5f, 12f, 5.2f, stroke);
                    c.drawLine(7.6f, 3.8f, 9.1f, 6.0f, stroke);
                    c.drawLine(16.4f, 3.8f, 14.9f, 6.0f, stroke);
                    // Залитый силуэт корпуса фонарика
                    path.moveTo(7.4f, 8.0f);
                    path.lineTo(16.6f, 8.0f);
                    path.lineTo(14.5f, 12.2f);
                    path.lineTo(14.5f, 21.0f);
                    path.lineTo(9.5f, 21.0f);
                    path.lineTo(9.5f, 12.2f);
                    path.close();
                    c.drawPath(path, fill);
                    // Контрастная буква B / F / A внутри корпуса
                    Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
                    cut.setColor(0xFF28282C);
                    cut.setTextAlign(Paint.Align.CENTER);
                    cut.setTextSize(5.8f);
                    cut.setFakeBoldText(true);
                    String letter = "torch_back".equals(name) ? "B"
                            : ("torch_front".equals(name) ? "F" : "A");
                    c.drawText(letter, 12f, 18.2f, cut);
                    break;
                }

                case "sim1":
                case "sim2": {
                    // Контур SIM-карты со скошенным верхним левым углом
                    path.moveTo(8.8f, 3.5f);
                    path.lineTo(17.5f, 3.5f);
                    path.quadTo(19f, 3.5f, 19f, 5f);
                    path.lineTo(19f, 19f);
                    path.quadTo(19f, 20.5f, 17.5f, 20.5f);
                    path.lineTo(6.5f, 20.5f);
                    path.quadTo(5f, 20.5f, 5f, 19f);
                    path.lineTo(5f, 7.6f);
                    path.close();
                    c.drawPath(path, stroke);
                    Paint numP = new Paint(Paint.ANTI_ALIAS_FLAG);
                    numP.setColor(fill.getColor());
                    numP.setTextAlign(Paint.Align.CENTER);
                    numP.setTextSize(9.2f);
                    numP.setFakeBoldText(true);
                    c.drawText("sim1".equals(name) ? "1" : "2", 12f, 16.0f, numP);
                    break;
                }

                case "screenshot_crop": {
                    // Рамка экрана + ножницы снизу (как в карточке «Снимок экрана»)
                    rf.set(4.5f, 4.5f, 19.5f, 14.8f);
                    c.drawRoundRect(rf, 2.2f, 2.2f, stroke);
                    c.drawCircle(8.8f, 18.8f, 1.9f, stroke);
                    c.drawCircle(15.2f, 18.8f, 1.9f, stroke);
                    c.drawLine(10.2f, 17.5f, 15.2f, 12.2f, stroke);
                    c.drawLine(13.8f, 17.5f, 8.8f, 12.2f, stroke);
                    break;
                }

                case "screenshot_partial": {
                    // Плавная фигура свободного выделения части экрана
                    path.moveTo(12f, 4.5f);
                    path.cubicTo(15.2f, 4.5f, 15.6f, 7.8f, 18.2f, 8.6f);
                    path.cubicTo(20.6f, 9.4f, 20.4f, 13.4f, 17.8f, 14.8f);
                    path.cubicTo(16.0f, 15.8f, 16.2f, 19.4f, 12.5f, 19.4f);
                    path.cubicTo(9.4f, 19.4f, 9.2f, 16.6f, 6.6f, 15.6f);
                    path.cubicTo(4.0f, 14.6f, 4.2f, 10.4f, 6.8f, 9.2f);
                    path.cubicTo(9.0f, 8.2f, 9.0f, 4.5f, 12f, 4.5f);
                    path.close();
                    c.drawPath(path, stroke);
                    break;
                }

                case "screenshot_scroll": {
                    // 4 уголка рамки + двойная стрелка прокрутки вниз
                    path.moveTo(4.5f, 8.5f); path.lineTo(4.5f, 4.5f); path.lineTo(8.5f, 4.5f);
                    path.moveTo(15.5f, 4.5f); path.lineTo(19.5f, 4.5f); path.lineTo(19.5f, 8.5f);
                    path.moveTo(4.5f, 15.5f); path.lineTo(4.5f, 19.5f); path.lineTo(8.5f, 19.5f);
                    path.moveTo(15.5f, 19.5f); path.lineTo(19.5f, 19.5f); path.lineTo(19.5f, 15.5f);
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(9.0f, 9.2f); path.lineTo(12f, 12.2f); path.lineTo(15.0f, 9.2f);
                    path.moveTo(9.0f, 13.4f); path.lineTo(12f, 16.4f); path.lineTo(15.0f, 13.4f);
                    c.drawPath(path, stroke);
                    break;
                }

                default:
                    c.drawCircle(12f, 12f, 6f, stroke);
                    break;
            }
            c.restore();
        }
    }
}
