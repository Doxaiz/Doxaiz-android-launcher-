package com.doxaiz.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Dynamic Bar v3.4 — событийная капсула в стиле Magic Ring вокруг выреза камеры.
 *
 * Главный принцип:
 *  - НЕ висит постоянно на экране (по умолчанию View.GONE).
 *  - Зарядка появляется только в момент подключения кабеля или достижения 100% и сама скрывается через 5 сек.
 *  - Музыка появляется, когда плеер реально играет в фоне (свёрнут на рабочий стол), и сама скрывается через 10 сек после паузы.
 *  - Звонок и диктофон видны только пока активны.
 *  - Свайп вверх по капсуле мгновенно скрывает текущее событие.
 *  - 0 фоновых служб и циклов, минимальная нагрузка на CPU/батарею.
 */
public final class DynamicBarHelper {

    public static final long CHARGE_AUTO_HIDE_MS = 5000L;
    public static final long LOW_BATTERY_AUTO_HIDE_MS = 6000L;
    public static final long MEDIA_PAUSE_AUTO_HIDE_MS = 10000L;
    public static final long UNLOCK_PULSE_MS = 2200L;

    public interface Host {
        Prefs getPrefs();
        void onDynamicBarPrefsChanged();
        void showToast(String msg);
    }

    private final Activity activity;
    private final Host host;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FrameLayout capsuleContainer;
    private ImageView compactLeftIcon;
    private ImageView compactAlbumThumb;
    private TextView compactLeftValue;
    private View cameraGapView;
    private TextView compactTitle;
    private ImageView compactRightIcon;
    private TextView compactRightBadge;
    private LinearLayout expandedCard;

    private boolean expanded = false;
    private boolean batteryInitialized = false;
    private boolean charging = false;
    private int batteryPct = -1;
    private boolean batteryFull = false;

    // Временные окна умного автоскрытия (uptimeMillis)
    private static final long RINGER_AUTO_HIDE_MS = 2600L;
    private long chargeVisibleUntil = 0L;
    private long lowBatteryVisibleUntil = 0L;
    private int lastLowBatteryThreshold = 100;
    private long unlockFlashUntil = 0L;
    private long mediaPauseVisibleUntil = 0L;
    private long ringerVisibleUntil = 0L;
    private int lastRingerMode = -1;
    private boolean mediaWasPlaying = false;
    private String lastMediaTrackId = "";

    // Свайп вверх для ручного скрытия конкретного события
    private String dismissedEventSig = "";
    private float touchDownY = 0f;
    private float touchDownX = 0f;
    private boolean swipeDismissConsumed = false;

    private int cachedStatusBarHeight = 0;
    private String lastRenderSig = "";
    private boolean animatingHide = false;
    private boolean suppressedByOverlay = false;

    private final Runnable autoRefreshRunnable = this::refresh;

    public DynamicBarHelper(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    /**
     * Чистая логика видимости события зарядки (покрывается unit-тестами).
     */
    static boolean isChargeEventVisible(boolean enabled, boolean isCharging,
                                        boolean autoHide, long nowMs, long visibleUntilMs) {
        if (!enabled || !isCharging) return false;
        if (!autoHide) return true;
        return nowMs < visibleUntilMs;
    }

    /**
     * Чистая логика срабатывания предупреждения о низком заряде (пороги 15%, 10%, 5% без зарядки).
     */
    static int matchLowBatteryThreshold(int pct, boolean isCharging, int lastNotifiedThreshold) {
        if (isCharging || pct <= 0 || pct > 15) return -1;
        int tier = (pct <= 5) ? 5 : ((pct <= 10) ? 10 : 15);
        return (tier < lastNotifiedThreshold) ? tier : -1;
    }

    /**
     * Чистая логика видимости свёрнутого медиаплеера (покрывается unit-тестами).
     */
    static boolean isMediaEventVisible(boolean enabled, boolean hasMedia, boolean isPlaying,
                                       boolean autoHide, long nowMs, long pauseVisibleUntilMs) {
        if (!enabled || !hasMedia) return false;
        if (isPlaying) return true;
        if (!autoHide) return true;
        return nowMs < pauseVisibleUntilMs;
    }

    public View buildOverlayView(int statusBarHeight) {
        this.cachedStatusBarHeight = statusBarHeight;
        capsuleContainer = new FrameLayout(activity);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = computeCapsuleTopMargin();
        capsuleContainer.setLayoutParams(lp);
        capsuleContainer.setElevation(dp(12));
        capsuleContainer.setVisibility(View.GONE);
        capsuleContainer.setClipToPadding(false);

        LinearLayout inner = new LinearLayout(activity);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setShape(GradientDrawable.RECTANGLE);
        pillBg.setCornerRadius(dp(22));
        pillBg.setColor(0xFF000000);
        pillBg.setStroke(dp(1), 0x38FFFFFF);
        inner.setBackground(pillBg);
        inner.setPadding(dp(14), dp(6), dp(14), dp(6));

        LinearLayout compactRow = new LinearLayout(activity);
        compactRow.setOrientation(LinearLayout.HORIZONTAL);
        compactRow.setGravity(Gravity.CENTER_VERTICAL);
        compactRow.setMinimumHeight(dp(24));

        compactAlbumThumb = new ImageView(activity);
        compactAlbumThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        compactAlbumThumb.setBackground(GlassTheme.makeRoundedRect(0xFF242427, dp(6)));
        compactAlbumThumb.setClipToOutline(true);
        compactAlbumThumb.setVisibility(View.GONE);
        LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        thLp.rightMargin = dp(7);
        compactRow.addView(compactAlbumThumb, thLp);

        compactLeftIcon = new ImageView(activity);
        compactLeftIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        icLp.rightMargin = dp(6);
        compactRow.addView(compactLeftIcon, icLp);

        compactLeftValue = new TextView(activity);
        compactLeftValue.setTextColor(GlassTheme.ACCENT_GREEN);
        compactLeftValue.setTextSize(12f);
        compactLeftValue.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        compactLeftValue.setVisibility(View.GONE);
        compactRow.addView(compactLeftValue);

        // Симметричный зазор под объектив фронтальной камеры (в стиле Magic Ring)
        cameraGapView = new View(activity);
        cameraGapView.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(1)));
        cameraGapView.setVisibility(View.GONE);
        compactRow.addView(cameraGapView);

        compactTitle = new TextView(activity);
        compactTitle.setTextColor(Color.WHITE);
        compactTitle.setTextSize(12f);
        compactTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        compactTitle.setSingleLine(true);
        compactTitle.setEllipsize(TextUtils.TruncateAt.END);
        compactTitle.setMaxWidth(dp(156));
        compactRow.addView(compactTitle);

        compactRightBadge = new TextView(activity);
        compactRightBadge.setTextColor(GlassTheme.ACCENT_GREEN);
        compactRightBadge.setTextSize(12f);
        compactRightBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        compactRightBadge.setPadding(dp(7), 0, 0, 0);
        compactRow.addView(compactRightBadge);

        compactRightIcon = new ImageView(activity);
        compactRightIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        compactRightIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams riLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        riLp.leftMargin = dp(7);
        compactRow.addView(compactRightIcon, riLp);

        inner.addView(compactRow);

        expandedCard = new LinearLayout(activity);
        expandedCard.setOrientation(LinearLayout.VERTICAL);
        expandedCard.setVisibility(View.GONE);
        expandedCard.setPadding(0, dp(8), 0, dp(4));
        inner.addView(expandedCard);

        capsuleContainer.addView(inner);
        capsuleContainer.setOnTouchListener((v, ev) -> handleCapsuleTouch(v, ev));

        updateBatteryFromStickyIntent();
        refresh();
        return capsuleContainer;
    }

    private boolean handleCapsuleTouch(View v, MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownY = ev.getRawY();
                touchDownX = ev.getRawX();
                swipeDismissConsumed = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = ev.getRawY() - touchDownY;
                if (dy < -dp(22) && !swipeDismissConsumed) {
                    swipeDismissConsumed = true;
                    performHaptic(v);
                    dismissCurrentEvent();
                    return true;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!swipeDismissConsumed) {
                    float dx = Math.abs(ev.getRawX() - touchDownX);
                    float dy = Math.abs(ev.getRawY() - touchDownY);
                    if (dx < dp(16) && dy < dp(16)) {
                        performHaptic(v);
                        toggleExpanded();
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                swipeDismissConsumed = false;
                return false;
            default:
                return false;
        }
    }

    private void dismissCurrentEvent() {
        expanded = false;
        dismissedEventSig = currentBaseEventKey();
        chargeVisibleUntil = 0L;
        lowBatteryVisibleUntil = 0L;
        unlockFlashUntil = 0L;
        mediaPauseVisibleUntil = 0L;
        hideCapsuleAnimated();
    }

    public void triggerUnlockPulse() {
        Prefs p = host.getPrefs();
        if (!p.isDynamicBarEnabled() || !p.isDynamicBarUnlock()) return;
        dismissedEventSig = "";
        unlockFlashUntil = SystemClock.uptimeMillis() + UNLOCK_PULSE_MS;
        scheduleRefreshAfter(UNLOCK_PULSE_MS + 60L);
        refresh();
    }

    public void updateBatteryIntent(Intent intent) {
        if (intent == null) return;
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        boolean isChg = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        boolean isFull = (status == BatteryManager.BATTERY_STATUS_FULL) || (isChg && level >= 100);
        int pct = (level >= 0 && scale > 0) ? Math.round((level * 100f) / scale) : -1;

        boolean prevCharging = charging;
        boolean prevFull = batteryFull;
        boolean changed = (isChg != charging) || (pct != batteryPct) || (isFull != batteryFull);

        charging = isChg;
        batteryPct = pct;
        batteryFull = isFull;

        if (!batteryInitialized) {
            // Первый холодный замер: запоминаем состояние, но не показываем «висящую» зарядку
            batteryInitialized = true;
            if (!isChg) chargeVisibleUntil = 0L;
            return;
        }

        // Реальное событие: подключили кабель или батарея только что достигла 100%
        if ((!prevCharging && isChg) || (!prevFull && isFull && isChg)) {
            dismissedEventSig = "";
            chargeVisibleUntil = SystemClock.uptimeMillis() + CHARGE_AUTO_HIDE_MS;
            scheduleRefreshAfter(CHARGE_AUTO_HIDE_MS + 60L);
            refresh();
            return;
        }

        if (!isChg) {
            chargeVisibleUntil = 0L;
            if (pct > 18) {
                lastLowBatteryThreshold = 100;
                lowBatteryVisibleUntil = 0L;
            } else {
                int tier = matchLowBatteryThreshold(pct, false, lastLowBatteryThreshold);
                if (tier > 0 && host.getPrefs().isDynamicBarLowBattery()) {
                    lastLowBatteryThreshold = tier;
                    dismissedEventSig = "";
                    lowBatteryVisibleUntil = SystemClock.uptimeMillis() + LOW_BATTERY_AUTO_HIDE_MS;
                    scheduleRefreshAfter(LOW_BATTERY_AUTO_HIDE_MS + 60L);
                    refresh();
                    return;
                }
            }
            if (prevCharging) {
                refresh();
            }
            return;
        }
        lowBatteryVisibleUntil = 0L;
        lastLowBatteryThreshold = 100;

        if (changed) {
            refresh();
        }
    }

    public void onRingerModeChanged(int ringerMode) {
        if (!host.getPrefs().isDynamicBarEnabled() || !host.getPrefs().isDynamicBarRinger()) return;
        if (lastRingerMode == -1) {
            // Игнорируем первый sticky-вызов при старте, чтобы капсула не мигала без действия пользователя
            lastRingerMode = ringerMode;
            return;
        }
        if (ringerMode == lastRingerMode) return;
        lastRingerMode = ringerMode;
        ringerVisibleUntil = SystemClock.uptimeMillis() + RINGER_AUTO_HIDE_MS;
        dismissedEventSig = "";
        scheduleRefreshAfter(RINGER_AUTO_HIDE_MS + 60L);
        refresh();
    }

    public void updateBatteryFromStickyIntent() {
        try {
            Intent sticky = activity.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) updateBatteryIntent(sticky);
        } catch (Throwable ignored) {
        }
    }

    public void collapse() {
        if (expanded) {
            expanded = false;
            if (expandedCard != null) expandedCard.setVisibility(View.GONE);
        }
    }

    private Dialog settingsDialog;

    public void destroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (settingsDialog != null && settingsDialog.isShowing()) {
            try { settingsDialog.dismiss(); } catch (Throwable ignored) {}
        }
        settingsDialog = null;
    }

    private void toggleExpanded() {
        expanded = !expanded;
        // Если пользователь сам раскрыл капсулу зарядки/паузы — продлеваем окно, чтобы успел посмотреть/нажать
        if (expanded) {
            long now = SystemClock.uptimeMillis();
            if (chargeVisibleUntil > now) {
                chargeVisibleUntil = now + 9000L;
                scheduleRefreshAfter(9060L);
            }
            if (mediaPauseVisibleUntil > now) {
                mediaPauseVisibleUntil = now + 15000L;
                scheduleRefreshAfter(15060L);
            }
        }
        refresh();
    }

    private void scheduleRefreshAfter(long delayMs) {
        mainHandler.removeCallbacks(autoRefreshRunnable);
        mainHandler.postDelayed(autoRefreshRunnable, Math.max(100L, delayMs));
    }

    /**
     * Doxaiz 4.9: Точный расчёт зазора под физический центральный вырез камеры (DisplayCutout).
     * Возвращает 0, если вырез сбоку/в углу (камера слева/справа), в альбомной ориентации на боковой грани,
     * либо отсутствует на данном устройстве.
     */
    static int computeCenterCutoutGapPx(int screenW, int screenH,
                                        int rectLeft, int rectTop, int rectRight, int rectBottom,
                                        int minGapPx, int maxGapPx, int padPx) {
        if (screenW <= 0 || screenH <= 0) return 0;
        int rw = rectRight - rectLeft;
        int rh = rectBottom - rectTop;
        if (rw <= 0 || rh <= 0) return 0;
        // Интересуют только вырезы, примыкающие к ВЕРХНЕМУ краю экрана (игнорируем боковые/нижние в landscape)
        if (rectTop > Math.max(12, screenH / 40)) return 0;
        if (rh > screenH / 5) return 0;
        // Слишком широкий вырез (> 45% ширины) — это не компактная камера-островок
        if (rw > Math.round(screenW * 0.45f)) return 0;
        float centerX = (rectLeft + rectRight) * 0.5f;
        // Вырез считается центральным только если его центр лежит в центральной трети экрана
        if (centerX < screenW * 0.33f || centerX > screenW * 0.67f) return 0;
        int gap = rw + Math.max(0, padPx);
        return Math.max(minGapPx, Math.min(maxGapPx, gap));
    }

    private int resolveCenterCameraCutoutGapPx() {
        try {
            if (Build.VERSION.SDK_INT >= 28 && activity.getWindow() != null) {
                WindowInsets wi = activity.getWindow().getDecorView().getRootWindowInsets();
                if (wi != null) {
                    DisplayCutout cutout = wi.getDisplayCutout();
                    if (cutout != null && cutout.getSafeInsetTop() > dp(16)) {
                        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
                        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
                        if (Build.VERSION.SDK_INT >= 30) {
                            try {
                                Rect b = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
                                if (b.width() > 0 && b.height() > 0) {
                                    screenW = b.width();
                                    screenH = b.height();
                                }
                            } catch (Throwable ignored) {}
                        }
                        if (Build.VERSION.SDK_INT >= 29) {
                            Rect topRect = cutout.getBoundingRectTop();
                            if (topRect != null && !topRect.isEmpty()) {
                                return computeCenterCutoutGapPx(
                                        screenW, screenH,
                                        topRect.left, topRect.top, topRect.right, topRect.bottom,
                                        dp(22), dp(52), dp(4));
                            }
                        }
                        java.util.List<Rect> rects = cutout.getBoundingRects();
                        if (rects != null) {
                            for (Rect r : rects) {
                                if (r == null || r.isEmpty()) continue;
                                int g = computeCenterCutoutGapPx(
                                        screenW, screenH,
                                        r.left, r.top, r.right, r.bottom,
                                        dp(22), dp(52), dp(4));
                                if (g > 0) return g;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private int computeCapsuleTopMargin() {
        int pos = host.getPrefs().getDynamicBarPosition();
        int sb = cachedStatusBarHeight > 0 ? cachedStatusBarHeight : dp(28);
        if (pos == Prefs.DBAR_POS_BELOW) {
            return sb + dp(4);
        }
        if (pos == Prefs.DBAR_POS_COMPACT) {
            return dp(4);
        }
        try {
            if (Build.VERSION.SDK_INT >= 28 && activity.getWindow() != null) {
                WindowInsets wi = activity.getWindow().getDecorView().getRootWindowInsets();
                if (wi != null) {
                    DisplayCutout cutout = wi.getDisplayCutout();
                    if (cutout != null && cutout.getSafeInsetTop() > dp(22)) {
                        int safeTop = cutout.getSafeInsetTop();
                        return Math.max(dp(5), Math.min(safeTop - dp(25), dp(12)));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return Math.max(dp(5), sb > dp(32) ? dp(8) : dp(5));
    }

    public void updateTopPosition() {
        if (capsuleContainer != null && capsuleContainer.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) capsuleContainer.getLayoutParams();
            int nextTop = computeCapsuleTopMargin();
            if (lp.topMargin != nextTop) {
                lp.topMargin = nextTop;
                capsuleContainer.setLayoutParams(lp);
            }
        }
    }

    private boolean isScreenInteractive() {
        try {
            PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private String currentBaseEventKey() {
        int idx = lastRenderSig.indexOf('|');
        return idx > 0 ? lastRenderSig.substring(0, idx) : lastRenderSig;
    }

    private void hideCapsuleAnimated() {
        if (capsuleContainer == null) return;
        lastRenderSig = "EMPTY";
        expanded = false;
        if (capsuleContainer.getVisibility() != View.VISIBLE || animatingHide) {
            capsuleContainer.setVisibility(View.GONE);
            return;
        }
        if (!host.getPrefs().isSmoothAnimationsEnabled()) {
            capsuleContainer.setVisibility(View.GONE);
            return;
        }
        animatingHide = true;
        capsuleContainer.animate().cancel();
        capsuleContainer.animate()
                .alpha(0f)
                .scaleX(0.58f)
                .scaleY(0.76f)
                .translationY(-dp(4))
                .setDuration(190L)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        animatingHide = false;
                        capsuleContainer.setVisibility(View.GONE);
                        capsuleContainer.setAlpha(1f);
                        capsuleContainer.setScaleX(1f);
                        capsuleContainer.setScaleY(1f);
                        capsuleContainer.setTranslationY(0f);
                        capsuleContainer.animate().setListener(null);
                    }
                })
                .start();
    }

    /**
     * Doxaiz 4.4: Временно прячет хот-бар, пока поверх открыта Шторка, Недавние или панель звука,
     * чтобы элементы никогда не накладывались друг на друга.
     */
    public void setSuppressedByOverlay(boolean suppressed) {
        if (this.suppressedByOverlay == suppressed) return;
        this.suppressedByOverlay = suppressed;
        if (suppressed) {
            hideCapsuleAnimated();
        } else {
            refresh();
        }
    }

    public void refresh() {
        if (capsuleContainer == null) return;
        if (!isScreenInteractive()) {
            // Раньше здесь был просто выход. lastRenderSig оставался от события,
            // показанного до гашения экрана, а сама капсула при разблокировке
            // ещё считается «видимой» — сравнение сигнатур совпадало, и бар
            // оставался скрытым до следующей смены события. Сбрасываем снимок,
            // чтобы после разблокировки состояние перерисовалось.
            lastRenderSig = "";
            return;
        }
        if (suppressedByOverlay) {
            expanded = false;
            lastRenderSig = "SUPPRESSED";
            capsuleContainer.animate().cancel();
            animatingHide = false;
            capsuleContainer.setVisibility(View.GONE);
            return;
        }
        Prefs p = host.getPrefs();
        if (!p.isDynamicBarEnabled()) {
            expanded = false;
            lastRenderSig = "OFF";
            capsuleContainer.animate().cancel();
            animatingHide = false;
            capsuleContainer.setVisibility(View.GONE);
            return;
        }
        updateTopPosition();

        final long now = SystemClock.uptimeMillis();
        final boolean autoHide = p.isDynamicBarAutoHide();

        String callLbl = p.isDynamicBarCalls() ? DoxaizNotificationService.getActiveCallLabel() : null;
        String recLbl = p.isDynamicBarRecorder() ? DoxaizNotificationService.getActiveRecorderLabel() : null;
        String timerLbl = p.isDynamicBarTimer() ? DoxaizNotificationService.getActiveTimerLabel() : null;
        String hotspotLbl = p.isDynamicBarHotspot() ? DoxaizNotificationService.getActiveHotspotLabel() : null;
        DoxaizNotificationService.MediaInfo media = p.isDynamicBarMedia()
                ? DoxaizNotificationService.getActiveMediaInfo() : null;

        // Умное отслеживание перехода музыки Play -> Pause (10 сек автоскрытия)
        boolean mediaPlaying = false;
        if (media != null) {
            mediaPlaying = DoxaizNotificationService.isMediaPlaying(activity, media);
            String trackId = media.packageName + ":" + media.title;
            if (mediaPlaying) {
                if (!trackId.equals(lastMediaTrackId) || !mediaWasPlaying) {
                    dismissedEventSig = "";
                }
                mediaWasPlaying = true;
                mediaPauseVisibleUntil = 0L;
            } else {
                if (mediaWasPlaying) {
                    // Только что поставили на паузу — оставляем на 10 сек для удобного возврата
                    mediaWasPlaying = false;
                    if (autoHide) {
                        mediaPauseVisibleUntil = now + MEDIA_PAUSE_AUTO_HIDE_MS;
                        scheduleRefreshAfter(MEDIA_PAUSE_AUTO_HIDE_MS + 60L);
                    }
                }
            }
            lastMediaTrackId = trackId;
        } else {
            mediaWasPlaying = false;
            mediaPauseVisibleUntil = 0L;
            lastMediaTrackId = "";
        }

        boolean showCharge = isChargeEventVisible(
                p.isDynamicBarCharging(), charging, autoHide, now, chargeVisibleUntil);
        boolean showLowBat = p.isDynamicBarLowBattery() && !charging && batteryPct > 0 && batteryPct <= 15
                && (autoHide ? now < lowBatteryVisibleUntil : true);
        boolean showMedia = isMediaEventVisible(
                p.isDynamicBarMedia(), media != null, mediaPlaying, autoHide, now, mediaPauseVisibleUntil);
        boolean showRinger = p.isDynamicBarRinger() && lastRingerMode >= 0 && now < ringerVisibleUntil;
        boolean showUnlock = p.isDynamicBarUnlock() && now < unlockFlashUntil;

        // Приоритет: 1) Звонок 2) Диктофон 9) Режим звука (2.6с) 3) Зарядка (5с) 4) Низкий заряд (6с) 5) Таймер 6) Музыка 7) Точка доступа 8) Разблокировка
        int mode = 0;
        String eventKey = "";
        if (callLbl != null) {
            mode = 1;
            eventKey = "CALL:" + callLbl;
        } else if (recLbl != null) {
            mode = 2;
            eventKey = "REC:" + recLbl;
        } else if (showRinger) {
            mode = 9;
            eventKey = "RNG:" + lastRingerMode;
        } else if (showCharge) {
            mode = 3;
            eventKey = "CHG:" + (batteryFull ? "FULL" : "ON");
        } else if (showLowBat) {
            mode = 6;
            eventKey = "LOWBAT:" + batteryPct;
        } else if (timerLbl != null) {
            mode = 7;
            eventKey = "TMR:" + timerLbl;
        } else if (showMedia) {
            mode = 4;
            eventKey = "MED:" + media.packageName + ":" + media.title + ":" + (mediaPlaying ? "1" : "0");
        } else if (hotspotLbl != null) {
            mode = 8;
            eventKey = "HSP:" + hotspotLbl;
        } else if (showUnlock) {
            mode = 5;
            eventKey = "UNLOCK";
        }

        if (mode == 0 || (!dismissedEventSig.isEmpty() && dismissedEventSig.equals(eventKey))) {
            hideCapsuleAnimated();
            return;
        }

        int cameraGapPx = (p.getDynamicBarPosition() == Prefs.DBAR_POS_AUTO && !expanded)
                ? resolveCenterCameraCutoutGapPx() : 0;
        boolean useCameraGap = (cameraGapPx > 0);

        String sig = eventKey + "|" + expanded + "|" + p.getDynamicBarPosition() + "|" + cameraGapPx
                + "|" + p.isShowBatteryPct() + "|" + batteryPct + "|" + batteryFull + "|" + mediaPlaying
                + "|" + (media != null ? media.artist : "");

        boolean wasHidden = capsuleContainer.getVisibility() != View.VISIBLE || animatingHide;
        if (!wasHidden && sig.equals(lastRenderSig)) {
            return;
        }
        lastRenderSig = sig;

        if (animatingHide) {
            capsuleContainer.animate().cancel();
            capsuleContainer.animate().setListener(null);
            animatingHide = false;
        }

        capsuleContainer.setVisibility(View.VISIBLE);
        if (wasHidden) {
            if (p.isSmoothAnimationsEnabled()) {
                capsuleContainer.animate().cancel();
                capsuleContainer.setAlpha(0f);
                capsuleContainer.setScaleX(0.56f);
                capsuleContainer.setScaleY(0.76f);
                capsuleContainer.setTranslationY(-dp(3));
                capsuleContainer.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(250L)
                        .setInterpolator(new OvershootInterpolator(1.06f))
                        .start();
            } else {
                capsuleContainer.setAlpha(1f);
                capsuleContainer.setScaleX(1f);
                capsuleContainer.setScaleY(1f);
                capsuleContainer.setTranslationY(0f);
            }
        }

        // Сбрасываем дополнительные элементы компактной строки
        compactAlbumThumb.setVisibility(View.GONE);
        compactLeftIcon.setVisibility(View.VISIBLE);
        compactLeftValue.setVisibility(View.GONE);
        compactRightIcon.setVisibility(View.GONE);
        compactRightBadge.setVisibility(View.VISIBLE);
        cameraGapView.setVisibility(useCameraGap ? View.VISIBLE : View.GONE);
        if (useCameraGap) {
            ViewGroup.LayoutParams gLp = cameraGapView.getLayoutParams();
            if (gLp != null && gLp.width != cameraGapPx) {
                gLp.width = cameraGapPx;
                cameraGapView.setLayoutParams(gLp);
            }
        }
        expandedCard.removeAllViews();

        switch (mode) {
            case 1:
                compactLeftIcon.setImageDrawable(VectorGlyphs.create(activity, "phone", 16, GlassTheme.ACCENT_GREEN));
                compactTitle.setText(callLbl);
                compactRightBadge.setText("Вызов");
                compactRightBadge.setTextColor(GlassTheme.ACCENT_GREEN);
                if (expanded) addExpandedHint("Активный вызов", "Смахните вверх или нажмите, чтобы свернуть");
                break;

            case 2:
                compactLeftIcon.setImageDrawable(VectorGlyphs.create(activity, "mic", 16, GlassTheme.ACCENT_RED));
                compactTitle.setText(recLbl);
                compactRightBadge.setText("REC");
                compactRightBadge.setTextColor(GlassTheme.ACCENT_RED);
                if (expanded) addExpandedHint("Диктофон / Запись", "Идёт активная запись в фоне");
                break;

            case 3:
                // Симметричная раскладка как в Infinix Magic Ring: [молния + 85%] [вырез камеры] [Зарядка]
                compactLeftIcon.setImageDrawable(VectorGlyphs.create(activity, "bolt", 16, GlassTheme.ACCENT_GREEN));
                if (p.isShowBatteryPct() && batteryPct >= 0) {
                    compactLeftValue.setText(batteryPct + "%");
                    compactLeftValue.setTextColor(GlassTheme.ACCENT_GREEN);
                    compactLeftValue.setVisibility(View.VISIBLE);
                }
                compactTitle.setText(batteryFull ? "Заряжено" : "Зарядка");
                compactTitle.setPadding(useCameraGap ? 0 : dp(8), 0, 0, 0);
                compactRightBadge.setVisibility(View.GONE);
                if (expanded) addChargeExpandedBody();
                break;

            case 4:
                compactTitle.setPadding(0, 0, 0, 0);
                if (media.albumArt != null && !media.albumArt.isRecycled()) {
                    compactAlbumThumb.setImageBitmap(media.albumArt);
                    compactAlbumThumb.setVisibility(View.VISIBLE);
                    compactLeftIcon.setVisibility(View.GONE);
                } else {
                    compactLeftIcon.setImageDrawable(VectorGlyphs.create(activity, "music", 16, GlassTheme.ACCENT_BLUE));
                }
                compactTitle.setText(media.title);
                compactRightBadge.setVisibility(View.GONE);
                compactRightIcon.setVisibility(View.VISIBLE);
                compactRightIcon.setImageDrawable(VectorGlyphs.create(
                        activity,
                        mediaPlaying ? "pause" : "play",
                        14,
                        mediaPlaying ? GlassTheme.ACCENT_BLUE : 0xCCFFFFFF));
                if (expanded) addMediaExpandedBody(media, mediaPlaying);
                break;

            case 6:
                compactLeftIcon.setImageDrawable(VectorGlyphs.create(activity, "bolt", 16, GlassTheme.ACCENT_RED));
                compactLeftValue.setText(batteryPct + "%");
                compactLeftValue.setTextColor(GlassTheme.ACCENT_RED);
                compactLeftValue.setVisibility(View.VISIBLE);
                compactTitle.setText("Низкий заряд");
                compactTitle.setPadding(useCameraGap ? 0 : dp(8), 0, 0, 0);
                compactRightBadge.setVisibility(View.GONE);
                if (expanded) addExpandedHint("Осталось " + batteryPct + "% заряда", "Подключите зарядное устройство или включите энергосбережение");
                break;

            case 7:
                compactLeftIcon.setImageDrawable(VectorGlyphs.create(activity, "timer", 16, 0xFFF59E0B));
                compactLeftValue.setText("Таймер");
                compactLeftValue.setTextColor(0xFFF59E0B);
                compactLeftValue.setVisibility(View.VISIBLE);
                compactTitle.setText(timerLbl);
                compactTitle.setPadding(useCameraGap ? 0 : dp(8), 0, 0, 0);
                compactRightBadge.setVisibility(View.GONE);
                if (expanded) addExpandedHint("Активный таймер", "Смахните вверх, чтобы скрыть капсулу");
                break;

            case 8:
                compactLeftIcon.setImageDrawable(VectorGlyphs.create(activity, "hotspot", 16, GlassTheme.ACCENT_BLUE));
                compactLeftValue.setText("Wi-Fi");
                compactLeftValue.setTextColor(GlassTheme.ACCENT_BLUE);
                compactLeftValue.setVisibility(View.VISIBLE);
                compactTitle.setText("Точка доступа");
                compactTitle.setPadding(useCameraGap ? 0 : dp(8), 0, 0, 0);
                compactRightBadge.setVisibility(View.GONE);
                if (expanded) addExpandedHint("Раздача интернета активна", hotspotLbl);
                break;

            case 9: {
                boolean isSilent = (lastRingerMode == android.media.AudioManager.RINGER_MODE_SILENT);
                boolean isVibe = (lastRingerMode == android.media.AudioManager.RINGER_MODE_VIBRATE);
                String rGlyph = isSilent ? "mute" : (isVibe ? "vibrate" : "volume");
                int rColor = isSilent ? GlassTheme.ACCENT_RED : (isVibe ? 0xFFF59E0B : GlassTheme.ACCENT_GREEN);
                compactLeftIcon.setImageDrawable(VectorGlyphs.create(activity, rGlyph, 16, rColor));
                if (useCameraGap) {
                    compactLeftValue.setText(isSilent ? "Тихо" : (isVibe ? "Вибро" : "Звук"));
                    compactLeftValue.setTextColor(rColor);
                    compactLeftValue.setVisibility(View.VISIBLE);
                    compactTitle.setText("Режим");
                    compactTitle.setPadding(0, 0, 0, 0);
                } else {
                    compactTitle.setText(isSilent ? "Без звука" : (isVibe ? "Вибрация" : "Звук вкл."));
                    compactTitle.setPadding(dp(6), 0, 0, 0);
                }
                compactRightBadge.setVisibility(View.GONE);
                expanded = false;
                break;
            }

            case 5:
            default:
                compactTitle.setPadding(0, 0, 0, 0);
                compactLeftIcon.setImageDrawable(VectorGlyphs.create(activity, "lock", 16, GlassTheme.ACCENT_GREEN));
                compactTitle.setText("Разблокировано");
                compactRightBadge.setVisibility(View.GONE);
                expanded = false;
                break;
        }

        boolean showExp = expanded && expandedCard.getChildCount() > 0;
        boolean wasExpVisible = expandedCard.getVisibility() == View.VISIBLE;
        expandedCard.setVisibility(showExp ? View.VISIBLE : View.GONE);
        if (showExp && !wasExpVisible) {
            expandedCard.setAlpha(0f);
            expandedCard.setTranslationY(-dp(6));
            expandedCard.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .start();
        }
    }

    private void addExpandedHint(String title, String sub) {
        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        expandedCard.addView(t);

        TextView s = new TextView(activity);
        s.setText(sub);
        s.setTextColor(GlassTheme.TEXT_SECONDARY);
        s.setTextSize(11.5f);
        s.setPadding(0, dp(2), 0, 0);
        expandedCard.addView(s);
    }

    private void addChargeExpandedBody() {
        int pct = Math.max(0, Math.min(100, batteryPct));
        TextView info = new TextView(activity);
        info.setText(batteryFull
                ? "Батарея полностью заряжена (100%)"
                : ("Быстрая зарядка • " + pct + "%"));
        info.setTextColor(0xE0FFFFFF);
        info.setTextSize(12f);
        info.setPadding(0, 0, 0, dp(6));
        expandedCard.addView(info);

        FrameLayout bar = new FrameLayout(activity);
        bar.setLayoutParams(new LinearLayout.LayoutParams(dp(220), dp(6)));
        bar.setBackground(GlassTheme.makeRoundedRect(0xFF2C2C2E, dp(3)));

        View fill = new View(activity);
        int w = Math.max(dp(12), Math.round(dp(220) * (pct / 100f)));
        fill.setLayoutParams(new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
        fill.setBackground(GlassTheme.makeRoundedRect(GlassTheme.ACCENT_GREEN, dp(3)));
        bar.addView(fill);
        expandedCard.addView(bar);
    }

    private void addMediaExpandedBody(final DoxaizNotificationService.MediaInfo media, boolean isPlaying) {
        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, dp(8));

        if (media.albumArt != null && !media.albumArt.isRecycled()) {
            ImageView artView = new ImageView(activity);
            artView.setImageBitmap(media.albumArt);
            artView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artView.setBackground(GlassTheme.makeRoundedRect(0xFF242427, dp(10)));
            artView.setClipToOutline(true);
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(dp(40), dp(40));
            aLp.rightMargin = dp(10);
            top.addView(artView, aLp);
        }

        LinearLayout meta = new LinearLayout(activity);
        meta.setOrientation(LinearLayout.VERTICAL);

        TextView tr = new TextView(activity);
        tr.setText(media.title);
        tr.setTextColor(Color.WHITE);
        tr.setTextSize(13f);
        tr.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tr.setSingleLine(true);
        tr.setEllipsize(TextUtils.TruncateAt.END);
        tr.setMaxWidth(dp(175));
        meta.addView(tr);

        if (!media.artist.isEmpty()) {
            TextView art = new TextView(activity);
            art.setText(media.artist);
            art.setTextColor(GlassTheme.TEXT_SECONDARY);
            art.setTextSize(11.5f);
            art.setSingleLine(true);
            art.setEllipsize(TextUtils.TruncateAt.END);
            art.setMaxWidth(dp(175));
            meta.addView(art);
        }
        top.addView(meta);
        top.setOnClickListener(v -> {
            if (media.contentIntent != null) {
                DoxaizNotificationService.sendPendingIntentSafely(activity, media.contentIntent, null);
            }
        });
        expandedCard.addView(top);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(makeMediaBtn("prev", KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        row.addView(makeMediaBtn(isPlaying ? "pause" : "play", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        row.addView(makeMediaBtn("next", KeyEvent.KEYCODE_MEDIA_NEXT));
        expandedCard.addView(row);
    }

    private View makeMediaBtn(String glyph, final int keyCode) {
        FrameLayout box = new FrameLayout(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(34));
        lp.setMargins(dp(6), 0, dp(6), 0);
        box.setLayoutParams(lp);
        box.setBackground(GlassTheme.makeRoundedRect(0xFF242427, dp(12)));

        ImageView iv = VectorGlyphs.makeIconView(activity, glyph, 17, Color.WHITE);
        FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(dp(17), dp(17));
        iLp.gravity = Gravity.CENTER;
        box.addView(iv, iLp);

        box.setOnClickListener(v -> {
            performHaptic(v);
            dispatchMediaKey(keyCode);
        });
        return box;
    }

    private void dispatchMediaKey(int keyCode) {
        try {
            AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // Экран настроек Dynamic Bar (в стиле Magic Ring с умным автоскрытием)
    // =========================================================================

    public void showSettingsDialog() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (settingsDialog != null && settingsDialog.isShowing()) {
            try { settingsDialog.dismiss(); } catch (Throwable ignored) {}
        }
        final Prefs prefs = host.getPrefs();
        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        settingsDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (settingsDialog == dialog) settingsDialog = null;
        });
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(GlassTheme.makeRoundedRect(0xFF000000, 0));
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
        }

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        int topPad = Math.max(dp(26), cachedStatusBarHeight + dp(10));
        root.setPadding(dp(18), topPad, dp(18), dp(22));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(16));

        TextView backBtn = new TextView(activity);
        backBtn.setText("‹");
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTextSize(28f);
        backBtn.setPadding(0, 0, dp(14), 0);
        backBtn.setOnClickListener(v -> dialog.dismiss());
        header.addView(backBtn);

        TextView title = new TextView(activity);
        title.setText("Dynamic Bar");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title);
        root.addView(header);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        LinearLayout previewCard = new LinearLayout(activity);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        previewCard.setGravity(Gravity.CENTER_HORIZONTAL);
        previewCard.setBackground(GlassTheme.makeRoundedRect(0xFF1A1A1E, dp(22)));
        previewCard.setPadding(dp(16), dp(20), dp(16), dp(18));

        LinearLayout phoneTop = new LinearLayout(activity);
        phoneTop.setOrientation(LinearLayout.VERTICAL);
        phoneTop.setGravity(Gravity.CENTER_HORIZONTAL);
        phoneTop.setBackground(GlassTheme.makeRoundedRect(0xFF28282E, dp(20)));
        phoneTop.setPadding(dp(14), dp(12), dp(14), dp(14));
        phoneTop.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout previewPill = new LinearLayout(activity);
        previewPill.setOrientation(LinearLayout.HORIZONTAL);
        previewPill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pBg = new GradientDrawable();
        pBg.setShape(GradientDrawable.RECTANGLE);
        pBg.setCornerRadius(dp(18));
        pBg.setColor(0xFF000000);
        pBg.setStroke(dp(1), 0x38FFFFFF);
        previewPill.setBackground(pBg);
        previewPill.setPadding(dp(14), dp(7), dp(14), dp(7));

        final ImageView pIcon = VectorGlyphs.makeIconView(activity, "bolt", 16, GlassTheme.ACCENT_GREEN);
        LinearLayout.LayoutParams piLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        piLp.rightMargin = dp(6);
        previewPill.addView(pIcon, piLp);

        final TextView pLeft = new TextView(activity);
        pLeft.setText("85%");
        pLeft.setTextColor(GlassTheme.ACCENT_GREEN);
        pLeft.setTextSize(12.5f);
        pLeft.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        previewPill.addView(pLeft);

        // Имитация выреза объектива фронтальной камеры по центру капсулы
        View camDot = new View(activity);
        GradientDrawable camBg = new GradientDrawable();
        camBg.setShape(GradientDrawable.OVAL);
        camBg.setColor(0xFF141418);
        camBg.setStroke(dp(1), 0x44FFFFFF);
        camDot.setBackground(camBg);
        LinearLayout.LayoutParams camLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        camLp.setMargins(dp(12), 0, dp(12), 0);
        previewPill.addView(camDot, camLp);

        final TextView pText = new TextView(activity);
        pText.setText("Зарядка • 5с");
        pText.setTextColor(Color.WHITE);
        pText.setTextSize(12.5f);
        pText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        previewPill.addView(pText);
        phoneTop.addView(previewPill);

        final int[] demoIdx = {0};
        final String[][] demos = {
                {"bolt",    "85%",    "Зарядка • 5с",    "#22C55E"},
                {"music",   "Play",   "Трек в фоне",     "#2B8BFF"},
                {"timer",   "Таймер", "04:32",           "#F59E0B"},
                {"hotspot", "Wi-Fi",  "Точка доступа",   "#2B8BFF"},
                {"bolt",    "15%",    "Низкий заряд",    "#EF4444"},
                {"phone",   "01:24",  "Вызов",           "#22C55E"},
                {"mic",     "REC",    "Диктофон",        "#EF4444"},
                {"lock",    "OK",     "Разблокировано",  "#22C55E"}
        };
        previewPill.setOnClickListener(v -> {
            performHaptic(v);
            demoIdx[0] = (demoIdx[0] + 1) % demos.length;
            int col = Color.parseColor(demos[demoIdx[0]][3]);
            pIcon.setImageDrawable(VectorGlyphs.create(activity, demos[demoIdx[0]][0], 16, col));
            pLeft.setText(demos[demoIdx[0]][1]);
            pLeft.setTextColor(col);
            pText.setText(demos[demoIdx[0]][2]);
        });

        TextView previewHint = new TextView(activity);
        previewHint.setText("Появляется только по событию и скрывается сам • Нажмите для просмотра");
        previewHint.setTextColor(GlassTheme.TEXT_SECONDARY);
        previewHint.setTextSize(11.5f);
        previewHint.setGravity(Gravity.CENTER);
        previewHint.setPadding(0, dp(10), 0, 0);
        phoneTop.addView(previewHint);

        previewCard.addView(phoneTop);
        body.addView(previewCard);

        LinearLayout masterCard = new LinearLayout(activity);
        masterCard.setOrientation(LinearLayout.VERTICAL);
        masterCard.setBackground(GlassTheme.makeRoundedRect(0xFF1A1A1E, dp(20)));
        masterCard.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mcLp.topMargin = dp(14);
        masterCard.setLayoutParams(mcLp);

        final LinearLayout optionsCard = new LinearLayout(activity);
        optionsCard.setOrientation(LinearLayout.VERTICAL);
        optionsCard.setBackground(GlassTheme.makeRoundedRect(0xFF1A1A1E, dp(20)));
        optionsCard.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams ocLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ocLp.topMargin = dp(12);
        optionsCard.setLayoutParams(ocLp);

        masterCard.addView(makeSwitchRow("dbar", "Dynamic Bar",
                "Показывать капсулу только при активных событиях",
                prefs.isDynamicBarEnabled(),
                enabled -> {
                    prefs.setDynamicBarEnabled(enabled);
                    optionsCard.setAlpha(enabled ? 1f : 0.45f);
                    refresh();
                    host.onDynamicBarPrefsChanged();
                }));
        masterCard.addView(makeDivider());
        masterCard.addView(makeSwitchRow("dim", "Умное автоскрытие",
                "Зарядка скрывается через 5 сек, музыка на паузе — через 10 сек; свайп вверх прячет сразу",
                prefs.isDynamicBarAutoHide(),
                enabled -> {
                    prefs.setDynamicBarAutoHide(enabled);
                    dismissedEventSig = "";
                    lastRenderSig = "";
                    refresh();
                }));
        body.addView(masterCard);

        optionsCard.setAlpha(prefs.isDynamicBarEnabled() ? 1f : 0.45f);
        optionsCard.addView(makeSwitchRow("bolt", "Подключение зарядки",
                "Краткий показ при подключении кабеля и при достижении 100%",
                prefs.isDynamicBarCharging(),
                v -> { prefs.setDynamicBarCharging(v); refresh(); }));
        optionsCard.addView(makeDivider());
        optionsCard.addView(makeSwitchRow("music", "Свёрнутая музыка",
                "Появляется, когда плеер играет в фоне; раскрывается по нажатию",
                prefs.isDynamicBarMedia(),
                v -> { prefs.setDynamicBarMedia(v); refresh(); }));
        optionsCard.addView(makeDivider());
        optionsCard.addView(makeSwitchRow("timer", "Таймер и секундомер",
                "Показывать активный обратный отсчёт часов",
                prefs.isDynamicBarTimer(),
                v -> { prefs.setDynamicBarTimer(v); refresh(); }));
        optionsCard.addView(makeDivider());
        optionsCard.addView(makeSwitchRow("hotspot", "Точка доступа Wi-Fi",
                "Индикатор при активной раздаче интернета",
                prefs.isDynamicBarHotspot(),
                v -> { prefs.setDynamicBarHotspot(v); refresh(); }));
        optionsCard.addView(makeDivider());
        optionsCard.addView(makeSwitchRow("vibrate", "Режим звука (Без звука / Вибрация)",
                "Краткая капсула при переключении режима звонка (2.6 сек)",
                prefs.isDynamicBarRinger(),
                v -> { prefs.setDynamicBarRinger(v); refresh(); }));
        optionsCard.addView(makeDivider());
        optionsCard.addView(makeSwitchRow("bolt", "Низкий заряд (≤15%)",
                "Краткое предупреждение на 15%, 10% и 5% (скрывается через 6 сек)",
                prefs.isDynamicBarLowBattery(),
                v -> { prefs.setDynamicBarLowBattery(v); refresh(); }));
        optionsCard.addView(makeDivider());
        optionsCard.addView(makeSwitchRow("phone", "Статус звонка",
                "Отображать активный голосовой вызов",
                prefs.isDynamicBarCalls(),
                v -> { prefs.setDynamicBarCalls(v); refresh(); }));
        optionsCard.addView(makeDivider());
        optionsCard.addView(makeSwitchRow("mic", "Запись диктофона",
                "Показывать индикатор активной записи",
                prefs.isDynamicBarRecorder(),
                v -> { prefs.setDynamicBarRecorder(v); refresh(); }));
        optionsCard.addView(makeDivider());
        optionsCard.addView(makeSwitchRow("lock", "Статус разблокировки",
                "Мягкий короткий отклик при возврате на рабочий стол",
                prefs.isDynamicBarUnlock(),
                v -> { prefs.setDynamicBarUnlock(v); refresh(); }));
        body.addView(optionsCard);

        // Положение капсулы (учёт выреза камеры и строки состояния)
        LinearLayout posCard = new LinearLayout(activity);
        posCard.setOrientation(LinearLayout.VERTICAL);
        posCard.setBackground(GlassTheme.makeRoundedRect(0xFF1A1A1E, dp(20)));
        posCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams posLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        posLp.topMargin = dp(12);
        posCard.setLayoutParams(posLp);

        TextView posTitle = new TextView(activity);
        posTitle.setText("Положение капсулы сверху");
        posTitle.setTextColor(Color.WHITE);
        posTitle.setTextSize(14.5f);
        posTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        posCard.addView(posTitle);

        TextView posSub = new TextView(activity);
        posSub.setText("Подстройте высоту под вырез камеры или строку состояния");
        posSub.setTextColor(GlassTheme.TEXT_SECONDARY);
        posSub.setTextSize(12f);
        posSub.setPadding(0, dp(2), 0, dp(10));
        posCard.addView(posSub);

        LinearLayout posRow = new LinearLayout(activity);
        posRow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] posLabels = {"У камеры", "Ниже бара", "Выше"};
        final TextView[] posBtns = new TextView[3];
        for (int i = 0; i < 3; i++) {
            TextView b = new TextView(activity);
            b.setText(posLabels[i]);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(12.5f);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setPadding(dp(8), dp(9), dp(8), dp(9));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) bLp.leftMargin = dp(6);
            b.setLayoutParams(bLp);
            posBtns[i] = b;
            posRow.addView(b);
        }
        final Runnable paintPos = () -> {
            int cur = prefs.getDynamicBarPosition();
            for (int i = 0; i < 3; i++) {
                boolean sel = (i == cur);
                posBtns[i].setTextColor(sel ? Color.WHITE : GlassTheme.TEXT_SECONDARY);
                posBtns[i].setBackground(GlassTheme.makeRoundedRect(
                        sel ? GlassTheme.ACCENT_BLUE : 0xFF28282E, dp(12)));
            }
        };
        paintPos.run();
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            posBtns[i].setOnClickListener(v -> {
                performHaptic(v);
                prefs.setDynamicBarPosition(idx);
                paintPos.run();
                lastRenderSig = "";
                updateTopPosition();
                refresh();
            });
        }
        posCard.addView(posRow);
        body.addView(posCard);

        TextView note = new TextView(activity);
        note.setText("Dynamic Bar никогда не остаётся висеть впустую, работает строго по событиям и не расходует заряд.");
        note.setTextColor(GlassTheme.TEXT_MUTED);
        note.setTextSize(12f);
        note.setPadding(dp(4), dp(14), dp(4), dp(8));
        body.addView(note);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(root);
        try {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                dialog.show();
            }
        } catch (Throwable ignored) {
        }
    }

    private interface ToggleCallback {
        void onChanged(boolean value);
    }

    private View makeSwitchRow(String glyph, String title, String sub, boolean initial, final ToggleCallback cb) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        ImageView icon = VectorGlyphs.makeIconView(activity, glyph, 20, 0xCCFFFFFF);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        iLp.rightMargin = dp(12);
        row.addView(icon, iLp);

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15f);
        texts.addView(t);

        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(activity);
            s.setText(sub);
            s.setTextColor(GlassTheme.TEXT_SECONDARY);
            s.setTextSize(12f);
            s.setPadding(0, dp(2), dp(12), 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final boolean[] state = {initial};
        final FrameLayout track = new FrameLayout(activity);
        track.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(26)));

        final View thumb = new View(activity);
        FrameLayout.LayoutParams thLp = new FrameLayout.LayoutParams(dp(20), dp(20));
        thLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        thumb.setLayoutParams(thLp);
        thumb.setBackground(GlassTheme.makeCircle(Color.WHITE));
        track.addView(thumb);

        final Runnable applyVisual = () -> {
            track.setBackground(GlassTheme.makeRoundedRect(
                    state[0] ? GlassTheme.SWITCH_ON : GlassTheme.SWITCH_OFF, dp(13)));
            thumb.setTranslationX(state[0] ? dp(23) : dp(3));
        };
        applyVisual.run();

        row.addView(track);
        row.setOnClickListener(v -> {
            performHaptic(v);
            state[0] = !state[0];
            applyVisual.run();
            cb.onChanged(state[0]);
        });
        return row;
    }

    private void performHaptic(View v) {
        if (v != null && host.getPrefs().isHapticFeedbackEnabled()) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    private View makeDivider() {
        View v = new View(activity);
        v.setBackgroundColor(GlassTheme.DIVIDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    private int dp(int v) {
        return GlassTheme.dp(activity, v);
    }
}
