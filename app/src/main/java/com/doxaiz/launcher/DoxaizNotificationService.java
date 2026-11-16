package com.doxaiz.launcher;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Служба уведомлений Doxaiz 4.5 (100% локально в ОЗУ, без записи на диск и без INTERNET):
 * 1) Бейджи и счётчики на иконках приложений с непрочитанными уведомлениями;
 * 2) Полные карточки уведомлений: MessagingStyle/InboxStyle переписка, аватары, превью фото (BigPicture),
 *    живой прогресс загрузок, авто-извлечение OTP-кодов, RemoteInput + быстрые ответы, Snooze (отложить),
 *    локальная История до 100 уведомлений;
 * 3) Реальный MediaSession.Token (перемотка SeekBar, Play/Pause, кэшированная обложка альбома);
 * 4) Список приложений с активными фоновыми службами для окна «Активно N приложений >».
 */
public final class DoxaizNotificationService extends NotificationListenerService {

    private static final int MAX_NOTIFICATIONS = 40;
    private static final int MAX_HISTORY = 100;
    private static final int MAX_TITLE_LEN = 90;
    private static final int MAX_TEXT_LEN = 260;

    public static final class ActionItem {
        public final String title;
        public final PendingIntent actionIntent;
        public final RemoteInput remoteInput;
        public final List<String> quickReplies;

        public ActionItem(String title, PendingIntent actionIntent, RemoteInput remoteInput,
                          List<String> quickReplies) {
            this.title = title;
            this.actionIntent = actionIntent;
            this.remoteInput = remoteInput;
            this.quickReplies = (quickReplies != null)
                    ? Collections.unmodifiableList(quickReplies) : Collections.emptyList();
        }

        public boolean isReply() {
            return remoteInput != null;
        }
    }

    /**
     * Безопасная отправка PendingIntent с явным разрешением Background Activity Launch (BAL)
     * на Android 14+ (targetSdk=34). Без опции MODE_BACKGROUND_ACTIVITY_START_ALLOWED система
     * Android 14 блокирует открытие чатов/приложений/действий из сторонней шторки и баннеров.
     */
    public static boolean sendPendingIntentSafely(Context ctx, PendingIntent pi, Intent fillIn) {
        if (pi == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                Bundle opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle();
                if (ctx != null) {
                    pi.send(ctx, 0, fillIn, null, null, null, opts);
                } else {
                    pi.send(opts);
                }
            } else if (ctx != null && fillIn != null) {
                pi.send(ctx, 0, fillIn);
            } else {
                pi.send();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Отправляет реальный быстрый ответ (RemoteInput) в мессенджер/SMS без открытия приложения.
     */
    public static boolean sendDirectReply(Context ctx, ActionItem act, String replyText) {
        if (ctx == null || act == null || act.actionIntent == null || act.remoteInput == null) return false;
        String clean = replyText != null ? replyText.trim() : "";
        if (clean.isEmpty()) return false;
        try {
            Bundle results = new Bundle();
            results.putCharSequence(act.remoteInput.getResultKey(), clean);
            Intent fillIn = new Intent();
            RemoteInput.addResultsToIntent(new RemoteInput[]{act.remoteInput}, fillIn, results);
            return sendPendingIntentSafely(ctx, act.actionIntent, fillIn);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static final class NotificationItem {
        public final String key;
        public final String packageName;
        public final String title;
        public final String text;
        public final String subText;
        public final List<String> messageLines;
        public final String otpCode;
        public final boolean isConversation;
        public final int progress;
        public final int progressMax;
        public final boolean progressIndeterminate;
        public final Bitmap senderAvatar;
        public final Bitmap bigPicture;
        public final long postTime;
        public final boolean clearable;
        public final PendingIntent contentIntent;
        public final List<ActionItem> actions;

        public NotificationItem(String key, String packageName, String title, String text,
                                String subText, List<String> messageLines, String otpCode,
                                boolean isConversation, int progress, int progressMax,
                                boolean progressIndeterminate, Bitmap senderAvatar, Bitmap bigPicture,
                                long postTime, boolean clearable, PendingIntent contentIntent,
                                List<ActionItem> actions) {
            this.key = key;
            this.packageName = packageName;
            this.title = title != null ? title : "";
            this.text = text != null ? text : "";
            this.subText = subText != null ? subText : "";
            this.messageLines = (messageLines != null)
                    ? Collections.unmodifiableList(messageLines) : Collections.emptyList();
            this.otpCode = otpCode;
            this.isConversation = isConversation;
            this.progress = progress;
            this.progressMax = progressMax;
            this.progressIndeterminate = progressIndeterminate;
            this.senderAvatar = senderAvatar;
            this.bigPicture = bigPicture;
            this.postTime = postTime;
            this.clearable = clearable;
            this.contentIntent = contentIntent;
            this.actions = (actions != null) ? Collections.unmodifiableList(actions) : Collections.emptyList();
        }

        public boolean hasProgress() {
            return progressIndeterminate || progressMax > 0;
        }
    }

    public static final class HistoryItem {
        public final String packageName;
        public final String title;
        public final String text;
        public final String otpCode;
        public final long postTime;
        public final long removedTime;

        public HistoryItem(String packageName, String title, String text, String otpCode,
                           long postTime, long removedTime) {
            this.packageName = packageName;
            this.title = title;
            this.text = text;
            this.otpCode = otpCode;
            this.postTime = postTime;
            this.removedTime = removedTime;
        }
    }

    public static final class MediaInfo {
        public final String packageName;
        public final String title;
        public final String artist;
        public final PendingIntent contentIntent;
        public final Bitmap albumArt;
        public final MediaSession.Token sessionToken;

        public MediaInfo(String packageName, String title, String artist,
                         PendingIntent contentIntent, Bitmap albumArt,
                         MediaSession.Token sessionToken) {
            this.packageName = packageName;
            this.title = title;
            this.artist = artist;
            this.contentIntent = contentIntent;
            this.albumArt = albumArt;
            this.sessionToken = sessionToken;
        }
    }

    public static boolean isMediaPlaying(Context ctx, MediaInfo media) {
        if (ctx == null || media == null || media.sessionToken == null) return false;
        try {
            MediaController mc = new MediaController(ctx, media.sessionToken);
            PlaybackState st = mc.getPlaybackState();
            return st != null && st.getState() == PlaybackState.STATE_PLAYING;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final Object LOCK = new Object();
    private static final Set<String> UNREAD_PACKAGES = new HashSet<>();
    private static final Map<String, Integer> UNREAD_COUNTS = new HashMap<>();
    private static final Map<String, Long> BACKGROUND_PACKAGES = new HashMap<>();
    private static final Map<String, String> BACKGROUND_TAB_DETAILS = new HashMap<>();
    private static final List<NotificationItem> ACTIVE_ITEMS = new ArrayList<>();
    private static final List<NotificationItem> SILENT_ITEMS = new ArrayList<>();
    private static final List<HistoryItem> HISTORY_ITEMS = new ArrayList<>();
    private static volatile MediaInfo activeMedia = null;
    private static volatile String activeCallLabel = null;
    private static volatile String activeRecorderLabel = null;
    private static volatile String activeTimerLabel = null;
    private static volatile String activeHotspotLabel = null;
    private static volatile int lastStateHash = 0;
    private static volatile DoxaizNotificationService instance = null;

    public interface HeadsUpListener {
        void onNewNotification(NotificationItem item);
    }

    private String cachedArtKey = null;
    private Bitmap cachedArtBmp = null;
    private volatile String lastHeadsUpSig = "";
    private final Object mediaCacheLock = new Object();
    private final Map<String, Bitmap> avatarCache = new LinkedHashMap<String, Bitmap>(24, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 24;
        }
    };
    private final Map<String, Bitmap> pictureCache = new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 8;
        }
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean connected = false;
    private static volatile long lastRebindAttemptMs = 0L;
    private static volatile Runnable listener;
    private static volatile HeadsUpListener headsUpListener;

    private final Runnable refreshDebounceRunnable = this::refreshFromSystem;

    private static final Runnable NOTIFY_RUNNABLE = () -> {
        Runnable l = listener;
        if (l != null) l.run();
    };

    public static boolean isServiceConnected() {
        return connected;
    }

    /**
     * Автоматическое переподключение службы уведомлений после обновления APK или перезагрузки
     * на прошивках XOS / MIUI / MagicOS / Android 14 (cooldown 20с, сначала requestRebind,
     * затем через 4с мягкий toggle компонента с DONT_KILL_APP, если система не привязала службу).
     */
    public static void ensureBoundIfPermitted(Context context) {
        if (connected || context == null) return;
        final Context appCtx = context.getApplicationContext();
        if (!isPermissionGranted(appCtx)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRebindAttemptMs < 20_000L) return;
        lastRebindAttemptMs = now;
        final ComponentName cn = new ComponentName(appCtx, DoxaizNotificationService.class);
        try {
            NotificationListenerService.requestRebind(cn);
        } catch (Throwable ignored) {
        }
        MAIN.postDelayed(() -> {
            if (connected || !isPermissionGranted(appCtx)) return;
            try {
                PackageManager pm = appCtx.getPackageManager();
                pm.setComponentEnabledSetting(
                        cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                pm.setComponentEnabledSetting(
                        cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                NotificationListenerService.requestRebind(cn);
            } catch (Throwable ignored) {
            }
        }, 4000L);
    }

    public static boolean isPermissionGranted(Context context) {
        try {
            String flat = Settings.Secure.getString(
                    context.getContentResolver(), "enabled_notification_listeners"
            );
            return flat != null && flat.contains(context.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void openSettings(Context context) {
        try {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    public static void setListener(Runnable onChange) {
        listener = onChange;
    }

    public static void setHeadsUpListener(HeadsUpListener l) {
        headsUpListener = l;
    }

    public static boolean hasUnread(String packageName) {
        if (!connected || packageName == null || packageName.isEmpty()) return false;
        synchronized (LOCK) {
            return UNREAD_PACKAGES.contains(packageName);
        }
    }

    public static int getUnreadCount(String packageName) {
        if (!connected || packageName == null || packageName.isEmpty()) return 0;
        synchronized (LOCK) {
            Integer c = UNREAD_COUNTS.get(packageName);
            return (c != null) ? c : 0;
        }
    }

    public static Set<String> snapshotUnreadPackages() {
        if (!connected) return Collections.emptySet();
        synchronized (LOCK) {
            return Collections.unmodifiableSet(new HashSet<>(UNREAD_PACKAGES));
        }
    }

    /**
     * Быстрый хэш состояния бейджей и счётчиков на иконках рабочего стола.
     * Позволяет не пересобирать рабочий стол на тики прогресса загрузок/медиа,
     * но точно обновлять числовые счётчики (1 -> 2 -> 3) в том же приложении.
     */
    public static int getBadgeStateHash() {
        if (!connected) return 0;
        synchronized (LOCK) {
            int h = 17;
            h = 31 * h + UNREAD_PACKAGES.hashCode();
            h = 31 * h + UNREAD_COUNTS.hashCode();
            return h;
        }
    }

    public static List<NotificationItem> snapshotNotifications() {
        if (!connected) return Collections.emptyList();
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(ACTIVE_ITEMS));
        }
    }

    public static List<NotificationItem> snapshotSilentNotifications() {
        if (!connected) return Collections.emptyList();
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(SILENT_ITEMS));
        }
    }

    public static List<HistoryItem> snapshotHistory() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(HISTORY_ITEMS));
        }
    }

    public static int getHistoryCount() {
        synchronized (LOCK) {
            return HISTORY_ITEMS.size();
        }
    }

    public static void clearHistory() {
        synchronized (LOCK) {
            HISTORY_ITEMS.clear();
        }
    }

    public static int dismissPackageNotifications(String packageName) {
        DoxaizNotificationService svc = instance;
        if (svc == null || !connected || packageName == null || packageName.isEmpty()) return 0;
        List<String> keys = new ArrayList<>();
        synchronized (LOCK) {
            for (NotificationItem it : ACTIVE_ITEMS) {
                if (it.clearable && packageName.equals(it.packageName) && it.key != null) {
                    keys.add(it.key);
                }
            }
        }
        int cleared = 0;
        for (String k : keys) {
            try {
                svc.cancelNotification(k);
                cleared++;
            } catch (Throwable ignored) {
            }
        }
        return cleared;
    }

    public static MediaInfo getActiveMediaInfo() {
        return connected ? activeMedia : null;
    }

    public static String getActiveCallLabel() {
        return connected ? activeCallLabel : null;
    }

    public static String getActiveRecorderLabel() {
        return connected ? activeRecorderLabel : null;
    }

    public static String getActiveTimerLabel() {
        return connected ? activeTimerLabel : null;
    }

    public static String getActiveHotspotLabel() {
        return connected ? activeHotspotLabel : null;
    }

    public static Map<String, Long> snapshotBackgroundApps() {
        if (!connected) return Collections.emptyMap();
        synchronized (LOCK) {
            return Collections.unmodifiableMap(new HashMap<>(BACKGROUND_PACKAGES));
        }
    }

    public static Map<String, String> snapshotBackgroundTabDetails() {
        if (!connected) return Collections.emptyMap();
        synchronized (LOCK) {
            return Collections.unmodifiableMap(new HashMap<>(BACKGROUND_TAB_DETAILS));
        }
    }

    /**
     * Возвращает реальные живые строки содержимого (последние сообщения/заголовки/статус службы)
     * конкретного приложения из активных и фоновых уведомлений Android.
     */
    public static List<String[]> snapshotRealAppLines(String pkg) {
        if (!connected || pkg == null || pkg.isEmpty()) return Collections.emptyList();
        List<String[]> out = new ArrayList<>();
        synchronized (LOCK) {
            for (NotificationItem it : ACTIVE_ITEMS) {
                if (pkg.equals(it.packageName)) {
                    out.add(new String[]{it.title, it.text});
                    if (out.size() >= 3) break;
                }
            }
            if (out.size() < 3) {
                for (NotificationItem it : SILENT_ITEMS) {
                    if (pkg.equals(it.packageName)) {
                        out.add(new String[]{it.title, it.text});
                        if (out.size() >= 3) break;
                    }
                }
            }
        }
        return out;
    }

    public static int getBackgroundAppCount() {
        if (!connected) return 0;
        synchronized (LOCK) {
            return BACKGROUND_PACKAGES.size();
        }
    }

    /**
     * Реально удаляет выбранное системное уведомление в Android.
     */
    public static boolean dismissNotification(String key) {
        DoxaizNotificationService svc = instance;
        if (svc == null || !connected || key == null || key.isEmpty()) return false;
        try {
            svc.cancelNotification(key);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Doxaiz 4.5: Нативно откладывает уведомление на заданное время (Android 8+ API 26 snoozeNotification).
     */
    public static boolean snoozeNotificationByKey(String key, long durationMs) {
        DoxaizNotificationService svc = instance;
        if (svc == null || !connected || key == null || key.isEmpty() || durationMs <= 0L) return false;
        try {
            svc.snoozeNotification(key, durationMs);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Останавливает воспроизведение в активном медиаплеере (если сейчас играет музыка/видео в фоне).
     */
    public static boolean stopActiveMedia(Context ctx) {
        MediaInfo info = activeMedia;
        if (ctx == null || info == null || info.sessionToken == null) return false;
        try {
            MediaController mc = new MediaController(ctx, info.sessionToken);
            mc.getTransportControls().pause();
            mc.getTransportControls().stop();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Останавливает медиаплеер, только если он относится к закрываемому пакету.
     */
    public static boolean stopMediaForPackage(Context ctx, String pkg) {
        MediaInfo info = activeMedia;
        if (pkg == null || info == null || !pkg.equals(info.packageName)) return false;
        return stopActiveMedia(ctx);
    }

    /**
     * Останавливает медиаплеер, только если он НЕ входит в список закреплённых замком приложений.
     */
    public static boolean stopActiveMediaExcept(Context ctx, Set<String> lockedPackages) {
        MediaInfo info = activeMedia;
        if (info != null && lockedPackages != null && lockedPackages.contains(info.packageName)) {
            return false;
        }
        return stopActiveMedia(ctx);
    }

    /**
     * Очищает все смахиваемые (clearable) уведомления, кроме закреплённых замком приложений.
     */
    public static int clearAllClearableExcept(Set<String> lockedPackages) {
        DoxaizNotificationService svc = instance;
        if (svc == null || !connected) return 0;
        List<String> keys = new ArrayList<>();
        synchronized (LOCK) {
            for (NotificationItem it : ACTIVE_ITEMS) {
                if (it.clearable && it.key != null) {
                    if (lockedPackages != null && lockedPackages.contains(it.packageName)) {
                        continue;
                    }
                    keys.add(it.key);
                }
            }
        }
        int cleared = 0;
        for (String k : keys) {
            try {
                svc.cancelNotification(k);
                cleared++;
            } catch (Throwable ignored) {
            }
        }
        return cleared;
    }

    /**
     * Очищает все смахиваемые (clearable) уведомления, не трогая системные/фоновые службы.
     */
    public static int clearAllClearable() {
        return clearAllClearableExcept(null);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        connected = true;
        refreshFromSystem();
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        if (instance == this) instance = null;
        lastHeadsUpSig = "";
        MAIN.removeCallbacks(refreshDebounceRunnable);
        synchronized (mediaCacheLock) { avatarCache.clear(); pictureCache.clear(); }
        cachedArtKey = null;
        cachedArtBmp = null;
        clearState();
        scheduleNotify();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (instance == this) instance = null;
        lastHeadsUpSig = "";
        MAIN.removeCallbacks(refreshDebounceRunnable);
        MAIN.removeCallbacks(NOTIFY_RUNNABLE);
        cachedArtKey = null;
        cachedArtBmp = null;
        synchronized (mediaCacheLock) {
            avatarCache.clear();
            pictureCache.clear();
        }
        clearState();
        super.onDestroy();
    }

    private static void clearState() {
        synchronized (LOCK) {
            UNREAD_PACKAGES.clear();
            UNREAD_COUNTS.clear();
            BACKGROUND_PACKAGES.clear();
            BACKGROUND_TAB_DETAILS.clear();
            ACTIVE_ITEMS.clear();
            SILENT_ITEMS.clear();
            HISTORY_ITEMS.clear();
        }
        activeMedia = null;
        activeCallLabel = null;
        activeRecorderLabel = null;
        activeTimerLabel = null;
        activeHotspotLabel = null;
        lastStateHash = 0;
    }

    private RankingMap getCurrentRankingSafe() {
        try {
            return getCurrentRanking();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        scheduleRefresh();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!connected || instance != this) return;
        dispatchHeadsUpIfEligible(sbn);
        scheduleRefresh();
    }

    private void dispatchHeadsUpIfEligible(StatusBarNotification sbn) {
        HeadsUpListener hl = headsUpListener;
        if (hl == null || sbn == null || sbn.isOngoing() || !sbn.isClearable()) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName()) || pkg.equals("android")) return;
        Notification notif = sbn.getNotification();
        if (notif == null) return;
        if ((notif.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        // Сверка с системным Ranking (как в AOSP Launcher3 / Lawnchair):
        // не всплываем баннером для беззвучных, низкоприоритетных, приостановленных и отфильтрованных DND уведомлений
        List<CharSequence> smartReplies = null;
        RankingMap rMap = getCurrentRankingSafe();
        if (rMap != null) {
            Ranking r = new Ranking();
            if (rMap.getRanking(sbn.getKey(), r)) {
                if (Build.VERSION.SDK_INT >= 28 && r.isSuspended()) return;
                if (r.isAmbient()) return;
                if (!r.matchesInterruptionFilter()) return;
                int imp = r.getImportance();
                if (imp != NotificationManager.IMPORTANCE_UNSPECIFIED
                        && imp <= NotificationManager.IMPORTANCE_LOW) {
                    return;
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    smartReplies = r.getSmartReplies();
                }
            }
        }

        Bundle extras = notif.extras;
        boolean isMedia = Notification.CATEGORY_TRANSPORT.equals(notif.category)
                || (extras != null && extras.containsKey(Notification.EXTRA_MEDIA_SESSION));
        if (isMedia) return;

        String title = trimClean(extras != null ? extras.getCharSequence(Notification.EXTRA_TITLE) : null, MAX_TITLE_LEN);
        String body = trimClean(extras != null ? extras.getCharSequence(Notification.EXTRA_BIG_TEXT) : null, MAX_TEXT_LEN);
        if (body.isEmpty()) {
            body = trimClean(extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT) : null, MAX_TEXT_LEN);
        }
        List<String> lines = extractMessageLines(extras);
        if (body.isEmpty() && !lines.isEmpty()) {
            body = lines.get(lines.size() - 1);
        }
        if (title.isEmpty() && body.isEmpty()) return;

        String sig = sbn.getKey() + "|" + title + "|" + body;
        if (sig.equals(lastHeadsUpSig)) return;
        lastHeadsUpSig = sig;

        final NotificationItem item = buildNotificationItem(sbn, notif, extras, pkg, title, body, lines, true, smartReplies);
        MAIN.post(() -> {
            HeadsUpListener cur = headsUpListener;
            if (connected && instance == this && cur != null) cur.onNewNotification(item);
        });
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!connected || instance != this) return;
        recordRemovedToHistory(sbn);
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        MAIN.removeCallbacks(refreshDebounceRunnable);
        MAIN.postDelayed(refreshDebounceRunnable, 60L);
    }

    private void recordRemovedToHistory(StatusBarNotification sbn) {
        if (sbn == null || sbn.isOngoing() || !sbn.isClearable()) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName()) || pkg.equals("android")) return;
        Notification notif = sbn.getNotification();
        if (notif == null) return;
        if ((notif.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle extras = notif.extras;
        boolean isMedia = Notification.CATEGORY_TRANSPORT.equals(notif.category)
                || (extras != null && extras.containsKey(Notification.EXTRA_MEDIA_SESSION));
        if (isMedia) return;

        String title = trimClean(extras != null ? extras.getCharSequence(Notification.EXTRA_TITLE) : null, MAX_TITLE_LEN);
        String body = trimClean(extras != null ? extras.getCharSequence(Notification.EXTRA_BIG_TEXT) : null, MAX_TEXT_LEN);
        if (body.isEmpty()) {
            body = trimClean(extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT) : null, MAX_TEXT_LEN);
        }
        if (body.isEmpty()) {
            List<String> lines = extractMessageLines(extras);
            if (!lines.isEmpty()) {
                body = lines.get(lines.size() - 1);
            }
        }
        if (title.isEmpty() && body.isEmpty()) return;

        String otp = NotificationTools.extractOtpCode(title, body);
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (!HISTORY_ITEMS.isEmpty()) {
                HistoryItem top = HISTORY_ITEMS.get(0);
                if (top.packageName.equals(pkg)
                        && top.title.equals(title)
                        && top.text.equals(body)
                        && Math.abs(now - top.removedTime) < 5000L) {
                    return;
                }
            }
            HISTORY_ITEMS.add(0, new HistoryItem(pkg, title, body, otp, sbn.getPostTime(), now));
            while (HISTORY_ITEMS.size() > MAX_HISTORY) {
                HISTORY_ITEMS.remove(HISTORY_ITEMS.size() - 1);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static MediaSession.Token extractMediaToken(Bundle extras) {
        if (extras == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token.class);
            }
            Object raw = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
            return (raw instanceof MediaSession.Token) ? (MediaSession.Token) raw : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void refreshFromSystem() {
        if (!connected) return;
        Set<String> freshUnread = new HashSet<>();
        Map<String, Integer> freshCounts = new HashMap<>();
        Map<String, Long> freshBg = new HashMap<>();
        Map<String, String> freshBgDetails = new HashMap<>();
        List<NotificationItem> freshItems = new ArrayList<>();
        List<NotificationItem> freshSilent = new ArrayList<>();
        MediaInfo freshMedia = null;
        String freshCall = null;
        String freshRec = null;
        String freshTimer = null;
        String freshHotspot = null;
        String selfPkg = getPackageName();

        try {
            StatusBarNotification[] list = getActiveNotifications();
            RankingMap rankingMap = getCurrentRankingSafe();
            Ranking ranking = new Ranking();
            if (list != null) {
                for (StatusBarNotification sbn : list) {
                    if (sbn == null) continue;
                    String pkg = sbn.getPackageName();
                    if (pkg == null || pkg.isEmpty() || pkg.equals(selfPkg)) {
                        continue;
                    }
                    Notification notif = sbn.getNotification();
                    if (notif == null) continue;

                    boolean hasRanking = rankingMap != null && rankingMap.getRanking(sbn.getKey(), ranking);
                    if (hasRanking && Build.VERSION.SDK_INT >= 28 && ranking.isSuspended()) {
                        continue;
                    }

                    Bundle extras = notif.extras;
                    String title = trimClean(extras != null ? extras.getCharSequence(Notification.EXTRA_TITLE) : null, MAX_TITLE_LEN);
                    String body = trimClean(extras != null ? extras.getCharSequence(Notification.EXTRA_BIG_TEXT) : null, MAX_TEXT_LEN);
                    if (body.isEmpty()) {
                        body = trimClean(extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT) : null, MAX_TEXT_LEN);
                    }

                    // Проверяем системное уведомление точки доступа Wi-Fi / модема даже от пакета "android"
                    if (freshHotspot == null && (sbn.isOngoing() || !sbn.isClearable())) {
                        String joined = (pkg + " " + title + " " + body).toLowerCase(java.util.Locale.ROOT);
                        if (joined.contains("hotspot") || joined.contains("tether")
                                || joined.contains("точка доступа") || joined.contains("модем")) {
                            freshHotspot = !title.isEmpty() ? title : "Точка доступа";
                        }
                    }

                    if (pkg.equals("android")) {
                        continue;
                    }

                    boolean isGroupSummary = (notif.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
                    boolean isMedia = Notification.CATEGORY_TRANSPORT.equals(notif.category)
                            || (extras != null && extras.containsKey(Notification.EXTRA_MEDIA_SESSION));

                    if (isMedia && freshMedia == null && !title.isEmpty()) {
                        String mArtist = trimClean(extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT) : null, MAX_TITLE_LEN);
                        String artKey = pkg + "|" + title + "|" + mArtist;
                        Bitmap art;
                        if (artKey.equals(cachedArtKey) && cachedArtBmp != null && !cachedArtBmp.isRecycled()) {
                            art = cachedArtBmp;
                        } else {
                            art = extractAlbumArt(notif);
                            cachedArtKey = artKey;
                            cachedArtBmp = art;
                        }
                        MediaSession.Token token = extractMediaToken(extras);
                        freshMedia = new MediaInfo(pkg, title, mArtist, notif.contentIntent, art, token);
                    }
                    if (freshCall == null && Notification.CATEGORY_CALL.equals(notif.category)) {
                        freshCall = !title.isEmpty() ? title : "Вызов";
                    }
                    if (freshRec == null && (sbn.isOngoing() || !sbn.isClearable())) {
                        String lp = pkg.toLowerCase(java.util.Locale.ROOT);
                        if (lp.contains("recorder") || lp.contains("soundrecord") || lp.contains("screenrecord")) {
                            freshRec = !title.isEmpty() ? title : "Идёт запись";
                        }
                    }
                    if (freshTimer == null && (sbn.isOngoing() || !sbn.isClearable())) {
                        String lp = pkg.toLowerCase(java.util.Locale.ROOT);
                        String tl = (title + " " + body).toLowerCase(java.util.Locale.ROOT);
                        if (lp.contains("deskclock") || lp.contains("clock") || lp.contains("timer")
                                || Notification.CATEGORY_ALARM.equals(notif.category)
                                || tl.contains("таймер") || tl.contains("секундомер") || tl.contains("timer")) {
                            freshTimer = !body.isEmpty() && body.length() <= 24 ? body
                                    : (!title.isEmpty() ? title : "Таймер");
                        }
                    }

                    if (!sbn.isOngoing() && sbn.isClearable()) {
                        boolean badgeAllowed = !hasRanking || ranking.canShowBadge();
                        boolean isLowSilent = hasRanking
                                && (ranking.isAmbient()
                                || ranking.getImportance() == NotificationManager.IMPORTANCE_MIN);
                        if (badgeAllowed && !isLowSilent) {
                            freshUnread.add(pkg);
                        }
                        if (!isGroupSummary && !isMedia) {
                            if (badgeAllowed && !isLowSilent) {
                                Integer prevC = freshCounts.get(pkg);
                                freshCounts.put(pkg, (prevC != null ? prevC : 0) + 1);
                            }
                            List<String> lines = extractMessageLines(extras);
                            String resolvedBody = body.isEmpty() && !lines.isEmpty()
                                    ? lines.get(lines.size() - 1) : body;
                            List<CharSequence> smartReplies = (hasRanking && Build.VERSION.SDK_INT >= 29)
                                    ? ranking.getSmartReplies() : null;
                            if (isLowSilent) {
                                if (!title.isEmpty() && freshSilent.size() < 12) {
                                    freshSilent.add(buildNotificationItem(
                                            sbn, notif, extras, pkg, title, resolvedBody, lines, true, smartReplies));
                                }
                            } else {
                                freshItems.add(buildNotificationItem(
                                        sbn, notif, extras, pkg, title, resolvedBody, lines, true, smartReplies));
                            }
                        }
                    } else if (sbn.isOngoing() || !sbn.isClearable()) {
                        if (!isMedia && !title.isEmpty() && freshSilent.size() < 12) {
                            freshSilent.add(buildNotificationItem(
                                    sbn, notif, extras, pkg, title, body, Collections.emptyList(), false, null));
                        }
                        if (pkg.equals("com.android.systemui")
                                || pkg.equals("com.android.providers.downloads")
                                || pkg.equals("com.android.bluetooth")
                                || pkg.equals("com.android.phone")) {
                            continue;
                        }
                        long postTime = sbn.getPostTime();
                        Long prev = freshBg.get(pkg);
                        if (prev == null || postTime < prev) {
                            freshBg.put(pkg, postTime);
                        }
                        if (!freshBgDetails.containsKey(pkg)) {
                            String detail = !title.isEmpty()
                                    ? (!body.isEmpty() ? title + " • " + body : title)
                                    : body;
                            if (!detail.isEmpty()) {
                                freshBgDetails.put(pkg, detail);
                            }
                        }
                    }
                }
            }
        } catch (SecurityException se) {
            // Android 14–16: если доступ к уведомлениям был отозван или сервис отсоединён,
            // сбрасываем connected и очищаем устаревший кэш вместо повторения ошибки
            connected = false;
            clearState();
            scheduleNotify();
            return;
        } catch (Throwable ignored) {
            return;
        }

        Collections.sort(freshItems, (a, b) -> Long.compare(b.postTime, a.postTime));
        if (freshItems.size() > MAX_NOTIFICATIONS) {
            freshItems = new ArrayList<>(freshItems.subList(0, MAX_NOTIFICATIONS));
        }

        int newHash = computeStateHash(freshUnread, freshBg, freshItems, freshSilent,
                freshMedia, freshCall, freshRec, freshTimer, freshHotspot);
        synchronized (LOCK) {
            UNREAD_PACKAGES.clear();
            UNREAD_PACKAGES.addAll(freshUnread);
            UNREAD_COUNTS.clear();
            UNREAD_COUNTS.putAll(freshCounts);
            BACKGROUND_PACKAGES.clear();
            BACKGROUND_PACKAGES.putAll(freshBg);
            BACKGROUND_TAB_DETAILS.clear();
            BACKGROUND_TAB_DETAILS.putAll(freshBgDetails);
            ACTIVE_ITEMS.clear();
            ACTIVE_ITEMS.addAll(freshItems);
            SILENT_ITEMS.clear();
            SILENT_ITEMS.addAll(freshSilent);
        }
        if (freshMedia == null) {
            cachedArtKey = null;
            cachedArtBmp = null;
        }
        activeMedia = freshMedia;
        activeCallLabel = freshCall;
        activeRecorderLabel = freshRec;
        activeTimerLabel = freshTimer;
        activeHotspotLabel = freshHotspot;
        if (newHash != lastStateHash) {
            lastStateHash = newHash;
            scheduleNotify();
        }
    }

    private static int computeStateHash(
            Set<String> unread, Map<String, Long> bg,
            List<NotificationItem> items, List<NotificationItem> silent,
            MediaInfo media, String callLbl, String recLbl,
            String timerLbl, String hotspotLbl
    ) {
        int h = unread.hashCode();
        h = 31 * h + bg.keySet().hashCode();
        for (NotificationItem it : items) {
            h = 31 * h + it.key.hashCode();
            h = 31 * h + it.title.hashCode();
            h = 31 * h + it.text.hashCode();
            h = 31 * h + it.progress;
            h = 31 * h + it.progressMax;
            h = 31 * h + (it.progressIndeterminate ? 1 : 0);
            h = 31 * h + it.messageLines.size();
            h = 31 * h + (it.otpCode != null ? it.otpCode.hashCode() : 0);
            h = 31 * h + (int) (it.postTime ^ (it.postTime >>> 32));
        }
        for (NotificationItem it : silent) {
            h = 31 * h + it.key.hashCode();
            h = 31 * h + it.title.hashCode();
            h = 31 * h + it.progress;
            h = 31 * h + it.progressMax;
        }
        if (media != null) {
            h = 31 * h + media.packageName.hashCode();
            h = 31 * h + media.title.hashCode();
            h = 31 * h + media.artist.hashCode();
        }
        if (callLbl != null) h = 31 * h + callLbl.hashCode();
        if (recLbl != null) h = 31 * h + recLbl.hashCode();
        if (timerLbl != null) h = 31 * h + timerLbl.hashCode();
        if (hotspotLbl != null) h = 31 * h + hotspotLbl.hashCode();
        return h;
    }

    private NotificationItem buildNotificationItem(
            StatusBarNotification sbn, Notification notif, Bundle extras,
            String pkg, String title, String body, List<String> lines, boolean clearable,
            List<CharSequence> smartReplies
    ) {
        String subText = trimClean(extras != null ? extras.getCharSequence(Notification.EXTRA_SUB_TEXT) : null, 48);
        String convTitle = trimClean(extras != null ? extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) : null, 48);
        if (subText.isEmpty() && !convTitle.isEmpty() && !convTitle.equals(title)) {
            subText = convTitle;
        }
        List<ActionItem> actions = extractActions(notif, smartReplies);
        boolean hasReplyAction = false;
        for (ActionItem a : actions) {
            if (a.isReply()) {
                hasReplyAction = true;
                break;
            }
        }
        boolean isConv = hasReplyAction
                || Notification.CATEGORY_MESSAGE.equals(notif.category)
                || (extras != null && (extras.containsKey(Notification.EXTRA_MESSAGES)
                || !convTitle.isEmpty()));

        int progress = extras != null ? extras.getInt(Notification.EXTRA_PROGRESS, 0) : 0;
        int progressMax = extras != null ? extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) : 0;
        boolean progressInd = extras != null && extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
        if (progressMax < 0) progressMax = 0;
        if (progress < 0) progress = 0;
        if (progressMax > 0 && progress > progressMax) progress = progressMax;

        // Ищем OTP-код по заголовку, тексту и последней строке чата
        String otpSource = body;
        if (!lines.isEmpty()) {
            otpSource = body + " " + lines.get(lines.size() - 1);
        }
        String otpCode = clearable ? NotificationTools.extractOtpCode(title, otpSource) : null;

        String cacheKey = sbn.getKey() + "|" + sbn.getPostTime();
        Bitmap avatar = clearable ? extractAvatarCached(cacheKey, notif) : null;
        Bitmap bigPic = clearable ? extractBigPictureCached(cacheKey, extras) : null;

        return new NotificationItem(
                sbn.getKey(),
                pkg,
                title,
                body,
                subText,
                lines,
                otpCode,
                isConv,
                progress,
                progressMax,
                progressInd,
                avatar,
                bigPic,
                sbn.getPostTime(),
                clearable,
                notif.contentIntent,
                actions
        );
    }

    @SuppressWarnings("deprecation")
    private List<String> extractMessageLines(Bundle extras) {
        List<String> out = new ArrayList<>();
        if (extras == null) return out;
        try {
            // 1) MessagingStyle (Telegram, WhatsApp, VK, Google Messages и др.)
            Parcelable[] rawMsgs = null;
            if (Build.VERSION.SDK_INT >= 33) {
                rawMsgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle.class);
            } else {
                rawMsgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            }
            if (rawMsgs != null && rawMsgs.length > 0) {
                int start = Math.max(0, rawMsgs.length - 4);
                for (int i = start; i < rawMsgs.length; i++) {
                    if (!(rawMsgs[i] instanceof Bundle)) continue;
                    Bundle mb = (Bundle) rawMsgs[i];
                    String msgText = trimClean(mb.getCharSequence("text"), 140);
                    if (msgText.isEmpty()) continue;
                    String sender = trimClean(mb.getCharSequence("sender"), 28);
                    if (sender.isEmpty() && Build.VERSION.SDK_INT >= 28) {
                        Object sp = Build.VERSION.SDK_INT >= 33
                                ? mb.getParcelable("sender_person", android.app.Person.class)
                                : mb.getParcelable("sender_person");
                        if (sp instanceof android.app.Person) {
                            sender = trimClean(((android.app.Person) sp).getName(), 28);
                        }
                    }
                    out.add(!sender.isEmpty() ? (sender + ": " + msgText) : msgText);
                }
                if (!out.isEmpty()) return out;
            }
            // 2) InboxStyle (список писем / сводка чатов)
            CharSequence[] textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (textLines != null && textLines.length > 0) {
                int start = Math.max(0, textLines.length - 4);
                for (int i = start; i < textLines.length; i++) {
                    String line = trimClean(textLines[i], 140);
                    if (!line.isEmpty()) out.add(line);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private Bitmap extractAvatarCached(String cacheKey, Notification notif) {
        if (notif == null) return null;
        synchronized (mediaCacheLock) {
            Bitmap cached = avatarCache.get(cacheKey);
            if (cached != null && !cached.isRecycled()) return cached;
        }
        try {
            Icon large = notif.getLargeIcon();
            if (large == null) return null;
            Drawable d = large.loadDrawable(this);
            if (d == null) return null;
            final int size = 72;
            Bitmap bmp;
            if (d instanceof BitmapDrawable) {
                Bitmap src = ((BitmapDrawable) d).getBitmap();
                if (src == null || src.isRecycled()) return null;
                bmp = Bitmap.createScaledBitmap(src, size, size, true);
            } else {
                bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, size, size);
                d.draw(c);
            }
            synchronized (mediaCacheLock) {
                avatarCache.put(cacheKey, bmp);
            }
            return bmp;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private Bitmap extractBigPictureCached(String cacheKey, Bundle extras) {
        if (extras == null) return null;
        synchronized (mediaCacheLock) {
            Bitmap cached = pictureCache.get(cacheKey);
            if (cached != null && !cached.isRecycled()) return cached;
        }
        try {
            Bitmap src = null;
            if (Build.VERSION.SDK_INT >= 33) {
                src = extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap.class);
            } else {
                Object raw = extras.getParcelable(Notification.EXTRA_PICTURE);
                if (raw instanceof Bitmap) src = (Bitmap) raw;
            }
            if (src == null || src.isRecycled() || src.getWidth() <= 0 || src.getHeight() <= 0) {
                return null;
            }
            final int maxW = 420;
            final int maxH = 260;
            int w = src.getWidth();
            int h = src.getHeight();
            float scale = Math.min(1f, Math.min((float) maxW / w, (float) maxH / h));
            int tw = Math.max(1, Math.round(w * scale));
            int th = Math.max(1, Math.round(h * scale));
            Bitmap scaled = (scale < 0.99f) ? Bitmap.createScaledBitmap(src, tw, th, true) : src;
            synchronized (mediaCacheLock) {
                pictureCache.put(cacheKey, scaled);
            }
            return scaled;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<ActionItem> extractActions(Notification notif, List<CharSequence> smartReplies) {
        List<ActionItem> out = new ArrayList<>();
        if (notif == null || notif.actions == null) return out;
        for (Notification.Action a : notif.actions) {
            if (a == null || a.actionIntent == null || a.title == null) continue;
            RemoteInput freeFormRi = null;
            List<String> quickReplies = null;
            RemoteInput[] ris = a.getRemoteInputs();
            if (ris != null && ris.length > 0) {
                for (RemoteInput ri : ris) {
                    if (ri != null && ri.getAllowFreeFormInput()) {
                        freeFormRi = ri;
                        CharSequence[] choices = ri.getChoices();
                        if (choices != null && choices.length > 0) {
                            quickReplies = new ArrayList<>();
                            for (CharSequence ch : choices) {
                                String q = trimClean(ch, 32);
                                if (!q.isEmpty()) {
                                    quickReplies.add(q);
                                    if (quickReplies.size() >= 4) break;
                                }
                            }
                        }
                        if ((quickReplies == null || quickReplies.isEmpty())
                                && smartReplies != null && !smartReplies.isEmpty()) {
                            quickReplies = new ArrayList<>();
                            for (CharSequence sr : smartReplies) {
                                String q = trimClean(sr, 32);
                                if (!q.isEmpty()) {
                                    quickReplies.add(q);
                                    if (quickReplies.size() >= 4) break;
                                }
                            }
                        }
                        break;
                    }
                }
                if (freeFormRi == null) continue;
            }
            String t = trimClean(a.title, 26);
            if (!t.isEmpty()) {
                ActionItem item = new ActionItem(t, a.actionIntent, freeFormRi, quickReplies);
                if (freeFormRi != null) {
                    out.add(0, item); // Кнопку «Ответить» ставим первой
                } else {
                    out.add(item);
                }
                if (out.size() >= 3) break;
            }
        }
        return out;
    }

    private Bitmap extractAlbumArt(Notification notif) {
        try {
            Icon large = notif.getLargeIcon();
            if (large == null) return null;
            Drawable d = large.loadDrawable(this);
            if (d == null) return null;
            final int size = 96;
            if (d instanceof BitmapDrawable) {
                Bitmap src = ((BitmapDrawable) d).getBitmap();
                if (src != null && !src.isRecycled()) {
                    return Bitmap.createScaledBitmap(src, size, size, true);
                }
            }
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            return bmp;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String trimClean(CharSequence cs, int maxLen) {
        if (cs == null) return "";
        String s = cs.toString().replace('\n', ' ').trim();
        if (s.length() > maxLen) {
            return s.substring(0, maxLen - 1) + "…";
        }
        return s;
    }

    private static void scheduleNotify() {
        MAIN.removeCallbacks(NOTIFY_RUNNABLE);
        MAIN.postDelayed(NOTIFY_RUNNABLE, 150L);
    }
}
