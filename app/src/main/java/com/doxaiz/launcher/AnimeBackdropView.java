package com.doxaiz.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Слой встроенных обоев Doxaiz («Куруми × Сяо Бай — Дунхуа») с 0 аллокациями в onDraw():
 * - Фоновое декодирование из res/drawable-nodpi с точным inSampleSize под экран устройства (RGB_565).
 * - Автоматическая выгрузка Bitmap при выборе системных обоев или TRIM_MEMORY_BACKGROUND.
 * - Плавный параллакс при перелистывании страниц рабочего стола и мягкая виньетка сверху/снизу
 *   для высокой читаемости иконок и подписей без перекрытия лиц героинь.
 */
public final class AnimeBackdropView extends View {

    private static final float MAX_PARALLAX_SHIFT_FRACTION = 0.045f; // ~4.5% ширины для плавного параллакса

    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrimPaint = new Paint();
    private final Matrix drawMatrix = new Matrix();
    private final AtomicInteger loadGen = new AtomicInteger(0);
    private final ExecutorService decodeExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "doxaiz-wp-decode");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private volatile Bitmap wallpaperBitmap;
    private int wallpaperMode = Prefs.WALLPAPER_KURUMI_XIAOBAI;
    private int styleMode = Prefs.STYLE_SAKURA_JADE;
    private int dimLevel = Prefs.WALLPAPER_DIM_MEDIUM;
    private float parallaxNorm = 0f; // -1..+1
    private int viewW = 0;
    private int viewH = 0;
    private boolean matrixDirty = true;
    private boolean scrimDirty = true;
    private volatile boolean destroyed = false;

    public AnimeBackdropView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Чистый расчёт степени уменьшения (степень двойки) для экономии RAM без потери чёткости. */
    public static int computeSampleSize(int srcW, int srcH, int reqW, int reqH) {
        if (srcW <= 0 || srcH <= 0 || reqW <= 0 || reqH <= 0) return 1;
        int sample = 1;
        int halfW = srcW / 2;
        int halfH = srcH / 2;
        while ((halfW / sample) >= reqW && (halfH / sample) >= reqH) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    /** Чистые значения альфа-виньетки (верх, середина, низ) для выбранного уровня затемнения. */
    public static int[] computeScrimAlphas(int dimLevel) {
        return computeScrimAlphas(dimLevel, false);
    }

    /**
     * Альфы скрима для трёх зон экрана: верх (строка настроек), середина
     * (иконки), низ (док).
     *
     * @param systemWallpaper true — обои пользователя. Их яркость неизвестна,
     *                        поэтому скрим плотнее: даже белая картинка под ним
     *                        должна оставлять текст читаемым.
     */
    public static int[] computeScrimAlphas(int dimLevel, boolean systemWallpaper) {
        int safe = Prefs.sanitizeWallpaperDim(dimLevel);
        int top;
        int mid;
        int bottom;
        if (safe == Prefs.WALLPAPER_DIM_LIGHT) {
            top = 88; mid = 18; bottom = 124;
        } else if (safe == Prefs.WALLPAPER_DIM_STRONG) {
            top = 162; mid = 68; bottom = 206;
        } else {
            // MEDIUM — баланс: встроенные обои остаются яркими, текст читается
            top = 126; mid = 36; bottom = 168;
        }
        if (systemWallpaper) {
            // Нижние границы посчитаны, а не выбраны на глаз: белый текст на
            // белых обоях даёт 4,66:1 уже при 145/255 в средней зоне (порог
            // WCAG AA — 4,5:1). Верх и низ плотнее, потому что там текст
            // мельче и лежит на самом краю экрана.
            top = Math.max(top, 190);
            mid = Math.max(mid, 150);
            bottom = Math.max(bottom, 215);
        }
        return new int[]{top, mid, bottom};
    }

    public void applyConfig(int newWallpaperMode, int newStyleMode, int newDimLevel) {
        int safeWp = Prefs.sanitizeWallpaperMode(newWallpaperMode);
        int safeStyle = Prefs.sanitizeVisualStyle(newStyleMode);
        int safeDim = Prefs.sanitizeWallpaperDim(newDimLevel);
        boolean wpChanged = (this.wallpaperMode != safeWp);
        boolean scrimChanged = (this.styleMode != safeStyle || this.dimLevel != safeDim
                || this.wallpaperMode != safeWp);

        this.wallpaperMode = safeWp;
        this.styleMode = safeStyle;
        this.dimLevel = safeDim;

        // При системных обоях вью БОЛЬШЕ НЕ ПРЯЧЕТСЯ: оно единственный слой,
        // который притемняет обои под белыми подписями рабочего стола. Раньше
        // здесь стоял setVisibility(GONE), поэтому на светлой картинке контраст
        // подписей падал до 1,2:1 при пороге WCAG 4,5:1.
        if (safeWp == Prefs.WALLPAPER_SYSTEM) {
            releaseBitmapInternal();
            scrimDirty = true;
            rebuildScrimIfNeeded();
            setVisibility(scrimAlphaIsUseful() ? VISIBLE : GONE);
            invalidate();
            return;
        }

        setVisibility(VISIBLE);
        if (scrimChanged) {
            scrimDirty = true;
            rebuildScrimIfNeeded();
        }
        if (wpChanged || wallpaperBitmap == null) {
            ensureBitmapLoaded();
        }
        invalidate();
    }

    public void setParallaxNormalized(float norm) {
        float clamped = Math.max(-1f, Math.min(1f, norm));
        if (Math.abs(clamped - parallaxNorm) < 0.001f) return;
        parallaxNorm = clamped;
        matrixDirty = true;
        if (wallpaperMode == Prefs.WALLPAPER_KURUMI_XIAOBAI && wallpaperBitmap != null) {
            invalidate();
        }
    }

    public void onTrimMemoryCache() {
        // Recreated on the next foreground resume. No explicit recycle: the hardware
        // renderer may still reference this bitmap in a recorded display list.
        releaseBitmapInternal();
    }

    public void destroy() {
        destroyed = true;
        loadGen.incrementAndGet();
        releaseBitmapInternal();
        decodeExec.shutdownNow();
    }

    private void releaseBitmapInternal() {
        loadGen.incrementAndGet();
        // Hardware rendering may still hold a display-list reference; let GC reclaim it safely.
        wallpaperBitmap = null;
        invalidate();
    }

    private void ensureBitmapLoaded() {
        if (destroyed || wallpaperMode != Prefs.WALLPAPER_KURUMI_XIAOBAI) return;
        if (wallpaperBitmap != null && !wallpaperBitmap.isRecycled()) return;
        final int gen = loadGen.incrementAndGet();
        final Context appCtx = getContext().getApplicationContext();
        final int targetW = viewW > 0 ? viewW : appCtx.getResources().getDisplayMetrics().widthPixels;
        final int targetH = viewH > 0 ? viewH : appCtx.getResources().getDisplayMetrics().heightPixels;
        try {
            decodeExec.execute(() -> {
                if (destroyed || loadGen.get() != gen) return;
                Bitmap decoded = null;
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    bounds.inScaled = false;
                    BitmapFactory.decodeResource(appCtx.getResources(), R.drawable.wallpaper_kurumi_xiaobai, bounds);

                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inScaled = false;
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    opts.inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH);
                    decoded = BitmapFactory.decodeResource(appCtx.getResources(), R.drawable.wallpaper_kurumi_xiaobai, opts);
                } catch (Throwable ignored) {
                }
                final Bitmap result = decoded;
                post(() -> {
                    if (destroyed || loadGen.get() != gen || wallpaperMode != Prefs.WALLPAPER_KURUMI_XIAOBAI) {
                        if (result != null && !result.isRecycled()) result.recycle();
                        return;
                    }
                    wallpaperBitmap = result;
                    matrixDirty = true;
                    invalidate();
                });
            });
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewW = w;
        viewH = h;
        matrixDirty = true;
        scrimDirty = true;
        rebuildScrimIfNeeded();
        if (wallpaperMode == Prefs.WALLPAPER_KURUMI_XIAOBAI && wallpaperBitmap == null) {
            ensureBitmapLoaded();
        }
    }

    private void rebuildMatrixIfNeeded(Bitmap bmp) {
        if (!matrixDirty || bmp == null || viewW <= 0 || viewH <= 0) return;
        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) return;

        // Запас ширины под плавный параллакс без чёрных полос по краям
        float neededW = viewW * (1f + MAX_PARALLAX_SHIFT_FRACTION * 2f);
        float scale = Math.max(neededW / (float) bw, (float) viewH / (float) bh);
        float scaledW = bw * scale;
        float scaledH = bh * scale;

        float maxShiftX = Math.max(0f, (scaledW - viewW) * 0.5f);
        float dx = (viewW - scaledW) * 0.5f - (parallaxNorm * Math.min(maxShiftX, viewW * MAX_PARALLAX_SHIFT_FRACTION));
        // Лёгкий фокус к верхней части (0.34 вместо 0.50), чтобы лица Куруми и Сяо Бай были идеально видны
        float dy = (viewH - scaledH) * 0.34f;

        drawMatrix.reset();
        drawMatrix.postScale(scale, scale);
        drawMatrix.postTranslate(dx, dy);
        matrixDirty = false;
    }

    /**
     * Нужен ли слой скрима: если все три альфы нулевые, слой можно не включать.
     * Отдельный метод, а не «всегда VISIBLE», чтобы пустой слой не участвовал
     * в композиции на слабых устройствах.
     */
    private boolean scrimAlphaIsUseful() {
        int[] a = computeScrimAlphas(dimLevel, wallpaperMode != Prefs.WALLPAPER_KURUMI_XIAOBAI);
        return a[0] > 0 || a[1] > 0 || a[2] > 0;
    }

    private void rebuildScrimIfNeeded() {
        if (!scrimDirty || viewH <= 0) return;
        int[] a = computeScrimAlphas(dimLevel, wallpaperMode != Prefs.WALLPAPER_KURUMI_XIAOBAI);
        int baseR = (styleMode == Prefs.STYLE_CYBER_ANIME) ? 6 : 7;
        int baseG = (styleMode == Prefs.STYLE_CYBER_ANIME) ? 8 : 8;
        int baseB = (styleMode == Prefs.STYLE_CYBER_ANIME) ? 22 : 16;

        int cTop = Color.argb(a[0], baseR, baseG, baseB);
        int cMid = Color.argb(a[1], baseR, baseG, baseB);
        int cBot = Color.argb(a[2], baseR, baseG, baseB);

        scrimPaint.setShader(new LinearGradient(
                0f, 0f, 0f, viewH,
                new int[]{cTop, cMid, cMid, cBot},
                new float[]{0f, 0.24f, 0.72f, 1f},
                Shader.TileMode.CLAMP
        ));
        scrimDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Скрим рисуется ВСЕГДА — и поверх встроенных обоев, и поверх системных.
        //
        // Раньше при системных обоях здесь был ранний return: вью не рисовало
        // ничего, и белые подписи рабочего стола лежали прямо на картинке
        // пользователя. На светлых обоях контраст падал до 1,2:1 при пороге
        // WCAG 4,5:1 — подписи не читались. Системные обои видны через
        // прозрачное окно (windowShowWallpaper), поэтому скрим не «закрывает»
        // их, а только притемняет под текстом.
        if (wallpaperMode == Prefs.WALLPAPER_KURUMI_XIAOBAI) {
            Bitmap bmp = wallpaperBitmap;
            if (bmp != null && !bmp.isRecycled()) {
                rebuildMatrixIfNeeded(bmp);
                canvas.drawBitmap(bmp, drawMatrix, bmpPaint);
            } else {
                // Быстрая тёмно-ночная подложка, пока фоновый поток декодирует Bitmap (<15мс)
                canvas.drawColor(styleMode == Prefs.STYLE_CYBER_ANIME ? 0xFF060814 : 0xFF07080F);
            }
        }
        canvas.drawRect(0f, 0f, viewW, viewH, scrimPaint);
    }
}
