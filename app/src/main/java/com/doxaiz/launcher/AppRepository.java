package com.doxaiz.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.LruCache;
import android.widget.ImageView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class AppRepository {

    public interface Listener {
        void onAppsUpdated(List<AppEntry> visibleApps, List<AppEntry> hiddenApps,
                           Map<String, AppEntry> byKey, String newlyInstalledPackage);
    }

    public interface PackageRemovedListener {
        void onPackageUninstalled(String packageName, long userSerial);
    }

    private final Context appContext;
    private final Prefs prefs;
    private final LauncherApps launcherApps;
    private final UserManager userManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Раздельные пулы для максимальной отзывчивости 120 FPS:
    // - iconExecutor: приоритетная загрузка видимых иконок (не блокируется записью на диск и reload)
    // - bgExecutor: сканирование пакетов, предпрогрев и асинхронное сжатие PNG/WEBP на флеш
    private final ExecutorService iconExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
            r -> {
                Thread t = new Thread(() -> {
                    try { Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE); } catch (Throwable ignored) {}
                    r.run();
                }, "doxaiz-icon-fast");
                t.setDaemon(true);
                return t;
            }
    );
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) {}
            r.run();
        }, "doxaiz-repo-bg");
        t.setDaemon(true);
        return t;
    });
    // 2-уровневый кэш иконок: ОЗУ LruCache (до 10 МБ) + постоянный дисковый кэш IconDiskCache
    private final LruCache<String, Drawable> iconCache;
    private final IconDiskCache diskCache;
    // Атомарно подменяемые неизменяемые снимки — исключают окно пустой карты между clear() и putAll()
    private volatile Map<String, LauncherActivityInfo> activityInfoMap = Collections.emptyMap();
    private volatile Map<String, Long> versionStampMap = Collections.emptyMap();
    private final AtomicInteger reloadSeq = new AtomicInteger(0);

    private Listener listener;
    private PackageRemovedListener packageRemovedListener;
    private boolean callbackRegistered = false;
    private volatile String pendingInstalledPkg = null;

    private void safeExecuteBg(Runnable task) {
        if (task == null || bgExecutor.isShutdown()) return;
        try {
            bgExecutor.execute(task);
        } catch (Throwable ignored) {
        }
    }

    private void safeExecuteIcon(Runnable task) {
        if (task == null || iconExecutor.isShutdown()) return;
        try {
            iconExecutor.execute(task);
        } catch (Throwable ignored) {
        }
    }

    private final Runnable debouncedReload = () -> {
        String pkg = pendingInstalledPkg;
        pendingInstalledPkg = null;
        reloadNow(pkg);
    };

    private final LauncherApps.Callback launcherCallback = new LauncherApps.Callback() {
        @Override
        public void onPackageRemoved(String packageName, UserHandle user) {
            evictPackageIcons(packageName);
            final long serial = (userManager != null && user != null)
                    ? userManager.getSerialNumberForUser(user) : 0L;
            final PackageRemovedListener rl = packageRemovedListener;
            if (rl != null && packageName != null && !packageName.isEmpty()) {
                mainHandler.post(() -> rl.onPackageUninstalled(packageName, serial));
            }
            scheduleReload(null);
        }

        @Override
        public void onPackageAdded(String packageName, UserHandle user) {
            evictPackageIcons(packageName);
            scheduleReload(packageName);
        }

        @Override
        public void onPackageChanged(String packageName, UserHandle user) {
            evictPackageIcons(packageName);
            scheduleReload(null);
        }

        @Override
        public void onPackagesAvailable(String[] packageNames, UserHandle user, boolean replacing) {
            if (packageNames != null) {
                for (String p : packageNames) evictPackageIcons(p);
            }
            scheduleReload(null);
        }

        @Override
        public void onPackagesUnavailable(String[] packageNames, UserHandle user, boolean replacing) {
            if (packageNames != null) {
                for (String p : packageNames) evictPackageIcons(p);
            }
            scheduleReload(null);
        }
    };

    private void evictPackageIcons(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        String prefix = packageName + "/";
        final Set<String> diskKeys = new HashSet<>();
        Map<String, Drawable> snap = iconCache.snapshot();
        for (String key : snap.keySet()) {
            if (key != null && key.startsWith(prefix)) {
                iconCache.remove(key);
                diskKeys.add(key);
            }
        }
        Map<String, Long> stampsSnap = versionStampMap;
        for (String key : stampsSnap.keySet()) {
            if (key != null && key.startsWith(prefix)) {
                diskKeys.add(key);
            }
        }
        // Выносим дисковую очистку из LauncherApps.Callback (UI-поток) на фоновый пул
        if (!diskKeys.isEmpty()) {
            safeExecuteBg(() -> {
                for (String key : diskKeys) {
                    diskCache.evictByKey(key);
                }
            });
        }
    }

    private void scheduleReload(String newlyInstalledPkg) {
        if (newlyInstalledPkg != null) pendingInstalledPkg = newlyInstalledPkg;
        mainHandler.removeCallbacks(debouncedReload);
        mainHandler.postDelayed(debouncedReload, 180L);
    }

    /**
     * Doxaiz 5.0: Аппаратно-адаптивный размер ОЗУ-кэша иконок (в КБ) под реальный класс памяти устройства:
     * - на low-RAM устройствах (2–3 ГБ) экономит память (2–4 МБ), чтобы лаунчер не выгружался;
     * - на 6–12 ГБ устройствах держит до 14 МБ иконок в ОЗУ для мгновенной отрисовки на 120 Гц.
     */
    static int computeOptimalIconCacheKb(long maxHeapBytes, boolean isLowRamDevice) {
        if (isLowRamDevice) {
            return (int) Math.min(4096L, Math.max(2048L, maxHeapBytes / 16384L));
        }
        return (int) Math.min(14336L, Math.max(4096L, maxHeapBytes / 10240L));
    }

    public AppRepository(Context context, Prefs prefs) {
        this.appContext = context.getApplicationContext();
        this.prefs = prefs;
        this.launcherApps = (LauncherApps) appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        this.userManager = (UserManager) appContext.getSystemService(Context.USER_SERVICE);
        boolean lowRam = false;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            lowRam = (am != null && am.isLowRamDevice());
        } catch (Throwable ignored) {}
        int maxKb = computeOptimalIconCacheKb(Runtime.getRuntime().maxMemory(), lowRam);
        this.iconCache = new LruCache<String, Drawable>(maxKb) {
            @Override
            protected int sizeOf(String key, Drawable value) {
                if (value instanceof BitmapDrawable) {
                    Bitmap b = ((BitmapDrawable) value).getBitmap();
                    if (b != null) return Math.max(1, b.getAllocationByteCount() / 1024);
                }
                return 32;
            }
        };
        this.diskCache = new IconDiskCache(appContext);
    }

    public void trimIconCache() {
        iconCache.evictAll();
    }

    public long[] getDiskCacheStats() {
        return diskCache.getStats();
    }

    public int getRamCachedIconCount() {
        return iconCache.snapshot().size();
    }

    public void clearAllCachesAndReload() {
        iconCache.evictAll();
        VectorGlyphs.clearCache();
        if (!bgExecutor.isShutdown()) {
            safeExecuteBg(() -> {
                diskCache.clearAll();
                mainHandler.post(this::reload);
            });
        } else {
            diskCache.clearAll();
        }
    }

    /**
     * Тихая фоновая предзагрузка иконок приоритетных приложений (док и текущий экран)
     * из дискового кэша в ОЗУ, чтобы страницы открывались мгновенно без мерцания.
     */
    public void prewarmIcons(final List<String> priorityKeys) {
        if (priorityKeys == null || priorityKeys.isEmpty() || bgExecutor.isShutdown()) return;
        final List<String> copy = new ArrayList<>(priorityKeys);
        safeExecuteBg(() -> {
            for (String key : copy) {
                if (bgExecutor.isShutdown()) return;
                if (key == null || iconCache.get(key) != null) continue;
                loadIconTwoLevel(key);
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setPackageRemovedListener(PackageRemovedListener l) {
        this.packageRemovedListener = l;
    }

    public void start() {
        if (!callbackRegistered && launcherApps != null) {
            try {
                launcherApps.registerCallback(launcherCallback, mainHandler);
                callbackRegistered = true;
            } catch (Throwable ignored) {
            }
        }
        reloadNow(null);
    }

    public void destroy() {
        listener = null;
        packageRemovedListener = null;
        mainHandler.removeCallbacksAndMessages(null);
        if (callbackRegistered && launcherApps != null) {
            try {
                launcherApps.unregisterCallback(launcherCallback);
            } catch (Throwable ignored) {
            }
            callbackRegistered = false;
        }
        iconExecutor.shutdownNow();
        bgExecutor.shutdownNow();
        iconCache.evictAll();
    }

    public void reload() {
        reloadNow(null);
    }

    private void reloadNow(final String newlyInstalledPkg) {
        if (bgExecutor.isShutdown()) return;
        final int mySeq = reloadSeq.incrementAndGet();
        safeExecuteBg(() -> {
            if (mySeq != reloadSeq.get()) return;
            final List<AppEntry> allEntries = new ArrayList<>();
            final Map<String, LauncherActivityInfo> freshInfoMap = new HashMap<>();
            final Map<String, Long> freshStamps = new HashMap<>();
            final Map<String, Long> pkgUpdateCache = new HashMap<>();
            final PackageManager pm = appContext.getPackageManager();
            final Map<String, String> customLabels = prefs.getCustomLabels();
            final Map<String, Integer> customCategories = prefs.getAllCustomCategories();
            final String selfPkg = appContext.getPackageName();
            final UserHandle myHandle = Process.myUserHandle();

            List<UserHandle> profiles = null;
            if (userManager != null) {
                try {
                    profiles = userManager.getUserProfiles();
                } catch (Throwable ignored) {
                }
            }
            if (profiles == null || profiles.isEmpty()) {
                profiles = Collections.singletonList(myHandle);
            }

            if (launcherApps != null) {
                for (UserHandle profile : profiles) {
                    long serial = (userManager != null) ? userManager.getSerialNumberForUser(profile) : 0L;
                    boolean isMain = profile.equals(myHandle);
                    List<LauncherActivityInfo> list;
                    try {
                        list = launcherApps.getActivityList(null, profile);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (list == null) continue;
                    for (LauncherActivityInfo info : list) {
                        ComponentName cn = info.getComponentName();
                        if (cn == null || selfPkg.equals(cn.getPackageName())) continue;
                        CharSequence rawLabel = info.getLabel();
                        String orig = (rawLabel != null) ? rawLabel.toString() : cn.getPackageName();
                        String key = cn.getPackageName() + "/" + cn.getClassName() + "#" + serial;
                        boolean isSys = false;
                        ApplicationInfo ai = null;
                        try {
                            ai = info.getApplicationInfo();
                            if (ai != null) {
                                isSys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                            }
                        } catch (Throwable ignored) {
                        }
                        Integer customCatObj = customCategories.get(cn.getPackageName());
                        int customCat = (customCatObj != null) ? customCatObj : -1;
                        int cat = (customCat >= AppCategoryHelper.CAT_COMM && customCat <= AppCategoryHelper.CAT_SYSTEM)
                                ? customCat
                                : AppCategoryHelper.classify(ai, cn.getPackageName(), orig, isSys);
                        long installTs = 0L;
                        try {
                            installTs = info.getFirstInstallTime();
                        } catch (Throwable ignored) {
                        }
                        long stamp = installTs;
                        Long cachedPkgStamp = pkgUpdateCache.get(cn.getPackageName());
                        if (cachedPkgStamp != null) {
                            if (cachedPkgStamp > 0L) stamp = cachedPkgStamp;
                        } else {
                            long resolvedStamp = 0L;
                            try {
                                PackageInfo pi = pm.getPackageInfo(cn.getPackageName(), 0);
                                if (pi != null && pi.lastUpdateTime > 0L) {
                                    resolvedStamp = pi.lastUpdateTime;
                                }
                            } catch (Throwable ignored) {
                            }
                            pkgUpdateCache.put(cn.getPackageName(), resolvedStamp);
                            if (resolvedStamp > 0L) stamp = resolvedStamp;
                        }
                        AppEntry entry = new AppEntry(
                                cn, profile, serial, orig, customLabels.get(key), isSys, isMain, cat, installTs
                        );
                        allEntries.add(entry);
                        freshInfoMap.put(entry.key, info);
                        freshStamps.put(entry.key, stamp);
                    }
                }
            }

            Collator collator = Collator.getInstance(Locale.forLanguageTag("ru-RU"));
            collator.setStrength(Collator.PRIMARY);
            Collections.sort(allEntries, new Comparator<AppEntry>() {
                @Override
                public int compare(AppEntry a, AppEntry b) {
                    int cmp = collator.compare(a.displayLabel, b.displayLabel);
                    if (cmp != 0) return cmp;
                    return a.packageName.compareTo(b.packageName);
                }
            });

            Set<String> hiddenSet = prefs.getHiddenApps();
            List<AppEntry> visible = new ArrayList<>();
            List<AppEntry> hidden = new ArrayList<>();
            Map<String, AppEntry> byKey = new HashMap<>();

            for (AppEntry e : allEntries) {
                byKey.put(e.key, e);
                if (hiddenSet.contains(e.key)) {
                    hidden.add(e);
                } else {
                    visible.add(e);
                }
            }

            final Map<String, LauncherActivityInfo> immutableInfo = Collections.unmodifiableMap(freshInfoMap);
            final Map<String, Long> immutableStamps = Collections.unmodifiableMap(freshStamps);

            mainHandler.post(() -> {
                // Защита от гонки потоков (angel-launcher#2): устаревший reload не перетирает более свежий
                if (mySeq != reloadSeq.get()) return;
                // Атомарная подмена без окна пустой карты между clear() и putAll()
                activityInfoMap = immutableInfo;
                versionStampMap = immutableStamps;
                // Прунинг ОЗУ-кэша от ключей удалённых приложений (only-search#1)
                Map<String, Drawable> snap = iconCache.snapshot();
                for (String cachedKey : snap.keySet()) {
                    if (cachedKey != null && !immutableInfo.containsKey(cachedKey)) {
                        iconCache.remove(cachedKey);
                    }
                }
                if (listener != null) {
                    listener.onAppsUpdated(visible, hidden, byKey, newlyInstalledPkg);
                }
            });
        });
    }

    private Drawable loadIconTwoLevel(String key) {
        Drawable inRam = iconCache.get(key);
        if (inRam != null) return inRam;

        Map<String, Long> stampsSnap = versionStampMap;
        Long stampObj = stampsSnap.get(key);
        long stamp = (stampObj != null) ? stampObj : 0L;

        // Уровень 2: быстрое чтение готового Bitmap из постоянного дискового кэша (без вызова PackageManager)
        Drawable fromDisk = diskCache.load(appContext, key, stamp);
        if (fromDisk != null) {
            iconCache.put(key, fromDisk);
            return fromDisk;
        }

        // Уровень 3: первичная загрузка оригинальной иконки 1 раз и сохранение на диск
        Map<String, LauncherActivityInfo> infoSnap = activityInfoMap;
        LauncherActivityInfo info = infoSnap.get(key);
        if (info == null) return null;
        Drawable loaded = null;
        try {
            loaded = IconHelper.loadOriginalIcon(appContext, info);
        } catch (Throwable ignored) {
            // Защита от гонки NameNotFoundException в момент обновления APK (Lawnchair#7481)
        }
        if (loaded == null) {
            try {
                ComponentName cn = info.getComponentName();
                if (cn != null) {
                    Drawable raw = appContext.getPackageManager().getApplicationIcon(cn.getPackageName());
                    if (raw != null) {
                        loaded = IconHelper.normalizeDrawable(appContext, raw);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (loaded != null) {
            iconCache.put(key, loaded);
            if (loaded instanceof BitmapDrawable) {
                final Bitmap bmp = ((BitmapDrawable) loaded).getBitmap();
                if (bmp != null && !bmp.isRecycled()) {
                    // Запись и сжатие WEBP/PNG уходят в фоновый поток, не тормозя показ иконки!
                    safeExecuteBg(() -> diskCache.save(key, stamp, bmp));
                }
            }
        }
        return loaded;
    }

    public void bindIcon(final AppEntry entry, final ImageView target) {
        if (entry == null || target == null) return;
        target.setTag(entry.key);
        Drawable cached = iconCache.get(entry.key);
        if (cached != null) {
            target.setImageDrawable(cached);
            return;
        }
        target.setImageDrawable(null);
        safeExecuteIcon(() -> {
            // Пропускаем лишнюю работу, если ImageView уже переиспользован при быстром скролле
            if (!entry.key.equals(target.getTag())) return;
            final Drawable loaded = loadIconTwoLevel(entry.key);
            if (loaded != null) {
                mainHandler.post(() -> {
                    if (entry.key.equals(target.getTag())) {
                        target.setImageDrawable(loaded);
                    }
                });
            }
        });
    }

    public boolean launchApp(AppEntry entry, android.view.View sourceView) {
        if (launcherApps == null || entry == null) return false;
        try {
            android.graphics.Rect bounds = null;
            if (sourceView != null) {
                int[] loc = new int[2];
                sourceView.getLocationOnScreen(loc);
                bounds = new android.graphics.Rect(
                        loc[0], loc[1],
                        loc[0] + sourceView.getWidth(),
                        loc[1] + sourceView.getHeight()
                );
            }
            launcherApps.startMainActivity(entry.componentName, entry.userHandle, bounds, null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void openAppInfo(AppEntry entry) {
        if (launcherApps == null || entry == null) return;
        try {
            launcherApps.startAppDetailsActivity(entry.componentName, entry.userHandle, null, null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Загружает нативные быстрые действия приложения (Android 8+ App Shortcuts).
     * Работает автоматически, когда Doxaiz выбран лаунчером по умолчанию.
     */
    public List<ShortcutInfo> getAppShortcuts(AppEntry entry, int maxCount) {
        List<ShortcutInfo> out = new ArrayList<>();
        if (launcherApps == null || entry == null) return out;
        try {
            if (!launcherApps.hasShortcutHostPermission()) return out;
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(entry.packageName);
            q.setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            );
            List<ShortcutInfo> raw = launcherApps.getShortcuts(q, entry.userHandle);
            if (raw == null || raw.isEmpty()) return out;
            List<ShortcutInfo> sorted = new ArrayList<>(raw);
            Collections.sort(sorted, (a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                return Integer.compare(a.getRank(), b.getRank());
            });
            Set<String> seenIds = new HashSet<>();
            for (ShortcutInfo si : sorted) {
                if (si != null && si.isEnabled()) {
                    String sid = si.getId();
                    if (sid != null && !seenIds.add(sid)) continue;
                    CharSequence lbl = si.getShortLabel();
                    if (lbl == null || lbl.length() == 0) lbl = si.getLongLabel();
                    if (lbl != null && lbl.length() > 0) {
                        out.add(si);
                        if (out.size() >= maxCount) break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public Drawable getShortcutIcon(ShortcutInfo si) {
        if (launcherApps == null || si == null) return null;
        try {
            return launcherApps.getShortcutIconDrawable(si, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public boolean launchShortcut(ShortcutInfo si) {
        if (launcherApps == null || si == null) return false;
        try {
            launcherApps.startShortcut(si, null, null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
