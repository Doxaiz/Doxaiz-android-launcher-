package com.doxaiz.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Prefs {
    public static final int OPACITY_LIGHT = 0;
    public static final int OPACITY_BALANCED = 1;
    public static final int OPACITY_DENSE = 2;

    public static final int DRAWER_GRID = 0;
    public static final int DRAWER_LIST = 1;

    public static final int ICON_COMPACT = 0;
    public static final int ICON_STANDARD = 1;
    public static final int ICON_LARGE = 2;

    // Действия для настраиваемых жестов (свайп вниз / двойной тап)
    public static final int GESTURE_NONE = 0;
    public static final int GESTURE_NOTIFICATIONS = 1;
    public static final int GESTURE_SEARCH = 2;
    public static final int GESTURE_LOCK_SCREEN = 3;
    public static final int GESTURE_SETTINGS = 4;
    public static final int GESTURE_NETWORK_PANEL = 5;
    public static final int GESTURE_HIDDEN_SPACE = 6;
    public static final int GESTURE_ACTIVE_WINDOWS = 7;

    // Вертикальное положение панели ОЗУ в окне «Недавние»
    public static final int RECENTS_RAM_POS_HIGH = 0;
    public static final int RECENTS_RAM_POS_DEFAULT = 1;
    public static final int RECENTS_RAM_POS_LOW = 2;

    // Стиль шторки уведомлений и быстрых настроек
    public static final int SHADE_STYLE_INFINIX = 0; // Современная раздельная (Центр управления / уведомлений)
    public static final int SHADE_STYLE_MODERN  = SHADE_STYLE_INFINIX;
    public static final int SHADE_STYLE_REDMI   = 1; // Классическая объединённая шторка
    public static final int SHADE_STYLE_CLASSIC = SHADE_STYLE_REDMI;
    public static final int SHADE_STYLE_SYSTEM  = 2; // Системная шторка Android

    // Стиль оформления меню приложений
    public static final int DRAWER_STYLE_INFINIX = 0; // Новый (AMOLED-карточки, как Infinix Hot 50)
    public static final int DRAWER_STYLE_REDMI = 1;   // Старый классический (компактный, как Redmi 9A)

    // Способ оповещения о непрочитанных уведомлениях (как в системных настройках)
    public static final int NOTIF_BADGE_DOT = 0;       // Значок уведомления (точка)
    public static final int NOTIF_BADGE_APP_ICONS = 1; // Значки приложений
    public static final int NOTIF_BADGE_NUMBERS = 2;   // Цифры (счётчик)
    public static final int NOTIF_BADGE_NONE = 3;      // Отсутствуют

    public static final int NET_MODE_GENTLE = 0;   // Мягкий (30с, только залипание)
    public static final int NET_MODE_BALANCED = 1; // Сбалансированный (15с, залипание + слабый канал)
    public static final int NET_MODE_ACTIVE = 2;   // Активный (10с, максимум реакции)

    public static final String DEFAULT_SHADE_TILES =
            "wifi,mobile,torch,bluetooth,sound,dnd,hotspot,airplane,gps,autonet,rotate,record,qr,battery,dns,dim,screenshot,lock,active_bg";

    private static final String PREF_NAME = "doxaiz_prefs";
    private static final String KEY_OPACITY = "opacity";
    private static final String KEY_DRAWER_MODE = "drawer_mode";
    private static final String KEY_DRAWER_STYLE = "drawer_style";
    private static final String KEY_SHADE_STYLE = "shade_style";
    private static final String KEY_SHADE_TILES = "shade_tiles_csv";
    private static final String KEY_SHADE_SPLIT_SWIPE = "shade_split_swipe";
    private static final String KEY_HIDE_NOTIF_TEXT = "hide_notif_text_shade";
    private static final String KEY_DYNAMIC_BAR = "dynamic_bar_enabled";
    private static final String KEY_DBAR_UNLOCK = "dbar_unlock";
    private static final String KEY_DBAR_CALLS = "dbar_calls";
    private static final String KEY_DBAR_RECORDER = "dbar_recorder";
    private static final String KEY_DBAR_CHARGING = "dbar_charging";
    private static final String KEY_DBAR_MEDIA = "dbar_media";
    private static final String KEY_NOTIF_BADGE_MODE = "notif_badge_mode";
    private static final String KEY_SHOW_NET_SPEED = "show_net_speed";
    private static final String KEY_SHOW_BATTERY_PCT = "show_battery_pct";
    private static final String KEY_SHOW_PAGE_DOTS = "show_page_dots";
    private static final String KEY_SHOW_ALPHABET_BAR = "show_alphabet_bar";
    private static final String KEY_SMOOTH_ANIMATIONS = "smooth_animations";
    private static final String KEY_COLUMNS = "columns";
    private static final String KEY_SHOW_HOME_LABELS = "show_home_labels";
    private static final String KEY_ICON_SIZE = "icon_size";
    private static final String KEY_HOME_BOTTOM_ALIGN = "home_bottom_align";
    private static final String KEY_AUTO_ADD_NEW = "auto_add_new";
    private static final String KEY_DESKTOP_LOCKED = "desktop_locked";
    private static final String KEY_HIDE_TOP_SETTINGS = "hide_top_settings";
    private static final String KEY_SHOW_DOCK_DRAWER = "show_dock_drawer";
    private static final String KEY_SWIPE_DOWN = "gesture_swipe_down";
    private static final String KEY_DOUBLE_TAP = "gesture_double_tap";
    private static final String KEY_SHOW_DRAWER_MEMORY = "show_drawer_memory";
    private static final String KEY_RECENTS_RAM_POS = "recents_ram_pos";
    private static final String KEY_AUTO_NETWORK_ASSIST = "auto_network_assist";
    private static final String KEY_PROTECT_HIDDEN = "protect_hidden_apps";
    private static final String KEY_SHOW_DRAWER_CATEGORIES = "show_drawer_categories";
    private static final String KEY_SHOW_NOTIF_DOTS = "show_notif_dots";
    private static final String KEY_SHOW_RECENT_APPS = "show_recent_apps";
    private static final String KEY_AUTO_KEYBOARD = "auto_keyboard_drawer";
    private static final String KEY_AUTO_LAUNCH_SINGLE = "auto_launch_single";
    private static final String KEY_USAGE_JSON = "app_usage_json";
    private static final String KEY_RECENTS_CLEARED_AT = "recents_cleared_at";
    private static final String KEY_DISMISSED_PKGS_JSON = "dismissed_pkgs_json";
    private static final String KEY_DESKTOP_JSON = "desktop_layout_json";
    private static final String KEY_HIDDEN = "hidden_apps";
    private static final String KEY_RENAME_PREFIX = "rename_";
    private static final String KEY_SWIPE_UP_PREFIX = "swipeup_";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Конструктор для JVM-тестов: работает с готовым хранилищем, без Context.
     * Пакетный доступ — снаружи приложения им пользоваться незачем.
     */
    Prefs(SharedPreferences storage) {
        this.sp = storage;
    }

    // ---------------------------------------------------------------- CrashGuard

    /**
     * Состояние аварийного возврата на штатный лаунчер (см. {@link CrashGuard}).
     *
     * Отдельные ключи, а не один JSON: значения крошечные, а читаются на каждом
     * старте — до построения интерфейса.
     */
    private static final String KEY_CRASH_PENDING = "crash_guard_pending";
    private static final String KEY_CRASH_STREAK = "crash_guard_streak";
    private static final String KEY_CRASH_FALLBACK = "crash_guard_fallback";
    private static final String KEY_CRASH_LAST_FALLBACK = "crash_guard_last_fallback";
    private static final String KEY_CRASH_ENABLED = "crash_guard_enabled";

    // ---------------------------------------------------- Импорт со штатного экрана

    /**
     * Спрашивали ли уже базу штатной оболочки при первом запуске.
     *
     * Зачем: LayoutImporter читает ContentProvider чужого лаунчера (XOS/HiOS/
     * Launcher3). На Android 14 такой доступ обычно запрещён, и каждый старт с
     * пустым рабочим столом заново шёл в IPC к провайдеру, который мог не
     * ответить вовсе — это блокировало UI-поток. Теперь неудачная попытка тоже
     * запоминается: спросили один раз — и хватит.
     */
    private static final String KEY_SYSTEM_IMPORT_DONE = "system_layout_import_done";

    public boolean isSystemLayoutImportDone() { return sp.getBoolean(KEY_SYSTEM_IMPORT_DONE, false); }

    public void setSystemLayoutImportDone(boolean done) {
        sp.edit().putBoolean(KEY_SYSTEM_IMPORT_DONE, done).apply();
    }

    public boolean isCrashGuardEnabled() { return sp.getBoolean(KEY_CRASH_ENABLED, true); }

    public void setCrashGuardEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_CRASH_ENABLED, enabled).apply();
    }

    /** Время начала последнего запуска рабочего стола, который не дошёл до показа. */
    public long getCrashGuardPending() { return sp.getLong(KEY_CRASH_PENDING, 0L); }

    public void setCrashGuardPending(long when) { sp.edit().putLong(KEY_CRASH_PENDING, when).apply(); }

    public int getCrashGuardStreak() { return sp.getInt(KEY_CRASH_STREAK, 0); }

    /** Устанавливает счётчик сбоев и отметку «запуск идёт» одним изменением. */
    public void setCrashGuardState(int streak, long pending) {
        sp.edit().putInt(KEY_CRASH_STREAK, streak).putLong(KEY_CRASH_PENDING, pending).apply();
    }

    public String getCrashGuardFallback() { return sp.getString(KEY_CRASH_FALLBACK, null); }

    public long getCrashGuardLastFallback() { return sp.getLong(KEY_CRASH_LAST_FALLBACK, 0L); }

    public void setCrashGuardFallback(String pkg, long when) {
        sp.edit().putString(KEY_CRASH_FALLBACK, pkg)
                .putLong(KEY_CRASH_LAST_FALLBACK, when).apply();
    }

    /**
     * Сбрасывает отметки аварийного возврата: и счётчик, и запомненный штатный
     * лаунчер. Пользователь сам решил начать с чистого листа — возвращаться
     * «куда раньше переключались» уже не обязательно.
     */
    public void clearCrashGuardMarks() {
        sp.edit().remove(KEY_CRASH_PENDING)
                .remove(KEY_CRASH_STREAK)
                .remove(KEY_CRASH_FALLBACK)
                .remove(KEY_CRASH_LAST_FALLBACK)
                .apply();
    }

    /**
     * Прямой доступ к хранилищу настроек. Нужен только диагностическому журналу
     * (см. {@link Diagnostics}): он живёт в тех же prefs и должен подняться
     * раньше, чем будет загружена раскладка.
     */
    public SharedPreferences getSharedPreferences() { return sp; }

    public int getGlassOpacity() { return sp.getInt(KEY_OPACITY, OPACITY_BALANCED); }
    public void setGlassOpacity(int opacity) { sp.edit().putInt(KEY_OPACITY, opacity).apply(); }

    public int getDrawerMode() { return sp.getInt(KEY_DRAWER_MODE, DRAWER_GRID); }
    public void setDrawerMode(int mode) { sp.edit().putInt(KEY_DRAWER_MODE, (mode == DRAWER_LIST) ? DRAWER_LIST : DRAWER_GRID).apply(); }

    public int getDrawerStyle() {
        int v = sp.getInt(KEY_DRAWER_STYLE, DRAWER_STYLE_INFINIX);
        return (v == DRAWER_STYLE_REDMI) ? DRAWER_STYLE_REDMI : DRAWER_STYLE_INFINIX;
    }
    public void setDrawerStyle(int style) {
        sp.edit().putInt(KEY_DRAWER_STYLE, (style == DRAWER_STYLE_REDMI) ? DRAWER_STYLE_REDMI : DRAWER_STYLE_INFINIX).apply();
    }

    public int getShadeStyle() {
        int v = sp.getInt(KEY_SHADE_STYLE, SHADE_STYLE_INFINIX);
        return (v >= 0 && v <= 2) ? v : SHADE_STYLE_INFINIX;
    }
    public void setShadeStyle(int style) {
        sp.edit().putInt(KEY_SHADE_STYLE, Math.max(0, Math.min(2, style))).apply();
    }

    public List<String> getShadeTiles() {
        String raw = sp.getString(KEY_SHADE_TILES, DEFAULT_SHADE_TILES);
        List<String> out = new ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            String[] parts = raw.split(",");
            for (String p : parts) {
                String k = p.trim();
                if (!k.isEmpty() && !out.contains(k)) out.add(k);
            }
        }
        return out;
    }

    public String getShadeTilesCsv() {
        return sp.getString(KEY_SHADE_TILES, DEFAULT_SHADE_TILES);
    }

    public void setShadeTilesCsv(String csv) {
        if (csv == null) return;
        String safe = csv.length() > 300 ? csv.substring(0, 300) : csv;
        sp.edit().putString(KEY_SHADE_TILES, safe).apply();
    }

    public boolean isShowPageDots() { return sp.getBoolean(KEY_SHOW_PAGE_DOTS, true); }
    public void setShowPageDots(boolean show) { sp.edit().putBoolean(KEY_SHOW_PAGE_DOTS, show).apply(); }

    public boolean isShowAlphabetBar() { return sp.getBoolean(KEY_SHOW_ALPHABET_BAR, true); }
    public void setShowAlphabetBar(boolean show) { sp.edit().putBoolean(KEY_SHOW_ALPHABET_BAR, show).apply(); }

    public boolean isSmoothAnimationsEnabled() {
        return sp.getBoolean(KEY_SMOOTH_ANIMATIONS, true);
    }
    public void setSmoothAnimationsEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_SMOOTH_ANIMATIONS, enabled).apply();
    }

    public boolean isShadeSplitSwipe() { return sp.getBoolean(KEY_SHADE_SPLIT_SWIPE, true); }
    public void setShadeSplitSwipe(boolean enabled) { sp.edit().putBoolean(KEY_SHADE_SPLIT_SWIPE, enabled).apply(); }

    public boolean isHideNotifTextInShade() { return sp.getBoolean(KEY_HIDE_NOTIF_TEXT, false); }
    public void setHideNotifTextInShade(boolean hide) { sp.edit().putBoolean(KEY_HIDE_NOTIF_TEXT, hide).apply(); }

    public int getColumns() {
        int c = sp.getInt(KEY_COLUMNS, 4);
        return (c == 5) ? 5 : 4;
    }
    public void setColumns(int columns) { sp.edit().putInt(KEY_COLUMNS, (columns == 5) ? 5 : 4).apply(); }

    public boolean isShowHomeLabels() { return sp.getBoolean(KEY_SHOW_HOME_LABELS, true); }
    public void setShowHomeLabels(boolean show) { sp.edit().putBoolean(KEY_SHOW_HOME_LABELS, show).apply(); }

    public int getIconSize() { return sp.getInt(KEY_ICON_SIZE, ICON_STANDARD); }
    public void setIconSize(int size) { sp.edit().putInt(KEY_ICON_SIZE, size).apply(); }

    public boolean isHomeBottomAligned() { return sp.getBoolean(KEY_HOME_BOTTOM_ALIGN, true); }
    public void setHomeBottomAligned(boolean bottom) { sp.edit().putBoolean(KEY_HOME_BOTTOM_ALIGN, bottom).apply(); }

    public boolean isAutoAddNewApps() { return sp.getBoolean(KEY_AUTO_ADD_NEW, true); }
    public void setAutoAddNewApps(boolean enabled) { sp.edit().putBoolean(KEY_AUTO_ADD_NEW, enabled).apply(); }

    public boolean isDesktopLocked() { return sp.getBoolean(KEY_DESKTOP_LOCKED, false); }
    public void setDesktopLocked(boolean locked) { sp.edit().putBoolean(KEY_DESKTOP_LOCKED, locked).apply(); }

    public boolean isHideTopSettingsBtn() { return sp.getBoolean(KEY_HIDE_TOP_SETTINGS, true); }
    public void setHideTopSettingsBtn(boolean hide) { sp.edit().putBoolean(KEY_HIDE_TOP_SETTINGS, hide).apply(); }

    public boolean isShowDockDrawerBtn() { return sp.getBoolean(KEY_SHOW_DOCK_DRAWER, false); }
    public void setShowDockDrawerBtn(boolean show) { sp.edit().putBoolean(KEY_SHOW_DOCK_DRAWER, show).apply(); }

    public int getSwipeDownAction() { return sp.getInt(KEY_SWIPE_DOWN, GESTURE_NOTIFICATIONS); }
    public void setSwipeDownAction(int action) { sp.edit().putInt(KEY_SWIPE_DOWN, action).apply(); }

    public int getDoubleTapAction() { return sp.getInt(KEY_DOUBLE_TAP, GESTURE_ACTIVE_WINDOWS); }
    public void setDoubleTapAction(int action) { sp.edit().putInt(KEY_DOUBLE_TAP, action).apply(); }

    public boolean isShowDrawerMemory() { return sp.getBoolean(KEY_SHOW_DRAWER_MEMORY, false); }
    public void setShowDrawerMemory(boolean show) { sp.edit().putBoolean(KEY_SHOW_DRAWER_MEMORY, show).apply(); }

    public int getRecentsRamPosition() {
        int p = sp.getInt(KEY_RECENTS_RAM_POS, RECENTS_RAM_POS_DEFAULT);
        return (p >= 0 && p <= 2) ? p : RECENTS_RAM_POS_DEFAULT;
    }
    public void setRecentsRamPosition(int pos) {
        sp.edit().putInt(KEY_RECENTS_RAM_POS, Math.max(0, Math.min(2, pos))).apply();
    }

    public boolean isAutoNetworkAssist() { return sp.getBoolean(KEY_AUTO_NETWORK_ASSIST, true); }
    public void setAutoNetworkAssist(boolean enabled) { sp.edit().putBoolean(KEY_AUTO_NETWORK_ASSIST, enabled).apply(); }

    public int getAutoNetworkMode() {
        int m = sp.getInt("auto_network_mode", NET_MODE_BALANCED);
        return (m >= NET_MODE_GENTLE && m <= NET_MODE_ACTIVE) ? m : NET_MODE_BALANCED;
    }

    public void setAutoNetworkMode(int mode) {
        int safe = Math.max(NET_MODE_GENTLE, Math.min(NET_MODE_ACTIVE, mode));
        sp.edit().putInt("auto_network_mode", safe).apply();
    }

    public boolean isAutoNetworkWeakWifi() {
        return sp.getBoolean("auto_network_weak_wifi", true);
    }

    public void setAutoNetworkWeakWifi(boolean enabled) {
        sp.edit().putBoolean("auto_network_weak_wifi", enabled).apply();
    }

    public boolean isProtectHiddenApps() { return sp.getBoolean(KEY_PROTECT_HIDDEN, false); }
    public void setProtectHiddenApps(boolean protect) { sp.edit().putBoolean(KEY_PROTECT_HIDDEN, protect).apply(); }

    public boolean isShowDrawerCategories() { return sp.getBoolean(KEY_SHOW_DRAWER_CATEGORIES, true); }
    public void setShowDrawerCategories(boolean show) { sp.edit().putBoolean(KEY_SHOW_DRAWER_CATEGORIES, show).apply(); }

    public boolean isShowNotificationDots() { return sp.getBoolean(KEY_SHOW_NOTIF_DOTS, true); }
    public void setShowNotificationDots(boolean show) { sp.edit().putBoolean(KEY_SHOW_NOTIF_DOTS, show).apply(); }

    public boolean isShowRecentApps() { return sp.getBoolean(KEY_SHOW_RECENT_APPS, true); }
    public void setShowRecentApps(boolean show) { sp.edit().putBoolean(KEY_SHOW_RECENT_APPS, show).apply(); }

    public boolean isAutoKeyboardDrawer() { return sp.getBoolean(KEY_AUTO_KEYBOARD, false); }
    public void setAutoKeyboardDrawer(boolean enabled) { sp.edit().putBoolean(KEY_AUTO_KEYBOARD, enabled).apply(); }

    public boolean isAutoLaunchSingleMatch() { return sp.getBoolean(KEY_AUTO_LAUNCH_SINGLE, false); }
    public void setAutoLaunchSingleMatch(boolean enabled) { sp.edit().putBoolean(KEY_AUTO_LAUNCH_SINGLE, enabled).apply(); }

    public String getDesktopJson() { return sp.getString(KEY_DESKTOP_JSON, null); }
    public void setDesktopJson(String json) {
        if (json == null) sp.edit().remove(KEY_DESKTOP_JSON).apply();
        else sp.edit().putString(KEY_DESKTOP_JSON, json).apply();
    }

    private final Object prefsCacheLock = new Object();
    private Set<String> hiddenAppsCache = null;

    public Set<String> getHiddenApps() {
        synchronized (prefsCacheLock) {
            if (hiddenAppsCache == null) {
                Set<String> stored = sp.getStringSet(KEY_HIDDEN, null);
                hiddenAppsCache = stored != null ? new HashSet<>(stored) : new HashSet<>();
            }
            return new HashSet<>(hiddenAppsCache);
        }
    }

    public void hideApp(String key) {
        if (key == null || key.isEmpty()) return;
        Set<String> toSave = null;
        synchronized (prefsCacheLock) {
            if (hiddenAppsCache == null) {
                Set<String> stored = sp.getStringSet(KEY_HIDDEN, null);
                hiddenAppsCache = stored != null ? new HashSet<>(stored) : new HashSet<>();
            }
            if (hiddenAppsCache.add(key)) {
                toSave = new HashSet<>(hiddenAppsCache);
            }
        }
        if (toSave != null) {
            sp.edit().putStringSet(KEY_HIDDEN, toSave).apply();
        }
    }

    public void unhideApp(String key) {
        if (key == null || key.isEmpty()) return;
        Set<String> toSave = null;
        synchronized (prefsCacheLock) {
            if (hiddenAppsCache == null) {
                Set<String> stored = sp.getStringSet(KEY_HIDDEN, null);
                hiddenAppsCache = stored != null ? new HashSet<>(stored) : new HashSet<>();
            }
            if (hiddenAppsCache.remove(key)) {
                toSave = new HashSet<>(hiddenAppsCache);
            }
        }
        if (toSave != null) {
            sp.edit().putStringSet(KEY_HIDDEN, toSave).apply();
        }
    }

    public void clearHiddenApps() {
        synchronized (prefsCacheLock) {
            hiddenAppsCache = new HashSet<>();
        }
        sp.edit().remove(KEY_HIDDEN).apply();
    }

    public Map<String, String> getCustomLabels() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_RENAME_PREFIX) && e.getValue() instanceof String) {
                result.put(e.getKey().substring(KEY_RENAME_PREFIX.length()), (String) e.getValue());
            }
        }
        return result;
    }

    public void setCustomLabel(String appKey, String newLabel) {
        if (newLabel == null || newLabel.trim().isEmpty()) {
            sp.edit().remove(KEY_RENAME_PREFIX + appKey).apply();
        } else {
            sp.edit().putString(KEY_RENAME_PREFIX + appKey, newLabel.trim()).apply();
        }
    }

    public void clearAllCustomLabels() {
        SharedPreferences.Editor ed = sp.edit();
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith(KEY_RENAME_PREFIX)) ed.remove(k);
        }
        ed.apply();
    }

    // Быстрый свайп вверх по конкретной иконке («2 в 1»)
    public String getIconSwipeUpTarget(String sourceAppKey) {
        if (sourceAppKey == null || sourceAppKey.isEmpty()) return null;
        String val = sp.getString(KEY_SWIPE_UP_PREFIX + sourceAppKey, null);
        return (val != null && !val.trim().isEmpty()) ? val.trim() : null;
    }

    public void setIconSwipeUpTarget(String sourceAppKey, String targetAppKey) {
        if (sourceAppKey == null || sourceAppKey.isEmpty()) return;
        if (targetAppKey == null || targetAppKey.trim().isEmpty()) {
            sp.edit().remove(KEY_SWIPE_UP_PREFIX + sourceAppKey).apply();
        } else {
            sp.edit().putString(KEY_SWIPE_UP_PREFIX + sourceAppKey, targetAppKey.trim()).apply();
        }
    }

    public Map<String, String> getAllIconSwipeUpTargets() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_SWIPE_UP_PREFIX) && e.getValue() instanceof String) {
                result.put(e.getKey().substring(KEY_SWIPE_UP_PREFIX.length()), (String) e.getValue());
            }
        }
        return result;
    }

    public void clearAllIconSwipeUpTargets() {
        SharedPreferences.Editor ed = sp.edit();
        for (String k : sp.getAll().keySet()) {
            if (k != null && k.startsWith(KEY_SWIPE_UP_PREFIX)) ed.remove(k);
        }
        ed.apply();
    }

    // =========================================================================
    // Учёт частых / недавних приложений (без разрешений, хранится локально)
    // =========================================================================

    private final Object usageLock = new Object();
    private Map<String, long[]> usageCache = null;

    private Map<String, long[]> ensureUsageCacheLocked() {
        if (usageCache != null) return usageCache;
        Map<String, long[]> map = new HashMap<>();
        String raw = sp.getString(KEY_USAGE_JSON, null);
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONObject root = new JSONObject(raw);
                Iterator<String> it = root.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject o = root.optJSONObject(k);
                    if (o != null) {
                        int c = o.optInt("c", 0);
                        long t = o.optLong("t", 0L);
                        if (c > 0) map.put(k, new long[]{c, t});
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        usageCache = map;
        return map;
    }

    /**
     * Журнал засчитывается сразу, а сериализация в JSON и запись на диск уходят
     * в фоновый поток: вызов идёт на горячем пути запуска приложения (сразу после
     * старта активности) и не должен отнимать кадр у анимации запуска.
     */
    public void recordAppLaunch(String appKey) {
        if (appKey == null || appKey.isEmpty()) return;
        try {
            final Map<String, long[]> copy;
            synchronized (usageLock) {
                Map<String, long[]> map = ensureUsageCacheLocked();
                long[] rec = map.get(appKey);
                long nextCount = (rec != null) ? (rec[0] + 1L) : 1L;
                map.put(appKey, new long[]{nextCount, System.currentTimeMillis()});

                // Держим журнал компактным (до 80 приложений)
                if (map.size() > 80) {
                    String oldestKey = null;
                    long oldestTs = Long.MAX_VALUE;
                    for (Map.Entry<String, long[]> e : map.entrySet()) {
                        long t = e.getValue()[1];
                        if (t < oldestTs) {
                            oldestTs = t;
                            oldestKey = e.getKey();
                        }
                    }
                    if (oldestKey != null) map.remove(oldestKey);
                }

                // Под блокировкой только копия карты (до 80 записей). Построение
                // JSON раньше шло здесь же, в UI-потоке, на каждое нажатие иконки
                // — то есть прямо в момент, когда пользователь ждёт запуска
                // приложения. Теперь строка собирается в фоновом потоке, а копия
                // защищает от изменений карты под нами.
                copy = new java.util.HashMap<>(map.size() * 2);
                for (Map.Entry<String, long[]> e : map.entrySet()) {
                    long[] v = e.getValue();
                    copy.put(e.getKey(), new long[]{v[0], v[1]});
                }
            }
            persistUsageAsync(copy);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Копия карты запусков в JSON. Выполняется в фоновом потоке, вне блокировки.
     *
     * org.json в Android объявляет put() бросающим JSONException (проверяемое
     * исключение), поэтому здесь оно перехватывается и возвращается null —
     * вызывающий просто пропускает запись, как и раньше при любом сбое.
     */
    private static String serializeUsage(Map<String, long[]> map) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, long[]> e : map.entrySet()) {
                JSONObject obj = new JSONObject();
                obj.put("c", (int) e.getValue()[0]);
                obj.put("t", e.getValue()[1]);
                root.put(e.getKey(), obj);
            }
            return root.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Исполнитель для редких фоновых записей настроек (журнал запусков, статистика). */
    private static final java.util.concurrent.ExecutorService PERSIST_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "doxaiz-prefs-io");
                t.setDaemon(true);
                return t;
            });

    private void persistUsageAsync(final Map<String, long[]> snapshot) {
        try {
            PERSIST_IO.execute(() -> {
                try {
                    String json = serializeUsage(snapshot);
                    if (json != null) sp.edit().putString(KEY_USAGE_JSON, json).apply();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
            // Пул уже остановлен (выход из процесса) — пишем синхронно, чтобы не потерять счётчик.
            try {
                String json = serializeUsage(snapshot);
                if (json != null) sp.edit().putString(KEY_USAGE_JSON, json).apply();
            } catch (Throwable ignored2) {
            }
        }
    }

    public static final long NEW_APP_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L;

    /**
     * Doxaiz 4.6: Проверяет, нужно ли показать зелёную точку «• » перед названием нового приложения
     * (пользовательское приложение, установлено не более 7 дней назад и ещё ни разу не открывалось).
     */
    public static boolean isNewUnopenedApp(boolean isSystemApp, long firstInstallTimeMs,
                                           boolean everLaunched, long nowMs) {
        if (isSystemApp || everLaunched || firstInstallTimeMs <= 0L) return false;
        long age = nowMs - firstInstallTimeMs;
        return age >= 0L && age <= NEW_APP_WINDOW_MS;
    }

    public boolean hasEverLaunched(String appKey) {
        if (appKey == null || appKey.isEmpty()) return false;
        synchronized (usageLock) {
            Map<String, long[]> map = ensureUsageCacheLocked();
            long[] rec = map.get(appKey);
            return rec != null && rec[0] > 0L;
        }
    }

    /**
     * Кэш метки «новое и ещё не открывалось» на текущие сутки.
     *
     * formatAppLabel() вызывается при каждой привязке элемента списка (в том числе на
     * каждом кадре прокрутки меню приложений), а внутри — захват usageLock, разбор
     * журнала и System.currentTimeMillis(). Метка живёт до 7 суток, поэтому пересчёт
     * раз в минуту даёт тот же результат бесплатно.
     */
    private final Object newAppCacheLock = new Object();
    private final Map<String, Boolean> newAppFlags = new HashMap<>();
    private long newAppFlagsBuiltAtMs = 0L;
    private static final long NEW_APP_FLAGS_TTL_MS = 60_000L;

    /** true, если у приложения ещё не было запусков и оно установлено недавно. */
    public boolean isNewUnopenedAppCached(AppEntry entry) {
        if (entry == null) return false;
        final long now = System.currentTimeMillis();
        synchronized (newAppCacheLock) {
            if (now - newAppFlagsBuiltAtMs > NEW_APP_FLAGS_TTL_MS || newAppFlagsBuiltAtMs > now) {
                newAppFlags.clear();
                newAppFlagsBuiltAtMs = now;
            }
            Boolean cached = newAppFlags.get(entry.key);
            if (cached != null) return cached;
        }
        boolean value = isNewUnopenedApp(entry.isSystemApp, entry.firstInstallTime,
                hasEverLaunched(entry.key), now);
        synchronized (newAppCacheLock) {
            newAppFlags.put(entry.key, value);
        }
        return value;
    }

    /** После запуска/установки приложения кэш меток нужно сбросить. */
    public void invalidateNewAppCache() {
        synchronized (newAppCacheLock) {
            newAppFlags.clear();
            newAppFlagsBuiltAtMs = 0L;
        }
    }

    public Map<String, Integer> getLaunchCountsMap() {
        Map<String, Integer> out = new HashMap<>();
        synchronized (usageLock) {
            Map<String, long[]> map = ensureUsageCacheLocked();
            for (Map.Entry<String, long[]> e : map.entrySet()) {
                out.put(e.getKey(), (int) e.getValue()[0]);
            }
        }
        return out;
    }

    public List<AppEntry> getFrequentApps(List<AppEntry> visible, int limit) {
        List<AppEntry> out = new ArrayList<>();
        if (limit <= 0 || visible == null || visible.isEmpty()) return out;

        final Map<String, Double> scores = new HashMap<>();
        long now = System.currentTimeMillis();
        synchronized (usageLock) {
            Map<String, long[]> map = ensureUsageCacheLocked();
            if (map.isEmpty()) return out;
            for (AppEntry e : visible) {
                long[] rec = map.get(e.key);
                if (rec == null || rec[0] <= 0L) continue;
                long ageHours = Math.max(0L, (now - rec[1]) / 3600000L);
                double recencyBoost = 24.0 / (1.0 + ageHours * 0.25);
                scores.put(e.key, rec[0] * 4.0 + recencyBoost);
                out.add(e);
            }
        }
        Collections.sort(out, (a, b) -> {
            double sa = scores.containsKey(a.key) ? scores.get(a.key) : 0.0;
            double sb = scores.containsKey(b.key) ? scores.get(b.key) : 0.0;
            return Double.compare(sb, sa);
        });
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    public List<AppEntry> getRecentlyLaunchedApps(List<AppEntry> visible, int limit) {
        List<AppEntry> out = new ArrayList<>();
        if (limit <= 0 || visible == null || visible.isEmpty()) return out;
        long clearedAt = sp.getLong(KEY_RECENTS_CLEARED_AT, 0L);
        JSONObject dismissed = null;
        try {
            String dRaw = sp.getString(KEY_DISMISSED_PKGS_JSON, null);
            if (dRaw != null && !dRaw.isEmpty()) dismissed = new JSONObject(dRaw);
        } catch (Throwable ignored) {
        }
        final Map<String, Long> times = new HashMap<>();
        synchronized (usageLock) {
            Map<String, long[]> map = ensureUsageCacheLocked();
            if (map.isEmpty()) return out;
            for (AppEntry e : visible) {
                long[] rec = map.get(e.key);
                if (rec == null) continue;
                long ts = rec[1];
                if (ts <= 0L || ts <= clearedAt) continue;
                if (dismissed != null && ts <= dismissed.optLong(e.packageName, 0L)) continue;
                times.put(e.key, ts);
                out.add(e);
            }
        }
        Collections.sort(out, (a, b) -> {
            long ta = times.containsKey(a.key) ? times.get(a.key) : 0L;
            long tb = times.containsKey(b.key) ? times.get(b.key) : 0L;
            return Long.compare(tb, ta);
        });
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    /**
     * Сколько «скрытых из Недавних» пакетов хранится.
     *
     * Зачем предел: каждый свайп по карточке в «Недавних» дописывал пакет в
     * JSON и никогда его не убирал. Набор только рос — десятки установок и
     * удалений приложений оставляли в настройках мусор, который целиком
     * перечитывался и перезаписывался при каждом следующем свайпе. Старые
     * записи не нужны: они сравниваются с временем запуска, а такие запуски
     * давно вне списка.
     */
    private static final int MAX_DISMISSED_PKGS = 200;

    public void markRecentPackageDismissed(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            String dRaw = sp.getString(KEY_DISMISSED_PKGS_JSON, null);
            JSONObject obj = (dRaw != null && !dRaw.isEmpty()) ? new JSONObject(dRaw) : new JSONObject();
            Map<String, Long> entries = parseDismissed(obj);
            entries.put(pkg, System.currentTimeMillis());
            sp.edit().putString(KEY_DISMISSED_PKGS_JSON,
                    dismissedToJson(trimDismissed(entries, MAX_DISMISSED_PKGS)).toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Оставляет {@link #MAX_DISMISSED_PKGS} самых свежих записей.
     *
     * Отдельный статический метод: он не трогает SharedPreferences, поэтому
     * проверяется JVM-тестом {@code Audit60Tests.dismissedRecentSetIsBounded()}.
     */
    static Map<String, Long> trimDismissed(Map<String, Long> entries, int limit) {
        Map<String, Long> out = new HashMap<>();
        if (entries == null || entries.isEmpty()) return out;
        if (limit <= 0) return out;
        if (entries.size() <= limit) {
            out.putAll(entries);
            return out;
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(entries.entrySet());
        // Свежие первыми: так в журнал попадают именно те записи, которые ещё
        // влияют на список «Недавних».
        Collections.sort(sorted, (a, b) -> {
            long ta = (a.getValue() != null) ? a.getValue() : 0L;
            long tb = (b.getValue() != null) ? b.getValue() : 0L;
            return Long.compare(tb, ta);
        });
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Long> e = sorted.get(i);
            out.put(e.getKey(), (e.getValue() != null) ? e.getValue() : 0L);
        }
        return out;
    }

    /** Разбор JSON «скрытых из Недавних» в обычную карту. */
    private static Map<String, Long> parseDismissed(JSONObject obj) {
        Map<String, Long> out = new HashMap<>();
        if (obj == null) return out;
        try {
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k == null || k.isEmpty()) continue;
                out.put(k, obj.optLong(k, 0L));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static JSONObject dismissedToJson(Map<String, Long> map) throws org.json.JSONException {
        JSONObject obj = new JSONObject();
        if (map != null) {
            for (Map.Entry<String, Long> e : map.entrySet()) {
                obj.put(e.getKey(), (e.getValue() != null) ? e.getValue() : 0L);
            }
        }
        return obj;
    }

    public void markAllRecentTabsCleared() {
        sp.edit()
                .putLong(KEY_RECENTS_CLEARED_AT, System.currentTimeMillis())
                .remove(KEY_DISMISSED_PKGS_JSON)
                .apply();
    }

    // =========================================================================
    // Dynamic Bar (информационная капсула вверху экрана) и индикаторы статуса
    // =========================================================================

    public boolean isDynamicBarEnabled() { return sp.getBoolean(KEY_DYNAMIC_BAR, true); }
    public void setDynamicBarEnabled(boolean v) { sp.edit().putBoolean(KEY_DYNAMIC_BAR, v).apply(); }

    public boolean isDynamicBarUnlock() { return sp.getBoolean(KEY_DBAR_UNLOCK, true); }
    public void setDynamicBarUnlock(boolean v) { sp.edit().putBoolean(KEY_DBAR_UNLOCK, v).apply(); }

    public boolean isDynamicBarCalls() { return sp.getBoolean(KEY_DBAR_CALLS, true); }
    public void setDynamicBarCalls(boolean v) { sp.edit().putBoolean(KEY_DBAR_CALLS, v).apply(); }

    public boolean isDynamicBarRecorder() { return sp.getBoolean(KEY_DBAR_RECORDER, true); }
    public void setDynamicBarRecorder(boolean v) { sp.edit().putBoolean(KEY_DBAR_RECORDER, v).apply(); }

    public boolean isDynamicBarCharging() { return sp.getBoolean(KEY_DBAR_CHARGING, true); }
    public void setDynamicBarCharging(boolean v) { sp.edit().putBoolean(KEY_DBAR_CHARGING, v).apply(); }

    public boolean isDynamicBarMedia() { return sp.getBoolean(KEY_DBAR_MEDIA, true); }
    public void setDynamicBarMedia(boolean v) { sp.edit().putBoolean(KEY_DBAR_MEDIA, v).apply(); }

    public int getNotifBadgeMode() {
        int v = sp.getInt(KEY_NOTIF_BADGE_MODE, NOTIF_BADGE_DOT);
        return (v >= NOTIF_BADGE_DOT && v <= NOTIF_BADGE_NONE) ? v : NOTIF_BADGE_DOT;
    }
    public void setNotifBadgeMode(int mode) {
        int safe = (mode >= NOTIF_BADGE_DOT && mode <= NOTIF_BADGE_NONE) ? mode : NOTIF_BADGE_DOT;
        sp.edit().putInt(KEY_NOTIF_BADGE_MODE, safe)
                .putBoolean(KEY_SHOW_NOTIF_DOTS, safe != NOTIF_BADGE_NONE)
                .apply();
    }

    public boolean isShowNetSpeed() { return sp.getBoolean(KEY_SHOW_NET_SPEED, true); }
    public void setShowNetSpeed(boolean v) { sp.edit().putBoolean(KEY_SHOW_NET_SPEED, v).apply(); }

    public boolean isShowBatteryPct() { return sp.getBoolean(KEY_SHOW_BATTERY_PCT, true); }
    public void setShowBatteryPct(boolean v) { sp.edit().putBoolean(KEY_SHOW_BATTERY_PCT, v).apply(); }

    public static final int DRAWER_SORT_AZ = 0;
    public static final int DRAWER_SORT_FREQ = 1;
    public static final int DRAWER_SORT_NEW = 2;

    public int getDrawerSortMode() {
        int m = sp.getInt("drawer_sort_mode", DRAWER_SORT_AZ);
        return (m >= 0 && m <= 2) ? m : DRAWER_SORT_AZ;
    }
    public void setDrawerSortMode(int m) {
        sp.edit().putInt("drawer_sort_mode", (m >= 0 && m <= 2) ? m : DRAWER_SORT_AZ).apply();
    }

    public boolean isHapticFeedbackEnabled() { return sp.getBoolean("haptic_feedback", true); }
    public void setHapticFeedbackEnabled(boolean v) { sp.edit().putBoolean("haptic_feedback", v).apply(); }

    // === Doxaiz 3.3: Масштаб иконок (85%–115%), размер подписей, док, зацикливание, Главная страница ===
    public static final int LABEL_SIZE_SMALL = 0;   // 10sp
    public static final int LABEL_SIZE_NORMAL = 1;  // 11.5sp
    public static final int LABEL_SIZE_LARGE = 2;   // 13sp

    public static final int DBAR_POS_AUTO = 0;      // Авто у выреза камеры
    public static final int DBAR_POS_BELOW = 1;     // Ниже строки состояния
    public static final int DBAR_POS_COMPACT = 2;   // Компактно у верхнего края

    public int getIconScalePercent() {
        if (!sp.contains("icon_scale_pct")) {
            int legacy = getIconSize();
            if (legacy == ICON_COMPACT) return 88;
            if (legacy == ICON_LARGE) return 112;
            return 100;
        }
        int pct = sp.getInt("icon_scale_pct", 100);
        return Math.max(85, Math.min(115, pct));
    }
    public void setIconScalePercent(int pct) {
        int safe = Math.max(85, Math.min(115, pct));
        int legacy = (safe <= 92) ? ICON_COMPACT : (safe >= 108 ? ICON_LARGE : ICON_STANDARD);
        sp.edit().putInt("icon_scale_pct", safe).putInt(KEY_ICON_SIZE, legacy).apply();
    }

    public int getLabelTextSizeMode() {
        int m = sp.getInt("label_text_size_mode", LABEL_SIZE_NORMAL);
        return (m >= 0 && m <= 2) ? m : LABEL_SIZE_NORMAL;
    }
    public void setLabelTextSizeMode(int mode) {
        sp.edit().putInt("label_text_size_mode", Math.max(0, Math.min(2, mode))).apply();
    }

    public float getLabelTextSizeSp() {
        int m = getLabelTextSizeMode();
        if (m == LABEL_SIZE_SMALL) return 10f;
        if (m == LABEL_SIZE_LARGE) return 13f;
        return 11.5f;
    }

    public boolean isShowDockLabels() { return sp.getBoolean("show_dock_labels", false); }
    public void setShowDockLabels(boolean v) { sp.edit().putBoolean("show_dock_labels", v).apply(); }

    public boolean isLoopHomePages() { return sp.getBoolean("loop_home_pages", true); }
    public void setLoopHomePages(boolean v) { sp.edit().putBoolean("loop_home_pages", v).apply(); }

    public int getHomePageIndex() { return Math.max(0, sp.getInt("home_page_index", 0)); }
    public void setHomePageIndex(int idx) { sp.edit().putInt("home_page_index", Math.max(0, idx)).apply(); }

    // === Doxaiz 3.3: Шторка (режим одной рукой, группировка уведомлений) и позиция Dynamic Bar ===
    public boolean isShadeOneHandMode() { return sp.getBoolean("shade_one_hand", false); }
    public void setShadeOneHandMode(boolean v) { sp.edit().putBoolean("shade_one_hand", v).apply(); }

    public boolean isShadeGroupNotifications() { return sp.getBoolean("shade_group_notif", true); }
    public void setShadeGroupNotifications(boolean v) { sp.edit().putBoolean("shade_group_notif", v).apply(); }

    public int getDynamicBarPosition() {
        int v = sp.getInt("dbar_position", DBAR_POS_AUTO);
        return (v >= 0 && v <= 2) ? v : DBAR_POS_AUTO;
    }
    public void setDynamicBarPosition(int pos) {
        sp.edit().putInt("dbar_position", Math.max(0, Math.min(2, pos))).apply();
    }

    // Doxaiz 3.4: Умное автоскрытие Dynamic Bar (зарядка 5 сек, пауза музыки 10 сек, скрытие свайпом вверх)
    public boolean isDynamicBarAutoHide() { return sp.getBoolean("dbar_auto_hide", true); }
    public void setDynamicBarAutoHide(boolean v) { sp.edit().putBoolean("dbar_auto_hide", v).apply(); }

    // === Расширенный Dynamic Bar и всплывающие баннеры ===
    // Стиль перелистывания страниц один — плавный (см. DesktopPagerView). Отдельная
    // настройка с выбором «Карточки/Затухание/Стек» удалена вместе с недостижимым кодом.

    public boolean isHeadsUpBannersEnabled() { return sp.getBoolean("heads_up_banners", true); }
    public void setHeadsUpBannersEnabled(boolean v) { sp.edit().putBoolean("heads_up_banners", v).apply(); }

    public boolean isDynamicBarLowBattery() { return sp.getBoolean("dbar_low_battery", true); }
    public void setDynamicBarLowBattery(boolean v) { sp.edit().putBoolean("dbar_low_battery", v).apply(); }

    public boolean isDynamicBarTimer() { return sp.getBoolean("dbar_timer", true); }
    public void setDynamicBarTimer(boolean v) { sp.edit().putBoolean("dbar_timer", v).apply(); }

    public boolean isDynamicBarHotspot() { return sp.getBoolean("dbar_hotspot", true); }
    public void setDynamicBarHotspot(boolean v) { sp.edit().putBoolean("dbar_hotspot", v).apply(); }

    public boolean isDynamicBarRinger() { return sp.getBoolean("dbar_ringer", true); }
    public void setDynamicBarRinger(boolean v) { sp.edit().putBoolean("dbar_ringer", v).apply(); }

    // === Doxaiz 4.0: Свайп вверх → Все приложения (с категориями) + Замок в Недавних ===
    public static final int SWIPE_UP_DRAWER = 0;
    public static final int SWIPE_UP_RECENTS = 1;
    public static final int SWIPE_UP_SHADE = 2;
    public static final int SWIPE_UP_NONE = 3;

    public int getSwipeUpAction() {
        int v = sp.getInt("swipe_up_action", SWIPE_UP_DRAWER);
        return (v >= 0 && v <= 3) ? v : SWIPE_UP_DRAWER;
    }

    public void setSwipeUpAction(int action) {
        sp.edit().putInt("swipe_up_action", Math.max(0, Math.min(3, action))).apply();
    }

    public Set<String> getLockedRecentsPackages() {
        Set<String> stored = sp.getStringSet("locked_recents_pkgs", null);
        return (stored != null) ? new HashSet<>(stored) : new HashSet<>();
    }

    public boolean isPackageLockedInRecents(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Set<String> stored = sp.getStringSet("locked_recents_pkgs", null);
        return stored != null && stored.contains(pkg);
    }

    public boolean togglePackageLockedInRecents(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Set<String> next = getLockedRecentsPackages();
        boolean locked;
        if (next.contains(pkg)) {
            next.remove(pkg);
            locked = false;
        } else {
            next.add(pkg);
            locked = true;
        }
        sp.edit().putStringSet("locked_recents_pkgs", next).apply();
        return locked;
    }

    public void removeLockedRecentPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> next = getLockedRecentsPackages();
        if (next.remove(pkg)) {
            sp.edit().putStringSet("locked_recents_pkgs", next).apply();
        }
    }

    public void setLockedRecentsPackages(Set<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) {
            sp.edit().remove("locked_recents_pkgs").apply();
        } else {
            sp.edit().putStringSet("locked_recents_pkgs", new HashSet<>(pkgs)).apply();
        }
    }

    public void markAllRecentTabsClearedExcept(Set<String> keepPkgs) {
        if (keepPkgs == null || keepPkgs.isEmpty()) {
            markAllRecentTabsCleared();
            return;
        }
        // Помечаем закрытыми только незакреплённые пакеты (читаем ключи прямо из usageCache в RAM без парсинга JSON)
        long now = System.currentTimeMillis();
        try {
            List<String> keysSnapshot;
            synchronized (usageLock) {
                Map<String, long[]> map = ensureUsageCacheLocked();
                if (map.isEmpty()) return;
                keysSnapshot = new ArrayList<>(map.keySet());
            }
            String dRaw = sp.getString(KEY_DISMISSED_PKGS_JSON, null);
            JSONObject dismissed = (dRaw != null && !dRaw.isEmpty()) ? new JSONObject(dRaw) : new JSONObject();
            for (String key : keysSnapshot) {
                int slash = key.indexOf('/');
                String pkg = (slash > 0) ? key.substring(0, slash) : key;
                if (!keepPkgs.contains(pkg)) {
                    dismissed.put(pkg, now);
                }
            }
            sp.edit().putString(KEY_DISMISSED_PKGS_JSON, dismissed.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Учёт трафика перенесён в {@link NetworkQualityHelper#accumulateTraffic}.
     *
     * Раньше здесь жил updateAndReadTrafficBytes(): он считал суточную и
     * месячную сумму по дельтам TrafficStats, но не вызывался НИ ОТКУДА — то
     * есть панель сети показывала только текущую скорость, а накопленные
     * значения в настройках никогда не обновлялись. Перенос в панель сети не
     * просто «оживил» код: там уже есть готовый снимок TrafficStats и
     * собственный троттлинг обновлений, поэтому учёт не добавляет ни одного
     * лишнего IPC.
     */

    // === Doxaiz 4.1: Ручные категории приложений ===
    private static final String KEY_CUSTOM_CAT_PREFIX = "custom_cat_";

    public int getCustomCategory(String packageName) {
        if (packageName == null || packageName.isEmpty()) return -1;
        return sp.getInt(KEY_CUSTOM_CAT_PREFIX + packageName, -1);
    }

    public void setCustomCategory(String packageName, int category) {
        if (packageName == null || packageName.isEmpty()) return;
        if (category <= AppCategoryHelper.CAT_ALL || category > AppCategoryHelper.CAT_SYSTEM) {
            sp.edit().remove(KEY_CUSTOM_CAT_PREFIX + packageName).apply();
        } else {
            sp.edit().putInt(KEY_CUSTOM_CAT_PREFIX + packageName, category).apply();
        }
    }

    public Map<String, Integer> getAllCustomCategories() {
        Map<String, Integer> out = new HashMap<>();
        Map<String, ?> all = sp.getAll();
        if (all == null) return out;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (k != null && k.startsWith(KEY_CUSTOM_CAT_PREFIX) && e.getValue() instanceof Integer) {
                int cat = (Integer) e.getValue();
                if (cat > AppCategoryHelper.CAT_ALL && cat <= AppCategoryHelper.CAT_SYSTEM) {
                    out.put(k.substring(KEY_CUSTOM_CAT_PREFIX.length()), cat);
                }
            }
        }
        return out;
    }

    public void clearAllCustomCategories() {
        SharedPreferences.Editor ed = sp.edit();
        Map<String, ?> all = sp.getAll();
        if (all != null) {
            for (String k : all.keySet()) {
                if (k != null && k.startsWith(KEY_CUSTOM_CAT_PREFIX)) ed.remove(k);
            }
        }
        ed.apply();
    }

    // === Doxaiz 4.3: Пользовательские разделы во «Все приложения» + скрытие стандартных ===
    private static final String KEY_CUSTOM_SECTIONS = "drawer_custom_sections_json";
    private static final String KEY_HIDDEN_STD_CATS = "drawer_hidden_std_cats";
    private List<CustomSection> customSectionsCache = null;
    private Set<Integer> hiddenStdCatsCache = null;

    private static List<CustomSection> cloneCustomSections(List<CustomSection> src) {
        List<CustomSection> copy = new ArrayList<>(src.size());
        for (CustomSection s : src) {
            if (s != null) {
                copy.add(new CustomSection(s.id, s.name, s.packages));
            }
        }
        return copy;
    }

    public List<CustomSection> getCustomSections() {
        synchronized (prefsCacheLock) {
            if (customSectionsCache == null) {
                customSectionsCache = CustomSection.fromJson(sp.getString(KEY_CUSTOM_SECTIONS, ""));
            }
            return cloneCustomSections(customSectionsCache);
        }
    }

    public void saveCustomSections(List<CustomSection> sections) {
        List<CustomSection> safe = (sections != null) ? cloneCustomSections(sections) : new ArrayList<>();
        synchronized (prefsCacheLock) {
            customSectionsCache = safe;
        }
        sp.edit().putString(KEY_CUSTOM_SECTIONS, CustomSection.toJson(safe)).apply();
    }

    public void removePackageFromAllCustomSections(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        List<CustomSection> sections = getCustomSections();
        boolean changed = false;
        for (CustomSection s : sections) {
            if (s != null && s.packages.remove(pkg)) {
                changed = true;
            }
        }
        if (changed) saveCustomSections(sections);
    }

    public String getCustomSectionsRawJson() {
        return sp.getString(KEY_CUSTOM_SECTIONS, "");
    }

    /**
     * Вариант для импорта бэкапа: пишет в хранилище, но НЕ трогает кэш чтения —
     * при откате кэш должен остаться согласованным с восстановленными настройками
     * (см. invalidateCachesAfterImport()).
     */
    void setCustomSectionsRawJsonForImport(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            sp.edit().remove(KEY_CUSTOM_SECTIONS).apply();
        } else {
            // Та же строгая санитизация лимитов MAX_SECTIONS/MAX_NAME_LEN, но без кэша.
            sp.edit().putString(KEY_CUSTOM_SECTIONS,
                    CustomSection.toJson(CustomSection.fromJson(raw))).apply();
        }
    }

    public Set<Integer> getHiddenStdCategories() {
        synchronized (prefsCacheLock) {
            if (hiddenStdCatsCache == null) {
                hiddenStdCatsCache = CustomSection.parseHiddenStdCategories(sp.getString(KEY_HIDDEN_STD_CATS, ""));
            }
            return new HashSet<>(hiddenStdCatsCache);
        }
    }

    public void setHiddenStdCategories(Set<Integer> hidden) {
        Set<Integer> copy = (hidden != null) ? new HashSet<>(hidden) : new HashSet<>();
        synchronized (prefsCacheLock) {
            hiddenStdCatsCache = copy;
        }
        sp.edit().putString(KEY_HIDDEN_STD_CATS, CustomSection.encodeHiddenStdCategories(copy)).apply();
    }

    public boolean isStdCategoryHidden(int cat) {
        return getHiddenStdCategories().contains(cat);
    }

    public void setStdCategoryHidden(int cat, boolean hidden) {
        if (cat < AppCategoryHelper.CAT_COMM || cat > AppCategoryHelper.CAT_SYSTEM) return;
        Set<Integer> cur = getHiddenStdCategories();
        if (hidden) cur.add(cat);
        else cur.remove(cat);
        setHiddenStdCategories(cur);
    }

    public String getHiddenStdCategoriesRaw() {
        return sp.getString(KEY_HIDDEN_STD_CATS, "");
    }

    void setHiddenStdCatsRawForImport(String raw) {
        // Санитизация та же, что и в обычном пути, но без записи в кэш чтения.
        Set<Integer> parsed = CustomSection.parseHiddenStdCategories(raw);
        sp.edit().putString(KEY_HIDDEN_STD_CATS, CustomSection.encodeHiddenStdCategories(parsed)).apply();
    }

    public boolean isAutoNetworkEnabled() { return isAutoNetworkAssist(); }
    public void setAutoNetworkEnabled(boolean v) { setAutoNetworkAssist(v); }

    // === Doxaiz 5.2: Стиль Аниме × Дунхуа (палитры, встроенные обои, декоративные рамки и частицы) ===
    public static final int STYLE_SAKURA_JADE  = 0; // Сакура × Нефрит (Аниме × Дунхуа, по умолчанию)
    public static final int STYLE_CYBER_ANIME  = 1; // Кибер-Аниме Неон (Токио / Sci-Fi)
    public static final int STYLE_CLASSIC_LIGHT = 3;
    public static final int STYLE_CLASSIC_DARK = 2; // Классический графит (#000000)

    public static final int WALLPAPER_KURUMI_XIAOBAI = 0; // Встроенные обои Куруми × Сяо Бай (Дунхуа)
    public static final int WALLPAPER_SYSTEM         = 1; // Системные обои телефона

    public static final int WALLPAPER_DIM_LIGHT  = 0;
    public static final int WALLPAPER_DIM_MEDIUM = 1;
    public static final int WALLPAPER_DIM_STRONG = 2;

    /**
     * Приводит номер стиля к допустимому.
     *
     * Раньше здесь оставалось только «светлая или тёмная», а Сакура и
     * Кибер-Аниме сворачивались в графит. В интерфейсе выбора этих стилей не
     * было, поэтому усечение выглядело безобидным — но оно же делало
     * недостижимыми готовые палитры, которые уже посчитаны и проверены
     * контрастом (GlassTheme.createResolved). Теперь сохраняются все четыре.
     */
    public static int sanitizeVisualStyle(int style) {
        switch (style) {
            case STYLE_SAKURA_JADE:
            case STYLE_CYBER_ANIME:
            case STYLE_CLASSIC_DARK:
            case STYLE_CLASSIC_LIGHT:
                return style;
            default:
                return STYLE_CLASSIC_DARK;
        }
    }

    public static int sanitizeWallpaperMode(int mode) {
        return (mode == WALLPAPER_SYSTEM) ? WALLPAPER_SYSTEM : WALLPAPER_KURUMI_XIAOBAI;
    }

    public static int sanitizeWallpaperDim(int dim) {
        return (dim >= WALLPAPER_DIM_LIGHT && dim <= WALLPAPER_DIM_STRONG) ? dim : WALLPAPER_DIM_MEDIUM;
    }

    public int getVisualStyle() {
        return sanitizeVisualStyle(sp.getInt("simple_visual_style", STYLE_CLASSIC_DARK));
    }

    public void setVisualStyle(int style) {
        sp.edit().putInt("simple_visual_style", sanitizeVisualStyle(style)).apply();
    }

    public int getWallpaperMode() {
        return sanitizeWallpaperMode(sp.getInt("simple_wallpaper_mode", WALLPAPER_KURUMI_XIAOBAI));
    }

    public void setWallpaperMode(int mode) {
        sp.edit().putInt("simple_wallpaper_mode", sanitizeWallpaperMode(mode)).apply();
    }

    public int getWallpaperDimLevel() {
        return sanitizeWallpaperDim(sp.getInt("wallpaper_dim", WALLPAPER_DIM_MEDIUM));
    }

    public void setWallpaperDimLevel(int dim) {
        sp.edit().putInt("wallpaper_dim", sanitizeWallpaperDim(dim)).apply();
    }

    public boolean isAnimeOrnamentsEnabled() {
        return sp.getBoolean("anime_ornaments", true);
    }

    // isAnimeOrnamentsEnabled() используется: MainActivity передаёт значение в
    // GlassTheme.create(...). Записи в настройку нет намеренно — выключателя в
    // интерфейсе тоже нет, а мёртвый сеттер создавал ложное впечатление
    // работающей настройки. Частицы (anime_particles) не создаются нигде: слой
    // AnimeParticlesView остался от прежней версии и в дерево видов не
    // добавляется, поэтому и настроек у него нет.

    // =========================================================================
    // Транзакционный импорт резервной копии (BackupHelper)
    //
    // Импорт восстанавливает настройки по одной (setXxx → apply), поэтому исключение
    // в середине разбора раньше оставляло пользователя с частично применённым
    // состоянием — в том числе с уже очищенным списком скрытых приложений.
    // Теперь BackupHelper снимает полный снимок настроек и при любой ошибке
    // возвращает их одной атомарной записью.
    // =========================================================================

    /** Полный снимок настроек для отката неудачного импорта. */
    @SuppressWarnings("unchecked")
    Map<String, Object> snapshotForRollback() {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Set) {
                copy.put(e.getKey(), new HashSet<>((Set<String>) v));
            } else {
                copy.put(e.getKey(), v);
            }
        }
        return copy;
    }

    /** Возвращает настройки к снимку: одна атомарная запись + сброс кэшей чтения. */
    @SuppressWarnings("unchecked")
    boolean restoreFromRollback(Map<String, Object> snapshot) {
        if (snapshot == null) return false;
        try {
            SharedPreferences.Editor ed = sp.edit();
            ed.clear();
            for (Map.Entry<String, Object> e : snapshot.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) ed.putString(e.getKey(), (String) v);
                else if (v instanceof Set) {
                    ed.putStringSet(e.getKey(), new HashSet<>((Set<String>) v));
                } else if (v instanceof Integer) ed.putInt(e.getKey(), (Integer) v);
                else if (v instanceof Long) ed.putLong(e.getKey(), (Long) v);
                else if (v instanceof Boolean) ed.putBoolean(e.getKey(), (Boolean) v);
                else if (v instanceof Float) ed.putFloat(e.getKey(), (Float) v);
            }
            boolean ok = ed.commit();
            invalidateCachesAfterImport();
            return ok;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Сбрасывает кэши чтения (скрытые приложения, разделы, категории, журнал запусков). */
    void invalidateCachesAfterImport() {
        synchronized (prefsCacheLock) {
            hiddenAppsCache = null;
            customSectionsCache = null;
            hiddenStdCatsCache = null;
        }
        synchronized (usageLock) {
            usageCache = null;
        }
    }
}
