package com.doxaiz.launcher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Сверхлёгкий Drawable (0 аллокаций в draw()) для оформления карточек, попапов и панелей Doxaiz:
 * - Сакура × Нефрит (Дунхуа): мягкий фон, тонкий золотой кант, изящные восточные уголки свитков сянься и нефритовые ромбы.
 * - Кибер-Аниме Неон: глубокий индиго-фон, острые кибер-HUD скобы в углах и неоновая верхняя линия-акцент.
 * - Классический графит: чистая матовая карточка без орнаментов.
 */
public final class AnimeStyleDrawable extends Drawable {

    private final int styleMode;
    private final boolean ornaments;
    private final float radiusPx;
    private final float strokePx;
    private final float dp;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ornamentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path diamondPath = new Path();

    public AnimeStyleDrawable(
            int styleMode,
            boolean ornaments,
            int fillColor,
            int borderColor,
            int ornamentColor,
            int accentHighlightColor,
            float radiusPx,
            float strokePx,
            float density
    ) {
        this.styleMode = styleMode;
        this.ornaments = ornaments && (styleMode != Prefs.STYLE_CLASSIC_DARK);
        this.radiusPx = Math.max(0f, radiusPx);
        this.strokePx = Math.max(0f, strokePx);
        this.dp = Math.max(1f, density);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(fillColor);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(this.strokePx > 0f ? this.strokePx : dp);
        borderPaint.setColor(borderColor);

        ornamentPaint.setStyle(Paint.Style.STROKE);
        ornamentPaint.setStrokeWidth(Math.max(1.1f * dp, 1.5f));
        ornamentPaint.setStrokeCap(Paint.Cap.ROUND);
        ornamentPaint.setStrokeJoin(Paint.Join.ROUND);
        ornamentPaint.setColor(ornamentColor);

        accentDotPaint.setStyle(Paint.Style.FILL);
        accentDotPaint.setColor(accentHighlightColor);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;

        float inset = (strokePx > 0f) ? (strokePx * 0.5f) : 0f;
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        float w = rect.width();
        float h = rect.height();
        if (w <= 0f || h <= 0f) return;

        float r = Math.min(radiusPx, Math.min(w, h) * 0.5f);
        canvas.drawRoundRect(rect, r, r, fillPaint);

        if (strokePx > 0f && Color.alpha(borderPaint.getColor()) > 0) {
            canvas.drawRoundRect(rect, r, r, borderPaint);
        }

        if (!ornaments || w < 38f * dp || h < 28f * dp) return;

        if (styleMode == Prefs.STYLE_SAKURA_JADE) {
            drawDonghuaScrollOrnaments(canvas, w, h, r);
        } else if (styleMode == Prefs.STYLE_CYBER_ANIME) {
            drawCyberHudOrnaments(canvas, w, h, r);
        }
    }

    private void drawDonghuaScrollOrnaments(Canvas canvas, float w, float h, float r) {
        // Отступ внутрь карточки, чтобы уголки гармонично повторяли скругление свитка
        float pad = Math.max(5.5f * dp, Math.min(r * 0.42f, 10f * dp));
        float arm = Math.min(11f * dp, Math.min(w, h) * 0.16f);
        if (arm < 4f * dp) return;

        float l = rect.left + pad;
        float t = rect.top + pad;
        float ri = rect.right - pad;
        float bo = rect.bottom - pad;

        // 4 изящных восточных Г-уголка свитка сянься
        // Левый верхний
        canvas.drawLine(l, t + arm, l, t, ornamentPaint);
        canvas.drawLine(l, t, l + arm, t, ornamentPaint);
        // Правый верхний
        canvas.drawLine(ri - arm, t, ri, t, ornamentPaint);
        canvas.drawLine(ri, t, ri, t + arm, ornamentPaint);
        // Левый нижний
        canvas.drawLine(l, bo - arm, l, bo, ornamentPaint);
        canvas.drawLine(l, bo, l + arm, bo, ornamentPaint);
        // Правый нижний
        canvas.drawLine(ri - arm, bo, ri, bo, ornamentPaint);
        canvas.drawLine(ri, bo, ri, bo - arm, ornamentPaint);

        // Тонкий верхний лунно-золотой штрих + крошечный нефритовый ромб в центре верхней грани (для крупных карточек)
        if (w >= 120f * dp && h >= 54f * dp) {
            float cx = rect.centerX();
            float lineHalf = Math.min(28f * dp, w * 0.16f);
            float ty = rect.top + Math.max(2f * dp, strokePx);
            canvas.drawLine(cx - lineHalf, ty, cx - 5f * dp, ty, ornamentPaint);
            canvas.drawLine(cx + 5f * dp, ty, cx + lineHalf, ty, ornamentPaint);

            float dSize = 2.4f * dp;
            diamondPath.rewind();
            diamondPath.moveTo(cx, ty - dSize);
            diamondPath.lineTo(cx + dSize, ty);
            diamondPath.lineTo(cx, ty + dSize);
            diamondPath.lineTo(cx - dSize, ty);
            diamondPath.close();
            canvas.drawPath(diamondPath, accentDotPaint);
        }
    }

    private void drawCyberHudOrnaments(Canvas canvas, float w, float h, float r) {
        float pad = Math.max(4.5f * dp, Math.min(r * 0.36f, 8.5f * dp));
        float arm = Math.min(12f * dp, Math.min(w, h) * 0.17f);
        if (arm < 4f * dp) return;

        float l = rect.left + pad;
        float t = rect.top + pad;
        float ri = rect.right - pad;
        float bo = rect.bottom - pad;
        float bevel = Math.min(3.5f * dp, arm * 0.38f);

        // Скошенные кибер-HUD скобы в 4 углах
        // Левый верхний
        canvas.drawLine(l, t + arm, l, t + bevel, ornamentPaint);
        canvas.drawLine(l, t + bevel, l + bevel, t, ornamentPaint);
        canvas.drawLine(l + bevel, t, l + arm, t, ornamentPaint);
        // Правый верхний
        canvas.drawLine(ri - arm, t, ri - bevel, t, ornamentPaint);
        canvas.drawLine(ri - bevel, t, ri, t + bevel, ornamentPaint);
        canvas.drawLine(ri, t + bevel, ri, t + arm, ornamentPaint);
        // Левый нижний
        canvas.drawLine(l, bo - arm, l, bo - bevel, ornamentPaint);
        canvas.drawLine(l, bo - bevel, l + bevel, bo, ornamentPaint);
        canvas.drawLine(l + bevel, bo, l + arm, bo, ornamentPaint);
        // Правый нижний
        canvas.drawLine(ri - arm, bo, ri - bevel, bo, ornamentPaint);
        canvas.drawLine(ri - bevel, bo, ri, bo - bevel, ornamentPaint);
        canvas.drawLine(ri, bo - bevel, ri, bo - arm, ornamentPaint);

        // Короткая неоновая полоска-индикатор сверху для крупных карточек и попапов
        if (w >= 110f * dp && h >= 52f * dp) {
            float cx = rect.centerX();
            float seg = Math.min(24f * dp, w * 0.14f);
            float ty = rect.top + Math.max(2f * dp, strokePx);
            canvas.drawRect(cx - seg, ty - dp * 0.7f, cx + seg, ty + dp * 0.7f, accentDotPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        borderPaint.setAlpha(alpha);
        ornamentPaint.setAlpha(alpha);
        accentDotPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        borderPaint.setColorFilter(colorFilter);
        ornamentPaint.setColorFilter(colorFilter);
        accentDotPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
