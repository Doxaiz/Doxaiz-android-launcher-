package com.doxaiz.launcher;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Всплывающий баннер уведомлений (Heads-Up) в стиле тёмного минимализма Doxaiz 4.5:
 * - Показывает круглый аватар отправителя + бейдж приложения;
 * - Кнопка «Копировать XXXXXX» в 1 тап прямо на баннере для кодов подтверждения (OTP);
 * - Очередь до 3 входящих уведомлений (второе сообщение не теряется);
 * - Свайп вверх — скрыть баннер (уведомление остаётся в шторке);
 * - Свайп влево/вправо — реально смахнуть уведомление;
 * - Тап — сразу открыть чат/приложение.
 */
public final class HeadsUpBannerHelper {

    public interface Host {
        Prefs getPrefs();
        boolean isShadeOpen();
        void showToast(String msg);
    }

    private static final long AUTO_HIDE_MS = 4600L;
    private static final int MAX_QUEUE = 3;

    private final Activity activity;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Deque<DoxaizNotificationService.NotificationItem> queue = new ArrayDeque<>();

    private FrameLayout overlayHost;
    private View currentBanner;
    private int topInsetPx = 0;
    private boolean isShowing = false;
    private String activeKey = "";

    private final Runnable autoHideRunnable = () -> dismissBanner(true);

    public HeadsUpBannerHelper(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    public View buildOverlayView(int insetTop) {
        this.topInsetPx = Math.max(0, insetTop);
        overlayHost = new FrameLayout(activity);
        overlayHost.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlayHost.setClickable(false);
        overlayHost.setFocusable(false);
        return overlayHost;
    }

    public void updateTopInset(int insetTop) {
        this.topInsetPx = Math.max(0, insetTop);
        if (currentBanner != null && currentBanner.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) currentBanner.getLayoutParams();
            lp.topMargin = topInsetPx + dp(10);
            currentBanner.setLayoutParams(lp);
        }
    }

    public boolean isShowing() {
        return isShowing;
    }

    public void hideImmediate() {
        handler.removeCallbacks(autoHideRunnable);
        queue.clear();
        activeKey = "";
        removeImmediate();
    }

    public void destroy() {
        hideImmediate();
        overlayHost = null;
    }

    public void showNotification(final DoxaizNotificationService.NotificationItem item) {
        if (item == null || overlayHost == null || activity.isFinishing() || activity.isDestroyed()) return;
        Prefs prefs = host != null ? host.getPrefs() : null;
        if (prefs == null || !prefs.isHeadsUpBannersEnabled()) return;
        if (host != null && host.isShadeOpen()) return;
        if (isSystemDndActive()) return;

        if (isShowing) {
            if (item.key != null && item.key.equals(activeKey)) return;
            for (DoxaizNotificationService.NotificationItem q : queue) {
                if (item.key != null && item.key.equals(q.key)) return;
            }
            if (queue.size() >= MAX_QUEUE) {
                queue.pollFirst();
            }
            queue.addLast(item);
            return;
        }
        presentBanner(item);
    }

    private boolean isSystemDndActive() {
        try {
            NotificationManager nm = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                int f = nm.getCurrentInterruptionFilter();
                return f != NotificationManager.INTERRUPTION_FILTER_ALL
                        && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void presentBanner(final DoxaizNotificationService.NotificationItem item) {
        if (overlayHost == null) return;
        handler.removeCallbacks(autoHideRunnable);
        removeImmediate();

        Prefs prefs = host != null ? host.getPrefs() : null;
        final boolean hideText = (prefs != null && prefs.isHideNotifTextInShade());
        final String appLabel = resolveAppLabel(item.packageName);
        activeKey = item.key != null ? item.key : "";

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackground(GlassTheme.makeStyledCard(activity, GlassTheme.POPUP_BG, GlassTheme.CARD_BORDER_LIVE, 22f, 1f, true));
        outer.setPadding(dp(12), dp(10), dp(12), dp(10));
        outer.setElevation(dp(14));

        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        topRow.addView(buildAvatarOrIcon(item));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = dp(10);
        colLp.rightMargin = dp(6);

        LinearLayout hdr = new LinearLayout(activity);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);

        TextView appTv = new TextView(activity);
        appTv.setText(appLabel);
        appTv.setTextColor(GlassTheme.TEXT_MUTED);
        appTv.setTextSize(11f);
        appTv.setMaxLines(1);
        appTv.setEllipsize(TextUtils.TruncateAt.END);
        hdr.addView(appTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView nowTv = new TextView(activity);
        nowTv.setText(item.otpCode != null && !hideText ? "Код" : "сейчас");
        nowTv.setTextColor(item.otpCode != null && !hideText
                ? GlassTheme.ACCENT_GREEN : GlassTheme.TEXT_MUTED);
        nowTv.setTextSize(10.5f);
        if (item.otpCode != null && !hideText) {
            nowTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        hdr.addView(nowTv);
        col.addView(hdr);

        TextView titleTv = new TextView(activity);
        titleTv.setText(!item.title.isEmpty() ? item.title : appLabel);
        titleTv.setTextColor(GlassTheme.TEXT_PRIMARY);
        titleTv.setTextSize(13.5f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleTv.setMaxLines(1);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(titleTv);

        String bodyText = hideText ? "Новое уведомление" : item.text;
        if (bodyText != null && !bodyText.isEmpty()) {
            TextView bodyTv = new TextView(activity);
            bodyTv.setText(bodyText);
            bodyTv.setTextColor(GlassTheme.TEXT_SECONDARY);
            bodyTv.setTextSize(12.2f);
            bodyTv.setMaxLines(2);
            bodyTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(bodyTv);
        }
        topRow.addView(col, colLp);

        if (!queue.isEmpty()) {
            TextView qTv = new TextView(activity);
            qTv.setText("+" + queue.size());
            qTv.setTextColor(GlassTheme.TEXT_SECONDARY);
            qTv.setTextSize(10.5f);
            qTv.setBackground(GlassTheme.makePill(GlassTheme.SHADE_FIELD));
            qTv.setPadding(dp(6), dp(2), dp(6), dp(2));
            topRow.addView(qTv);
        }

        outer.addView(topRow);

        // Быстрая строка действий: OTP-код «Копировать» в 1 тап ИЛИ до 2 быстрых действий
        if (!hideText && (item.otpCode != null || !item.actions.isEmpty())) {
            LinearLayout actRow = new LinearLayout(activity);
            actRow.setOrientation(LinearLayout.HORIZONTAL);
            actRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            arLp.topMargin = dp(8);
            arLp.leftMargin = dp(46);

            if (item.otpCode != null) {
                LinearLayout otpChip = new LinearLayout(activity);
                otpChip.setOrientation(LinearLayout.HORIZONTAL);
                otpChip.setGravity(Gravity.CENTER_VERTICAL);
                otpChip.setBackground(GlassTheme.makePill(GlassTheme.ACCENT_SOFT_BG));
                otpChip.setPadding(dp(11), dp(5), dp(12), dp(5));

                ImageView cpIco = VectorGlyphs.makeIconView(activity, "copy", 12, otpChipText());
                LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(dp(12), dp(12));
                ciLp.rightMargin = dp(5);
                otpChip.addView(cpIco, ciLp);

                TextView otpTv = new TextView(activity);
                otpTv.setText("Копировать " + item.otpCode);
                otpTv.setTextColor(otpChipText());
                otpTv.setTextSize(12f);
                otpTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                otpChip.addView(otpTv);

                otpChip.setOnClickListener(v -> {
                    copyToClipboard("Код подтверждения", item.otpCode);
                    if (host.getPrefs().isHapticFeedbackEnabled()) {
                        try { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Throwable ignored) {}
                    }
                    dismissBanner(true);
                });
                actRow.addView(otpChip);
            } else {
                int shown = 0;
                for (final DoxaizNotificationService.ActionItem act : item.actions) {
                    if (act.isReply()) continue;
                    TextView chip = new TextView(activity);
                    chip.setText(act.title);
                    chip.setTextColor(GlassTheme.ACCENT_BLUE_ON_SOFT);
                    chip.setTextSize(12f);
                    chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    chip.setBackground(GlassTheme.makePill(GlassTheme.SHADE_TILE_OFF));
                    chip.setPadding(dp(11), dp(4), dp(11), dp(4));
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (shown > 0) clp.leftMargin = dp(8);
                    chip.setOnClickListener(v -> {
                        if (DoxaizNotificationService.sendPendingIntentSafely(activity, act.actionIntent, null)) {
                            if (host != null) host.showToast(act.title);
                        }
                        dismissBanner(true);
                    });
                    actRow.addView(chip, clp);
                    shown++;
                    if (shown >= 2) break;
                }
            }
            if (actRow.getChildCount() > 0) {
                outer.addView(actRow, arLp);
            }
        }

        View pill = new View(activity);
        pill.setBackground(GlassTheme.makeRoundedRect(0x40FFFFFF, dp(2)));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(dp(28), dp(3));
        pillLp.gravity = Gravity.CENTER_HORIZONTAL;
        pillLp.topMargin = dp(6);
        outer.addView(pill, pillLp);

        // Жесты: свайп вверх — скрыть баннер; свайп влево/вправо — смахнуть уведомление; тап — открыть
        final float[] downX = {0f};
        final float[] downY = {0f};
        final int[] dragMode = {0};
        outer.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = ev.getRawX();
                    downY[0] = ev.getRawY();
                    dragMode[0] = 0;
                    handler.removeCallbacks(autoHideRunnable);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - downX[0];
                    float dy = ev.getRawY() - downY[0];
                    if (dragMode[0] == 0) {
                        if (Math.abs(dy) > dp(10) && Math.abs(dy) >= Math.abs(dx)) {
                            dragMode[0] = 1;
                        } else if (Math.abs(dx) > dp(12) && Math.abs(dx) > Math.abs(dy)) {
                            dragMode[0] = 2;
                        }
                    }
                    if (dragMode[0] == 1 && dy < 0) {
                        v.setTranslationY(dy * 0.7f);
                        v.setAlpha(Math.max(0.25f, 1f + dy / dp(90)));
                    } else if (dragMode[0] == 2) {
                        v.setTranslationX(dx);
                        v.setAlpha(Math.max(0.25f, 1f - Math.abs(dx) / dp(170)));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dx = ev.getRawX() - downX[0];
                    float dy = ev.getRawY() - downY[0];
                    if (dragMode[0] == 1 && dy < -dp(26)) {
                        dismissBanner(true);
                    } else if (dragMode[0] == 2 && Math.abs(dx) > dp(68)) {
                        if (host.getPrefs().isHapticFeedbackEnabled()) {
                            try { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Throwable ignored) {}
                        }
                        DoxaizNotificationService.dismissNotification(item.key);
                        animateSwipeOutHorizontal(v, dx > 0 ? dp(260) : -dp(260));
                    } else if (dragMode[0] != 0) {
                        v.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(150).start();
                        handler.postDelayed(autoHideRunnable, AUTO_HIDE_MS);
                    } else {
                        openNotification(item);
                        dismissBanner(false);
                    }
                    return true;
                }
                default:
                    return false;
            }
        });

        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int bannerW = Math.min(Math.max(dp(260), screenW - dp(24)), dp(420));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                bannerW, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = Math.max(dp(28), topInsetPx) + dp(10);

        currentBanner = outer;
        isShowing = true;
        overlayHost.addView(outer, lp);

        outer.setTranslationY(-dp(96));
        outer.setAlpha(0f);
        outer.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        handler.postDelayed(autoHideRunnable, AUTO_HIDE_MS);
    }

    private View buildAvatarOrIcon(DoxaizNotificationService.NotificationItem item) {
        FrameLayout box = new FrameLayout(activity);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));

        Drawable appIcon = resolveAppIcon(item.packageName);
        Bitmap avatar = item.senderAvatar;

        if (avatar != null && !avatar.isRecycled()) {
            ImageView av = new ImageView(activity);
            av.setImageBitmap(avatar);
            av.setScaleType(ImageView.ScaleType.CENTER_CROP);
            av.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            av.setClipToOutline(true);
            box.addView(av, new FrameLayout.LayoutParams(dp(34), dp(34)));

            if (appIcon != null) {
                ImageView badge = new ImageView(activity);
                badge.setImageDrawable(appIcon);
                badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
                badge.setBackground(GlassTheme.makeCircle(GlassTheme.SHADE_CARD));
                badge.setPadding(dp(1), dp(1), dp(1), dp(1));
                FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(dp(15), dp(15));
                bLp.gravity = Gravity.BOTTOM | Gravity.END;
                box.addView(badge, bLp);
            }
        } else {
            ImageView iconIv = new ImageView(activity);
            iconIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (appIcon != null) {
                iconIv.setImageDrawable(appIcon);
            } else {
                iconIv.setImageDrawable(VectorGlyphs.create(activity, "bell", 20, GlassTheme.TEXT_PRIMARY));
            }
            box.addView(iconIv, new FrameLayout.LayoutParams(dp(34), dp(34)));
        }
        return box;
    }

    private void animateSwipeOutHorizontal(final View v, float targetX) {
        handler.removeCallbacks(autoHideRunnable);
        isShowing = false;
        activeKey = "";
        v.animate()
                .translationX(targetX)
                .alpha(0f)
                .setDuration(170)
                .withEndAction(() -> {
                    removeViewSafe(v);
                    if (currentBanner == v) currentBanner = null;
                    showNextQueuedIfAny();
                })
                .start();
    }

    public void dismissBanner(boolean animate) {
        handler.removeCallbacks(autoHideRunnable);
        final View v = currentBanner;
        isShowing = false;
        activeKey = "";
        if (v == null) {
            showNextQueuedIfAny();
            return;
        }
        if (!animate) {
            removeViewSafe(v);
            currentBanner = null;
            queue.clear();
            return;
        }
        v.animate()
                .translationY(-dp(90))
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> {
                    removeViewSafe(v);
                    if (currentBanner == v) currentBanner = null;
                    showNextQueuedIfAny();
                })
                .start();
    }

    private void showNextQueuedIfAny() {
        if (isShowing || queue.isEmpty() || overlayHost == null) return;
        if (activity.isFinishing() || activity.isDestroyed()) {
            queue.clear();
            return;
        }
        Prefs prefs = host != null ? host.getPrefs() : null;
        if (prefs == null || !prefs.isHeadsUpBannersEnabled()
                || (host != null && host.isShadeOpen()) || isSystemDndActive()) {
            queue.clear();
            return;
        }
        DoxaizNotificationService.NotificationItem next = queue.pollFirst();
        if (next != null) {
            handler.postDelayed(() -> {
                if (!isShowing && !activity.isFinishing() && !activity.isDestroyed()
                        && (host == null || !host.isShadeOpen()) && !isSystemDndActive()) {
                    presentBanner(next);
                }
            }, 140L);
        }
    }

    private void removeImmediate() {
        if (currentBanner != null) {
            currentBanner.animate().setListener(null);
            currentBanner.animate().cancel();
            removeViewSafe(currentBanner);
            currentBanner = null;
        }
        isShowing = false;
    }

    private void removeViewSafe(View v) {
        try {
            if (v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).removeView(v);
            }
        } catch (Throwable ignored) {
        }
    }

    private void copyToClipboard(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && text != null && !text.isEmpty()) {
                ClipData clip = ClipData.newPlainText(label, text);
                if (Build.VERSION.SDK_INT >= 33) {
                    PersistableBundle extras = new PersistableBundle();
                    extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                    clip.getDescription().setExtras(extras);
                }
                cm.setPrimaryClip(clip);
                if (host != null) host.showToast("Код " + text + " скопирован");
            }
        } catch (Throwable ignored) {
        }
    }

    private void openNotification(DoxaizNotificationService.NotificationItem item) {
        try {
            if (item.contentIntent != null) {
                if (DoxaizNotificationService.sendPendingIntentSafely(activity, item.contentIntent, null)) {
                    return;
                }
            }
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(item.packageName);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(launch);
            }
        } catch (Throwable ignored) {
        }
    }

    private String resolveAppLabel(String pkg) {
        try {
            PackageManager pm = activity.getPackageManager();
            CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    private Drawable resolveAppIcon(String pkg) {
        try {
            return activity.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Цвет подписи на мягкой подложке OTP-чипа.
     *
     * ACCENT_GREEN сам по себе — цвет заливки под белым текстом (5,58:1 на
     * светлой палитре). На мягкой подложке ACCENT_SOFT_BG тот же акцент даёт
     * меньше 3:1, поэтому подпись берёт либо белый (тёмные стили), либо
     * TEXT_PRIMARY (светлый стиль, где подложка светлая).
     */
    private int otpChipText() {
        return GlassTheme.LIGHT_SURFACES ? GlassTheme.TEXT_PRIMARY : Color.WHITE;
    }

    private int dp(float v) {
        return GlassTheme.dp(activity, v);
    }
}
