package com.doxaiz.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Экспорт и импорт точной раскладки Doxaiz (страницы, координаты клеток, папки, док и настройки)
 * в единый JSON-файл через стандартный системный диалог Android (без разрешений на хранилище).
 * Включает строгую проверку диапазонов, длины строк и лимитов количества при импорте.
 */
public final class BackupHelper {

    public static final int MAX_BACKUP_BYTES = 512 * 1024; // 512 КБ — защита от OOM при выборе неверного файла
    private static final int MAX_LIST_ENTRIES = 500;
    private static final int MAX_LABEL_LEN = 64;

    private BackupHelper() {}

    /**
     * Собирает JSON резервной копии.
     *
     * @param includeHidden включать ли список скрытых приложений, пользовательские
     *                      категории и переименования. Это приватные данные: если
     *                      пользователь хочет поделиться только раскладкой
     *                      (скриншот, перенос на другой телефон), их не должно быть
     *                      в файле вовсе — раньше они попадали туда всегда (F-06).
     */
    public static String exportBackupJson(Prefs prefs, DesktopModel desktop, boolean includeHidden) {
        try {
            desktop.save();
            JSONObject root = new JSONObject();
            root.put("app", "Doxaiz");
            root.put("format", 2);
            // Явный признак для импорта и для человека, открывшего файл:
            // отсутствие ключа не должно трактоваться как «скрытых приложений нет».
            root.put("privacyIncluded", includeHidden);

            String desktopJson = prefs.getDesktopJson();
            if (desktopJson != null && !desktopJson.isEmpty()) {
                root.put("desktop", new JSONObject(desktopJson));
            }

            JSONObject s = new JSONObject();
            s.put("visualStyle", prefs.getVisualStyle());
            s.put("wallpaperMode", prefs.getWallpaperMode());
            s.put("wallpaperDim", prefs.getWallpaperDimLevel());
            s.put("opacity", prefs.getGlassOpacity());
            s.put("columns", prefs.getColumns());
            s.put("iconSize", prefs.getIconSize());
            s.put("showHomeLabels", prefs.isShowHomeLabels());
            s.put("homeBottomAlign", prefs.isHomeBottomAligned());
            s.put("drawerMode", prefs.getDrawerMode());
            s.put("drawerStyle", prefs.getDrawerStyle());
            s.put("shadeStyle", prefs.getShadeStyle());
            s.put("shadeTiles", prefs.getShadeTilesCsv());
            s.put("showPageDots", prefs.isShowPageDots());
            s.put("showAlphabetBar", prefs.isShowAlphabetBar());
            s.put("smoothAnimations", prefs.isSmoothAnimationsEnabled());
            s.put("shadeSplitSwipe", prefs.isShadeSplitSwipe());
            s.put("hideNotifText", prefs.isHideNotifTextInShade());
            s.put("dynamicBar", prefs.isDynamicBarEnabled());
            s.put("dbarUnlock", prefs.isDynamicBarUnlock());
            s.put("dbarCalls", prefs.isDynamicBarCalls());
            s.put("dbarRecorder", prefs.isDynamicBarRecorder());
            s.put("dbarCharging", prefs.isDynamicBarCharging());
            s.put("dbarMedia", prefs.isDynamicBarMedia());
            s.put("notifBadgeMode", prefs.getNotifBadgeMode());
            s.put("showNetSpeed", prefs.isShowNetSpeed());
            s.put("showBatteryPct", prefs.isShowBatteryPct());
            s.put("desktopLocked", prefs.isDesktopLocked());
            s.put("autoAddNew", prefs.isAutoAddNewApps());
            s.put("hideTopSettings", prefs.isHideTopSettingsBtn());
            s.put("showDockDrawer", prefs.isShowDockDrawerBtn());
            s.put("swipeDown", prefs.getSwipeDownAction());
            s.put("doubleTap", prefs.getDoubleTapAction());
            s.put("showMemory", prefs.isShowDrawerMemory());
            s.put("recentsRamPos", prefs.getRecentsRamPosition());
            s.put("autoNetworkAssist", prefs.isAutoNetworkAssist());
            s.put("autoNetMode", prefs.getAutoNetworkMode());
            s.put("autoNetWeakWifi", prefs.isAutoNetworkWeakWifi());
            if (includeHidden) s.put("protectHidden", prefs.isProtectHiddenApps());
            s.put("showDrawerCategories", prefs.isShowDrawerCategories());
            s.put("showNotifDots", prefs.isShowNotificationDots());
            s.put("showRecent", prefs.isShowRecentApps());
            s.put("autoKeyboard", prefs.isAutoKeyboardDrawer());
            s.put("autoLaunchSingle", prefs.isAutoLaunchSingleMatch());
            s.put("iconScalePct", prefs.getIconScalePercent());
            s.put("labelSizeMode", prefs.getLabelTextSizeMode());
            s.put("showDockLabels", prefs.isShowDockLabels());
            s.put("loopHomePages", prefs.isLoopHomePages());
            s.put("homePageIndex", prefs.getHomePageIndex());
            s.put("shadeOneHand", prefs.isShadeOneHandMode());
            s.put("shadeGroupNotif", prefs.isShadeGroupNotifications());
            s.put("dbarPosition", prefs.getDynamicBarPosition());
            s.put("dbarAutoHide", prefs.isDynamicBarAutoHide());
            s.put("headsUpBanners", prefs.isHeadsUpBannersEnabled());
            s.put("dbarLowBattery", prefs.isDynamicBarLowBattery());
            s.put("dbarTimer", prefs.isDynamicBarTimer());
            s.put("dbarHotspot", prefs.isDynamicBarHotspot());
            if (includeHidden) s.put("hiddenStdCats", prefs.getHiddenStdCategoriesRaw());
            s.put("hapticFeedback", prefs.isHapticFeedbackEnabled());
            root.put("settings", s);

            String customSecRaw = prefs.getCustomSectionsRawJson();
            if (customSecRaw != null && !customSecRaw.isEmpty()) {
                root.put("customSections", new JSONArray(customSecRaw));
            }

            if (includeHidden) {
                JSONArray hiddenArr = new JSONArray();
                Set<String> hidden = prefs.getHiddenApps();
                for (String k : hidden) hiddenArr.put(k);
                root.put("hidden", hiddenArr);

                JSONObject renamesObj = new JSONObject();
                for (Map.Entry<String, String> e : prefs.getCustomLabels().entrySet()) {
                    renamesObj.put(e.getKey(), e.getValue());
                }
                root.put("renames", renamesObj);
            }

            JSONObject swipeUpObj = new JSONObject();
            for (Map.Entry<String, String> e : prefs.getAllIconSwipeUpTargets().entrySet()) {
                swipeUpObj.put(e.getKey(), e.getValue());
            }
            root.put("iconSwipeUp", swipeUpObj);

            JSONArray lockedArr = new JSONArray();
            for (String pkg : prefs.getLockedRecentsPackages()) {
                if (pkg != null && !pkg.isEmpty()) lockedArr.put(pkg);
            }
            root.put("lockedRecents", lockedArr);

            JSONObject customCatObj = new JSONObject();
            for (Map.Entry<String, Integer> e : prefs.getAllCustomCategories().entrySet()) {
                customCatObj.put(e.getKey(), e.getValue());
            }
            root.put("customCategories", customCatObj);

            // Таблица переименований и категории собираются по именам пакетов,
            // то есть тоже являются сведениями о приложениях пользователя.
            // При экспорте «только раскладки» они не выгружаются.
            if (!includeHidden) {
                root.remove("renames");
                root.remove("customCategories");
            }

            return root.toString(2);
        } catch (Throwable t) {
            // Без этого пользователь видел «Ошибка формирования копии» без причины.
            Diagnostics.record("BackupHelper.export", t);
            return null;
        }
    }

    /**
     * Строгая предварительная проверка структуры раскладки до изменения текущих настроек пользователя.
     * Защищает от случайного выбора чужого или повреждённого JSON-файла.
     */
    static boolean validateDesktopJson(JSONObject desk) {
        if (desk == null) return false;
        JSONArray pages = desk.optJSONArray("pages");
        if (pages == null || pages.length() == 0 || pages.length() > DesktopModel.MAX_PAGES) return false;
        // A genuinely empty desktop is valid, but an arbitrary {"pages":[[]]} is not a backup.
        if (desk.has("dock") && desk.optJSONArray("dock") == null) return false;
        JSONArray dock = desk.optJSONArray("dock");
        int itemCount = 0;
        int memberCount = 0;
        for (int p = 0; p < pages.length(); p++) {
            JSONArray page = pages.optJSONArray(p);
            if (page == null || (itemCount += page.length()) > MAX_LIST_ENTRIES) return false;
            for (int i = 0; i < page.length(); i++) {
                JSONObject item = page.optJSONObject(i);
                if (item == null) return false;
                String type = item.optString("type", "app");
                if ("folder".equals(type)) {
                    JSONArray members = item.optJSONArray("apps");
                    if (members == null || (memberCount += members.length()) > 4096) return false;
                    if (item.optString("id", "").length() > 512 || item.optString("name", "").length() > 512) return false;
                    for (int j = 0; j < members.length(); j++) {
                        Object key = members.opt(j);
                        if (!(key instanceof String) || ((String) key).isEmpty() || ((String) key).length() > 1024) return false;
                    }
                } else if ("app".equals(type)) {
                    Object key = item.opt("key");
                    if (!(key instanceof String) || ((String) key).isEmpty() || ((String) key).length() > 1024) return false;
                } else return false;
            }
        }
        if (dock != null) {
            if (dock.length() > 12) return false;
            for (int i = 0; i < dock.length(); i++) {
                Object key = dock.opt(i);
                if (!(key instanceof String) || ((String) key).isEmpty() || ((String) key).length() > 1024) return false;
            }
        }
        return itemCount > 0 || (dock != null);
    }

    /**
     * Doxaiz 5.1: Защита от обхода приватного пространства через поддельный JSON-файл резервной копии:
     * если защита скрытых приложений включена, без подтверждения отпечатком/PIN её нельзя молча выключить.
     */
    static boolean resolveImportedProtectHidden(boolean currentlyProtected, boolean requestedInBackup, boolean authVerified) {
        if (currentlyProtected && !authVerified) return true;
        return requestedInBackup;
    }

    /** Строгая санитизация списка плиток шторки при импорте произвольного JSON (только [a-z0-9_], без пустых токенов). */
    static String sanitizeTileCsv(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 256) return "";
        StringBuilder out = new StringBuilder(trimmed.length());
        String[] parts = trimmed.split(",");
        for (String part : parts) {
            StringBuilder token = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                    token.append(c);
                }
            }
            if (token.length() > 0) {
                if (out.length() > 0) out.append(',');
                out.append(token);
            }
        }
        return out.toString();
    }

    public static boolean importBackupJson(String raw, Prefs prefs, DesktopModel desktop) {
        return importBackupJson(raw, prefs, desktop, false);
    }

    /**
     * Импорт резервной копии со снимком/откатом.
     *
     * Настройки восстанавливаются по одной (setXxx → apply), поэтому исключение в
     * середине разбора раньше оставляло пользователя с частично применённым
     * состоянием — включая уже очищенный список скрытых приложений. Теперь перед
     * разбором снимается полный снимок SharedPreferences, а любая ошибка
     * возвращает настройки одной атомарной записью и перечитывает раскладку с диска.
     */
    public static boolean importBackupJson(String raw, Prefs prefs, DesktopModel desktop, boolean privacyAuthVerified) {
        if (raw == null || raw.trim().isEmpty() || raw.length() > MAX_BACKUP_BYTES) return false;
        final Map<String, Object> rollback = (prefs != null) ? prefs.snapshotForRollback() : null;
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject desk = root.optJSONObject("desktop");
            if (desk == null && root.has("pages")) {
                desk = root;
            }
            if (!validateDesktopJson(desk)) {
                return false;
            }

            final boolean wasProtected = prefs.isProtectHiddenApps();
            final boolean allowPrivacyChange = !wasProtected || privacyAuthVerified;

            prefs.setDesktopJson(desk.toString());
            // desktop.load() раньше стоял здесь и оставлял раскладку уже подменённой даже
            // при провале последующего разбора. Теперь считаем страницы из JSON, а сам
            // load() выполняется один раз после успешного применения всех настроек.
            int actualPageCount = Math.max(1, (desk.optJSONArray("pages") != null)
                    ? desk.optJSONArray("pages").length() : 1);

            JSONObject s = root.optJSONObject("settings");
            if (s != null) {
                if (s.has("visualStyle")) prefs.setVisualStyle(s.optInt("visualStyle", prefs.getVisualStyle()));
                if (s.has("wallpaperMode")) prefs.setWallpaperMode(s.optInt("wallpaperMode", prefs.getWallpaperMode()));
                if (s.has("wallpaperDim")) prefs.setWallpaperDimLevel(s.optInt("wallpaperDim", prefs.getWallpaperDimLevel()));
                if (s.has("opacity")) prefs.setGlassOpacity(clamp(s.optInt("opacity", prefs.getGlassOpacity()), 0, 2));
                if (s.has("columns")) prefs.setColumns(s.optInt("columns", prefs.getColumns()) == 5 ? 5 : 4);
                if (s.has("iconSize")) prefs.setIconSize(clamp(s.optInt("iconSize", prefs.getIconSize()), 0, 2));
                if (s.has("showHomeLabels")) prefs.setShowHomeLabels(s.optBoolean("showHomeLabels", true));
                if (s.has("homeBottomAlign")) prefs.setHomeBottomAligned(s.optBoolean("homeBottomAlign", true));
                if (s.has("drawerMode")) prefs.setDrawerMode(clamp(s.optInt("drawerMode", Prefs.DRAWER_GRID), 0, 1));
                if (s.has("drawerStyle")) prefs.setDrawerStyle(clamp(s.optInt("drawerStyle", Prefs.DRAWER_STYLE_INFINIX), 0, 1));
                if (s.has("shadeStyle")) prefs.setShadeStyle(clamp(s.optInt("shadeStyle", Prefs.SHADE_STYLE_INFINIX), 0, 2));
                if (s.has("shadeTiles")) {
                    String csv = sanitizeTileCsv(s.optString("shadeTiles", Prefs.DEFAULT_SHADE_TILES));
                    if (!csv.isEmpty()) prefs.setShadeTilesCsv(csv);
                }
                if (s.has("showPageDots")) prefs.setShowPageDots(s.optBoolean("showPageDots", true));
                if (s.has("showAlphabetBar")) prefs.setShowAlphabetBar(s.optBoolean("showAlphabetBar", true));
                if (s.has("smoothAnimations")) prefs.setSmoothAnimationsEnabled(s.optBoolean("smoothAnimations", true));
                if (s.has("shadeSplitSwipe")) prefs.setShadeSplitSwipe(s.optBoolean("shadeSplitSwipe", true));
                if (s.has("hideNotifText")) prefs.setHideNotifTextInShade(s.optBoolean("hideNotifText", false));
                if (s.has("dynamicBar")) prefs.setDynamicBarEnabled(s.optBoolean("dynamicBar", true));
                if (s.has("dbarUnlock")) prefs.setDynamicBarUnlock(s.optBoolean("dbarUnlock", true));
                if (s.has("dbarCalls")) prefs.setDynamicBarCalls(s.optBoolean("dbarCalls", true));
                if (s.has("dbarRecorder")) prefs.setDynamicBarRecorder(s.optBoolean("dbarRecorder", true));
                if (s.has("dbarCharging")) prefs.setDynamicBarCharging(s.optBoolean("dbarCharging", true));
                if (s.has("dbarMedia")) prefs.setDynamicBarMedia(s.optBoolean("dbarMedia", true));
                if (s.has("notifBadgeMode")) prefs.setNotifBadgeMode(clamp(s.optInt("notifBadgeMode", Prefs.NOTIF_BADGE_DOT), 0, 3));
                if (s.has("showNetSpeed")) prefs.setShowNetSpeed(s.optBoolean("showNetSpeed", true));
                if (s.has("showBatteryPct")) prefs.setShowBatteryPct(s.optBoolean("showBatteryPct", true));
                if (s.has("desktopLocked")) prefs.setDesktopLocked(s.optBoolean("desktopLocked", false));
                if (s.has("autoAddNew")) prefs.setAutoAddNewApps(s.optBoolean("autoAddNew", true));
                if (s.has("hideTopSettings")) prefs.setHideTopSettingsBtn(s.optBoolean("hideTopSettings", false));
                if (s.has("showDockDrawer")) prefs.setShowDockDrawerBtn(s.optBoolean("showDockDrawer", false));
                if (s.has("swipeDown")) prefs.setSwipeDownAction(clamp(s.optInt("swipeDown", Prefs.GESTURE_NOTIFICATIONS), 0, 7));
                if (s.has("doubleTap")) prefs.setDoubleTapAction(clamp(s.optInt("doubleTap", Prefs.GESTURE_SEARCH), 0, 7));
                if (s.has("showMemory")) prefs.setShowDrawerMemory(s.optBoolean("showMemory", true));
                if (s.has("recentsRamPos")) prefs.setRecentsRamPosition(clamp(s.optInt("recentsRamPos", Prefs.RECENTS_RAM_POS_DEFAULT), 0, 2));
                if (s.has("autoNetworkAssist")) prefs.setAutoNetworkAssist(s.optBoolean("autoNetworkAssist", true));
                if (s.has("autoNetMode")) prefs.setAutoNetworkMode(clamp(s.optInt("autoNetMode", Prefs.NET_MODE_BALANCED), 0, 2));
                if (s.has("autoNetWeakWifi")) prefs.setAutoNetworkWeakWifi(s.optBoolean("autoNetWeakWifi", true));
                if (s.has("protectHidden")) {
                    prefs.setProtectHiddenApps(resolveImportedProtectHidden(
                            wasProtected, s.optBoolean("protectHidden", false), privacyAuthVerified));
                }
                if (s.has("showDrawerCategories")) prefs.setShowDrawerCategories(s.optBoolean("showDrawerCategories", true));
                if (s.has("showNotifDots")) prefs.setShowNotificationDots(s.optBoolean("showNotifDots", true));
                if (s.has("showRecent")) prefs.setShowRecentApps(s.optBoolean("showRecent", true));
                if (s.has("autoKeyboard")) prefs.setAutoKeyboardDrawer(s.optBoolean("autoKeyboard", false));
                if (s.has("autoLaunchSingle")) prefs.setAutoLaunchSingleMatch(s.optBoolean("autoLaunchSingle", false));
                if (s.has("iconScalePct")) prefs.setIconScalePercent(clamp(s.optInt("iconScalePct", 100), 85, 115));
                if (s.has("labelSizeMode")) prefs.setLabelTextSizeMode(clamp(s.optInt("labelSizeMode", Prefs.LABEL_SIZE_NORMAL), 0, 2));
                if (s.has("showDockLabels")) prefs.setShowDockLabels(s.optBoolean("showDockLabels", false));
                if (s.has("loopHomePages")) prefs.setLoopHomePages(s.optBoolean("loopHomePages", true));
                if (s.has("homePageIndex")) prefs.setHomePageIndex(clamp(s.optInt("homePageIndex", 0), 0, actualPageCount - 1));
                if (s.has("shadeOneHand")) prefs.setShadeOneHandMode(s.optBoolean("shadeOneHand", false));
                if (s.has("shadeGroupNotif")) prefs.setShadeGroupNotifications(s.optBoolean("shadeGroupNotif", true));
                if (s.has("dbarPosition")) prefs.setDynamicBarPosition(clamp(s.optInt("dbarPosition", Prefs.DBAR_POS_AUTO), 0, 2));
                if (s.has("dbarAutoHide")) prefs.setDynamicBarAutoHide(s.optBoolean("dbarAutoHide", true));
                if (s.has("headsUpBanners")) prefs.setHeadsUpBannersEnabled(s.optBoolean("headsUpBanners", true));
                if (s.has("dbarLowBattery")) prefs.setDynamicBarLowBattery(s.optBoolean("dbarLowBattery", true));
                if (s.has("dbarTimer")) prefs.setDynamicBarTimer(s.optBoolean("dbarTimer", true));
                if (s.has("dbarHotspot")) prefs.setDynamicBarHotspot(s.optBoolean("dbarHotspot", true));
                if (s.has("hiddenStdCats")) prefs.setHiddenStdCatsRawForImport(s.optString("hiddenStdCats", ""));
                if (s.has("hapticFeedback")) prefs.setHapticFeedbackEnabled(s.optBoolean("hapticFeedback", true));
            }

            JSONArray customSecArr = root.optJSONArray("customSections");
            if (customSecArr != null) {
                // Строгая санитизация лимитов MAX_SECTIONS и MAX_NAME_LEN при импорте произвольного JSON
                prefs.setCustomSectionsRawJsonForImport(
                        CustomSection.toJson(CustomSection.fromJson(customSecArr.toString())));
            }

            JSONArray hiddenArr = root.optJSONArray("hidden");
            if (hiddenArr != null && allowPrivacyChange) {
                prefs.clearHiddenApps();
                int lim = Math.min(hiddenArr.length(), MAX_LIST_ENTRIES);
                for (int i = 0; i < lim; i++) {
                    String k = hiddenArr.optString(i, "");
                    if (!k.isEmpty() && k.length() <= 180) prefs.hideApp(k);
                }
            }

            JSONObject renamesObj = root.optJSONObject("renames");
            if (renamesObj != null) {
                prefs.clearAllCustomLabels();
                int count = 0;
                Iterator<String> it = renamesObj.keys();
                while (it.hasNext() && count < MAX_LIST_ENTRIES) {
                    String k = it.next();
                    String val = renamesObj.optString(k, "").trim();
                    if (!k.isEmpty() && k.length() <= 180 && !val.isEmpty()) {
                        if (val.length() > MAX_LABEL_LEN) val = val.substring(0, MAX_LABEL_LEN);
                        prefs.setCustomLabel(k, val);
                        count++;
                    }
                }
            }

            JSONObject swipeUpObj = root.optJSONObject("iconSwipeUp");
            if (swipeUpObj != null) {
                prefs.clearAllIconSwipeUpTargets();
                int count = 0;
                Iterator<String> it = swipeUpObj.keys();
                while (it.hasNext() && count < MAX_LIST_ENTRIES) {
                    String k = it.next();
                    String val = swipeUpObj.optString(k, "").trim();
                    if (!k.isEmpty() && k.length() <= 180 && !val.isEmpty() && val.length() <= 180) {
                        prefs.setIconSwipeUpTarget(k, val);
                        count++;
                    }
                }
            }

            JSONArray lockedArr = root.optJSONArray("lockedRecents");
            if (lockedArr != null) {
                Set<String> restoredLocked = new HashSet<>();
                int lim = Math.min(lockedArr.length(), MAX_LIST_ENTRIES);
                for (int i = 0; i < lim; i++) {
                    String pkg = lockedArr.optString(i, "").trim();
                    if (!pkg.isEmpty() && pkg.length() <= 180) {
                        restoredLocked.add(pkg);
                    }
                }
                prefs.setLockedRecentsPackages(restoredLocked);
            }

            JSONObject customCatObj = root.optJSONObject("customCategories");
            if (customCatObj != null) {
                prefs.clearAllCustomCategories();
                int count = 0;
                Iterator<String> it = customCatObj.keys();
                while (it.hasNext() && count < MAX_LIST_ENTRIES) {
                    String pkg = it.next();
                    int cat = customCatObj.optInt(pkg, -1);
                    if (pkg != null && !pkg.isEmpty() && pkg.length() <= 180
                            && cat > AppCategoryHelper.CAT_ALL && cat <= AppCategoryHelper.CAT_SYSTEM) {
                        prefs.setCustomCategory(pkg, cat);
                        count++;
                    }
                }
            }
            // Единственная перезагрузка раскладки — после того, как все настройки применены.
            desktop.load();
            desktop.save(); // Queue a fresh recovery copy instead of retaining an older layout.
            prefs.invalidateCachesAfterImport();
            return true;
        } catch (Throwable t) {
            // Импорт откатился — раньше пользователь видел только «не удалось»,
            // без понимания, на каком шаге и почему (F-07).
            Diagnostics.record("BackupHelper.import", t);
            rollbackImportedState(prefs, desktop, rollback);
            return false;
        }
    }

    /** Есть ли в JSON хоть одна известная секция — дешёвая предварительная проверка. */
    static boolean looksLikeBackup(JSONObject root) {
        if (root == null) return false;
        return root.has("pages") || root.has("desktop") || root.has("settings")
                || root.has("hidden") || root.has("customSections");
    }

    /**
     * Быстрая валидация выбранного файла бэкапа ДО снятия снимка настроек.
     *
     * Снимок SharedPreferences (snapshotForRollback) копирует все ключи и множества
     * в память, поэтому снимать его для заведомо чужого/битого файла бессмысленно:
     * сначала дешёвый разбор и проверка структуры, и только затем реальный импорт.
     */
    public static boolean looksLikeValidBackup(String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.length() > MAX_BACKUP_BYTES) return false;
        try {
            JSONObject root = new JSONObject(raw);
            if (!looksLikeBackup(root)) return false;
            JSONObject desk = root.optJSONObject("desktop");
            if (desk == null && root.has("pages")) desk = root;
            return validateDesktopJson(desk);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Откат неудачного импорта: настройки — из снимка, раскладка — с диска. */
    private static void rollbackImportedState(Prefs prefs, DesktopModel desktop, Map<String, Object> snapshot) {
        if (prefs != null && snapshot != null) prefs.restoreFromRollback(snapshot);
        try {
            if (desktop != null) desktop.load();
        } catch (Throwable ignored) {
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
