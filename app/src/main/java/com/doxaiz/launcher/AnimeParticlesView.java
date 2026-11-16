package com.doxaiz.launcher;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

/**
 * Сверхлёгкий слой 2D-частиц (0 аллокаций в onDraw(), 60/90/120 FPS, без GPU-блюра):
 * - Сакура × Нефрит (Дунхуа): мягко кружащиеся лепестки сакуры и парящие золотые/нефритовые искры духовной ци.
 * - Кибер-Аниме Неон: голографические неоновые искры и тонкие световые штрихи (циан / сакура-неон / фиолет).
 * - Автоматическая пауза при сворачивании, выключении экрана, открытых оверлеях и режиме энергосбережения.
 */
public final class AnimeParticlesView extends View {

    private static final int PARTICLE_COUNT = 22;
    private static final int KIND_PETAL = 0;
    private static final int KIND_QI_GOLD = 1;
    private static final int KIND_QI_JADE = 2;

    private final float[] px = new float[PARTICLE_COUNT];
    private final float[] py = new float[PARTICLE_COUNT];
    private final float[] vx = new float[PARTICLE_COUNT];
    private final float[] vy = new float[PARTICLE_COUNT];
    private final float[] size = new float[PARTICLE_COUNT];
    private final float[] angle = new float[PARTICLE_COUNT];
    private final float[] vAngle = new float[PARTICLE_COUNT];
    private final float[] phase = new float[PARTICLE_COUNT];
    private final float[] baseAlpha = new float[PARTICLE_COUNT];
    private final int[] kind = new int[PARTICLE_COUNT];

    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path petalPath = new Path();
    private final Path sparkPath = new Path();
    private final float density;

    private int styleMode = Prefs.STYLE_SAKURA_JADE;
    private boolean userEnabled = true;
    private boolean hostActive = false;
    private boolean windowVisible = true;
    private boolean initialized = false;
    private boolean powerOrAnimationDisabled = false;
    private int widthPx = 0;
    private int heightPx = 0;
    private long lastFrameUptimeMs = 0L;
    private int rngState = 0x5A17D0A1;
    private boolean powerReceiverRegistered;
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateRunningState();
        }
    };

    public AnimeParticlesView(Context context) {
        super(context);
        this.density = Math.max(1f, context.getResources().getDisplayMetrics().density);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        shapePaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);

        // Форма лепестка сакуры в единичном радиусе [-1..+1] (создаётся один раз!)
        petalPath.moveTo(0f, -1.0f);
        petalPath.cubicTo(0.75f, -0.75f, 0.95f, 0.25f, 0.20f, 0.95f);
        petalPath.lineTo(0f, 0.72f);
        petalPath.lineTo(-0.20f, 0.95f);
        petalPath.cubicTo(-0.95f, 0.25f, -0.75f, -0.75f, 0f, -1.0f);
        petalPath.close();

        // Форма 4-лучевой искры ци (звёздочка-ромб) в единичном радиусе
        sparkPath.moveTo(0f, -1.0f);
        sparkPath.lineTo(0.28f, -0.28f);
        sparkPath.lineTo(1.0f, 0f);
        sparkPath.lineTo(0.28f, 0.28f);
        sparkPath.lineTo(0f, 1.0f);
        sparkPath.lineTo(-0.28f, 0.28f);
        sparkPath.lineTo(-1.0f, 0f);
        sparkPath.lineTo(-0.28f, -0.28f);
        sparkPath.close();
    }

    /** Чистая логика разрешения анимации (удобно покрывается pure-JVM тестами). */
    public static boolean shouldAnimate(boolean enabled, int styleMode, boolean hostActive,
                                        boolean windowVisible, boolean powerSaveMode) {
        return enabled
                && styleMode != Prefs.STYLE_CLASSIC_DARK
                && hostActive
                && windowVisible
                && !powerSaveMode;
    }

    public void applyConfig(int newStyleMode, boolean enabled) {
        int safeStyle = Prefs.sanitizeVisualStyle(newStyleMode);
        boolean styleChanged = (this.styleMode != safeStyle);
        this.styleMode = safeStyle;
        this.userEnabled = enabled;
        if (styleChanged && widthPx > 0 && heightPx > 0) {
            seedAllParticles();
        }
        updateRunningState();
    }

    public void setHostActive(boolean active) {
        if (this.hostActive == active) { updateRunningState(); return; }
        this.hostActive = active;
        updateRunningState();
    }

    private boolean isSystemPowerSaveOrNoAnim() {
        try {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) return true;
        } catch (Throwable ignored) {
        }
        try {
            float scale = Settings.Global.getFloat(
                    getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            );
            if (scale <= 0f) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean canRunNow() {
        return shouldAnimate(userEnabled, styleMode, hostActive, windowVisible, powerOrAnimationDisabled);
    }

    private void updateRunningState() {
        powerOrAnimationDisabled = isSystemPowerSaveOrNoAnim();
        boolean visible = userEnabled && styleMode != Prefs.STYLE_CLASSIC_DARK;
        setVisibility(visible ? VISIBLE : GONE);
        if (canRunNow()) {
            lastFrameUptimeMs = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
    }

    private float nextRand01() {
        int x = rngState;
        x ^= (x << 13);
        x ^= (x >>> 17);
        x ^= (x << 5);
        rngState = x;
        return (x & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }

    private void seedAllParticles() {
        if (widthPx <= 0 || heightPx <= 0) return;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            respawnParticle(i, true);
        }
        initialized = true;
    }

    private void respawnParticle(int i, boolean randomY) {
        int k;
        if (styleMode == Prefs.STYLE_SAKURA_JADE) {
            // 11 нежных лепестков сакуры + 6 золотых искр ци + 5 нефритовых искр ци
            k = (i < 11) ? KIND_PETAL : ((i < 17) ? KIND_QI_GOLD : KIND_QI_JADE);
        } else {
            // Кибер-Аниме: циан-голограммы, сакура-неон и фиолетовые искры
            k = i % 3;
        }
        kind[i] = k;
        phase[i] = nextRand01() * 6.283185f;
        angle[i] = nextRand01() * 360f;

        if (styleMode == Prefs.STYLE_SAKURA_JADE && k == KIND_PETAL) {
            px[i] = nextRand01() * widthPx;
            py[i] = randomY ? (nextRand01() * heightPx) : (-16f * density - nextRand01() * 60f * density);
            vx[i] = (-10f + nextRand01() * 26f) * density;
            vy[i] = (18f + nextRand01() * 24f) * density;
            size[i] = (4.2f + nextRand01() * 3.8f) * density;
            vAngle[i] = (-42f + nextRand01() * 84f);
            baseAlpha[i] = 0.42f + nextRand01() * 0.36f;
        } else {
            // Духовные искры ци и кибер-частицы плавно поднимаются вверх
            px[i] = nextRand01() * widthPx;
            py[i] = randomY ? (nextRand01() * heightPx) : (heightPx + 14f * density + nextRand01() * 50f * density);
            vx[i] = (-9f + nextRand01() * 18f) * density;
            vy[i] = -(14f + nextRand01() * 26f) * density;
            size[i] = (2.6f + nextRand01() * 3.4f) * density;
            vAngle[i] = (-28f + nextRand01() * 56f);
            baseAlpha[i] = 0.38f + nextRand01() * 0.44f;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        widthPx = w;
        heightPx = h;
        if (w > 0 && h > 0) {
            seedAllParticles();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = (visibility == VISIBLE);
        powerOrAnimationDisabled = isSystemPowerSaveOrNoAnim();
        if (canRunNow()) {
            lastFrameUptimeMs = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                getContext().registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else if (Build.VERSION.SDK_INT >= 26) {
                getContext().registerReceiver(powerReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                getContext().registerReceiver(powerReceiver, filter);
            }
            powerReceiverRegistered = true;
        } catch (RuntimeException ignored) { }
        windowVisible = getWindowVisibility() == VISIBLE;
        updateRunningState();
    }

    @Override
    protected void onDetachedFromWindow() {
        windowVisible = false;
        if (powerReceiverRegistered) {
            try { getContext().unregisterReceiver(powerReceiver); } catch (RuntimeException ignored) { }
            powerReceiverRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!userEnabled || styleMode == Prefs.STYLE_CLASSIC_DARK || widthPx <= 0 || heightPx <= 0) return;
        if (!initialized) seedAllParticles();

        long now = SystemClock.uptimeMillis();
        float dt = (lastFrameUptimeMs > 0L) ? ((now - lastFrameUptimeMs) / 1000f) : 0.016f;
        if (dt < 0f) dt = 0.016f;
        if (dt > 0.050f) dt = 0.050f; // Защита от скачков после паузы
        lastFrameUptimeMs = now;

        boolean running = canRunNow();
        float margin = 24f * density;

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            if (running) {
                phase[i] += dt * 1.45f;
                float sway = (float) Math.sin(phase[i]) * 12f * density;
                px[i] += (vx[i] + sway) * dt;
                py[i] += vy[i] * dt;
                angle[i] += vAngle[i] * dt;

                if (px[i] < -margin) px[i] = widthPx + margin;
                else if (px[i] > widthPx + margin) px[i] = -margin;

                if (vy[i] > 0f && py[i] > heightPx + margin) {
                    respawnParticle(i, false);
                } else if (vy[i] < 0f && py[i] < -margin) {
                    respawnParticle(i, false);
                }
            }

            float pulse = 0.65f + 0.35f * (float) Math.sin(phase[i] * 1.6f);
            int alpha = Math.max(18, Math.min(230, Math.round(baseAlpha[i] * pulse * 255f)));
            int k = kind[i];

            if (styleMode == Prefs.STYLE_SAKURA_JADE) {
                if (k == KIND_PETAL) {
                    // Нежный лепесток сакуры с лёгким жемчужным оттенком
                    shapePaint.setColor(Color.argb(alpha, 252, 180, 214));
                    canvas.save();
                    canvas.translate(px[i], py[i]);
                    canvas.rotate(angle[i]);
                    float sx = size[i] * (0.65f + 0.35f * Math.abs((float) Math.cos(phase[i] * 0.9f)));
                    canvas.scale(sx, size[i]);
                    canvas.drawPath(petalPath, shapePaint);
                    canvas.restore();
                } else {
                    // Золотые или нефритовые искры духовной ци
                    int r = (k == KIND_QI_GOLD) ? 245 : 45;
                    int g = (k == KIND_QI_GOLD) ? 196 : 212;
                    int b = (k == KIND_QI_GOLD) ? 81  : 191;
                    glowPaint.setColor(Color.argb(alpha / 3, r, g, b));
                    canvas.drawCircle(px[i], py[i], size[i] * 1.65f, glowPaint);

                    shapePaint.setColor(Color.argb(alpha, r, g, b));
                    canvas.save();
                    canvas.translate(px[i], py[i]);
                    canvas.rotate(angle[i] * 0.5f);
                    canvas.scale(size[i], size[i]);
                    canvas.drawPath(sparkPath, shapePaint);
                    canvas.restore();
                }
            } else {
                // Кибер-Аниме Неон: циан (#00E5FF), сакура-неон (#FF4D8D), звёздный фиолет (#A855F7)
                int r = (k == 0) ? 0   : (k == 1 ? 255 : 168);
                int g = (k == 0) ? 229 : (k == 1 ? 77  : 85);
                int b = (k == 0) ? 255 : (k == 1 ? 141 : 247);
                glowPaint.setColor(Color.argb(alpha / 3, r, g, b));
                canvas.drawCircle(px[i], py[i], size[i] * 1.5f, glowPaint);

                shapePaint.setColor(Color.argb(alpha, r, g, b));
                if (k == 0) {
                    // Вертикальный короткий неоновый лучик-данные
                    float hw = Math.max(1f, size[i] * 0.24f);
                    float hh = size[i] * 1.85f;
                    canvas.drawRect(px[i] - hw, py[i] - hh, px[i] + hw, py[i] + hh, shapePaint);
                } else {
                    canvas.save();
                    canvas.translate(px[i], py[i]);
                    canvas.rotate(45f);
                    canvas.scale(size[i] * 0.9f, size[i] * 0.9f);
                    canvas.drawPath(sparkPath, shapePaint);
                    canvas.restore();
                }
            }
        }

        if (running) {
            postInvalidateOnAnimation();
        }
    }
}
