package com.doxaiz.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Полноценная шторка Doxaiz 3.1:
 * - Единые чёткие векторные иконки (VectorGlyphs, 0 эмодзи)
 * - Современная раздельная (Центр управления / Центр уведомлений) и Классическая объединённая
 * - Свайп-смахивание уведомлений (Swipe-to-Dismiss) + реальные кнопки Notification.actions
 * - Живая синхронизация фонарика (TorchCallback), реальный DND, тройной режим звука, живая скорость сети
 * - Редактор «Изменить» с «−» / «+» и перестановкой порядка плиток ◀ / ▶
 */
public final class QuickShadeHelper {

    public static final int TAB_CONTROL = 0;
    public static final int TAB_NOTIFICATIONS = 1;

    public interface Host {
        Prefs getPrefs();
        void openLauncherSettings();
        void openActiveApps();
        void openSoundPanel();
        void openDynamicBarSettings();
        void openNotifStatusSettings();
        void openPrivateDnsModal();
        void showToast(String message);
        default void onShadeVisibilityChanged(boolean visible) {}
        default void onAutoNetworkSettingsChanged() {}
        default boolean triggerNetworkBoostNow() { return false; }
    }

    public static final class TileSpec {
        public final String id;
        public final String glyph;
        public final String label;

        TileSpec(String id, String glyph, String label) {
            this.id = id;
            this.glyph = glyph;
            this.label = label;
        }
    }

    private static final Map<String, TileSpec> ALL_TILES = new LinkedHashMap<>();
    static {
        ALL_TILES.put("wifi",       new TileSpec("wifi",       "wifi",       "Wi-Fi"));
        ALL_TILES.put("mobile",     new TileSpec("mobile",     "mobile",     "Данные"));
        ALL_TILES.put("torch",      new TileSpec("torch",      "torch",      "Фонарик"));
        ALL_TILES.put("bluetooth",  new TileSpec("bluetooth",  "bluetooth",  "Bluetooth"));
        ALL_TILES.put("sound",      new TileSpec("sound",      "sound",      "Звук"));
        ALL_TILES.put("dnd",        new TileSpec("dnd",        "dnd",        "Не беспокоить"));
        ALL_TILES.put("hotspot",    new TileSpec("hotspot",    "hotspot",    "Точка доступа"));
        ALL_TILES.put("airplane",   new TileSpec("airplane",   "airplane",   "Режим полёта"));
        ALL_TILES.put("gps",        new TileSpec("gps",        "gps",        "Геолокация"));
        ALL_TILES.put("autonet",    new TileSpec("autonet",    "autonet",    "Авто-сеть"));
        ALL_TILES.put("netboost",   new TileSpec("netboost",   "bolt",       "Сеть+"));
        ALL_TILES.put("rotate",     new TileSpec("rotate",     "rotate",     "Автоповорот"));
        ALL_TILES.put("record",     new TileSpec("record",     "record",     "Запись экрана"));
        ALL_TILES.put("qr",         new TileSpec("qr",         "qr",         "Сканер QR"));
        ALL_TILES.put("battery",    new TileSpec("battery",    "battery",    "Энергосбереж."));
        ALL_TILES.put("dns",        new TileSpec("dns",        "dns",        "Частный DNS"));
        ALL_TILES.put("dim",        new TileSpec("dim",        "dim",        "Ночной экран"));
        ALL_TILES.put("screenshot", new TileSpec("screenshot", "screenshot", "Снимок экрана"));
        ALL_TILES.put("lock",       new TileSpec("lock",       "lock",       "Блокировка"));
        ALL_TILES.put("timeout",    new TileSpec("timeout",    "timer",      "Сон экрана"));
        ALL_TILES.put("active_bg",  new TileSpec("active_bg",  "active_bg",  "Активные"));
        ALL_TILES.put("dbar",       new TileSpec("dbar",       "dbar",       "Dynamic Bar"));
        ALL_TILES.put("volume",     new TileSpec("volume",     "eq",         "Панель звука"));
        ALL_TILES.put("settings",   new TileSpec("settings",   "settings",   "Настройки"));
    }

    private static final List<String> CLASSIC_ORDER = Arrays.asList(
            "wifi", "mobile", "torch", "bluetooth",
            "sound", "dnd", "hotspot", "airplane",
            "gps", "autonet", "rotate", "battery",
            "dns", "record", "qr", "dim",
            "screenshot", "lock", "active_bg", "dbar",
            "volume", "settings"
    );

    /**
     * Шаблоны времени. Сами SimpleDateFormat не создаются как static-поля:
     * SimpleDateFormat не потокобезопасен, а форматирование идёт и из
     * UI-потока (часы, уведомления), и из фонового (панель сети
     * NetworkQualityHelper). Общий экземпляр под нагрузкой даёт «рваные»
     * строки и редкие ArrayIndexOutOfBounds внутри Calendar.
     */
    private static final String TIME_PATTERN = "HH:mm";
    private static final java.util.Locale DATE_LOCALE = Locale.forLanguageTag("ru-RU");

    /**
     * Форматирует время в текущем часовом поясе. Часовой пояс берётся в момент
     * форматирования (защита от залипания часов при перелёте / смене TimeZone),
     * поэтому форматтер создаётся локально — дешёвая операция по сравнению с
     * риском гонки на общем экземпляре.
     */
    static String formatWithCurrentTimeZone(String pattern, java.util.Locale locale, Date date) {
        if (date == null) return "";
        SimpleDateFormat fmt = new SimpleDateFormat(pattern, locale);
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        if (tz != null) fmt.setTimeZone(tz);
        return fmt.format(date);
    }

    static String formatTime(Date date) {
        return formatWithCurrentTimeZone(TIME_PATTERN, Locale.getDefault(), date);
    }

    static String formatDate(Date date) {
        return formatWithCurrentTimeZone("EEE, d MMM", DATE_LOCALE, date);
    }

    private final Activity activity;
    private final Host host;
    private final Handler liveHandler = new Handler(Looper.getMainLooper());
    private Dialog shadeDialog;
    private Dialog activeChildDialog;
    private LinearLayout shadeRoot;
    private TextView liveTrafficLabel;

    private boolean torchOn = false;
    private boolean backTorchOn = false;
    private boolean frontTorchOn = false;
    private String backTorchCameraId = null;
    private String frontTorchCameraId = null;
    private boolean frontTorchProbed = false;
    private int backTorchMaxStrength = -1;
    private int backTorchStrengthLevel = 1;
    private final Handler actionHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingScreenshotRunnable = null;
    private Boolean cachedHasVibrator = null;
    private Boolean cachedHasLightSensor = null;
    private boolean cachedHotspotActive = false;
    private long cachedHotspotCheckedMs = 0L;
    private int cachedMaxBrightness = -1;
    private int lastWrittenBrightnessVal = -1;
    private boolean brightnessManualApplied = false;
    private int lastWrittenVolumeStep = -1;
    private AudioManager cachedAudioManager;
    private ConnectivityManager cachedConnectivityManager;
    private BluetoothManager cachedBluetoothManager;
    private NotificationManager cachedNotificationManager;
    private PowerManager cachedPowerManager;
    private LocationManager cachedLocationManager;
    private boolean hasFrameNetSnapshot = false;
    private NetworkCapabilities frameNetCaps = null;
    private String cachedGridTilesCsv = null;
    private List<String> cachedGridTilesList = null;
    private String lastLiveNetIconKey = "";
    private boolean torchCbRegistered = false;
    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeUnavailable(String cameraId) {
            if (cameraId == null) return;
            boolean changed = false;
            if (backTorchCameraId != null && backTorchCameraId.equals(cameraId) && backTorchOn) {
                backTorchOn = false;
                changed = true;
            } else if (frontTorchCameraId != null && frontTorchCameraId.equals(cameraId) && frontTorchOn) {
                frontTorchOn = false;
                changed = true;
            }
            boolean anyOn = backTorchOn || frontTorchOn;
            if (changed || torchOn != anyOn) {
                torchOn = anyOn;
                if (isShowing()) {
                    liveHandler.post(QuickShadeHelper.this::refreshIfShowing);
                }
            }
        }

        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId == null) return;
            boolean matched = false;
            if (backTorchCameraId != null && backTorchCameraId.equals(cameraId)) {
                backTorchOn = enabled;
                matched = true;
            } else if (frontTorchCameraId != null && frontTorchCameraId.equals(cameraId)) {
                frontTorchOn = enabled;
                matched = true;
            } else if (backTorchCameraId == null) {
                backTorchOn = enabled;
                matched = true;
            }
            if (!matched) return;
            boolean anyOn = backTorchOn || frontTorchOn;
            if (torchOn != anyOn) {
                torchOn = anyOn;
                if (isShowing()) {
                    liveHandler.post(QuickShadeHelper.this::refreshIfShowing);
                }
            }
        }
    };

    private long prevRxBytes = -1L;
    private long prevTxBytes = -1L;
    private long prevNetTimeMs = 0L;
    private long liveRxBps = 0L;
    private long liveTxBps = 0L;
    private boolean liveNetSampled = false;
    private ImageView liveNetIcon = null;
    private View liveNetBadge = null;

    private final Runnable liveNetTicker = new Runnable() {
        @Override
        public void run() {
            if (!isShowing()) return;
            updateLiveSpeedSample();
            // Читаем тип сети 1 раз за тик вместо 2 раз (убираем лишний системный IPC)
            String netType = resolveActiveNetTypeLabel();
            if (liveTrafficLabel != null) {
                liveTrafficLabel.setText(formatNetSpeedLine(netType, liveRxBps, liveTxBps, liveNetSampled));
            }
            refreshLiveNetIconColor(netType);
            liveHandler.postDelayed(this, 1000L);
        }
    };

    private boolean dimEnabled = false;
    private int currentTab = TAB_CONTROL;
    private boolean gridExpanded = false;
    private boolean silentExpanded = false;
    private int classicPage = 0;
    private float shadeSwipeStartX = 0f;
    private float shadeSwipeStartY = 0f;
    private final Set<String> expandedNotifKeys = new HashSet<>();
    private final Set<String> expandedGroupPackages = new HashSet<>();
    /**
     * Кэши иконок и подписей приложений для строк уведомлений.
     *
     * LruCache, а не HashMap: при переполнении вытесняется одна самая старая
     * запись, а не весь кэш. Иначе каждая прокрутка списка уведомлений заново
     * спрашивала PackageManager (IPC + разбор resources.apk) для всех строк.
     * Предел по количеству прежний — 64 записи.
     */
    private final LruCache<String, Drawable> appIconCache = new LruCache<String, Drawable>(64) {
        @Override
        protected int sizeOf(String key, Drawable value) {
            return 1; // считаем записи, а не байты: Drawable разного веса, но их мало
        }
    };
    private final LruCache<String, String> appLabelCache = new LruCache<String, String>(64) {
        @Override
        protected int sizeOf(String key, String value) {
            return 1;
        }
    };

    // Doxaiz 4.5: фильтры Центра уведомлений, защита ввода/свайпа и сохранение скролла
    private static final int FILTER_APP = 3;
    private int notifFilterMode = NotificationTools.FILTER_ALL;
    private String notifFilterPackage = "";
    private String lastDismissedBannerText = "";
    private long lastDismissedAtMs = 0L;
    private boolean inlineReplyActive = false;
    private boolean activeNotifSwiping = false;
    private boolean pendingDeferredRefresh = false;
    private boolean touchDownInHScroll = false;
    private ScrollView activeNotifScroll = null;
    private int savedNotifScrollY = 0;

    // Живая синхронизация медиаплеера (работает ТОЛЬКО пока шторка открыта и музыка играет)
    private MediaController activeMediaController = null;
    private SeekBar liveMediaSeekBar = null;
    private TextView liveMediaPosTv = null;
    private long liveMediaDurationMs = 0L;
    private boolean liveMediaUserSeeking = false;

    private final Runnable liveMediaTicker = new Runnable() {
        @Override
        public void run() {
            if (!isShowing() || activeMediaController == null || liveMediaSeekBar == null || liveMediaDurationMs <= 0L) {
                return;
            }
            try {
                PlaybackState st = activeMediaController.getPlaybackState();
                if (st == null) return;
                long pos = computeCurrentMediaPos(st, liveMediaDurationMs);
                if (!liveMediaUserSeeking) {
                    int permille = (int) Math.max(0L, Math.min(1000L, (pos * 1000L) / liveMediaDurationMs));
                    liveMediaSeekBar.setProgress(permille);
                    if (liveMediaPosTv != null) liveMediaPosTv.setText(formatMediaDuration(pos));
                }
                if (st.getState() == PlaybackState.STATE_PLAYING) {
                    liveHandler.postDelayed(this, 1000L);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    public QuickShadeHelper(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    public static int getAllTilesCount() {
        return ALL_TILES.size();
    }

    public void clearIconCache() {
        appIconCache.clear();
        appLabelCache.clear();
    }

    public void destroy() {
        if (pendingScreenshotRunnable != null) {
            actionHandler.removeCallbacks(pendingScreenshotRunnable);
            pendingScreenshotRunnable = null;
        }
        actionHandler.removeCallbacksAndMessages(null);
        dismiss();
        clearIconCache();
    }

    public boolean isShowing() {
        return shadeDialog != null && shadeDialog.isShowing();
    }

    private void resetTransientShadeState() {
        shadeDialog = null;
        shadeRoot = null;
        liveTrafficLabel = null;
        liveNetIcon = null;
        liveNetBadge = null;
        activeNotifScroll = null;
        savedNotifScrollY = 0;
        notifFilterMode = NotificationTools.FILTER_ALL;
        notifFilterPackage = "";
        lastDismissedBannerText = "";
        lastDismissedAtMs = 0L;
        inlineReplyActive = false;
        activeNotifSwiping = false;
        pendingDeferredRefresh = false;
        touchDownInHScroll = false;
        lastWrittenBrightnessVal = -1;
        brightnessManualApplied = false;
        cachedHotspotCheckedMs = 0L;
        lastWrittenVolumeStep = -1;
        lastLiveNetIconKey = "";
        expandedNotifKeys.clear();
        expandedGroupPackages.clear();
        // appIconCache/appLabelCache чистить не нужно: LruCache сам вытесняет
        // лишнее и никогда не превышает свой предел.
    }

    private void dismissChildDialog() {
        Dialog c = activeChildDialog;
        activeChildDialog = null;
        if (c != null && c.isShowing()) {
            try {
                c.setOnDismissListener(null);
                c.dismiss();
            } catch (Throwable ignored) {}
        }
    }

    private void trackChildDialog(final Dialog dlg) {
        dismissChildDialog();
        activeChildDialog = dlg;
        dlg.setOnDismissListener(d -> {
            if (activeChildDialog == dlg) activeChildDialog = null;
        });
    }

    public void dismiss() {
        dismissChildDialog();
        boolean wasShowing = isShowing();
        Dialog dlg = shadeDialog;
        stopShadeLiveMonitors();
        resetTransientShadeState();
        if (dlg != null && dlg.isShowing()) {
            try {
                dlg.setOnDismissListener(null);
                dlg.dismiss();
            } catch (Throwable ignored) {}
        }
        if (wasShowing) {
            host.onShadeVisibilityChanged(false);
        }
    }

    public void dismissAnimated() {
        if (shadeRoot == null || !isShowing()) {
            dismiss();
            return;
        }
        shadeRoot.animate().cancel();
        shadeRoot.animate()
                .translationY(-dp(220))
                .alpha(0f)
                .setDuration(145L)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.35f))
                .withLayer()
                .withEndAction(this::dismiss)
                .start();
    }

    /**
     * Чистая проверка: нужно ли перестраивать шторку при фоновом обновлении уведомлений.
     * В современном стиле на вкладке «Управление» карточек уведомлений нет —
     * игнорируем лишние фоновые тики, чтобы не срывать ползунки яркости/громкости.
     */
    static boolean shouldRebuildOnNotifUpdate(int shadeStyle, int activeTab) {
        if (shadeStyle == Prefs.SHADE_STYLE_CLASSIC) return true;
        return activeTab == TAB_NOTIFICATIONS;
    }

    public void onNotificationsChanged() {
        if (!isShowing() || shadeRoot == null) return;
        if (!shouldRebuildOnNotifUpdate(host.getPrefs().getShadeStyle(), currentTab)) {
            return;
        }
        refreshIfShowing();
    }

    public void refreshIfShowing() {
        if (!isShowing() || shadeRoot == null) return;
        // Не сносим поле быстрого ответа и не срываем свайп карточки во время фоновых тиков уведомлений!
        if (inlineReplyActive || activeNotifSwiping) {
            pendingDeferredRefresh = true;
            return;
        }
        rebuildShadeContent();
    }

    private void flushDeferredRefreshIfNeeded() {
        if (pendingDeferredRefresh && !inlineReplyActive && !activeNotifSwiping && isShowing()) {
            pendingDeferredRefresh = false;
            rebuildShadeContent();
        }
    }

    private boolean isTouchInsideHorizontalScroll(View view, float rawX, float rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof HorizontalScrollView) {
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            return rawX >= loc[0] && rawX <= loc[0] + view.getWidth()
                    && rawY >= loc[1] && rawY <= loc[1] + view.getHeight();
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                if (isTouchInsideHorizontalScroll(vg.getChildAt(i), rawX, rawY)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void show() {
        show(TAB_CONTROL);
    }

    public void show(int initialTab) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        dismiss();
        Prefs prefs = host.getPrefs();
        if (prefs.getShadeStyle() == Prefs.SHADE_STYLE_CLASSIC) {
            currentTab = TAB_CONTROL;
        } else {
            currentTab = (initialTab == TAB_NOTIFICATIONS) ? TAB_NOTIFICATIONS : TAB_CONTROL;
        }
        gridExpanded = false;
        classicPage = 0;

        shadeDialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window w = shadeDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(GlassTheme.makeRoundedRect(0xFF000000, 0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(0xFF000000);
            w.setNavigationBarColor(0xFF000000);
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }

        shadeRoot = new LinearLayout(activity) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                int act = ev.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) {
                    shadeSwipeStartX = ev.getX();
                    shadeSwipeStartY = ev.getY();
                    touchDownInHScroll = isTouchInsideHorizontalScroll(this, ev.getRawX(), ev.getRawY());
                } else if (act == MotionEvent.ACTION_MOVE && ev.getPointerCount() == 1) {
                    if (touchDownInHScroll || activeNotifSwiping || inlineReplyActive) {
                        return false;
                    }
                    float dx = ev.getX() - shadeSwipeStartX;
                    float dy = ev.getY() - shadeSwipeStartY;
                    int tabSwipeThreshold = (currentTab == TAB_NOTIFICATIONS) ? dp(84) : dp(52);
                    if (host.getPrefs().getShadeStyle() == Prefs.SHADE_STYLE_MODERN
                            && Math.abs(dx) > tabSwipeThreshold && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                        if (dx < 0 && currentTab == TAB_NOTIFICATIONS) {
                            performHaptic(this);
                            currentTab = TAB_CONTROL;
                            savedNotifScrollY = 0;
                            rebuildShadeContent();
                            return true;
                        } else if (dx > 0 && currentTab == TAB_CONTROL) {
                            performHaptic(this);
                            currentTab = TAB_NOTIFICATIONS;
                            savedNotifScrollY = 0;
                            rebuildShadeContent();
                            return true;
                        }
                    }
                } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    touchDownInHScroll = false;
                }
                return super.onInterceptTouchEvent(ev);
            }
        };
        shadeRoot.setOrientation(LinearLayout.VERTICAL);
        shadeRoot.setBackgroundColor(GlassTheme.SHADE_BG);
        int topInset = Math.max(dp(26), readShadeTopInset() + dp(6));
        int botInset = Math.max(dp(16), readShadeBottomInset() + dp(6));
        shadeRoot.setPadding(dp(16), topInset, dp(16), botInset);

        rebuildShadeContent();

        shadeDialog.setContentView(shadeRoot);
        shadeDialog.setOnDismissListener(d -> {
            stopShadeLiveMonitors();
            resetTransientShadeState();
            host.onShadeVisibilityChanged(false);
        });
        if (!safeShowDialog(shadeDialog)) {
            stopShadeLiveMonitors();
            shadeDialog = null;
            return;
        }
        host.onShadeVisibilityChanged(true);

        // Запускаем живые мониторы (включая IPC к CameraService для фонарика) сразу после 1-го кадра анимации,
        // чтобы открытие шторки не ждало системный cameraserver
        liveHandler.post(() -> {
            if (!isShowing()) return;
            DoxaizNotificationService.ensureBoundIfPermitted(activity);
            startShadeLiveMonitors();
        });

        if (prefs.isSmoothAnimationsEnabled()) {
            shadeRoot.setAlpha(0f);
            shadeRoot.setTranslationY(-dp(36));
            shadeRoot.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(165L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f))
                    .withLayer()
                    .start();
        }
    }

    private String resolveBackTorchCameraId(CameraManager cm) {
        if (backTorchCameraId != null) return backTorchCameraId;
        if (cm == null) return null;
        try {
            String fallbackWithFlash = null;
            for (String camId : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(camId);
                Boolean hasFlash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (!Boolean.TRUE.equals(hasFlash)) continue;
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK) {
                    backTorchCameraId = camId;
                    return camId;
                }
                if (fallbackWithFlash == null) fallbackWithFlash = camId;
            }
            backTorchCameraId = fallbackWithFlash;
            return fallbackWithFlash;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String resolveFrontTorchCameraId(CameraManager cm) {
        if (frontTorchProbed) return frontTorchCameraId;
        frontTorchProbed = true;
        if (cm == null) return null;
        try {
            for (String camId : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(camId);
                Boolean hasFlash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (!Boolean.TRUE.equals(hasFlash)) continue;
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontTorchCameraId = camId;
                    return camId;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void startShadeLiveMonitors() {
        try {
            CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (cm != null && !torchCbRegistered) {
                resolveBackTorchCameraId(cm);
                resolveFrontTorchCameraId(cm);
                cm.registerTorchCallback(torchCallback, liveHandler);
                torchCbRegistered = true;
            }
        } catch (Throwable ignored) {
        }
        prevRxBytes = readRawRxBytes();
        prevTxBytes = readRawTxBytes();
        prevNetTimeMs = SystemClock.uptimeMillis();
        liveRxBps = 0L;
        liveTxBps = 0L;
        liveNetSampled = false;
        liveHandler.removeCallbacks(liveNetTicker);
        if (host.getPrefs().isShowNetSpeed()) {
            liveHandler.postDelayed(liveNetTicker, 700L);
        }
    }

    private void stopShadeLiveMonitors() {
        liveHandler.removeCallbacksAndMessages(null);
        activeMediaController = null;
        liveMediaSeekBar = null;
        liveMediaPosTv = null;
        liveMediaDurationMs = 0L;
        liveMediaUserSeeking = false;
        if (torchCbRegistered) {
            try {
                CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
                if (cm != null) cm.unregisterTorchCallback(torchCallback);
            } catch (Throwable ignored) {
            }
            torchCbRegistered = false;
        }
    }

    private boolean safeShowDialog(Dialog d) {
        if (d == null || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return false;
        }
        try {
            d.show();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int readShadeTopInset() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    int top = wm.getCurrentWindowMetrics().getWindowInsets()
                            .getInsetsIgnoringVisibility(
                                    android.view.WindowInsets.Type.statusBars()
                                            | android.view.WindowInsets.Type.displayCutout())
                            .top;
                    if (top > 0) return top;
                }
            } catch (Throwable ignored) {
            }
        }
        return readSystemBarDimen("status_bar_height");
    }

    private int readShadeBottomInset() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    int bot = wm.getCurrentWindowMetrics().getWindowInsets()
                            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
                            .bottom;
                    if (bot >= 0) return bot;
                }
            } catch (Throwable ignored) {
            }
        }
        return readSystemBarDimen("navigation_bar_height");
    }

    private int readSystemBarDimen(String name) {
        try {
            int resId = activity.getResources().getIdentifier(name, "dimen", "android");
            return resId > 0 ? activity.getResources().getDimensionPixelSize(resId) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void rebuildShadeContent() {
        if (shadeRoot == null) return;
        if (activeNotifScroll != null) {
            savedNotifScrollY = activeNotifScroll.getScrollY();
        }
        liveHandler.removeCallbacks(liveMediaTicker);
        activeMediaController = null;
        liveMediaSeekBar = null;
        liveMediaPosTv = null;
        liveMediaDurationMs = 0L;
        liveMediaUserSeeking = false;
        inlineReplyActive = false;
        activeNotifSwiping = false;
        pendingDeferredRefresh = false;
        activeNotifScroll = null;
        shadeRoot.removeAllViews();
        liveTrafficLabel = null;
        liveNetIcon = null;
        liveNetBadge = null;

        Prefs prefs = host.getPrefs();
        boolean isClassic = (prefs.getShadeStyle() == Prefs.SHADE_STYLE_CLASSIC);

        // Один снимок NetworkCapabilities на весь кадр пересборки шторки (вместо 4 повторных IPC к ConnectivityService)
        frameNetCaps = queryActiveNetworkCapabilitiesFresh();
        hasFrameNetSnapshot = true;
        try {
            if (isClassic) {
                buildClassicShade(shadeRoot, prefs);
            } else if (currentTab == TAB_CONTROL) {
                buildModernControlCenter(shadeRoot, prefs);
            } else {
                buildModernNotificationCenter(shadeRoot, prefs);
            }
        } finally {
            hasFrameNetSnapshot = false;
            frameNetCaps = null;
        }
    }

    private NetworkCapabilities queryActiveNetworkCapabilitiesFresh() {
        try {
            if (cachedConnectivityManager == null) {
                cachedConnectivityManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            }
            ConnectivityManager cm = cachedConnectivityManager;
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                return (active != null) ? cm.getNetworkCapabilities(active) : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private NetworkCapabilities getNetworkCapabilitiesForFrame() {
        if (hasFrameNetSnapshot) return frameNetCaps;
        return queryActiveNetworkCapabilitiesFresh();
    }

    // =========================================================================
    // 1. СОВРЕМЕННЫЙ ЦЕНТР УПРАВЛЕНИЯ (чистые векторные иконки + живые слайдеры)
    // =========================================================================

    private void buildModernControlCenter(LinearLayout root, Prefs prefs) {
        root.addView(buildTopClockHeader(true));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(10), 0, dp(12));

        if (prefs.isShowNetSpeed()) {
            body.addView(buildNetSpeedPill());
        }
        body.addView(buildTopDualPanel());

        List<String> gridTiles = getGridTileIds(prefs);
        body.addView(buildModernTileGrid(gridTiles, gridExpanded));

        // Кнопка разворота / сворачивания сетки (векторный шеврон)
        FrameLayout chevBtn = new FrameLayout(activity);
        LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(dp(56), dp(34));
        exLp.gravity = Gravity.CENTER_HORIZONTAL;
        exLp.topMargin = dp(2);
        chevBtn.setLayoutParams(exLp);
        ImageView chevIc = VectorGlyphs.makeIconView(
                activity, gridExpanded ? "chevron_up" : "chevron_down", 22, 0xCCFFFFFF);
        FrameLayout.LayoutParams ciLp = new FrameLayout.LayoutParams(dp(22), dp(22));
        ciLp.gravity = Gravity.CENTER;
        chevBtn.addView(chevIc, ciLp);
        chevBtn.setOnClickListener(v -> {
            performHaptic(v);
            gridExpanded = !gridExpanded;
            rebuildShadeContent();
        });
        body.addView(chevBtn);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildBottomNavBar(TAB_CONTROL));
    }

    private View buildTopClockHeader(boolean isControlCenter) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(2), dp(2), dp(6));

        Date now = new Date();
        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView clockTv = new TextView(activity);
        clockTv.setText(formatTime(now));
        clockTv.setTextColor(Color.WHITE);
        clockTv.setTextSize(32f);
        clockTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        left.addView(clockTv);

        TextView dateTv = new TextView(activity);
        String dStr = formatDate(now);
        if (!dStr.isEmpty()) {
            dStr = Character.toUpperCase(dStr.charAt(0)) + dStr.substring(1);
        }
        dateTv.setText(dStr);
        dateTv.setTextColor(GlassTheme.TEXT_SECONDARY);
        dateTv.setTextSize(13f);
        left.addView(dateTv);

        header.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (isControlCenter) {
            View editBtn = makeHeaderIconBtn("edit", "Изменить плитки");
            editBtn.setOnClickListener(v -> {
                performHaptic(v);
                showTileEditorDialog();
            });
            header.addView(editBtn);

            View settingsBtn = makeHeaderIconBtn("settings", "Настройки");
            settingsBtn.setOnClickListener(v -> {
                performHaptic(v);
                dismiss();
                host.openLauncherSettings();
            });
            header.addView(settingsBtn);

            View moreBtn = makeHeaderIconBtn("more", "Меню шторки");
            moreBtn.setOnClickListener(v -> {
                performHaptic(v);
                showShadeOverflowMenu();
            });
            header.addView(moreBtn);
        } else {
            View histBtn = makeHeaderIconBtn("history", "История уведомлений");
            histBtn.setOnClickListener(v -> {
                performHaptic(v);
                showNotificationHistoryDialog();
            });
            header.addView(histBtn);

            View notifCfgBtn = makeHeaderIconBtn("bell", "Уведомления и строка состояния");
            notifCfgBtn.setOnClickListener(v -> {
                performHaptic(v);
                dismiss();
                host.openNotifStatusSettings();
            });
            header.addView(notifCfgBtn);

            View settingsBtn = makeHeaderIconBtn("settings", "Настройки");
            settingsBtn.setOnClickListener(v -> {
                performHaptic(v);
                dismiss();
                host.openLauncherSettings();
            });
            header.addView(settingsBtn);
        }

        return header;
    }

    private View makeHeaderIconBtn(String glyph, String desc) {
        FrameLayout btn = new FrameLayout(activity);
        btn.setContentDescription(desc);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(4);
        btn.setLayoutParams(lp);
        // Doxaiz 4.6: чистые минималистичные иконки шапки без тяжёлых круглых рамок
        btn.setBackgroundColor(Color.TRANSPARENT);

        ImageView iv = VectorGlyphs.makeIconView(activity, glyph, 20, Color.WHITE);
        FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(dp(20), dp(20));
        iLp.gravity = Gravity.CENTER;
        btn.addView(iv, iLp);
        return btn;
    }

    /**
     * Doxaiz 4.6: Компактная строка живой скорости сети (КБ/с, МБ/с) с зелёным бейджем-индикатором,
     * строго без счётчика расхода трафика.
     */
    private View buildNetSpeedPill() {
        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(GlassTheme.makeRoundedRect(0xFF1A1A1E, dp(16)));
        pill.setPadding(dp(12), dp(9), dp(14), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        pill.setLayoutParams(lp);

        String netType = resolveActiveNetTypeLabel();
        String icoName = "Wi-Fi".equals(netType) ? "wifi" : "mobile";
        int badgeColor = resolveNetIconColor(netType);
        lastLiveNetIconKey = icoName + "#" + badgeColor;

        FrameLayout badge = new FrameLayout(activity);
        badge.setBackground(GlassTheme.makeCircle(badgeColor));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        bLp.rightMargin = dp(9);
        liveNetIcon = VectorGlyphs.makeIconView(activity, icoName, 13, Color.WHITE);
        FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(dp(13), dp(13));
        iLp.gravity = Gravity.CENTER;
        badge.addView(liveNetIcon, iLp);
        liveNetBadge = badge;
        pill.addView(badge, bLp);

        liveTrafficLabel = new TextView(activity);
        liveTrafficLabel.setText(formatNetSpeedLine(netType, liveRxBps, liveTxBps, liveNetSampled));
        liveTrafficLabel.setTextColor(0xE6FFFFFF);
        liveTrafficLabel.setTextSize(12.5f);
        liveTrafficLabel.setSingleLine(true);
        liveTrafficLabel.setEllipsize(TextUtils.TruncateAt.END);
        pill.addView(liveTrafficLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(activity);
        arrow.setText("›");
        arrow.setTextColor(GlassTheme.TEXT_SECONDARY);
        arrow.setTextSize(18f);
        pill.addView(arrow);

        pill.setOnClickListener(v -> {
            performHaptic(v);
            dismiss();
            openSystemAction(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_WIFI_SETTINGS);
        });
        return pill;
    }

    private View buildTopDualPanel() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(154));
        rowLp.bottomMargin = dp(12);
        row.setLayoutParams(rowLp);

        LinearLayout leftCol = new LinearLayout(activity);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lcLp.rightMargin = dp(10);
        leftCol.setLayoutParams(lcLp);

        boolean wifiOn = isTileActive("wifi");
        boolean mobOn = isTileActive("mobile");

        View wifiCard = buildConnectivityBigTile(
                "wifi", "Wi-Fi", wifiOn ? readWifiSubtext() : "Откл.",
                wifiOn ? GlassTheme.TILE_ACTIVE_BLUE : GlassTheme.TILE_INACTIVE,
                v -> {
                    performHaptic(v);
                    showWifiPopupCard();
                },
                v -> {
                    dismiss();
                    openSystemAction(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS);
                    return true;
                }
        );
        LinearLayout.LayoutParams wcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wcLp.bottomMargin = dp(10);
        leftCol.addView(wifiCard, wcLp);

        View mobileCard = buildConnectivityBigTile(
                "mobile", "Данные", mobOn ? readMobileSubtext() : "Откл.",
                mobOn ? GlassTheme.TILE_ACTIVE_GREEN : GlassTheme.TILE_INACTIVE,
                v -> {
                    performHaptic(v);
                    showMobileDataPopupCard();
                },
                v -> {
                    dismiss();
                    openSystemAction(Settings.ACTION_NETWORK_OPERATOR_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS);
                    return true;
                }
        );
        leftCol.addView(mobileCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        row.addView(leftCol);

        LinearLayout rightCol = new LinearLayout(activity);
        rightCol.setOrientation(LinearLayout.HORIZONTAL);
        rightCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        View brightSlider = buildVerticalSlider(true);
        LinearLayout.LayoutParams bsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        bsLp.rightMargin = dp(10);
        rightCol.addView(brightSlider, bsLp);

        View volSlider = buildVerticalSlider(false);
        rightCol.addView(volSlider, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        row.addView(rightCol);

        return row;
    }

    private View buildConnectivityBigTile(
            String glyph, String title, String sub, int bgColor,
            View.OnClickListener onClick, View.OnLongClickListener onLongClick
    ) {
        FrameLayout card = new FrameLayout(activity);
        card.setBackground(GlassTheme.makeRoundedRect(bgColor, dp(20)));
        card.setPadding(dp(14), dp(10), dp(12), dp(10));

        LinearLayout inner = new LinearLayout(activity);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView ic = VectorGlyphs.makeIconView(activity, glyph, 22, Color.WHITE);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        icLp.rightMargin = dp(11);
        inner.addView(ic, icLp);

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(t);

        TextView s = new TextView(activity);
        s.setText(sub);
        s.setTextColor(0xCCFFFFFF);
        s.setTextSize(11f);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(s);

        inner.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView corner = new TextView(activity);
        corner.setText("◢");
        corner.setTextColor(0x99FFFFFF);
        corner.setTextSize(8f);
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.gravity = Gravity.BOTTOM | Gravity.END;
        card.addView(corner, cLp);

        card.setOnClickListener(onClick);
        card.setOnLongClickListener(onLongClick);
        return card;
    }

    /**
     * Вертикальный тактильный слайдер с векторной иконкой и живым процентом вверху капсулы.
     */
    private View buildVerticalSlider(final boolean isBrightness) {
        final FrameLayout container = new FrameLayout(activity);
        container.setBackground(GlassTheme.makeRoundedRect(GlassTheme.SLIDER_TRACK, dp(24)));
        container.setClipToOutline(true);

        final View fillView = new View(activity);
        fillView.setBackgroundColor(GlassTheme.SLIDER_FILL);
        FrameLayout.LayoutParams fLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        fLp.gravity = Gravity.BOTTOM;
        container.addView(fillView, fLp);

        final float[] level = {isBrightness ? readBrightnessFraction() : readVolumeFraction()};

        final TextView pctTv = new TextView(activity);
        pctTv.setText(Math.round(level[0] * 100) + "%");
        pctTv.setTextColor(0xCCFFFFFF);
        pctTv.setTextSize(10.5f);
        pctTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pctTv.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        pLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        pLp.topMargin = dp(6);
        container.addView(pctTv, pLp);

        if (isBrightness) {
            if (hasHardwareLightSensor()) {
                final boolean autoNow = isAutoBrightnessOn();
                TextView autoBtn = new TextView(activity);
                autoBtn.setText("A");
                autoBtn.setTextColor(Color.WHITE);
                autoBtn.setTextSize(10.5f);
                autoBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                autoBtn.setGravity(Gravity.CENTER);
                autoBtn.setBackground(GlassTheme.makeCircle(autoNow ? GlassTheme.TILE_ACTIVE_BLUE : 0x55000000));
                FrameLayout.LayoutParams aLp = new FrameLayout.LayoutParams(dp(22), dp(22));
                aLp.gravity = Gravity.TOP | Gravity.END;
                aLp.topMargin = dp(7);
                aLp.rightMargin = dp(7);
                autoBtn.setOnClickListener(v -> {
                    performHaptic(v);
                    toggleAutoBrightness();
                    rebuildShadeContent();
                });
                container.addView(autoBtn, aLp);
            }
        } else {
            pctTv.setOnClickListener(v -> {
                performHaptic(v);
                dismiss();
                host.openSoundPanel();
            });
        }

        final ImageView iconIv = VectorGlyphs.makeIconView(
                activity,
                isBrightness ? "sun" : "volume",
                21,
                isBrightness ? GlassTheme.ACCENT_AMBER : 0xFF18181B
        );
        FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(dp(21), dp(21));
        iLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        iLp.bottomMargin = dp(13);
        container.addView(iconIv, iLp);

        final Runnable renderFill = () -> {
            int h = container.getHeight();
            if (h <= 0) h = dp(154);
            int fillH = Math.max(dp(30), Math.round(h * level[0]));
            ViewGroup.LayoutParams lp = fillView.getLayoutParams();
            lp.height = fillH;
            fillView.setLayoutParams(lp);
            int pct = Math.round(level[0] * 100);
            pctTv.setText(isBrightness ? (pct + "%") : (pct + "% ≡"));
            pctTv.setTextColor(level[0] > 0.85f ? 0xFF18181B : 0xCCFFFFFF);
            if (!isBrightness) {
                iconIv.setImageDrawable(VectorGlyphs.create(
                        activity,
                        pct <= 2 ? "mute" : "volume",
                        21,
                        level[0] < 0.24f ? Color.WHITE : 0xFF18181B
                ));
            }
        };
        container.post(renderFill);

        container.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                performHaptic(v);
                v.animate().scaleX(1.035f).scaleY(1.02f).setDuration(90L).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(130L).start();
            }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                int h = v.getHeight();
                if (h > 0) {
                    float frac = 1f - (event.getY() / h);
                    frac = Math.max(0.02f, Math.min(1f, frac));
                    level[0] = frac;
                    renderFill.run();
                    if (isBrightness) {
                        applyBrightnessFraction(frac);
                    } else {
                        applyVolumeFraction(frac);
                    }
                }
                return true;
            }
            return false;
        });

        return container;
    }

    private View buildModernTileGrid(List<String> tileIds, boolean expanded) {
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);

        List<String> displayList = new ArrayList<>(tileIds);
        int maxCount = expanded ? displayList.size() : Math.min(displayList.size(), 16);
        List<String> shown = new ArrayList<>(displayList.subList(0, maxCount));
        if (expanded) {
            shown.add("__add__");
        }

        final int cols = 4;
        for (int i = 0; i < shown.size(); i += cols) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = dp(expanded ? 12 : 10);
            row.setLayoutParams(rLp);

            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                if (idx < shown.size()) {
                    String id = shown.get(idx);
                    View cell = "__add__".equals(id)
                            ? buildAddTileCell()
                            : buildSquircleTileCell(id, expanded);
                    LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (c < cols - 1) cellLp.rightMargin = dp(10);
                    row.addView(cell, cellLp);
                } else {
                    LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
                    if (c < cols - 1) spLp.rightMargin = dp(10);
                    row.addView(new View(activity), spLp);
                }
            }
            grid.addView(row);
        }
        return grid;
    }

    private View buildSquircleTileCell(final String tileId, boolean showLabel) {
        TileSpec spec = ALL_TILES.get(tileId);
        if (spec == null) return new View(activity);

        boolean active = isTileActive(tileId);
        int bgColor = active ? GlassTheme.TILE_ACTIVE_BLUE : GlassTheme.TILE_INACTIVE;

        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout box = new FrameLayout(activity);
        box.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        box.setBackground(GlassTheme.makeRoundedRect(bgColor, dp(18)));

        ImageView iv = VectorGlyphs.makeIconView(activity, resolveTileGlyph(tileId, spec), 23, Color.WHITE);
        FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(dp(23), dp(23));
        iLp.gravity = Gravity.CENTER;
        box.addView(iv, iLp);

        if (hasExpandCorner(tileId)) {
            FrameLayout cornerHit = new FrameLayout(activity);
            TextView tri = new TextView(activity);
            tri.setText("◢");
            tri.setTextColor(0x88FFFFFF);
            tri.setTextSize(7f);
            FrameLayout.LayoutParams tLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.gravity = Gravity.BOTTOM | Gravity.END;
            tLp.setMargins(0, 0, dp(7), dp(5));
            cornerHit.addView(tri, tLp);

            FrameLayout.LayoutParams chLp = new FrameLayout.LayoutParams(dp(28), dp(26));
            chLp.gravity = Gravity.BOTTOM | Gravity.END;
            if ("torch".equals(tileId) || "sound".equals(tileId)) {
                cornerHit.setOnClickListener(v -> {
                    performHaptic(v);
                    if ("torch".equals(tileId)) {
                        showTorchPopupCard();
                    } else {
                        showSoundQuickPopupCard();
                    }
                });
            }
            box.addView(cornerHit, chLp);
        }

        wrap.addView(box);

        if (showLabel) {
            TextView lbl = new TextView(activity);
            lbl.setText(resolveTileLabel(tileId, spec));
            lbl.setTextColor(0xD9FFFFFF);
            lbl.setTextSize(11f);
            lbl.setGravity(Gravity.CENTER);
            lbl.setMaxLines(2);
            lbl.setEllipsize(TextUtils.TruncateAt.END);
            lbl.setPadding(dp(2), dp(5), dp(2), 0);
            wrap.addView(lbl);
        }

        wrap.setOnClickListener(v -> {
            performHaptic(v);
            handleTileClick(tileId);
        });
        wrap.setOnLongClickListener(v -> {
            performHaptic(v);
            handleTileLongClick(tileId);
            return true;
        });
        return wrap;
    }

    private View buildAddTileCell() {
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout box = new FrameLayout(activity);
        box.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        GradientDrawable dashed = new GradientDrawable();
        dashed.setShape(GradientDrawable.RECTANGLE);
        dashed.setCornerRadius(dp(18));
        dashed.setColor(0xFF1A1A1E);
        dashed.setStroke(dp(1), 0x45FFFFFF);
        box.setBackground(dashed);

        ImageView plus = VectorGlyphs.makeIconView(activity, "plus", 22, Color.WHITE);
        FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(dp(22), dp(22));
        pLp.gravity = Gravity.CENTER;
        box.addView(plus, pLp);
        wrap.addView(box);

        TextView lbl = new TextView(activity);
        lbl.setText("Добавить");
        lbl.setTextColor(0xD9FFFFFF);
        lbl.setTextSize(11f);
        lbl.setGravity(Gravity.CENTER);
        lbl.setPadding(0, dp(5), 0, 0);
        wrap.addView(lbl);

        wrap.setOnClickListener(v -> {
            performHaptic(v);
            showTileEditorDialog();
        });
        return wrap;
    }

    // =========================================================================
    // 2. СОВРЕМЕННЫЙ ЦЕНТР УВЕДОМЛЕНИЙ (Swipe-to-Dismiss + реальные Actions + Без звука)
    // =========================================================================

    private void buildModernNotificationCenter(LinearLayout root, Prefs prefs) {
        root.addView(buildTopClockHeader(false));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(8), 0, dp(12));

        DoxaizNotificationService.MediaInfo media = DoxaizNotificationService.getActiveMediaInfo();
        if (media != null) {
            body.addView(buildMediaCard(media));
        }

        boolean hasAccess = DoxaizNotificationService.isServiceConnected();
        List<DoxaizNotificationService.NotificationItem> items = DoxaizNotificationService.snapshotNotifications();
        List<DoxaizNotificationService.NotificationItem> silentItems = DoxaizNotificationService.snapshotSilentNotifications();

        if (!hasAccess) {
            body.addView(buildAccessBanner());
        } else if (items.isEmpty() && silentItems.isEmpty()) {
            body.addView(buildEmptyNotifState());
        } else {
            boolean hideText = prefs.isHideNotifTextInShade();
            View filterBar = buildNotifFilterBar(items, hideText);
            if (filterBar != null) {
                body.addView(filterBar);
            }
            List<DoxaizNotificationService.NotificationItem> filtered = applyNotificationFilter(items, hideText);
            if (filtered.isEmpty() && !items.isEmpty()) {
                // Авто-сброс, если фильтр опустел после смахивания последнего уведомления в категории
                notifFilterMode = NotificationTools.FILTER_ALL;
                notifFilterPackage = "";
                filtered = items;
            }
            addNotificationsWithGrouping(body, filtered, hideText, prefs.isShadeGroupNotifications());
            if (!silentItems.isEmpty() && notifFilterMode == NotificationTools.FILTER_ALL) {
                body.addView(buildSilentSection(silentItems, hideText));
            }
        }

        scroll.addView(body);
        activeNotifScroll = scroll;
        if (savedNotifScrollY > 0) {
            final int targetY = savedNotifScrollY;
            scroll.post(() -> scroll.scrollTo(0, targetY));
        }
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View undoPill = buildUndoBannerPillIfActive();
        if (undoPill != null) {
            root.addView(undoPill);
        }

        // Круглая векторная кнопка очистки по центру внизу
        if (hasAccess && !items.isEmpty()) {
            FrameLayout clearCircle = new FrameLayout(activity);
            clearCircle.setBackground(GlassTheme.makeCircle(0xFF2E2E32));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(48), dp(48));
            clp.gravity = Gravity.CENTER_HORIZONTAL;
            clp.topMargin = dp(6);
            clp.bottomMargin = dp(8);
            clearCircle.setLayoutParams(clp);

            ImageView trashIv = VectorGlyphs.makeIconView(activity, "trash", 20, Color.WHITE);
            FrameLayout.LayoutParams tLp = new FrameLayout.LayoutParams(dp(20), dp(20));
            tLp.gravity = Gravity.CENTER;
            clearCircle.addView(trashIv, tLp);

            clearCircle.setOnClickListener(v -> {
                performHaptic(v);
                int cleared = DoxaizNotificationService.clearAllClearable();
                if (cleared > 0) {
                    recordDismissedBanner("Очищено: " + cleared);
                } else {
                    host.showToast("Нет смахиваемых уведомлений");
                }
                rebuildShadeContent();
            });
            root.addView(clearCircle);
        }

        root.addView(buildBottomNavBar(TAB_NOTIFICATIONS));
    }

    private View buildNotifFilterBar(List<DoxaizNotificationService.NotificationItem> items, boolean hideText) {
        if (items == null || items.isEmpty()) return null;
        int msgCount = 0;
        int codeCount = 0;
        Map<String, Integer> appCounts = new LinkedHashMap<>();
        for (DoxaizNotificationService.NotificationItem it : items) {
            if (it.isConversation) msgCount++;
            if (!hideText && it.otpCode != null) codeCount++;
            Integer c = appCounts.get(it.packageName);
            appCounts.put(it.packageName, (c != null ? c : 0) + 1);
        }
        // Показываем полосу фильтров, если есть смысл фильтровать (2+ приложений, либо есть коды/сообщения)
        if (appCounts.size() < 2 && codeCount == 0 && msgCount == 0) {
            return null;
        }

        HorizontalScrollView hsv = new HorizontalScrollView(activity);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp(10);
        hsv.setLayoutParams(hLp);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(makeNotifFilterChip(
                "Все " + items.size(),
                notifFilterMode == NotificationTools.FILTER_ALL,
                GlassTheme.TILE_ACTIVE_BLUE,
                () -> {
                    notifFilterMode = NotificationTools.FILTER_ALL;
                    notifFilterPackage = "";
                    rebuildShadeContent();
                }));

        if (codeCount > 0) {
            row.addView(makeNotifFilterChip(
                    "Коды " + codeCount,
                    notifFilterMode == NotificationTools.FILTER_CODES,
                    GlassTheme.TILE_ACTIVE_GREEN,
                    () -> {
                        notifFilterMode = NotificationTools.FILTER_CODES;
                        notifFilterPackage = "";
                        rebuildShadeContent();
                    }));
        }

        if (msgCount > 0) {
            row.addView(makeNotifFilterChip(
                    "Сообщения " + msgCount,
                    notifFilterMode == NotificationTools.FILTER_MESSAGES,
                    GlassTheme.TILE_ACTIVE_BLUE,
                    () -> {
                        notifFilterMode = NotificationTools.FILTER_MESSAGES;
                        notifFilterPackage = "";
                        rebuildShadeContent();
                    }));
        }

        if (appCounts.size() >= 2) {
            int addedApps = 0;
            for (Map.Entry<String, Integer> e : appCounts.entrySet()) {
                final String pkg = e.getKey();
                final int cnt = e.getValue();
                // Раньше здесь было ручное обрезание по 14 символам: кириллица
                // и латиница разной ширины, поэтому «Яндекс.Карты» теряли букву
                // при свободном месте, а латинские названия обрезались зря.
                // Теперь обрезку делает TextView по реальной ширине чипа.
                String lbl = loadAppLabelCached(pkg);
                boolean active = (notifFilterMode == FILTER_APP && pkg.equals(notifFilterPackage));
                row.addView(makeNotifFilterChip(
                        lbl + (cnt > 1 ? (" " + cnt) : ""),
                        active,
                        GlassTheme.TILE_ACTIVE_BLUE,
                        () -> {
                            notifFilterMode = FILTER_APP;
                            notifFilterPackage = pkg;
                            rebuildShadeContent();
                        }));
                addedApps++;
                if (addedApps >= 5) break;
            }
        }

        hsv.addView(row);
        return hsv;
    }

    private View makeNotifFilterChip(String text, boolean active, int activeColor, Runnable onClick) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(active ? Color.WHITE : 0xCCFFFFFF);
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setBackground(GlassTheme.makePill(active ? activeColor : 0xFF1F1F24));
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        // Длинное название приложения не должно растягивать ряд чипов за экран:
        // ограничиваем ширину и отдаём обрезку TextView (многоточие по метрикам
        // шрифта, а не по числу символов).
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(150));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(7);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> {
            performHaptic(v);
            onClick.run();
        });
        return tv;
    }

    private List<DoxaizNotificationService.NotificationItem> applyNotificationFilter(
            List<DoxaizNotificationService.NotificationItem> items, boolean hideText
    ) {
        if (notifFilterMode == NotificationTools.FILTER_ALL || items == null) return items;
        List<DoxaizNotificationService.NotificationItem> out = new ArrayList<>();
        for (DoxaizNotificationService.NotificationItem it : items) {
            if (notifFilterMode == NotificationTools.FILTER_MESSAGES && it.isConversation) {
                out.add(it);
            } else if (notifFilterMode == NotificationTools.FILTER_CODES && !hideText && it.otpCode != null) {
                out.add(it);
            } else if (notifFilterMode == FILTER_APP && it.packageName.equals(notifFilterPackage)) {
                out.add(it);
            }
        }
        return out;
    }

    private View buildUndoBannerPillIfActive() {
        if (lastDismissedBannerText.isEmpty() || (System.currentTimeMillis() - lastDismissedAtMs) >= 6500L) {
            return null;
        }
        LinearLayout undoPill = new LinearLayout(activity);
        undoPill.setOrientation(LinearLayout.HORIZONTAL);
        undoPill.setGravity(Gravity.CENTER_VERTICAL);
        undoPill.setBackground(GlassTheme.makeRoundedRect(0xFF1F2430, dp(16), 0x382B8BFF, dp(1)));
        undoPill.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        upLp.gravity = Gravity.CENTER_HORIZONTAL;
        upLp.topMargin = dp(4);
        upLp.bottomMargin = dp(2);
        undoPill.setLayoutParams(upLp);

        ImageView hIco = VectorGlyphs.makeIconView(activity, "history", 14, 0xFF60A5FA);
        LinearLayout.LayoutParams hiLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        hiLp.rightMargin = dp(6);
        undoPill.addView(hIco, hiLp);

        TextView uTxt = new TextView(activity);
        uTxt.setText(lastDismissedBannerText + " • История ›");
        uTxt.setTextColor(0xFFDBEAFE);
        uTxt.setTextSize(12f);
        uTxt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        undoPill.addView(uTxt);

        undoPill.setOnClickListener(v -> {
            performHaptic(v);
            lastDismissedBannerText = "";
            showNotificationHistoryDialog();
        });
        return undoPill;
    }

    private void recordDismissedBanner(String text) {
        lastDismissedBannerText = text != null ? text : "Смахнуто";
        lastDismissedAtMs = System.currentTimeMillis();
    }

    private void copyToClipboard(String label, String text, String toastMsg, boolean sensitive) {
        try {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && text != null && !text.isEmpty()) {
                ClipData clip = ClipData.newPlainText(label, text);
                if (sensitive && Build.VERSION.SDK_INT >= 33) {
                    PersistableBundle extras = new PersistableBundle();
                    extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                    clip.getDescription().setExtras(extras);
                }
                cm.setPrimaryClip(clip);
                host.showToast(toastMsg);
            }
        } catch (Throwable ignored) {
        }
    }

    private void addNotificationsWithGrouping(
            LinearLayout container,
            List<DoxaizNotificationService.NotificationItem> items,
            boolean hideText,
            boolean groupEnabled
    ) {
        if (!groupEnabled || items.size() <= 1) {
            for (DoxaizNotificationService.NotificationItem item : items) {
                container.addView(buildRealNotifCard(item, hideText, false));
            }
            return;
        }
        Map<String, List<DoxaizNotificationService.NotificationItem>> byPkg = new LinkedHashMap<>();
        for (DoxaizNotificationService.NotificationItem it : items) {
            List<DoxaizNotificationService.NotificationItem> g = byPkg.get(it.packageName);
            if (g == null) {
                g = new ArrayList<>();
                byPkg.put(it.packageName, g);
            }
            g.add(it);
        }
        for (Map.Entry<String, List<DoxaizNotificationService.NotificationItem>> entry : byPkg.entrySet()) {
            String pkg = entry.getKey();
            List<DoxaizNotificationService.NotificationItem> group = entry.getValue();
            if (group.size() == 1) {
                container.addView(buildRealNotifCard(group.get(0), hideText, false));
            } else if (!expandedGroupPackages.contains(pkg)) {
                container.addView(buildCollapsedGroupStack(pkg, group, hideText));
            } else {
                // Заголовок развёрнутой группы
                LinearLayout gHdr = new LinearLayout(activity);
                gHdr.setOrientation(LinearLayout.HORIZONTAL);
                gHdr.setGravity(Gravity.CENTER_VERTICAL);
                gHdr.setPadding(dp(4), dp(4), dp(4), dp(6));

                Drawable appIcon = loadAppIconCached(pkg);
                if (appIcon != null) {
                    ImageView iv = new ImageView(activity);
                    iv.setImageDrawable(appIcon);
                    LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(18), dp(18));
                    iLp.rightMargin = dp(7);
                    gHdr.addView(iv, iLp);
                }

                TextView gTitle = new TextView(activity);
                gTitle.setText(loadAppLabelCached(pkg) + " • " + group.size());
                gTitle.setTextColor(Color.WHITE);
                gTitle.setTextSize(13f);
                gTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                gHdr.addView(gTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                // Doxaiz 4.1: быстрая кнопка «Отключить уведомления» для приложения
                FrameLayout muteGrpBtn = new FrameLayout(activity);
                muteGrpBtn.setBackground(GlassTheme.makeRoundedRect(0x22FFFFFF, dp(9)));
                muteGrpBtn.setPadding(dp(7), dp(4), dp(7), dp(4));
                muteGrpBtn.setContentDescription("Отключить уведомления приложения");
                ImageView mgIco = VectorGlyphs.makeIconView(activity, "mute", 14, GlassTheme.TEXT_SECONDARY);
                muteGrpBtn.addView(mgIco, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
                LinearLayout.LayoutParams mgLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mgLp.rightMargin = dp(4);
                muteGrpBtn.setOnClickListener(v -> {
                    performHaptic(v);
                    openAppNotificationSettings(pkg);
                });
                gHdr.addView(muteGrpBtn, mgLp);

                TextView collapseBtn = new TextView(activity);
                collapseBtn.setText("Свернуть");
                collapseBtn.setTextColor(0xFF60A5FA);
                collapseBtn.setTextSize(12f);
                collapseBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
                collapseBtn.setOnClickListener(v -> {
                    performHaptic(v);
                    expandedGroupPackages.remove(pkg);
                    rebuildShadeContent();
                });
                gHdr.addView(collapseBtn);

                TextView clearGrpBtn = new TextView(activity);
                clearGrpBtn.setText("Убрать все");
                clearGrpBtn.setTextColor(0xFFFCA5A5);
                clearGrpBtn.setTextSize(12f);
                clearGrpBtn.setPadding(dp(6), dp(4), dp(4), dp(4));
                clearGrpBtn.setOnClickListener(v -> {
                    performHaptic(v);
                    int n = DoxaizNotificationService.dismissPackageNotifications(pkg);
                    expandedGroupPackages.remove(pkg);
                    host.showToast("Убрано: " + n);
                    rebuildShadeContent();
                });
                gHdr.addView(clearGrpBtn);
                container.addView(gHdr);

                for (DoxaizNotificationService.NotificationItem it : group) {
                    container.addView(buildRealNotifCard(it, hideText, false));
                }
            }
        }
    }

    private View buildCollapsedGroupStack(
            final String pkg,
            final List<DoxaizNotificationService.NotificationItem> group,
            boolean hideText
    ) {
        final DoxaizNotificationService.NotificationItem latest = group.get(0);
        final LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wLp.bottomMargin = dp(8);
        wrap.setLayoutParams(wLp);

        final LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GlassTheme.makeStyledSurface(activity, GlassTheme.NOTIF_CARD, 20f, true));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        Drawable appIcon = loadAppIconCached(pkg);
        if (appIcon != null) {
            ImageView iv = new ImageView(activity);
            iv.setImageDrawable(appIcon);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            iLp.rightMargin = dp(8);
            topRow.addView(iv, iLp);
        }

        String appName = loadAppLabelCached(pkg);
        String relTime = NotificationTools.formatRelativeTime(
                latest.postTime, System.currentTimeMillis(), formatTime(new Date(latest.postTime)));
        TextView meta = new TextView(activity);
        meta.setText(appName + " • " + relTime);
        meta.setTextColor(GlassTheme.TEXT_SECONDARY);
        meta.setTextSize(12f);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        topRow.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Doxaiz 4.1: быстрая кнопка настроек/отключения уведомлений этого приложения
        FrameLayout muteStackBtn = new FrameLayout(activity);
        muteStackBtn.setBackground(GlassTheme.makeRoundedRect(0x1EFFFFFF, dp(9)));
        muteStackBtn.setPadding(dp(6), dp(3), dp(6), dp(3));
        muteStackBtn.setContentDescription("Отключить уведомления приложения");
        ImageView msIco = VectorGlyphs.makeIconView(activity, "mute", 13, GlassTheme.TEXT_SECONDARY);
        muteStackBtn.addView(msIco, new FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER));
        LinearLayout.LayoutParams msLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msLp.rightMargin = dp(6);
        muteStackBtn.setOnClickListener(v -> {
            performHaptic(v);
            openAppNotificationSettings(pkg);
        });
        topRow.addView(muteStackBtn, msLp);

        // Синий бейдж стопки с числом уведомлений
        LinearLayout stackPill = new LinearLayout(activity);
        stackPill.setOrientation(LinearLayout.HORIZONTAL);
        stackPill.setGravity(Gravity.CENTER_VERTICAL);
        stackPill.setBackground(GlassTheme.makeRoundedRect(0x2E2B8BFF, dp(10)));
        stackPill.setPadding(dp(8), dp(3), dp(8), dp(3));

        ImageView stIco = VectorGlyphs.makeIconView(activity, "stack", 12, 0xFF60A5FA);
        LinearLayout.LayoutParams siLp = new LinearLayout.LayoutParams(dp(12), dp(12));
        siLp.rightMargin = dp(4);
        stackPill.addView(stIco, siLp);

        TextView stCnt = new TextView(activity);
        stCnt.setText(group.size() + " ▾");
        stCnt.setTextColor(0xFF93C5FD);
        stCnt.setTextSize(11f);
        stCnt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        stackPill.addView(stCnt);
        topRow.addView(stackPill);
        card.addView(topRow);

        TextView titleTv = new TextView(activity);
        titleTv.setText(latest.title.isEmpty() ? appName : latest.title);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(14.5f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setPadding(0, dp(5), 0, 0);
        card.addView(titleTv);

        String bodyText = hideText ? "Содержимое скрыто" : latest.text;
        if (!bodyText.isEmpty()) {
            TextView bodyTv = new TextView(activity);
            bodyTv.setText(bodyText);
            bodyTv.setTextColor(0xCCFFFFFF);
            bodyTv.setTextSize(13f);
            bodyTv.setMaxLines(2);
            bodyTv.setEllipsize(TextUtils.TruncateAt.END);
            bodyTv.setPadding(0, dp(2), 0, 0);
            card.addView(bodyTv);
        }

        // Doxaiz 4.5: если в свежем уведомлении стопки есть код подтверждения — кнопка «Копировать» сразу здесь
        if (!hideText && latest.otpCode != null) {
            LinearLayout otpChip = buildOtpCopyChip(latest.otpCode);
            LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            oLp.topMargin = dp(7);
            card.addView(otpChip, oLp);
        }

        // Превью 2-го уведомления в стопке
        if (!hideText && group.size() >= 2) {
            DoxaizNotificationService.NotificationItem second = group.get(1);
            String preview2 = !second.title.isEmpty()
                    ? (second.title + (second.text.isEmpty() ? "" : (": " + second.text)))
                    : second.text;
            if (!preview2.isEmpty()) {
                TextView p2 = new TextView(activity);
                p2.setText("Ещё: " + preview2);
                p2.setTextColor(GlassTheme.TEXT_MUTED);
                p2.setTextSize(11.5f);
                p2.setSingleLine(true);
                p2.setEllipsize(TextUtils.TruncateAt.END);
                p2.setPadding(0, dp(5), 0, 0);
                card.addView(p2);
            }
        }
        wrap.addView(card);

        // Визуальный «подслой» стопки снизу
        View under = new View(activity);
        GradientDrawable uBg = new GradientDrawable();
        uBg.setColor(0xFF1D1D20);
        uBg.setCornerRadii(new float[]{0, 0, 0, 0, dp(14), dp(14), dp(14), dp(14)});
        under.setBackground(uBg);
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        uLp.leftMargin = dp(16);
        uLp.rightMargin = dp(16);
        wrap.addView(under, uLp);

        // Свайп стопки вбок — смахивает всю группу приложения
        final float[] downX = {0f};
        final float[] downY = {0f};
        final boolean[] swiping = {false};
        wrap.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = ev.getRawX();
                    downY[0] = ev.getRawY();
                    swiping[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - downX[0];
                    float dy = ev.getRawY() - downY[0];
                    if (!swiping[0] && Math.abs(dx) > dp(20) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        swiping[0] = true;
                        activeNotifSwiping = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (swiping[0]) {
                        v.setTranslationX(dx);
                        int w = Math.max(1, v.getWidth());
                        v.setAlpha(Math.max(0.18f, 1f - Math.abs(dx) / (w * 0.9f)));
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (swiping[0]) {
                        float dx = v.getTranslationX();
                        int w = Math.max(1, v.getWidth());
                        swiping[0] = false;
                        activeNotifSwiping = false;
                        if (Math.abs(dx) > w * 0.36f) {
                            performHaptic(v);
                            int n = DoxaizNotificationService.dismissPackageNotifications(pkg);
                            recordDismissedBanner("Смахнуто: " + n);
                            rebuildShadeContent();
                        } else {
                            v.animate().translationX(0f).alpha(1f).setDuration(160).start();
                            flushDeferredRefreshIfNeeded();
                        }
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });

        wrap.setOnClickListener(v -> {
            performHaptic(v);
            expandedGroupPackages.add(pkg);
            rebuildShadeContent();
        });
        return wrap;
    }

    private void showNotificationHistoryDialog() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final Dialog dlg = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        trackChildDialog(dlg);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(GlassTheme.makeRoundedRect(0xFF000000, 0));
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(0xFF000000);
            w.setNavigationBarColor(0xFF000000);
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
        }

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        int topInset = Math.max(dp(26), readSystemBarDimen("status_bar_height") + dp(6));
        int botInset = Math.max(dp(16), readSystemBarDimen("navigation_bar_height") + dp(6));
        root.setPadding(dp(16), topInset, dp(16), botInset);

        LinearLayout hdr = new LinearLayout(activity);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(0, 0, 0, dp(14));

        FrameLayout backBtn = new FrameLayout(activity);
        backBtn.setBackground(GlassTheme.makeCircle(0xFF1A1A1E));
        ImageView bIco = VectorGlyphs.makeIconView(activity, "close", 15, Color.WHITE);
        FrameLayout.LayoutParams biLp = new FrameLayout.LayoutParams(dp(15), dp(15));
        biLp.gravity = Gravity.CENTER;
        backBtn.addView(bIco, biLp);
        backBtn.setOnClickListener(v -> dlg.dismiss());
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        bbLp.rightMargin = dp(12);
        hdr.addView(backBtn, bbLp);

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleTv = new TextView(activity);
        titleTv.setText("История уведомлений");
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(19f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleCol.addView(titleTv);

        final List<DoxaizNotificationService.HistoryItem> hist = DoxaizNotificationService.snapshotHistory();
        final TextView subTv = new TextView(activity);
        subTv.setText("Последние смахнутые (" + hist.size() + " / 100) • локально в ОЗУ");
        subTv.setTextColor(GlassTheme.TEXT_SECONDARY);
        subTv.setTextSize(11.5f);
        titleCol.addView(subTv);
        hdr.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!hist.isEmpty()) {
            TextView clearBtn = new TextView(activity);
            clearBtn.setText("Очистить");
            clearBtn.setTextColor(0xFFFCA5A5);
            clearBtn.setTextSize(12.5f);
            clearBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            clearBtn.setBackground(GlassTheme.makeRoundedRect(0x28EF4444, dp(12)));
            clearBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
            clearBtn.setOnClickListener(v -> {
                performHaptic(v);
                DoxaizNotificationService.clearHistory();
                host.showToast("История уведомлений очищена");
                dlg.dismiss();
            });
            hdr.addView(clearBtn);
        }
        root.addView(hdr);

        // Doxaiz 4.5: строка мгновенного поиска по локальной истории уведомлений
        final EditText searchInput = new EditText(activity);
        final ImageView clearSearchBtn = VectorGlyphs.makeIconView(activity, "close", 15, GlassTheme.TEXT_SECONDARY);
        if (!hist.isEmpty()) {
            LinearLayout searchBar = new LinearLayout(activity);
            searchBar.setOrientation(LinearLayout.HORIZONTAL);
            searchBar.setGravity(Gravity.CENTER_VERTICAL);
            searchBar.setBackground(GlassTheme.makeRoundedRect(0xFF1A1A1E, dp(16)));
            searchBar.setPadding(dp(12), dp(4), dp(10), dp(4));
            LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sbLp.bottomMargin = dp(10);

            ImageView sIco = VectorGlyphs.makeIconView(activity, "search", 16, GlassTheme.TEXT_SECONDARY);
            LinearLayout.LayoutParams siLp = new LinearLayout.LayoutParams(dp(16), dp(16));
            siLp.rightMargin = dp(8);
            searchBar.addView(sIco, siLp);

            searchInput.setBackground(null);
            searchInput.setHint("Поиск по истории, кодам, приложениям...");
            searchInput.setHintTextColor(0x78FFFFFF);
            searchInput.setTextColor(Color.WHITE);
            searchInput.setTextSize(13.5f);
            searchInput.setSingleLine(true);
            searchInput.setPadding(0, dp(6), dp(6), dp(6));
            searchBar.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            clearSearchBtn.setVisibility(View.GONE);
            clearSearchBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
            clearSearchBtn.setOnClickListener(v -> searchInput.setText(""));
            searchBar.addView(clearSearchBtn, new LinearLayout.LayoutParams(dp(28), dp(28)));

            root.addView(searchBar, sbLp);
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout listBox = new LinearLayout(activity);
        listBox.setOrientation(LinearLayout.VERTICAL);

        final boolean hideText = host.getPrefs().isHideNotifTextInShade();
        final Runnable renderHistory = () -> {
            listBox.removeAllViews();
            String q = searchInput.getText() != null ? searchInput.getText().toString() : "";
            clearSearchBtn.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);

            if (hist.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText("История пока пуста.\nСмахнутые уведомления автоматически сохраняются здесь, пока активна служба Doxaiz.");
                empty.setTextColor(GlassTheme.TEXT_MUTED);
                empty.setTextSize(13.5f);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(24), dp(80), dp(24), dp(80));
                listBox.addView(empty);
                return;
            }

            int shown = 0;
            for (final DoxaizNotificationService.HistoryItem hi : hist) {
                final String appLbl = loadAppLabelCached(hi.packageName);
                final String visibleText = hideText ? "" : hi.text;
                if (!NotificationTools.matchesHistoryQuery(appLbl, hi.title, visibleText, q)) {
                    continue;
                }
                shown++;
                LinearLayout card = new LinearLayout(activity);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(GlassTheme.makeRoundedRect(0xFF1A1A1E, dp(18)));
                card.setPadding(dp(14), dp(11), dp(14), dp(11));
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cLp.bottomMargin = dp(8);
                card.setLayoutParams(cLp);

                LinearLayout top = new LinearLayout(activity);
                top.setOrientation(LinearLayout.HORIZONTAL);
                top.setGravity(Gravity.CENTER_VERTICAL);
                Drawable ico = loadAppIconCached(hi.packageName);
                if (ico != null) {
                    ImageView iv = new ImageView(activity);
                    iv.setImageDrawable(ico);
                    LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(18), dp(18));
                    iLp.rightMargin = dp(8);
                    top.addView(iv, iLp);
                }
                TextView m = new TextView(activity);
                m.setText(appLbl + " • " + formatTime(new Date(hi.removedTime)));
                m.setTextColor(GlassTheme.TEXT_SECONDARY);
                m.setTextSize(11.5f);
                m.setSingleLine(true);
                m.setEllipsize(TextUtils.TruncateAt.END);
                top.addView(m, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                // Кнопка копирования текста/кода в 1 тап
                if (!hideText) {
                    final String copyPayload = (hi.otpCode != null)
                            ? hi.otpCode
                            : (!hi.text.isEmpty() ? hi.text : hi.title);
                    if (copyPayload != null && !copyPayload.isEmpty()) {
                        LinearLayout cpBtn = new LinearLayout(activity);
                        cpBtn.setOrientation(LinearLayout.HORIZONTAL);
                        cpBtn.setGravity(Gravity.CENTER_VERTICAL);
                        boolean isOtp = (hi.otpCode != null);
                        cpBtn.setBackground(GlassTheme.makePill(isOtp ? 0x3322C55E : 0xFF26262C));
                        cpBtn.setPadding(dp(8), dp(3), dp(9), dp(3));

                        ImageView cpIco = VectorGlyphs.makeIconView(
                                activity, "copy", 12, isOtp ? GlassTheme.ACCENT_GREEN : GlassTheme.TEXT_SECONDARY);
                        LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(dp(12), dp(12));
                        ciLp.rightMargin = dp(4);
                        cpBtn.addView(cpIco, ciLp);

                        TextView cpTxt = new TextView(activity);
                        cpTxt.setText(isOtp ? hi.otpCode : "Копир.");
                        cpTxt.setTextColor(isOtp ? GlassTheme.ACCENT_GREEN : GlassTheme.TEXT_SECONDARY);
                        cpTxt.setTextSize(11f);
                        cpTxt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                        cpBtn.addView(cpTxt);

                        cpBtn.setOnClickListener(v -> {
                            performHaptic(v);
                            copyToClipboard("Уведомление", copyPayload,
                                    isOtp ? ("Код " + copyPayload + " скопирован") : "Текст скопирован", isOtp);
                        });
                        top.addView(cpBtn);
                    }
                }
                card.addView(top);

                if (!hi.title.isEmpty()) {
                    TextView t = new TextView(activity);
                    t.setText(hi.title);
                    t.setTextColor(Color.WHITE);
                    t.setTextSize(14f);
                    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    t.setPadding(0, dp(4), 0, 0);
                    card.addView(t);
                }
                String txt = hideText ? "Содержимое скрыто" : hi.text;
                if (!txt.isEmpty()) {
                    TextView b = new TextView(activity);
                    b.setText(txt);
                    b.setTextColor(0xCCFFFFFF);
                    b.setTextSize(12.5f);
                    b.setMaxLines(3);
                    b.setEllipsize(TextUtils.TruncateAt.END);
                    b.setPadding(0, dp(2), 0, 0);
                    card.addView(b);
                }
                card.setOnClickListener(v -> {
                    try {
                        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(hi.packageName);
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            dlg.dismiss();
                            dismiss();
                            activity.startActivity(launch);
                        }
                    } catch (Throwable ignored) {
                    }
                });
                listBox.addView(card);
            }

            if (shown == 0) {
                TextView noRes = new TextView(activity);
                noRes.setText("Ничего не найдено по запросу «" + q.trim() + "»");
                noRes.setTextColor(GlassTheme.TEXT_MUTED);
                noRes.setTextSize(13.5f);
                noRes.setGravity(Gravity.CENTER);
                noRes.setPadding(dp(24), dp(48), dp(24), dp(48));
                listBox.addView(noRes);
            }
            subTv.setText(q.trim().isEmpty()
                    ? ("Последние смахнутые (" + hist.size() + " / 100) • локально в ОЗУ")
                    : ("Найдено " + shown + " из " + hist.size() + " • локально в ОЗУ"));
        };

        renderHistory.run();
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                renderHistory.run();
            }
        });

        scroll.addView(listBox);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        dlg.setContentView(root);
        safeShowDialog(dlg);
    }

    private View buildSilentSection(
            List<DoxaizNotificationService.NotificationItem> silentItems,
            boolean hideText
    ) {
        LinearLayout sec = new LinearLayout(activity);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setPadding(0, dp(10), 0, dp(4));

        LinearLayout hdr = new LinearLayout(activity);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(4), dp(4), dp(4), dp(8));

        TextView t = new TextView(activity);
        t.setText("Без звука (" + silentItems.size() + ")");
        t.setTextColor(GlassTheme.TEXT_SECONDARY);
        t.setTextSize(13f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hdr.addView(t);

        ImageView chev = VectorGlyphs.makeIconView(
                activity, silentExpanded ? "chevron_up" : "chevron_down", 16, GlassTheme.TEXT_SECONDARY);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        cLp.leftMargin = dp(6);
        hdr.addView(chev, cLp);

        hdr.setOnClickListener(v -> {
            performHaptic(v);
            silentExpanded = !silentExpanded;
            rebuildShadeContent();
        });
        sec.addView(hdr);

        if (silentExpanded) {
            for (DoxaizNotificationService.NotificationItem item : silentItems) {
                sec.addView(buildRealNotifCard(item, hideText, true));
            }
        }
        return sec;
    }

    private LinearLayout buildOtpCopyChip(final String otpCode) {
        LinearLayout chip = new LinearLayout(activity);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(GlassTheme.makePill(GlassTheme.TILE_ACTIVE_GREEN));
        chip.setPadding(dp(11), dp(5), dp(12), dp(5));

        ImageView ico = VectorGlyphs.makeIconView(activity, "copy", 13, Color.WHITE);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(13), dp(13));
        iLp.rightMargin = dp(5);
        chip.addView(ico, iLp);

        TextView tv = new TextView(activity);
        tv.setText("Копировать " + otpCode);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.addView(tv);

        chip.setOnClickListener(v -> {
            performHaptic(v);
            copyToClipboard("Код подтверждения", otpCode, "Код " + otpCode + " скопирован", true);
        });
        return chip;
    }

    private View buildNotifAvatarBadge(DoxaizNotificationService.NotificationItem item) {
        Drawable appIcon = loadAppIconCached(item.packageName);
        Bitmap avatar = item.senderAvatar;
        if (avatar != null && !avatar.isRecycled()) {
            FrameLayout box = new FrameLayout(activity);
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(dp(34), dp(34));
            bLp.rightMargin = dp(10);
            box.setLayoutParams(bLp);

            ImageView av = new ImageView(activity);
            av.setImageBitmap(avatar);
            av.setScaleType(ImageView.ScaleType.CENTER_CROP);
            av.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            av.setClipToOutline(true);
            box.addView(av, new FrameLayout.LayoutParams(dp(32), dp(32)));

            if (appIcon != null) {
                ImageView badge = new ImageView(activity);
                badge.setImageDrawable(appIcon);
                badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
                badge.setBackground(GlassTheme.makeCircle(0xFF1F1F24));
                badge.setPadding(dp(1), dp(1), dp(1), dp(1));
                FrameLayout.LayoutParams bgLp = new FrameLayout.LayoutParams(dp(14), dp(14));
                bgLp.gravity = Gravity.BOTTOM | Gravity.END;
                box.addView(badge, bgLp);
            }
            return box;
        }
        if (appIcon != null) {
            ImageView iv = new ImageView(activity);
            iv.setImageDrawable(appIcon);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(22), dp(22));
            iLp.rightMargin = dp(9);
            iv.setLayoutParams(iLp);
            return iv;
        }
        return null;
    }

    private View buildRealNotifCard(
            final DoxaizNotificationService.NotificationItem item,
            boolean hideText,
            boolean isSilent
    ) {
        final boolean isExpanded = expandedNotifKeys.contains(item.key);

        // Обёртка для визуальной красной подложки с корзиной при свайпе
        FrameLayout swipeWrap = new FrameLayout(activity);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.bottomMargin = dp(8);
        swipeWrap.setLayoutParams(swLp);

        final FrameLayout dismissBackdrop = new FrameLayout(activity);
        if (item.clearable) {
            dismissBackdrop.setBackground(GlassTheme.makeRoundedRect(0xFF991B1B, dp(20)));
            dismissBackdrop.setAlpha(0f);
            ImageView leftTrash = VectorGlyphs.makeIconView(activity, "trash", 18, Color.WHITE);
            FrameLayout.LayoutParams ltLp = new FrameLayout.LayoutParams(dp(18), dp(18));
            ltLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            ltLp.leftMargin = dp(20);
            dismissBackdrop.addView(leftTrash, ltLp);

            ImageView rightTrash = VectorGlyphs.makeIconView(activity, "trash", 18, Color.WHITE);
            FrameLayout.LayoutParams rtLp = new FrameLayout.LayoutParams(dp(18), dp(18));
            rtLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            rtLp.rightMargin = dp(20);
            dismissBackdrop.addView(rightTrash, rtLp);

            swipeWrap.addView(dismissBackdrop, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        final LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GlassTheme.makeRoundedRect(
                isSilent ? 0xFF1E1E22 : GlassTheme.NOTIF_CARD, dp(20)));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        View avatarOrIcon = buildNotifAvatarBadge(item);
        if (avatarOrIcon != null) {
            topRow.addView(avatarOrIcon);
        }

        String appName = loadAppLabelCached(item.packageName);
        String relTime = NotificationTools.formatRelativeTime(
                item.postTime, System.currentTimeMillis(), formatTime(new Date(item.postTime)));
        String metaStr = appName
                + (!item.subText.isEmpty() ? (" • " + item.subText) : "")
                + " • " + relTime;

        LinearLayout headerCol = new LinearLayout(activity);
        headerCol.setOrientation(LinearLayout.VERTICAL);

        TextView meta = new TextView(activity);
        meta.setText(metaStr);
        meta.setTextColor(GlassTheme.TEXT_SECONDARY);
        meta.setTextSize(11.8f);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        headerCol.addView(meta);

        TextView titleTv = new TextView(activity);
        titleTv.setText(item.title.isEmpty() ? appName : item.title);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(14.5f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleTv.setSingleLine(!isExpanded);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setPadding(0, dp(2), 0, 0);
        headerCol.addView(titleTv);

        topRow.addView(headerCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout expWrap = new FrameLayout(activity);
        expWrap.setPadding(dp(6), dp(4), dp(2), dp(4));
        ImageView expIc = VectorGlyphs.makeIconView(
                activity, isExpanded ? "chevron_up" : "chevron_down", 16, GlassTheme.TEXT_SECONDARY);
        expWrap.addView(expIc, new FrameLayout.LayoutParams(dp(16), dp(16)));
        expWrap.setOnClickListener(v -> {
            performHaptic(v);
            if (isExpanded) expandedNotifKeys.remove(item.key);
            else expandedNotifKeys.add(item.key);
            rebuildShadeContent();
        });
        topRow.addView(expWrap);
        card.addView(topRow);

        // Текст уведомления ИЛИ многострочная переписка MessagingStyle/InboxStyle при раскрытии
        if (!hideText && isExpanded && item.messageLines.size() >= 2) {
            LinearLayout chatBox = new LinearLayout(activity);
            chatBox.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cbLp.topMargin = dp(6);

            View accentBar = new View(activity);
            accentBar.setBackground(GlassTheme.makeRoundedRect(0x992B8BFF, dp(2)));
            LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT);
            abLp.rightMargin = dp(9);
            chatBox.addView(accentBar, abLp);

            LinearLayout linesCol = new LinearLayout(activity);
            linesCol.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < item.messageLines.size(); i++) {
                TextView lineTv = new TextView(activity);
                lineTv.setText(item.messageLines.get(i));
                lineTv.setTextColor(i == item.messageLines.size() - 1 ? 0xEEFFFFFF : 0xB8FFFFFF);
                lineTv.setTextSize(12.8f);
                lineTv.setMaxLines(2);
                lineTv.setEllipsize(TextUtils.TruncateAt.END);
                if (i > 0) lineTv.setPadding(0, dp(3), 0, 0);
                linesCol.addView(lineTv);
            }
            chatBox.addView(linesCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(chatBox, cbLp);
        } else {
            String bodyText = hideText ? "Содержимое скрыто" : item.text;
            if (!bodyText.isEmpty()) {
                TextView bodyTv = new TextView(activity);
                bodyTv.setText(bodyText);
                bodyTv.setTextColor(0xCCFFFFFF);
                bodyTv.setTextSize(13f);
                bodyTv.setMaxLines(isExpanded ? 6 : 2);
                bodyTv.setEllipsize(TextUtils.TruncateAt.END);
                bodyTv.setPadding(0, dp(5), 0, 0);
                card.addView(bodyTv);
            }
        }

        // Doxaiz 4.5: живая полоса прогресса загрузок / обновлений
        if (item.hasProgress()) {
            LinearLayout progRow = new LinearLayout(activity);
            progRow.setOrientation(LinearLayout.HORIZONTAL);
            progRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            prLp.topMargin = dp(7);

            ProgressBar pb = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            pb.setIndeterminate(item.progressIndeterminate);
            if (!item.progressIndeterminate && item.progressMax > 0) {
                pb.setMax(item.progressMax);
                pb.setProgress(item.progress);
            }
            try {
                pb.setProgressTintList(android.content.res.ColorStateList.valueOf(GlassTheme.TILE_ACTIVE_BLUE));
                pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF34363F));
                pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(GlassTheme.TILE_ACTIVE_BLUE));
            } catch (Throwable ignored) {}
            progRow.addView(pb, new LinearLayout.LayoutParams(0, dp(5), 1f));

            if (!item.progressIndeterminate && item.progressMax > 0) {
                int pct = Math.min(100, Math.max(0, (item.progress * 100) / item.progressMax));
                TextView pctTv = new TextView(activity);
                pctTv.setText(pct + "%");
                pctTv.setTextColor(0xFF93C5FD);
                pctTv.setTextSize(11f);
                pctTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                pctTv.setPadding(dp(8), 0, 0, 0);
                progRow.addView(pctTv);
            }
            card.addView(progRow, prLp);
        }

        // Doxaiz 4.5: превью присланного фото (BigPictureStyle) при раскрытии карточки
        if (!hideText && isExpanded && item.bigPicture != null && !item.bigPicture.isRecycled()) {
            ImageView picIv = new ImageView(activity);
            picIv.setImageBitmap(item.bigPicture);
            picIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picIv.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
                }
            });
            picIv.setClipToOutline(true);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(156));
            pLp.topMargin = dp(8);
            card.addView(picIv, pLp);
        }

        // Контейнер для встроенного поля быстрого ответа (доступен СРАЗУ при нажатии «Ответить», даже на свёрнутой карточке!)
        final LinearLayout inlineReplyBox = new LinearLayout(activity);
        inlineReplyBox.setOrientation(LinearLayout.VERTICAL);
        inlineReplyBox.setVisibility(View.GONE);
        inlineReplyBox.setPadding(dp(10), dp(7), dp(8), dp(7));
        inlineReplyBox.setBackground(GlassTheme.makeRoundedRect(0xFF17181D, dp(14), 0x2E2B8BFF, dp(1)));
        LinearLayout.LayoutParams irLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        irLp.topMargin = dp(8);
        inlineReplyBox.setLayoutParams(irLp);

        // Doxaiz 4.5: прямые быстрые чипы прямо на карточке (OTP «Копировать», «Ответить», действия) без лишнего раскрытия!
        boolean hasQuickRow = (!hideText && item.otpCode != null) || (!hideText && !item.actions.isEmpty());
        if (hasQuickRow) {
            HorizontalScrollView qScroll = new HorizontalScrollView(activity);
            qScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout.LayoutParams qsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qsLp.topMargin = dp(8);

            LinearLayout qRow = new LinearLayout(activity);
            qRow.setOrientation(LinearLayout.HORIZONTAL);
            qRow.setGravity(Gravity.CENTER_VERTICAL);

            if (item.otpCode != null) {
                LinearLayout otpChip = buildOtpCopyChip(item.otpCode);
                LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                oLp.rightMargin = dp(8);
                qRow.addView(otpChip, oLp);
            }

            int maxVisibleActs = isExpanded ? item.actions.size() : Math.min(2, item.actions.size());
            for (int i = 0; i < maxVisibleActs; i++) {
                final DoxaizNotificationService.ActionItem act = item.actions.get(i);
                if (act.isReply()) {
                    LinearLayout replyBtn = new LinearLayout(activity);
                    replyBtn.setOrientation(LinearLayout.HORIZONTAL);
                    replyBtn.setGravity(Gravity.CENTER_VERTICAL);
                    replyBtn.setPadding(dp(10), dp(5), dp(12), dp(5));
                    replyBtn.setBackground(GlassTheme.makePill(0x292B8BFF));
                    ImageView rIco = VectorGlyphs.makeIconView(activity, "reply", 13, 0xFF60A5FA);
                    LinearLayout.LayoutParams riLp = new LinearLayout.LayoutParams(dp(13), dp(13));
                    riLp.rightMargin = dp(5);
                    replyBtn.addView(rIco, riLp);
                    TextView rTxt = new TextView(activity);
                    rTxt.setText(act.title);
                    rTxt.setTextColor(0xFF93C5FD);
                    rTxt.setTextSize(12f);
                    rTxt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    replyBtn.addView(rTxt);
                    LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rbLp.rightMargin = dp(8);
                    replyBtn.setLayoutParams(rbLp);
                    replyBtn.setOnClickListener(v -> {
                        performHaptic(v);
                        openInlineReplyBox(inlineReplyBox, act);
                    });
                    qRow.addView(replyBtn);
                } else {
                    TextView actChip = new TextView(activity);
                    actChip.setText(act.title);
                    actChip.setTextColor(0xFFE5E7EB);
                    actChip.setTextSize(12f);
                    actChip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    actChip.setBackground(GlassTheme.makePill(0xFF2B2C32));
                    actChip.setPadding(dp(11), dp(5), dp(11), dp(5));
                    LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    acLp.rightMargin = dp(8);
                    actChip.setOnClickListener(v -> {
                        performHaptic(v);
                        if (DoxaizNotificationService.sendPendingIntentSafely(activity, act.actionIntent, null)) {
                            host.showToast(act.title);
                        }
                    });
                    qRow.addView(actChip, acLp);
                }
            }
            qScroll.addView(qRow);
            card.addView(qScroll, qsLp);
        }

        // Расширенная панель управления карточкой: Отложить (+15 мин / +1 ч), Убрать, Откл. увед.
        if (isExpanded) {
            HorizontalScrollView ctrlScroll = new HorizontalScrollView(activity);
            ctrlScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            csLp.topMargin = dp(9);

            LinearLayout ctrlRow = new LinearLayout(activity);
            ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
            ctrlRow.setGravity(Gravity.CENTER_VERTICAL);

            if (item.clearable) {
                ctrlRow.addView(makeSmallCardActionChip("timer", "+15 мин", 0xFF93C5FD, () -> {
                    boolean ok = DoxaizNotificationService.snoozeNotificationByKey(item.key, 15L * 60_000L);
                    expandedNotifKeys.remove(item.key);
                    host.showToast(ok ? "Отложено на 15 мин" : "Не удалось отложить");
                    rebuildShadeContent();
                }));
                ctrlRow.addView(makeSmallCardActionChip("timer", "+1 ч", 0xFF93C5FD, () -> {
                    boolean ok = DoxaizNotificationService.snoozeNotificationByKey(item.key, 60L * 60_000L);
                    expandedNotifKeys.remove(item.key);
                    host.showToast(ok ? "Отложено на 1 час" : "Не удалось отложить");
                    rebuildShadeContent();
                }));
                ctrlRow.addView(makeSmallCardActionChip("trash", "Убрать", 0xFFFCA5A5, () -> {
                    DoxaizNotificationService.dismissNotification(item.key);
                    expandedNotifKeys.remove(item.key);
                    recordDismissedBanner("Уведомление смахнуто");
                    rebuildShadeContent();
                }));
            } else {
                ctrlRow.addView(makeSmallCardActionChip("chevron_right", "Открыть", GlassTheme.ACCENT_GREEN,
                        () -> openNotificationItem(item)));
            }

            ctrlRow.addView(makeSmallCardActionChip("mute", "Откл. увед.", GlassTheme.TEXT_SECONDARY,
                    () -> openAppNotificationSettings(item.packageName)));

            ctrlScroll.addView(ctrlRow);
            card.addView(ctrlScroll, csLp);
        }

        card.addView(inlineReplyBox);
        swipeWrap.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Жест Swipe-to-Dismiss вбок с плавной красной подложкой корзины сзади!
        if (item.clearable) {
            final float[] downX = {0f};
            final float[] downY = {0f};
            final boolean[] swiping = {false};
            card.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX[0] = ev.getRawX();
                        downY[0] = ev.getRawY();
                        swiping[0] = false;
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = ev.getRawX() - downX[0];
                        float dy = ev.getRawY() - downY[0];
                        if (!swiping[0] && Math.abs(dx) > dp(18) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                            swiping[0] = true;
                            activeNotifSwiping = true;
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        if (swiping[0]) {
                            v.setTranslationX(dx);
                            int w = Math.max(1, v.getWidth());
                            float frac = Math.min(1f, Math.abs(dx) / (w * 0.42f));
                            dismissBackdrop.setAlpha(frac);
                            v.setAlpha(Math.max(0.24f, 1f - Math.abs(dx) / (w * 0.95f)));
                            return true;
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (swiping[0]) {
                            float dx = v.getTranslationX();
                            int w = Math.max(1, v.getWidth());
                            swiping[0] = false;
                            activeNotifSwiping = false;
                            if (Math.abs(dx) > w * 0.34f) {
                                performHaptic(v);
                                DoxaizNotificationService.dismissNotification(item.key);
                                expandedNotifKeys.remove(item.key);
                                recordDismissedBanner("Уведомление смахнуто");
                                rebuildShadeContent();
                            } else {
                                v.animate().translationX(0f).alpha(1f).setDuration(160).start();
                                dismissBackdrop.animate().alpha(0f).setDuration(160).start();
                                flushDeferredRefreshIfNeeded();
                            }
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            });
        }

        card.setOnClickListener(v -> openNotificationItem(item));
        return swipeWrap;
    }

    private View makeSmallCardActionChip(String glyph, String text, int tintColor, Runnable action) {
        LinearLayout chip = new LinearLayout(activity);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(GlassTheme.makeRoundedRect(0xFF28292F, dp(10)));
        chip.setPadding(dp(9), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(7);
        chip.setLayoutParams(lp);

        if (glyph != null && !glyph.isEmpty()) {
            ImageView iv = VectorGlyphs.makeIconView(activity, glyph, 12, tintColor);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(12), dp(12));
            iLp.rightMargin = dp(5);
            chip.addView(iv, iLp);
        }
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(tintColor);
        tv.setTextSize(11.8f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.addView(tv);

        chip.setOnClickListener(v -> {
            performHaptic(v);
            action.run();
        });
        return chip;
    }

    private void openAppNotificationSettings(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        dismiss();
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Throwable t) {
            try {
                Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                fallback.setData(android.net.Uri.fromParts("package", pkg, null));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(fallback);
            } catch (Throwable ignored) {
                host.showToast("Не удалось открыть настройки уведомлений");
            }
        }
    }

    private void openNotificationItem(DoxaizNotificationService.NotificationItem item) {
        try {
            if (item.contentIntent != null) {
                boolean sent = DoxaizNotificationService.sendPendingIntentSafely(activity, item.contentIntent, null);
                dismiss();
                if (sent) return;
            }
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(item.packageName);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                dismiss();
                activity.startActivity(launch);
            }
        } catch (Throwable ignored) {
        }
    }

    private View buildMediaCard(final DoxaizNotificationService.MediaInfo media) {
        MediaController controller = null;
        PlaybackState pbState = null;
        long durationMs = 0L;
        if (media.sessionToken != null) {
            try {
                controller = new MediaController(activity, media.sessionToken);
                pbState = controller.getPlaybackState();
                MediaMetadata md = controller.getMetadata();
                if (md != null) {
                    durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                }
            } catch (Throwable ignored) {
                controller = null;
            }
        }
        final MediaController finalCtrl = controller;
        final boolean isPlaying = (pbState != null && pbState.getState() == PlaybackState.STATE_PLAYING);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GlassTheme.makeRoundedRect(0xFF1F222B, dp(20)));
        card.setPadding(dp(13), dp(12), dp(13), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        // Верхняя строка: обложка + название/исполнитель + чип вывода звука (Динамик / Bluetooth / Наушники)
        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        if (media.albumArt != null && !media.albumArt.isRecycled()) {
            ImageView art = new ImageView(activity);
            art.setImageBitmap(media.albumArt);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackground(GlassTheme.makeRoundedRect(0xFF2C2E38, dp(12)));
            art.setClipToOutline(true);
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(dp(46), dp(46));
            aLp.rightMargin = dp(11);
            topRow.addView(art, aLp);
        } else {
            FrameLayout icoBox = new FrameLayout(activity);
            icoBox.setBackground(GlassTheme.makeRoundedRect(0xFF2C2E38, dp(12)));
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(dp(46), dp(46));
            aLp.rightMargin = dp(11);
            icoBox.setLayoutParams(aLp);
            ImageView iv = VectorGlyphs.makeIconView(activity, "music", 20, GlassTheme.ACCENT_BLUE);
            FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(dp(20), dp(20));
            iLp.gravity = Gravity.CENTER;
            icoBox.addView(iv, iLp);
            topRow.addView(icoBox);
        }

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(activity);
        t.setText(media.title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(t);

        if (!media.artist.isEmpty()) {
            TextView a = new TextView(activity);
            a.setText(media.artist);
            a.setTextColor(GlassTheme.TEXT_SECONDARY);
            a.setTextSize(12f);
            a.setSingleLine(true);
            a.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(a);
        }
        texts.setOnClickListener(v -> {
            if (media.contentIntent != null) {
                DoxaizNotificationService.sendPendingIntentSafely(activity, media.contentIntent, null);
                dismiss();
            }
        });
        topRow.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Чип вывода звука (Динамик / Bluetooth / Наушники)
        String[] outInfo = readActiveAudioOutput();
        LinearLayout outChip = new LinearLayout(activity);
        outChip.setOrientation(LinearLayout.HORIZONTAL);
        outChip.setGravity(Gravity.CENTER_VERTICAL);
        outChip.setBackground(GlassTheme.makeRoundedRect(0xFF2B2F3B, dp(12)));
        outChip.setPadding(dp(8), dp(5), dp(9), dp(5));
        LinearLayout.LayoutParams ocLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ocLp.leftMargin = dp(8);
        outChip.setLayoutParams(ocLp);

        ImageView outIc = VectorGlyphs.makeIconView(activity, outInfo[0], 13, 0xFF93C5FD);
        LinearLayout.LayoutParams oiLp = new LinearLayout.LayoutParams(dp(13), dp(13));
        oiLp.rightMargin = dp(5);
        outChip.addView(outIc, oiLp);

        TextView outLbl = new TextView(activity);
        outLbl.setText(outInfo[1]);
        outLbl.setTextColor(0xE6FFFFFF);
        outLbl.setTextSize(11f);
        outLbl.setSingleLine(true);
        outLbl.setMaxWidth(dp(86));
        outLbl.setEllipsize(TextUtils.TruncateAt.END);
        outChip.addView(outLbl);

        outChip.setOnClickListener(v -> {
            performHaptic(v);
            showAudioOutputDialog();
        });
        topRow.addView(outChip);
        card.addView(topRow);

        // Реальная полоса перемотки SeekBar + время (если плеер отдаёт MediaSession и длительность)
        if (finalCtrl != null && durationMs > 0L) {
            LinearLayout seekRow = new LinearLayout(activity);
            seekRow.setOrientation(LinearLayout.HORIZONTAL);
            seekRow.setGravity(Gravity.CENTER_VERTICAL);
            seekRow.setPadding(0, dp(10), 0, dp(2));

            long curPos = computeCurrentMediaPos(pbState, durationMs);
            final TextView posTv = new TextView(activity);
            posTv.setText(formatMediaDuration(curPos));
            posTv.setTextColor(GlassTheme.TEXT_SECONDARY);
            posTv.setTextSize(10.5f);
            seekRow.addView(posTv);

            final SeekBar sb = new SeekBar(activity);
            sb.setMax(1000);
            sb.setProgress((int) Math.max(0L, Math.min(1000L, (curPos * 1000L) / durationMs)));
            if (sb.getProgressDrawable() != null) {
                sb.getProgressDrawable().setTint(GlassTheme.TILE_ACTIVE_BLUE);
            }
            if (sb.getThumb() != null) {
                sb.getThumb().setTint(Color.WHITE);
            }
            final long durFinal = durationMs;
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        long target = (durFinal * progress) / 1000L;
                        posTv.setText(formatMediaDuration(target));
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    liveMediaUserSeeking = true;
                    if (seekBar.getParent() != null) {
                        seekBar.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    liveMediaUserSeeking = false;
                    long target = (durFinal * seekBar.getProgress()) / 1000L;
                    try {
                        finalCtrl.getTransportControls().seekTo(target);
                    } catch (Throwable ignored) {}
                }
            });
            LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            sbLp.leftMargin = dp(6);
            sbLp.rightMargin = dp(6);
            seekRow.addView(sb, sbLp);

            TextView durTv = new TextView(activity);
            durTv.setText(formatMediaDuration(durationMs));
            durTv.setTextColor(GlassTheme.TEXT_SECONDARY);
            durTv.setTextSize(10.5f);
            seekRow.addView(durTv);
            card.addView(seekRow);

            activeMediaController = finalCtrl;
            liveMediaSeekBar = sb;
            liveMediaPosTv = posTv;
            liveMediaDurationMs = durationMs;
            if (isPlaying) {
                liveHandler.removeCallbacks(liveMediaTicker);
                liveHandler.postDelayed(liveMediaTicker, 1000L);
            }
        }

        // Нижняя строка кнопок управления плеером (Prev / Play-Pause / Next)
        LinearLayout ctrlRow = new LinearLayout(activity);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow.setGravity(Gravity.CENTER);
        ctrlRow.setPadding(0, dp(9), 0, 0);

        ctrlRow.addView(makeMediaCtrlBtn("prev", false, () -> {
            if (finalCtrl != null) {
                try { finalCtrl.getTransportControls().skipToPrevious(); return; } catch (Throwable ignored) {}
            }
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }));
        ctrlRow.addView(makeMediaCtrlBtn(isPlaying ? "pause" : "play", true, () -> {
            if (finalCtrl != null) {
                try {
                    if (isPlaying) finalCtrl.getTransportControls().pause();
                    else finalCtrl.getTransportControls().play();
                    liveHandler.postDelayed(QuickShadeHelper.this::refreshIfShowing, 180L);
                    return;
                } catch (Throwable ignored) {}
            }
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            liveHandler.postDelayed(QuickShadeHelper.this::refreshIfShowing, 180L);
        }));
        ctrlRow.addView(makeMediaCtrlBtn("next", false, () -> {
            if (finalCtrl != null) {
                try { finalCtrl.getTransportControls().skipToNext(); return; } catch (Throwable ignored) {}
            }
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
        }));
        card.addView(ctrlRow);
        return card;
    }

    private View makeMediaCtrlBtn(String glyph, boolean primary, final Runnable action) {
        FrameLayout b = new FrameLayout(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                primary ? dp(52) : dp(42), dp(34));
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setBackground(GlassTheme.makeRoundedRect(
                primary ? GlassTheme.TILE_ACTIVE_BLUE : 0xFF2E303A, dp(12)));

        ImageView iv = VectorGlyphs.makeIconView(activity, glyph, primary ? 17 : 15, Color.WHITE);
        FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(
                dp(primary ? 17 : 15), dp(primary ? 17 : 15));
        iLp.gravity = Gravity.CENTER;
        b.addView(iv, iLp);

        b.setOnClickListener(v -> {
            performHaptic(v);
            action.run();
        });
        return b;
    }

    private long computeCurrentMediaPos(PlaybackState st, long durationMs) {
        if (st == null) return 0L;
        long pos = Math.max(0L, st.getPosition());
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0L) {
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime());
            float speed = st.getPlaybackSpeed() > 0f ? st.getPlaybackSpeed() : 1f;
            pos += Math.round(elapsed * speed);
        }
        return durationMs > 0L ? Math.min(pos, durationMs) : pos;
    }

    private String formatMediaDuration(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long min = totalSec / 60L;
        long sec = totalSec % 60L;
        return String.format(Locale.US, "%d:%02d", min, sec);
    }

    /**
     * Определяет реальное активное устройство вывода звука через AudioManager.
     * Возвращает пару {glyphName, displayLabel}.
     */
    private String[] readActiveAudioOutput() {
        try {
            AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                if (outs != null) {
                    for (AudioDeviceInfo d : outs) {
                        int type = d.getType();
                        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                            CharSequence name = d.getProductName();
                            String label = (name != null && name.length() > 0) ? name.toString() : "Bluetooth";
                            return new String[]{"bluetooth", label};
                        }
                    }
                    for (AudioDeviceInfo d : outs) {
                        int type = d.getType();
                        if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                                || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                                || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                            return new String[]{"headphones", "Наушники"};
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new String[]{"speaker", "Динамик"};
    }

    private void showAudioOutputDialog() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        List<String> lines = new ArrayList<>();
        try {
            AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                if (outs != null) {
                    for (AudioDeviceInfo d : outs) {
                        int t = d.getType();
                        String label = null;
                        if (t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) label = "Динамик телефона";
                        else if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                            CharSequence pn = d.getProductName();
                            label = "Bluetooth: " + ((pn != null && pn.length() > 0) ? pn : "Аудиоустройство");
                        } else if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                                || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                                || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                            label = "Проводные / USB наушники";
                        }
                        if (label != null && !lines.contains(label)) lines.add(label);
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (lines.isEmpty()) lines.add("Динамик телефона");
        lines.add("Открыть устройства Bluetooth...");
        lines.add("Панель звука (4 канала)");

        final String[] arr = lines.toArray(new String[0]);
        final int btIdx = arr.length - 2;
        final int sndIdx = arr.length - 1;
        Dialog audioDlg = new android.app.AlertDialog.Builder(activity)
                .setTitle("Вывод звука")
                .setItems(arr, (d, which) -> {
                    if (which == btIdx) {
                        dismiss();
                        openSystemAction(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SOUND_SETTINGS);
                    } else if (which == sndIdx) {
                        dismiss();
                        host.openSoundPanel();
                    } else {
                        host.showToast("Активно: " + arr[which]);
                    }
                })
                .create();
        trackChildDialog(audioDlg);
        safeShowDialog(audioDlg);
    }

    private View buildAccessBanner() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GlassTheme.makeStyledSurface(activity, GlassTheme.NOTIF_CARD, 20f, true));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(activity);
        title.setText("Включите доступ к уведомлениям");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);

        TextView desc = new TextView(activity);
        desc.setText("Чтобы видеть реальные уведомления, кнопки действий, обложку плеера и активные фоновые приложения прямо в шторке Doxaiz.");
        desc.setTextColor(GlassTheme.TEXT_SECONDARY);
        desc.setTextSize(12.5f);
        desc.setPadding(0, dp(4), 0, dp(12));
        card.addView(desc);

        TextView btn = new TextView(activity);
        btn.setText("Разрешить доступ");
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13.5f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(GlassTheme.makeRoundedRect(GlassTheme.ACCENT_GREEN, dp(14)));
        btn.setPadding(dp(16), dp(10), dp(16), dp(10));
        btn.setOnClickListener(v -> {
            dismiss();
            DoxaizNotificationService.openSettings(activity);
        });
        card.addView(btn);
        return card;
    }

    private View buildEmptyNotifState() {
        TextView empty = new TextView(activity);
        empty.setText("Нет новых уведомлений");
        empty.setTextColor(GlassTheme.TEXT_MUTED);
        empty.setTextSize(14f);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(80), 0, dp(80));
        return empty;
    }

    private View buildBottomNavBar(int activeTab) {
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(0, dp(4), 0, dp(2));

        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabs.setBackground(GlassTheme.makeRoundedRect(0xFF1A1A1E, dp(18)));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));

        TextView notifTab = makeSwitchTab("Уведомления", activeTab == TAB_NOTIFICATIONS);
        notifTab.setOnClickListener(v -> {
            performHaptic(v);
            if (currentTab != TAB_NOTIFICATIONS) {
                currentTab = TAB_NOTIFICATIONS;
                rebuildShadeContent();
            }
        });
        tabs.addView(notifTab);

        TextView ctrlTab = makeSwitchTab("Управление", activeTab == TAB_CONTROL);
        ctrlTab.setOnClickListener(v -> {
            performHaptic(v);
            if (currentTab != TAB_CONTROL) {
                currentTab = TAB_CONTROL;
                rebuildShadeContent();
            }
        });
        tabs.addView(ctrlTab);
        wrap.addView(tabs);

        View pill = new View(activity);
        pill.setBackground(GlassTheme.makeRoundedRect(0x66FFFFFF, dp(3)));
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(dp(44), dp(5));
        pLp.topMargin = dp(10);
        pill.setLayoutParams(pLp);
        pill.setOnClickListener(v -> dismissAnimated());
        wrap.addView(pill);

        // Плавное интерактивное закрытие шторки свайпом вверх за пальцем (drag-to-dismiss)
        final float[] bDownY = {0f};
        final boolean[] draggingShade = {false};
        wrap.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                bDownY[0] = ev.getRawY();
                draggingShade[0] = false;
                return false;
            } else if (act == MotionEvent.ACTION_MOVE) {
                float dy = ev.getRawY() - bDownY[0];
                if (!draggingShade[0] && dy < -dp(12)) {
                    draggingShade[0] = true;
                }
                if (draggingShade[0] && shadeRoot != null) {
                    float ty = Math.min(0f, dy);
                    shadeRoot.setTranslationY(ty);
                    shadeRoot.setAlpha(Math.max(0.35f, 1f - Math.abs(ty) / dp(360)));
                    return true;
                }
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                if (draggingShade[0] && shadeRoot != null) {
                    draggingShade[0] = false;
                    if (shadeRoot.getTranslationY() < -dp(72)) {
                        performHaptic(v);
                        dismissAnimated();
                    } else {
                        shadeRoot.animate().translationY(0f).alpha(1f).setDuration(140L).withLayer().start();
                    }
                    return true;
                }
            }
            return false;
        });

        return wrap;
    }

    private TextView makeSwitchTab(String text, boolean active) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(active ? Color.WHITE : GlassTheme.TEXT_SECONDARY);
        tv.setTextSize(12.5f);
        tv.setTypeface(Typeface.create(active ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        tv.setPadding(dp(16), dp(7), dp(16), dp(7));
        if (active) {
            tv.setBackground(GlassTheme.makeRoundedRect(0xFF2E2E34, dp(14)));
        }
        return tv;
    }

    // =========================================================================
    // 3. КЛАССИЧЕСКАЯ ОБЪЕДИНЁННАЯ ШТОРКА (чистые векторные круглые плитки 4×3)
    // =========================================================================

    private void buildClassicShade(LinearLayout root, Prefs prefs) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(2), dp(2), dp(10));

        Date now = new Date();
        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView clk = new TextView(activity);
        clk.setText(formatTime(now));
        clk.setTextColor(Color.WHITE);
        clk.setTextSize(28f);
        clk.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        left.addView(clk);

        TextView dt = new TextView(activity);
        dt.setText(formatDate(now));
        dt.setTextColor(GlassTheme.TEXT_SECONDARY);
        dt.setTextSize(12.5f);
        left.addView(dt);
        header.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View histBtn = makeHeaderIconBtn("history", "История уведомлений");
        histBtn.setOnClickListener(v -> {
            performHaptic(v);
            showNotificationHistoryDialog();
        });
        header.addView(histBtn);

        View editBtn = makeHeaderIconBtn("edit", "Изменить плитки");
        editBtn.setOnClickListener(v -> showTileEditorDialog());
        header.addView(editBtn);

        View moreBtn = makeHeaderIconBtn("more", "Меню шторки");
        moreBtn.setOnClickListener(v -> showShadeOverflowMenu());
        header.addView(moreBtn);
        root.addView(header);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tileCard = new LinearLayout(activity);
        tileCard.setOrientation(LinearLayout.VERTICAL);
        tileCard.setBackground(GlassTheme.makeRoundedRect(0xFF1F1F24, dp(24)));
        tileCard.setPadding(dp(12), dp(16), dp(12), dp(12));

        List<String> allIds = getAllEnabledTilesIncludingConn(prefs);
        int perPage = 12;
        int totalPages = Math.max(1, (allIds.size() + perPage - 1) / perPage);
        if (classicPage >= totalPages) classicPage = 0;
        int start = classicPage * perPage;
        int end = Math.min(allIds.size(), start + perPage);

        for (int i = start; i < end; i += 4) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = dp(12);
            row.setLayoutParams(rLp);

            for (int c = 0; c < 4; c++) {
                int idx = i + c;
                if (idx < end) {
                    row.addView(buildClassicCircleTile(allIds.get(idx)),
                            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                } else {
                    row.addView(new View(activity),
                            new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
            tileCard.addView(row);
        }

        if (totalPages > 1) {
            LinearLayout dots = new LinearLayout(activity);
            dots.setOrientation(LinearLayout.HORIZONTAL);
            dots.setGravity(Gravity.CENTER);
            dots.setPadding(0, 0, 0, dp(8));
            for (int p = 0; p < totalPages; p++) {
                final int targetPage = p;
                View dot = new View(activity);
                boolean active = (p == classicPage);
                LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                        active ? dp(14) : dp(6), dp(6));
                dLp.setMargins(dp(4), 0, dp(4), 0);
                dot.setLayoutParams(dLp);
                dot.setBackground(GlassTheme.makeRoundedRect(
                        active ? Color.WHITE : 0x55FFFFFF, dp(3)));
                dot.setOnClickListener(v -> {
                    performHaptic(v);
                    classicPage = targetPage;
                    rebuildShadeContent();
                });
                dots.addView(dot);
            }
            tileCard.addView(dots);
        }

        if (prefs.isShowNetSpeed()) {
            View sep = new View(activity);
            sep.setBackgroundColor(GlassTheme.DIVIDER);
            tileCard.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

            liveTrafficLabel = new TextView(activity);
            liveTrafficLabel.setText(buildNetSpeedLineText());
            liveTrafficLabel.setTextColor(GlassTheme.TEXT_SECONDARY);
            liveTrafficLabel.setTextSize(11.5f);
            liveTrafficLabel.setGravity(Gravity.CENTER);
            liveTrafficLabel.setSingleLine(true);
            liveTrafficLabel.setEllipsize(TextUtils.TruncateAt.END);
            liveTrafficLabel.setPadding(0, dp(10), 0, dp(2));
            liveTrafficLabel.setOnClickListener(v -> {
                dismiss();
                openSystemAction(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_WIFI_SETTINGS);
            });
            tileCard.addView(liveTrafficLabel);
        }

        body.addView(tileCard);
        body.addView(buildClassicBrightnessBar());

        DoxaizNotificationService.MediaInfo cMedia = DoxaizNotificationService.getActiveMediaInfo();
        if (cMedia != null) {
            body.addView(buildMediaCard(cMedia));
        }

        if (!DoxaizNotificationService.isServiceConnected()) {
            body.addView(buildAccessBanner());
        } else {
            List<DoxaizNotificationService.NotificationItem> items = DoxaizNotificationService.snapshotNotifications();
            List<DoxaizNotificationService.NotificationItem> silentItems = DoxaizNotificationService.snapshotSilentNotifications();
            boolean hideText = prefs.isHideNotifTextInShade();

            if (items.isEmpty() && silentItems.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText("Нет новых уведомлений");
                empty.setTextColor(GlassTheme.TEXT_MUTED);
                empty.setTextSize(13.5f);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, dp(28), 0, dp(28));
                body.addView(empty);
            } else {
                // Doxaiz 4.5: заголовок уведомлений в классической шторке + быстрая кнопка «Очистить все»
                if (!items.isEmpty()) {
                    LinearLayout nHdr = new LinearLayout(activity);
                    nHdr.setOrientation(LinearLayout.HORIZONTAL);
                    nHdr.setGravity(Gravity.CENTER_VERTICAL);
                    nHdr.setPadding(dp(4), dp(4), dp(4), dp(8));

                    TextView nCnt = new TextView(activity);
                    nCnt.setText("Уведомления • " + items.size());
                    nCnt.setTextColor(GlassTheme.TEXT_SECONDARY);
                    nCnt.setTextSize(12.5f);
                    nCnt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    nHdr.addView(nCnt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    TextView clearAllBtn = new TextView(activity);
                    clearAllBtn.setText("Очистить все");
                    clearAllBtn.setTextColor(0xFF93C5FD);
                    clearAllBtn.setTextSize(12f);
                    clearAllBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    clearAllBtn.setBackground(GlassTheme.makePill(0xFF1F222C));
                    clearAllBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
                    clearAllBtn.setOnClickListener(v -> {
                        performHaptic(v);
                        int cleared = DoxaizNotificationService.clearAllClearable();
                        if (cleared > 0) recordDismissedBanner("Очищено: " + cleared);
                        rebuildShadeContent();
                    });
                    nHdr.addView(clearAllBtn);
                    body.addView(nHdr);

                    View filterBar = buildNotifFilterBar(items, hideText);
                    if (filterBar != null) {
                        body.addView(filterBar);
                    }
                    List<DoxaizNotificationService.NotificationItem> filtered = applyNotificationFilter(items, hideText);
                    if (filtered.isEmpty()) {
                        notifFilterMode = NotificationTools.FILTER_ALL;
                        notifFilterPackage = "";
                        filtered = items;
                    }
                    addNotificationsWithGrouping(body, filtered, hideText, prefs.isShadeGroupNotifications());
                }
                if (!silentItems.isEmpty() && notifFilterMode == NotificationTools.FILTER_ALL) {
                    body.addView(buildSilentSection(silentItems, hideText));
                }
            }
        }

        scroll.addView(body);
        activeNotifScroll = scroll;
        if (savedNotifScrollY > 0) {
            final int targetY = savedNotifScrollY;
            scroll.post(() -> scroll.scrollTo(0, targetY));
        }
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View undoPill = buildUndoBannerPillIfActive();
        if (undoPill != null) {
            root.addView(undoPill);
        }

        FrameLayout closeZone = new FrameLayout(activity);
        closeZone.setPadding(0, dp(10), 0, dp(4));
        View closePill = new View(activity);
        closePill.setBackground(GlassTheme.makeRoundedRect(0x66FFFFFF, dp(3)));
        FrameLayout.LayoutParams cpLp = new FrameLayout.LayoutParams(dp(44), dp(5));
        cpLp.gravity = Gravity.CENTER;
        closeZone.addView(closePill, cpLp);
        closeZone.setOnClickListener(v -> dismiss());
        final float[] cDownY = {0f};
        closeZone.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) cDownY[0] = ev.getRawY();
            else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE && (cDownY[0] - ev.getRawY() > dp(32))) {
                performHaptic(v);
                dismiss();
                return true;
            }
            return false;
        });
        root.addView(closeZone, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View buildClassicCircleTile(final String tileId) {
        TileSpec spec = ALL_TILES.get(tileId);
        if (spec == null) return new View(activity);
        boolean active = isTileActive(tileId);
        int color = active
                ? ("mobile".equals(tileId) ? GlassTheme.TILE_ACTIVE_GREEN : GlassTheme.TILE_ACTIVE_BLUE)
                : 0xFF2E2E34;

        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(dp(2), 0, dp(2), 0);

        FrameLayout circle = new FrameLayout(activity);
        circle.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        circle.setBackground(GlassTheme.makeCircle(color));

        ImageView iv = VectorGlyphs.makeIconView(activity, resolveTileGlyph(tileId, spec), 21, Color.WHITE);
        FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(dp(21), dp(21));
        iLp.gravity = Gravity.CENTER;
        circle.addView(iv, iLp);
        wrap.addView(circle);

        TextView lbl = new TextView(activity);
        lbl.setText(resolveTileLabel(tileId, spec));
        lbl.setTextColor(0xD9FFFFFF);
        lbl.setTextSize(10.5f);
        lbl.setGravity(Gravity.CENTER);
        lbl.setMaxLines(1);
        lbl.setEllipsize(TextUtils.TruncateAt.END);
        lbl.setPadding(0, dp(5), 0, 0);
        wrap.addView(lbl);

        wrap.setOnClickListener(v -> {
            performHaptic(v);
            handleTileClick(tileId);
        });
        wrap.setOnLongClickListener(v -> {
            performHaptic(v);
            handleTileLongClick(tileId);
            return true;
        });
        return wrap;
    }

    private void openInlineReplyBox(final LinearLayout box, final DoxaizNotificationService.ActionItem act) {
        if (box.getVisibility() == View.VISIBLE) {
            box.setVisibility(View.GONE);
            box.removeAllViews();
            inlineReplyActive = false;
            flushDeferredRefreshIfNeeded();
            return;
        }
        box.removeAllViews();
        box.setVisibility(View.VISIBLE);
        inlineReplyActive = true;

        // Doxaiz 4.5: быстрые ответы в 1 тап (RemoteInput.getChoices()), если мессенджер их передал
        if (!act.quickReplies.isEmpty()) {
            HorizontalScrollView qHsv = new HorizontalScrollView(activity);
            qHsv.setHorizontalScrollBarEnabled(false);
            LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qLp.bottomMargin = dp(7);

            LinearLayout qRow = new LinearLayout(activity);
            qRow.setOrientation(LinearLayout.HORIZONTAL);
            for (final String qr : act.quickReplies) {
                TextView chip = new TextView(activity);
                chip.setText(qr);
                chip.setTextColor(0xFFDBEAFE);
                chip.setTextSize(12f);
                chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                chip.setBackground(GlassTheme.makePill(0xFF252A36));
                chip.setPadding(dp(11), dp(4), dp(11), dp(4));
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cLp.rightMargin = dp(6);
                chip.setOnClickListener(v -> {
                    performHaptic(v);
                    boolean ok = DoxaizNotificationService.sendDirectReply(activity, act, qr);
                    if (ok) {
                        host.showToast("Отправлено: " + qr);
                        box.setVisibility(View.GONE);
                        box.removeAllViews();
                        inlineReplyActive = false;
                        flushDeferredRefreshIfNeeded();
                    } else {
                        host.showToast("Не удалось отправить ответ — откройте чат");
                    }
                });
                qRow.addView(chip, cLp);
            }
            qHsv.addView(qRow);
            box.addView(qHsv, qLp);
        }

        LinearLayout inputRow = new LinearLayout(activity);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        final EditText input = new EditText(activity);
        input.setBackground(null);
        CharSequence hint = (act.remoteInput != null && act.remoteInput.getLabel() != null)
                ? act.remoteInput.getLabel() : "Введите ответ...";
        input.setHint(hint);
        input.setHintTextColor(0x88FFFFFF);
        input.setTextColor(Color.WHITE);
        input.setTextSize(13.5f);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPadding(dp(4), dp(6), dp(6), dp(6));
        input.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Runnable doSend = () -> {
            String msg = input.getText().toString().trim();
            if (msg.isEmpty()) return;
            boolean ok = DoxaizNotificationService.sendDirectReply(activity, act, msg);
            if (ok) {
                host.showToast("Ответ отправлен");
                box.setVisibility(View.GONE);
                box.removeAllViews();
                inlineReplyActive = false;
                try {
                    InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                } catch (Throwable ignored) {}
                flushDeferredRefreshIfNeeded();
            } else {
                host.showToast("Не удалось отправить ответ — откройте чат");
            }
        };

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                doSend.run();
                return true;
            }
            return false;
        });
        inputRow.addView(input);

        FrameLayout sendBtn = new FrameLayout(activity);
        sendBtn.setBackground(GlassTheme.makeCircle(GlassTheme.TILE_ACTIVE_BLUE));
        ImageView sIco = VectorGlyphs.makeIconView(activity, "send", 16, Color.WHITE);
        FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(dp(16), dp(16));
        sLp.gravity = Gravity.CENTER;
        sendBtn.addView(sIco, sLp);
        sendBtn.setOnClickListener(v -> {
            performHaptic(v);
            doSend.run();
        });
        inputRow.addView(sendBtn, new LinearLayout.LayoutParams(dp(34), dp(34)));

        box.addView(inputRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        input.requestFocus();
        input.post(() -> {
            try {
                InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            } catch (Throwable ignored) {}
        });
    }

    private View buildClassicBrightnessBar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(GlassTheme.makeRoundedRect(0xFF1F1F24, dp(22)));
        bar.setPadding(dp(14), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        bar.setLayoutParams(lp);

        ImageView sun = VectorGlyphs.makeIconView(activity, "sun", 20, GlassTheme.ACCENT_AMBER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        sLp.rightMargin = dp(8);
        bar.addView(sun, sLp);

        SeekBar sb = new SeekBar(activity);
        sb.setMax(100);
        sb.setProgress(Math.round(readBrightnessFraction() * 100));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) applyBrightnessFraction(Math.max(0.05f, progress / 100f));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        bar.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (hasHardwareLightSensor()) {
            boolean autoBright = isAutoBrightnessOn();
            TextView autoBtn = new TextView(activity);
            autoBtn.setText("A");
            autoBtn.setTextColor(Color.WHITE);
            autoBtn.setTextSize(13f);
            autoBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            autoBtn.setGravity(Gravity.CENTER);
            autoBtn.setBackground(GlassTheme.makeCircle(autoBright ? GlassTheme.ACCENT_BLUE : 0xFF323238));
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(dp(30), dp(30));
            aLp.leftMargin = dp(8);
            autoBtn.setLayoutParams(aLp);
            autoBtn.setOnClickListener(v -> {
                performHaptic(v);
                toggleAutoBrightness();
            });
            bar.addView(autoBtn);
        }

        return bar;
    }

    // =========================================================================
    // 4. РЕДАКТОР ПЛИТОК «ИЗМЕНИТЬ» (− / + и перестановка порядка ◀ / ▶)
    // =========================================================================

    public void showTileEditorDialog() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final Prefs prefs = host.getPrefs();
        final List<String> activeList = new ArrayList<>(getGridTileIds(prefs));
        final String[] selectedId = {null};

        final Dialog dlg = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        trackChildDialog(dlg);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(GlassTheme.makeRoundedRect(0xFF000000, 0));
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
        }

        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(dp(16), dp(24), dp(16), dp(18));

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, dp(8));

        TextView title = new TextView(activity);
        title.setText("Изменить");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout closeX = new FrameLayout(activity);
        closeX.setPadding(dp(8), dp(8), dp(8), dp(8));
        ImageView closeIv = VectorGlyphs.makeIconView(activity, "close", 18, GlassTheme.TEXT_SECONDARY);
        FrameLayout.LayoutParams cxLp = new FrameLayout.LayoutParams(dp(18), dp(18));
        cxLp.gravity = Gravity.CENTER;
        closeX.addView(closeIv, cxLp);
        closeX.setOnClickListener(v -> dlg.dismiss());
        top.addView(closeX, new LinearLayout.LayoutParams(dp(36), dp(36)));
        root.addView(top);

        TextView sub = new TextView(activity);
        sub.setText("Нажмите на плитку для перемещения (◀ / ▶), красный «−» убирает, зелёный «+» добавляет");
        sub.setTextColor(GlassTheme.TEXT_SECONDARY);
        sub.setTextSize(12f);
        sub.setPadding(0, 0, 0, dp(10));
        root.addView(sub);

        // Панель перестановки выбранной плитки (◀ Влево | Вправо ▶)
        final LinearLayout reorderBar = new LinearLayout(activity);
        reorderBar.setOrientation(LinearLayout.HORIZONTAL);
        reorderBar.setGravity(Gravity.CENTER_VERTICAL);
        reorderBar.setBackground(GlassTheme.makeRoundedRect(0xFF1A1A1F, dp(14)));
        reorderBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams rbWrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rbWrapLp.bottomMargin = dp(10);
        reorderBar.setLayoutParams(rbWrapLp);
        reorderBar.setVisibility(View.GONE);
        root.addView(reorderBar);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            content.removeAllViews();
            reorderBar.removeAllViews();

            if (selectedId[0] != null && activeList.contains(selectedId[0])) {
                final int pos = activeList.indexOf(selectedId[0]);
                TileSpec sp = ALL_TILES.get(selectedId[0]);
                reorderBar.setVisibility(View.VISIBLE);

                TextView selTxt = new TextView(activity);
                selTxt.setText(sp != null ? sp.label : selectedId[0]);
                selTxt.setTextColor(Color.WHITE);
                selTxt.setTextSize(13f);
                selTxt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                reorderBar.addView(selTxt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView leftBtn = makeReorderBtn("◀ Влево", pos > 0);
                leftBtn.setOnClickListener(v -> {
                    if (pos > 0) {
                        performHaptic(v);
                        String id = activeList.remove(pos);
                        activeList.add(pos - 1, id);
                        rebuild[0].run();
                    }
                });
                reorderBar.addView(leftBtn);

                TextView rightBtn = makeReorderBtn("Вправо ▶", pos < activeList.size() - 1);
                rightBtn.setOnClickListener(v -> {
                    if (pos < activeList.size() - 1) {
                        performHaptic(v);
                        String id = activeList.remove(pos);
                        activeList.add(pos + 1, id);
                        rebuild[0].run();
                    }
                });
                reorderBar.addView(rightBtn);
            } else {
                reorderBar.setVisibility(View.GONE);
            }

            TextView actHdr = new TextView(activity);
            actHdr.setText("На панели управления (" + activeList.size() + ")");
            actHdr.setTextColor(0xCCFFFFFF);
            actHdr.setTextSize(13f);
            actHdr.setPadding(0, 0, 0, dp(10));
            content.addView(actHdr);

            content.addView(buildEditorGrid(activeList, true, selectedId[0],
                    id -> {
                        if (activeList.size() <= 4) {
                            host.showToast("Минимум 4 плитки на панели");
                            return;
                        }
                        activeList.remove(id);
                        if (id.equals(selectedId[0])) selectedId[0] = null;
                        rebuild[0].run();
                    },
                    id -> {
                        selectedId[0] = id.equals(selectedId[0]) ? null : id;
                        rebuild[0].run();
                    }));

            View div = new View(activity);
            div.setBackgroundColor(GlassTheme.DIVIDER);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dLp.setMargins(0, dp(14), 0, dp(14));
            div.setLayoutParams(dLp);
            content.addView(div);

            List<String> avail = new ArrayList<>();
            for (String id : ALL_TILES.keySet()) {
                if ("wifi".equals(id) || "mobile".equals(id)) continue;
                if (!activeList.contains(id)) avail.add(id);
            }

            TextView avHdr = new TextView(activity);
            avHdr.setText("Доступные переключатели (" + avail.size() + ")");
            avHdr.setTextColor(0xCCFFFFFF);
            avHdr.setTextSize(13f);
            avHdr.setPadding(0, 0, 0, dp(10));
            content.addView(avHdr);

            if (avail.isEmpty()) {
                TextView none = new TextView(activity);
                none.setText("Все доступные плитки уже добавлены");
                none.setTextColor(GlassTheme.TEXT_MUTED);
                none.setTextSize(12.5f);
                none.setPadding(0, dp(8), 0, dp(16));
                content.addView(none);
            } else {
                content.addView(buildEditorGrid(avail, false, null,
                        id -> {
                            activeList.add(id);
                            selectedId[0] = id;
                            rebuild[0].run();
                        },
                        id -> {
                            activeList.add(id);
                            selectedId[0] = id;
                            rebuild[0].run();
                        }));
            }
        };
        rebuild[0].run();

        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, dp(12), 0, 0);

        TextView resetBtn = new TextView(activity);
        resetBtn.setText("Сбросить");
        resetBtn.setTextColor(Color.WHITE);
        resetBtn.setTextSize(14.5f);
        resetBtn.setGravity(Gravity.CENTER);
        resetBtn.setBackground(GlassTheme.makeRoundedRect(0xFF242427, dp(18)));
        resetBtn.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rbLp.rightMargin = dp(10);
        resetBtn.setLayoutParams(rbLp);
        resetBtn.setOnClickListener(v -> {
            performHaptic(v);
            activeList.clear();
            selectedId[0] = null;
            for (String s : Prefs.DEFAULT_SHADE_TILES.split(",")) {
                String id = s.trim();
                if (!"wifi".equals(id) && !"mobile".equals(id) && ALL_TILES.containsKey(id)) {
                    activeList.add(id);
                }
            }
            rebuild[0].run();
        });
        footer.addView(resetBtn);

        TextView doneBtn = new TextView(activity);
        doneBtn.setText("Готово");
        doneBtn.setTextColor(Color.WHITE);
        doneBtn.setTextSize(14.5f);
        doneBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        doneBtn.setGravity(Gravity.CENTER);
        doneBtn.setBackground(GlassTheme.makeRoundedRect(GlassTheme.ACCENT_GREEN, dp(18)));
        doneBtn.setPadding(0, dp(12), 0, dp(12));
        doneBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        doneBtn.setOnClickListener(v -> {
            performHaptic(v);
            StringBuilder sb = new StringBuilder("wifi,mobile");
            for (String id : activeList) sb.append(',').append(id);
            prefs.setShadeTilesCsv(sb.toString());
            dlg.dismiss();
            rebuildShadeContent();
            host.showToast("Панель управления сохранена");
        });
        footer.addView(doneBtn);
        root.addView(footer);

        dlg.setContentView(root);
        safeShowDialog(dlg);
    }

    private TextView makeReorderBtn(String label, boolean enabled) {
        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(enabled ? Color.WHITE : 0x55FFFFFF);
        tv.setTextSize(12f);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackground(GlassTheme.makeRoundedRect(enabled ? GlassTheme.TILE_ACTIVE_BLUE : GlassTheme.TILE_SUB_BG, dp(10)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private interface TileIdCallback {
        void onPick(String id);
    }

    private View buildEditorGrid(
            List<String> ids, boolean isRemoveMode, String selectedId,
            TileIdCallback onBadgeClick, TileIdCallback onTileClick
    ) {
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < ids.size(); i += 4) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = dp(12);
            row.setLayoutParams(rLp);

            for (int c = 0; c < 4; c++) {
                int idx = i + c;
                if (idx < ids.size()) {
                    final String id = ids.get(idx);
                    TileSpec spec = ALL_TILES.get(id);
                    if (spec == null) continue;
                    boolean isSel = id.equals(selectedId);

                    LinearLayout cell = new LinearLayout(activity);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);

                    FrameLayout tileFrame = new FrameLayout(activity);
                    tileFrame.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

                    FrameLayout squircle = new FrameLayout(activity);
                    FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
                    sLp.topMargin = dp(6);
                    sLp.rightMargin = dp(4);
                    squircle.setLayoutParams(sLp);
                    GradientDrawable sqBg = GlassTheme.makeRoundedRect(
                            isSel ? 0xFF233856 : GlassTheme.TILE_INACTIVE, dp(16));
                    if (isSel) sqBg.setStroke(dp(2), GlassTheme.ACCENT_BLUE);
                    squircle.setBackground(sqBg);

                    ImageView ic = VectorGlyphs.makeIconView(activity, spec.glyph, 22, Color.WHITE);
                    FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(dp(22), dp(22));
                    iLp.gravity = Gravity.CENTER;
                    squircle.addView(ic, iLp);
                    squircle.setOnClickListener(v -> {
                        performHaptic(v);
                        onTileClick.onPick(id);
                    });
                    tileFrame.addView(squircle);

                    TextView badge = new TextView(activity);
                    badge.setText(isRemoveMode ? "−" : "+");
                    badge.setTextColor(Color.WHITE);
                    badge.setTextSize(13f);
                    badge.setTypeface(Typeface.DEFAULT_BOLD);
                    badge.setGravity(Gravity.CENTER);
                    badge.setBackground(GlassTheme.makeCircle(
                            isRemoveMode ? GlassTheme.ACCENT_RED : GlassTheme.ACCENT_GREEN));
                    FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(dp(21), dp(21));
                    bLp.gravity = Gravity.TOP | Gravity.END;
                    badge.setOnClickListener(v -> {
                        performHaptic(v);
                        onBadgeClick.onPick(id);
                    });
                    tileFrame.addView(badge, bLp);

                    cell.addView(tileFrame);

                    TextView lbl = new TextView(activity);
                    lbl.setText(spec.label);
                    lbl.setTextColor(0xD9FFFFFF);
                    lbl.setTextSize(10.5f);
                    lbl.setGravity(Gravity.CENTER);
                    lbl.setMaxLines(2);
                    lbl.setEllipsize(TextUtils.TruncateAt.END);
                    lbl.setPadding(dp(2), dp(4), dp(2), 0);
                    cell.addView(lbl);

                    LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (c < 3) cLp.rightMargin = dp(8);
                    row.addView(cell, cLp);
                } else {
                    LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
                    if (c < 3) spLp.rightMargin = dp(8);
                    row.addView(new View(activity), spLp);
                }
            }
            grid.addView(row);
        }
        return grid;
    }

    private void showShadeOverflowMenu() {
        final Prefs prefs = host.getPrefs();
        final boolean isClassic = prefs.getShadeStyle() == Prefs.SHADE_STYLE_CLASSIC;
        final boolean grouped = prefs.isShadeGroupNotifications();
        final int histCnt = DoxaizNotificationService.getHistoryCount();
        final String[] items = {
                "Изменить плитки («−» / «+» / порядок)",
                "Стиль шторки: " + (isClassic ? "Классическая → Современная" : "Современная → Классическая"),
                "Группировка уведомлений: " + (grouped ? "Вкл → Выкл" : "Выкл → Вкл"),
                "История уведомлений (" + histCnt + ")",
                "Dynamic Bar (капсула сверху)",
                "Уведомления и строка состояния",
                "Расширенная панель громкости (4 канала)"
        };
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        android.app.AlertDialog menuDlg = new android.app.AlertDialog.Builder(activity)
                .setTitle("Параметры шторки")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showTileEditorDialog();
                    } else if (which == 1) {
                        prefs.setShadeStyle(isClassic ? Prefs.SHADE_STYLE_MODERN : Prefs.SHADE_STYLE_CLASSIC);
                        rebuildShadeContent();
                        host.showToast(isClassic ? "Современная шторка" : "Классическая шторка");
                    } else if (which == 2) {
                        prefs.setShadeGroupNotifications(!grouped);
                        rebuildShadeContent();
                        host.showToast(!grouped ? "Группировка включена" : "Группировка выключена");
                    } else if (which == 3) {
                        showNotificationHistoryDialog();
                    } else if (which == 4) {
                        dismiss();
                        host.openDynamicBarSettings();
                    } else if (which == 5) {
                        dismiss();
                        host.openNotifStatusSettings();
                    } else if (which == 6) {
                        dismiss();
                        host.openSoundPanel();
                    }
                })
                .create();
        trackChildDialog(menuDlg);
        safeShowDialog(menuDlg);
    }

    // =========================================================================
    // Действия плиток, системные состояния и живая скорость трафика
    // =========================================================================

    private String resolveTileGlyph(String id, TileSpec spec) {
        if ("sound".equals(id)) {
            try {
                AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    int m = am.getRingerMode();
                    if (m == AudioManager.RINGER_MODE_VIBRATE) return "vibrate";
                    if (m == AudioManager.RINGER_MODE_SILENT) return "mute";
                }
            } catch (Throwable ignored) {}
        }
        return spec.glyph;
    }

    private String resolveTileLabel(String id, TileSpec spec) {
        if ("sound".equals(id)) {
            try {
                AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    int m = am.getRingerMode();
                    if (m == AudioManager.RINGER_MODE_VIBRATE) return "Вибрация";
                    if (m == AudioManager.RINGER_MODE_SILENT) return "Без звука";
                    return "Звук";
                }
            } catch (Throwable ignored) {}
        } else if ("timeout".equals(id)) {
            return formatScreenTimeoutShort(readScreenTimeoutMs());
        }
        return spec.label;
    }

    static List<String> parseGridTileIds(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null && !csv.isEmpty()) {
            for (String raw : csv.split(",")) {
                String id = raw.trim();
                if ("wifi".equals(id) || "mobile".equals(id)) continue;
                if (ALL_TILES.containsKey(id) && !out.contains(id)) out.add(id);
            }
        }
        if (out.isEmpty()) {
            for (String raw : Prefs.DEFAULT_SHADE_TILES.split(",")) {
                String id = raw.trim();
                if (!"wifi".equals(id) && !"mobile".equals(id) && ALL_TILES.containsKey(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private List<String> getGridTileIds(Prefs prefs) {
        String csv = prefs.getShadeTilesCsv();
        if (csv != null && csv.equals(cachedGridTilesCsv) && cachedGridTilesList != null) {
            return cachedGridTilesList;
        }
        List<String> parsed = parseGridTileIds(csv);
        cachedGridTilesCsv = csv;
        cachedGridTilesList = parsed;
        return parsed;
    }

    private List<String> getAllEnabledTilesIncludingConn(Prefs prefs) {
        List<String> out = new ArrayList<>();
        out.add("wifi");
        out.add("mobile");
        for (String id : getGridTileIds(prefs)) {
            if (!out.contains(id)) out.add(id);
        }
        if (out.size() < 12) {
            for (String id : CLASSIC_ORDER) {
                if (!out.contains(id)) out.add(id);
                if (out.size() >= 12) break;
            }
        }
        return out;
    }

    private boolean isTileActive(String id) {
        try {
            switch (id) {
                case "wifi": {
                    NetworkCapabilities caps = getNetworkCapabilitiesForFrame();
                    return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                }
                case "mobile": {
                    NetworkCapabilities caps = getNetworkCapabilitiesForFrame();
                    return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                }
                case "torch":
                    return torchOn;
                case "hotspot":
                    return isHotspotInterfaceActive();
                case "bluetooth": {
                    if (cachedBluetoothManager == null) {
                        cachedBluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
                    }
                    BluetoothAdapter ba = (cachedBluetoothManager != null) ? cachedBluetoothManager.getAdapter() : null;
                    return ba != null && ba.isEnabled();
                }
                case "sound": {
                    if (cachedAudioManager == null) {
                        cachedAudioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                    }
                    return cachedAudioManager != null && cachedAudioManager.getRingerMode() != AudioManager.RINGER_MODE_SILENT;
                }
                case "dnd": {
                    if (cachedNotificationManager == null) {
                        cachedNotificationManager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
                    }
                    return cachedNotificationManager != null
                            && cachedNotificationManager.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
                }
                case "airplane":
                    return Settings.Global.getInt(activity.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
                case "gps":
                    return isLocationEnabledCompat();
                case "autonet":
                    return host.getPrefs().isAutoNetworkEnabled();
                case "netboost":
                    return host.getPrefs().isAutoNetworkEnabled();
                case "rotate":
                    return Settings.System.getInt(activity.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
                case "battery": {
                    if (cachedPowerManager == null) {
                        cachedPowerManager = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
                    }
                    return cachedPowerManager != null && cachedPowerManager.isPowerSaveMode();
                }
                case "dim":
                    return dimEnabled;
                case "timeout":
                    return readScreenTimeoutMs() >= 120000;
                case "dbar":
                    return host.getPrefs().isDynamicBarEnabled();
                default:
                    return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isLocationEnabledCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (cachedLocationManager == null) {
                    cachedLocationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
                }
                return cachedLocationManager != null && cachedLocationManager.isLocationEnabled();
            }
            return Settings.Secure.getInt(activity.getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String readMobileSubtext() {
        try {
            if (!hasSystemFeatureSafe(PackageManager.FEATURE_TELEPHONY)) return "Нет модуля";
            TelephonyManager tm = (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                if (tm.getSimState() == TelephonyManager.SIM_STATE_ABSENT) return "Нет SIM";
                String op = tm.getNetworkOperatorName();
                if (op == null || op.trim().isEmpty()) op = tm.getSimOperatorName();
                if (op != null && !op.trim().isEmpty()) return op.trim();
            }
        } catch (Throwable ignored) {}
        return "Активно";
    }

    private boolean hasSystemFeatureSafe(String feature) {
        try {
            PackageManager pm = activity.getPackageManager();
            return pm != null && pm.hasSystemFeature(feature);
        } catch (Throwable ignored) {
            return true;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean hasHardwareVibrator() {
        if (cachedHasVibrator != null) return cachedHasVibrator;
        boolean res = true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                res = (vm != null && vm.getDefaultVibrator().hasVibrator());
            } else {
                Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                res = (v != null && v.hasVibrator());
            }
        } catch (Throwable ignored) {
            res = true;
        }
        cachedHasVibrator = res;
        return res;
    }

    private int resolveBackTorchMaxStrength(CameraManager cm) {
        if (backTorchMaxStrength >= 0) return backTorchMaxStrength;
        if (Build.VERSION.SDK_INT < 33 || cm == null) {
            backTorchMaxStrength = 1;
            return 1;
        }
        try {
            String backId = resolveBackTorchCameraId(cm);
            if (backId == null) {
                backTorchMaxStrength = 0;
                return 0;
            }
            CameraCharacteristics cc = cm.getCameraCharacteristics(backId);
            Integer maxLvl = cc.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
            Integer defLvl = cc.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL);
            int maxVal = (maxLvl != null && maxLvl > 1) ? maxLvl : 1;
            backTorchMaxStrength = maxVal;
            if (defLvl != null && defLvl >= 1 && defLvl <= maxVal) {
                backTorchStrengthLevel = defLvl;
            } else {
                backTorchStrengthLevel = Math.max(1, maxVal / 2);
            }
            return maxVal;
        } catch (Throwable ignored) {
            backTorchMaxStrength = 1;
            return 1;
        }
    }

    /**
     * Doxaiz 4.8: Безопасное масштабирование положения ползунка [0..maxProgress] в аппаратный уровень [1..maxStrength]
     * (защита от OEM HAL, где FLASH_INFO_STRENGTH_MAXIMUM_LEVEL может быть 4, 5, 10 или 100).
     */
    static int mapTorchSliderToStrength(int progress, int maxProgress, int maxStrength) {
        if (maxStrength <= 1) return 1;
        int clampedMax = Math.max(1, maxProgress);
        int clampedProg = Math.max(0, Math.min(clampedMax, progress));
        int level = 1 + Math.round((clampedProg / (float) clampedMax) * (maxStrength - 1));
        return Math.max(1, Math.min(maxStrength, level));
    }

    static String describeCameraAccessError(int reason) {
        if (reason == CameraAccessException.CAMERA_IN_USE) {
            return "Камера занята другим приложением";
        }
        if (reason == CameraAccessException.MAX_CAMERAS_IN_USE) {
            return "Устройство позволяет держать активной только 1 вспышку одновременно";
        }
        if (reason == CameraAccessException.CAMERA_DISCONNECTED) {
            return "Модуль вспышки временно недоступен";
        }
        return "Не удалось управлять вспышкой";
    }

    private void applyBackTorchStrength(int targetLevel) {
        try {
            CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return;
            String backId = resolveBackTorchCameraId(cm);
            if (backId == null) return;
            int maxLvl = resolveBackTorchMaxStrength(cm);
            int safeLvl = Math.max(1, Math.min(Math.max(1, maxLvl), targetLevel));
            backTorchStrengthLevel = safeLvl;
            if (Build.VERSION.SDK_INT >= 33 && maxLvl > 1) {
                cm.turnOnTorchWithStrengthLevel(backId, safeLvl);
            } else {
                cm.setTorchMode(backId, true);
            }
            backTorchOn = true;
            torchOn = true;
        } catch (CameraAccessException cae) {
            host.showToast(describeCameraAccessError(cae.getReason()));
        } catch (Throwable ignored) {
            host.showToast("Яркость вспышки недоступна");
        }
    }

    private String readWifiSubtext() {
        try {
            NetworkCapabilities caps = getNetworkCapabilitiesForFrame();
            if (caps != null) {
                int kbps = caps.getLinkDownstreamBandwidthKbps();
                if (kbps >= 1000) {
                    return "Подкл. • " + (kbps / 1000) + " Мбит/с";
                }
            }
        } catch (Throwable ignored) {}
        return "Подключено";
    }

    private void handleTileClick(String id) {
        switch (id) {
            case "wifi":
                showWifiPopupCard();
                break;
            case "mobile":
                showMobileDataPopupCard();
                break;
            case "torch":
                toggleTorch();
                rebuildShadeContent();
                break;
            case "bluetooth":
                showBluetoothPopupCard();
                break;
            case "sound":
                cycleRingerMode();
                rebuildShadeContent();
                break;
            case "dnd":
                toggleDoNotDisturb();
                rebuildShadeContent();
                break;
            case "hotspot":
                showHotspotPopupCard();
                break;
            case "airplane":
                dismiss();
                openSystemAction(Settings.ACTION_AIRPLANE_MODE_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS);
                break;
            case "gps":
                dismiss();
                openSystemAction(Settings.ACTION_LOCATION_SOURCE_SETTINGS, Settings.ACTION_SETTINGS);
                break;
            case "autonet": {
                Prefs p = host.getPrefs();
                boolean next = !p.isAutoNetworkEnabled();
                p.setAutoNetworkEnabled(next);
                host.onAutoNetworkSettingsChanged();
                rebuildShadeContent();
                break;
            }
            case "netboost": {
                boolean ok = host.triggerNetworkBoostNow();
                // reportNetworkConnectivity() на части прошивок — no-op, поэтому нельзя
                // обещать «перепроверено», если сеть не подтвердила восстановление (F-13).
                host.showToast(ok ? "Канал сети перепроверен"
                        : "Система не подтвердила переподключение — откройте панель сети");
                rebuildShadeContent();
                break;
            }
            case "rotate":
                toggleAutoRotate();
                rebuildShadeContent();
                break;
            case "record":
                dismiss();
                openSystemAction(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS);
                host.showToast("Запись экрана запускается из системной панели Android");
                break;
            case "qr":
                dismiss();
                try {
                    Intent cam = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                    cam.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(cam);
                } catch (Throwable ignored) {
                    host.showToast("Откройте камеру для сканирования QR");
                }
                break;
            case "battery":
                dismiss();
                openSystemAction(Settings.ACTION_BATTERY_SAVER_SETTINGS, Settings.ACTION_SETTINGS);
                break;
            case "dns":
                dismiss();
                host.openPrivateDnsModal();
                break;
            case "dim":
                dimEnabled = !dimEnabled;
                applyBrightnessFraction(dimEnabled ? 0.06f : 0.65f);
                host.showToast(dimEnabled ? "Ночная яркость включена" : "Обычная яркость");
                rebuildShadeContent();
                break;
            case "screenshot":
                showScreenshotPopupCard();
                break;
            case "lock":
                dismiss();
                if (!DoxaizLockService.lockScreenNow()) {
                    host.showToast("Включите службу жестов Doxaiz для блокировки");
                }
                break;
            case "timeout":
                cycleScreenTimeout();
                rebuildShadeContent();
                break;
            case "active_bg":
                dismiss();
                host.openActiveApps();
                break;
            case "dbar": {
                Prefs p = host.getPrefs();
                p.setDynamicBarEnabled(!p.isDynamicBarEnabled());
                host.showToast(p.isDynamicBarEnabled() ? "Dynamic Bar включён" : "Dynamic Bar выключен");
                rebuildShadeContent();
                break;
            }
            case "volume":
                dismiss();
                host.openSoundPanel();
                break;
            case "settings":
                dismiss();
                openSystemAction(Settings.ACTION_SETTINGS, Settings.ACTION_SETTINGS);
                break;
            default:
                break;
        }
    }

    /**
     * Долгое нажатие на плитку Центра управления -> мгновенный переход в её системные настройки
     * (как в нативных оболочках Android 14/15).
     */
    private void handleTileLongClick(String id) {
        if (id == null) return;
        switch (id) {
            case "wifi":
                dismiss();
                openSystemAction(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS);
                break;
            case "mobile":
                dismiss();
                openSystemAction(Settings.ACTION_DATA_ROAMING_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS);
                break;
            case "torch":
                showTorchPopupCard();
                break;
            case "bluetooth":
                dismiss();
                openSystemAction(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS);
                break;
            case "sound":
                showSoundQuickPopupCard();
                break;
            case "volume":
                dismiss();
                host.openSoundPanel();
                break;
            case "dnd":
                dismiss();
                openSystemAction(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, Settings.ACTION_SOUND_SETTINGS);
                break;
            case "hotspot":
                dismiss();
                openSystemAction("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS);
                break;
            case "screenshot":
                dismiss();
                openSystemAction(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS);
                break;
            case "gps":
                dismiss();
                openSystemAction(Settings.ACTION_LOCATION_SOURCE_SETTINGS, Settings.ACTION_SETTINGS);
                break;
            case "battery":
                dismiss();
                openSystemAction(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS);
                break;
            case "rotate":
            case "timeout":
            case "dim":
                dismiss();
                openSystemAction(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS);
                break;
            case "dbar":
                dismiss();
                host.openDynamicBarSettings();
                break;
            case "netboost":
                dismiss();
                NetworkQualityHelper.openInternetQuickPanel(activity);
                break;
            case "autonet":
            case "dns":
                dismiss();
                host.openPrivateDnsModal();
                break;
            case "active_bg":
                dismiss();
                host.openActiveApps();
                break;
            default:
                showTileEditorDialog();
                break;
        }
    }

    private int readScreenTimeoutMs() {
        try {
            return Settings.System.getInt(activity.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 60000);
        } catch (Throwable ignored) {
            return 60000;
        }
    }

    private String formatScreenTimeoutShort(int ms) {
        if (ms <= 15000) return "Сон 15с";
        if (ms <= 30000) return "Сон 30с";
        if (ms <= 60000) return "Сон 1м";
        if (ms <= 120000) return "Сон 2м";
        if (ms <= 300000) return "Сон 5м";
        return "Сон " + (ms / 60000) + "м";
    }

    private void cycleScreenTimeout() {
        try {
            if (Settings.System.canWrite(activity)) {
                int cur = readScreenTimeoutMs();
                int next;
                if (cur < 45000) next = 60000;        // 30с -> 1м
                else if (cur < 90000) next = 120000;  // 1м -> 2м
                else if (cur < 210000) next = 300000; // 2м -> 5м
                else next = 30000;                    // 5м -> 30с
                Settings.System.putInt(activity.getContentResolver(),
                        Settings.System.SCREEN_OFF_TIMEOUT, next);
                host.showToast("Автоотключение экрана: " + formatScreenTimeoutShort(next).replace("Сон ", ""));
                return;
            }
        } catch (Throwable ignored) {}
        dismiss();
        openWriteSettingsPermission("Разрешите изменение системных настроек для тайм-аута экрана");
    }

    private void openWriteSettingsPermission(String toastMsg) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            if (toastMsg != null) host.showToast(toastMsg);
            return;
        } catch (Throwable ignored) {
        }
        openSystemAction(Settings.ACTION_MANAGE_WRITE_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS);
        if (toastMsg != null) host.showToast(toastMsg);
    }

    static final int TORCH_MODE_BACK = 1;
    static final int TORCH_MODE_FRONT = 2;
    static final int TORCH_MODE_360 = 3;

    private void toggleTorch() {
        try {
            CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return;
            String backId = resolveBackTorchCameraId(cm);
            String frontId = resolveFrontTorchCameraId(cm);
            if (backTorchOn || frontTorchOn || torchOn) {
                if (backId != null) {
                    try { cm.setTorchMode(backId, false); } catch (Throwable ignored) {}
                }
                if (frontId != null) {
                    try { cm.setTorchMode(frontId, false); } catch (Throwable ignored) {}
                }
                backTorchOn = false;
                frontTorchOn = false;
                torchOn = false;
                host.showToast("Фонарик выключен");
                return;
            }
            if (backId != null) {
                cm.setTorchMode(backId, true);
                backTorchOn = true;
                torchOn = true;
                host.showToast("Фонарик включён");
                return;
            }
        } catch (Throwable ignored) {
            host.showToast("Фонарик недоступен");
        }
    }

    /**
     * Doxaiz 4.7: 3-режимное управление LED-вспышками (Подсветка / Фронтальная вспышка / 360° свет).
     */
    private void applyTorchMode(int mode) {
        try {
            CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                host.showToast("Фонарик недоступен");
                return;
            }
            String backId = resolveBackTorchCameraId(cm);
            String frontId = resolveFrontTorchCameraId(cm);
            if (mode == TORCH_MODE_BACK) {
                if (backId == null) {
                    host.showToast("Основная вспышка недоступна");
                    return;
                }
                boolean enableBack = !(backTorchOn && !frontTorchOn);
                cm.setTorchMode(backId, enableBack);
                backTorchOn = enableBack;
                if (frontId != null && frontTorchOn) {
                    try { cm.setTorchMode(frontId, false); } catch (Throwable ignored) {}
                    frontTorchOn = false;
                }
                torchOn = backTorchOn || frontTorchOn;
                host.showToast(backTorchOn ? "Подсветка включена" : "Фонарик выключен");
            } else if (mode == TORCH_MODE_FRONT) {
                if (frontId == null) {
                    host.showToast("Фронтальная вспышка не обнаружена на устройстве");
                    return;
                }
                boolean enableFront = !(frontTorchOn && !backTorchOn);
                cm.setTorchMode(frontId, enableFront);
                frontTorchOn = enableFront;
                if (backId != null && backTorchOn) {
                    try { cm.setTorchMode(backId, false); } catch (Throwable ignored) {}
                    backTorchOn = false;
                }
                torchOn = backTorchOn || frontTorchOn;
                host.showToast(frontTorchOn ? "Фронтальная вспышка включена" : "Фронтальная вспышка выключена");
            } else if (mode == TORCH_MODE_360) {
                boolean bothNow = backTorchOn && (frontId == null || frontTorchOn);
                boolean target = !bothNow;
                boolean anyOk = false;
                if (backId != null) {
                    try {
                        cm.setTorchMode(backId, target);
                        backTorchOn = target;
                        anyOk = true;
                    } catch (CameraAccessException cae) {
                        host.showToast(describeCameraAccessError(cae.getReason()));
                    } catch (Throwable ignored) {}
                }
                if (frontId != null) {
                    try {
                        cm.setTorchMode(frontId, target);
                        frontTorchOn = target;
                        anyOk = true;
                    } catch (CameraAccessException cae) {
                        if (target && cae.getReason() == CameraAccessException.MAX_CAMERAS_IN_USE) {
                            host.showToast("Это устройство позволяет держать включённой только 1 вспышку одновременно");
                            torchOn = backTorchOn || frontTorchOn;
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
                torchOn = backTorchOn || frontTorchOn;
                if (!anyOk) {
                    host.showToast("Фонарик недоступен");
                } else if (!target) {
                    host.showToast("Фонарик выключен");
                } else if (frontId == null) {
                    host.showToast("Включена основная вспышка (фронтальная LED отсутствует на устройстве)");
                } else {
                    host.showToast("Режим 360° свет включён");
                }
            }
        } catch (CameraAccessException cae) {
            host.showToast(describeCameraAccessError(cae.getReason()));
        } catch (Throwable ignored) {
            host.showToast("Фонарик недоступен");
        }
    }

    private void cycleRingerMode() {
        try {
            AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            NotificationManager nm = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
            if (am == null) return;
            int cur = am.getRingerMode();
            boolean hasVib = hasHardwareVibrator();
            boolean hasDndPerm = (nm != null && nm.isNotificationPolicyAccessGranted());
            int next;
            if (cur == AudioManager.RINGER_MODE_NORMAL) {
                if (hasVib) next = AudioManager.RINGER_MODE_VIBRATE;
                else if (hasDndPerm) next = AudioManager.RINGER_MODE_SILENT;
                else next = AudioManager.RINGER_MODE_NORMAL;
            } else if (cur == AudioManager.RINGER_MODE_VIBRATE && hasDndPerm) {
                next = AudioManager.RINGER_MODE_SILENT;
            } else {
                next = AudioManager.RINGER_MODE_NORMAL;
            }
            am.setRingerMode(next);
            host.showToast(next == AudioManager.RINGER_MODE_VIBRATE ? "Режим: Вибрация"
                    : next == AudioManager.RINGER_MODE_SILENT ? "Режим: Без звука" : "Режим: Звук");
        } catch (Throwable ignored) {
            dismiss();
            host.openSoundPanel();
        }
    }

    private void toggleDoNotDisturb() {
        try {
            NotificationManager nm = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (!nm.isNotificationPolicyAccessGranted()) {
                dismiss();
                openSystemAction(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, Settings.ACTION_SOUND_SETTINGS);
                host.showToast("Разрешите Doxaiz доступ к режиму «Не беспокоить»");
                return;
            }
            boolean currentlyDnd = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
            nm.setInterruptionFilter(currentlyDnd
                    ? NotificationManager.INTERRUPTION_FILTER_ALL
                    : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            host.showToast(currentlyDnd ? "Режим «Не беспокоить» выкл." : "Режим «Не беспокоить» вкл.");
        } catch (Throwable ignored) {
            dismiss();
            openSystemAction(Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_SETTINGS);
        }
    }

    private void toggleAutoRotate() {
        try {
            if (Settings.System.canWrite(activity)) {
                int cur = Settings.System.getInt(activity.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                Settings.System.putInt(activity.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, cur == 1 ? 0 : 1);
                host.showToast(cur == 1 ? "Автоповорот выкл." : "Автоповорот вкл.");
                return;
            }
        } catch (Throwable ignored) {}
        dismiss();
        openWriteSettingsPermission("Разрешите изменение системных настроек для автоповорота");
    }

    private boolean isAutoBrightnessOn() {
        try {
            return Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void clearWindowBrightnessOverride() {
        try {
            Window w = activity.getWindow();
            if (w != null) {
                WindowManager.LayoutParams lp = w.getAttributes();
                if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                    w.setAttributes(lp);
                }
            }
            if (shadeDialog != null && shadeDialog.getWindow() != null) {
                WindowManager.LayoutParams dlp = shadeDialog.getWindow().getAttributes();
                if (dlp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                    dlp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                    shadeDialog.getWindow().setAttributes(dlp);
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean hasHardwareLightSensor() {
        if (cachedHasLightSensor != null) return cachedHasLightSensor;
        boolean has = true;
        try {
            SensorManager sm = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
            has = (sm != null && sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null);
        } catch (Throwable ignored) {
            has = true;
        }
        cachedHasLightSensor = has;
        return has;
    }

    private void toggleAutoBrightness() {
        if (!hasHardwareLightSensor()) {
            host.showToast("Датчик освещённости отсутствует на этом устройстве");
            return;
        }
        try {
            if (Settings.System.canWrite(activity)) {
                boolean next = !isAutoBrightnessOn();
                Settings.System.putInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        next ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                brightnessManualApplied = !next;
                lastWrittenBrightnessVal = -1;
                if (next) clearWindowBrightnessOverride();
                host.showToast(next ? "Автояркость включена" : "Автояркость выключена");
                refreshIfShowing();
                return;
            }
        } catch (Throwable ignored) {}
        dismiss();
        openWriteSettingsPermission("Разрешите изменение системных настроек для яркости");
    }

    /**
     * Чистая тестируемая функция определения потолка системной яркости Android:
     * поддерживает стандартные 8-бит (255) и OEM 10/12-бит панели (1023 / 2047 / 4095).
     */
    static int resolveMaxBrightness(int configMax, int currentRaw) {
        int max = (configMax >= 255 && configMax <= 65535) ? configMax : 255;
        if (currentRaw > max) {
            if (currentRaw <= 1023) max = 1023;
            else if (currentRaw <= 2047) max = 2047;
            else if (currentRaw <= 4095) max = 4095;
            else max = Math.min(65535, currentRaw);
        }
        return max;
    }

    private int readMaxSystemBrightness(int currentRaw) {
        if (cachedMaxBrightness < 255) {
            int cfg = 255;
            try {
                Resources sys = Resources.getSystem();
                int id = sys.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android");
                if (id > 0) cfg = sys.getInteger(id);
            } catch (Throwable ignored) {
            }
            cachedMaxBrightness = resolveMaxBrightness(cfg, currentRaw);
        } else if (currentRaw > cachedMaxBrightness) {
            cachedMaxBrightness = resolveMaxBrightness(cachedMaxBrightness, currentRaw);
        }
        return cachedMaxBrightness;
    }

    private float readBrightnessFraction() {
        try {
            int v = Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 140);
            int maxB = readMaxSystemBrightness(v);
            double norm = Math.max(0.01, Math.min(1.0, v / (double) maxB));
            // Обратная перцептивная гамма 1.8 для комфортного чтения на низкой яркости
            float uiFrac = (float) Math.pow(norm, 1.0 / 1.8);
            return Math.max(0.04f, Math.min(1f, uiFrac));
        } catch (Throwable ignored) {
            return 0.6f;
        }
    }

    private AudioManager getAudioManager() {
        if (cachedAudioManager == null) {
            cachedAudioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        }
        return cachedAudioManager;
    }

    private void applyBrightnessFraction(float frac) {
        float safe = Math.max(0.04f, Math.min(1f, frac));
        int maxB = (cachedMaxBrightness >= 255) ? cachedMaxBrightness : readMaxSystemBrightness(140);
        int minB = Math.max(4, maxB / 64);
        // Перцептивная гамма 1.8: плавная регулировка в тёмной комнате без провала в чёрный
        int sysVal = Math.max(minB, Math.min(maxB, (int) Math.round(Math.pow(safe, 1.8) * maxB)));
        // Убираем лишний Binder IPC на 90/120 Гц, пока дискретное значение яркости не изменилось
        if (sysVal == lastWrittenBrightnessVal) return;
        lastWrittenBrightnessVal = sysVal;
        try {
            if (Settings.System.canWrite(activity)) {
                if (!brightnessManualApplied) {
                    clearWindowBrightnessOverride();
                    Settings.System.putInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    brightnessManualApplied = true;
                }
                Settings.System.putInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, sysVal);
                return;
            }
            if (shadeDialog != null && shadeDialog.getWindow() != null) {
                WindowManager.LayoutParams dlp = shadeDialog.getWindow().getAttributes();
                dlp.screenBrightness = Math.max(0.05f, (float) Math.pow(safe, 1.8));
                shadeDialog.getWindow().setAttributes(dlp);
            }
        } catch (Throwable ignored) {}
    }

    private float readVolumeFraction() {
        try {
            AudioManager am = getAudioManager();
            if (am == null) return 0.5f;
            int min = (Build.VERSION.SDK_INT >= 28) ? Math.max(0, am.getStreamMinVolume(AudioManager.STREAM_MUSIC)) : 0;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            lastWrittenVolumeStep = cur;
            int pct = SoundPanelHelper.volumeToPercent(cur, min, max);
            return Math.max(0.02f, Math.min(1f, pct / 100f));
        } catch (Throwable ignored) {
            return 0.5f;
        }
    }

    private void applyVolumeFraction(float frac) {
        try {
            AudioManager am = getAudioManager();
            if (am == null || am.isVolumeFixed()) return;
            int min = (Build.VERSION.SDK_INT >= 28) ? Math.max(0, am.getStreamMinVolume(AudioManager.STREAM_MUSIC)) : 0;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int target = SoundPanelHelper.ratioToVolume(frac, min, max);
            if (target == lastWrittenVolumeStep) return;
            lastWrittenVolumeStep = target;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        } catch (Throwable ignored) {}
    }

    private void sendMediaKey(int keyCode) {
        try {
            AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Throwable ignored) {}
    }

    private long readRawRxBytes() {
        try {
            long rx = TrafficStats.getTotalRxBytes();
            return (rx >= 0) ? rx : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private long readRawTxBytes() {
        try {
            long tx = TrafficStats.getTotalTxBytes();
            return (tx >= 0) ? tx : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static final long MAX_PLAUSIBLE_SPEED_BPS = 500_000_000L; // 500 МБ/с — защита от артефактов сброса VPN/интерфейса

    static long computeClampedSpeedBps(long prevBytes, long nowBytes, long deltaMs) {
        if (prevBytes <= 0L || nowBytes < prevBytes || deltaMs < 150L) return 0L;
        long bps = Math.round((nowBytes - prevBytes) / (deltaMs / 1000.0));
        if (bps < 0L || bps > MAX_PLAUSIBLE_SPEED_BPS) return 0L;
        return bps;
    }

    private void updateLiveSpeedSample() {
        long nowRx = readRawRxBytes();
        long nowTx = readRawTxBytes();
        long nowMs = SystemClock.uptimeMillis();
        if (nowRx < 0L || nowTx < 0L) {
            // TrafficStats временно недоступен — сохраняем предыдущую базу без скачка до ГБ/с
            return;
        }
        if (prevRxBytes > 0L && prevTxBytes > 0L && nowMs > prevNetTimeMs) {
            long dt = nowMs - prevNetTimeMs;
            long rawRx = computeClampedSpeedBps(prevRxBytes, nowRx, dt);
            long rawTx = computeClampedSpeedBps(prevTxBytes, nowTx, dt);
            liveRxBps = liveNetSampled ? smoothSpeedBps(liveRxBps, rawRx) : rawRx;
            liveTxBps = liveNetSampled ? smoothSpeedBps(liveTxBps, rawTx) : rawTx;
            liveNetSampled = true;
        }
        prevRxBytes = nowRx;
        prevTxBytes = nowTx;
        prevNetTimeMs = nowMs;
    }

    /**
     * Doxaiz 4.4: Экспоненциальное сглаживание живой скорости сети
     * (убирает резкие скачки до 0 между тиками; быстрый взлёт и мягкий спад).
     */
    static long smoothSpeedBps(long prevBps, long rawBps) {
        long p = Math.max(0L, prevBps);
        long r = Math.max(0L, rawBps);
        if (r < 512L && p < 2048L) return 0L;
        if (r >= p) {
            return Math.round(r * 0.72 + p * 0.28);
        }
        return Math.round(r * 0.55 + p * 0.45);
    }

    private int resolveNetIconColor(String netType) {
        if ("Нет сети".equals(netType)) return 0xFF52525B;
        return GlassTheme.ACCENT_GREEN;
    }

    private void refreshLiveNetIconColor(String netType) {
        if (liveNetIcon == null) return;
        String icoName = "Wi-Fi".equals(netType) ? "wifi" : "mobile";
        int badgeColor = resolveNetIconColor(netType);
        String key = icoName + "#" + badgeColor;
        if (key.equals(lastLiveNetIconKey)) return;
        lastLiveNetIconKey = key;
        liveNetIcon.setImageDrawable(VectorGlyphs.create(activity, icoName, 13, Color.WHITE));
        if (liveNetBadge != null) {
            liveNetBadge.setBackground(GlassTheme.makeCircle(badgeColor));
        }
    }

    /**
     * Doxaiz 4.6: Какие плитки Центра управления имеют треугольный уголок ◢ в правом нижнем углу.
     */
    static boolean hasExpandCorner(String tileId) {
        if (tileId == null) return false;
        switch (tileId) {
            case "wifi":
            case "mobile":
            case "torch":
            case "bluetooth":
            case "sound":
            case "hotspot":
            case "screenshot":
            case "autonet":
                return true;
            default:
                return false;
        }
    }

    /**
     * Doxaiz 4.6: Читает реально подключённые Bluetooth-аудиоустройства (наушники, гарнитуры, колонки)
     * через AudioManager без каких-либо дополнительных разрешений в манифесте.
     */
    private List<String> readConnectedBluetoothAudioNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            if (cachedAudioManager == null) {
                cachedAudioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            }
            if (cachedAudioManager != null) {
                AudioDeviceInfo[] outs = cachedAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                if (outs != null) {
                    for (AudioDeviceInfo dev : outs) {
                        if (dev == null) continue;
                        int type = dev.getType();
                        // Поля AudioDeviceInfo имеют разный уровень API:
                        // TYPE_HEARING_AID появился в 28, TYPE_BLE_* — в 31.
                        // Без проверки версии на старых Android был бы
                        // NoSuchFieldError, и панель звука падала бы целиком.
                        // Раньше здесь ещё стояли магические числа 26/27/30 —
                        // заменил на публичные константы BLE_* (они с API 31).
                        boolean isBt = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                || (Build.VERSION.SDK_INT >= 28
                                    && type == AudioDeviceInfo.TYPE_HEARING_AID)
                                || (Build.VERSION.SDK_INT >= 31
                                    && (type == AudioDeviceInfo.TYPE_BLE_HEADSET
                                        || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                                        || type == AudioDeviceInfo.TYPE_BLE_BROADCAST));
                        if (!isBt) continue;
                        CharSequence pn = dev.getProductName();
                        String label = (pn != null) ? pn.toString().trim() : "";
                        if (label.isEmpty()) label = "Bluetooth-аудио";
                        names.add(label);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return new ArrayList<>(names);
    }

    /**
     * Doxaiz 4.8: Компактная тёмная карточка-попап по центру шторки (единый каркас для всех плиток)
     * с защитой от обрезания на маленьких экранах и в горизонтальной ориентации.
     */
    static int popupWidthForWindow(int screenW, int maxWidthPx) {
        return Math.max(1, Math.min(Math.max(1, maxWidthPx), Math.round(Math.max(1, screenW) * 0.88f)));
    }

    static int popupHeightForWindow(int screenH) {
        return Math.max(1, Math.round(Math.max(1, screenH) * 0.82f));
    }

    private Dialog createCenteredPopupDialog(LinearLayout cardContent) {
        final Dialog dlg = new Dialog(activity);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCanceledOnTouchOutside(true);

        FrameLayout backdrop = new FrameLayout(activity);
        backdrop.setBackgroundColor(Color.TRANSPARENT);
        backdrop.setOnClickListener(v -> dlg.dismiss());

        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                WindowManager wm = activity.getWindowManager();
                if (wm != null) {
                    int bW = wm.getCurrentWindowMetrics().getBounds().width();
                    int bH = wm.getCurrentWindowMetrics().getBounds().height();
                    if (bW > 0) screenW = bW;
                    if (bH > 0) screenH = bH;
                }
            } catch (Throwable ignored) {}
        }
        // Never exceed the actual window on small split-screen and landscape windows.
        int cardW = popupWidthForWindow(screenW, dp(340));
        int maxCardH = popupHeightForWindow(screenH);

        ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int parentMode = MeasureSpec.getMode(heightMeasureSpec);
                int parentSize = MeasureSpec.getSize(heightMeasureSpec);
                int limit = parentMode == MeasureSpec.UNSPECIFIED
                        ? maxCardH : Math.min(maxCardH, parentSize);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
        };
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(GlassTheme.makeStyledPopup(activity, 24f));
        scroll.setClipToOutline(true);
        scroll.setClickable(true);

        cardContent.setPadding(dp(20), dp(18), dp(20), dp(16));
        cardContent.setClickable(true);
        scroll.addView(cardContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.gravity = Gravity.CENTER;
        backdrop.addView(scroll, cLp);

        dlg.setContentView(backdrop);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.62f;
            w.setAttributes(lp);
        }
        trackChildDialog(dlg);
        return dlg;
    }

    private View buildPopupGreenToggle(boolean checked, View.OnClickListener onClick) {
        FrameLayout track = new FrameLayout(activity);
        track.setBackground(GlassTheme.makeRoundedRect(
                checked ? GlassTheme.ACCENT_GREEN : 0xFF48484E, dp(14)));
        track.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(dp(46), dp(26));
        track.setLayoutParams(tLp);

        View thumb = new View(activity);
        thumb.setBackground(GlassTheme.makeCircle(Color.WHITE));
        FrameLayout.LayoutParams thLp = new FrameLayout.LayoutParams(dp(20), dp(20));
        thLp.gravity = (checked ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
        track.addView(thumb, thLp);
        track.setOnClickListener(onClick);
        return track;
    }

    private LinearLayout buildPopupHeaderRow(String title, boolean checked, View.OnClickListener onToggle) {
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp(14);
        head.setLayoutParams(hLp);

        TextView titleTv = new TextView(activity);
        titleTv.setText(title);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(19f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        head.addView(buildPopupGreenToggle(checked, onToggle));
        return head;
    }

    private TextView buildPopupMoreSettingsBtn(View.OnClickListener onClick) {
        TextView moreBtn = new TextView(activity);
        moreBtn.setText("Больше настроек");
        moreBtn.setTextColor(GlassTheme.ACCENT_GREEN);
        moreBtn.setTextSize(15f);
        moreBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        moreBtn.setGravity(Gravity.CENTER);
        moreBtn.setPadding(dp(12), dp(12), dp(12), dp(4));
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = dp(8);
        moreBtn.setLayoutParams(mLp);
        moreBtn.setOnClickListener(onClick);
        return moreBtn;
    }

    /**
     * Doxaiz 4.6: Компактная всплывающая карточка Bluetooth поверх шторки (статус, активные наушники/колонки, Больше настроек).
     */
    private void showBluetoothPopupCard() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        boolean btOn = isTileActive("bluetooth");
        List<String> activeAudio = readConnectedBluetoothAudioNames();

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);

        final Dialog[] dlgRef = new Dialog[1];
        View.OnClickListener openBtSettings = v -> {
            performHaptic(v);
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            dismiss();
            openSystemAction(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS);
        };

        card.addView(buildPopupHeaderRow("Bluetooth", btOn, openBtSettings));

        if (!activeAudio.isEmpty()) {
            for (String devName : activeAudio) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(2), dp(8), dp(2), dp(8));

                ImageView leftIc = VectorGlyphs.makeIconView(activity, "headphones", 20, Color.WHITE);
                LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(dp(20), dp(20));
                lLp.rightMargin = dp(14);
                row.addView(leftIc, lLp);

                LinearLayout txt = new LinearLayout(activity);
                txt.setOrientation(LinearLayout.VERTICAL);
                TextView nameTv = new TextView(activity);
                nameTv.setText(devName);
                nameTv.setTextColor(Color.WHITE);
                nameTv.setTextSize(15f);
                nameTv.setSingleLine(true);
                nameTv.setEllipsize(TextUtils.TruncateAt.END);
                txt.addView(nameTv);

                TextView subTv = new TextView(activity);
                subTv.setText("Активно • Аудиовыход");
                subTv.setTextColor(0xFF9EA0A8);
                subTv.setTextSize(12.5f);
                txt.addView(subTv);
                row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                ImageView rightIc = VectorGlyphs.makeIconView(activity, "settings", 18, GlassTheme.ACCENT_GREEN);
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(dp(18), dp(18));
                rLp.leftMargin = dp(10);
                row.addView(rightIc, rLp);

                row.setOnClickListener(openBtSettings);
                card.addView(row);
            }
        }

        View div = new View(activity);
        div.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dLp.topMargin = dp(6);
        dLp.bottomMargin = dp(10);
        card.addView(div, dLp);

        TextView secTv = new TextView(activity);
        secTv.setText("Доступные устройства");
        secTv.setTextColor(0xFF8E9098);
        secTv.setTextSize(12.5f);
        secTv.setPadding(dp(2), 0, dp(2), dp(8));
        card.addView(secTv);

        LinearLayout hintRow = new LinearLayout(activity);
        hintRow.setOrientation(LinearLayout.HORIZONTAL);
        hintRow.setGravity(Gravity.CENTER_VERTICAL);
        hintRow.setPadding(dp(2), dp(6), dp(2), dp(6));

        ImageView btIc = VectorGlyphs.makeIconView(activity, "bluetooth", 18, 0xFFB0B3BC);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        bLp.rightMargin = dp(12);
        hintRow.addView(btIc, bLp);

        TextView hintTv = new TextView(activity);
        boolean hasBtHw = hasSystemFeatureSafe(PackageManager.FEATURE_BLUETOOTH);
        hintTv.setText(!hasBtHw
                ? "Модуль Bluetooth отсутствует на этом устройстве"
                : (btOn
                        ? "Нажмите «Больше настроек» для поиска и подключения новых устройств"
                        : "Bluetooth выключен — откройте системные настройки, чтобы включить"));
        hintTv.setTextColor(0xFFB8BAC2);
        hintTv.setTextSize(13f);
        hintRow.addView(hintTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hintRow.setOnClickListener(openBtSettings);
        card.addView(hintRow);

        card.addView(buildPopupMoreSettingsBtn(openBtSettings));

        dlgRef[0] = createCenteredPopupDialog(card);
        safeShowDialog(dlgRef[0]);
    }

    private String readPrivateDnsSummaryFast() {
        boolean dnsActive = false;
        String dnsServer = "";
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                if (cachedConnectivityManager == null) {
                    cachedConnectivityManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
                }
                if (cachedConnectivityManager != null) {
                    Network net = cachedConnectivityManager.getActiveNetwork();
                    if (net != null) {
                        android.net.LinkProperties lp = cachedConnectivityManager.getLinkProperties(net);
                        if (lp != null) {
                            dnsActive = lp.isPrivateDnsActive();
                            String srv = lp.getPrivateDnsServerName();
                            if (srv != null) dnsServer = srv.trim();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return NetworkQualityHelper.formatPrivateDnsSummary(dnsActive, dnsServer);
    }

    /**
     * Doxaiz 4.7: Компактная всплывающая карточка Wi-Fi поверх шторки (1-в-1 со скриншотами:
     * состояние активной сети «Доступная сеть» + пустое состояние «Не удалось найти доступные сети Wi-Fi»).
     */
    private void showWifiPopupCard() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        boolean wifiOn = isTileActive("wifi");
        String speedInfo = wifiOn ? readWifiSubtext() : "";
        String dnsInfo = readPrivateDnsSummaryFast();
        Prefs p = host.getPrefs();

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);

        final Dialog[] dlgRef = new Dialog[1];
        View.OnClickListener openWifiPanel = v -> {
            performHaptic(v);
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            dismiss();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                openSystemAction(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS);
            } else {
                openSystemAction(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS);
            }
        };

        card.addView(buildPopupHeaderRow("Wi-Fi", wifiOn, openWifiPanel));

        if (!wifiOn) {
            FrameLayout emptyBox = new FrameLayout(activity);
            emptyBox.setMinimumHeight(dp(186));
            TextView emptyTv = new TextView(activity);
            emptyTv.setText("Не удалось найти доступные сети Wi-Fi");
            emptyTv.setTextColor(0xFF8E9098);
            emptyTv.setTextSize(14.5f);
            emptyTv.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams eLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            eLp.gravity = Gravity.CENTER;
            emptyBox.addView(emptyTv, eLp);
            emptyBox.setOnClickListener(openWifiPanel);
            card.addView(emptyBox, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            TextView secTv = new TextView(activity);
            secTv.setText("Доступная сеть");
            secTv.setTextColor(0xFF8E9098);
            secTv.setTextSize(13f);
            secTv.setPadding(dp(2), 0, dp(2), dp(10));
            card.addView(secTv);

            LinearLayout activeRow = new LinearLayout(activity);
            activeRow.setOrientation(LinearLayout.HORIZONTAL);
            activeRow.setGravity(Gravity.CENTER_VERTICAL);
            activeRow.setPadding(dp(2), dp(9), dp(2), dp(9));

            ImageView wIc = VectorGlyphs.makeIconView(activity, "wifi", 20, GlassTheme.ACCENT_GREEN);
            LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            wLp.rightMargin = dp(14);
            activeRow.addView(wIc, wLp);

            LinearLayout txt = new LinearLayout(activity);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView stTitle = new TextView(activity);
            stTitle.setText(speedInfo);
            stTitle.setTextColor(Color.WHITE);
            stTitle.setTextSize(15f);
            txt.addView(stTitle);

            TextView stSub = new TextView(activity);
            stSub.setText("Частный DNS: " + dnsInfo + " • Авто-сеть: " + (p.isAutoNetworkEnabled() ? "Вкл" : "Выкл"));
            stSub.setTextColor(0xFF8E9098);
            stSub.setTextSize(12f);
            txt.addView(stSub);
            activeRow.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageView chk = VectorGlyphs.makeIconView(activity, "check", 17, GlassTheme.ACCENT_GREEN);
            activeRow.addView(chk, new LinearLayout.LayoutParams(dp(17), dp(17)));
            activeRow.setOnClickListener(openWifiPanel);
            card.addView(activeRow);

            LinearLayout pickRow = new LinearLayout(activity);
            pickRow.setOrientation(LinearLayout.HORIZONTAL);
            pickRow.setGravity(Gravity.CENTER_VERTICAL);
            pickRow.setPadding(dp(2), dp(10), dp(2), dp(10));
            ImageView wIc2 = VectorGlyphs.makeIconView(activity, "wifi", 19, 0xFFB0B3BC);
            LinearLayout.LayoutParams w2Lp = new LinearLayout.LayoutParams(dp(19), dp(19));
            w2Lp.rightMargin = dp(14);
            pickRow.addView(wIc2, w2Lp);
            TextView pickTv = new TextView(activity);
            pickTv.setText("Выбрать другую сеть Wi-Fi");
            pickTv.setTextColor(Color.WHITE);
            pickTv.setTextSize(14.5f);
            pickRow.addView(pickTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            pickRow.setOnClickListener(openWifiPanel);
            card.addView(pickRow);

            LinearLayout recheckRow = new LinearLayout(activity);
            recheckRow.setOrientation(LinearLayout.HORIZONTAL);
            recheckRow.setGravity(Gravity.CENTER_VERTICAL);
            recheckRow.setPadding(dp(2), dp(10), dp(2), dp(6));
            ImageView rIc = VectorGlyphs.makeIconView(activity, "autonet", 19, 0xFFB0B3BC);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(dp(19), dp(19));
            rLp.rightMargin = dp(14);
            recheckRow.addView(rIc, rLp);
            TextView rcTv = new TextView(activity);
            rcTv.setText("Перепроверить канал сейчас");
            rcTv.setTextColor(0xFFD4D6DE);
            rcTv.setTextSize(14f);
            recheckRow.addView(rcTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            recheckRow.setOnClickListener(v -> {
                performHaptic(v);
                boolean ok = host.triggerNetworkBoostNow();
                host.showToast(ok ? "Канал Wi-Fi / сети перепроверен"
                        : "Система не подтвердила переподключение — откройте панель сети");
                if (dlgRef[0] != null) dlgRef[0].dismiss();
                rebuildShadeContent();
            });
            card.addView(recheckRow);
        }

        card.addView(buildPopupMoreSettingsBtn(openWifiPanel));

        dlgRef[0] = createCenteredPopupDialog(card);
        safeShowDialog(dlgRef[0]);
    }

    static boolean isHotspotInterfaceName(String ifName) {
        if (ifName == null || ifName.isEmpty()) return false;
        String lower = ifName.toLowerCase(Locale.ROOT);
        return lower.startsWith("ap0")
                || lower.startsWith("ap1")
                || lower.startsWith("wlan1")
                || lower.startsWith("swlan")
                || lower.startsWith("softap");
    }

    private boolean isHotspotInterfaceActive() {
        long now = SystemClock.uptimeMillis();
        if (cachedHotspotCheckedMs > 0L && (now - cachedHotspotCheckedMs) < 2000L) {
            return cachedHotspotActive;
        }
        boolean active = false;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                while (ifaces.hasMoreElements()) {
                    NetworkInterface ni = ifaces.nextElement();
                    if (ni != null && isHotspotInterfaceName(ni.getName()) && ni.isUp()) {
                        active = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        cachedHotspotActive = active;
        cachedHotspotCheckedMs = now;
        return active;
    }

    static String resolveActiveTorchLabel(boolean backOn, boolean frontOn) {
        if (backOn && frontOn) return "360° свет";
        if (frontOn) return "Фронтальная вспышка";
        if (backOn) return "Подсветка";
        return "Выкл.";
    }

    /**
     * Doxaiz 4.8: Всплывающая карточка «Фонарик», автоматически адаптирующаяся под железо телефона:
     * - если есть и задняя, и фронтальная LED -> 3 режима (Подсветка / Фронтальная вспышка / 360° свет);
     * - если есть только задняя вспышка -> чистый режим «Подсветка» без мёртвых кнопок;
     * - если Android 13+ и драйвер камеры поддерживает FLASH_INFO_STRENGTH_MAXIMUM_LEVEL > 1 -> плавный ползунок яркости.
     */
    private void showTorchPopupCard() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        CameraManager cm = null;
        String backId = null;
        String frontId = null;
        int maxStrength = 1;
        try {
            cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) {
                backId = resolveBackTorchCameraId(cm);
                frontId = resolveFrontTorchCameraId(cm);
                maxStrength = resolveBackTorchMaxStrength(cm);
            }
        } catch (Throwable ignored) {}

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(activity);
        titleTv.setText("Фонарик");
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(19f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.bottomMargin = dp(18);
        card.addView(titleTv, tLp);

        if (backId == null && frontId == null) {
            TextView noFlash = new TextView(activity);
            noFlash.setText("На этом устройстве не обнаружена аппаратная вспышка.");
            noFlash.setTextColor(0xFF9EA0A8);
            noFlash.setTextSize(14f);
            noFlash.setPadding(0, dp(8), 0, dp(10));
            card.addView(noFlash);
            Dialog dlg = createCenteredPopupDialog(card);
            safeShowDialog(dlg);
            return;
        }

        final boolean hasFront = (frontId != null);
        final int btnCount = hasFront ? 3 : 1;
        final LinearLayout[] modeBoxes = new LinearLayout[btnCount];
        final String[] glyphs = hasFront
                ? new String[]{"torch_back", "torch_front", "torch_360"}
                : new String[]{"torch_back"};
        final String[] labels = hasFront
                ? new String[]{"Подсветка", "Фронтальная\nвспышка", "360° свет"}
                : new String[]{"Подсветка"};
        final int[] modes = hasFront
                ? new int[]{TORCH_MODE_BACK, TORCH_MODE_FRONT, TORCH_MODE_360}
                : new int[]{TORCH_MODE_BACK};

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.bottomMargin = dp(6);
        row.setLayoutParams(rLp);

        final Runnable refreshHighlights = () -> {
            if (hasFront) {
                boolean is360 = backTorchOn && frontTorchOn;
                boolean isBackOnly = backTorchOn && !frontTorchOn;
                boolean isFrontOnly = frontTorchOn && !backTorchOn;
                boolean[] actives = {isBackOnly, isFrontOnly, is360};
                for (int i = 0; i < 3; i++) {
                    if (modeBoxes[i] != null) {
                        modeBoxes[i].setBackground(GlassTheme.makeRoundedRect(
                                actives[i] ? GlassTheme.TILE_ACTIVE_BLUE : GlassTheme.TILE_SUB_BG, dp(14)));
                    }
                }
            } else if (modeBoxes[0] != null) {
                modeBoxes[0].setBackground(GlassTheme.makeRoundedRect(
                        backTorchOn ? GlassTheme.TILE_ACTIVE_BLUE : GlassTheme.TILE_SUB_BG, dp(14)));
            }
        };

        for (int i = 0; i < btnCount; i++) {
            final int mode = modes[i];
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(6), dp(14), dp(6), dp(12));
            box.setMinimumHeight(dp(96));

            ImageView iv = VectorGlyphs.makeIconView(activity, glyphs[i], 28, Color.WHITE);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(28), dp(28));
            iLp.bottomMargin = dp(9);
            box.addView(iv, iLp);

            TextView lbl = new TextView(activity);
            lbl.setText(labels[i]);
            lbl.setTextColor(0xE6FFFFFF);
            lbl.setTextSize(11.5f);
            lbl.setGravity(Gravity.CENTER);
            lbl.setMaxLines(2);
            box.addView(lbl);

            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0, dp(98), 1f);
            if (i < btnCount - 1) bLp.rightMargin = dp(10);
            box.setLayoutParams(bLp);

            box.setOnClickListener(v -> {
                performHaptic(v);
                applyTorchMode(mode);
                refreshHighlights.run();
                rebuildShadeContent();
            });
            modeBoxes[i] = box;
            row.addView(box);
        }
        refreshHighlights.run();
        card.addView(row);

        // Android 13+ аппаратная яркость вспышки (показывается ТОЛЬКО если поддерживается камерой телефона!)
        if (Build.VERSION.SDK_INT >= 33 && maxStrength > 1 && backId != null) {
            final int maxStrFinal = maxStrength;
            final int sliderMax = Math.min(20, Math.max(4, maxStrFinal - 1));

            LinearLayout strHead = new LinearLayout(activity);
            strHead.setOrientation(LinearLayout.HORIZONTAL);
            strHead.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            shLp.topMargin = dp(14);
            shLp.bottomMargin = dp(4);
            strHead.setLayoutParams(shLp);

            TextView strLbl = new TextView(activity);
            strLbl.setText("Яркость подсветки");
            strLbl.setTextColor(0xFFB8BAC2);
            strLbl.setTextSize(12.5f);
            strHead.addView(strLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final TextView strValTv = new TextView(activity);
            int curPct = Math.round((backTorchStrengthLevel * 100f) / maxStrFinal);
            strValTv.setText(curPct + "%");
            strValTv.setTextColor(GlassTheme.ACCENT_GREEN);
            strValTv.setTextSize(12.5f);
            strValTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            strHead.addView(strValTv);
            card.addView(strHead);

            SeekBar sb = new SeekBar(activity);
            sb.setMax(sliderMax);
            int initProg = Math.round(((backTorchStrengthLevel - 1) / (float) (maxStrFinal - 1)) * sliderMax);
            sb.setProgress(Math.max(0, Math.min(sliderMax, initProg)));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                private int lastApplied = -1;
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int lvl = mapTorchSliderToStrength(progress, sliderMax, maxStrFinal);
                    strValTv.setText(Math.round((lvl * 100f) / maxStrFinal) + "%");
                    if (fromUser && lvl != lastApplied) {
                        lastApplied = lvl;
                        applyBackTorchStrength(lvl);
                        refreshHighlights.run();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) { rebuildShadeContent(); }
            });
            card.addView(sb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (!hasFront) {
            TextView devNote = new TextView(activity);
            devNote.setText("Фронтальная вспышка не предусмотрена на этом устройстве");
            devNote.setTextColor(0xFF8E9098);
            devNote.setTextSize(12f);
            devNote.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dnLp.topMargin = dp(8);
            card.addView(devNote, dnLp);
        }

        Dialog dlg = createCenteredPopupDialog(card);
        safeShowDialog(dlg);
    }

    /**
     * Doxaiz 4.8: Всплывающая карточка «Точка доступа» (адаптируется под наличие Wi-Fi и реальный статус AP).
     */
    private void showHotspotPopupCard() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        boolean hasWifiHw = hasSystemFeatureSafe(PackageManager.FEATURE_WIFI);
        boolean apActive = hasWifiHw && isHotspotInterfaceActive();

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);

        final Dialog[] dlgRef = new Dialog[1];
        View.OnClickListener openHotspotSettings = v -> {
            performHaptic(v);
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            dismiss();
            openSystemAction("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS);
        };

        card.addView(buildPopupHeaderRow("Точка доступа", apActive, openHotspotSettings));

        if (!hasWifiHw) {
            TextView noWifi = new TextView(activity);
            noWifi.setText("Модуль Wi-Fi для точки доступа отсутствует на этом устройстве.");
            noWifi.setTextColor(0xFF9EA0A8);
            noWifi.setTextSize(14f);
            noWifi.setPadding(dp(2), dp(8), dp(2), dp(8));
            card.addView(noWifi);
        } else {
            card.addView(buildHotspotFieldRow(
                    apActive ? "Активна • Раздача Wi-Fi" : "Точка доступа Wi-Fi",
                    "Имя",
                    openHotspotSettings
            ));
            card.addView(buildHotspotFieldRow(
                    "Настраивается в системе",
                    "Пароль",
                    openHotspotSettings
            ));
        }

        card.addView(buildPopupMoreSettingsBtn(openHotspotSettings));

        dlgRef[0] = createCenteredPopupDialog(card);
        safeShowDialog(dlgRef[0]);
    }

    private LinearLayout buildHotspotFieldRow(String value, String caption, View.OnClickListener onClick) {
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(2), dp(8), dp(2), dp(10));

        TextView valTv = new TextView(activity);
        valTv.setText(value);
        valTv.setTextColor(Color.WHITE);
        valTv.setTextSize(15.5f);
        valTv.setSingleLine(true);
        valTv.setEllipsize(TextUtils.TruncateAt.END);
        wrap.addView(valTv);

        TextView capTv = new TextView(activity);
        capTv.setText(caption);
        capTv.setTextColor(0xFF8E9098);
        capTv.setTextSize(12.5f);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(2);
        wrap.addView(capTv, cLp);

        wrap.setOnClickListener(onClick);
        return wrap;
    }

    private static final class SimSlotInfo {
        final int slotNum;
        final String carrier;
        final String subtitle;
        final boolean active;

        SimSlotInfo(int slotNum, String carrier, String subtitle, boolean active) {
            this.slotNum = slotNum;
            this.carrier = carrier;
            this.subtitle = subtitle;
            this.active = active;
        }
    }

    @SuppressWarnings("deprecation")
    private List<SimSlotInfo> readSimSlotsSafe(boolean mobOn) {
        List<SimSlotInfo> slots = new ArrayList<>();
        if (!hasSystemFeatureSafe(PackageManager.FEATURE_TELEPHONY)) {
            return slots;
        }
        String primaryOp = "";
        int slotCount = 1;
        boolean simAbsent = false;
        try {
            TelephonyManager tm = (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                simAbsent = (tm.getSimState() == TelephonyManager.SIM_STATE_ABSENT);
                String netOp = tm.getNetworkOperatorName();
                String simOp = tm.getSimOperatorName();
                if (netOp != null && !netOp.trim().isEmpty()) {
                    primaryOp = netOp.trim();
                } else if (simOp != null && !simOp.trim().isEmpty()) {
                    primaryOp = simOp.trim();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    slotCount = Math.max(1, Math.min(2, tm.getActiveModemCount()));
                } else {
                    slotCount = Math.max(1, Math.min(2, tm.getPhoneCount()));
                }
            }
        } catch (Throwable ignored) {}

        if (simAbsent && primaryOp.isEmpty() && !mobOn) {
            slots.add(new SimSlotInfo(1, "SIM-карта не установлена",
                    "Вставьте SIM-карту или откройте настройки сети", false));
            return slots;
        }

        String slot1Name = !primaryOp.isEmpty() ? primaryOp : "SIM 1";
        slots.add(new SimSlotInfo(1, slot1Name,
                mobOn ? "Активно • Мобильный интернет" : "Данные отключены", mobOn));
        if (slotCount >= 2) {
            slots.add(new SimSlotInfo(2, "SIM 2",
                    "Выбор SIM доступен в системной панели", false));
        }
        return slots;
    }

    /**
     * Doxaiz 4.8: Всплывающая карточка «Мобильные данные» (адаптируется под 0/1/2 SIM и наличие сотового модуля).
     */
    private void showMobileDataPopupCard() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        boolean hasTel = hasSystemFeatureSafe(PackageManager.FEATURE_TELEPHONY);
        boolean mobOn = hasTel && isTileActive("mobile");
        List<SimSlotInfo> slots = readSimSlotsSafe(mobOn);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);

        final Dialog[] dlgRef = new Dialog[1];
        View.OnClickListener openInternetPanel = v -> {
            performHaptic(v);
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            dismiss();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                openSystemAction(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_DATA_ROAMING_SETTINGS);
            } else {
                openSystemAction(Settings.ACTION_DATA_ROAMING_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS);
            }
        };
        View.OnClickListener openMobileSettings = v -> {
            performHaptic(v);
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            dismiss();
            openSystemAction(Settings.ACTION_DATA_ROAMING_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS);
        };

        card.addView(buildPopupHeaderRow("Мобильные данные", mobOn, openInternetPanel));

        if (!hasTel || slots.isEmpty()) {
            TextView noModem = new TextView(activity);
            noModem.setText("Сотовый модем отсутствует на этом устройстве.");
            noModem.setTextColor(0xFF9EA0A8);
            noModem.setTextSize(14f);
            noModem.setPadding(dp(2), dp(8), dp(2), dp(8));
            card.addView(noModem);
        } else {
            for (SimSlotInfo s : slots) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(2), dp(11), dp(2), dp(11));

                ImageView simIc = VectorGlyphs.makeIconView(activity, s.slotNum == 2 ? "sim2" : "sim1", 22, Color.WHITE);
                LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(dp(22), dp(22));
                sLp.rightMargin = dp(14);
                row.addView(simIc, sLp);

                LinearLayout txt = new LinearLayout(activity);
                txt.setOrientation(LinearLayout.VERTICAL);
                TextView nameTv = new TextView(activity);
                nameTv.setText(s.carrier);
                nameTv.setTextColor(Color.WHITE);
                nameTv.setTextSize(15.5f);
                nameTv.setSingleLine(true);
                nameTv.setEllipsize(TextUtils.TruncateAt.END);
                txt.addView(nameTv);

                TextView subTv = new TextView(activity);
                subTv.setText(s.subtitle);
                subTv.setTextColor(0xFF8E9098);
                subTv.setTextSize(12.5f);
                txt.addView(subTv);
                row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                if (s.active) {
                    ImageView chk = VectorGlyphs.makeIconView(activity, "check", 18, GlassTheme.ACCENT_GREEN);
                    LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(18), dp(18));
                    cLp.leftMargin = dp(10);
                    row.addView(chk, cLp);
                }

                row.setOnClickListener(openInternetPanel);
                card.addView(row);
            }
        }

        card.addView(buildPopupMoreSettingsBtn(openMobileSettings));

        dlgRef[0] = createCenteredPopupDialog(card);
        safeShowDialog(dlgRef[0]);
    }

    private void triggerDelayedScreenshot(final String afterHint) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            dismiss();
            host.showToast("Снимок экрана из шторки поддерживается на Android 9 и выше");
            return;
        }
        if (!DoxaizLockService.isRunning()) {
            dismiss();
            host.showToast("Включите службу жестов Doxaiz в Спецвозможностях");
            openSystemAction(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS);
            return;
        }
        dismiss();
        if (pendingScreenshotRunnable != null) {
            actionHandler.removeCallbacks(pendingScreenshotRunnable);
        }
        pendingScreenshotRunnable = () -> {
            pendingScreenshotRunnable = null;
            boolean ok = DoxaizLockService.takeScreenshotNow();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            if (ok) {
                if (afterHint != null && !afterHint.isEmpty()) {
                    host.showToast(afterHint);
                }
            } else {
                host.showToast("Не удалось сделать снимок экрана");
            }
        };
        actionHandler.postDelayed(pendingScreenshotRunnable, 320L);
    }

    /**
     * Doxaiz 4.8: Всплывающая карточка «Снимок экрана» (адаптируется под Android 9+ и статус службы Doxaiz).
     */
    private void showScreenshotPopupCard() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(activity);
        titleTv.setText("Снимок экрана");
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(19f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.bottomMargin = dp(12);
        card.addView(titleTv, tLp);

        final Dialog[] dlgRef = new Dialog[1];

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            TextView unsup = new TextView(activity);
            unsup.setText("Мгновенный снимок экрана из шторки доступен на Android 9 и выше.");
            unsup.setTextColor(0xFF9EA0A8);
            unsup.setTextSize(13.5f);
            unsup.setPadding(dp(2), dp(6), dp(2), dp(10));
            card.addView(unsup);
        } else {
            if (!DoxaizLockService.isRunning()) {
                TextView status = new TextView(activity);
                status.setText("Для снимка в 1 касание включите службу Doxaiz в Спецвозможностях");
                status.setTextColor(0xFFFBBF24);
                status.setTextSize(12.5f);
                status.setPadding(dp(10), dp(8), dp(10), dp(8));
                status.setBackground(GlassTheme.makeRoundedRect(0x26FBBF24, dp(12)));
                LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                stLp.bottomMargin = dp(10);
                status.setLayoutParams(stLp);
                status.setOnClickListener(v -> {
                    performHaptic(v);
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    dismiss();
                    openSystemAction(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS);
                });
                card.addView(status);
            }

            String[][] actions = {
                    {"screenshot_crop", "Снимок экрана", ""},
                    {"screenshot_partial", "Скриншот части экрана", "Нажмите «Обрезать» на миниатюре снимка"},
                    {"screenshot_scroll", "Прокручиваемый скриншот", "Нажмите «Длинный снимок» на миниатюре"}
            };
            for (String[] a : actions) {
                final String hint = a[2];
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(2), dp(12), dp(2), dp(12));

                ImageView ic = VectorGlyphs.makeIconView(activity, a[0], 22, Color.WHITE);
                LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(22), dp(22));
                icLp.rightMargin = dp(14);
                row.addView(ic, icLp);

                TextView lbl = new TextView(activity);
                lbl.setText(a[1]);
                lbl.setTextColor(Color.WHITE);
                lbl.setTextSize(15f);
                row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                row.setOnClickListener(v -> {
                    performHaptic(v);
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    triggerDelayedScreenshot(hint);
                });
                card.addView(row);
            }
        }

        card.addView(buildPopupMoreSettingsBtn(v -> {
            performHaptic(v);
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            dismiss();
            openSystemAction(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS);
        }));

        dlgRef[0] = createCenteredPopupDialog(card);
        safeShowDialog(dlgRef[0]);
    }

    /**
     * Doxaiz 4.6: Компактная всплывающая карточка режимов звука поверх шторки (Звук / Вибрация / Без звука / Не беспокоить).
     */
    private void showSoundQuickPopupCard() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        AudioManager am = (cachedAudioManager != null)
                ? cachedAudioManager
                : (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        NotificationManager nm = (cachedNotificationManager != null)
                ? cachedNotificationManager
                : (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);

        final int curRinger = (am != null) ? am.getRingerMode() : AudioManager.RINGER_MODE_NORMAL;
        final boolean curDnd = (nm != null
                && nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);

        final Dialog[] dlgRef = new Dialog[1];

        card.addView(buildPopupHeaderRow("Звук", curRinger != AudioManager.RINGER_MODE_SILENT && !curDnd, v -> {
            performHaptic(v);
            cycleRingerMode();
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            rebuildShadeContent();
        }));

        boolean hasVibHw = hasHardwareVibrator();
        List<String[]> modesList = new ArrayList<>();
        modesList.add(new String[]{"sound", "Звук", "normal"});
        if (hasVibHw) {
            modesList.add(new String[]{"vibrate", "Вибрация", "vibrate"});
        }
        modesList.add(new String[]{"mute", "Без звука", "silent"});
        modesList.add(new String[]{"dnd", "Не беспокоить", "dnd"});
        for (String[] m : modesList) {
            final String key = m[2];
            boolean isActive = ("normal".equals(key) && !curDnd && curRinger == AudioManager.RINGER_MODE_NORMAL)
                    || ("vibrate".equals(key) && !curDnd && curRinger == AudioManager.RINGER_MODE_VIBRATE)
                    || ("silent".equals(key) && !curDnd && curRinger == AudioManager.RINGER_MODE_SILENT)
                    || ("dnd".equals(key) && curDnd);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(11), dp(10), dp(11));
            row.setBackground(GlassTheme.makeRoundedRect(isActive ? 0xFF282B30 : Color.TRANSPARENT, dp(14)));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = dp(4);
            row.setLayoutParams(rLp);

            ImageView ic = VectorGlyphs.makeIconView(activity, m[0], 19,
                    isActive ? GlassTheme.ACCENT_GREEN : Color.WHITE);
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(19), dp(19));
            icLp.rightMargin = dp(12);
            row.addView(ic, icLp);

            TextView tv = new TextView(activity);
            tv.setText(m[1]);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(14.5f);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (isActive) {
                ImageView chk = VectorGlyphs.makeIconView(activity, "check", 16, GlassTheme.ACCENT_GREEN);
                row.addView(chk, new LinearLayout.LayoutParams(dp(16), dp(16)));
            }

            row.setOnClickListener(v -> {
                performHaptic(v);
                try {
                    if ("dnd".equals(key)) {
                        toggleDoNotDisturb();
                    } else if (am != null) {
                        if (curDnd && nm != null && nm.isNotificationPolicyAccessGranted()) {
                            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                        }
                        if ("normal".equals(key)) {
                            am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                            host.showToast("Режим: Звук");
                        } else if ("vibrate".equals(key)) {
                            am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                            host.showToast("Режим: Вибрация");
                        } else if ("silent".equals(key)) {
                            if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                                am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                                host.showToast("Режим: Без звука");
                            } else {
                                openSystemAction(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, Settings.ACTION_SOUND_SETTINGS);
                                host.showToast("Разрешите доступ к режиму «Не беспокоить» для полной тишины");
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                if (dlgRef[0] != null) dlgRef[0].dismiss();
                rebuildShadeContent();
            });
            card.addView(row);
        }

        card.addView(buildPopupMoreSettingsBtn(v -> {
            performHaptic(v);
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            dismiss();
            host.openSoundPanel();
        }));

        dlgRef[0] = createCenteredPopupDialog(card);
        safeShowDialog(dlgRef[0]);
    }

    private String resolveActiveNetTypeLabel() {
        try {
            NetworkCapabilities caps = getNetworkCapabilitiesForFrame();
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Мобильная сеть";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Сеть";
            }
        } catch (Throwable ignored) {
        }
        return "Нет сети";
    }

    private String buildNetSpeedLineText() {
        return formatNetSpeedLine(resolveActiveNetTypeLabel(), liveRxBps, liveTxBps, liveNetSampled);
    }

    /**
     * Doxaiz 4.2: Чистое форматирование живой скорости сети (без расхода трафика за день/месяц).
     */
    static String formatNetSpeedLine(String netType, long rxBps, long txBps, boolean sampled) {
        String prefix = (netType != null && !netType.isEmpty()) ? netType : "Сеть";
        if ("Нет сети".equals(prefix)) {
            return "Нет сети • 0 КБ/с";
        }
        if (!sampled) {
            return prefix + " • 0 КБ/с";
        }
        long safeRx = Math.max(0L, rxBps);
        long safeTx = Math.max(0L, txBps);
        long total = safeRx + safeTx;
        if (total < 512L) {
            return prefix + " • 0 КБ/с";
        }
        return prefix + " • " + formatSpeedRate(total)
                + "  (↓ " + formatSpeedRate(safeRx) + "  ↑ " + formatSpeedRate(safeTx) + ")";
    }

    static String formatSpeedRate(long bps) {
        long safe = Math.max(0L, bps);
        if (safe >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f МБ/с", safe / (1024.0 * 1024.0));
        }
        if (safe >= 1024L) {
            return String.format(Locale.US, "%.0f КБ/с", safe / 1024.0);
        }
        return "0 КБ/с";
    }

    private void performHaptic(View v) {
        if (v != null && host.getPrefs().isHapticFeedbackEnabled()) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    private void openSystemAction(String primary, String fallback) {
        try {
            Intent i = new Intent(primary);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Throwable ignored) {
            try {
                Intent f = new Intent(fallback);
                f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(f);
            } catch (Throwable ignored2) {
                host.showToast("Не удалось открыть системный экран");
            }
        }
    }

    private Drawable loadAppIconCached(String pkg) {
        if (pkg == null) return null;
        Drawable cached = appIconCache.get(pkg);
        if (cached != null) return cached;
        try {
            Drawable d = activity.getPackageManager().getApplicationIcon(pkg);
            appIconCache.put(pkg, d);
            return d;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String loadAppLabelCached(String pkg) {
        if (pkg == null) return "Система";
        String cached = appLabelCache.get(pkg);
        if (cached != null) return cached;
        try {
            PackageManager pm = activity.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence lbl = pm.getApplicationLabel(ai);
            String res = (lbl != null && lbl.length() > 0) ? lbl.toString() : pkg;
            appLabelCache.put(pkg, res);
            return res;
        } catch (Throwable ignored) {
            return pkg;
        }
    }

    private int dp(int v) {
        return GlassTheme.dp(activity, v);
    }
}
