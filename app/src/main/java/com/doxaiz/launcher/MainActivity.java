package com.doxaiz.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements AppRepository.Listener {

    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_DRAWER = 1;
    private static final int SCREEN_SETTINGS = 2;

    private static final int REQ_EXPORT_BACKUP = 1001;
    private static final int REQ_IMPORT_BACKUP = 1002;
    private static final int REQ_ROLE_HOME = 1003;

    private Prefs prefs;
    private GlassTheme theme;
    private DesktopModel desktop;
    private AppRepository repository;
    private NetworkQualityHelper networkHelper;

    private int currentScreen = SCREEN_HOME;
    private int currentPage = 0;
    private int insetTop = 0;
    private int insetBottom = 0;
    private int insetImeBottom = 0;
    private int lastUnreadHash = 0;
    private boolean volumeCompactOpen = false;
    private boolean volumeExpandedOpen = false;
    private boolean shadeOpen = false;
    private static final DecelerateInterpolator SMOOTH_INTERPOLATOR = new DecelerateInterpolator(1.6f);
    private final Runnable autoHideVolumeRunnable = this::onAutoHideVolume;

    // Данные приложений
    private final List<AppEntry> visibleApps = new ArrayList<>();
    private final List<AppEntry> hiddenApps = new ArrayList<>();
    private final java.util.HashSet<String> hiddenAppKeys = new java.util.HashSet<>();
    private final Map<String, AppEntry> appsByKey = new HashMap<>();
    private final List<AppEntry> filteredApps = new ArrayList<>();
    private final LinkedHashMap<String, Integer> alphabetPositions = new LinkedHashMap<>();

    // Открытая папка (если есть)
    private String openFolderId = null;
    private int openFolderPage = -1;
    private float folderZoomOriginX = -1f;
    private float folderZoomOriginY = -1f;
    private View activeFolderCard = null;
    private boolean multiSelectMode = false;
    private final java.util.LinkedHashSet<String> selectedAppKeys = new java.util.LinkedHashSet<>();
    private LinearLayout multiSelectBar;
    private TextView multiSelectCountTv;

    // Корневые слои
    private FrameLayout rootContainer;
    private AnimeBackdropView animeBackdrop;
    private boolean activityResumed;
    private LinearLayout homeContainer;
    private LinearLayout drawerContainer;
    private LinearLayout settingsContainer;
    private FrameLayout dragOverlay;
    private FrameLayout modalOverlay;

    // Главный экран (многостраничный, свободная сетка, без часов и виджетов)
    private LinearLayout topStrip;
    private FrameLayout settingsBtn;
    private ImageView settingsBtnIcon;
    private DesktopPagerView pagerView;
    private LinearLayout pageDotsBar;
    private LinearLayout dockCard;
    private LinearLayout dockAppsRow;
    private FrameLayout dockDrawerBtn;
    private ImageView dockDrawerIcon;

    // Слой перетаскивания (Drag & Drop без тяжёлых эффектов)
    private TextView dragRemoveBar;
    private FrameLayout dragGhost;
    private boolean dragPending = false;
    private boolean dragging = false;
    private int dragFromPage = -1;
    private int dragItemIndex = -1;
    private DesktopItem dragItem = null;
    private AppEntry dragFromDrawerEntry = null;
    private View dragSourceView = null;
    private float dragStartX = 0f;
    private float dragStartY = 0f;
    private float lastTouchRawX = 0f;
    private float lastTouchRawY = 0f;
    private int dragHoverCol = -1;
    private int dragHoverRow = -1;
    private boolean dragOverRemoveBar = false;
    private int dragEdgeDirection = 0;
    private boolean dragAutoCreatedPage = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable edgePageSwitchRunnable = this::performDragEdgePageSwitch;

    // Двойное касание пустого места рабочего стола
    private long lastEmptyTapTime = 0L;
    private float lastEmptyTapX = 0f;
    private float lastEmptyTapY = 0f;

    // Экран всех приложений
    private LinearLayout drawerSearchPill;
    private ImageView drawerSearchIcon;
    private EditText drawerSearchInput;
    private FrameLayout drawerClearBtn;
    private ImageView drawerClearIcon;
    private FrameLayout drawerModeBtn;
    private ImageView drawerModeIcon;
    private FrameLayout drawerBatchBtn;
    private ImageView drawerBatchIcon;
    private FrameLayout drawerCloseBtn;
    private ImageView drawerCloseIcon;
    private LinearLayout drawerSmartBox;
    // Doxaiz 4.1: Пакетный выбор и быстрое удаление в «Все приложения»
    private boolean drawerBatchMode = false;
    private final java.util.LinkedHashSet<String> drawerBatchSelectedPkgs = new java.util.LinkedHashSet<>();
    private final java.util.ArrayDeque<String> pendingBatchUninstallPkgs = new java.util.ArrayDeque<>();
    private boolean awaitingBatchUninstallResume = false;
    private LinearLayout drawerBatchBar;
    private TextView drawerBatchCountTv;
    private TextView drawerBatchDeleteBtn;

    // Щипок двумя пальцами на рабочем столе -> Менеджер страниц
    private ScaleGestureDetector pinchDetector;
    private boolean pinchTriggered = false;

    // Карточка памяти устройства (ОЗУ + Хранилище) и плашка активных фоновых в меню приложений
    private LinearLayout drawerMemoryCard;
    private TextView drawerBgActivePill;
    private TextView drawerRamTitle;
    private TextView drawerRamValue;
    private FrameLayout drawerRamTrack;
    private View drawerRamFill;
    private View drawerMemoryDivider;
    private TextView drawerStorageTitle;
    private TextView drawerStorageValue;
    private FrameLayout drawerStorageTrack;
    private View drawerStorageFill;
    private int lastRamPct = 0;
    private int lastStoragePct = 0;

    // Карточка частых / недавних приложений и умные категории в меню приложений
    private FrameLayout drawerSettingsBtn;
    private ImageView drawerSettingsIcon;
    private LinearLayout drawerRecentCard;
    private TextView drawerRecentLabel;
    private LinearLayout drawerRecentRow;
    private TextView drawerAllAppsLabel;
    private HorizontalScrollView drawerCategoryScroll;
    private LinearLayout drawerCategoryRow;
    private int selectedDrawerCategory = AppCategoryHelper.CAT_ALL;
    private String selectedCustomSectionId = null;
    private int lastSearchQueryLen = 0;

    // Свайп вверх по конкретной иконке («2 в 1») и приватное пространство
    private String touchedSwipeUpAppKey = null;
    private View touchedSwipeUpAnchor = null;
    private Runnable pendingPrivateSpaceAction = null;
    private boolean pendingImportAuthVerified = false;
    private final java.util.concurrent.ExecutorService backupIo =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "doxaiz-backup-io");
                t.setDaemon(true);
                return t;
            });

    private GridView drawerGrid;
    private LinearLayout alphabetBar;
    private TextView drawerEmptyText;
    private DrawerAdapter drawerAdapter;

    // Экран настроек
    private LinearLayout settingsContent;
    private FrameLayout settingsBackBtn;
    private ImageView settingsBackIcon;
    private TextView settingsTitleView;
    private EditText settingsSearchInput;
    private FrameLayout settingsSearchClearBtn;
    private String settingsSearchQuery = "";

    // Вертикальные свайпы на рабочем столе (вверх — все приложения, вниз — шторка)
    private float touchDownX;
    private float touchDownY;
    private boolean verticalSwipeTriggered;
    private int touchSlop;

    // Плавное интерактивное стягивание списка всех приложений вниз (Doxaiz 4.0)
    private float drawerDragDownStartX;
    private float drawerDragDownStartY;
    private boolean drawerDragDownActive = false;
    private boolean drawerClosingAnim = false;

    private Object backInvokedCallback;
    private DynamicBarHelper dynamicBarHelper;
    private QuickShadeHelper quickShadeHelper;
    private HeadsUpBannerHelper headsUpBannerHelper;
    private View dynamicBarOverlay;
    private View headsUpBannerOverlay;
    private int pendingShadeTab = QuickShadeHelper.TAB_CONTROL;
    private boolean batteryReceiverRegistered = false;
    private boolean userPresentReceiverRegistered = false;
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (dynamicBarHelper == null || intent == null) return;
            String act = intent.getAction();
            if (android.media.AudioManager.RINGER_MODE_CHANGED_ACTION.equals(act)) {
                int mode = intent.getIntExtra(
                        android.media.AudioManager.EXTRA_RINGER_MODE,
                        android.media.AudioManager.RINGER_MODE_NORMAL
                );
                dynamicBarHelper.onRingerModeChanged(mode);
            } else {
                dynamicBarHelper.updateBatteryIntent(intent);
            }
        }
    };
    private final BroadcastReceiver userPresentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (dynamicBarHelper != null && Intent.ACTION_USER_PRESENT.equals(intent != null ? intent.getAction() : null)) {
                dynamicBarHelper.triggerUnlockPulse();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Prefs(this);
        theme = GlassTheme.create(prefs.getVisualStyle(), prefs.getGlassOpacity(), false);
        desktop = new DesktopModel(this, prefs);
        currentPage = getEffectiveHomePageIndex();
        networkHelper = new NetworkQualityHelper(this);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        pinchDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                pinchTriggered = false;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (!pinchTriggered && detector.getScaleFactor() < 0.84f) {
                    pinchTriggered = true;
                    showHomeOverviewModal();
                    return true;
                }
                return false;
            }
        });

        dynamicBarHelper = new DynamicBarHelper(this, new DynamicBarHelper.Host() {
            @Override public Prefs getPrefs() { return prefs; }
            @Override public void onDynamicBarPrefsChanged() {
                if (currentScreen == SCREEN_SETTINGS) rebuildSettingsContent();
            }
            @Override public void showToast(String msg) {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });

        headsUpBannerHelper = new HeadsUpBannerHelper(this, new HeadsUpBannerHelper.Host() {
            @Override public Prefs getPrefs() { return prefs; }
            @Override public boolean isShadeOpen() {
                return quickShadeHelper != null && quickShadeHelper.isShowing();
            }
            @Override public void showToast(String msg) {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });

        quickShadeHelper = new QuickShadeHelper(this, new QuickShadeHelper.Host() {
            @Override public Prefs getPrefs() { return prefs; }
            @Override public void openLauncherSettings() { showScreen(SCREEN_SETTINGS); }
            @Override public void openActiveApps() { showBackgroundAppsModal(); }
            @Override public void openSoundPanel() { showExpandedSoundPanel(); }
            @Override public void openDynamicBarSettings() {
                if (dynamicBarHelper != null) dynamicBarHelper.showSettingsDialog();
            }
            @Override public void openNotifStatusSettings() { showNotifStatusSettingsModal(); }
            @Override public void openPrivateDnsModal() { showPrivateDnsModal(); }
            @Override public void showToast(String message) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
            @Override public void onShadeVisibilityChanged(boolean visible) {
                syncDynamicBarOverlaySuppression();
            }
            @Override public void onAutoNetworkSettingsChanged() {
                restartNetworkMonitor();
                if (currentScreen == SCREEN_SETTINGS) {
                    rebuildSettingsContent();
                }
            }
            @Override public boolean triggerNetworkBoostNow() {
                boolean ok = (networkHelper != null) && networkHelper.triggerSystemReprobe("Шторка: Сеть+");
                if (currentScreen == SCREEN_SETTINGS) {
                    rebuildSettingsContent();
                }
                return ok;
            }
        });

        setupWindow();
        buildRootUi();
        setContentView(rootContainer);
        setupInsets();
        setupBackHandler();

        repository = new AppRepository(this, prefs);
        repository.setListener(this);
        repository.setPackageRemovedListener((pkg, serial) -> {
            if (isFinishing() || isDestroyed()) return;
            desktop.purgePackageForUser(pkg, serial);
            if (!isPackageInstalled(pkg)) {
                prefs.removeLockedRecentPackage(pkg);
                prefs.removePackageFromAllCustomSections(pkg);
            }
        });
        repository.start();

        applyTheme();
        showScreen(SCREEN_HOME);
    }

    @SuppressWarnings("deprecation")
    private void setupWindow() {
        Window w = getWindow();
        if (w == null) return;
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
        }
    }

    @SuppressWarnings("deprecation")
    private void updateSystemBarIconColors() {
        Window w = getWindow();
        if (w == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ctrl = w.getInsetsController();
            if (ctrl != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctrl.setSystemBarsAppearance(0, mask);
            }
        } else {
            View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            decor.setSystemUiVisibility(flags);
        }
    }

    @SuppressWarnings("deprecation")
    private void setupInsets() {
        rootContainer.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sys = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                insetTop = sys.top;
                insetBottom = sys.bottom;
                insetImeBottom = Math.max(sys.bottom, ime.bottom);
            } else {
                insetTop = insets.getSystemWindowInsetTop();
                insetBottom = insets.getSystemWindowInsetBottom();
                insetImeBottom = insetBottom;
            }
            applyContainerInsets();
            return insets;
        });
        rootContainer.requestApplyInsets();
    }

    private void applyContainerInsets() {
        homeContainer.setPadding(0, insetTop + dp(8), 0, insetBottom + dp(12));
        drawerContainer.setPadding(dp(16), insetTop + dp(12), dp(8), insetImeBottom + dp(8));
        settingsContainer.setPadding(dp(16), insetTop + dp(12), dp(16), insetBottom + dp(16));
        modalOverlay.setPadding(dp(18), insetTop + dp(20), dp(18), insetImeBottom + dp(20));
        if (dragRemoveBar != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dragRemoveBar.getLayoutParams();
            lp.topMargin = insetTop + dp(10);
            dragRemoveBar.setLayoutParams(lp);
        }
        if (dynamicBarOverlay != null) {
            FrameLayout.LayoutParams dLp = (FrameLayout.LayoutParams) dynamicBarOverlay.getLayoutParams();
            dLp.topMargin = Math.max(dp(6), insetTop > dp(28) ? dp(6) : dp(4));
            dynamicBarOverlay.setLayoutParams(dLp);
        }
        if (headsUpBannerHelper != null) {
            headsUpBannerHelper.updateTopInset(insetTop);
        }
    }

    private void setupBackHandler() {
        if (Build.VERSION.SDK_INT >= 33) {
            OnBackInvokedCallback cb = this::handleBackAction;
            backInvokedCallback = cb;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb
            );
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBackAction();
    }

    private int getEffectiveHomePageIndex() {
        int size = (desktop != null && desktop.pages != null) ? desktop.pages.size() : 1;
        if (size <= 1) return 0;
        return Math.max(0, Math.min(prefs.getHomePageIndex(), size - 1));
    }

    private void handleBackAction() {
        if (quickShadeHelper != null && quickShadeHelper.isShowing()) {
            quickShadeHelper.dismissAnimated();
            return;
        }
        if (modalOverlay.getVisibility() == View.VISIBLE) {
            dismissModal();
            return;
        }
        if (multiSelectMode) {
            exitMultiSelectMode();
            return;
        }
        if (currentScreen == SCREEN_DRAWER && drawerBatchMode) {
            exitDrawerBatchMode();
            return;
        }
        if (currentScreen == SCREEN_DRAWER) {
            closeDrawerAnimated();
            return;
        }
        if (currentScreen != SCREEN_HOME) {
            showScreen(SCREEN_HOME);
            return;
        }
        int homeIdx = getEffectiveHomePageIndex();
        if (currentPage != homeIdx) {
            currentPage = homeIdx;
            pagerView.goToPage(homeIdx);
            rebuildPageDots();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (dragging || dragPending) {
            if (cancelDragCleanup()) rebuildDesktop();
        }
        if (quickShadeHelper != null && quickShadeHelper.isShowing()) {
            quickShadeHelper.dismiss();
        }
        if (headsUpBannerHelper != null) {
            headsUpBannerHelper.hideImmediate();
        }
        if (dynamicBarHelper != null) {
            dynamicBarHelper.collapse();
        }
        if (multiSelectMode) {
            exitMultiSelectMode();
        }
        if (drawerBatchMode) {
            exitDrawerBatchMode();
        }
        pendingBatchUninstallPkgs.clear();
        awaitingBatchUninstallResume = false;
        dismissModal();
        if (currentScreen != SCREEN_HOME) {
            showScreen(SCREEN_HOME);
        }
        int homeIdx = getEffectiveHomePageIndex();
        if (currentPage != homeIdx) {
            currentPage = homeIdx;
            pagerView.goToPage(homeIdx);
            rebuildPageDots();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (animeBackdrop != null && prefs != null)
            animeBackdrop.applyConfig(prefs.getWallpaperMode(), prefs.getVisualStyle(), prefs.getWallpaperDimLevel());
        syncAmbientParticles();
        DoxaizNotificationService.setListener(this::onNotificationDotsChanged);
        DoxaizNotificationService.setHeadsUpListener(item -> {
            if (!isFinishing() && !isDestroyed() && headsUpBannerHelper != null) {
                headsUpBannerHelper.showNotification(item);
            }
        });
        restartNetworkMonitor();
        boolean freshBatteryReg = false;
        if (!batteryReceiverRegistered) {
            try {
                IntentFilter bf = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                bf.addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(batteryReceiver, bf, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(batteryReceiver, bf);
                }
                batteryReceiverRegistered = true;
                freshBatteryReg = true;
            } catch (Throwable ignored) {
            }
        }
        if (!userPresentReceiverRegistered) {
            try {
                IntentFilter uf = new IntentFilter(Intent.ACTION_USER_PRESENT);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(userPresentReceiver, uf, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(userPresentReceiver, uf);
                }
                userPresentReceiverRegistered = true;
            } catch (Throwable ignored) {
            }
        }
        DoxaizNotificationService.ensureBoundIfPermitted(this);
        if (dynamicBarHelper != null) {
            // При свежей регистрации batteryReceiver Android сразу доставляет sticky ACTION_BATTERY_CHANGED
            if (!freshBatteryReg && !batteryReceiverRegistered) {
                dynamicBarHelper.updateBatteryFromStickyIntent();
            }
            dynamicBarHelper.refresh();
        }
        if (prefs.getNotifBadgeMode() != Prefs.NOTIF_BADGE_NONE && !dragging && !dragPending) {
            int h = DoxaizNotificationService.getBadgeStateHash();
            if (h != lastUnreadHash) {
                lastUnreadHash = h;
                refreshBadgesInPlace();
            }
        }
        if (currentScreen == SCREEN_SETTINGS) {
            rebuildSettingsContent();
        }
        if (awaitingBatchUninstallResume) {
            awaitingBatchUninstallResume = false;
            if (!pendingBatchUninstallPkgs.isEmpty()) {
                uiHandler.postDelayed(this::launchNextBatchUninstall, 280L);
            } else {
                Toast.makeText(this, "Пакетное удаление завершено", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Не сбрасываем ОЗУ-кэш иконок при обычном открытии приложения (TRIM_MEMORY_UI_HIDDEN=20) —
        // освобождаем при вытеснении в LRU-фон / дефиците ОЗУ (на Android 14+ TRIM_MEMORY_COMPLETE не приходит)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            if (animeBackdrop != null) animeBackdrop.onTrimMemoryCache();
            if (repository != null) repository.trimIconCache();
            if (quickShadeHelper != null) quickShadeHelper.clearIconCache();
            IconHelper.clearMaskCache();
            VectorGlyphs.clearCache();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                try {
                    // Во время звонка / VoIP / рингтона отдаём клавиши громкости системе (STREAM_VOICE_CALL / RING)
                    if (am.getMode() != AudioManager.MODE_NORMAL) {
                        return super.onKeyDown(keyCode, event);
                    }
                    if (!am.isVolumeFixed()) {
                        am.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                keyCode == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                                0
                        );
                    }
                } catch (Throwable ignored) {
                }
            }
            if (volumeExpandedOpen && modalOverlay != null && modalOverlay.getVisibility() == View.VISIBLE) {
                showExpandedSoundPanel();
            } else if (volumeCompactOpen || modalOverlay == null || modalOverlay.getVisibility() != View.VISIBLE) {
                showCompactVolumePanel();
            } else {
                // Не закрываем и не сносим открытую папку / диалог при нажатии аппаратных кнопок громкости!
                if (am != null) {
                    int pct = SoundPanelHelper.volumeToPercent(
                            am.getStreamVolume(AudioManager.STREAM_MUSIC),
                            Build.VERSION.SDK_INT >= 28 ? am.getStreamMinVolume(AudioManager.STREAM_MUSIC) : 0,
                            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                    Toast.makeText(this, "Громкость медиа: " + pct + "%", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void restartNetworkMonitor() {
        if (networkHelper == null) return;
        networkHelper.startForegroundMonitor(
                prefs.isAutoNetworkAssist(),
                prefs.getAutoNetworkMode(),
                prefs.isAutoNetworkWeakWifi(),
                (status, autoReprobed) ->
                        uiHandler.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            // Тихая работа без всплывашек: обновляем диагностику и журнал в Настройках
                            if (currentScreen == SCREEN_SETTINGS) {
                                rebuildSettingsContent();
                            }
                        })
        );
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        syncAmbientParticles();
        super.onPause();
        if (dragging || dragPending) {
            if (cancelDragCleanup()) rebuildDesktop();
        }
        DoxaizNotificationService.setListener(null);
        DoxaizNotificationService.setHeadsUpListener(null);
        if (headsUpBannerHelper != null) {
            headsUpBannerHelper.hideImmediate();
        }
        if (networkHelper != null) {
            networkHelper.stopForegroundMonitor();
        }
        if (batteryReceiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Throwable ignored) {
            }
            batteryReceiverRegistered = false;
        }
        if (dynamicBarHelper != null) {
            dynamicBarHelper.collapse();
        }
    }

    private void onNotificationDotsChanged() {
        if (isFinishing() || isDestroyed()) return;
        if (!dragging && !dragPending) {
            int badgeHash = DoxaizNotificationService.getBadgeStateHash();
            if (badgeHash != lastUnreadHash) {
                lastUnreadHash = badgeHash;
                if (prefs.getNotifBadgeMode() != Prefs.NOTIF_BADGE_NONE) {
                    refreshBadgesInPlace();
                }
            }
            if (currentScreen == SCREEN_DRAWER) {
                refreshDrawerBgPill();
            }
            if (quickShadeHelper != null && quickShadeHelper.isShowing()) {
                quickShadeHelper.onNotificationsChanged();
            }
            if (dynamicBarHelper != null) {
                dynamicBarHelper.refresh();
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        cancelDragCleanup();
        folderZoomOriginX = -1f;
        folderZoomOriginY = -1f;
        lastDrawerRecentSig = "";
        lastAlphabetBarSig = "";
        if (quickShadeHelper != null && quickShadeHelper.isShowing()) {
            quickShadeHelper.dismiss();
        }
        applyContainerInsets();
        if (dynamicBarHelper != null) {
            dynamicBarHelper.refresh();
        }
        rebuildDesktop();
        if (openFolderId != null && openFolderPage >= 0 && modalOverlay != null
                && modalOverlay.getVisibility() == View.VISIBLE) {
            DesktopItem folder = desktop.findFolder(openFolderPage, openFolderId);
            if (folder != null) {
                showFolderModalWithSlide(openFolderPage, folder, 0);
            } else {
                dismissModal();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (animeBackdrop != null) animeBackdrop.destroy();
        backupIo.shutdownNow();
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        pendingBatchUninstallPkgs.clear();
        awaitingBatchUninstallResume = false;
        pendingPrivateSpaceAction = null;
        DoxaizNotificationService.setListener(null);
        DoxaizNotificationService.setHeadsUpListener(null);
        if (networkHelper != null) networkHelper.stopForegroundMonitor();
        if (batteryReceiverRegistered) {
            try { unregisterReceiver(batteryReceiver); } catch (Throwable ignored) {}
            batteryReceiverRegistered = false;
        }
        if (userPresentReceiverRegistered) {
            try { unregisterReceiver(userPresentReceiver); } catch (Throwable ignored) {}
            userPresentReceiverRegistered = false;
        }
        if (quickShadeHelper != null) {
            quickShadeHelper.destroy();
        }
        if (dynamicBarHelper != null) {
            dynamicBarHelper.destroy();
        }
        if (headsUpBannerHelper != null) {
            headsUpBannerHelper.destroy();
        }
        if (Build.VERSION.SDK_INT >= 33 && backInvokedCallback instanceof OnBackInvokedCallback) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                        (OnBackInvokedCallback) backInvokedCallback
                );
            } catch (Throwable ignored) {
            }
        }
        if (repository != null) repository.destroy();
    }

    // =========================================================================
    // Построение слоёв интерфейса
    // =========================================================================

    private void buildRootUi() {
        rootContainer = new FrameLayout(this);
        rootContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        buildHomeLayer();
        buildDrawerLayer();
        buildSettingsLayer();
        buildDragLayer();
        buildModalLayer();

        animeBackdrop = new AnimeBackdropView(this);
        animeBackdrop.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        rootContainer.addView(animeBackdrop);
        rootContainer.addView(homeContainer);
        rootContainer.addView(drawerContainer);
        rootContainer.addView(settingsContainer);
        rootContainer.addView(dragOverlay);
        if (dynamicBarHelper != null) {
            dynamicBarOverlay = dynamicBarHelper.buildOverlayView(insetTop);
            rootContainer.addView(dynamicBarOverlay);
        }
        if (headsUpBannerHelper != null) {
            headsUpBannerOverlay = headsUpBannerHelper.buildOverlayView(insetTop);
            rootContainer.addView(headsUpBannerOverlay);
        }
        rootContainer.addView(modalOverlay);
    }

    private void buildHomeLayer() {
        homeContainer = new LinearLayout(this);
        homeContainer.setOrientation(LinearLayout.VERTICAL);
        homeContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Компактная верхняя полоса: только незаметная кнопка настроек справа (без часов и виджетов!)
        topStrip = new LinearLayout(this);
        topStrip.setOrientation(LinearLayout.HORIZONTAL);
        topStrip.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        topStrip.setPadding(dp(16), 0, dp(16), dp(4));
        topStrip.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        settingsBtn = new FrameLayout(this);
        settingsBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        settingsBtn.setContentDescription("Настройки Doxaiz");
        settingsBtn.setOnClickListener(v -> showScreen(SCREEN_SETTINGS));

        settingsBtnIcon = new ImageView(this);
        settingsBtnIcon.setImageResource(R.drawable.ic_settings);
        FrameLayout.LayoutParams setIconLp = new FrameLayout.LayoutParams(dp(18), dp(18));
        setIconLp.gravity = Gravity.CENTER;
        settingsBtn.addView(settingsBtnIcon, setIconLp);

        topStrip.addView(settingsBtn);
        homeContainer.addView(topStrip);

        // Многостраничный рабочий стол
        pagerView = new DesktopPagerView(this);
        pagerView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));
        pagerView.setOnPageChangedListener(idx -> {
            currentPage = idx;
            rebuildPageDots();
            if (repository != null && desktop != null) {
                repository.prewarmIcons(desktop.collectPriorityIconKeys(idx));
                if (idx + 1 < desktop.pages.size()) {
                    repository.prewarmIcons(desktop.collectPriorityIconKeys(idx + 1));
                }
            }
        });
        homeContainer.addView(pagerView);

        // Точки-индикаторы страниц
        pageDotsBar = new LinearLayout(this);
        pageDotsBar.setOrientation(LinearLayout.HORIZONTAL);
        pageDotsBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        dotsLp.topMargin = dp(4);
        dotsLp.bottomMargin = dp(10);
        pageDotsBar.setLayoutParams(dotsLp);
        homeContainer.addView(pageDotsBar);

        // Нижний AMOLED-стеклянный док (закреплённые приложения + кнопка всех приложений)
        dockCard = new LinearLayout(this);
        dockCard.setOrientation(LinearLayout.HORIZONTAL);
        dockCard.setGravity(Gravity.CENTER_VERTICAL);
        dockCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        dockLp.leftMargin = dp(14);
        dockLp.rightMargin = dp(14);
        dockCard.setLayoutParams(dockLp);

        dockAppsRow = new LinearLayout(this);
        dockAppsRow.setOrientation(LinearLayout.HORIZONTAL);
        dockAppsRow.setGravity(Gravity.CENTER_VERTICAL);
        dockAppsRow.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        dockDrawerBtn = new FrameLayout(this);
        LinearLayout.LayoutParams ddLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        ddLp.leftMargin = dp(6);
        dockDrawerBtn.setLayoutParams(ddLp);
        dockDrawerBtn.setContentDescription("Все приложения");
        dockDrawerBtn.setOnClickListener(v -> openDrawer(false));
        dockDrawerBtn.setOnLongClickListener(v -> {
            openDrawer(true);
            return true;
        });

        dockDrawerIcon = new ImageView(this);
        dockDrawerIcon.setImageResource(R.drawable.ic_apps);
        FrameLayout.LayoutParams ddiLp = new FrameLayout.LayoutParams(dp(22), dp(22));
        ddiLp.gravity = Gravity.CENTER;
        dockDrawerBtn.addView(dockDrawerIcon, ddiLp);

        dockCard.addView(dockAppsRow);
        dockCard.addView(dockDrawerBtn);
        homeContainer.addView(dockCard);

        // Плавающая панель мультивыбора иконок (Doxaiz 3.9): «В папку», «На экран», «Готово»
        multiSelectBar = new LinearLayout(this);
        multiSelectBar.setOrientation(LinearLayout.HORIZONTAL);
        multiSelectBar.setGravity(Gravity.CENTER_VERTICAL);
        multiSelectBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        multiSelectBar.setBackground(GlassTheme.makeGlassRect(
                this, 0xF0171A22, 0x3AFFFFFF, 24f, 1f
        ));
        LinearLayout.LayoutParams msLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        msLp.topMargin = dp(4);
        multiSelectBar.setLayoutParams(msLp);
        multiSelectBar.setVisibility(View.GONE);

        multiSelectCountTv = new TextView(this);
        multiSelectCountTv.setText("Выбрано: 0");
        multiSelectCountTv.setTextColor(Color.WHITE);
        multiSelectCountTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        multiSelectCountTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        multiSelectBar.addView(multiSelectCountTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        multiSelectBar.addView(makeMultiSelectActionBtn("folder_plus", "В папку", GlassTheme.ACCENT_BLUE, this::multiSelectCreateFolder));
        View sp1 = new View(this);
        multiSelectBar.addView(sp1, new LinearLayout.LayoutParams(dp(6), dp(1)));
        multiSelectBar.addView(makeMultiSelectActionBtn("pages", "На экран", 0xFF2B2E38, this::multiSelectMoveToPageModal));
        View sp2 = new View(this);
        multiSelectBar.addView(sp2, new LinearLayout.LayoutParams(dp(6), dp(1)));
        multiSelectBar.addView(makeVectorCircleBtn("close", "Готово", this::exitMultiSelectMode),
                new LinearLayout.LayoutParams(dp(36), dp(36)));

        homeContainer.addView(multiSelectBar);
    }

    private LinearLayout makeMultiSelectActionBtn(String glyph, String label, int bgColor, final Runnable onClick) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(8), dp(11), dp(8));
        btn.setBackground(GlassTheme.makeRoundedRect(bgColor, dp(14)));
        ImageView iv = VectorGlyphs.makeIconView(this, glyph, 15, Color.WHITE);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(15), dp(15));
        iLp.rightMargin = dp(5);
        btn.addView(iv, iLp);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.addView(tv);
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private void enterMultiSelectMode(String initialAppKey) {
        if (prefs.isDesktopLocked()) {
            Toast.makeText(this, "Сначала разблокируйте раскладку", Toast.LENGTH_SHORT).show();
            return;
        }
        multiSelectMode = true;
        selectedAppKeys.clear();
        if (initialAppKey != null && !initialAppKey.isEmpty()) {
            selectedAppKeys.add(initialAppKey);
        }
        updateMultiSelectBar();
        rebuildDesktop();
    }

    private void exitMultiSelectMode() {
        if (!multiSelectMode) return;
        multiSelectMode = false;
        selectedAppKeys.clear();
        updateMultiSelectBar();
        rebuildDesktop();
    }

    private void updateMultiSelectBar() {
        if (multiSelectBar == null || dockCard == null) return;
        if (multiSelectMode) {
            dockCard.setVisibility(View.GONE);
            if (multiSelectBar.getVisibility() != View.VISIBLE) {
                multiSelectBar.setVisibility(View.VISIBLE);
                multiSelectBar.setAlpha(0f);
                multiSelectBar.setTranslationY(dp(18));
                multiSelectBar.animate().alpha(1f).translationY(0f).setDuration(170L).start();
            }
            if (multiSelectCountTv != null) {
                multiSelectCountTv.setText("Выбрано: " + selectedAppKeys.size());
            }
        } else {
            multiSelectBar.setVisibility(View.GONE);
            dockCard.setVisibility(View.VISIBLE);
        }
    }

    private void multiSelectCreateFolder() {
        if (selectedAppKeys.isEmpty()) {
            Toast.makeText(this, "Выберите хотя бы одно приложение", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> keys = new ArrayList<>(selectedAppKeys);
        // Убираем выбранные приложения с их старых ячеек на всех страницах (доки не трогаем)
        for (String k : keys) {
            for (List<DesktopItem> page : desktop.pages) {
                for (int i = page.size() - 1; i >= 0; i--) {
                    DesktopItem it = page.get(i);
                    if (it.type == DesktopItem.TYPE_APP && k.equals(it.appKey)) {
                        page.remove(i);
                    }
                }
            }
        }
        int targetPage = Math.max(0, Math.min(currentPage, desktop.pages.size() - 1));
        DesktopItem created = desktop.createFolderAt(
                targetPage, "Новая папка", keys, -1, -1,
                prefs.getColumns(), targetPage == 0 && prefs.isHomeBottomAligned()
        );
        desktop.save();
        exitMultiSelectMode();
        Toast.makeText(this, "Собрано в папку: " + formatRussianAppsCount(keys.size()), Toast.LENGTH_SHORT).show();
        if (created != null) {
            showFolderModal(targetPage, created);
        }
    }

    private void multiSelectMoveToPageModal() {
        if (selectedAppKeys.isEmpty()) {
            Toast.makeText(this, "Выберите хотя бы одно приложение", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Перенести выбранные (" + selectedAppKeys.size() + ") на экран:");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(4), 0, dp(4), dp(10));
        box.addView(title);

        for (int p = 0; p < desktop.pages.size(); p++) {
            final int targetPage = p;
            String lbl = "Экран " + (p + 1) + (p == currentPage ? " (текущий)" : "");
            addModalAction(box, lbl, theme.textPrimary, () -> moveSelectedAppsToPage(targetPage));
        }
        if (desktop.pages.size() < DesktopModel.MAX_PAGES) {
            addModalAction(box, "+ Создать новый экран и перенести", theme.accentColor, () -> {
                int newPage = desktop.addPage();
                moveSelectedAppsToPage(newPage);
            });
        }
        showModalCard(box, Gravity.BOTTOM, false);
    }

    private void moveSelectedAppsToPage(int targetPage) {
        List<String> keys = new ArrayList<>(selectedAppKeys);
        for (String k : keys) {
            for (List<DesktopItem> page : desktop.pages) {
                for (int i = page.size() - 1; i >= 0; i--) {
                    DesktopItem it = page.get(i);
                    if (it.type == DesktopItem.TYPE_APP && k.equals(it.appKey)) {
                        page.remove(i);
                    }
                }
            }
        }
        int cols = prefs.getColumns();
        boolean preferBottom = (targetPage == 0 && prefs.isHomeBottomAligned());
        int moved = 0;
        for (String k : keys) {
            if (desktop.addAppToPage(targetPage, k, cols, preferBottom)) {
                moved++;
            } else if (desktop.pages.size() < DesktopModel.MAX_PAGES) {
                int overflow = desktop.addPage();
                if (desktop.addAppToPage(overflow, k, cols, false)) moved++;
            }
        }
        desktop.save();
        currentPage = Math.max(0, Math.min(targetPage, desktop.pages.size() - 1));
        exitMultiSelectMode();
        if (pagerView != null) pagerView.goToPage(currentPage);
        rebuildPageDots();
        Toast.makeText(this, "Перенесено: " + formatRussianAppsCount(moved), Toast.LENGTH_SHORT).show();
    }

    private void buildDrawerLayer() {
        drawerContainer = new LinearLayout(this);
        drawerContainer.setOrientation(LinearLayout.VERTICAL);
        drawerContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        drawerContainer.setVisibility(View.GONE);
        drawerContainer.setClickable(true);

        // Аккуратная ручка-индикатор сверху: потяните вниз, чтобы плавно закрыть список всех приложений
        View drawerHandle = new View(this);
        drawerHandle.setBackground(GlassTheme.makeRoundedRect(0x55FFFFFF, dp(2)));
        LinearLayout.LayoutParams dhLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        dhLp.gravity = Gravity.CENTER_HORIZONTAL;
        dhLp.bottomMargin = dp(10);
        drawerContainer.addView(drawerHandle, dhLp);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        );
        topLp.rightMargin = dp(8);
        topLp.bottomMargin = dp(10);
        topBar.setLayoutParams(topLp);

        drawerSearchPill = new LinearLayout(this);
        drawerSearchPill.setOrientation(LinearLayout.HORIZONTAL);
        drawerSearchPill.setGravity(Gravity.CENTER_VERTICAL);
        drawerSearchPill.setPadding(dp(14), 0, dp(6), 0);
        drawerSearchPill.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ));

        drawerSearchIcon = new ImageView(this);
        drawerSearchIcon.setImageResource(R.drawable.ic_search);
        LinearLayout.LayoutParams dsiLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        dsiLp.rightMargin = dp(10);
        drawerSearchPill.addView(drawerSearchIcon, dsiLp);

        drawerSearchInput = new EditText(this);
        drawerSearchInput.setBackground(null);
        drawerSearchInput.setHint("Поиск приложений");
        drawerSearchInput.setSingleLine(true);
        drawerSearchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        drawerSearchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        drawerSearchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        drawerSearchInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ));
        drawerSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                drawerClearBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                applyFilter(s.toString(), true);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        drawerSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                if (!filteredApps.isEmpty()) {
                    launchAndReturnHome(filteredApps.get(0), v);
                }
                return true;
            }
            return false;
        });
        drawerSearchPill.addView(drawerSearchInput);

        drawerClearBtn = new FrameLayout(this);
        drawerClearBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        drawerClearBtn.setVisibility(View.GONE);
        drawerClearBtn.setOnClickListener(v -> drawerSearchInput.setText(""));
        drawerClearIcon = new ImageView(this);
        drawerClearIcon.setImageResource(R.drawable.ic_close);
        FrameLayout.LayoutParams clrLp = new FrameLayout.LayoutParams(dp(16), dp(16));
        clrLp.gravity = Gravity.CENTER;
        drawerClearBtn.addView(drawerClearIcon, clrLp);
        drawerSearchPill.addView(drawerClearBtn);

        drawerModeBtn = new FrameLayout(this);
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(dp(40), dp(44));
        modeLp.leftMargin = dp(6);
        drawerModeBtn.setLayoutParams(modeLp);
        drawerModeBtn.setOnClickListener(v -> {
            int next = (prefs.getDrawerMode() == Prefs.DRAWER_GRID)
                    ? Prefs.DRAWER_LIST : Prefs.DRAWER_GRID;
            prefs.setDrawerMode(next);
            updateDrawerModeUi();
        });
        drawerModeIcon = new ImageView(this);
        FrameLayout.LayoutParams miLp = new FrameLayout.LayoutParams(dp(19), dp(19));
        miLp.gravity = Gravity.CENTER;
        drawerModeBtn.addView(drawerModeIcon, miLp);

        drawerBatchBtn = new FrameLayout(this);
        LinearLayout.LayoutParams batchLp = new LinearLayout.LayoutParams(dp(38), dp(44));
        batchLp.leftMargin = dp(2);
        drawerBatchBtn.setLayoutParams(batchLp);
        drawerBatchBtn.setContentDescription("Выбрать несколько приложений для удаления");
        drawerBatchBtn.setOnClickListener(v -> {
            if (drawerBatchMode) exitDrawerBatchMode();
            else enterDrawerBatchMode(null);
        });
        drawerBatchIcon = VectorGlyphs.makeIconView(this, "trash", 19, Color.WHITE);
        FrameLayout.LayoutParams biLp = new FrameLayout.LayoutParams(dp(19), dp(19));
        biLp.gravity = Gravity.CENTER;
        drawerBatchBtn.addView(drawerBatchIcon, biLp);

        // Doxaiz 4.6: Чистая шестерёнка настроек прямо в строке поиска (как на референсе)
        drawerSettingsBtn = new FrameLayout(this);
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(dp(38), dp(44));
        setLp.leftMargin = dp(2);
        drawerSettingsBtn.setLayoutParams(setLp);
        drawerSettingsBtn.setContentDescription("Настройки Doxaiz");
        drawerSettingsBtn.setOnClickListener(v -> {
            if (prefs.isHapticFeedbackEnabled()) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            showScreen(SCREEN_SETTINGS);
        });
        drawerSettingsIcon = VectorGlyphs.makeIconView(this, "settings", 20, Color.WHITE);
        FrameLayout.LayoutParams siLp = new FrameLayout.LayoutParams(dp(20), dp(20));
        siLp.gravity = Gravity.CENTER;
        drawerSettingsBtn.addView(drawerSettingsIcon, siLp);

        drawerCloseBtn = new FrameLayout(this);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(36), dp(44));
        closeLp.leftMargin = dp(2);
        drawerCloseBtn.setLayoutParams(closeLp);
        drawerCloseBtn.setOnClickListener(v -> closeDrawerAnimated());
        drawerCloseIcon = new ImageView(this);
        drawerCloseIcon.setImageResource(R.drawable.ic_close);
        FrameLayout.LayoutParams ciLp = new FrameLayout.LayoutParams(dp(19), dp(19));
        ciLp.gravity = Gravity.CENTER;
        drawerCloseBtn.addView(drawerCloseIcon, ciLp);

        topBar.addView(drawerSearchPill);
        topBar.addView(drawerModeBtn);
        topBar.addView(drawerBatchBtn);
        topBar.addView(drawerSettingsBtn);
        topBar.addView(drawerCloseBtn);
        drawerContainer.addView(topBar);

        // Умный универсальный блок поиска: встроенный калькулятор + быстрые действия (скрыт при пустом запросе)
        drawerSmartBox = new LinearLayout(this);
        drawerSmartBox.setOrientation(LinearLayout.VERTICAL);
        drawerSmartBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams smartLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        smartLp.rightMargin = dp(8);
        smartLp.bottomMargin = dp(8);
        drawerSmartBox.setLayoutParams(smartLp);
        drawerContainer.addView(drawerSmartBox);

        // Компактная AMOLED-стеклянная полоса памяти устройства (ОЗУ + Встроенная память)
        drawerMemoryCard = new LinearLayout(this);
        drawerMemoryCard.setOrientation(LinearLayout.HORIZONTAL);
        drawerMemoryCard.setGravity(Gravity.CENTER_VERTICAL);
        drawerMemoryCard.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams memCardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        memCardLp.rightMargin = dp(8);
        memCardLp.bottomMargin = dp(8);
        drawerMemoryCard.setLayoutParams(memCardLp);

        // Левая половина: ОЗУ (RAM) — нажатие открывает «Работают в фоне»
        LinearLayout ramCol = new LinearLayout(this);
        ramCol.setOrientation(LinearLayout.VERTICAL);
        ramCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ramCol.setOnClickListener(v -> showBackgroundAppsModal());

        LinearLayout ramHead = new LinearLayout(this);
        ramHead.setOrientation(LinearLayout.HORIZONTAL);
        ramHead.setGravity(Gravity.CENTER_VERTICAL);
        drawerRamTitle = new TextView(this);
        drawerRamTitle.setText("ОЗУ");
        drawerRamTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        drawerRamTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        drawerRamValue = new TextView(this);
        drawerRamValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        drawerRamValue.setGravity(Gravity.END);
        drawerRamValue.setSingleLine(true);
        drawerRamValue.setEllipsize(TextUtils.TruncateAt.END);
        ramHead.addView(drawerRamTitle);
        ramHead.addView(drawerRamValue, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ramCol.addView(ramHead);

        drawerRamTrack = new FrameLayout(this);
        LinearLayout.LayoutParams rtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
        );
        rtLp.topMargin = dp(5);
        drawerRamTrack.setLayoutParams(rtLp);
        drawerRamFill = new View(this);
        drawerRamTrack.addView(drawerRamFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        drawerRamTrack.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol)) applyProgressBarWidth(drawerRamTrack, drawerRamFill, lastRamPct);
        });
        ramCol.addView(drawerRamTrack);

        // Разделитель
        drawerMemoryDivider = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(1, dp(24));
        divLp.leftMargin = dp(10);
        divLp.rightMargin = dp(10);
        drawerMemoryDivider.setLayoutParams(divLp);

        // Правая половина: Хранилище (Память) — нажатие открывает системное хранилище
        LinearLayout stCol = new LinearLayout(this);
        stCol.setOrientation(LinearLayout.VERTICAL);
        stCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stCol.setOnClickListener(v -> openStorageSettings());

        LinearLayout stHead = new LinearLayout(this);
        stHead.setOrientation(LinearLayout.HORIZONTAL);
        stHead.setGravity(Gravity.CENTER_VERTICAL);
        drawerStorageTitle = new TextView(this);
        drawerStorageTitle.setText("Память");
        drawerStorageTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        drawerStorageTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        drawerStorageValue = new TextView(this);
        drawerStorageValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        drawerStorageValue.setGravity(Gravity.END);
        drawerStorageValue.setSingleLine(true);
        drawerStorageValue.setEllipsize(TextUtils.TruncateAt.END);
        stHead.addView(drawerStorageTitle);
        stHead.addView(drawerStorageValue, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stCol.addView(stHead);

        drawerStorageTrack = new FrameLayout(this);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
        );
        stLp.topMargin = dp(5);
        drawerStorageTrack.setLayoutParams(stLp);
        drawerStorageFill = new View(this);
        drawerStorageTrack.addView(drawerStorageFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        drawerStorageTrack.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol)) applyProgressBarWidth(drawerStorageTrack, drawerStorageFill, lastStoragePct);
        });
        stCol.addView(drawerStorageTrack);

        drawerMemoryCard.addView(ramCol);
        drawerMemoryCard.addView(drawerMemoryDivider);
        drawerMemoryCard.addView(stCol);
        drawerContainer.addView(drawerMemoryCard);

        // Плашка «Активно N приложений >» как на Infinix Hot 50 (открывает список фоновых с «Остановить»)
        drawerBgActivePill = new TextView(this);
        drawerBgActivePill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        drawerBgActivePill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        drawerBgActivePill.setGravity(Gravity.CENTER);
        drawerBgActivePill.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams bgPillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bgPillLp.gravity = Gravity.CENTER_HORIZONTAL;
        bgPillLp.bottomMargin = dp(8);
        drawerBgActivePill.setLayoutParams(bgPillLp);
        drawerBgActivePill.setVisibility(View.GONE);
        drawerBgActivePill.setOnClickListener(v -> showBackgroundAppsModal());
        drawerContainer.addView(drawerBgActivePill);

        // Doxaiz 4.6: Безрамочный блок «Недавние приложения» (до 2 рядов, скрывается при поиске)
        drawerRecentCard = new LinearLayout(this);
        drawerRecentCard.setOrientation(LinearLayout.VERTICAL);
        drawerRecentCard.setPadding(dp(2), dp(2), dp(2), dp(6));
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        recLp.rightMargin = dp(8);
        recLp.bottomMargin = dp(4);
        drawerRecentCard.setLayoutParams(recLp);
        drawerRecentCard.setVisibility(View.GONE);

        drawerRecentLabel = new TextView(this);
        drawerRecentLabel.setText("Недавние приложения");
        drawerRecentLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        drawerRecentLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        drawerRecentLabel.setPadding(dp(4), dp(2), dp(4), dp(8));
        drawerRecentCard.addView(drawerRecentLabel);

        drawerRecentRow = new LinearLayout(this);
        drawerRecentRow.setOrientation(LinearLayout.VERTICAL);
        drawerRecentCard.addView(drawerRecentRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        drawerContainer.addView(drawerRecentCard);

        // Заголовок «Все приложения» над сеткой (как на чистом экране всех приложений)
        drawerAllAppsLabel = new TextView(this);
        drawerAllAppsLabel.setText("Все приложения");
        drawerAllAppsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        drawerAllAppsLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        drawerAllAppsLabel.setPadding(dp(6), dp(2), dp(6), dp(6));
        LinearLayout.LayoutParams allLblLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        allLblLp.rightMargin = dp(8);
        drawerAllAppsLabel.setLayoutParams(allLblLp);
        drawerContainer.addView(drawerAllAppsLabel);

        // Полоса умных категорий приложений (Все, Связь, Банки, Медиа, Игры, Инструменты, Система)
        drawerCategoryScroll = new HorizontalScrollView(this);
        drawerCategoryScroll.setHorizontalScrollBarEnabled(false);
        drawerCategoryScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams catScrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        catScrollLp.rightMargin = dp(8);
        catScrollLp.bottomMargin = dp(8);
        drawerCategoryScroll.setLayoutParams(catScrollLp);
        drawerCategoryScroll.setVisibility(View.GONE);

        drawerCategoryRow = new LinearLayout(this);
        drawerCategoryRow.setOrientation(LinearLayout.HORIZONTAL);
        drawerCategoryRow.setGravity(Gravity.CENTER_VERTICAL);
        drawerCategoryScroll.addView(drawerCategoryRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        drawerContainer.addView(drawerCategoryScroll);

        LinearLayout bodyRow = new LinearLayout(this);
        bodyRow.setOrientation(LinearLayout.HORIZONTAL);
        bodyRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        FrameLayout listHolder = new FrameLayout(this);
        listHolder.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ));

        drawerGrid = new GridView(this);
        drawerGrid.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        drawerGrid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        drawerGrid.setVerticalScrollBarEnabled(false);
        drawerGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        drawerGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        drawerGrid.setClipToPadding(false);
        drawerGrid.setPadding(0, dp(4), dp(4), dp(16));

        drawerAdapter = new DrawerAdapter();
        drawerGrid.setAdapter(drawerAdapter);
        drawerGrid.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < filteredApps.size()) {
                AppEntry entry = filteredApps.get(position);
                if (drawerBatchMode) {
                    toggleDrawerBatchItem(entry);
                } else {
                    launchAndReturnHome(entry, view);
                }
            }
        });
        drawerGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < filteredApps.size()) {
                AppEntry entry = filteredApps.get(position);
                if (drawerBatchMode) {
                    toggleDrawerBatchItem(entry);
                    return true;
                }
                if (prefs.isDesktopLocked()) {
                    showDrawerAppActionModal(entry);
                } else {
                    beginPendingDrawerDrag(entry, view);
                }
                return true;
            }
            return false;
        });
        drawerGrid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL && drawerSearchInput.hasFocus()) {
                    hideKeyboard();
                }
            }
            @Override
            public void onScroll(AbsListView view, int a, int b, int c) {}
        });

        drawerEmptyText = new TextView(this);
        drawerEmptyText.setText("Ничего не найдено");
        drawerEmptyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        drawerEmptyText.setGravity(Gravity.CENTER);
        drawerEmptyText.setVisibility(View.GONE);
        listHolder.addView(drawerGrid);
        listHolder.addView(drawerEmptyText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        alphabetBar = new LinearLayout(this);
        alphabetBar.setOrientation(LinearLayout.VERTICAL);
        alphabetBar.setGravity(Gravity.CENTER);
        alphabetBar.setLayoutParams(new LinearLayout.LayoutParams(
                dp(24), ViewGroup.LayoutParams.MATCH_PARENT
        ));
        alphabetBar.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                int count = alphabetBar.getChildCount();
                if (count > 0 && v.getHeight() > 0) {
                    int idx = (int) ((event.getY() / (float) v.getHeight()) * count);
                    idx = Math.max(0, Math.min(count - 1, idx));
                    View child = alphabetBar.getChildAt(idx);
                    if (child != null && child.getTag() instanceof Integer) {
                        drawerGrid.setSelection((Integer) child.getTag());
                    }
                }
                return true;
            }
            return false;
        });

        bodyRow.addView(listHolder);
        bodyRow.addView(alphabetBar);
        drawerContainer.addView(bodyRow);

        // Doxaiz 4.1: Нижняя панель пакетного выбора и быстрого удаления (видна только в drawerBatchMode)
        drawerBatchBar = new LinearLayout(this);
        drawerBatchBar.setOrientation(LinearLayout.HORIZONTAL);
        drawerBatchBar.setGravity(Gravity.CENTER_VERTICAL);
        drawerBatchBar.setBackground(GlassTheme.makeGlassRect(
                this, 0xF01C1D23, 0x38FFFFFF, 18f, 1f
        ));
        drawerBatchBar.setPadding(dp(14), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bbLp.topMargin = dp(6);
        bbLp.rightMargin = dp(8);
        drawerBatchBar.setLayoutParams(bbLp);
        drawerBatchBar.setVisibility(View.GONE);

        drawerBatchCountTv = new TextView(this);
        drawerBatchCountTv.setText("Выберите приложения");
        drawerBatchCountTv.setTextColor(Color.WHITE);
        drawerBatchCountTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        drawerBatchCountTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        drawerBatchBar.addView(drawerBatchCountTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        TextView batchCancelBtn = new TextView(this);
        batchCancelBtn.setText("Отмена");
        batchCancelBtn.setTextColor(GlassTheme.TEXT_SECONDARY);
        batchCancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        batchCancelBtn.setPadding(dp(10), dp(7), dp(10), dp(7));
        batchCancelBtn.setOnClickListener(v -> exitDrawerBatchMode());
        drawerBatchBar.addView(batchCancelBtn);

        drawerBatchDeleteBtn = new TextView(this);
        drawerBatchDeleteBtn.setText("Удалить");
        drawerBatchDeleteBtn.setTextColor(Color.WHITE);
        drawerBatchDeleteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        drawerBatchDeleteBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        drawerBatchDeleteBtn.setBackground(GlassTheme.makeRoundedRect(0xFFEF4444, dp(12)));
        drawerBatchDeleteBtn.setPadding(dp(14), dp(7), dp(14), dp(7));
        drawerBatchDeleteBtn.setOnClickListener(v -> startBatchUninstallSelected());
        LinearLayout.LayoutParams bdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bdLp.leftMargin = dp(4);
        drawerBatchBar.addView(drawerBatchDeleteBtn, bdLp);
        drawerContainer.addView(drawerBatchBar);
    }

    private void buildSettingsLayer() {
        settingsContainer = new LinearLayout(this);
        settingsContainer.setOrientation(LinearLayout.VERTICAL);
        settingsContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        settingsContainer.setVisibility(View.GONE);
        settingsContainer.setClickable(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        );
        headerLp.bottomMargin = dp(12);
        header.setLayoutParams(headerLp);

        settingsBackBtn = new FrameLayout(this);
        settingsBackBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        settingsBackBtn.setOnClickListener(v -> showScreen(SCREEN_HOME));
        settingsBackIcon = new ImageView(this);
        settingsBackIcon.setImageResource(R.drawable.ic_back);
        FrameLayout.LayoutParams bIconLp = new FrameLayout.LayoutParams(dp(20), dp(20));
        bIconLp.gravity = Gravity.CENTER;
        settingsBackBtn.addView(settingsBackIcon, bIconLp);

        settingsTitleView = new TextView(this);
        settingsTitleView.setText("Настройки Doxaiz");
        settingsTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        settingsTitleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        tLp.leftMargin = dp(12);
        settingsTitleView.setLayoutParams(tLp);

        header.addView(settingsBackBtn);
        header.addView(settingsTitleView);
        settingsContainer.addView(header);

        // Строка мгновенного поиска по настройкам Doxaiz
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(12), dp(6), dp(8), dp(6));
        searchBar.setBackground(GlassTheme.makeGlassRect(this, GlassTheme.DRAWER_SEARCH_BG, theme.cardBorderColor, 16f, 1f));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        );
        sbLp.bottomMargin = dp(10);
        searchBar.setLayoutParams(sbLp);

        ImageView sIco = VectorGlyphs.makeIconView(this, "search", 16, 0xFF9EA0A6);
        LinearLayout.LayoutParams siLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        siLp.rightMargin = dp(9);
        searchBar.addView(sIco, siLp);

        settingsSearchInput = new EditText(this);
        settingsSearchInput.setBackground(null);
        settingsSearchInput.setHint("Поиск по настройкам (шторка, звук, сетка...)");
        settingsSearchInput.setHintTextColor(0xFF7D7F87);
        settingsSearchInput.setTextColor(theme.textPrimary);
        settingsSearchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        settingsSearchInput.setSingleLine(true);
        settingsSearchInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        settingsSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                settingsSearchQuery = (s != null) ? s.toString().trim() : "";
                if (settingsSearchClearBtn != null) {
                    settingsSearchClearBtn.setVisibility(settingsSearchQuery.isEmpty() ? View.GONE : View.VISIBLE);
                }
                applySettingsSearchFilter();
            }
        });
        searchBar.addView(settingsSearchInput);

        settingsSearchClearBtn = new FrameLayout(this);
        settingsSearchClearBtn.setVisibility(View.GONE);
        ImageView clrIv = VectorGlyphs.makeIconView(this, "close", 15, 0xFF9EA0A6);
        FrameLayout.LayoutParams cIvLp = new FrameLayout.LayoutParams(dp(15), dp(15));
        cIvLp.gravity = Gravity.CENTER;
        settingsSearchClearBtn.addView(clrIv, cIvLp);
        settingsSearchClearBtn.setOnClickListener(v -> settingsSearchInput.setText(""));
        searchBar.addView(settingsSearchClearBtn, new LinearLayout.LayoutParams(dp(30), dp(30)));
        settingsContainer.addView(searchBar);

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        settingsContent = new LinearLayout(this);
        settingsContent.setOrientation(LinearLayout.VERTICAL);
        settingsContent.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scroll.addView(settingsContent);
        settingsContainer.addView(scroll);
    }

    private void buildDragLayer() {
        dragOverlay = new FrameLayout(this);
        dragOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        dragOverlay.setVisibility(View.GONE);

        dragRemoveBar = new TextView(this);
        dragRemoveBar.setText("Убрать со страницы");
        dragRemoveBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        dragRemoveBar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dragRemoveBar.setGravity(Gravity.CENTER);
        dragRemoveBar.setPadding(dp(20), dp(10), dp(20), dp(10));
        FrameLayout.LayoutParams rbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rbLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        rbLp.topMargin = insetTop + dp(10);
        dragOverlay.addView(dragRemoveBar, rbLp);

        dragGhost = new FrameLayout(this);
        dragGhost.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        dragOverlay.addView(dragGhost);
    }

    private void buildModalLayer() {
        modalOverlay = new FrameLayout(this);
        modalOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        modalOverlay.setBackgroundColor(Color.argb(165, 0, 0, 0));
        modalOverlay.setVisibility(View.GONE);
        modalOverlay.setClickable(true);
        modalOverlay.setOnClickListener(v -> dismissModal());
    }

    // =========================================================================
    // Применение темы (AMOLED-стекло по умолчанию)
    // =========================================================================

    private void applyTheme() {
        lastDrawerRecentSig = "";
        lastAlphabetBarSig = "";
        theme = GlassTheme.create(prefs.getVisualStyle(), prefs.getGlassOpacity(), false);
        updateSystemBarIconColors();

        homeContainer.setBackgroundColor(theme.homeScrimColor);
        if (animeBackdrop != null) animeBackdrop.applyConfig(prefs.getWallpaperMode(), prefs.getVisualStyle(), prefs.getWallpaperDimLevel());
        topStrip.setVisibility(prefs.isHideTopSettingsBtn() ? View.GONE : View.VISIBLE);
        settingsBtn.setBackground(GlassTheme.makeGlassRect(
                this, theme.pillBgColor, theme.pillBorderColor, 18f, 1f
        ));
        tintIcon(settingsBtnIcon, theme.textPrimary);

        // Doxaiz 4.6: Чистый безрамочный док со свободными иконками (без тяжёлой капсулы)
        dockCard.setBackground(null);
        boolean classicPagesOnly = prefs.getHomeLayoutMode() == Prefs.HOME_MODE_CLASSIC_PAGES;
        dockDrawerBtn.setVisibility((prefs.isShowDockDrawerBtn() && !classicPagesOnly) ? View.VISIBLE : View.GONE);
        dockDrawerBtn.setBackground(GlassTheme.makeGlassRect(
                this, theme.chipInactiveBg, theme.cardBorderColor, 16f, 1f
        ));
        tintIcon(dockDrawerIcon, theme.textPrimary);

        boolean redmiDrawer = prefs.getDrawerStyle() == Prefs.DRAWER_STYLE_REDMI;
        drawerContainer.setBackgroundColor(theme.overlayBgColor);
        drawerSearchPill.setBackground(GlassTheme.makeRoundedRect(GlassTheme.DRAWER_SEARCH_BG, dp(16)));
        tintIcon(drawerSearchIcon, 0xFF9EA0A8);
        drawerSearchInput.setTextColor(theme.textPrimary);
        drawerSearchInput.setHintTextColor(0xFF8E9098);
        tintIcon(drawerClearIcon, theme.textSecondary);

        drawerModeBtn.setBackground(null);
        tintIcon(drawerModeIcon, theme.textPrimary);
        updateDrawerBatchBtnStyle();
        if (drawerSettingsBtn != null) {
            drawerSettingsBtn.setBackground(null);
            if (drawerSettingsIcon != null) tintIcon(drawerSettingsIcon, theme.textPrimary);
        }
        drawerCloseBtn.setBackground(null);
        tintIcon(drawerCloseIcon, theme.textPrimary);
        drawerEmptyText.setTextColor(theme.textHint);

        drawerMemoryCard.setBackground(GlassTheme.makeGlassRect(
                this,
                redmiDrawer ? theme.chipInactiveBg : theme.cardBgColor,
                redmiDrawer ? Color.TRANSPARENT : theme.cardBorderColor,
                redmiDrawer ? 10f : 16f, redmiDrawer ? 0f : 1f
        ));
        drawerRamTitle.setTextColor(theme.textPrimary);
        drawerRamValue.setTextColor(theme.textSecondary);
        drawerRamTrack.setBackground(GlassTheme.makeGlassRect(
                this, theme.chipInactiveBg, Color.TRANSPARENT, 2f, 0f
        ));
        drawerRamFill.setBackground(GlassTheme.makeGlassRect(
                this, theme.accentColor, Color.TRANSPARENT, 2f, 0f
        ));
        drawerMemoryDivider.setBackgroundColor(theme.dividerColor);
        drawerStorageTitle.setTextColor(theme.textPrimary);
        drawerStorageValue.setTextColor(theme.textSecondary);
        drawerStorageTrack.setBackground(GlassTheme.makeGlassRect(
                this, theme.chipInactiveBg, Color.TRANSPARENT, 2f, 0f
        ));
        drawerStorageFill.setBackground(GlassTheme.makeGlassRect(
                this, theme.accentColor, Color.TRANSPARENT, 2f, 0f
        ));

        // Doxaiz 4.6: Безрамочный чистый блок недавних приложений
        drawerRecentCard.setBackground(null);
        drawerRecentLabel.setTextColor(0xFF8E9098);
        if (drawerAllAppsLabel != null) {
            drawerAllAppsLabel.setTextColor(0xFF8E9098);
        }

        settingsContainer.setBackgroundColor(theme.overlayBgColor);
        settingsBackBtn.setBackground(GlassTheme.makeGlassRect(
                this, theme.pillBgColor, theme.pillBorderColor, 21f, 1f
        ));
        tintIcon(settingsBackIcon, theme.textPrimary);
        settingsTitleView.setTextColor(theme.textPrimary);
        if (settingsSearchInput != null) {
            settingsSearchInput.setTextColor(theme.textPrimary);
            settingsSearchInput.setHintTextColor(theme.textHint);
            android.view.ViewParent p = settingsSearchInput.getParent();
            if (p instanceof View) ((View) p).setBackground(GlassTheme.makeGlassRect(this,
                    GlassTheme.DRAWER_SEARCH_BG, theme.cardBorderColor, 16f, 1f));
        }
        if (pagerView != null) {
            pagerView.setSmoothScrollEnabled(prefs.isSmoothAnimationsEnabled());
            pagerView.setLoopPagesEnabled(prefs.isLoopHomePages());
            pagerView.setTransitionStyle(prefs.getPageTransitionStyle());
        }

        updateDrawerModeUi();
        rebuildDesktop();
        refreshDrawerMemory();
        rebuildDrawerRecent();
        rebuildDrawerCategories();
        rebuildAlphabetBar();
        if (currentScreen == SCREEN_SETTINGS) {
            rebuildSettingsContent();
        }
    }

    private void updateDrawerModeUi() {
        boolean isList = prefs.getDrawerMode() == Prefs.DRAWER_LIST;
        drawerModeIcon.setImageResource(isList ? R.drawable.ic_apps : R.drawable.ic_view_list);
        tintIcon(drawerModeIcon, theme.textPrimary);
        drawerGrid.setNumColumns(isList ? 1 : prefs.getColumns());
        drawerGrid.setVerticalSpacing(isList ? dp(6) : dp(10));
        drawerGrid.setHorizontalSpacing(isList ? 0 : dp(4));
        drawerAdapter.notifyDataSetChanged();
    }

    // =========================================================================
    // Переключение экранов
    // =========================================================================

    private void showScreen(int screen) {
        dismissModal();
        hideKeyboard();
        int prevScreen = currentScreen;
        boolean changed = (prevScreen != screen);
        currentScreen = screen;
        syncAmbientParticles();
        drawerClosingAnim = false;
        drawerDragDownActive = false;

        homeContainer.animate().cancel();
        drawerContainer.animate().cancel();
        settingsContainer.animate().cancel();

        if (screen == SCREEN_DRAWER && prevScreen == SCREEN_HOME && changed) {
            // Плавный и мгновенный выезд списка всех приложений на GPU hardware layer (120 FPS)
            settingsContainer.setVisibility(View.GONE);
            homeContainer.setVisibility(View.VISIBLE);
            drawerContainer.setVisibility(View.VISIBLE);
            int h = rootContainer.getHeight();
            float startY = Math.max(dp(140), h > 0 ? h * 0.22f : dp(180));
            drawerContainer.setAlpha(0.24f);
            drawerContainer.setTranslationY(startY);
            drawerContainer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2.3f))
                    .withLayer()
                    .withEndAction(() -> {
                        if (currentScreen == SCREEN_DRAWER) {
                            homeContainer.setVisibility(View.GONE);
                            homeContainer.setScaleX(1f);
                            homeContainer.setScaleY(1f);
                            homeContainer.setAlpha(1f);
                        }
                    })
                    .start();
            homeContainer.animate()
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .alpha(0.75f)
                    .setDuration(165L)
                    .setInterpolator(SMOOTH_INTERPOLATOR)
                    .withLayer()
                    .start();
        } else {
            homeContainer.setVisibility(screen == SCREEN_HOME ? View.VISIBLE : View.GONE);
            homeContainer.setScaleX(1f);
            homeContainer.setScaleY(1f);
            drawerContainer.setVisibility(screen == SCREEN_DRAWER ? View.VISIBLE : View.GONE);
            drawerContainer.setTranslationY(0f);
            drawerContainer.setAlpha(1f);
            settingsContainer.setVisibility(screen == SCREEN_SETTINGS ? View.VISIBLE : View.GONE);

            View active = (screen == SCREEN_HOME) ? homeContainer
                    : (screen == SCREEN_DRAWER ? drawerContainer : settingsContainer);
            if (changed && prefs.isSmoothAnimationsEnabled()) {
                active.setAlpha(0f);
                active.setTranslationY(dp(10));
                active.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(155L)
                        .setInterpolator(SMOOTH_INTERPOLATOR)
                        .withLayer()
                        .start();
            } else {
                active.setAlpha(1f);
                active.setTranslationY(0f);
            }
        }

        if (screen != SCREEN_SETTINGS && settingsSearchInput != null && settingsSearchInput.length() > 0) {
            settingsSearchInput.setText("");
        }
        if (screen == SCREEN_HOME) {
            hideKeyboard();
            if (drawerSearchInput.length() > 0) drawerSearchInput.setText("");
            selectedDrawerCategory = AppCategoryHelper.CAT_ALL;
            selectedCustomSectionId = null;
            lastSearchQueryLen = 0;
            drawerGrid.setSelection(0);
        } else if (screen == SCREEN_DRAWER) {
            // applyFilter уже обновляет недавние, категории и алфавит — убираем двойную пересборку на 1-м кадре!
            applyFilter(drawerSearchInput.getText().toString(), false);
            // Системные вызовы StatFs/MemoryInfo и фоновый прогрев запускаем сразу после старта 1-го кадра анимации
            uiHandler.post(() -> {
                if (currentScreen == SCREEN_DRAWER && !isFinishing() && !isDestroyed()) {
                    refreshDrawerMemory();
                    prewarmDrawerIcons();
                }
            });
        } else if (screen == SCREEN_SETTINGS) {
            rebuildSettingsContent();
        }
    }

    private void closeDrawerAnimated() {
        if (currentScreen != SCREEN_DRAWER || drawerClosingAnim) {
            showScreen(SCREEN_HOME);
            return;
        }
        dismissModal();
        hideKeyboard();
        if (drawerBatchMode) exitDrawerBatchMode();
        drawerClosingAnim = true;
        drawerDragDownActive = false;
        currentScreen = SCREEN_HOME;

        homeContainer.animate().cancel();
        homeContainer.setVisibility(View.VISIBLE);
        if (homeContainer.getAlpha() > 0.98f && homeContainer.getScaleX() > 0.99f) {
            homeContainer.setScaleX(0.96f);
            homeContainer.setScaleY(0.96f);
            homeContainer.setAlpha(0.8f);
        }
        homeContainer.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(165L)
                .setInterpolator(SMOOTH_INTERPOLATOR)
                .withLayer()
                .start();

        int h = rootContainer.getHeight();
        float endY = Math.max(dp(180), h > 0 ? h * 0.28f : dp(220));
        drawerContainer.animate().cancel();
        drawerContainer.animate()
                .translationY(endY)
                .alpha(0f)
                .setDuration(160L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2.0f))
                .withLayer()
                .withEndAction(() -> {
                    drawerClosingAnim = false;
                    drawerContainer.setVisibility(View.GONE);
                    drawerContainer.setTranslationY(0f);
                    drawerContainer.setAlpha(1f);
                    if (drawerSearchInput.length() > 0) drawerSearchInput.setText("");
                    selectedDrawerCategory = AppCategoryHelper.CAT_ALL;
                    selectedCustomSectionId = null;
                    lastSearchQueryLen = 0;
                    drawerGrid.setSelection(0);
                })
                .start();
    }

    private boolean isDrawerScrolledToTop() {
        if (drawerGrid == null || drawerGrid.getChildCount() == 0) return true;
        if (drawerGrid.getFirstVisiblePosition() > 0) return false;
        View first = drawerGrid.getChildAt(0);
        return first == null || first.getTop() >= drawerGrid.getPaddingTop() - dp(2);
    }

    private void openDrawer(boolean forceFocusSearch) {
        showScreen(SCREEN_DRAWER);
        if (forceFocusSearch || prefs.isAutoKeyboardDrawer()) {
            drawerSearchInput.requestFocus();
            drawerSearchInput.post(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(drawerSearchInput, InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }

    private void prewarmDrawerIcons() {
        if (repository == null || visibleApps.isEmpty()) return;
        int lim = Math.min(24, visibleApps.size());
        List<String> keys = new ArrayList<>(lim);
        for (int i = 0; i < lim; i++) {
            keys.add(visibleApps.get(i).key);
        }
        repository.prewarmIcons(keys);
    }

    private void runQuickRamBoost() {
        MemoryHelper.BoostResult res = MemoryHelper.boostRam(this, visibleApps);
        refreshDrawerMemory();
        refreshDrawerBgPill();
        if (currentScreen == SCREEN_SETTINGS) {
            rebuildSettingsContent();
        }
        Toast.makeText(this, res.message, Toast.LENGTH_SHORT).show();
    }

    private void refreshDrawerMemory() {
        if (drawerMemoryCard == null) return;
        if (!prefs.isShowDrawerMemory()) {
            drawerMemoryCard.setVisibility(View.GONE);
            return;
        }
        drawerMemoryCard.setVisibility(View.VISIBLE);
        final MemoryHelper.Snapshot snap = MemoryHelper.read(this);
        lastRamPct = snap.ramUsedPercent;
        lastStoragePct = snap.storageUsedPercent;
        drawerRamValue.setText(snap.ramShortText);
        drawerStorageValue.setText(snap.storageShortText);
        drawerRamTrack.post(() -> applyProgressBarWidth(drawerRamTrack, drawerRamFill, lastRamPct));
        drawerStorageTrack.post(() -> applyProgressBarWidth(drawerStorageTrack, drawerStorageFill, lastStoragePct));
        refreshDrawerBgPill();
    }

    private void refreshDrawerBgPill() {
        if (drawerBgActivePill == null) return;
        boolean searching = drawerSearchInput != null && drawerSearchInput.getText().length() > 0;
        int count = DoxaizNotificationService.getBackgroundAppCount();
        if (!prefs.isShowDrawerMemory() || searching || count <= 0) {
            drawerBgActivePill.setVisibility(View.GONE);
            return;
        }
        drawerBgActivePill.setText("Активно " + formatAppsRu(count) + " >");
        drawerBgActivePill.setTextColor(theme.textPrimary);
        drawerBgActivePill.setBackground(GlassTheme.makeGlassRect(
                this, theme.pillBgColor, theme.pillBorderColor, 18f, 1f
        ));
        drawerBgActivePill.setVisibility(View.VISIBLE);
    }

    private void applyProgressBarWidth(FrameLayout track, View fill, int percent) {
        int tw = track.getWidth();
        if (tw <= 0) return;
        int fw = Math.max(dp(3), (tw * Math.max(0, Math.min(100, percent))) / 100);
        ViewGroup.LayoutParams lp = fill.getLayoutParams();
        if (lp.width != fw) {
            lp.width = fw;
            fill.setLayoutParams(lp);
        }
    }

    private String lastDrawerRecentSig = "";

    private void rebuildDrawerRecent() {
        if (drawerRecentCard == null) return;
        boolean searching = drawerSearchInput != null && drawerSearchInput.getText().length() > 0;
        if (drawerAllAppsLabel != null) {
            if (searching) {
                drawerAllAppsLabel.setVisibility(View.GONE);
            } else {
                drawerAllAppsLabel.setVisibility(View.VISIBLE);
                if (selectedCustomSectionId != null) {
                    CustomSection sec = CustomSection.findById(prefs.getCustomSections(), selectedCustomSectionId);
                    drawerAllAppsLabel.setText(sec != null ? sec.name : "Все приложения");
                } else if (selectedDrawerCategory != AppCategoryHelper.CAT_ALL) {
                    drawerAllAppsLabel.setText(AppCategoryHelper.getTitle(selectedDrawerCategory));
                } else {
                    drawerAllAppsLabel.setText("Все приложения");
                }
            }
        }
        if (!prefs.isShowRecentApps() || searching) {
            drawerRecentCard.setVisibility(View.GONE);
            return;
        }
        int cols = prefs.getColumns();
        // Doxaiz 4.6: до 2 рядов × cols (8 приложений в 4-колоночной сетке, как на референсе)
        int maxRecent = cols * 2;
        List<AppEntry> recent = prefs.getFrequentApps(visibleApps, maxRecent);
        if (recent.isEmpty()) {
            drawerRecentCard.setVisibility(View.GONE);
            return;
        }
        int iconDp = Math.min(50, getIconDp());
        int rowCount = (recent.size() + cols - 1) / cols;
        StringBuilder sig = new StringBuilder(140);
        sig.append(cols).append(':').append(iconDp).append(':').append(theme.textPrimary);
        for (int i = 0; i < recent.size(); i++) {
            AppEntry e = recent.get(i);
            sig.append('|').append(e.key).append('=').append(e.displayLabel);
        }
        String nextSig = sig.toString();
        if (nextSig.equals(lastDrawerRecentSig) && drawerRecentRow.getChildCount() == rowCount) {
            drawerRecentCard.setVisibility(View.VISIBLE);
            return;
        }
        lastDrawerRecentSig = nextSig;

        drawerRecentCard.setVisibility(View.VISIBLE);
        drawerRecentRow.removeAllViews();
        for (int r = 0; r < rowCount; r++) {
            LinearLayout rowLine = new LinearLayout(this);
            rowLine.setOrientation(LinearLayout.HORIZONTAL);
            rowLine.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (r > 0) rlLp.topMargin = dp(8);
            rowLine.setLayoutParams(rlLp);

            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                if (i < recent.size()) {
                    final AppEntry entry = recent.get(i);
                    LinearLayout tile = new LinearLayout(this);
                    tile.setOrientation(LinearLayout.VERTICAL);
                    tile.setGravity(Gravity.CENTER_HORIZONTAL);
                    tile.setPadding(dp(2), dp(2), dp(2), dp(2));

                    ImageView iv = new ImageView(this);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    tile.addView(iv, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
                    repository.bindIcon(entry, iv);

                    TextView lbl = new TextView(this);
                    lbl.setText(formatAppLabel(entry));
                    lbl.setTextColor(theme.textPrimary);
                    lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                    lbl.setGravity(Gravity.CENTER);
                    lbl.setSingleLine(true);
                    lbl.setEllipsize(TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    lLp.topMargin = dp(4);
                    tile.addView(lbl, lLp);

                    tile.setOnClickListener(v -> launchAndReturnHome(entry, iv));
                    tile.setOnLongClickListener(v -> {
                        showDrawerAppActionModal(entry);
                        return true;
                    });
                    rowLine.addView(tile, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ));
                } else {
                    rowLine.addView(new View(this), new LinearLayout.LayoutParams(0, dp(1), 1f));
                }
            }
            drawerRecentRow.addView(rowLine);
        }
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) focus = drawerSearchInput;
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
    }

    // =========================================================================
    // Обновление приложений и построение многостраничного рабочего стола
    // =========================================================================

    @Override
    public void onAppsUpdated(List<AppEntry> visible, List<AppEntry> hidden,
                              Map<String, AppEntry> byKey, String newlyInstalledPkg) {
        lastDrawerRecentSig = "";
        lastAlphabetBarSig = "";
        visibleApps.clear();
        visibleApps.addAll(visible);
        hiddenApps.clear();
        hiddenApps.addAll(hidden);
        hiddenAppKeys.clear();
        for (AppEntry he : hidden) {
            hiddenAppKeys.add(he.key);
        }
        appsByKey.clear();
        appsByKey.putAll(byKey);

        boolean bottomAlign = prefs.isHomeBottomAligned();
        if (!desktop.isInitialized() && !visible.isEmpty()) {
            // Пробуем бережно перенести раскладку и точные координаты (включая 5 столбцов XOS)
            LayoutImporter.Result imp = LayoutImporter.tryImportFromSystemLauncher(this, appsByKey, desktop, prefs);
            int effectiveCols = prefs.getColumns();
            if (!imp.success) {
                desktop.buildInitialLayout(visible, effectiveCols, bottomAlign);
                if (prefs.getHomeLayoutMode() == Prefs.HOME_MODE_CLASSIC_PAGES) {
                    desktop.spreadAllAppsAcrossPages(visible, effectiveCols);
                }
            } else {
                Toast.makeText(
                        this,
                        "Перенесено из " + imp.sourceName + ": " + imp.importedApps + " иконок (" + imp.detectedCols + " ст.)",
                        Toast.LENGTH_SHORT
                ).show();
            }
        } else {
            // ВАЖНО: восстанавливаем ключи переименованных Activity на тех же клетках (col, row),
            // никогда не удаляя и не сдвигая расставленные пользователем иконки!
            desktop.reconcileRenamedActivities(appsByKey);
            int cols = prefs.getColumns();
            if (newlyInstalledPkg != null && prefs.isAutoAddNewApps() && !prefs.isDesktopLocked()) {
                int lastPage = Math.max(0, desktop.pages.size() - 1);
                for (AppEntry e : visible) {
                    if (newlyInstalledPkg.equals(e.packageName) && !desktop.containsAppAnywhere(e.key)) {
                        desktop.addAppToPage(lastPage, e.key, cols, lastPage == 0 && bottomAlign);
                    }
                }
            }
        }

        repository.prewarmIcons(desktop.collectPriorityIconKeys(currentPage));
        rebuildDesktop();
        if (currentScreen == SCREEN_DRAWER) {
            rebuildDrawerCategories();
        }
        applyFilter(drawerSearchInput != null ? drawerSearchInput.getText().toString() : "", false);
        rebuildDrawerRecent();

        // Если сейчас открыта папка — обновим её содержимое
        if (openFolderId != null && openFolderPage >= 0 && modalOverlay.getVisibility() == View.VISIBLE) {
            DesktopItem folder = desktop.findFolder(openFolderPage, openFolderId);
            if (folder != null) {
                showFolderModal(openFolderPage, folder);
            } else {
                dismissModal();
            }
        }

        if (currentScreen == SCREEN_SETTINGS) {
            rebuildSettingsContent();
        }
    }

    private static final class BadgeSlot {
        final FrameLayout container;
        final String singlePkg;
        final DesktopItem folder;
        View badgeView;
        int lastCount;
        int lastMode;

        BadgeSlot(FrameLayout container, String singlePkg, DesktopItem folder,
                  View badgeView, int lastCount, int lastMode) {
            this.container = container;
            this.singlePkg = singlePkg;
            this.folder = folder;
            this.badgeView = badgeView;
            this.lastCount = lastCount;
            this.lastMode = lastMode;
        }
    }

    private final List<BadgeSlot> desktopBadgeSlots = new ArrayList<>();
    private final List<BadgeSlot> dockBadgeSlots = new ArrayList<>();

    private void rebuildDesktop() {
        cancelDragCleanup();
        desktopBadgeSlots.clear();
        int cols = prefs.getColumns();
        desktop.normalizeAllPages(cols, prefs.isHomeBottomAligned());
        if (currentPage >= desktop.pages.size()) {
            currentPage = desktop.pages.size() - 1;
        }

        List<View> pageViews = new ArrayList<>();
        for (int p = 0; p < desktop.pages.size(); p++) {
            pageViews.add(buildDesktopPageView(p, desktop.pages.get(p)));
        }
        pagerView.setPages(pageViews, currentPage);
        rebuildPageDots();
        rebuildDock();
    }

    /**
     * Мгновенное точечное обновление бейджей уведомлений на рабочем столе и в доке (< 0.1 мс):
     * не пересоздаёт страницы, не дёргает скроллер DesktopPagerView и не тратит кадры на 120 Гц.
     */
    private void refreshBadgesInPlace() {
        if (multiSelectMode) return;
        if (desktopBadgeSlots.isEmpty() && dockBadgeSlots.isEmpty()) {
            rebuildDesktop();
            return;
        }
        int mode = prefs.getNotifBadgeMode();
        updateBadgeSlotList(desktopBadgeSlots, mode);
        updateBadgeSlotList(dockBadgeSlots, mode);
    }

    private void updateBadgeSlotList(List<BadgeSlot> slots, int mode) {
        for (BadgeSlot slot : slots) {
            boolean show;
            int count;
            if (slot.folder != null) {
                show = hasFolderNotifDot(slot.folder);
                count = show ? getFolderUnreadCount(slot.folder) : 0;
            } else {
                show = hasNotifDot(slot.singlePkg);
                count = show ? DoxaizNotificationService.getUnreadCount(slot.singlePkg) : 0;
            }
            int effCount = show ? Math.max(1, count) : 0;
            if (effCount == slot.lastCount && mode == slot.lastMode) continue;

            if (effCount <= 0 || mode == Prefs.NOTIF_BADGE_NONE) {
                if (slot.badgeView != null) {
                    slot.container.removeView(slot.badgeView);
                    slot.badgeView = null;
                }
            } else if (slot.badgeView instanceof TextView
                    && mode == Prefs.NOTIF_BADGE_NUMBERS
                    && slot.lastMode == Prefs.NOTIF_BADGE_NUMBERS) {
                // Обновляем цифру счётчика прямо в существующем TextView без пересоздания View!
                ((TextView) slot.badgeView).setText(effCount > 99 ? "99+" : String.valueOf(effCount));
            } else {
                if (slot.badgeView != null) {
                    slot.container.removeView(slot.badgeView);
                }
                slot.badgeView = addNotifBadge(slot.container, effCount);
            }
            slot.lastCount = effCount;
            slot.lastMode = mode;
        }
    }

    private void registerBadgeSlot(List<BadgeSlot> slots, FrameLayout container,
                                   String singlePkg, DesktopItem folder) {
        int mode = prefs.getNotifBadgeMode();
        boolean show = (folder != null) ? hasFolderNotifDot(folder) : hasNotifDot(singlePkg);
        int count = show ? (folder != null ? getFolderUnreadCount(folder)
                : DoxaizNotificationService.getUnreadCount(singlePkg)) : 0;
        int effCount = show ? Math.max(1, count) : 0;
        View bv = (effCount > 0 && mode != Prefs.NOTIF_BADGE_NONE)
                ? addNotifBadge(container, effCount) : null;
        slots.add(new BadgeSlot(container, singlePkg, folder, bv, effCount, mode));
    }

    private View buildDesktopPageView(final int pageIndex, List<DesktopItem> rawItems) {
        FrameLayout wrapper = new FrameLayout(this);

        int cols = prefs.getColumns();
        int iconDp = getIconDp();
        boolean showLabels = prefs.isShowHomeLabels();

        final FreeGridPageView grid = new FreeGridPageView(this, cols, DesktopModel.GRID_ROWS);
        grid.setPadding(dp(10), dp(4), dp(10), dp(6));

        int visibleCount = 0;
        for (int i = 0; i < rawItems.size(); i++) {
            final int itemIndex = i;
            final DesktopItem item = rawItems.get(i);
            View tile = null;
            if (item.type == DesktopItem.TYPE_FOLDER) {
                tile = createFolderTile(pageIndex, itemIndex, item, iconDp, showLabels);
            } else if (appsByKey.containsKey(item.appKey) && !isHiddenKey(item.appKey)) {
                tile = createDesktopAppTile(pageIndex, itemIndex, item, appsByKey.get(item.appKey), iconDp, showLabels);
            }
            if (tile != null) {
                visibleCount++;
                grid.addView(tile, new FreeGridPageView.LayoutParams(item.col, item.row));
            }
        }

        if (visibleCount == 0) {
            TextView hint = new TextView(this);
            hint.setText("Страница " + (pageIndex + 1)
                    + "\nУдерживайте пустую клетку, чтобы добавить приложение или папку");
            hint.setGravity(Gravity.CENTER);
            hint.setTextColor(theme.textHint);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            hint.setPadding(dp(28), dp(16), dp(28), dp(16));
            FrameLayout.LayoutParams hLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            hLp.gravity = Gravity.CENTER;
            wrapper.addView(hint, hLp);
        }

        // Отслеживаем точную пустую ячейку под пальцем для меню и двойного касания
        final int[] downCell = new int[]{0, 0};
        final float[] downXY = new float[]{0f, 0f};
        grid.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downXY[0] = event.getX();
                downXY[1] = event.getY();
                grid.pointToCell(event.getX(), event.getY(), downCell);
            }
            return false;
        });

        grid.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            float dx = downXY[0] - lastEmptyTapX;
            float dy = downXY[1] - lastEmptyTapY;
            boolean withinDist = (dx * dx + dy * dy) <= dp(44) * dp(44);
            if (now - lastEmptyTapTime <= 340L && withinDist) {
                lastEmptyTapTime = 0L;
                executeGesture(prefs.getDoubleTapAction());
            } else {
                lastEmptyTapTime = now;
                lastEmptyTapX = downXY[0];
                lastEmptyTapY = downXY[1];
            }
        });

        grid.setOnLongClickListener(v -> {
            if (dragging || dragPending) return true;
            lastEmptyTapTime = 0L;
            if (prefs.isHapticFeedbackEnabled()) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            showPageEmptyMenu(pageIndex, downCell[0], downCell[1]);
            return true;
        });

        wrapper.setTag(grid);
        wrapper.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return wrapper;
    }

    private View createDesktopAppTile(
            final int pageIndex,
            final int itemIndex,
            final DesktopItem item,
            final AppEntry entry,
            int iconDp,
            boolean showLabel
    ) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(2), dp(1), dp(2), dp(1));

        FrameLayout iconWrap = new FrameLayout(this);
        iconWrap.setLayoutParams(new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconWrap.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        repository.bindIcon(entry, icon);
        final FrameLayout checkBadgeRef;
        if (multiSelectMode) {
            boolean picked = selectedAppKeys.contains(entry.key);
            FrameLayout checkBadge = new FrameLayout(this);
            applyMultiSelectTileVisual(iconWrap, checkBadge, picked, false);
            FrameLayout.LayoutParams cbLp = new FrameLayout.LayoutParams(dp(22), dp(22));
            cbLp.gravity = Gravity.TOP | Gravity.END;
            iconWrap.addView(checkBadge, cbLp);
            checkBadgeRef = checkBadge;
        } else {
            checkBadgeRef = null;
            registerBadgeSlot(desktopBadgeSlots, iconWrap, entry.packageName, null);
        }
        cell.addView(iconWrap);

        if (showLabel) {
            TextView label = makeDesktopLabel(formatAppLabel(entry));
            cell.addView(label);
        }

        cell.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchedSwipeUpAppKey = entry.key;
                touchedSwipeUpAnchor = icon;
            }
            return false;
        });
        cell.setOnClickListener(v -> {
            if (multiSelectMode) {
                if (prefs.isHapticFeedbackEnabled()) {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                boolean nowPicked;
                if (selectedAppKeys.contains(entry.key)) {
                    selectedAppKeys.remove(entry.key);
                    nowPicked = false;
                } else {
                    selectedAppKeys.add(entry.key);
                    nowPicked = true;
                }
                updateMultiSelectBar();
                if (checkBadgeRef != null) {
                    applyMultiSelectTileVisual(iconWrap, checkBadgeRef, nowPicked, true);
                } else {
                    rebuildDesktop();
                }
            } else {
                launchAndReturnHome(entry, icon);
            }
        });
        cell.setOnLongClickListener(v -> {
            if (multiSelectMode) {
                boolean nowPicked;
                if (selectedAppKeys.contains(entry.key)) {
                    selectedAppKeys.remove(entry.key);
                    nowPicked = false;
                } else {
                    selectedAppKeys.add(entry.key);
                    nowPicked = true;
                }
                updateMultiSelectBar();
                if (checkBadgeRef != null) {
                    applyMultiSelectTileVisual(iconWrap, checkBadgeRef, nowPicked, true);
                } else {
                    rebuildDesktop();
                }
                return true;
            }
            if (prefs.isDesktopLocked()) {
                showDesktopAppActionModal(pageIndex, itemIndex, entry);
            } else {
                beginPendingDrag(pageIndex, itemIndex, item, cell);
            }
            return true;
        });
        return cell;
    }

    private void applyMultiSelectTileVisual(FrameLayout iconWrap, FrameLayout checkBadge,
                                            boolean picked, boolean animate) {
        checkBadge.removeAllViews();
        checkBadge.setBackground(GlassTheme.makeGlassRect(
                this,
                picked ? GlassTheme.ACCENT_BLUE : 0xB8181A22,
                picked ? 0xFFFFFFFF : 0xAAFFFFFF,
                11f,
                1.2f
        ));
        if (picked) {
            ImageView ch = VectorGlyphs.makeIconView(this, "check", 13, Color.WHITE);
            FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(dp(13), dp(13));
            cLp.gravity = Gravity.CENTER;
            checkBadge.addView(ch, cLp);
        }
        float targetScale = picked ? 0.92f : 1f;
        if (animate && prefs.isSmoothAnimationsEnabled()) {
            iconWrap.animate().scaleX(targetScale).scaleY(targetScale).setDuration(95L).start();
        } else {
            iconWrap.setScaleX(targetScale);
            iconWrap.setScaleY(targetScale);
        }
    }

    private View createFolderTile(
            final int pageIndex,
            final int itemIndex,
            final DesktopItem folder,
            int iconDp,
            boolean showLabel
    ) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(2), dp(1), dp(2), dp(1));

        // AMOLED-стеклянная плитка папки: крупная 2x2 сетка оригинальных иконок (видно с главного экрана!)
        FrameLayout boxWrap = new FrameLayout(this);
        boxWrap.setLayoutParams(new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
        View box = buildFolderPreviewBox(folder, iconDp);
        boxWrap.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        if (!multiSelectMode) {
            registerBadgeSlot(desktopBadgeSlots, boxWrap, null, folder);
        }
        cell.addView(boxWrap);

        if (showLabel) {
            cell.addView(makeDesktopLabel(folder.folderName));
        }

        cell.setOnClickListener(v -> openFolderFromAnchor(pageIndex, folder, boxWrap));
        cell.setOnLongClickListener(v -> {
            if (prefs.isDesktopLocked()) {
                showFolderActionModal(pageIndex, itemIndex, folder);
            } else {
                beginPendingDrag(pageIndex, itemIndex, folder, cell);
            }
            return true;
        });
        return cell;
    }

    private boolean hasNotifDot(String packageName) {
        return prefs.getNotifBadgeMode() != Prefs.NOTIF_BADGE_NONE
                && DoxaizNotificationService.hasUnread(packageName);
    }

    private boolean hasFolderNotifDot(DesktopItem folder) {
        if (prefs.getNotifBadgeMode() == Prefs.NOTIF_BADGE_NONE || folder == null) return false;
        for (String key : folder.folderApps) {
            AppEntry e = appsByKey.get(key);
            if (e != null && DoxaizNotificationService.hasUnread(e.packageName)) return true;
        }
        return false;
    }

    private int getFolderUnreadCount(DesktopItem folder) {
        int sum = 0;
        if (folder == null) return 0;
        for (String key : folder.folderApps) {
            AppEntry e = appsByKey.get(key);
            if (e != null) sum += DoxaizNotificationService.getUnreadCount(e.packageName);
        }
        return sum;
    }

    private View addNotifBadge(FrameLayout iconWrap, int count) {
        int mode = prefs.getNotifBadgeMode();
        if (mode == Prefs.NOTIF_BADGE_NONE) return null;

        if (mode == Prefs.NOTIF_BADGE_NUMBERS) {
            TextView pill = new TextView(this);
            int safeCount = Math.max(1, count);
            pill.setText(safeCount > 99 ? "99+" : String.valueOf(safeCount));
            pill.setTextColor(Color.WHITE);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            pill.setTypeface(Typeface.DEFAULT_BOLD);
            pill.setGravity(Gravity.CENTER);
            pill.setMinWidth(dp(16));
            pill.setPadding(dp(4), 0, dp(4), 0);
            pill.setBackground(GlassTheme.makeRoundedRect(GlassTheme.ACCENT_RED, dp(8)));
            FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(16));
            pLp.gravity = Gravity.TOP | Gravity.END;
            iconWrap.addView(pill, pLp);
            return pill;
        }

        if (mode == Prefs.NOTIF_BADGE_APP_ICONS) {
            TextView bell = new TextView(this);
            bell.setText("•");
            bell.setTextColor(Color.WHITE);
            bell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            bell.setGravity(Gravity.CENTER);
            bell.setBackground(GlassTheme.makeCircle(GlassTheme.ACCENT_BLUE));
            FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(dp(12), dp(12));
            bLp.gravity = Gravity.TOP | Gravity.END;
            iconWrap.addView(bell, bLp);
            return bell;
        }

        // NOTIF_BADGE_DOT (по умолчанию — аккуратная точка уведомления)
        View dot = new View(this);
        dot.setBackground(GlassTheme.makeGlassRect(this, Color.WHITE, Color.argb(220, 0, 0, 0), 4f, 1.1f));
        FrameLayout.LayoutParams dLp = new FrameLayout.LayoutParams(dp(7), dp(7));
        dLp.gravity = Gravity.TOP | Gravity.END;
        dLp.topMargin = dp(1);
        dLp.rightMargin = dp(1);
        iconWrap.addView(dot, dLp);
        return dot;
    }

    /**
     * Doxaiz 4.6: Иконка папки 3×3 на главном экране (до 9 нативных мини-иконок)
     * в ровном 92%×92% тёмном полупрозрачном сквиркле — точное визуальное выравнивание со всеми приложениями.
     */
    private View buildFolderPreviewBox(DesktopItem folder, int iconDp) {
        int fullPx = dp(iconDp);
        int inset = Math.max(1, Math.round(fullPx * IconHelper.ICON_MARGIN_RATIO));
        float cornerDp = Math.max(12f, iconDp * 0.255f);

        FrameLayout outer = new FrameLayout(this);
        outer.setLayoutParams(new LinearLayout.LayoutParams(fullPx, fullPx));

        FrameLayout wrap = new FrameLayout(this);
        FrameLayout.LayoutParams wLp = new FrameLayout.LayoutParams(
                fullPx - inset * 2, fullPx - inset * 2, Gravity.CENTER
        );
        wrap.setBackground(GlassTheme.makeGlassRect(
                this, 0xCC232328, 0x24FFFFFF, cornerDp, 0.8f
        ));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER);
        int pad = dp(4.5f);
        int gap = dp(2f);
        grid.setPadding(pad, pad, pad, pad);

        List<AppEntry> resolved = resolveFolderApps(folder);
        for (int r = 0; r < 3; r++) {
            LinearLayout miniRow = new LinearLayout(this);
            miniRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            );
            if (r > 0) mrLp.topMargin = gap;
            miniRow.setLayoutParams(mrLp);

            for (int c = 0; c < 3; c++) {
                int idx = r * 3 + c;
                ImageView mini = new ImageView(this);
                mini.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                );
                if (c > 0) mLp.leftMargin = gap;
                mini.setLayoutParams(mLp);
                if (idx < resolved.size()) {
                    repository.bindIcon(resolved.get(idx), mini);
                }
                miniRow.addView(mini);
            }
            grid.addView(miniRow);
        }
        wrap.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        outer.addView(wrap, wLp);
        return outer;
    }

    private CharSequence formatAppLabel(AppEntry entry) {
        if (entry == null) return "";
        if (!Prefs.isNewUnopenedApp(entry.isSystemApp, entry.firstInstallTime,
                prefs.hasEverLaunched(entry.key), System.currentTimeMillis())) {
            return entry.displayLabel;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder("• ");
        sb.setSpan(new ForegroundColorSpan(GlassTheme.ACCENT_GREEN), 0, 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(entry.displayLabel);
        return sb;
    }

    private TextView makeDesktopLabel(CharSequence text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.getLabelTextSizeSp());
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(2f, 0f, 1f, Color.argb(150, 0, 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dp(5);
        label.setLayoutParams(lp);
        return label;
    }

    private int dotsBuiltCount = -1;
    private int dotsBuiltHomeIdx = -1;
    private int dotsBuiltActiveColor = 0;
    private int dotsBuiltInactiveColor = 0;
    private int dotsLastActiveIdx = -1;

    private void rebuildPageDots() {
        int count = desktop.pages.size();
        if (count <= 1 || !prefs.isShowPageDots()) {
            if (pageDotsBar.getChildCount() > 0) pageDotsBar.removeAllViews();
            dotsBuiltCount = 0;
            dotsLastActiveIdx = -1;
            pageDotsBar.setVisibility(View.INVISIBLE);
            return;
        }
        pageDotsBar.setVisibility(View.VISIBLE);
        int homeIdx = getEffectiveHomePageIndex();

        // Быстрый путь при листании страниц: обновляем только 2 изменившиеся точки без пересоздания View!
        if (dotsBuiltCount == count
                && dotsBuiltHomeIdx == homeIdx
                && dotsBuiltActiveColor == theme.dotActiveColor
                && dotsBuiltInactiveColor == theme.dotInactiveColor
                && pageDotsBar.getChildCount() == count) {
            if (dotsLastActiveIdx == currentPage) return;
            if (dotsLastActiveIdx >= 0 && dotsLastActiveIdx < count) {
                stylePageDotView(pageDotsBar.getChildAt(dotsLastActiveIdx), dotsLastActiveIdx == homeIdx, false);
            }
            if (currentPage >= 0 && currentPage < count) {
                stylePageDotView(pageDotsBar.getChildAt(currentPage), currentPage == homeIdx, true);
            }
            dotsLastActiveIdx = currentPage;
            return;
        }

        pageDotsBar.removeAllViews();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            boolean active = (i == currentPage);
            if (i == homeIdx) {
                ImageView homeDot = new ImageView(this);
                homeDot.setImageResource(R.drawable.ic_home);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(12), dp(12));
                lp.leftMargin = dp(4);
                lp.rightMargin = dp(4);
                homeDot.setLayoutParams(lp);
                stylePageDotView(homeDot, true, active);
                homeDot.setOnClickListener(v -> {
                    currentPage = homeIdx;
                    pagerView.goToPage(homeIdx);
                    rebuildPageDots();
                });
                pageDotsBar.addView(homeDot);
            } else {
                View dot = new View(this);
                int sz = active ? dp(7) : dp(6);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
                lp.leftMargin = dp(4);
                lp.rightMargin = dp(4);
                dot.setLayoutParams(lp);
                stylePageDotView(dot, false, active);
                dot.setOnClickListener(v -> {
                    currentPage = idx;
                    pagerView.goToPage(idx);
                    rebuildPageDots();
                });
                pageDotsBar.addView(dot);
            }
        }
        dotsBuiltCount = count;
        dotsBuiltHomeIdx = homeIdx;
        dotsBuiltActiveColor = theme.dotActiveColor;
        dotsBuiltInactiveColor = theme.dotInactiveColor;
        dotsLastActiveIdx = currentPage;
    }

    private void stylePageDotView(View dot, boolean isHome, boolean active) {
        if (dot == null) return;
        if (isHome && dot instanceof ImageView) {
            tintIcon((ImageView) dot, active ? theme.dotActiveColor : theme.dotInactiveColor);
        } else {
            dot.setBackground(GlassTheme.makeGlassRect(
                    this,
                    active ? Color.WHITE : 0x55FFFFFF,
                    Color.TRANSPARENT, 4f, 0f
            ));
            int sz = active ? dp(6) : dp(5);
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            if (lp != null && (lp.width != sz || lp.height != sz)) {
                lp.width = sz;
                lp.height = sz;
                dot.setLayoutParams(lp);
            }
        }
    }

    private void rebuildDock() {
        dockAppsRow.removeAllViews();
        dockBadgeSlots.clear();
        boolean showDockLbl = prefs.isShowDockLabels();
        int maxDockDp = showDockLbl ? 46 : 54;
        int iconDp = Math.min(maxDockDp, getIconDp());
        float dockLabelSp = Math.max(9.5f, prefs.getLabelTextSizeSp() - 1f);
        for (final String key : desktop.dock) {
            final AppEntry entry = appsByKey.get(key);
            if (entry == null || isHiddenKey(key)) continue;

            LinearLayout slot = new LinearLayout(this);
            slot.setOrientation(LinearLayout.VERTICAL);
            slot.setGravity(Gravity.CENTER);
            slot.setPadding(dp(2), dp(2), dp(2), dp(2));
            slot.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ));

            FrameLayout iconWrap = new FrameLayout(this);
            slot.addView(iconWrap, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));

            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconWrap.addView(icon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ));
            repository.bindIcon(entry, icon);
            registerBadgeSlot(dockBadgeSlots, iconWrap, entry.packageName, null);

            if (showDockLbl) {
                TextView dl = new TextView(this);
                dl.setText(entry.displayLabel);
                dl.setTextColor(Color.WHITE);
                dl.setTextSize(TypedValue.COMPLEX_UNIT_SP, dockLabelSp);
                dl.setGravity(Gravity.CENTER);
                dl.setSingleLine(true);
                dl.setEllipsize(TextUtils.TruncateAt.END);
                dl.setShadowLayer(2f, 0f, 1f, Color.argb(150, 0, 0, 0));
                LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlLp.topMargin = dp(3);
                slot.addView(dl, dlLp);
            }

            slot.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    touchedSwipeUpAppKey = entry.key;
                    touchedSwipeUpAnchor = icon;
                }
                return false;
            });
            slot.setOnClickListener(v -> launchAndReturnHome(entry, icon));
            slot.setOnLongClickListener(v -> {
                showDockAppActionModal(entry);
                return true;
            });
            dockAppsRow.addView(slot);
        }
        // Если в доке нет закреплённых приложений, добавим пустой распорный блок
        if (dockAppsRow.getChildCount() == 0) {
            TextView hint = new TextView(this);
            hint.setText("Doxaiz");
            hint.setTextColor(theme.textHint);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hint.setPadding(dp(10), 0, 0, 0);
            dockAppsRow.addView(hint);
        }
    }

    private List<AppEntry> resolveFolderApps(DesktopItem folder) {
        List<AppEntry> list = new ArrayList<>();
        for (String k : folder.folderApps) {
            AppEntry e = appsByKey.get(k);
            if (e != null && !isHiddenKey(k)) {
                list.add(e);
            }
        }
        return list;
    }

    private boolean isHiddenKey(String key) {
        return key != null && hiddenAppKeys.contains(key);
    }

    private void openFolderFromAnchor(int pageIndex, DesktopItem folder, View anchorView) {
        if (multiSelectMode) {
            Toast.makeText(this, "Завершите мультивыбор, чтобы открыть папку", Toast.LENGTH_SHORT).show();
            return;
        }
        if (anchorView != null) {
            int[] loc = new int[2];
            anchorView.getLocationInWindow(loc);
            folderZoomOriginX = loc[0] + anchorView.getWidth() * 0.5f;
            folderZoomOriginY = loc[1] + anchorView.getHeight() * 0.5f;
        } else {
            folderZoomOriginX = -1f;
            folderZoomOriginY = -1f;
        }
        showFolderModal(pageIndex, folder);
    }

    // =========================================================================
    // Полноэкранная ПАПКА v4.6 с верхней горизонтальной каруселью названий папок,
    // свайпом/тапом между папками, крупной сеткой 3×4 и плиткой «+ Добавить»
    // =========================================================================

    private static final class FolderRef {
        final int pageIndex;
        final DesktopItem folder;

        FolderRef(int pageIndex, DesktopItem folder) {
            this.pageIndex = pageIndex;
            this.folder = folder;
        }
    }

    private List<FolderRef> collectAllDesktopFolders() {
        List<FolderRef> out = new ArrayList<>();
        if (desktop == null || desktop.pages == null) return out;
        for (int p = 0; p < desktop.pages.size(); p++) {
            List<DesktopItem> items = desktop.pages.get(p);
            if (items == null) continue;
            for (int i = 0; i < items.size(); i++) {
                DesktopItem it = items.get(i);
                if (it != null && it.type == DesktopItem.TYPE_FOLDER) {
                    out.add(new FolderRef(p, it));
                }
            }
        }
        return out;
    }

    private final class FolderSwipeContainer extends FrameLayout {
        private final Runnable onSwipePrev;
        private final Runnable onSwipeNext;
        private final int slop;
        private float downX;
        private float downY;
        private boolean horizontalCaptured;

        FolderSwipeContainer(android.content.Context ctx, Runnable onSwipePrev, Runnable onSwipeNext) {
            super(ctx);
            this.onSwipePrev = onSwipePrev;
            this.onSwipeNext = onSwipeNext;
            this.slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (onSwipePrev == null && onSwipeNext == null) return false;
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                downY = ev.getY();
                horizontalCaptured = false;
                return false;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > slop * 1.4f && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                    horizontalCaptured = true;
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                downY = ev.getY();
                horizontalCaptured = false;
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                float thresh = dp(48);
                if (Math.abs(dx) >= thresh && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                    if (dx > 0f && onSwipePrev != null) {
                        onSwipePrev.run();
                        return true;
                    }
                    if (dx < 0f && onSwipeNext != null) {
                        onSwipeNext.run();
                        return true;
                    }
                } else if (!horizontalCaptured && Math.abs(dx) < slop && Math.abs(dy) < slop) {
                    dismissModal();
                    return true;
                }
            }
            return true;
        }
    }

    private void showFolderModal(final int pageIndex, final DesktopItem folder) {
        showFolderModalWithSlide(pageIndex, folder, 0);
    }

    private void showFolderModalWithSlide(final int pageIndex, final DesktopItem folder, final int slideDir) {
        if (folder == null) return;
        openFolderId = folder.folderId;
        openFolderPage = pageIndex;

        final List<FolderRef> allFolders = collectAllDesktopFolders();
        int curIdx = -1;
        for (int i = 0; i < allFolders.size(); i++) {
            if (allFolders.get(i).folder == folder
                    || (folder.folderId != null && folder.folderId.equals(allFolders.get(i).folder.folderId))) {
                curIdx = i;
                break;
            }
        }
        final FolderRef prevRef = (curIdx > 0) ? allFolders.get(curIdx - 1) : null;
        final FolderRef nextRef = (curIdx >= 0 && curIdx + 1 < allFolders.size())
                ? allFolders.get(curIdx + 1) : null;

        final Runnable goPrev = (prevRef != null) ? () -> {
            if (prefs.isHapticFeedbackEnabled() && modalOverlay != null) {
                modalOverlay.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            folderZoomOriginX = -1f;
            folderZoomOriginY = -1f;
            showFolderModalWithSlide(prevRef.pageIndex, prevRef.folder, -1);
        } : null;

        final Runnable goNext = (nextRef != null) ? () -> {
            if (prefs.isHapticFeedbackEnabled() && modalOverlay != null) {
                modalOverlay.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            folderZoomOriginX = -1f;
            folderZoomOriginY = -1f;
            showFolderModalWithSlide(nextRef.pageIndex, nextRef.folder, 1);
        } : null;

        final FolderSwipeContainer fullRoot = new FolderSwipeContainer(this, goPrev, goNext);
        fullRoot.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final boolean landscape = dm.widthPixels > dm.heightPixels;
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(4), dp(landscape ? 10 : 26), dp(4), dp(12));

        final boolean desktopLocked = prefs.isDesktopLocked();
        List<AppEntry> resolved = resolveFolderApps(folder);

        // Верхняя горизонтальная карусель названий папок (предыдущая dim ← ТЕКУЩАЯ яркая → следующая dim)
        LinearLayout carouselRow = new LinearLayout(this);
        carouselRow.setOrientation(LinearLayout.HORIZONTAL);
        carouselRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        crLp.bottomMargin = dp(24);
        carouselRow.setLayoutParams(crLp);

        TextView prevTv = new TextView(this);
        prevTv.setText(prevRef != null ? prevRef.folder.folderName : "");
        prevTv.setTextColor(0x66FFFFFF);
        prevTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        prevTv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        prevTv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        prevTv.setSingleLine(true);
        prevTv.setEllipsize(TextUtils.TruncateAt.END);
        prevTv.setPadding(0, dp(6), dp(8), dp(6));
        if (goPrev != null) {
            prevTv.setOnClickListener(v -> goPrev.run());
        }
        carouselRow.addView(prevTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f));

        TextView titleTv = new TextView(this);
        titleTv.setText(folder.folderName);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24.5f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setPadding(dp(6), dp(4), dp(6), dp(4));
        titleTv.setOnClickListener(v -> {
            if (prefs.isDesktopLocked()) {
                Toast.makeText(this, "Рабочий стол заблокирован", Toast.LENGTH_SHORT).show();
                return;
            }
            showRenameFolderModal(pageIndex, folder);
        });
        carouselRow.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f));

        TextView nextTv = new TextView(this);
        nextTv.setText(nextRef != null ? nextRef.folder.folderName : "");
        nextTv.setTextColor(0x66FFFFFF);
        nextTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        nextTv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        nextTv.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        nextTv.setSingleLine(true);
        nextTv.setEllipsize(TextUtils.TruncateAt.END);
        nextTv.setPadding(dp(8), dp(6), 0, dp(6));
        if (goNext != null) {
            nextTv.setOnClickListener(v -> goNext.run());
        }
        carouselRow.addView(nextTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f));
        content.addView(carouselRow);

        // Просторная полноэкранная сетка 3×4 без внешней коробки + плитка «+ Добавить» в конце
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout gridRows = new LinearLayout(this);
        gridRows.setOrientation(LinearLayout.VERTICAL);
        gridRows.setPadding(dp(4), dp(4), dp(4), dp(12));

        int cols = landscape ? 4 : 3;
        int iconDp = landscape
                ? Math.max(46, Math.min(54, getIconDp()))
                : Math.max(56, Math.min(62, getIconDp() + 4));
        int totalSlots = resolved.size() + (desktopLocked ? 0 : 1);
        int rows = Math.max(1, (totalSlots + cols - 1) / cols);

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (r > 0) rLp.topMargin = dp(20);
            row.setLayoutParams(rLp);

            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx < resolved.size()) {
                    final AppEntry app = resolved.get(idx);
                    LinearLayout cell = new LinearLayout(this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(dp(4), dp(2), dp(4), dp(2));

                    FrameLayout iconWrap = new FrameLayout(this);
                    iconWrap.setLayoutParams(new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));

                    ImageView iv = new ImageView(this);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iconWrap.addView(iv, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    ));
                    repository.bindIcon(app, iv);
                    if (hasNotifDot(app.packageName)) {
                        addNotifBadge(iconWrap, DoxaizNotificationService.getUnreadCount(app.packageName));
                    }
                    cell.addView(iconWrap);

                    TextView lbl = new TextView(this);
                    lbl.setText(formatAppLabel(app));
                    lbl.setTextColor(Color.WHITE);
                    lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                    lbl.setGravity(Gravity.CENTER);
                    lbl.setSingleLine(true);
                    lbl.setEllipsize(TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    lLp.topMargin = dp(6);
                    cell.addView(lbl, lLp);

                    cell.setOnClickListener(v -> {
                        dismissModal();
                        launchAndReturnHome(app, iv);
                    });
                    cell.setOnLongClickListener(v -> {
                        showFolderAppItemMenu(pageIndex, folder, app);
                        return true;
                    });
                    row.addView(cell, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ));
                } else if (!desktopLocked && idx == resolved.size()) {
                    // Полупрозрачная плитка «+ Добавить» 1-в-1 со скриншотами папок
                    LinearLayout addCell = new LinearLayout(this);
                    addCell.setOrientation(LinearLayout.VERTICAL);
                    addCell.setGravity(Gravity.CENTER_HORIZONTAL);
                    addCell.setPadding(dp(4), dp(2), dp(4), dp(2));

                    int fullPx = dp(iconDp);
                    int inset = Math.max(1, Math.round(fullPx * IconHelper.ICON_MARGIN_RATIO));
                    float cornerDp = Math.max(12f, iconDp * 0.255f);

                    FrameLayout plusOuter = new FrameLayout(this);
                    plusOuter.setLayoutParams(new LinearLayout.LayoutParams(fullPx, fullPx));

                    FrameLayout plusBox = new FrameLayout(this);
                    plusBox.setBackground(GlassTheme.makeGlassRect(
                            this, 0x4432343C, 0x26FFFFFF, cornerDp, 0.8f
                    ));
                    ImageView pIv = VectorGlyphs.makeIconView(this, "plus", 26, 0xE6FFFFFF);
                    FrameLayout.LayoutParams piLp = new FrameLayout.LayoutParams(dp(26), dp(26));
                    piLp.gravity = Gravity.CENTER;
                    plusBox.addView(pIv, piLp);
                    plusOuter.addView(plusBox, new FrameLayout.LayoutParams(
                            fullPx - inset * 2, fullPx - inset * 2, Gravity.CENTER
                    ));
                    addCell.addView(plusOuter);

                    TextView pLbl = new TextView(this);
                    pLbl.setText("Добавить");
                    pLbl.setTextColor(0xE6FFFFFF);
                    pLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                    pLbl.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams plLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    plLp.topMargin = dp(6);
                    addCell.addView(pLbl, plLp);

                    addCell.setOnClickListener(v -> showFolderAppPickerModal(pageIndex, folder));
                    row.addView(addCell, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ));
                } else {
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(1), 1f));
                }
            }
            gridRows.addView(row);
        }

        sv.addView(gridRows);
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.62f);
        content.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                rows > 4 ? maxH : ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Если папок несколько — тонкие точки-индикаторы карусели внизу
        if (allFolders.size() > 1) {
            LinearLayout fDots = new LinearLayout(this);
            fDots.setOrientation(LinearLayout.HORIZONTAL);
            fDots.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams fdLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            fdLp.topMargin = dp(20);
            fDots.setLayoutParams(fdLp);
            for (int i = 0; i < allFolders.size(); i++) {
                final FolderRef targetRef = allFolders.get(i);
                final int targetIdx = i;
                final int curSnapshot = curIdx;
                boolean act = (i == curIdx);
                View d = new View(this);
                int sz = act ? dp(6) : dp(5);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(sz, sz);
                dlp.leftMargin = dp(4);
                dlp.rightMargin = dp(4);
                d.setLayoutParams(dlp);
                d.setBackground(GlassTheme.makeCircle(act ? Color.WHITE : 0x55FFFFFF));
                if (!act) {
                    d.setOnClickListener(v -> {
                        folderZoomOriginX = -1f;
                        folderZoomOriginY = -1f;
                        showFolderModalWithSlide(targetRef.pageIndex, targetRef.folder,
                                targetIdx > curSnapshot ? 1 : -1);
                    });
                }
                fDots.addView(d);
            }
            content.addView(fDots);
        }

        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        fullRoot.addView(content, cLp);

        showFolderModalAnimated(fullRoot, content, slideDir);
    }

    private void showFolderModalAnimated(final View fullRoot, final View content, final int slideDir) {
        activeFolderCard = content;
        modalOverlay.setBackgroundColor(GlassTheme.FOLDER_SCRIM);
        showModalCardInternal(fullRoot, Gravity.FILL, true, false);
        if (slideDir != 0) {
            float offset = (slideDir > 0 ? 1f : -1f) * dp(48);
            content.setAlpha(0f);
            content.setTranslationX(offset);
            content.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(145L)
                    .setInterpolator(SMOOTH_INTERPOLATOR)
                    .withLayer()
                    .start();
            return;
        }
        if (folderZoomOriginX >= 0f && folderZoomOriginY >= 0f) {
            content.setAlpha(0.35f);
            content.setScaleX(0.34f);
            content.setScaleY(0.34f);
            content.post(() -> {
                if (activeFolderCard != content) return;
                int[] loc = new int[2];
                content.getLocationInWindow(loc);
                float cx = loc[0] + content.getWidth() * 0.5f;
                float cy = loc[1] + content.getHeight() * 0.5f;
                content.setTranslationX(folderZoomOriginX - cx);
                content.setTranslationY(folderZoomOriginY - cy);
                content.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(180L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(2.3f))
                        .withLayer()
                        .start();
            });
        } else {
            content.setAlpha(0f);
            content.setScaleX(0.90f);
            content.setScaleY(0.90f);
            content.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(165L)
                    .setInterpolator(SMOOTH_INTERPOLATOR)
                    .withLayer()
                    .start();
        }
    }

    private FrameLayout makeVectorCircleBtn(String glyph, String desc, final Runnable onClick) {
        FrameLayout btn = new FrameLayout(this);
        btn.setContentDescription(desc);
        btn.setBackground(GlassTheme.makeGlassRect(
                this, 0xFF282830, 0x33FFFFFF, 18f, 1f
        ));
        ImageView iv = VectorGlyphs.makeIconView(this, glyph, 16, Color.WHITE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(16), dp(16));
        lp.gravity = Gravity.CENTER;
        btn.addView(iv, lp);
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private static String formatRussianAppsCount(int n) {
        int m10 = n % 10;
        int m100 = n % 100;
        if (m10 == 1 && m100 != 11) return n + " приложение";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return n + " приложения";
        return n + " приложений";
    }

    private void showFolderAppItemMenu(final int pageIndex, final DesktopItem folder, final AppEntry entry) {
        LinearLayout box = createModalBox();
        addModalAppHeader(box, entry);

        int idx = folder.folderApps.indexOf(entry.key);
        if (idx > 0) {
            addModalAction(box, "Переместить левее", theme.textPrimary, () -> {
                desktop.moveAppInFolder(pageIndex, folder.folderId, entry.key, -1);
                rebuildDesktop();
                showFolderModal(pageIndex, folder);
            });
        }
        if (idx >= 0 && idx < folder.folderApps.size() - 1) {
            addModalAction(box, "Переместить правее", theme.textPrimary, () -> {
                desktop.moveAppInFolder(pageIndex, folder.folderId, entry.key, +1);
                rebuildDesktop();
                showFolderModal(pageIndex, folder);
            });
        }
        addModalAction(box, "Вынести на страницу", theme.textPrimary, () -> {
            desktop.removeAppFromFolder(pageIndex, folder.folderId, entry.key, true,
                    prefs.getColumns(), pageIndex == 0 && prefs.isHomeBottomAligned());
            rebuildDesktop();
            showFolderModal(pageIndex, folder);
        });
        addModalAction(box, "Убрать из папки", theme.textPrimary, () -> {
            desktop.removeAppFromFolder(pageIndex, folder.folderId, entry.key, false,
                    prefs.getColumns(), false);
            rebuildDesktop();
            showFolderModal(pageIndex, folder);
        });
        addModalAction(box, "О приложении", theme.textPrimary, () -> repository.openAppInfo(entry));
        if (!entry.isSystemApp && entry.isMainProfile) {
            addModalAction(box, "Удалить приложение", theme.dangerColor, () -> requestUninstall(entry));
        }
        showModalCard(box, Gravity.BOTTOM, false);
    }

    private void showFolderAppPickerModal(final int pageIndex, final DesktopItem folder) {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Приложения в «" + folder.folderName + "»");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        tLp.bottomMargin = prefs.isShowDrawerMemory() ? dp(3) : dp(10);
        box.addView(title, tLp);
        if (prefs.isShowDrawerMemory()) {
            addModalMemoryLine(box);
        }

        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        for (final AppEntry app : visibleApps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(32), dp(32));
            iLp.rightMargin = dp(12);
            row.addView(iv, iLp);
            repository.bindIcon(app, iv);

            TextView lbl = new TextView(this);
            lbl.setText(app.displayLabel);
            lbl.setTextColor(theme.textPrimary);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            lbl.setSingleLine(true);
            lbl.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final TextView mark = new TextView(this);
            mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            mark.setPadding(dp(10), dp(5), dp(10), dp(5));
            updateFolderPickChip(mark, folder.folderApps.contains(app.key));
            row.addView(mark);

            row.setOnClickListener(v -> {
                if (folder.folderApps.contains(app.key)) {
                    desktop.removeAppFromFolder(pageIndex, folder.folderId, app.key, false,
                            prefs.getColumns(), false);
                } else {
                    desktop.addAppToFolder(pageIndex, folder.folderId, app.key);
                }
                updateFolderPickChip(mark, folder.folderApps.contains(app.key));
                pendingDesktopRebuildOnModalDismiss = true;
            });
            list.addView(row);
        }

        sv.addView(list);
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.52f);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bLp.topMargin = dp(10);
        btns.setLayoutParams(bLp);
        addDialogButton(btns, "Готово", theme.accentColor, () -> {
            if (pendingDesktopRebuildOnModalDismiss) {
                pendingDesktopRebuildOnModalDismiss = false;
                rebuildDesktop();
            }
            showFolderModal(pageIndex, folder);
        });
        box.addView(btns);

        showModalCard(box, Gravity.CENTER, false);
    }

    private void updateFolderPickChip(TextView tv, boolean inFolder) {
        tv.setText(inFolder ? "В папке" : "Добавить");
        tv.setTextColor(inFolder ? theme.chipActiveText : theme.textSecondary);
        tv.setBackground(GlassTheme.makeGlassRect(
                this,
                inFolder ? theme.chipActiveBg : theme.chipInactiveBg,
                inFolder ? theme.chipActiveBorder : Color.TRANSPARENT,
                10f, inFolder ? 1f : 0f
        ));
    }

    // =========================================================================
    // Фильтрация, умный поиск и адаптер экрана всех приложений
    // =========================================================================

    private void rebuildDrawerCategories() {
        if (drawerCategoryScroll == null || drawerCategoryRow == null) return;
        boolean searching = drawerSearchInput != null && drawerSearchInput.getText().length() > 0;
        if (searching || visibleApps.isEmpty()) {
            drawerCategoryScroll.setVisibility(View.GONE);
            return;
        }

        drawerCategoryRow.removeAllViews();

        // 1. Векторный чип сортировки: А–Я / Частые / Новые
        final int sortMode = prefs.getDrawerSortMode();
        String sortTitle = (sortMode == Prefs.DRAWER_SORT_FREQ) ? "Частые"
                : (sortMode == Prefs.DRAWER_SORT_NEW ? "Новые" : "А–Я");
        boolean sortHighlighted = (sortMode != Prefs.DRAWER_SORT_AZ);

        LinearLayout sortChip = new LinearLayout(this);
        sortChip.setOrientation(LinearLayout.HORIZONTAL);
        sortChip.setGravity(Gravity.CENTER_VERTICAL);
        sortChip.setPadding(dp(10), dp(7), dp(11), dp(7));
        sortChip.setBackground(GlassTheme.makeGlassRect(
                this,
                sortHighlighted ? 0xFF1B2B45 : theme.cardBgColor,
                sortHighlighted ? 0xFF2B8BFF : theme.cardBorderColor,
                14f, 1f
        ));
        ImageView sortIco = VectorGlyphs.makeIconView(this, "sort", 14,
                sortHighlighted ? 0xFF60A5FA : theme.textSecondary);
        LinearLayout.LayoutParams siLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        siLp.rightMargin = dp(5);
        sortChip.addView(sortIco, siLp);
        TextView sortTv = new TextView(this);
        sortTv.setText(sortTitle);
        sortTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        sortTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sortTv.setTextColor(sortHighlighted ? Color.WHITE : theme.textPrimary);
        sortChip.addView(sortTv);
        sortChip.setOnClickListener(v -> {
            if (prefs.isHapticFeedbackEnabled()) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            int next = (prefs.getDrawerSortMode() + 1) % 3;
            prefs.setDrawerSortMode(next);
            applyFilter(drawerSearchInput.getText().toString(), false);
        });
        drawerCategoryRow.addView(sortChip);

        if (!prefs.isShowDrawerCategories()) {
            drawerCategoryScroll.setVisibility(View.VISIBLE);
            return;
        }

        int[] counts = new int[7];
        counts[AppCategoryHelper.CAT_ALL] = visibleApps.size();
        java.util.HashSet<String> installedPkgs = new java.util.HashSet<>();
        for (AppEntry e : visibleApps) {
            installedPkgs.add(e.packageName);
            if (e.category >= 1 && e.category < counts.length) {
                counts[e.category]++;
            }
        }

        List<CustomSection> customSections = prefs.getCustomSections();
        java.util.Set<Integer> hiddenStd = prefs.getHiddenStdCategories();

        if (selectedCustomSectionId != null
                && CustomSection.findById(customSections, selectedCustomSectionId) == null) {
            selectedCustomSectionId = null;
        }
        if (selectedCustomSectionId == null && selectedDrawerCategory != AppCategoryHelper.CAT_ALL
                && (selectedDrawerCategory < 0 || selectedDrawerCategory >= counts.length
                || counts[selectedDrawerCategory] == 0
                || hiddenStd.contains(selectedDrawerCategory))) {
            selectedDrawerCategory = AppCategoryHelper.CAT_ALL;
        }

        // 1) Чип «Все» всегда первый
        {
            boolean active = (selectedCustomSectionId == null && selectedDrawerCategory == AppCategoryHelper.CAT_ALL);
            TextView allChip = makeDrawerSectionChip("Все", active, false);
            allChip.setOnClickListener(v -> {
                if (prefs.isHapticFeedbackEnabled()) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                selectedCustomSectionId = null;
                selectedDrawerCategory = AppCategoryHelper.CAT_ALL;
                // applyFilter(..., false) сама вызывает rebuildDrawerCategories() 1 раз
                applyFilter(drawerSearchInput.getText().toString(), false);
            });
            allChip.setOnLongClickListener(v -> {
                showManageSectionsModal();
                return true;
            });
            drawerCategoryRow.addView(allChip);
        }

        // 2) Пользовательские разделы сразу после «Все» в выбранном порядке (Doxaiz 4.3)
        for (final CustomSection sec : customSections) {
            boolean active = sec.id.equals(selectedCustomSectionId);
            int cnt = CustomSection.countInstalledPackages(sec, installedPkgs);
            String label = cnt > 0 ? (sec.name + " " + cnt) : sec.name;
            TextView chip = makeDrawerSectionChip(label, active, true);
            chip.setOnClickListener(v -> {
                if (prefs.isHapticFeedbackEnabled()) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                selectedCustomSectionId = sec.id;
                selectedDrawerCategory = AppCategoryHelper.CAT_ALL;
                applyFilter(drawerSearchInput.getText().toString(), false);
            });
            chip.setOnLongClickListener(v -> {
                if (prefs.isHapticFeedbackEnabled()) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
                showCustomSectionActionModal(sec);
                return true;
            });
            drawerCategoryRow.addView(chip);
        }

        // 3) Стандартные категории (пропускаем скрытые пользователем)
        for (final int cat : AppCategoryHelper.ALL_CATEGORIES) {
            if (cat == AppCategoryHelper.CAT_ALL) continue;
            if (hiddenStd.contains(cat) || counts[cat] <= 0) continue;
            boolean active = (selectedCustomSectionId == null && cat == selectedDrawerCategory);

            TextView chip = makeDrawerSectionChip(AppCategoryHelper.getTitle(cat) + " " + counts[cat], active, false);
            chip.setOnClickListener(v -> {
                if (prefs.isHapticFeedbackEnabled()) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                selectedCustomSectionId = null;
                selectedDrawerCategory = cat;
                applyFilter(drawerSearchInput.getText().toString(), false);
            });
            chip.setOnLongClickListener(v -> {
                if (prefs.isHapticFeedbackEnabled()) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
                showStdCategoryActionModal(cat);
                return true;
            });
            drawerCategoryRow.addView(chip);
        }

        // 4) Кнопка «+ Раздел» — создать свой раздел в 1 тап
        LinearLayout addChip = new LinearLayout(this);
        addChip.setOrientation(LinearLayout.HORIZONTAL);
        addChip.setGravity(Gravity.CENTER_VERTICAL);
        addChip.setPadding(dp(10), dp(7), dp(11), dp(7));
        addChip.setBackground(GlassTheme.makeGlassRect(
                this, 0x262B8BFF, 0x662B8BFF, 14f, 1f
        ));
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        acLp.leftMargin = dp(6);
        addChip.setLayoutParams(acLp);
        ImageView pIco = VectorGlyphs.makeIconView(this, "plus", 13, 0xFF60A5FA);
        LinearLayout.LayoutParams piLp = new LinearLayout.LayoutParams(dp(13), dp(13));
        piLp.rightMargin = dp(4);
        addChip.addView(pIco, piLp);
        TextView pTv = new TextView(this);
        pTv.setText("Раздел");
        pTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        pTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pTv.setTextColor(theme.styleMode == Prefs.STYLE_CLASSIC_LIGHT ? 0xFF174E9A : 0xFF93C5FD);
        addChip.addView(pTv);
        addChip.setOnClickListener(v -> {
            if (prefs.isHapticFeedbackEnabled()) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            showCreateOrEditSectionModal(null, null);
        });
        drawerCategoryRow.addView(addChip);

        // 5) Кнопка настройки разделов (порядок ◀/▶ + скрытие/возврат стандартных)
        FrameLayout manageChip = new FrameLayout(this);
        manageChip.setContentDescription("Настроить разделы");
        manageChip.setBackground(GlassTheme.makeGlassRect(
                this, theme.cardBgColor, theme.cardBorderColor, 14f, 1f
        ));
        manageChip.setPadding(dp(9), dp(7), dp(9), dp(7));
        ImageView mIco = VectorGlyphs.makeIconView(this, "settings", 14, theme.textSecondary);
        manageChip.addView(mIco, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        mcLp.leftMargin = dp(6);
        manageChip.setLayoutParams(mcLp);
        manageChip.setOnClickListener(v -> {
            if (prefs.isHapticFeedbackEnabled()) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            showManageSectionsModal();
        });
        drawerCategoryRow.addView(manageChip);

        drawerCategoryScroll.setVisibility(View.VISIBLE);
    }

    private TextView makeDrawerSectionChip(String text, boolean active, boolean isCustom) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        chip.setTypeface(Typeface.create(active ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        chip.setTextColor(active ? theme.chipActiveText : (isCustom ? Color.WHITE : theme.textSecondary));
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setBackground(GlassTheme.makeGlassRect(
                this,
                active ? theme.chipActiveBg : (isCustom ? 0xFF1E2433 : theme.cardBgColor),
                active ? theme.chipActiveBorder : (isCustom ? 0x4460A5FA : theme.cardBorderColor),
                14f, 1f
        ));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cLp.leftMargin = dp(6);
        chip.setLayoutParams(cLp);
        return chip;
    }

    private void applyFilter(String rawQuery, boolean fromUserTyping) {
        filteredApps.clear();
        final String q = rawQuery != null ? rawQuery.trim() : "";
        String emptyMsg = "Ничего не найдено";
        if (q.isEmpty()) {
            if (prefs.isShowDrawerCategories() && selectedCustomSectionId != null) {
                CustomSection sec = CustomSection.findById(prefs.getCustomSections(), selectedCustomSectionId);
                if (sec != null) {
                    for (AppEntry e : visibleApps) {
                        if (sec.packages.contains(e.packageName)) filteredApps.add(e);
                    }
                    if (filteredApps.isEmpty()) {
                        emptyMsg = "Раздел «" + sec.name + "» пуст — удержите его чип, чтобы добавить приложения";
                    }
                } else {
                    selectedCustomSectionId = null;
                    filteredApps.addAll(visibleApps);
                }
            } else if (prefs.isShowDrawerCategories() && selectedDrawerCategory != AppCategoryHelper.CAT_ALL) {
                for (AppEntry e : visibleApps) {
                    if (e.category == selectedDrawerCategory) filteredApps.add(e);
                }
            } else {
                filteredApps.addAll(visibleApps);
            }
            int sortMode = prefs.getDrawerSortMode();
            if (sortMode == Prefs.DRAWER_SORT_FREQ) {
                final Map<String, Integer> counts = prefs.getLaunchCountsMap();
                Collections.sort(filteredApps, (a, b) -> {
                    int ca = counts.containsKey(a.key) ? counts.get(a.key) : 0;
                    int cb = counts.containsKey(b.key) ? counts.get(b.key) : 0;
                    if (ca != cb) return Integer.compare(cb, ca);
                    return a.displayLabel.compareToIgnoreCase(b.displayLabel);
                });
            } else if (sortMode == Prefs.DRAWER_SORT_NEW) {
                Collections.sort(filteredApps, (a, b) -> {
                    int cmp = Long.compare(b.firstInstallTime, a.firstInstallTime);
                    if (cmp != 0) return cmp;
                    return a.displayLabel.compareToIgnoreCase(b.displayLabel);
                });
            }
        } else {
            // Предвычисляем варианты запроса 1 раз вместо повторения на каждое приложение и каждую пару сортировки
            final SearchHelper.PreparedQuery pq = SearchHelper.prepare(q);
            for (AppEntry e : visibleApps) {
                if (SearchHelper.matches(e, pq)) filteredApps.add(e);
            }
            Collections.sort(filteredApps, (a, b) -> {
                int sa = SearchHelper.matchScore(a, pq);
                int sb = SearchHelper.matchScore(b, pq);
                if (sa != sb) return Integer.compare(sb, sa);
                return a.labelLower.compareTo(b.labelLower);
            });
        }
        boolean hasSmart = rebuildDrawerSmartBox(q);
        drawerEmptyText.setText(emptyMsg);
        drawerEmptyText.setVisibility((filteredApps.isEmpty() && !hasSmart) ? View.VISIBLE : View.GONE);
        drawerAdapter.notifyDataSetChanged();

        int prevLen = lastSearchQueryLen;
        lastSearchQueryLen = q.length();
        boolean continuingSearch = (fromUserTyping && prevLen > 0 && q.length() > 0);
        // Во время продолжения набора текста в поиске не пересоздаём скрытые чипы категорий и блок частых приложений
        if (!continuingSearch) {
            rebuildDrawerRecent();
            rebuildDrawerCategories();
            refreshDrawerBgPill();
            rebuildAlphabetBar();
        }

        if (fromUserTyping && currentScreen == SCREEN_DRAWER
                && prefs.isAutoLaunchSingleMatch()
                && !hasSmart
                && q.length() >= 2 && q.length() > prevLen
                && filteredApps.size() == 1) {
            launchAndReturnHome(filteredApps.get(0), drawerSearchInput);
        }
    }

    private boolean rebuildDrawerSmartBox(String q) {
        // В Doxaiz 4.0 в списке всех приложений работает только чистый поиск по приложениям (без калькулятора и конвертера)
        if (drawerSmartBox != null) {
            drawerSmartBox.removeAllViews();
            drawerSmartBox.setVisibility(View.GONE);
        }
        return false;
    }

    private void startSafeSystemIntent(String action) {
        try {
            Intent i = new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallback);
            } catch (Throwable ignored) {
            }
        }
    }

    private String lastAlphabetBarSig = "";

    private void rebuildAlphabetBar() {
        alphabetPositions.clear();

        boolean searching = drawerSearchInput != null && drawerSearchInput.getText().length() > 0;
        if (searching || filteredApps.size() < 12 || !prefs.isShowAlphabetBar()
                || prefs.getDrawerSortMode() != Prefs.DRAWER_SORT_AZ) {
            alphabetBar.setVisibility(View.GONE);
            return;
        }
        StringBuilder sig = new StringBuilder(64);
        sig.append(theme.textHint).append(':');
        for (int i = 0; i < filteredApps.size(); i++) {
            String letter = filteredApps.get(i).getIndexChar();
            if (!alphabetPositions.containsKey(letter)) {
                alphabetPositions.put(letter, i);
                sig.append(letter).append(i).append(',');
            }
        }
        String nextSig = sig.toString();
        if (nextSig.equals(lastAlphabetBarSig) && alphabetBar.getChildCount() == alphabetPositions.size() + 1) {
            alphabetBar.setVisibility(View.VISIBLE);
            return;
        }
        lastAlphabetBarSig = nextSig;

        alphabetBar.removeAllViews();
        alphabetBar.setVisibility(View.VISIBLE);
        int textSizeSp = alphabetPositions.size() > 26 ? 9 : 10;

        // Doxaiz 4.6: Символ «♡» в самом верху алфавитной полосы (быстрый переход к началу списка)
        TextView favTv = new TextView(this);
        favTv.setText("♡");
        favTv.setTag(0);
        favTv.setGravity(Gravity.CENTER);
        favTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        favTv.setTextColor(theme.textHint);
        favTv.setIncludeFontPadding(false);
        favTv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));
        alphabetBar.addView(favTv);

        for (Map.Entry<String, Integer> e : alphabetPositions.entrySet()) {
            TextView tv = new TextView(this);
            tv.setText(e.getKey());
            tv.setTag(e.getValue());
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tv.setTextColor(theme.textHint);
            tv.setIncludeFontPadding(false);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ));
            alphabetBar.addView(tv);
        }
    }

    private void launchAndReturnHome(AppEntry entry, View source) {
        boolean wasNew = Prefs.isNewUnopenedApp(entry.isSystemApp, entry.firstInstallTime,
                prefs.hasEverLaunched(entry.key), System.currentTimeMillis());
        boolean ok = repository.launchApp(entry, source);
        if (!ok) {
            Toast.makeText(this, "Не удалось открыть приложение", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.recordAppLaunch(entry.key);
        if (wasNew) {
            lastDrawerRecentSig = "";
            rebuildDesktop();
            if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
        }
        if (currentScreen != SCREEN_HOME) showScreen(SCREEN_HOME);
    }

    private final class DrawerAdapter extends BaseAdapter {
        @Override public int getCount() { return filteredApps.size(); }
        @Override public AppEntry getItem(int position) { return filteredApps.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getItemViewType(int position) { return prefs.getDrawerMode(); }
        @Override public int getViewTypeCount() { return 2; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppEntry entry = filteredApps.get(position);
            boolean isList = prefs.getDrawerMode() == Prefs.DRAWER_LIST;
            int iconDp = isList ? Math.max(36, getIconDp() - 8) : getIconDp();

            ViewHolder vh;
            if (convertView == null) {
                vh = new ViewHolder();
                if (isList) {
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(12), dp(9), dp(12), dp(9));

                    vh.icon = new ImageView(MainActivity.this);
                    vh.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp));
                    iLp.rightMargin = dp(14);
                    row.addView(vh.icon, iLp);

                    vh.label = new TextView(MainActivity.this);
                    vh.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
                    vh.label.setSingleLine(true);
                    vh.label.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(vh.label, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ));

                    vh.checkBadge = new TextView(MainActivity.this);
                    vh.checkBadge.setGravity(Gravity.CENTER);
                    vh.checkBadge.setTextColor(Color.WHITE);
                    vh.checkBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                    vh.checkBadge.setTypeface(Typeface.DEFAULT_BOLD);
                    vh.checkBadge.setVisibility(View.GONE);
                    row.addView(vh.checkBadge, new LinearLayout.LayoutParams(dp(22), dp(22)));
                    convertView = row;
                } else {
                    LinearLayout tile = new LinearLayout(MainActivity.this);
                    tile.setOrientation(LinearLayout.VERTICAL);
                    tile.setGravity(Gravity.CENTER_HORIZONTAL);
                    tile.setPadding(dp(4), dp(6), dp(4), dp(6));

                    FrameLayout iconBox = new FrameLayout(MainActivity.this);
                    vh.icon = new ImageView(MainActivity.this);
                    vh.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iconBox.addView(vh.icon, new FrameLayout.LayoutParams(
                            dp(iconDp), dp(iconDp), Gravity.CENTER
                    ));

                    vh.checkBadge = new TextView(MainActivity.this);
                    vh.checkBadge.setGravity(Gravity.CENTER);
                    vh.checkBadge.setTextColor(Color.WHITE);
                    vh.checkBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                    vh.checkBadge.setTypeface(Typeface.DEFAULT_BOLD);
                    vh.checkBadge.setVisibility(View.GONE);
                    FrameLayout.LayoutParams cbLp = new FrameLayout.LayoutParams(dp(20), dp(20));
                    cbLp.gravity = Gravity.TOP | Gravity.END;
                    iconBox.addView(vh.checkBadge, cbLp);

                    tile.addView(iconBox, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ));

                    vh.label = new TextView(MainActivity.this);
                    vh.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                    vh.label.setGravity(Gravity.CENTER);
                    vh.label.setSingleLine(true);
                    vh.label.setEllipsize(TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    lLp.topMargin = dp(5);
                    tile.addView(vh.label, lLp);
                    convertView = tile;
                }
                convertView.setTag(vh);
            } else {
                vh = (ViewHolder) convertView.getTag();
            }

            int px = dp(iconDp);
            ViewGroup.LayoutParams iLp = vh.icon.getLayoutParams();
            if (iLp.width != px || iLp.height != px) {
                iLp.width = px;
                iLp.height = px;
                vh.icon.setLayoutParams(iLp);
            }
            if (isList) {
                int listKey = (prefs.getGlassOpacity() << 3) | (prefs.getDrawerStyle() << 2) | (prefs.getVisualStyle() == Prefs.STYLE_CLASSIC_LIGHT ? 2 : 0) | 1;
                if (vh.appliedBgKey != listKey) {
                    convertView.setBackground(GlassTheme.makeGlassRect(
                            MainActivity.this, theme.cardBgColor, theme.cardBorderColor, 16f, 1f
                    ));
                    vh.appliedBgKey = listKey;
                }
            } else if (vh.appliedBgKey != 0) {
                convertView.setBackground(null);
                vh.appliedBgKey = 0;
            }

            vh.label.setText(formatAppLabel(entry));
            vh.label.setTextColor(theme.textPrimary);
            repository.bindIcon(entry, vh.icon);

            if (drawerBatchMode) {
                boolean removable = !entry.isSystemApp && entry.isMainProfile;
                convertView.setAlpha(removable ? 1f : 0.36f);
                if (removable) {
                    boolean sel = drawerBatchSelectedPkgs.contains(entry.packageName);
                    vh.checkBadge.setVisibility(View.VISIBLE);
                    vh.checkBadge.setText(sel ? "✓" : "");
                    int desiredState = sel ? 2 : 1;
                    if (vh.batchBgState != desiredState) {
                        vh.batchBgState = desiredState;
                        vh.checkBadge.setBackground(sel
                                ? GlassTheme.makeCircle(0xFFEF4444)
                                : GlassTheme.makeGlassRect(
                                        MainActivity.this, 0x991C1D22, 0xCCFFFFFF, 11f, 1.2f));
                    }
                } else {
                    vh.checkBadge.setVisibility(View.GONE);
                }
            } else {
                if (convertView.getAlpha() != 1f) convertView.setAlpha(1f);
                if (vh.checkBadge.getVisibility() != View.GONE) vh.checkBadge.setVisibility(View.GONE);
            }
            return convertView;
        }
    }

    private static final class ViewHolder {
        ImageView icon;
        TextView label;
        TextView checkBadge;
        int appliedBgKey = -1;
        int batchBgState = 0;
    }

    // =========================================================================
    // Drag & Drop на свободной сетке + настраиваемые жесты рабочего стола
    // =========================================================================

    private FreeGridPageView getFreeGridForPage(int pageIndex) {
        View pageView = pagerView.getPageView(pageIndex);
        if (pageView != null && pageView.getTag() instanceof FreeGridPageView) {
            return (FreeGridPageView) pageView.getTag();
        }
        return null;
    }

    private void beginPendingDrag(int pageIndex, int itemIndex, DesktopItem item, View sourceTile) {
        dragPending = true;
        dragging = false;
        dragFromDrawerEntry = null;
        dragFromPage = pageIndex;
        dragItemIndex = itemIndex;
        dragItem = item;
        dragSourceView = sourceTile;
        dragStartX = lastTouchRawX;
        dragStartY = lastTouchRawY;
        dragHoverCol = -1;
        dragHoverRow = -1;
        dragOverRemoveBar = false;
        dragEdgeDirection = 0;
        dragAutoCreatedPage = false;

        pagerView.setDragLocked(true);
        if (prefs.isHapticFeedbackEnabled()) {
            sourceTile.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        sourceTile.setBackground(GlassTheme.makeGlassRect(
                this, theme.chipInactiveBg, theme.dialogBorderColor, 14f, 1f
        ));
    }

    /**
     * Doxaiz 4.1: Долгое нажатие в «Все приложения»:
     * - если палец отпущен на месте -> открывается меню приложения
     * - если потянули палец -> меню плавно скрывается и иконка перетаскивается прямо на рабочий стол
     */
    private void beginPendingDrawerDrag(AppEntry entry, View sourceTile) {
        if (entry == null) return;
        dragPending = true;
        dragging = false;
        dragFromDrawerEntry = entry;
        dragFromPage = -1;
        dragItemIndex = -1;
        dragItem = DesktopItem.appAt(entry.key, 0, 0);
        dragSourceView = sourceTile;
        dragStartX = lastTouchRawX;
        dragStartY = lastTouchRawY;
        dragHoverCol = -1;
        dragHoverRow = -1;
        dragOverRemoveBar = false;
        dragEdgeDirection = 0;
        dragAutoCreatedPage = false;

        if (sourceTile != null) {
            if (prefs.isHapticFeedbackEnabled()) {
                sourceTile.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            sourceTile.setBackground(GlassTheme.makeGlassRect(
                    this, theme.chipInactiveBg, theme.dialogBorderColor, 14f, 1f
            ));
        }
    }

    private void promoteToActiveDrag(MotionEvent ev) {
        if (prefs.isDesktopLocked()) {
            cancelDragCleanup();
            Toast.makeText(this, "Рабочий стол заблокирован — снимите блокировку в настройках", Toast.LENGTH_SHORT).show();
            return;
        }
        dragPending = false;
        dragging = true;

        // Сбрасываем состояние нажатия у исходной плитки
        MotionEvent cancel = MotionEvent.obtain(ev);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();

        if (dragSourceView != null) {
            dragSourceView.setBackground(null);
            if (dragFromDrawerEntry == null) {
                dragSourceView.setAlpha(0.22f);
            }
        }

        // Если перетаскивание начато из «Все приложения» — мгновенно переходим на рабочий стол
        if (dragFromDrawerEntry != null && currentScreen == SCREEN_DRAWER) {
            hideKeyboard();
            drawerDragDownActive = false;
            drawerClosingAnim = false;
            drawerContainer.animate().cancel();
            drawerContainer.setVisibility(View.GONE);
            drawerContainer.setTranslationY(0f);
            drawerContainer.setAlpha(1f);
            homeContainer.animate().cancel();
            homeContainer.setVisibility(View.VISIBLE);
            homeContainer.setScaleX(1f);
            homeContainer.setScaleY(1f);
            homeContainer.setAlpha(1f);
            currentScreen = SCREEN_HOME;
            if (pagerView != null) pagerView.setDragLocked(true);
        }

        // Строим чистый фантом перетаскиваемой иконки/папки (без blur и теней)
        dragGhost.removeAllViews();
        int iconDp = getIconDp();
        if (dragItem != null && dragItem.type == DesktopItem.TYPE_FOLDER) {
            dragGhost.addView(buildFolderPreviewBox(dragItem, iconDp));
        } else if (dragItem != null && appsByKey.containsKey(dragItem.appKey)) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            dragGhost.addView(iv, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp)));
            repository.bindIcon(appsByKey.get(dragItem.appKey), iv);
        }
        dragGhost.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        dragGhostW = dragGhost.getMeasuredWidth();
        dragGhostH = dragGhost.getMeasuredHeight();
        if (dragGhostW <= 0) dragGhostW = dp(iconDp);
        if (dragGhostH <= 0) dragGhostH = dp(iconDp);
        dragGhost.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        dragRemoveBar.setText(dragFromDrawerEntry != null
                ? "Отменить добавление" : "Убрать с рабочего стола");
        updateDragRemoveBarStyle(false);
        dragOverlay.setVisibility(View.VISIBLE);
        updateActiveDragPosition(ev.getRawX(), ev.getRawY());
    }

    private final int[] dragTmpRootLoc = new int[2];
    private final int[] dragTmpGridLoc = new int[2];
    private final int[] dragTmpCell = new int[2];
    private int dragGhostW = 0;
    private int dragGhostH = 0;
    private boolean pendingDesktopRebuildOnModalDismiss = false;

    private void updateDragRemoveBarStyle(boolean active) {
        int bg = active ? Color.argb(230, 185, 28, 28) : theme.dialogBgColor;
        int border = active ? theme.dangerColor : theme.dialogBorderColor;
        dragRemoveBar.setBackground(GlassTheme.makeGlassRect(this, bg, border, 20f, 1.2f));
        dragRemoveBar.setTextColor(active ? Color.WHITE : theme.textPrimary);
    }

    private void updateActiveDragPosition(float rawX, float rawY) {
        rootContainer.getLocationOnScreen(dragTmpRootLoc);
        float localX = rawX - dragTmpRootLoc[0];
        float localY = rawY - dragTmpRootLoc[1];

        dragGhost.setX(localX - dragGhostW * 0.5f);
        dragGhost.setY(localY - dragGhostH * 0.5f - dp(24));

        // Проверка верхней зоны «Убрать со страницы»
        int removeZoneBottom = dragTmpRootLoc[1] + insetTop + dp(66);
        boolean overRemove = (rawY < removeZoneBottom);
        if (overRemove != dragOverRemoveBar) {
            dragOverRemoveBar = overRemove;
            updateDragRemoveBarStyle(overRemove);
        }

        FreeGridPageView grid = getFreeGridForPage(currentPage);
        if (overRemove) {
            if (grid != null) grid.clearHighlight();
            dragHoverCol = -1;
            dragHoverRow = -1;
            cancelEdgeSwitch();
            return;
        }

        // Перелистывание страниц у краёв экрана
        int screenW = rootContainer.getWidth();
        int edgePx = dp(28);
        int desiredEdge = 0;
        if (localX < edgePx && currentPage > 0) {
            desiredEdge = -1;
        } else if (localX > screenW - edgePx
                && (currentPage < desktop.pages.size() - 1 || desktop.pages.size() < DesktopModel.MAX_PAGES)) {
            desiredEdge = +1;
        }
        if (desiredEdge != dragEdgeDirection) {
            cancelEdgeSwitch();
            dragEdgeDirection = desiredEdge;
            if (desiredEdge != 0) {
                uiHandler.postDelayed(edgePageSwitchRunnable, 550L);
            }
        }

        // Определяем ячейку под пальцем на текущей странице (0 аллокаций на кадр 120 Гц)
        if (grid != null) {
            grid.getLocationOnScreen(dragTmpGridLoc);
            if (grid.pointToCell(rawX - dragTmpGridLoc[0], rawY - dragTmpGridLoc[1], dragTmpCell)) {
                dragHoverCol = dragTmpCell[0];
                dragHoverRow = dragTmpCell[1];
                int targetIdx = desktop.findItemIndexAt(currentPage, dragHoverCol, dragHoverRow);
                boolean willFolder = false;
                if (targetIdx >= 0 && dragItem != null && dragItem.type == DesktopItem.TYPE_APP) {
                    DesktopItem target = desktop.pages.get(currentPage).get(targetIdx);
                    if (target != dragItem) {
                        if (target.type == DesktopItem.TYPE_APP) {
                            willFolder = (dragItem.appKey == null || !dragItem.appKey.equals(target.appKey));
                        } else if (target.type == DesktopItem.TYPE_FOLDER) {
                            willFolder = (dragItem.appKey == null || !target.folderApps.contains(dragItem.appKey));
                        }
                    }
                }
                int mode = willFolder ? FreeGridPageView.HIGHLIGHT_FOLDER : FreeGridPageView.HIGHLIGHT_MOVE;
                int fill = willFolder ? Color.argb(65, 255, 255, 255) : Color.argb(28, 255, 255, 255);
                int stroke = willFolder ? theme.accentColor : theme.dialogBorderColor;
                grid.setHighlight(dragHoverCol, dragHoverRow, mode, fill, stroke);
            }
        }
    }

    private void performDragEdgePageSwitch() {
        if (!dragging || dragEdgeDirection == 0) return;
        int dir = dragEdgeDirection;
        dragEdgeDirection = 0;

        FreeGridPageView oldGrid = getFreeGridForPage(currentPage);
        if (oldGrid != null) oldGrid.clearHighlight();

        int next = currentPage + dir;
        if (next >= desktop.pages.size() && desktop.pages.size() < DesktopModel.MAX_PAGES) {
            desktop.addPage();
            dragAutoCreatedPage = true;
            dragSourceView = null;
            List<View> pages = new ArrayList<>();
            for (int p = 0; p < desktop.pages.size(); p++) {
                pages.add(buildDesktopPageView(p, desktop.pages.get(p)));
            }
            currentPage = next;
            pagerView.setPages(pages, currentPage);
            rebuildPageDots();
            Toast.makeText(this, "Страница " + (currentPage + 1), Toast.LENGTH_SHORT).show();
            pagerView.post(() -> {
                if (dragging) updateActiveDragPosition(lastTouchRawX, lastTouchRawY);
            });
            return;
        } else if (next >= 0 && next < desktop.pages.size()) {
            currentPage = next;
            pagerView.goToPage(currentPage);
            rebuildPageDots();
        }
        updateActiveDragPosition(lastTouchRawX, lastTouchRawY);
    }

    private void cancelEdgeSwitch() {
        dragEdgeDirection = 0;
        uiHandler.removeCallbacks(edgePageSwitchRunnable);
    }

    private void finishActiveDrag() {
        AppEntry drawerEntry = dragFromDrawerEntry;
        int fromPage = dragFromPage;
        int fromIdx = dragItemIndex;
        int toPage = currentPage;
        int toCol = dragHoverCol;
        int toRow = dragHoverRow;
        boolean remove = dragOverRemoveBar;
        boolean hadAutoPage = dragAutoCreatedPage;
        dragAutoCreatedPage = false;

        cancelDragCleanup();

        if (drawerEntry != null) {
            if (remove || toCol < 0 || toRow < 0) {
                if (hadAutoPage && desktop.pruneTrailingEmptyPage()) {
                    currentPage = Math.max(0, Math.min(currentPage, desktop.pages.size() - 1));
                    rebuildDesktop();
                }
                return;
            }
            int res = desktop.dropExternalAppOnCell(
                    drawerEntry.key, toPage, toCol, toRow, prefs.getColumns()
            );
            if (hadAutoPage && desktop.pruneTrailingEmptyPage()) {
                currentPage = Math.max(0, Math.min(currentPage, desktop.pages.size() - 1));
            }
            rebuildDesktop();
            if (res == DesktopModel.DROP_FOLDER_CREATED) {
                Toast.makeText(this, "Создана папка", Toast.LENGTH_SHORT).show();
            } else if (res == DesktopModel.DROP_ADDED_TO_FOLDER) {
                Toast.makeText(this, "Добавлено в папку", Toast.LENGTH_SHORT).show();
            } else if (res == DesktopModel.DROP_MOVED) {
                Toast.makeText(this, "Добавлено на страницу " + (toPage + 1), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (remove) {
            desktop.removeItem(fromPage, fromIdx);
            if (hadAutoPage && desktop.pruneTrailingEmptyPage()) {
                currentPage = Math.max(0, Math.min(currentPage, desktop.pages.size() - 1));
            }
            rebuildDesktop();
            Toast.makeText(this, "Убрано с рабочего стола", Toast.LENGTH_SHORT).show();
            return;
        }
        if (toCol >= 0 && toRow >= 0) {
            int res = desktop.dropItemOnCell(fromPage, fromIdx, toPage, toCol, toRow, prefs.getColumns());
            if (hadAutoPage && desktop.pruneTrailingEmptyPage()) {
                currentPage = Math.max(0, Math.min(currentPage, desktop.pages.size() - 1));
            }
            rebuildDesktop();
            if (res == DesktopModel.DROP_FOLDER_CREATED) {
                Toast.makeText(this, "Создана папка", Toast.LENGTH_SHORT).show();
            } else if (res == DesktopModel.DROP_ADDED_TO_FOLDER) {
                Toast.makeText(this, "Добавлено в папку", Toast.LENGTH_SHORT).show();
            }
        } else {
            if (hadAutoPage && desktop.pruneTrailingEmptyPage()) {
                currentPage = Math.max(0, Math.min(currentPage, desktop.pages.size() - 1));
            }
            rebuildDesktop();
        }
    }

    private void finishPendingDragAsMenu() {
        AppEntry drawerEntry = dragFromDrawerEntry;
        int pageIdx = dragFromPage;
        int itemIdx = dragItemIndex;
        DesktopItem item = dragItem;
        cancelDragCleanup();
        if (drawerEntry != null) {
            showDrawerAppActionModal(drawerEntry);
            return;
        }
        if (item == null) return;
        if (item.type == DesktopItem.TYPE_FOLDER) {
            showFolderActionModal(pageIdx, itemIdx, item);
        } else {
            AppEntry entry = appsByKey.get(item.appKey);
            if (entry != null) showDesktopAppActionModal(pageIdx, itemIdx, entry);
        }
    }

    private boolean cancelDragCleanup() {
        cancelEdgeSwitch();
        if (dragSourceView != null) {
            dragSourceView.setAlpha(1f);
            dragSourceView.setBackground(null);
            dragSourceView = null;
        }
        FreeGridPageView grid = getFreeGridForPage(currentPage);
        if (grid != null) grid.clearHighlight();
        if (dragOverlay != null) {
            dragOverlay.setVisibility(View.GONE);
            dragGhost.setLayerType(View.LAYER_TYPE_NONE, null);
            dragGhost.removeAllViews();
        }
        if (pagerView != null) pagerView.setDragLocked(false);
        dragPending = false;
        dragging = false;
        dragFromDrawerEntry = null;
        dragFromPage = -1;
        dragItemIndex = -1;
        dragItem = null;
        dragHoverCol = -1;
        dragHoverRow = -1;
        dragOverRemoveBar = false;
        boolean prunedAutoPage = false;
        if (dragAutoCreatedPage) {
            dragAutoCreatedPage = false;
            if (desktop != null && desktop.pruneTrailingEmptyPage()) {
                currentPage = Math.max(0, Math.min(currentPage, desktop.pages.size() - 1));
                prunedAutoPage = true;
            }
        }
        return prunedAutoPage;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            lastTouchRawX = ev.getRawX();
            lastTouchRawY = ev.getRawY();
        }

        if (modalOverlay.getVisibility() != View.VISIBLE
                && (currentScreen == SCREEN_HOME || currentScreen == SCREEN_DRAWER)) {
            // 1. Ожидание начала перетаскивания после долгого нажатия на иконку/папку (рабочий стол или «Все приложения»)
            if (dragPending) {
                if (action == MotionEvent.ACTION_MOVE) {
                    float dx = ev.getRawX() - dragStartX;
                    float dy = ev.getRawY() - dragStartY;
                    if (Math.hypot(dx, dy) > touchSlop) {
                        promoteToActiveDrag(ev);
                    }
                    return true;
                } else if (action == MotionEvent.ACTION_UP) {
                    finishPendingDragAsMenu();
                    return true;
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    cancelDragCleanup();
                    return true;
                }
            }

            // 2. Активное перетаскивание пальцем
            if (dragging) {
                if (action == MotionEvent.ACTION_MOVE) {
                    updateActiveDragPosition(ev.getRawX(), ev.getRawY());
                    return true;
                } else if (action == MotionEvent.ACTION_UP) {
                    finishActiveDrag();
                    return true;
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    if (cancelDragCleanup()) rebuildDesktop();
                    return true;
                }
            }
        }

        if (currentScreen == SCREEN_HOME && modalOverlay.getVisibility() != View.VISIBLE) {

            // 2.5 Щипок двумя пальцами на рабочем столе -> Менеджер страниц
            if (pinchDetector != null && ev.getPointerCount() >= 2) {
                pinchDetector.onTouchEvent(ev);
                if (pinchTriggered) {
                    return true;
                }
            }

            // 3. Вертикальные свайпы (вверх — 2-е приложение по иконке или меню; вниз — жест)
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = ev.getRawX();
                    touchDownY = ev.getRawY();
                    verticalSwipeTriggered = false;
                    touchedSwipeUpAppKey = null;
                    touchedSwipeUpAnchor = null;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!verticalSwipeTriggered && ev.getPointerCount() == 1) {
                        float dx = ev.getRawX() - touchDownX;
                        float dy = ev.getRawY() - touchDownY;
                        if (Math.abs(dy) > Math.max(touchSlop * 1.6f, dp(36))
                                && Math.abs(dy) > Math.abs(dx) * 1.35f) {
                            verticalSwipeTriggered = true;
                            lastEmptyTapTime = 0L;
                            MotionEvent cancel = MotionEvent.obtain(ev);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            super.dispatchTouchEvent(cancel);
                            cancel.recycle();

                            if (dy < 0) {
                                String targetKey = (touchedSwipeUpAppKey != null)
                                        ? prefs.getIconSwipeUpTarget(touchedSwipeUpAppKey)
                                        : null;
                                AppEntry target = (targetKey != null) ? appsByKey.get(targetKey) : null;
                                if (target != null && !isHiddenKey(target.key)) {
                                    if (prefs.isHapticFeedbackEnabled()) {
                                        rootContainer.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                                    }
                                    launchAndReturnHome(target, touchedSwipeUpAnchor);
                                } else {
                                    executeSwipeUpAction(prefs.getSwipeUpAction());
                                }
                            } else {
                                if (prefs.getSwipeDownAction() == Prefs.GESTURE_NOTIFICATIONS
                                        && prefs.getShadeStyle() == Prefs.SHADE_STYLE_INFINIX
                                        && prefs.isShadeSplitSwipe()) {
                                    int screenW = getResources().getDisplayMetrics().widthPixels;
                                    pendingShadeTab = (touchDownX < (screenW * 0.5f))
                                            ? QuickShadeHelper.TAB_NOTIFICATIONS
                                            : QuickShadeHelper.TAB_CONTROL;
                                } else {
                                    pendingShadeTab = QuickShadeHelper.TAB_CONTROL;
                                }
                                executeGesture(prefs.getSwipeDownAction());
                            }
                            touchedSwipeUpAppKey = null;
                            touchedSwipeUpAnchor = null;
                            return true;
                        }
                    } else {
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touchedSwipeUpAppKey = null;
                    touchedSwipeUpAnchor = null;
                    if (verticalSwipeTriggered) {
                        verticalSwipeTriggered = false;
                        return true;
                    }
                    break;
            }
        } else if (currentScreen == SCREEN_DRAWER && modalOverlay.getVisibility() != View.VISIBLE && !drawerClosingAnim) {
            // Плавное интерактивное закрытие списка всех приложений свайпом вниз (Doxaiz 4.0)
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    drawerDragDownStartX = ev.getRawX();
                    drawerDragDownStartY = ev.getRawY();
                    drawerDragDownActive = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (ev.getPointerCount() == 1) {
                        float dx = ev.getRawX() - drawerDragDownStartX;
                        float dy = ev.getRawY() - drawerDragDownStartY;
                        boolean inTopHeader = drawerDragDownStartY < (insetTop + dp(128));
                        if (!drawerDragDownActive && dy > Math.max(touchSlop * 1.4f, dp(22))
                                && dy > Math.abs(dx) * 1.4f
                                && (inTopHeader || isDrawerScrolledToTop())) {
                            drawerDragDownActive = true;
                            hideKeyboard();
                            homeContainer.setVisibility(View.VISIBLE);
                            MotionEvent cancel = MotionEvent.obtain(ev);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            super.dispatchTouchEvent(cancel);
                            cancel.recycle();
                        }
                        if (drawerDragDownActive) {
                            float ty = Math.max(0f, dy);
                            drawerContainer.setTranslationY(ty);
                            float progress = Math.min(1f, ty / dp(260));
                            drawerContainer.setAlpha(Math.max(0.2f, 1f - progress * 0.72f));
                            float homeSc = 0.96f + 0.04f * progress;
                            homeContainer.setScaleX(homeSc);
                            homeContainer.setScaleY(homeSc);
                            homeContainer.setAlpha(0.75f + 0.25f * progress);
                            return true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (drawerDragDownActive) {
                        float ty = drawerContainer.getTranslationY();
                        drawerDragDownActive = false;
                        if (action == MotionEvent.ACTION_UP && ty > dp(82)) {
                            closeDrawerAnimated();
                        } else {
                            drawerContainer.animate().translationY(0f).alpha(1f)
                                    .setDuration(150L).setInterpolator(SMOOTH_INTERPOLATOR)
                                    .withLayer()
                                    .withEndAction(() -> {
                                        if (currentScreen == SCREEN_DRAWER) {
                                            homeContainer.setVisibility(View.GONE);
                                            homeContainer.setScaleX(1f);
                                            homeContainer.setScaleY(1f);
                                            homeContainer.setAlpha(1f);
                                        }
                                    }).start();
                        }
                        return true;
                    }
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void executeSwipeUpAction(int action) {
        switch (action) {
            case Prefs.SWIPE_UP_DRAWER:
                openDrawer(false);
                break;
            case Prefs.SWIPE_UP_RECENTS:
                showBackgroundAppsModal();
                break;
            case Prefs.SWIPE_UP_SHADE:
                executeGesture(Prefs.GESTURE_NOTIFICATIONS);
                break;
            case Prefs.SWIPE_UP_NONE:
            default:
                break;
        }
    }

    private void executeGesture(int action) {
        switch (action) {
            case Prefs.GESTURE_NOTIFICATIONS:
                if (prefs.getShadeStyle() == Prefs.SHADE_STYLE_SYSTEM) {
                    expandNotificationShade();
                } else {
                    showDoxaizShade();
                }
                break;
            case Prefs.GESTURE_SEARCH:
                openDrawer(true);
                break;
            case Prefs.GESTURE_SETTINGS:
                showScreen(SCREEN_SETTINGS);
                break;
            case Prefs.GESTURE_LOCK_SCREEN:
                triggerScreenLock();
                break;
            case Prefs.GESTURE_NETWORK_PANEL:
                runQuickNetworkImprove();
                break;
            case Prefs.GESTURE_HIDDEN_SPACE:
                openPrivateSpaceGuarded();
                break;
            case Prefs.GESTURE_ACTIVE_WINDOWS:
                showBackgroundAppsModal();
                break;
            case Prefs.GESTURE_NONE:
            default:
                break;
        }
    }

    private void showDoxaizShade() {
        showDoxaizShade(true);
    }

    private void showDoxaizShade(boolean animateEntry) {
        if (quickShadeHelper == null) return;
        dismissModal();
        if (headsUpBannerHelper != null) headsUpBannerHelper.hideImmediate();
        quickShadeHelper.show(pendingShadeTab);
    }

    private void onAutoHideVolume() {
        if (volumeCompactOpen && modalOverlay != null && modalOverlay.getVisibility() == View.VISIBLE) {
            dismissModal();
        }
    }

    private void showCompactVolumePanel() {
        volumeCompactOpen = true;
        volumeExpandedOpen = false;
        uiHandler.removeCallbacks(autoHideVolumeRunnable);
        View pill = SoundPanelHelper.buildCompactPopup(this, theme, prefs.isHapticFeedbackEnabled(),
                new SoundPanelHelper.Callbacks() {
                    @Override public void onDismiss() { dismissModal(); }
                    @Override public void onOpenExpanded() { showExpandedSoundPanel(); }
                    @Override public void onRefreshExpanded() { showExpandedSoundPanel(); }
                    @Override public void onRefreshCompact() { showCompactVolumePanel(); }
                    @Override public void onUserInteraction() {
                        uiHandler.removeCallbacks(autoHideVolumeRunnable);
                        uiHandler.postDelayed(autoHideVolumeRunnable, 3600L);
                    }
                });
        openFolderId = null;
        openFolderPage = -1;
        modalOverlay.removeAllViews();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        pill.setClickable(true);
        modalOverlay.addView(pill, lp);
        modalOverlay.setVisibility(View.VISIBLE);
        if (prefs.isSmoothAnimationsEnabled()) {
            pill.setAlpha(0f);
            pill.setTranslationX(dp(18));
            pill.animate().alpha(1f).translationX(0f).setDuration(160L).setInterpolator(SMOOTH_INTERPOLATOR).start();
        }
        uiHandler.postDelayed(autoHideVolumeRunnable, 3600L);
    }

    private void showExpandedSoundPanel() {
        volumeCompactOpen = false;
        uiHandler.removeCallbacks(autoHideVolumeRunnable);
        View panel = SoundPanelHelper.buildExpandedPanel(this, theme, prefs.isHapticFeedbackEnabled(),
                new SoundPanelHelper.Callbacks() {
                    @Override public void onDismiss() { dismissModal(); }
                    @Override public void onOpenExpanded() { showExpandedSoundPanel(); }
                    @Override public void onRefreshExpanded() { showExpandedSoundPanel(); }
                    @Override public void onRefreshCompact() { showCompactVolumePanel(); }
                });
        showModalCard(panel, Gravity.BOTTOM, false);
        volumeExpandedOpen = true;
    }

    private void showShadeTileEditorModal(final boolean returnToShade) {
        if (quickShadeHelper != null) {
            quickShadeHelper.showTileEditorDialog();
        }
    }

    private void showAutoNetworkInfoModal() {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Авто-улучшение сети: как работает");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        TextView msg = new TextView(this);
        msg.setText(
                "1. Без расхода заряда и без доступа в интернет\n"
                        + "У Doxaiz нет разрешения INTERNET — оболочка ничего не скачивает и не отправляет. Она читает только системное состояние сети Android (ACCESS_NETWORK_STATE, LinkProperties).\n\n"
                        + "2. Три режима и тихая работа без всплывашек\n"
                        + "• Мягкий (30 с) — перепроверяет канал только при полном залипании (NET_CAPABILITY_VALIDATED снят).\n"
                        + "• Сбалансированный (15 с) — залипание + контроль слабого Wi-Fi (<= -82 дБм).\n"
                        + "• Активный (10 с) — быстрая реакция на залипание и просадку Wi-Fi (<= -76 дБм).\n"
                        + "Все срабатывания происходят тихо в фоне и фиксируются в локальной статистике настроек.\n\n"
                        + "3. Быстрый Частный DNS, панель Wi-Fi/4G и плитка «Сеть+»\n"
                        + "Показывает реальный статус Частного DNS, лимитность сети и уровень сигнала. В редакторе шторки можно добавить плитку «Сеть+» для перепроверки канала в 1 касание."
        );
        msg.setTextColor(theme.textSecondary);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        msg.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        mLp.topMargin = dp(10);
        mLp.bottomMargin = dp(12);
        box.addView(msg, mLp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        addDialogButton(btns, "Понятно", theme.accentColor, this::dismissModal);
        box.addView(btns);
        showModalCard(box, Gravity.CENTER, false);
    }

    private void showElementsVisibilityModal() {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Показ элементов экрана");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Включайте или убирайте любые элементы интерфейса, как в официальных оболочках.");
        sub.setTextColor(theme.textSecondary);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sLp.topMargin = dp(4);
        sLp.bottomMargin = dp(10);
        box.addView(sub, sLp);

        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        addToggleRow(list, "Кнопка ⚙ вверху рабочего стола", "Уберите для полностью чистого верха",
                !prefs.isHideTopSettingsBtn(), v -> {
                    prefs.setHideTopSettingsBtn(!v);
                    applyTheme();
                    showElementsVisibilityModal();
                });
        addDivider(list);
        addToggleRow(list, "Подписи под иконками", "На главном и доп. экранах",
                prefs.isShowHomeLabels(), v -> {
                    prefs.setShowHomeLabels(v);
                    applyTheme();
                    showElementsVisibilityModal();
                });
        addDivider(list);
        addToggleRow(list, "Индикатор страниц внизу", "Точки главного и доп. экранов",
                prefs.isShowPageDots(), v -> {
                    prefs.setShowPageDots(v);
                    rebuildPageDots();
                    showElementsVisibilityModal();
                });
        addDivider(list);
        addToggleRow(list, "Белые точки уведомлений", "На иконках и папках",
                prefs.isShowNotificationDots(), v -> {
                    prefs.setShowNotificationDots(v);
                    rebuildDesktop();
                    showElementsVisibilityModal();
                });

        sv.addView(list);
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.52f);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        addDialogButton(btns, "Готово", theme.accentColor, () -> {
            dismissModal();
            if (currentScreen == SCREEN_SETTINGS) rebuildSettingsContent();
        });
        box.addView(btns);
        showModalCard(box, Gravity.CENTER, false);
    }

    private void runQuickNetworkImprove() {
        boolean reprobed = (networkHelper != null) && networkHelper.triggerSystemReprobe();
        Toast.makeText(
                this,
                reprobed ? "Перепроверка канала запущена" : "Открытие панели подключения",
                Toast.LENGTH_SHORT
        ).show();
        NetworkQualityHelper.openInternetQuickPanel(this);
    }

    private void triggerScreenLock() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, "Блокировка экрана поддерживается с Android 9", Toast.LENGTH_SHORT).show();
            return;
        }
        if (DoxaizLockService.lockScreenNow()) {
            return;
        }
        showConfirmModal(
                "Включить блокировку экрана?",
                "Для выключения экрана двойным касанием (с сохранением отпечатка пальца) включите «Doxaiz — Панель ОЗУ и блокировка» в Специальных возможностях Android.",
                this::openAccessibilitySettings
        );
    }

    private void expandNotificationShade() {
        try {
            Object service = getSystemService("statusbar");
            if (service != null) {
                Class<?> cls = Class.forName("android.app.StatusBarManager");
                Method expand = cls.getMethod("expandNotificationsPanel");
                expand.invoke(service);
            }
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // Контекстные меню (Страница, Приложение, Папка, Док, Меню приложений)
    // =========================================================================

    private void showModalCard(View cardContent, int gravity, boolean keepFolderOpen) {
        showModalCardInternal(cardContent, gravity, keepFolderOpen, true);
    }

    private void showModalCardInternal(View cardContent, int gravity, boolean keepFolderOpen, boolean animate) {
        volumeCompactOpen = false;
        volumeExpandedOpen = false;
        shadeOpen = false;
        uiHandler.removeCallbacks(autoHideVolumeRunnable);
        if (!keepFolderOpen) {
            modalOverlay.setBackgroundColor(Color.argb(165, 0, 0, 0));
            if (pendingDesktopRebuildOnModalDismiss) {
                pendingDesktopRebuildOnModalDismiss = false;
                rebuildDesktop();
            }
            activeFolderCard = null;
            openFolderId = null;
            openFolderPage = -1;
            folderZoomOriginX = -1f;
            folderZoomOriginY = -1f;
        }
        modalOverlay.removeAllViews();
        int heightMode = (gravity == Gravity.FILL)
                ? ViewGroup.LayoutParams.MATCH_PARENT
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightMode
        );
        lp.gravity = gravity;
        cardContent.setClickable(true);
        modalOverlay.addView(cardContent, lp);
        modalOverlay.setVisibility(View.VISIBLE);
        syncDynamicBarOverlaySuppression();
        if (animate && prefs.isSmoothAnimationsEnabled()) {
            cardContent.setAlpha(0f);
            float startY = (gravity == Gravity.TOP) ? -dp(18) : (gravity == Gravity.BOTTOM ? dp(22) : dp(10));
            cardContent.setTranslationY(startY);
            cardContent.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(155L)
                    .setInterpolator(SMOOTH_INTERPOLATOR)
                    .withLayer()
                    .start();
        }
    }

    private void dismissModal() {
        volumeCompactOpen = false;
        volumeExpandedOpen = false;
        shadeOpen = false;
        uiHandler.removeCallbacks(autoHideVolumeRunnable);
        final View closingFolder = activeFolderCard;
        final float origX = folderZoomOriginX;
        final float origY = folderZoomOriginY;
        activeFolderCard = null;
        openFolderId = null;
        openFolderPage = -1;
        folderZoomOriginX = -1f;
        folderZoomOriginY = -1f;
        hideKeyboard();

        if (closingFolder != null && modalOverlay.getVisibility() == View.VISIBLE && origX >= 0f && origY >= 0f) {
            int[] loc = new int[2];
            closingFolder.getLocationInWindow(loc);
            float cx = loc[0] + closingFolder.getWidth() * 0.5f;
            float cy = loc[1] + closingFolder.getHeight() * 0.5f;
            closingFolder.animate().cancel();
            closingFolder.animate()
                    .translationX(origX - cx)
                    .translationY(origY - cy)
                    .scaleX(0.28f)
                    .scaleY(0.28f)
                    .alpha(0f)
                    .setDuration(145L)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f))
                    .withLayer()
                    .withEndAction(() -> {
                        if (activeFolderCard == null) {
                            modalOverlay.setBackgroundColor(Color.argb(165, 0, 0, 0));
                            modalOverlay.removeAllViews();
                            modalOverlay.setVisibility(View.GONE);
                            syncDynamicBarOverlaySuppression();
                            if (pendingDesktopRebuildOnModalDismiss) {
                                pendingDesktopRebuildOnModalDismiss = false;
                                rebuildDesktop();
                            }
                        }
                    })
                    .start();
            return;
        }
        modalOverlay.setBackgroundColor(Color.argb(165, 0, 0, 0));
        modalOverlay.removeAllViews();
        modalOverlay.setVisibility(View.GONE);
        syncDynamicBarOverlaySuppression();
        if (pendingDesktopRebuildOnModalDismiss) {
            pendingDesktopRebuildOnModalDismiss = false;
            rebuildDesktop();
        }
    }

    private void syncAmbientParticles() { /* Decorative particle effects removed. */ }

    private void syncDynamicBarOverlaySuppression() {
        syncAmbientParticles();
        if (dynamicBarHelper == null) return;
        boolean shadeShowing = quickShadeHelper != null && quickShadeHelper.isShowing();
        boolean modalShowing = modalOverlay != null && modalOverlay.getVisibility() == View.VISIBLE;
        dynamicBarHelper.setSuppressedByOverlay(shadeShowing || modalShowing);
    }

    private LinearLayout createModalBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(14));
        box.setBackground(GlassTheme.makeStyledCard(
                this, theme.dialogBgColor, theme.dialogBorderColor, 22f, 1f, true
        ));
        return box;
    }

    private void addModalAppHeader(LinearLayout box, AppEntry entry) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hLp.bottomMargin = dp(10);
        head.setLayoutParams(hLp);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        iLp.rightMargin = dp(12);
        head.addView(icon, iLp);
        repository.bindIcon(entry, icon);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameTv = new TextView(this);
        nameTv.setText(entry.displayLabel);
        nameTv.setTextColor(theme.textPrimary);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);

        TextView pkgTv = new TextView(this);
        pkgTv.setText(entry.packageName);
        pkgTv.setTextColor(theme.textHint);
        pkgTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        pkgTv.setSingleLine(true);
        pkgTv.setEllipsize(TextUtils.TruncateAt.END);

        titles.addView(nameTv);
        titles.addView(pkgTv);
        head.addView(titles);
        box.addView(head);

        View div = new View(this);
        div.setBackgroundColor(theme.dividerColor);
        box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private void addModalAction(LinearLayout box, String label, int color, final Runnable action) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tv.setPadding(dp(6), dp(12), dp(6), dp(12));
        tv.setOnClickListener(v -> {
            dismissModal();
            action.run();
        });
        box.addView(tv);
    }

    private void addModalMemoryLine(LinearLayout box) {
        TextView memSub = new TextView(this);
        memSub.setText(MemoryHelper.read(this).oneLineSummary);
        memSub.setTextColor(theme.textHint);
        memSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        mLp.bottomMargin = dp(10);
        box.addView(memSub, mLp);
    }

    private int getMaxDockApps() {
        return prefs.isShowDockDrawerBtn()
                ? DesktopModel.MAX_DOCK_APPS
                : DesktopModel.MAX_DOCK_APPS_EXTENDED;
    }

    private void addAppShortcutsSection(LinearLayout box, AppEntry entry) {
        List<ShortcutInfo> shortcuts = repository.getAppShortcuts(entry, 4);
        if (shortcuts.isEmpty()) return;
        for (final ShortcutInfo si : shortcuts) {
            CharSequence labelCs = si.getShortLabel();
            if (labelCs == null || labelCs.length() == 0) labelCs = si.getLongLabel();
            if (labelCs == null) continue;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Drawable d = repository.getShortcutIcon(si);
            if (d != null) {
                iv.setImageDrawable(d);
            } else {
                iv.setImageResource(R.drawable.ic_chevron_right);
                tintIcon(iv, theme.textSecondary);
            }
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(22), dp(22));
            iLp.rightMargin = dp(10);
            row.addView(iv, iLp);

            TextView tv = new TextView(this);
            tv.setText(labelCs);
            tv.setTextColor(theme.textPrimary);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(v -> {
                dismissModal();
                if (!repository.launchShortcut(si)) {
                    Toast.makeText(this, "Не удалось открыть действие", Toast.LENGTH_SHORT).show();
                } else {
                    prefs.recordAppLaunch(entry.key);
                    if (currentScreen != SCREEN_HOME) showScreen(SCREEN_HOME);
                }
            });
            box.addView(row);
        }
        View div = new View(this);
        div.setBackgroundColor(theme.dividerColor);
        box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private void showDesktopAppActionModal(final int pageIndex, final int itemIndex, final AppEntry entry) {
        LinearLayout box = createModalBox();
        addModalAppHeader(box, entry);
        addAppShortcutsSection(box, entry);

        if (prefs.isDesktopLocked()) {
            addModalAction(box, "Разблокировать раскладку для изменений", theme.accentColor, () -> {
                prefs.setDesktopLocked(false);
                Toast.makeText(this, "Раскладка разблокирована", Toast.LENGTH_SHORT).show();
            });
        } else {
            addModalAction(box, "Выбрать несколько иконок", theme.accentColor,
                    () -> enterMultiSelectMode(entry.key));

            addModalAction(box, "Поместить в папку...", theme.textPrimary,
                    () -> showPickFolderForAppModal(pageIndex, itemIndex, entry));

            addModalAction(box, "Перенести на другую страницу...", theme.textPrimary,
                    () -> showPickPageForItemModal(pageIndex, itemIndex));

            if (!desktop.dock.contains(entry.key) && desktop.dock.size() < getMaxDockApps()) {
                addModalAction(box, "Закрепить в нижнем доке", theme.textPrimary, () -> {
                    desktop.addToDock(entry.key, getMaxDockApps());
                    rebuildDesktop();
                });
            }

            addModalAction(box, "Убрать с рабочего стола", theme.textPrimary, () -> {
                desktop.removeItem(pageIndex, itemIndex);
                rebuildDesktop();
                Toast.makeText(this, "Убрано с экрана (осталось в «Все приложения»)", Toast.LENGTH_SHORT).show();
            });
        }

        addModalAction(box, "Переименовать", theme.textPrimary, () -> showRenameAppModal(entry));
        addModalAction(box, formatSwipeUpMenuTitle(entry.key), theme.textPrimary,
                () -> showPickSwipeUpAppModal(entry));

        if (!prefs.isDesktopLocked()) {
            addModalAction(box, "Скрыть приложение", theme.textPrimary, () -> {
                prefs.hideApp(entry.key);
                desktop.purgeAppKey(entry.key);
                repository.reload();
                Toast.makeText(this, "Скрыто. Вернуть можно в настройках Doxaiz", Toast.LENGTH_SHORT).show();
            });
        }

        addModalAction(box, "О приложении", theme.textPrimary, () -> repository.openAppInfo(entry));

        if (!entry.isSystemApp && entry.isMainProfile) {
            addModalAction(box, "Удалить приложение", theme.dangerColor, () -> requestUninstall(entry));
        }

        showModalCard(box, Gravity.BOTTOM, false);
    }

    private void showFolderActionModal(final int pageIndex, final int itemIndex, final DesktopItem folder) {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Папка «" + folder.folderName + "»");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, dp(6), dp(10));
        box.addView(title);

        View div = new View(this);
        div.setBackgroundColor(theme.dividerColor);
        box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        addModalAction(box, "Открыть папку", theme.textPrimary, () -> showFolderModal(pageIndex, folder));
        addModalAction(box, "Добавить / убрать приложения", theme.textPrimary,
                () -> showFolderAppPickerModal(pageIndex, folder));
        addModalAction(box, "Переименовать папку", theme.textPrimary,
                () -> showRenameFolderModal(pageIndex, folder));

        if (prefs.isDesktopLocked()) {
            addModalAction(box, "Разблокировать раскладку для перемещения", theme.accentColor, () -> {
                prefs.setDesktopLocked(false);
                Toast.makeText(this, "Раскладка разблокирована", Toast.LENGTH_SHORT).show();
            });
        } else {
            addModalAction(box, "Перенести на другую страницу...", theme.textPrimary,
                    () -> showPickPageForItemModal(pageIndex, itemIndex));

            addModalAction(box, "Распаковать приложения на страницу", theme.textPrimary, () -> {
                desktop.dissolveFolder(pageIndex, folder.folderId, true,
                        prefs.getColumns(), pageIndex == 0 && prefs.isHomeBottomAligned());
                rebuildDesktop();
            });

            addModalAction(box, "Удалить папку со страницы", theme.dangerColor, () -> {
                desktop.dissolveFolder(pageIndex, folder.folderId, false, prefs.getColumns(), false);
                rebuildDesktop();
            });
        }

        showModalCard(box, Gravity.BOTTOM, false);
    }

    private void showDockAppActionModal(final AppEntry entry) {
        LinearLayout box = createModalBox();
        addModalAppHeader(box, entry);
        addAppShortcutsSection(box, entry);

        if (prefs.isDesktopLocked()) {
            addModalAction(box, "Разблокировать раскладку для изменений", theme.accentColor, () -> {
                prefs.setDesktopLocked(false);
                Toast.makeText(this, "Раскладка разблокирована", Toast.LENGTH_SHORT).show();
            });
        } else {
            int pos = desktop.dock.indexOf(entry.key);
            if (pos > 0) {
                addModalAction(box, "Переместить левее", theme.textPrimary, () -> {
                    desktop.moveInDock(entry.key, -1);
                    rebuildDock();
                });
            }
            if (pos >= 0 && pos < desktop.dock.size() - 1) {
                addModalAction(box, "Переместить правее", theme.textPrimary, () -> {
                    desktop.moveInDock(entry.key, +1);
                    rebuildDock();
                });
            }
            addModalAction(box, "Убрать из нижнего дока", theme.textPrimary, () -> {
                desktop.removeFromDock(entry.key);
                rebuildDock();
            });
        }
        addModalAction(box, "Переименовать", theme.textPrimary, () -> showRenameAppModal(entry));
        addModalAction(box, formatSwipeUpMenuTitle(entry.key), theme.textPrimary,
                () -> showPickSwipeUpAppModal(entry));
        addModalAction(box, "О приложении", theme.textPrimary, () -> repository.openAppInfo(entry));
        if (!entry.isSystemApp && entry.isMainProfile) {
            addModalAction(box, "Удалить приложение", theme.dangerColor, () -> requestUninstall(entry));
        }

        showModalCard(box, Gravity.BOTTOM, false);
    }

    private void showDrawerAppActionModal(final AppEntry entry) {
        LinearLayout box = createModalBox();
        addModalAppHeader(box, entry);
        addAppShortcutsSection(box, entry);

        if (!prefs.isDesktopLocked()) {
            addModalAction(box, "Добавить на страницу " + (currentPage + 1), theme.textPrimary, () -> {
                desktop.addAppToPage(currentPage, entry.key,
                        prefs.getColumns(), currentPage == 0 && prefs.isHomeBottomAligned());
                rebuildDesktop();
                Toast.makeText(this, "Добавлено на страницу " + (currentPage + 1), Toast.LENGTH_SHORT).show();
            });

            addModalAction(box, "Добавить в папку...", theme.textPrimary,
                    () -> showPickFolderForAppModal(currentPage, -1, entry));

            boolean inDock = desktop.dock.contains(entry.key);
            if (inDock) {
                addModalAction(box, "Убрать из нижнего дока", theme.textPrimary, () -> {
                    desktop.removeFromDock(entry.key);
                    rebuildDock();
                });
            } else if (desktop.dock.size() < getMaxDockApps()) {
                addModalAction(box, "Закрепить в нижнем доке", theme.textPrimary, () -> {
                    desktop.addToDock(entry.key, getMaxDockApps());
                    rebuildDock();
                    Toast.makeText(this, "Закреплено в доке", Toast.LENGTH_SHORT).show();
                });
            }
        }

        List<String> appSecNames = CustomSection.getSectionNamesForPackage(
                prefs.getCustomSections(), entry.packageName
        );
        String mySecLabel = appSecNames.isEmpty()
                ? "Добавить в свой раздел ›"
                : ("Мои разделы: " + TextUtils.join(", ", appSecNames) + " ›");
        addModalAction(box, mySecLabel, theme.accentColor,
                () -> showPickCustomSectionsForAppModal(entry));

        addModalAction(box,
                "Категория: " + AppCategoryHelper.getTitle(entry.category) + " ›",
                theme.textPrimary,
                () -> showPickCategoryModal(entry));
        addModalAction(box, "Переименовать", theme.textPrimary, () -> showRenameAppModal(entry));
        addModalAction(box, "Скрыть приложение", theme.textPrimary, () -> {
            prefs.hideApp(entry.key);
            desktop.purgeAppKey(entry.key);
            repository.reload();
            Toast.makeText(this, "Скрыто. Вернуть можно в настройках Doxaiz", Toast.LENGTH_SHORT).show();
        });
        addModalAction(box, "О приложении", theme.textPrimary, () -> repository.openAppInfo(entry));
        if (!entry.isSystemApp && entry.isMainProfile) {
            addModalAction(box, "Выбрать несколько для удаления", theme.textPrimary,
                    () -> enterDrawerBatchMode(entry.packageName));
            addModalAction(box, "Удалить приложение", theme.dangerColor, () -> requestUninstall(entry));
        }

        showModalCard(box, Gravity.BOTTOM, false);
    }

    /**
     * Doxaiz 4.3: Создать новый или отредактировать существующий пользовательский раздел
     * (название + быстрый поиск + галочки приложений).
     */
    private void showCreateOrEditSectionModal(final CustomSection existing, final String preselectPkg) {
        final boolean isEdit = (existing != null);
        final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
        if (isEdit) {
            picked.addAll(existing.packages);
        } else if (preselectPkg != null && !preselectPkg.isEmpty()) {
            picked.add(preselectPkg);
        }

        LinearLayout box = createModalBox();

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(isEdit ? ("Раздел «" + existing.name + "»") : "Новый раздел");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hdr.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hdr.addView(makeVectorCircleBtn("close", "Закрыть", this::dismissModal),
                new LinearLayout.LayoutParams(dp(32), dp(32)));
        box.addView(hdr);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Название (напр.: Работа, Учёба, Избранное)");
        nameInput.setText(isEdit ? existing.name : "");
        nameInput.setTextColor(theme.textPrimary);
        nameInput.setHintTextColor(theme.textHint);
        nameInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        nameInput.setSingleLine(true);
        nameInput.setBackground(GlassTheme.makeGlassRect(this, theme.chipInactiveBg, theme.cardBorderColor, 14f, 1f));
        nameInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams niLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        niLp.topMargin = dp(10);
        box.addView(nameInput, niLp);

        final EditText filterInput = new EditText(this);
        filterInput.setHint("Найти приложение для добавления...");
        filterInput.setTextColor(theme.textPrimary);
        filterInput.setHintTextColor(theme.textHint);
        filterInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        filterInput.setSingleLine(true);
        filterInput.setBackground(GlassTheme.makeGlassRect(this, theme.chipInactiveBg, theme.cardBorderColor, 12f, 1f));
        filterInput.setPadding(dp(11), dp(8), dp(11), dp(8));
        LinearLayout.LayoutParams fiLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        fiLp.topMargin = dp(8);
        box.addView(filterInput, fiLp);

        final TextView countTv = new TextView(this);
        countTv.setTextColor(theme.accentColor);
        countTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        countTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        countTv.setPadding(dp(2), dp(8), dp(2), dp(4));
        box.addView(countTv);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox);
        int maxH = Math.min(dp(280), Math.round(getResources().getDisplayMetrics().heightPixels * 0.36f));
        box.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH
        ));

        // Уникальный отсортированный список приложений по packageName (отмеченные — сверху при открытии)
        final List<AppEntry> uniqueApps = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (AppEntry e : visibleApps) {
            if (seen.add(e.packageName)) uniqueApps.add(e);
        }
        Collections.sort(uniqueApps, (a, b) -> {
            boolean pa = picked.contains(a.packageName);
            boolean pb = picked.contains(b.packageName);
            if (pa != pb) return pa ? -1 : 1;
            return a.displayLabel.compareToIgnoreCase(b.displayLabel);
        });

        final Runnable[] renderRowsRef = new Runnable[1];
        renderRowsRef[0] = () -> {
            countTv.setText("Выбрано приложений: " + picked.size()
                    + " • можно добавлять в несколько разделов");
            listBox.removeAllViews();
            String q = filterInput.getText() != null ? filterInput.getText().toString().trim() : "";
            for (final AppEntry app : uniqueApps) {
                if (!q.isEmpty() && !SearchHelper.matches(app, q)) continue;
                final boolean sel = picked.contains(app.packageName);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(7), dp(8), dp(7));
                if (sel) {
                    row.setBackground(GlassTheme.makeRoundedRect(0x262B8BFF, dp(12)));
                }

                ImageView ico = new ImageView(this);
                ico.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(34), dp(34));
                iLp.rightMargin = dp(10);
                row.addView(ico, iLp);
                repository.bindIcon(app, ico);

                TextView lbl = new TextView(this);
                lbl.setText(app.displayLabel);
                lbl.setTextColor(theme.textPrimary);
                lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                lbl.setSingleLine(true);
                lbl.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView chk = new TextView(this);
                chk.setGravity(Gravity.CENTER);
                chk.setTextColor(Color.WHITE);
                chk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                chk.setTypeface(Typeface.DEFAULT_BOLD);
                chk.setText(sel ? "✓" : "");
                chk.setBackground(sel
                        ? GlassTheme.makeCircle(GlassTheme.ACCENT_BLUE)
                        : GlassTheme.makeGlassRect(this, 0x551C1D22, 0x88FFFFFF, 11f, 1.1f));
                row.addView(chk, new LinearLayout.LayoutParams(dp(22), dp(22)));

                row.setOnClickListener(v -> {
                    if (picked.contains(app.packageName)) picked.remove(app.packageName);
                    else picked.add(app.packageName);
                    renderRowsRef[0].run();
                });

                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                rLp.bottomMargin = dp(3);
                listBox.addView(row, rLp);
            }
        };
        renderRowsRef[0].run();

        filterInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { renderRowsRef[0].run(); }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(10), 0, 0);

        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("Отмена");
        cancelBtn.setTextColor(theme.textSecondary);
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        cancelBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
        cancelBtn.setOnClickListener(v -> dismissModal());
        actions.addView(cancelBtn);

        TextView saveBtn = new TextView(this);
        saveBtn.setText(isEdit ? "Сохранить" : "Создать раздел");
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        saveBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        saveBtn.setBackground(GlassTheme.makeRoundedRect(GlassTheme.ACCENT_BLUE, dp(12)));
        saveBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        saveBtn.setOnClickListener(v -> {
            String rawName = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
            if (rawName.isEmpty()) {
                Toast.makeText(this, "Введите название раздела", Toast.LENGTH_SHORT).show();
                return;
            }
            List<CustomSection> list = prefs.getCustomSections();
            if (isEdit) {
                CustomSection target = CustomSection.findById(list, existing.id);
                if (target != null) {
                    target.name = CustomSection.sanitizeName(rawName);
                    target.packages.clear();
                    target.packages.addAll(picked);
                }
                prefs.saveCustomSections(list);
                Toast.makeText(this, "Раздел «" + CustomSection.sanitizeName(rawName) + "» обновлён", Toast.LENGTH_SHORT).show();
            } else {
                if (list.size() >= CustomSection.MAX_SECTIONS) {
                    Toast.makeText(this, "Достигнут лимит разделов (" + CustomSection.MAX_SECTIONS + ")", Toast.LENGTH_SHORT).show();
                    return;
                }
                String newId = CustomSection.nextUniqueId(list);
                CustomSection created = new CustomSection(newId, rawName, picked);
                list.add(created);
                prefs.saveCustomSections(list);
                selectedCustomSectionId = created.id;
                selectedDrawerCategory = AppCategoryHelper.CAT_ALL;
                Toast.makeText(this, "Создан раздел «" + created.name + "»", Toast.LENGTH_SHORT).show();
            }
            hideKeyboard();
            dismissModal();
            rebuildDrawerCategories();
            applyFilter(drawerSearchInput != null ? drawerSearchInput.getText().toString() : "", false);
        });
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sLp.leftMargin = dp(6);
        actions.addView(saveBtn, sLp);
        box.addView(actions);

        showModalCard(box, Gravity.BOTTOM, false);
    }

    private void showCustomSectionActionModal(final CustomSection sec) {
        if (sec == null) return;
        List<CustomSection> all = prefs.getCustomSections();
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (sec.id.equals(all.get(i).id)) { idx = i; break; }
        }

        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Раздел «" + sec.name + "»");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, dp(6), dp(8));
        box.addView(title);

        addModalAction(box, "Изменить название и приложения", theme.accentColor,
                () -> showCreateOrEditSectionModal(sec, null));

        if (idx > 0) {
            addModalAction(box, "Сдвинуть левее (◀)", theme.textPrimary, () -> {
                List<CustomSection> list = prefs.getCustomSections();
                if (CustomSection.moveSection(list, sec.id, -1)) {
                    prefs.saveCustomSections(list);
                    rebuildDrawerCategories();
                }
            });
        }
        if (idx >= 0 && idx < all.size() - 1) {
            addModalAction(box, "Сдвинуть правее (▶)", theme.textPrimary, () -> {
                List<CustomSection> list = prefs.getCustomSections();
                if (CustomSection.moveSection(list, sec.id, +1)) {
                    prefs.saveCustomSections(list);
                    rebuildDrawerCategories();
                }
            });
        }

        addModalAction(box, "Управление всеми разделами...", theme.textPrimary, this::showManageSectionsModal);

        addModalAction(box, "Удалить раздел", theme.dangerColor, () -> {
            List<CustomSection> list = prefs.getCustomSections();
            CustomSection.removeSection(list, sec.id);
            prefs.saveCustomSections(list);
            if (sec.id.equals(selectedCustomSectionId)) {
                selectedCustomSectionId = null;
            }
            rebuildDrawerCategories();
            applyFilter(drawerSearchInput != null ? drawerSearchInput.getText().toString() : "", false);
            Toast.makeText(this, "Раздел «" + sec.name + "» удалён", Toast.LENGTH_SHORT).show();
        });

        showModalCard(box, Gravity.BOTTOM, false);
    }

    private void showStdCategoryActionModal(final int cat) {
        String titleStr = AppCategoryHelper.getTitle(cat);
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Раздел «" + titleStr + "»");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, dp(6), dp(8));
        box.addView(title);

        addModalAction(box, "Скрыть раздел «" + titleStr + "»", theme.dangerColor, () -> {
            prefs.setStdCategoryHidden(cat, true);
            if (selectedDrawerCategory == cat) {
                selectedDrawerCategory = AppCategoryHelper.CAT_ALL;
            }
            rebuildDrawerCategories();
            applyFilter(drawerSearchInput != null ? drawerSearchInput.getText().toString() : "", false);
            Toast.makeText(this, "Скрыто. Вернуть можно в «Настроить разделы»", Toast.LENGTH_SHORT).show();
        });
        addModalAction(box, "Создать свой раздел...", theme.accentColor,
                () -> showCreateOrEditSectionModal(null, null));
        addModalAction(box, "Управление всеми разделами...", theme.textPrimary, this::showManageSectionsModal);

        showModalCard(box, Gravity.BOTTOM, false);
    }

    /**
     * Doxaiz 4.3: Полный контроль разделов во «Все приложения»:
     * порядок своих разделов (◀ / ▶), редактирование/удаление + скрытие/возврат стандартных категорий.
     */
    private void showManageSectionsModal() {
        showManageSectionsModalInternal(true);
    }

    private void showManageSectionsModalInternal(boolean animate) {
        LinearLayout box = createModalBox();

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Разделы «Все приложения»");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hdr.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hdr.addView(makeVectorCircleBtn("close", "Закрыть", this::dismissModal),
                new LinearLayout.LayoutParams(dp(32), dp(32)));
        box.addView(hdr);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(8), 0, dp(4));

        TextView customHdr = new TextView(this);
        customHdr.setText("МОИ РАЗДЕЛЫ (ПОРЯДОК ◀ / ▶)");
        customHdr.setTextColor(theme.textSecondary);
        customHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        customHdr.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        customHdr.setPadding(dp(2), dp(4), dp(2), dp(6));
        body.addView(customHdr);

        final List<CustomSection> sections = prefs.getCustomSections();
        if (sections.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Пока нет своих разделов — нажмите «+ Создать раздел»");
            empty.setTextColor(theme.textSecondary);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            empty.setPadding(dp(4), dp(4), dp(4), dp(8));
            body.addView(empty);
        } else {
            for (int i = 0; i < sections.size(); i++) {
                final CustomSection sec = sections.get(i);
                final boolean canLeft = (i > 0);
                final boolean canRight = (i < sections.size() - 1);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(GlassTheme.makeRoundedRect(theme.chipInactiveBg, dp(13)));
                row.setPadding(dp(11), dp(8), dp(8), dp(8));
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                rLp.bottomMargin = dp(6);

                TextView nameTv = new TextView(this);
                nameTv.setText(sec.name + " (" + sec.packages.size() + ")");
                nameTv.setTextColor(theme.textPrimary);
                nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                nameTv.setSingleLine(true);
                nameTv.setEllipsize(TextUtils.TruncateAt.END);
                nameTv.setOnClickListener(v -> showCreateOrEditSectionModal(sec, null));
                row.addView(nameTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView leftBtn = makeSmallOrderBtn("◀", canLeft);
                leftBtn.setOnClickListener(v -> {
                    if (!canLeft) return;
                    List<CustomSection> list = prefs.getCustomSections();
                    if (CustomSection.moveSection(list, sec.id, -1)) {
                        prefs.saveCustomSections(list);
                        rebuildDrawerCategories();
                        showManageSectionsModalInternal(false);
                    }
                });
                row.addView(leftBtn);

                TextView rightBtn = makeSmallOrderBtn("▶", canRight);
                rightBtn.setOnClickListener(v -> {
                    if (!canRight) return;
                    List<CustomSection> list = prefs.getCustomSections();
                    if (CustomSection.moveSection(list, sec.id, +1)) {
                        prefs.saveCustomSections(list);
                        rebuildDrawerCategories();
                        showManageSectionsModalInternal(false);
                    }
                });
                row.addView(rightBtn);

                FrameLayout editBtn = new FrameLayout(this);
                editBtn.setBackground(GlassTheme.makeRoundedRect(0x22FFFFFF, dp(9)));
                editBtn.setPadding(dp(7), dp(6), dp(7), dp(6));
                editBtn.addView(VectorGlyphs.makeIconView(this, "edit", 14,
                        theme.styleMode == Prefs.STYLE_CLASSIC_LIGHT ? 0xFF174E9A : 0xFF93C5FD),
                        new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
                LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ebLp.leftMargin = dp(5);
                editBtn.setOnClickListener(v -> showCreateOrEditSectionModal(sec, null));
                row.addView(editBtn, ebLp);

                FrameLayout delBtn = new FrameLayout(this);
                delBtn.setBackground(GlassTheme.makeRoundedRect(0x26EF4444, dp(9)));
                delBtn.setPadding(dp(7), dp(6), dp(7), dp(6));
                delBtn.addView(VectorGlyphs.makeIconView(this, "trash", 14,
                        theme.styleMode == Prefs.STYLE_CLASSIC_LIGHT ? 0xFF9E1C2A : 0xFFFCA5A5),
                        new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
                LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dbLp.leftMargin = dp(5);
                delBtn.setOnClickListener(v -> {
                    List<CustomSection> list = prefs.getCustomSections();
                    CustomSection.removeSection(list, sec.id);
                    prefs.saveCustomSections(list);
                    if (sec.id.equals(selectedCustomSectionId)) selectedCustomSectionId = null;
                    rebuildDrawerCategories();
                    applyFilter(drawerSearchInput != null ? drawerSearchInput.getText().toString() : "", false);
                    showManageSectionsModalInternal(false);
                });
                row.addView(delBtn, dbLp);

                body.addView(row, rLp);
            }
        }

        // Кнопка «+ Создать раздел»
        TextView addBtn = new TextView(this);
        addBtn.setText("+ Создать свой раздел");
        addBtn.setTextColor(theme.styleMode == Prefs.STYLE_CLASSIC_LIGHT ? 0xFF174E9A : 0xFF93C5FD);
        addBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        addBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setBackground(GlassTheme.makeGlassRect(this, 0x262B8BFF, 0x662B8BFF, 13f, 1f));
        addBtn.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        abLp.topMargin = dp(2);
        abLp.bottomMargin = dp(12);
        addBtn.setOnClickListener(v -> showCreateOrEditSectionModal(null, null));
        body.addView(addBtn, abLp);

        // Скрытие / возврат стандартных категорий
        TextView stdHdr = new TextView(this);
        stdHdr.setText("СТАНДАРТНЫЕ РАЗДЕЛЫ (ПОКАЗЫВАТЬ / СКРЫТЬ)");
        stdHdr.setTextColor(theme.textSecondary);
        stdHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        stdHdr.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        stdHdr.setPadding(dp(2), dp(2), dp(2), dp(6));
        body.addView(stdHdr);

        int[] stdCats = {
                AppCategoryHelper.CAT_COMM, AppCategoryHelper.CAT_FINANCE,
                AppCategoryHelper.CAT_MEDIA, AppCategoryHelper.CAT_GAMES,
                AppCategoryHelper.CAT_TOOLS, AppCategoryHelper.CAT_SYSTEM
        };
        for (final int cat : stdCats) {
            boolean shown = !prefs.isStdCategoryHidden(cat);
            addToggleRow(body, AppCategoryHelper.getTitle(cat),
                    shown ? "Виден в полосе разделов" : "Скрыт из полосы разделов",
                    shown,
                    checked -> {
                        prefs.setStdCategoryHidden(cat, !checked);
                        if (!checked && selectedDrawerCategory == cat) {
                            selectedDrawerCategory = AppCategoryHelper.CAT_ALL;
                        }
                        rebuildDrawerCategories();
                        applyFilter(drawerSearchInput != null ? drawerSearchInput.getText().toString() : "", false);
                    });
        }

        scroll.addView(body);
        int maxH = Math.min(dp(380), Math.round(getResources().getDisplayMetrics().heightPixels * 0.56f));
        box.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH
        ));

        showModalCardInternal(box, Gravity.BOTTOM, false, animate);
    }

    private TextView makeSmallOrderBtn(String label, boolean enabled) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(enabled ? theme.textPrimary : theme.textHint);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(GlassTheme.makeRoundedRect(theme.cardBgColor, dp(9)));
        tv.setPadding(dp(8), dp(5), dp(8), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.leftMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    /**
     * Doxaiz 4.3: Отметить, в каких своих разделах состоит приложение (мульти-принадлежность).
     */
    private void showPickCustomSectionsForAppModal(final AppEntry entry) {
        LinearLayout box = createModalBox();
        addModalAppHeader(box, entry);

        TextView sub = new TextView(this);
        sub.setText("Отметьте разделы — приложение может быть в нескольких сразу");
        sub.setTextColor(theme.textSecondary);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        sub.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(sub);

        final List<CustomSection> sections = prefs.getCustomSections();
        if (sections.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("У вас пока нет своих разделов");
            empty.setTextColor(theme.textSecondary);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setPadding(dp(4), dp(6), dp(4), dp(10));
            box.addView(empty);
        } else {
            ScrollView secScroll = new ScrollView(this);
            secScroll.setVerticalScrollBarEnabled(false);
            LinearLayout secList = new LinearLayout(this);
            secList.setOrientation(LinearLayout.VERTICAL);
            for (final CustomSection sec : sections) {
                boolean member = sec.packages.contains(entry.packageName);
                addToggleRow(secList, sec.name,
                        member ? "Добавлено в этот раздел" : "Не в этом разделе",
                        member,
                        checked -> {
                            List<CustomSection> list = prefs.getCustomSections();
                            CustomSection target = CustomSection.findById(list, sec.id);
                            if (target != null) {
                                if (checked) target.packages.add(entry.packageName);
                                else target.packages.remove(entry.packageName);
                                prefs.saveCustomSections(list);
                                applyFilter(drawerSearchInput != null ? drawerSearchInput.getText().toString() : "", false);
                            }
                        });
            }
            secScroll.addView(secList);
            if (sections.size() > 5) {
                box.addView(secScroll, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(250)));
            } else {
                box.addView(secScroll, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        addModalAction(box, "+ Создать новый раздел с «" + entry.displayLabel + "»", theme.accentColor,
                () -> showCreateOrEditSectionModal(null, entry.packageName));

        showModalCard(box, Gravity.BOTTOM, false);
    }

    /**
     * Doxaiz 4.1: Ручное переназначение категории приложения в «Все приложения».
     */
    private void showPickCategoryModal(final AppEntry entry) {
        LinearLayout box = createModalBox();
        addModalAppHeader(box, entry);

        TextView sub = new TextView(this);
        sub.setText("Выберите категорию для приложения");
        sub.setTextColor(theme.textSecondary);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        sub.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(sub);

        int[] cats = {
                AppCategoryHelper.CAT_COMM,
                AppCategoryHelper.CAT_FINANCE,
                AppCategoryHelper.CAT_MEDIA,
                AppCategoryHelper.CAT_GAMES,
                AppCategoryHelper.CAT_TOOLS,
                AppCategoryHelper.CAT_SYSTEM
        };
        for (final int cat : cats) {
            boolean cur = (entry.category == cat);
            String label = AppCategoryHelper.getTitle(cat) + (cur ? "  ✓" : "");
            addModalAction(box, label, cur ? theme.accentColor : theme.textPrimary, () -> {
                prefs.setCustomCategory(entry.packageName, cat);
                applyCustomCategoryLocally(entry.packageName, cat);
                Toast.makeText(this,
                        entry.displayLabel + " → «" + AppCategoryHelper.getTitle(cat) + "»",
                        Toast.LENGTH_SHORT).show();
            });
        }
        if (prefs.getCustomCategory(entry.packageName) > 0) {
            addModalAction(box, "Определять автоматически (сбросить)", theme.textSecondary, () -> {
                prefs.setCustomCategory(entry.packageName, -1);
                repository.reload();
                Toast.makeText(this, "Категория сброшена на авто", Toast.LENGTH_SHORT).show();
            });
        }

        showModalCard(box, Gravity.BOTTOM, false);
    }

    private void applyCustomCategoryLocally(String packageName, int newCat) {
        for (AppEntry e : visibleApps) {
            if (packageName.equals(e.packageName)) {
                e.category = newCat;
            }
        }
        for (AppEntry e : appsByKey.values()) {
            if (packageName.equals(e.packageName)) {
                e.category = newCat;
            }
        }
        applyFilter(drawerSearchInput != null ? drawerSearchInput.getText().toString() : "", false);
    }

    // === Doxaiz 4.1: Пакетный выбор и быстрое последовательное удаление в «Все приложения» ===

    private void enterDrawerBatchMode(String preselectPkg) {
        drawerBatchMode = true;
        drawerBatchSelectedPkgs.clear();
        if (preselectPkg != null && !preselectPkg.isEmpty()) {
            drawerBatchSelectedPkgs.add(preselectPkg);
        }
        if (drawerBatchBar != null) drawerBatchBar.setVisibility(View.VISIBLE);
        updateDrawerBatchBtnStyle();
        updateDrawerBatchBar();
        if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
    }

    private void exitDrawerBatchMode() {
        drawerBatchMode = false;
        drawerBatchSelectedPkgs.clear();
        if (drawerBatchBar != null) drawerBatchBar.setVisibility(View.GONE);
        updateDrawerBatchBtnStyle();
        if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
    }

    private void toggleDrawerBatchItem(AppEntry entry) {
        if (entry == null) return;
        if (entry.isSystemApp || !entry.isMainProfile) {
            Toast.makeText(this, "Системные приложения удалить нельзя", Toast.LENGTH_SHORT).show();
            return;
        }
        if (drawerBatchSelectedPkgs.contains(entry.packageName)) {
            drawerBatchSelectedPkgs.remove(entry.packageName);
        } else {
            drawerBatchSelectedPkgs.add(entry.packageName);
        }
        updateDrawerBatchBar();
        if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
    }

    private void updateDrawerBatchBar() {
        if (drawerBatchCountTv == null || drawerBatchDeleteBtn == null) return;
        int n = drawerBatchSelectedPkgs.size();
        drawerBatchCountTv.setText(n == 0 ? "Отметьте приложения" : ("Выбрано: " + n));
        drawerBatchDeleteBtn.setText(n == 0 ? "Удалить" : ("Удалить (" + n + ")"));
        drawerBatchDeleteBtn.setAlpha(n > 0 ? 1f : 0.45f);
        drawerBatchDeleteBtn.setEnabled(n > 0);
    }

    private void updateDrawerBatchBtnStyle() {
        if (drawerBatchBtn == null || drawerBatchIcon == null || theme == null) return;
        if (drawerBatchMode) {
            drawerBatchBtn.setBackground(GlassTheme.makeRoundedRect(0xFFEF4444, dp(12)));
            tintIcon(drawerBatchIcon, Color.WHITE);
        } else {
            drawerBatchBtn.setBackground(null);
            tintIcon(drawerBatchIcon, theme.textPrimary);
        }
    }

    private void startBatchUninstallSelected() {
        if (drawerBatchSelectedPkgs.isEmpty()) return;
        pendingBatchUninstallPkgs.clear();
        pendingBatchUninstallPkgs.addAll(drawerBatchSelectedPkgs);
        exitDrawerBatchMode();
        launchNextBatchUninstall();
    }

    private void launchNextBatchUninstall() {
        if (isFinishing() || isDestroyed()) {
            pendingBatchUninstallPkgs.clear();
            awaitingBatchUninstallResume = false;
            return;
        }
        String selfPkg = getPackageName();
        while (!pendingBatchUninstallPkgs.isEmpty()) {
            String pkg = pendingBatchUninstallPkgs.pollFirst();
            if (pkg == null || pkg.isEmpty() || pkg.equals(selfPkg) || !isPackageInstalled(pkg)) continue;
            awaitingBatchUninstallResume = true;
            try {
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
                return;
            } catch (Throwable ignored) {
                awaitingBatchUninstallResume = false;
            }
        }
        awaitingBatchUninstallResume = false;
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void showPageEmptyMenu(final int pageIndex, final int cellCol, final int cellRow) {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Doxaiz • Страница " + (pageIndex + 1)
                + (prefs.isDesktopLocked() ? " (зафиксировано)" : ""));
        title.setTextColor(theme.textSecondary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, dp(6), dp(8));
        box.addView(title);

        if (prefs.isDesktopLocked()) {
            addModalAction(box, "Разблокировать перетаскивание", theme.accentColor, () -> {
                prefs.setDesktopLocked(false);
                Toast.makeText(this, "Раскладка разблокирована", Toast.LENGTH_SHORT).show();
            });
        } else {
            addModalAction(box, "Выбрать несколько иконок", theme.accentColor,
                    () -> enterMultiSelectMode(null));
            addModalAction(box, "Добавить приложение сюда", theme.textPrimary,
                    () -> showAddAppToPageModal(pageIndex, cellCol, cellRow));
            addModalAction(box, "Создать папку здесь", theme.textPrimary,
                    () -> showCreateFolderModal(pageIndex, -1, null, cellCol, cellRow));
            addModalAction(box, "Зафиксировать раскладку", theme.accentColor, () -> {
                prefs.setDesktopLocked(true);
                Toast.makeText(this, "Места иконок зафиксированы", Toast.LENGTH_SHORT).show();
            });

            if (desktop.pages.size() < DesktopModel.MAX_PAGES) {
                addModalAction(box, "Добавить новую страницу", theme.textPrimary, () -> {
                    int newIdx = desktop.addPage();
                    currentPage = newIdx;
                    rebuildDesktop();
                });
            }
            if (desktop.pages.size() > 1) {
                addModalAction(box, "Удалить эту страницу", theme.dangerColor, () -> showConfirmModal(
                        "Удалить страницу " + (pageIndex + 1) + "?",
                        "Ярлыки на этой странице будут убраны с рабочего стола. Сами приложения останутся в общем списке.",
                        () -> {
                            int oldHome = prefs.getHomePageIndex();
                            desktop.removePage(pageIndex);
                            prefs.setHomePageIndex(DesktopModel.adjustHomePageAfterRemoval(
                                    oldHome, pageIndex, desktop.pages.size()));
                            currentPage = Math.max(0, Math.min(currentPage, desktop.pages.size() - 1));
                            rebuildDesktop();
                        }
                ));
            }
        }

        addModalAction(box, "Обзор страниц и оформление", theme.accentColor, this::showHomeOverviewModal);
        addModalAction(box, "Настройки Doxaiz", theme.textPrimary, () -> showScreen(SCREEN_SETTINGS));
        addModalAction(box, "Сменить обои", theme.textPrimary, this::openWallpaperChooser);

        showModalCard(box, Gravity.BOTTOM, false);
    }

    /**
     * Менеджер страниц рабочего стола (щипок двумя пальцами или из меню рабочего стола):
     * мини-карточки страниц, добавление/удаление страниц + 4 быстрых векторных действия.
     */
    private void showHomeOverviewModal() {
        LinearLayout box = createModalBox();

        // Шапка
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(4), 0, dp(4), dp(12));

        TextView title = new TextView(this);
        title.setText("Страницы рабочего стола");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hdr.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView countTv = new TextView(this);
        countTv.setText(desktop.pages.size() + " / " + DesktopModel.MAX_PAGES);
        countTv.setTextColor(0xFF9EA0A6);
        countTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        hdr.addView(countTv);
        box.addView(hdr);

        // Горизонтальная лента мини-превью страниц
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(4), dp(2), dp(10));

        for (int p = 0; p < desktop.pages.size(); p++) {
            final int pageIdx = p;
            final List<DesktopItem> page = desktop.pages.get(p);
            final boolean active = (pageIdx == currentPage);

            FrameLayout card = new FrameLayout(this);
            card.setBackground(GlassTheme.makeGlassRect(
                    this,
                    active ? 0xFF1A2538 : 0xFF232327,
                    active ? 0xFF2B8BFF : 0xFF3A3A40,
                    18f, active ? 1.8f : 1f
            ));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(106), dp(148));
            cLp.rightMargin = dp(10);
            card.setLayoutParams(cLp);

            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            inner.setPadding(dp(8), dp(10), dp(8), dp(10));

            // Стилизованная мини-сетка занятых ячеек (4 точки x 3 ряда)
            final int itemCount = page != null ? page.size() : 0;
            LinearLayout miniGrid = new LinearLayout(this);
            miniGrid.setOrientation(LinearLayout.VERTICAL);
            miniGrid.setGravity(Gravity.CENTER);
            int filledDots = Math.min(12, itemCount);
            int dotIdx = 0;
            for (int r = 0; r < 3; r++) {
                LinearLayout dotRow = new LinearLayout(this);
                dotRow.setOrientation(LinearLayout.HORIZONTAL);
                dotRow.setGravity(Gravity.CENTER);
                for (int c = 0; c < 4; c++) {
                    View dot = new View(this);
                    boolean on = dotIdx < filledDots;
                    dot.setBackground(GlassTheme.makeGlassRect(
                            this,
                            on ? (active ? 0xFF60A5FA : 0xFFD0D4DC) : 0xFF34343A,
                            Color.TRANSPARENT, 4f, 0f
                    ));
                    LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(12), dp(12));
                    dLp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    dotRow.addView(dot, dLp);
                    dotIdx++;
                }
                miniGrid.addView(dotRow);
            }
            inner.addView(miniGrid);

            TextView pLbl = new TextView(this);
            pLbl.setText("Экран " + (pageIdx + 1));
            pLbl.setTextColor(active ? Color.WHITE : 0xFFD5D7DC);
            pLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            pLbl.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams plLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            plLp.topMargin = dp(10);
            inner.addView(pLbl, plLp);

            TextView cLbl = new TextView(this);
            final boolean isHomePage = (pageIdx == getEffectiveHomePageIndex());
            cLbl.setText(isHomePage ? ("Главный • " + itemCount + " эл.") : (itemCount == 0 ? "Пусто" : itemCount + " эл."));
            cLbl.setTextColor(isHomePage ? 0xFF4ADE80 : (active ? 0xFF93C5FD : 0xFF8E9098));
            cLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.2f);
            inner.addView(cLbl);

            card.addView(inner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ));

            // Кнопка выбора Главной страницы (домик в левом верхнем углу)
            FrameLayout homeBtn = new FrameLayout(this);
            homeBtn.setBackground(GlassTheme.makeGlassRect(
                    this,
                    isHomePage ? 0xFF143523 : 0xFF2E2E34,
                    isHomePage ? 0xFF22C55E : 0xFF44444D,
                    11f, 0.8f
            ));
            ImageView homeIco = VectorGlyphs.makeIconView(
                    this, "home", 11, isHomePage ? 0xFF4ADE80 : 0xFFB0B3BC);
            FrameLayout.LayoutParams hiLp = new FrameLayout.LayoutParams(dp(11), dp(11));
            hiLp.gravity = Gravity.CENTER;
            homeBtn.addView(homeIco, hiLp);
            FrameLayout.LayoutParams hbLp = new FrameLayout.LayoutParams(dp(22), dp(22));
            hbLp.gravity = Gravity.TOP | Gravity.START;
            hbLp.topMargin = dp(6);
            hbLp.leftMargin = dp(6);
            homeBtn.setOnClickListener(v -> {
                if (prefs.isHapticFeedbackEnabled()) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                prefs.setHomePageIndex(pageIdx);
                rebuildPageDots();
                Toast.makeText(this, "Главный экран: Страница " + (pageIdx + 1), Toast.LENGTH_SHORT).show();
                showHomeOverviewModal();
            });
            card.addView(homeBtn, hbLp);

            // Кнопка удаления страницы (если страниц больше 1)
            if (desktop.pages.size() > 1 && !prefs.isDesktopLocked()) {
                FrameLayout delBtn = new FrameLayout(this);
                delBtn.setBackground(GlassTheme.makeGlassRect(this, 0xFF3A1F22, 0xFFEF4444, 11f, 0.8f));
                ImageView delIco = VectorGlyphs.makeIconView(this, "close", 10, 0xFFFCA5A5);
                FrameLayout.LayoutParams diLp = new FrameLayout.LayoutParams(dp(10), dp(10));
                diLp.gravity = Gravity.CENTER;
                delBtn.addView(delIco, diLp);
                FrameLayout.LayoutParams dbLp = new FrameLayout.LayoutParams(dp(22), dp(22));
                dbLp.gravity = Gravity.TOP | Gravity.END;
                dbLp.topMargin = dp(6);
                dbLp.rightMargin = dp(6);
                delBtn.setOnClickListener(v -> {
                    if (prefs.isHapticFeedbackEnabled()) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                    Runnable doDelete = () -> {
                        int oldHome = prefs.getHomePageIndex();
                        desktop.removePage(pageIdx);
                        prefs.setHomePageIndex(DesktopModel.adjustHomePageAfterRemoval(
                                oldHome, pageIdx, desktop.pages.size()));
                        currentPage = Math.max(0, Math.min(currentPage, desktop.pages.size() - 1));
                        rebuildDesktop();
                        showHomeOverviewModal();
                    };
                    if (itemCount == 0) {
                        doDelete.run();
                    } else {
                        showConfirmModal(
                                "Удалить экран " + (pageIdx + 1) + "?",
                                "Ярлыки на этой странице будут убраны с рабочего стола. Сами приложения останутся в меню.",
                                doDelete
                        );
                    }
                });
                card.addView(delBtn, dbLp);
            }

            card.setOnClickListener(v -> {
                if (prefs.isHapticFeedbackEnabled()) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                currentPage = pageIdx;
                if (pagerView != null) {
                    pagerView.goToPage(pageIdx);
                }
                rebuildPageDots();
                dismissModal();
            });
            row.addView(card);
        }

        // Карточка «+ Добавить экран»
        if (desktop.pages.size() < DesktopModel.MAX_PAGES && !prefs.isDesktopLocked()) {
            LinearLayout addCard = new LinearLayout(this);
            addCard.setOrientation(LinearLayout.VERTICAL);
            addCard.setGravity(Gravity.CENTER);
            addCard.setBackground(GlassTheme.makeGlassRect(this, 0xFF1A1A1E, 0xFF3E3E46, 18f, 1f));
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(dp(94), dp(148));
            addCard.setLayoutParams(aLp);

            ImageView plusIco = VectorGlyphs.makeIconView(this, "plus", 26, 0xFF2B8BFF);
            addCard.addView(plusIco, new LinearLayout.LayoutParams(dp(26), dp(26)));

            TextView addLbl = new TextView(this);
            addLbl.setText("Добавить");
            addLbl.setTextColor(0xFF9EA0A6);
            addLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            LinearLayout.LayoutParams alLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            alLp.topMargin = dp(8);
            addCard.addView(addLbl, alLp);

            addCard.setOnClickListener(v -> {
                if (prefs.isHapticFeedbackEnabled()) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                int newIdx = desktop.addPage();
                currentPage = newIdx;
                rebuildDesktop();
                showHomeOverviewModal();
            });
            row.addView(addCard);
        }

        scroll.addView(row);
        box.addView(scroll);

        // Нижняя панель из 4 быстрых векторных кнопок: Обои, Dynamic Bar, Сетка, Настройки
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actLp.topMargin = dp(8);
        actions.setLayoutParams(actLp);

        actions.addView(makeOverviewQuickBtn("wallpaper", "Обои", false, false, () -> {
            dismissModal();
            openWallpaperChooser();
        }));
        actions.addView(makeOverviewQuickBtn("island", "Dynamic Bar", prefs.isDynamicBarEnabled(), true, () -> {
            dismissModal();
            if (dynamicBarHelper != null) dynamicBarHelper.showSettingsDialog();
        }));
        boolean locked = prefs.isDesktopLocked();
        actions.addView(makeOverviewQuickBtn("lock", locked ? "Закреплено" : "Фиксация", locked, true, () -> {
            prefs.setDesktopLocked(!locked);
            Toast.makeText(this, !locked ? "Раскладка иконок зафиксирована" : "Раскладка разблокирована", Toast.LENGTH_SHORT).show();
            showHomeOverviewModal();
        }));
        actions.addView(makeOverviewQuickBtn("settings", "Настройки", false, true, () -> {
            dismissModal();
            showScreen(SCREEN_SETTINGS);
        }));
        box.addView(actions);

        showModalCard(box, Gravity.BOTTOM, false);
    }

    private View makeOverviewQuickBtn(String glyph, String label, boolean active, boolean leftGap, Runnable onClick) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(4), dp(10), dp(4), dp(10));
        col.setBackground(GlassTheme.makeGlassRect(
                this,
                active ? 0xFF2B8BFF : 0xFF28282D,
                Color.TRANSPARENT, 16f, 0f
        ));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (leftGap) lp.leftMargin = dp(8);
        col.setLayoutParams(lp);

        ImageView iv = VectorGlyphs.makeIconView(this, glyph, 20, Color.WHITE);
        col.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        tLp.topMargin = dp(5);
        col.addView(tv, tLp);

        col.setOnClickListener(v -> {
            if (prefs.isHapticFeedbackEnabled()) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            onClick.run();
        });
        return col;
    }

    private void showPickFolderForAppModal(final int pageIndex, final int removeItemIndex, final AppEntry entry) {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Поместить в папку");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, dp(6), dp(10));
        box.addView(title);

        for (int p = 0; p < desktop.pages.size(); p++) {
            final int targetPage = p;
            List<DesktopItem> folders = desktop.getFoldersOnPage(p);
            for (final DesktopItem f : folders) {
                String label = f.folderName + " • стр. " + (p + 1) + " (" + resolveFolderApps(f).size() + ")";
                addModalAction(box, label, theme.textPrimary, () -> {
                    desktop.addAppToFolder(targetPage, f.folderId, entry.key);
                    if (removeItemIndex >= 0) {
                        desktop.removeItem(pageIndex, removeItemIndex);
                    }
                    rebuildDesktop();
                    Toast.makeText(this, "Добавлено в «" + f.folderName + "»", Toast.LENGTH_SHORT).show();
                });
            }
        }

        int preferredCol = -1;
        int preferredRow = -1;
        if (removeItemIndex >= 0 && pageIndex >= 0 && pageIndex < desktop.pages.size()
                && removeItemIndex < desktop.pages.get(pageIndex).size()) {
            DesktopItem src = desktop.pages.get(pageIndex).get(removeItemIndex);
            preferredCol = src.col;
            preferredRow = src.row;
        }
        final int fc = preferredCol;
        final int fr = preferredRow;
        addModalAction(box, "+ Создать новую папку на стр. " + (pageIndex + 1), theme.accentColor,
                () -> showCreateFolderModal(pageIndex, removeItemIndex, entry.key, fc, fr));

        showModalCard(box, Gravity.BOTTOM, false);
    }

    private void showPickPageForItemModal(final int fromPage, final int itemIndex) {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Перенести на страницу");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, dp(6), dp(10));
        box.addView(title);

        for (int i = 0; i < desktop.pages.size(); i++) {
            if (i == fromPage) continue;
            final int target = i;
            addModalAction(box, "Страница " + (i + 1), theme.textPrimary, () -> {
                currentPage = desktop.moveItemToPage(fromPage, itemIndex, target,
                        prefs.getColumns(), prefs.isHomeBottomAligned());
                rebuildDesktop();
            });
        }
        if (desktop.pages.size() < DesktopModel.MAX_PAGES) {
            addModalAction(box, "+ Новая страница (" + (desktop.pages.size() + 1) + ")", theme.accentColor, () -> {
                currentPage = desktop.moveItemToPage(fromPage, itemIndex, desktop.pages.size(),
                        prefs.getColumns(), prefs.isHomeBottomAligned());
                rebuildDesktop();
            });
        }
        showModalCard(box, Gravity.BOTTOM, false);
    }

    private void showCreateFolderModal(final int pageIndex, final int removeItemIndex,
                                       final String initialAppKey, final int cellCol, final int cellRow) {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Новая папка");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        final EditText input = makeModalEditText("Название папки (например, Общение)", "");
        box.addView(input);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        addDialogButton(btns, "Отмена", theme.textSecondary, this::dismissModal);
        addDialogButton(btns, "Создать", theme.accentColor, () -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) name = "Папка";
            List<String> keys = initialAppKey != null
                    ? Collections.singletonList(initialAppKey)
                    : Collections.<String>emptyList();
            if (removeItemIndex >= 0) {
                desktop.removeItem(pageIndex, removeItemIndex);
            }
            DesktopItem created = desktop.createFolderAt(
                    pageIndex, name, keys, cellCol, cellRow,
                    prefs.getColumns(), pageIndex == 0 && prefs.isHomeBottomAligned()
            );
            rebuildDesktop();
            if (initialAppKey == null) {
                showFolderAppPickerModal(pageIndex, created);
            } else {
                dismissModal();
            }
        });
        box.addView(btns);

        showModalCard(box, Gravity.CENTER, false);
        focusKeyboard(input);
    }

    private void showRenameFolderModal(final int pageIndex, final DesktopItem folder) {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Переименовать папку");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        final EditText input = makeModalEditText("Название папки", folder.folderName);
        box.addView(input);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        addDialogButton(btns, "Отмена", theme.textSecondary, () -> showFolderModal(pageIndex, folder));
        addDialogButton(btns, "Сохранить", theme.accentColor, () -> {
            desktop.renameFolder(pageIndex, folder.folderId, input.getText().toString());
            rebuildDesktop();
            showFolderModal(pageIndex, folder);
        });
        box.addView(btns);

        showModalCard(box, Gravity.CENTER, false);
        focusKeyboard(input);
    }

    private void showAddAppToPageModal(final int pageIndex, final int cellCol, final int cellRow) {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Добавить на страницу " + (pageIndex + 1));
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        tLp.bottomMargin = prefs.isShowDrawerMemory() ? dp(3) : dp(10);
        box.addView(title, tLp);
        if (prefs.isShowDrawerMemory()) {
            addModalMemoryLine(box);
        }

        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        for (final AppEntry app : visibleApps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(32), dp(32));
            iLp.rightMargin = dp(12);
            row.addView(iv, iLp);
            repository.bindIcon(app, iv);

            TextView lbl = new TextView(this);
            lbl.setText(app.displayLabel);
            lbl.setTextColor(theme.textPrimary);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            lbl.setSingleLine(true);
            lbl.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(v -> {
                desktop.addAppToPageAt(pageIndex, app.key, cellCol, cellRow,
                        prefs.getColumns(), pageIndex == 0 && prefs.isHomeBottomAligned());
                rebuildDesktop();
                dismissModal();
            });
            list.addView(row);
        }

        sv.addView(list);
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.52f);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        addDialogButton(btns, "Закрыть", theme.accentColor, this::dismissModal);
        box.addView(btns);

        showModalCard(box, Gravity.CENTER, false);
    }

    private void showRenameAppModal(final AppEntry entry) {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Переименовать");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        final EditText input = makeModalEditText(entry.originalLabel, entry.displayLabel);
        box.addView(input);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        addDialogButton(btns, "Сбросить", theme.textSecondary, () -> {
            prefs.setCustomLabel(entry.key, null);
            repository.reload();
            dismissModal();
        });
        addDialogButton(btns, "Отмена", theme.textSecondary, this::dismissModal);
        addDialogButton(btns, "Сохранить", theme.accentColor, () -> {
            prefs.setCustomLabel(entry.key, input.getText().toString());
            repository.reload();
            dismissModal();
        });
        box.addView(btns);

        showModalCard(box, Gravity.CENTER, false);
        focusKeyboard(input);
    }

    private EditText makeModalEditText(String hint, String value) {
        EditText input = new EditText(this);
        input.setText(value);
        if (value != null) input.setSelection(value.length());
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextColor(theme.textPrimary);
        input.setHintTextColor(theme.textHint);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(GlassTheme.makeGlassRect(
                this, theme.pillBgColor, theme.pillBorderColor, 14f, 1f
        ));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(14);
        input.setLayoutParams(lp);
        return input;
    }

    private void focusKeyboard(final EditText input) {
        input.requestFocus();
        input.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private String formatSwipeUpMenuTitle(String sourceKey) {
        String targetKey = prefs.getIconSwipeUpTarget(sourceKey);
        AppEntry target = (targetKey != null) ? appsByKey.get(targetKey) : null;
        if (target != null && !isHiddenKey(target.key)) {
            return "Свайп вверх ↑ " + target.displayLabel;
        }
        return "Свайп вверх по иконке (2-е приложение)";
    }

    private void showPickSwipeUpAppModal(final AppEntry sourceApp) {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Свайп вверх по «" + sourceApp.displayLabel + "»");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Нажатие откроет «" + sourceApp.displayLabel + "», а быстрый свайп вверх прямо по этой иконке — выбранное второе приложение.");
        sub.setTextColor(theme.textSecondary);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sLp.topMargin = dp(4);
        sLp.bottomMargin = dp(10);
        box.addView(sub, sLp);

        String currentTarget = prefs.getIconSwipeUpTarget(sourceApp.key);
        if (currentTarget != null) {
            addModalAction(box, "Убрать свайп вверх с этой иконки", theme.dangerColor, () -> {
                prefs.setIconSwipeUpTarget(sourceApp.key, null);
                Toast.makeText(this, "Свайп вверх убран", Toast.LENGTH_SHORT).show();
            });
            View div = new View(this);
            div.setBackgroundColor(theme.dividerColor);
            box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        for (final AppEntry app : visibleApps) {
            if (app.key.equals(sourceApp.key)) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(34), dp(34));
            iLp.rightMargin = dp(12);
            row.addView(iv, iLp);
            repository.bindIcon(app, iv);

            TextView name = new TextView(this);
            name.setText(app.displayLabel);
            name.setTextColor(app.key.equals(currentTarget) ? theme.accentColor : theme.textPrimary);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (app.key.equals(currentTarget)) {
                TextView mark = new TextView(this);
                mark.setText("✓");
                mark.setTextColor(theme.accentColor);
                mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                row.addView(mark);
            }

            row.setOnClickListener(v -> {
                prefs.setIconSwipeUpTarget(sourceApp.key, app.key);
                dismissModal();
                Toast.makeText(this, "↑ Свайп по «" + sourceApp.displayLabel + "» → " + app.displayLabel,
                        Toast.LENGTH_SHORT).show();
            });
            list.addView(row);
        }

        sv.addView(list);
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.48f);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bLp.topMargin = dp(8);
        btns.setLayoutParams(bLp);
        addDialogButton(btns, "Отмена", theme.textSecondary, this::dismissModal);
        box.addView(btns);

        showModalCard(box, Gravity.CENTER, false);
    }

    private void openPrivateSpaceGuarded() {
        if (prefs.isProtectHiddenApps()) {
            if (!BiometricHelper.isDeviceSecure(this)) {
                Toast.makeText(this, "Для доступа сначала установите PIN или пароль экрана в настройках Android", Toast.LENGTH_LONG).show();
                return;
            }
            pendingPrivateSpaceAction = this::showHiddenAppsModal;
            BiometricHelper.authenticate(
                    this,
                    "Скрытые приложения",
                    "Подтвердите отпечатком пальца или PIN-кодом",
                    () -> {
                        pendingPrivateSpaceAction = null;
                        showHiddenAppsModal();
                    },
                    () -> pendingPrivateSpaceAction = null
            );
        } else {
            showHiddenAppsModal();
        }
    }

    private void showHiddenAppsModal() {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText(prefs.isProtectHiddenApps() ? "Приватные (скрытые) приложения" : "Скрытые приложения");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Нажмите на приложение, чтобы сразу открыть его, или «Вернуть», чтобы снова показать в меню.");
        sub.setTextColor(theme.textSecondary);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sLp.topMargin = dp(4);
        sLp.bottomMargin = dp(10);
        box.addView(sub, sLp);

        if (hiddenApps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Скрытых приложений нет. Нажмите «+ Скрыть» ниже или удерживайте любую иконку.");
            empty.setTextColor(theme.textSecondary);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            empty.setPadding(0, dp(4), 0, dp(12));
            box.addView(empty);
        } else {
            ScrollView sv = new ScrollView(this);
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);

            for (final AppEntry entry : hiddenApps) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(2), dp(8), dp(2), dp(8));

                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(34), dp(34));
                iLp.rightMargin = dp(12);
                row.addView(iv, iLp);
                repository.bindIcon(entry, iv);

                TextView name = new TextView(this);
                name.setText(entry.displayLabel);
                name.setTextColor(theme.textPrimary);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                row.setOnClickListener(v -> {
                    dismissModal();
                    launchAndReturnHome(entry, iv);
                });

                TextView unhideBtn = new TextView(this);
                unhideBtn.setText("Вернуть");
                unhideBtn.setTextColor(theme.accentColor);
                unhideBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                unhideBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                unhideBtn.setPadding(dp(11), dp(6), dp(11), dp(6));
                unhideBtn.setBackground(GlassTheme.makeGlassRect(
                        this, theme.accentSoftBg, theme.chipActiveBorder, 10f, 1f
                ));
                unhideBtn.setOnClickListener(v -> {
                    prefs.unhideApp(entry.key);
                    repository.reload();
                    dismissModal();
                });
                row.addView(unhideBtn);
                list.addView(row);
            }
            sv.addView(list);
            int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.44f);
            box.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    hiddenApps.size() > 6 ? maxH : ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        addDialogButton(btns, "+ Скрыть", theme.textSecondary, this::showPickAppToHideModal);
        addDialogButton(btns, "Закрыть", theme.accentColor, this::dismissModal);
        box.addView(btns);

        showModalCard(box, Gravity.CENTER, false);
    }

    private void showPickAppToHideModal() {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Скрыть приложение");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        tLp.bottomMargin = dp(8);
        box.addView(title, tLp);

        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        for (final AppEntry app : visibleApps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(34), dp(34));
            iLp.rightMargin = dp(12);
            row.addView(iv, iLp);
            repository.bindIcon(app, iv);

            TextView name = new TextView(this);
            name.setText(app.displayLabel);
            name.setTextColor(theme.textPrimary);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(v -> {
                prefs.hideApp(app.key);
                desktop.purgeAppKey(app.key);
                repository.reload();
                dismissModal();
                Toast.makeText(this, "Скрыто: " + app.displayLabel, Toast.LENGTH_SHORT).show();
            });
            list.addView(row);
        }

        sv.addView(list);
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.50f);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        addDialogButton(btns, "Назад", theme.textSecondary, this::showHiddenAppsModal);
        box.addView(btns);
        showModalCard(box, Gravity.CENTER, false);
    }

    private void showConfirmModal(String titleText, String messageText, final Runnable onConfirm) {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        TextView msg = new TextView(this);
        msg.setText(messageText);
        msg.setTextColor(theme.textSecondary);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        mLp.topMargin = dp(8);
        mLp.bottomMargin = dp(14);
        box.addView(msg, mLp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        addDialogButton(btns, "Отмена", theme.textSecondary, this::dismissModal);
        addDialogButton(btns, "Подтвердить", theme.accentColor, () -> {
            dismissModal();
            onConfirm.run();
        });
        box.addView(btns);

        showModalCard(box, Gravity.CENTER, false);
    }

    private void addDialogButton(LinearLayout row, String label, int color, final Runnable onClick) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setOnClickListener(v -> onClick.run());
        row.addView(b);
    }

    // =========================================================================
    // Экран настроек Doxaiz
    // =========================================================================

    private void rebuildSettingsContent() {
        settingsContent.removeAllViews();

        // 0. Центр готовности системы (4 ключевых статуса в 1 компактной карточке)
        settingsContent.addView(buildSystemReadinessCard());

        // 1. Статус основной оболочки
        boolean isDefault = isDefaultLauncher();
        LinearLayout homeCard = createGlassSectionCard();
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView stIcon = new ImageView(this);
        stIcon.setImageResource(isDefault ? R.drawable.ic_check : R.drawable.ic_home);
        tintIcon(stIcon, isDefault ? theme.statusOkColor : theme.accentColor);
        LinearLayout.LayoutParams siLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        siLp.rightMargin = dp(12);
        statusRow.addView(stIcon, siLp);

        LinearLayout stTexts = new LinearLayout(this);
        stTexts.setOrientation(LinearLayout.VERTICAL);
        stTexts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView stTitle = new TextView(this);
        stTitle.setText(isDefault ? "Doxaiz — основная оболочка" : "Сделать Doxaiz основной");
        stTitle.setTextColor(theme.textPrimary);
        stTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        stTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        TextView stSub = new TextView(this);
        stSub.setText(isDefault
                ? "Можно в любой момент переключиться обратно на штатную оболочку"
                : "Откройте: Приложения по умолчанию → Приложение для главного экрана → Doxaiz");
        stSub.setTextColor(theme.textSecondary);
        stSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subLp.topMargin = dp(3);
        stSub.setLayoutParams(subLp);

        stTexts.addView(stTitle);
        stTexts.addView(stSub);
        statusRow.addView(stTexts);
        homeCard.addView(statusRow);

        TextView homeBtn = new TextView(this);
        homeBtn.setText(isDefault ? "Сменить оболочку" : "Выбрать в настройках");
        homeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        homeBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        homeBtn.setGravity(Gravity.CENTER);
        homeBtn.setPadding(dp(14), dp(11), dp(14), dp(11));
        homeBtn.setTextColor(isDefault ? theme.textPrimary : theme.chipActiveText);
        homeBtn.setBackground(GlassTheme.makeGlassRect(
                this,
                isDefault ? theme.chipInactiveBg : theme.chipActiveBg,
                isDefault ? theme.cardBorderColor : theme.chipActiveBorder,
                14f, 1f
        ));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bLp.topMargin = dp(12);
        homeBtn.setLayoutParams(bLp);
        homeBtn.setOnClickListener(v -> openDefaultHomeSettings());
        homeCard.addView(homeBtn);
        settingsContent.addView(homeCard);

        // 2. Внешний вид
        addSectionTitle("Внешний вид");
        LinearLayout lookCard = createGlassSectionCard();

        addOptionGroup(lookCard, "Тема оформления",
                new String[]{"Тёмная", "Светлая"},
                prefs.getVisualStyle() == Prefs.STYLE_CLASSIC_LIGHT ? 1 : 0, index -> {
                    prefs.setVisualStyle(index == 1 ? Prefs.STYLE_CLASSIC_LIGHT : Prefs.STYLE_CLASSIC_DARK);
                    applyTheme();
                });
        addDivider(lookCard);
        addOptionGroup(lookCard, "Обои рабочего стола",
                new String[]{"Куруми × Сяо Бай", "Системные"},
                prefs.getWallpaperMode(), index -> {
                    prefs.setWallpaperMode(index);
                    applyTheme();
                });
        addDivider(lookCard);
        addOptionGroup(lookCard, "Затемнение встроенных обоев",
                new String[]{"Лёгкое", "Среднее", "Сильное"},
                prefs.getWallpaperDimLevel(), index -> {
                    prefs.setWallpaperDimLevel(index);
                    applyTheme();
                });
        addDivider(lookCard);
        addOptionGroup(lookCard, "Прозрачность панелей",
                new String[]{"Лёгкая", "Средняя", "Плотная"},
                prefs.getGlassOpacity(),
                index -> {
                    prefs.setGlassOpacity(index);
                    applyTheme();
                });

        addDivider(lookCard);
        addOptionGroup(lookCard, "Колонок в сетке",
                new String[]{"4 колонки", "5 колонок"},
                prefs.getColumns() == 5 ? 1 : 0,
                index -> {
                    prefs.setColumns(index == 1 ? 5 : 4);
                    applyTheme();
                });

        addDivider(lookCard);
        final int[] iconScales = {85, 95, 100, 108, 115};
        int curScale = prefs.getIconScalePercent();
        int curScaleIdx = 2;
        int bestDiff = 999;
        for (int i = 0; i < iconScales.length; i++) {
            int d = Math.abs(iconScales[i] - curScale);
            if (d < bestDiff) { bestDiff = d; curScaleIdx = i; }
        }
        addOptionGroup(lookCard, "Масштаб иконок (85%–115%)",
                new String[]{"85%", "95%", "100%", "108%", "115%"},
                curScaleIdx,
                index -> {
                    prefs.setIconScalePercent(iconScales[index]);
                    applyTheme();
                });

        addDivider(lookCard);
        addOptionGroup(lookCard, "Размер шрифта подписей",
                new String[]{"Мелкий", "Обычный", "Крупный"},
                prefs.getLabelTextSizeMode(),
                index -> {
                    prefs.setLabelTextSizeMode(index);
                    applyTheme();
                });

        addDivider(lookCard);
        addOptionGroup(lookCard, "Иконки на 1-й странице",
                new String[]{"Снизу (под палец)", "Сверху"},
                prefs.isHomeBottomAligned() ? 0 : 1,
                index -> {
                    boolean bottom = (index == 0);
                    prefs.setHomeBottomAligned(bottom);
                    desktop.realignPage0(prefs.getColumns(), bottom);
                    rebuildDesktop();
                    rebuildSettingsContent();
                });

        addDivider(lookCard);
        addToggleRow(lookCard, "Подписи на рабочем столе",
                "Показывать названия под иконками на страницах",
                prefs.isShowHomeLabels(),
                checked -> {
                    prefs.setShowHomeLabels(checked);
                    applyTheme();
                });

        addDivider(lookCard);
        addToggleRow(lookCard, "Подписи в нижнем доке",
                "Отображать названия под иконками в нижней панели",
                prefs.isShowDockLabels(),
                checked -> {
                    prefs.setShowDockLabels(checked);
                    applyTheme();
                });

        addDivider(lookCard);
        addToggleRow(lookCard, "Зацикленный свайп страниц",
                "Переход по кругу с последней страницы на первую • Главный экран выбирается в «Страницах»",
                prefs.isLoopHomePages(),
                checked -> {
                    prefs.setLoopHomePages(checked);
                    if (pagerView != null) pagerView.setLoopPagesEnabled(checked);
                    rebuildSettingsContent();
                });

        addDivider(lookCard);
        addToggleRow(lookCard, "Скрыть кнопку ⚙ сверху",
                "Чистый экран; настройки — долгим нажатием на пустое место",
                prefs.isHideTopSettingsBtn(),
                checked -> {
                    prefs.setHideTopSettingsBtn(checked);
                    applyTheme();
                });

        addDivider(lookCard);
        addToggleRow(lookCard, "Тактильный отклик (вибрация)",
                "Лёгкий виброотклик при нажатии плиток шторки, ползунков и Dynamic Bar",
                prefs.isHapticFeedbackEnabled(),
                checked -> {
                    prefs.setHapticFeedbackEnabled(checked);
                    rebuildSettingsContent();
                });

        settingsContent.addView(lookCard);

        // 2.5 Шторка уведомлений и звук
        addSectionTitle("Шторка уведомлений и звук");
        LinearLayout styleCard = createGlassSectionCard();

        addOptionGroup(styleCard, "Стиль шторки уведомлений",
                new String[]{"Современный", "Классический", "Системная"},
                prefs.getShadeStyle(),
                index -> {
                    prefs.setShadeStyle(index);
                    rebuildSettingsContent();
                });

        addDivider(styleCard);
        int activeTileCount = prefs.getShadeTiles().size();
        addActionRow(styleCard, "Элементы шторки («Изменить»)",
                "На панели: " + activeTileCount + " из " + QuickShadeHelper.getAllTilesCount() + " (красные «−» / зелёные «+»)",
                () -> showShadeTileEditorModal(false));

        addDivider(styleCard);
        addActionRow(styleCard, "Dynamic Bar",
                prefs.isDynamicBarEnabled()
                        ? "Вкл • Зарядка, музыка, таймер, точка доступа, ≤15%"
                        : "Выкл • Нажмите, чтобы настроить верхнюю капсулу",
                () -> {
                    if (dynamicBarHelper != null) dynamicBarHelper.showSettingsDialog();
                });

        addDivider(styleCard);
        addToggleRow(styleCard, "Всплывающие баннеры уведомлений",
                "Аккуратные тёмные карточки новых сообщений сверху с быстрым ответом (4.5 сек / свайп вверх)",
                prefs.isHeadsUpBannersEnabled(),
                checked -> {
                    prefs.setHeadsUpBannersEnabled(checked);
                    if (!checked && headsUpBannerHelper != null) headsUpBannerHelper.hideImmediate();
                    rebuildSettingsContent();
                });

        addDivider(styleCard);
        addActionRow(styleCard, "Уведомления и строка состояния",
                "Способ оповещения: " + getNotifBadgeModeLabel(prefs.getNotifBadgeMode()),
                this::showNotifStatusSettingsModal);

        if (prefs.getShadeStyle() != Prefs.SHADE_STYLE_SYSTEM) {
            addDivider(styleCard);
            addToggleRow(styleCard, "Разделённый свайп шторки",
                    "Свайп слева — Центр уведомлений, справа — Центр управления",
                    prefs.isShadeSplitSwipe(),
                    checked -> {
                        prefs.setShadeSplitSwipe(checked);
                        rebuildSettingsContent();
                    });

            addDivider(styleCard);
            addToggleRow(styleCard, "Группировать уведомления по приложениям",
                    "Сворачивать сообщения одного приложения в стопку • быстрое отключение уведомлений в 1 тап",
                    prefs.isShadeGroupNotifications(),
                    checked -> {
                        prefs.setShadeGroupNotifications(checked);
                        rebuildSettingsContent();
                    });

            addDivider(styleCard);
            addToggleRow(styleCard, "Скрывать тексты уведомлений",
                    "В шторке показывать только название приложения (приватность)",
                    prefs.isHideNotifTextInShade(),
                    checked -> {
                        prefs.setHideNotifTextInShade(checked);
                        rebuildSettingsContent();
                    });

            addDivider(styleCard);
            addActionRow(styleCard, "Открыть шторку для проверки",
                    "Посмотреть выбранный стиль (" + (prefs.getShadeStyle() == Prefs.SHADE_STYLE_REDMI ? "Классический" : "Современный") + ")",
                    this::showDoxaizShade);
        }

        addDivider(styleCard);
        addActionRow(styleCard, "Панель настройки звука (4 канала)",
                "Медиа, звонок, уведомления, будильник • также по кнопкам громкости → «•••»",
                this::showExpandedSoundPanel);

        addDivider(styleCard);
        addActionRow(styleCard, "Показ элементов экрана",
                "Добавить или убрать кнопки, подписи и точки страниц",
                this::showElementsVisibilityModal);

        settingsContent.addView(styleCard);

        // 3. Работающие приложения (ОЗУ над корзиной и мусорка работают всегда по умолчанию — без выключателей)
        boolean svcActive = DoxaizLockService.isRunning();
        if (!svcActive) {
            addSectionTitle("Экран работающих приложений (≡)");
            LinearLayout recentsRamCard = createGlassSectionCard();
            addActionRow(recentsRamCard, "Активировать панель ОЗУ и мусорку в недавних",
                    "Спец. возможности → Doxaiz (работает по умолчанию)",
                    this::openAccessibilitySettings);
            settingsContent.addView(recentsRamCard);
        }

        addSectionTitle("Сеть и авто-улучшение связи");
        LinearLayout netCard = createGlassSectionCard();
        NetworkQualityHelper.Status netStatus = (networkHelper != null)
                ? networkHelper.inspect()
                : new NetworkQualityHelper.Status(false, false, false, "—", 0, 0, "Проверка...", "");

        TextView netHead = new TextView(this);
        netHead.setText(netStatus.headline);
        netHead.setTextColor(netStatus.validated ? theme.statusOkColor : theme.textPrimary);
        netHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        netHead.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        netCard.addView(netHead);

        TextView netSub = new TextView(this);
        netSub.setText(netStatus.details);
        netSub.setTextColor(theme.textSecondary);
        netSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams nsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        nsLp.topMargin = dp(2);
        netCard.addView(netSub, nsLp);

        TextView netDnsStats = new TextView(this);
        String statsLine = (networkHelper != null) ? networkHelper.formatStatsLine() : "";
        netDnsStats.setText("Частный DNS: " + netStatus.dnsSummary
                + (statsLine.isEmpty() ? "" : "\n" + statsLine));
        netDnsStats.setTextColor(theme.textHint);
        netDnsStats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        LinearLayout.LayoutParams ndLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        ndLp.topMargin = dp(4);
        netCard.addView(netDnsStats, ndLp);

        if (networkHelper != null && networkHelper.getHealCount() > 0) {
            List<NetworkQualityHelper.HealEvent> evs = networkHelper.getRecentEvents();
            if (!evs.isEmpty()) {
                LinearLayout logWrap = new LinearLayout(this);
                logWrap.setOrientation(LinearLayout.VERTICAL);
                logWrap.setPadding(dp(10), dp(8), dp(10), dp(8));
                logWrap.setBackground(GlassTheme.makeGlassRect(
                        this, 0x16FFFFFF, theme.cardBorderColor, 12f, 1f
                ));
                LinearLayout.LayoutParams lwLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lwLp.topMargin = dp(8);

                LinearLayout logHdr = new LinearLayout(this);
                logHdr.setOrientation(LinearLayout.HORIZONTAL);
                logHdr.setGravity(Gravity.CENTER_VERTICAL);
                TextView lhTitle = new TextView(this);
                lhTitle.setText("Журнал перепроверок (тихий режим)");
                lhTitle.setTextColor(theme.textSecondary);
                lhTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                lhTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                logHdr.addView(lhTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView resetLogBtn = new TextView(this);
                resetLogBtn.setText("Сбросить");
                resetLogBtn.setTextColor(theme.accentColor);
                resetLogBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                resetLogBtn.setPadding(dp(6), dp(2), dp(2), dp(2));
                resetLogBtn.setOnClickListener(v -> {
                    networkHelper.clearHealStats();
                    rebuildSettingsContent();
                });
                logHdr.addView(resetLogBtn);
                logWrap.addView(logHdr);

                java.text.SimpleDateFormat evFmt = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                int showLim = Math.min(3, evs.size());
                StringBuilder logText = new StringBuilder();
                for (int i = 0; i < showLim; i++) {
                    NetworkQualityHelper.HealEvent ev = evs.get(i);
                    if (i > 0) logText.append('\n');
                    logText.append(evFmt.format(new java.util.Date(ev.timeMs)))
                            .append(" • ").append(ev.transport)
                            .append(" — ").append(ev.reason);
                }
                TextView logBody = new TextView(this);
                logBody.setText(logText.toString());
                logBody.setTextColor(theme.textHint);
                logBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lbLp.topMargin = dp(4);
                logWrap.addView(logBody, lbLp);
                netCard.addView(logWrap, lwLp);
            }
        }

        addDivider(netCard);
        boolean autoNetOn = prefs.isAutoNetworkAssist();
        int autoNetMode = prefs.getAutoNetworkMode();
        addToggleRow(netCard, "Авто-улучшение сети и качества связи",
                autoNetOn
                        ? "Вкл • " + NetworkQualityHelper.modeShortLabel(autoNetMode) + " • Тихо без всплывашек"
                        : "Выкл • Автоматическая перепроверка сети остановлена",
                autoNetOn,
                checked -> {
                    prefs.setAutoNetworkAssist(checked);
                    restartNetworkMonitor();
                    rebuildSettingsContent();
                });

        if (autoNetOn) {
            addDivider(netCard);
            addOptionGroup(
                    netCard,
                    "Режим авто-улучшения",
                    new String[]{"Мягкий (30с)", "Сбаланс. (15с)", "Активный (10с)"},
                    autoNetMode,
                    idx -> {
                        prefs.setAutoNetworkMode(idx);
                        restartNetworkMonitor();
                        rebuildSettingsContent();
                    }
            );
            TextView modeHint = new TextView(this);
            modeHint.setText(NetworkQualityHelper.modeDescription(autoNetMode));
            modeHint.setTextColor(theme.textHint);
            modeHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            LinearLayout.LayoutParams mhLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            mhLp.topMargin = dp(6);
            netCard.addView(modeHint, mhLp);

            if (autoNetMode != Prefs.NET_MODE_GENTLE) {
                addDivider(netCard);
                addToggleRow(netCard, "Контроль слабого Wi-Fi",
                        "При слабом сигнале Wi-Fi запрашивать перепроверку канала для быстрого перехода на 4G",
                        prefs.isAutoNetworkWeakWifi(),
                        checked -> {
                            prefs.setAutoNetworkWeakWifi(checked);
                            restartNetworkMonitor();
                            rebuildSettingsContent();
                        });
            }
        }

        addDivider(netCard);
        addActionRow(netCard, "Перепроверить канал сейчас",
                "Мгновенный системный сброс залипания и обновление диагностики",
                () -> {
                    boolean ok = (networkHelper != null) && networkHelper.triggerSystemReprobe("Ручное обновление");
                    Toast.makeText(this,
                            ok ? "Перепроверка канала выполнена" : "Нет активного подключения",
                            Toast.LENGTH_SHORT).show();
                    rebuildSettingsContent();
                });

        addDivider(netCard);
        addActionRow(netCard, "Быстрая панель интернета (Wi-Fi / 4G)",
                "Мгновенное переключение Wi-Fi и мобильных данных Android",
                () -> NetworkQualityHelper.openInternetQuickPanel(this));

        addDivider(netCard);
        addActionRow(netCard, "Быстрый Частный DNS (ускорение отклика)",
                "Сейчас: " + netStatus.dnsSummary + " • 1-тап копирование DNS",
                this::showPrivateDnsModal);

        addDivider(netCard);
        addActionRow(netCard, "Параметры мобильной сети (4G / SIM / VoLTE)",
                "Выбор предпочтительного типа сети и SIM для интернета",
                () -> NetworkQualityHelper.openMobileNetworkSettings(this));

        addDivider(netCard);
        addActionRow(netCard, "Плитка «Сеть+» в Шторке",
                "Добавить или убрать плитку обновления канала в 1 касание",
                () -> showShadeTileEditorModal(false));

        addDivider(netCard);
        addActionRow(netCard, "Что делает авто-улучшение и как работает?",
                "Честное объяснение всех режимов (0 INTERNET, без расхода батареи)",
                this::showAutoNetworkInfoModal);

        settingsContent.addView(netCard);

        // Уведомления и приватное пространство
        addSectionTitle("Уведомления и приватность");
        LinearLayout privCard = createGlassSectionCard();

        addToggleRow(privCard, "Белая точка уведомлений на иконках",
                "Тонкий белый индикатор на иконках и папках (без счётчиков и чтения текстов)",
                prefs.isShowNotificationDots(),
                checked -> {
                    prefs.setShowNotificationDots(checked);
                    rebuildDesktop();
                    rebuildSettingsContent();
                });

        addDivider(privCard);
        boolean notifGranted = DoxaizNotificationService.isPermissionGranted(this);
        addActionRow(privCard,
                notifGranted ? "✓ Доступ к уведомлениям включён" : "Включить доступ к уведомлениям",
                notifGranted
                        ? "Бейджи, шторка уведомлений, плеер и активные службы (100% локально)"
                        : "Откройте «Доступ к уведомлениям» → выберите Doxaiz",
                () -> DoxaizNotificationService.openSettings(this));

        addDivider(privCard);
        addToggleRow(privCard, "Защитить скрытые отпечатком / PIN",
                BiometricHelper.describeAuthMethod(this),
                prefs.isProtectHiddenApps(),
                checked -> {
                    if (checked && !BiometricHelper.isDeviceSecure(this)) {
                        Toast.makeText(this, "Сначала включите блокировку экрана (PIN / отпечаток) в Android",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    // Не позволяем постороннему просто выключить защиту скрытых без отпечатка / PIN
                    if (!checked && prefs.isProtectHiddenApps()) {
                        if (!BiometricHelper.isDeviceSecure(this)) {
                            Toast.makeText(this, "Для отключения защиты сначала установите PIN или пароль экрана", Toast.LENGTH_LONG).show();
                            return;
                        }
                        pendingPrivateSpaceAction = () -> {
                            prefs.setProtectHiddenApps(false);
                            rebuildSettingsContent();
                        };
                        BiometricHelper.authenticate(
                                this,
                                "Отключение защиты",
                                "Подтвердите отпечатком пальца или PIN-кодом",
                                () -> {
                                    pendingPrivateSpaceAction = null;
                                    prefs.setProtectHiddenApps(false);
                                    rebuildSettingsContent();
                                },
                                () -> {
                                    pendingPrivateSpaceAction = null;
                                    rebuildSettingsContent();
                                }
                        );
                        return;
                    }
                    prefs.setProtectHiddenApps(checked);
                    rebuildSettingsContent();
                });

        addDivider(privCard);
        addActionRow(privCard, "Открыть скрытые приложения",
                hiddenApps.isEmpty()
                        ? "Скрытых пока нет — можно добавить внутри"
                        : ("Скрыто: " + hiddenApps.size() + " • запуск прямо из окна"),
                this::openPrivateSpaceGuarded);

        settingsContent.addView(privCard);

        // Сохранность раскладки и перенос
        addSectionTitle("Сохранность раскладки и перенос");
        LinearLayout layoutSafeCard = createGlassSectionCard();

        addToggleRow(layoutSafeCard, "Зафиксировать месторасположение",
                "Иконки, папки и док строго на своих местах — сдвиг отключён",
                prefs.isDesktopLocked(),
                checked -> {
                    prefs.setDesktopLocked(checked);
                    rebuildSettingsContent();
                });

        addDivider(layoutSafeCard);
        addActionRow(layoutSafeCard, "Импортировать со штатного экрана",
                "Попробовать перенести текущие места иконок со штатного экрана",
                this::runSystemLayoutImport);

        addDivider(layoutSafeCard);
        addActionRow(layoutSafeCard, "Сохранить раскладку в файл (.json)",
                "JSON без шифрования: содержит раскладку и список скрытых приложений. Храните файл в безопасном месте.",
                this::startExportBackup);

        addDivider(layoutSafeCard);
        addActionRow(layoutSafeCard, "Восстановить раскладку из файла",
                "Вернуть сохранённые места иконок 1-в-1",
                this::startImportBackup);

        settingsContent.addView(layoutSafeCard);

        // 4. Жесты рабочего стола
        addSectionTitle("Жесты рабочего стола");
        LinearLayout gestureCard = createGlassSectionCard();

        addOptionGroup(gestureCard, "Свайп вверх на рабочем столе",
                new String[]{"Все прил.", "Окна", "Шторка", "Выкл"},
                prefs.getSwipeUpAction(),
                index -> {
                    prefs.setSwipeUpAction(index);
                    rebuildSettingsContent();
                });

        addDivider(gestureCard);
        final int[] swipeDownMap = new int[]{
                Prefs.GESTURE_NOTIFICATIONS, Prefs.GESTURE_SEARCH, Prefs.GESTURE_NETWORK_PANEL, Prefs.GESTURE_NONE
        };
        int curSwipeIdx = 0;
        for (int i = 0; i < swipeDownMap.length; i++) {
            if (swipeDownMap[i] == prefs.getSwipeDownAction()) { curSwipeIdx = i; break; }
        }
        addOptionGroup(gestureCard, "Свайп вниз на рабочем столе",
                new String[]{"Шторка", "Все прил.", "Сеть", "Выкл"},
                curSwipeIdx,
                index -> {
                    prefs.setSwipeDownAction(swipeDownMap[index]);
                    rebuildSettingsContent();
                });

        addDivider(gestureCard);
        final int[] doubleTapMap = new int[]{
                Prefs.GESTURE_ACTIVE_WINDOWS, Prefs.GESTURE_LOCK_SCREEN, Prefs.GESTURE_HIDDEN_SPACE, Prefs.GESTURE_NONE
        };
        int curDtapIdx = 0;
        for (int i = 0; i < doubleTapMap.length; i++) {
            if (doubleTapMap[i] == prefs.getDoubleTapAction()) { curDtapIdx = i; break; }
        }
        addOptionGroup(gestureCard, "Двойной тап по пустому месту",
                new String[]{"Окна", "Экран", "Скрытые", "Выкл"},
                curDtapIdx,
                index -> {
                    prefs.setDoubleTapAction(doubleTapMap[index]);
                    rebuildSettingsContent();
                });

        if (prefs.getDoubleTapAction() == Prefs.GESTURE_LOCK_SCREEN) {
            addDivider(gestureCard);
            boolean lockReady = DoxaizLockService.isRunning();
            addActionRow(gestureCard,
                    lockReady ? "✓ Служба блокировки экрана активна" : "Включить блокировку экрана",
                    lockReady
                            ? "Двойной тап выключает экран с сохранением отпечатка"
                            : "Откройте Спец. возможности → Doxaiz — Панель ОЗУ и блокировка",
                    this::openAccessibilitySettings);
        }

        settingsContent.addView(gestureCard);

        // 5. Страницы и приложения
        addSectionTitle("Страницы и приложения");
        LinearLayout appsCard = createGlassSectionCard();

        addToggleRow(appsCard, "Новые приложения на рабочий стол",
                "Автоматически добавлять значок на последнюю страницу",
                prefs.isAutoAddNewApps(),
                checked -> {
                    prefs.setAutoAddNewApps(checked);
                    rebuildSettingsContent();
                });

        addDivider(appsCard);
        addActionRow(appsCard, "Разложить все приложения по страницам",
                "Заполнит страницы всеми установленными программами",
                () -> showConfirmModal(
                        "Разложить все приложения?",
                        "Все видимые приложения будут распределены по страницам рабочего стола.",
                        () -> {
                            desktop.spreadAllAppsAcrossPages(visibleApps, prefs.getColumns());
                            currentPage = 0;
                            rebuildDesktop();
                            Toast.makeText(this, "Готово: страниц " + desktop.pages.size(), Toast.LENGTH_SHORT).show();
                        }
                ));

        addDivider(appsCard);
        addActionRow(appsCard, "Сбросить страницы и папки",
                "Вернуть стартовую раскладку (2 страницы + папка)",
                () -> showConfirmModal(
                        "Сбросить рабочий стол?",
                        "Страницы, папки и док вернутся к начальному состоянию.",
                        () -> {
                            desktop.buildInitialLayout(visibleApps, prefs.getColumns(), prefs.isHomeBottomAligned());
                            currentPage = 0;
                            rebuildDesktop();
                            Toast.makeText(this, "Раскладка сброшена", Toast.LENGTH_SHORT).show();
                        }
                ));

        addDivider(appsCard);
        addActionRow(appsCard, "Скрытые приложения",
                hiddenApps.isEmpty() ? "Нет скрытых" : ("Скрыто: " + hiddenApps.size()),
                this::openPrivateSpaceGuarded);

        addDivider(appsCard);
        addActionRow(appsCard, "Сбросить переименования",
                "Вернуть исходные названия программ",
                () -> showConfirmModal(
                        "Сбросить названия?",
                        "Все пользовательские названия приложений будут удалены.",
                        () -> {
                            prefs.clearAllCustomLabels();
                            repository.reload();
                            Toast.makeText(this, "Названия сброшены", Toast.LENGTH_SHORT).show();
                        }
                ));

        settingsContent.addView(appsCard);

        // 6. Система
        addSectionTitle("Система и быстродействие");
        LinearLayout sysCard = createGlassSectionCard();
        MemoryHelper.Snapshot sysMem = MemoryHelper.read(this);
        addActionRow(sysCard, "Быстрая очистка ОЗУ",
                "Освободить фоновые кэш-процессы • " + sysMem.ramShortText,
                this::runQuickRamBoost);
        addDivider(sysCard);
        long[] diskStats = repository.getDiskCacheStats();
        String cacheSub = "Диск: " + diskStats[0] + " иконок (" + IconDiskCache.formatSize(diskStats[1])
                + ") • ОЗУ: " + repository.getRamCachedIconCount() + " иконок, "
                + VectorGlyphs.getCachedGlyphCount() + " значков";
        addActionRow(sysCard, "Обновить кэш иконок и значков", cacheSub, () -> {
            repository.clearAllCachesAndReload();
            Toast.makeText(this, "Кэш иконок и значков очищен — пересборка…", Toast.LENGTH_SHORT).show();
            rebuildSettingsContent();
        });
        addDivider(sysCard);
        addActionRow(sysCard, "Память и хранилище телефона",
                sysMem.oneLineSummary,
                this::openStorageSettings);
        addDivider(sysCard);
        addActionRow(sysCard, "Сменить обои", "Системный выбор обоев", this::openWallpaperChooser);
        addDivider(sysCard);
        addActionRow(sysCard, "Настройки телефона", "Системные параметры Android", this::openSystemSettings);
        settingsContent.addView(sysCard);

        TextView footer = new TextView(this);
        footer.setText("Doxaiz 5.1 • Глобальный аудит безопасности и оптимизации • Без лишних разрешений");
        footer.setTextColor(theme.textHint);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        fLp.topMargin = dp(14);
        fLp.bottomMargin = dp(20);
        footer.setLayoutParams(fLp);
        footer.setTag("settings_footer");
        settingsContent.addView(footer);

        applySettingsSearchFilter();
    }

    private View buildSystemReadinessCard() {
        boolean okHome = isDefaultLauncher();
        boolean okNotif = DoxaizNotificationService.isPermissionGranted(this);
        boolean okA11y = DoxaizLockService.isRunning();
        boolean okWrite = false;
        try {
            okWrite = Settings.System.canWrite(this);
        } catch (Throwable ignored) {}

        int readyCount = (okHome ? 1 : 0) + (okNotif ? 1 : 0) + (okA11y ? 1 : 0) + (okWrite ? 1 : 0);

        LinearLayout card = createGlassSectionCard();
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);

        ImageView ico = VectorGlyphs.makeIconView(this, "check", 17,
                readyCount == 4 ? 0xFF22C55E : 0xFF60A5FA);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(17), dp(17));
        iLp.rightMargin = dp(8);
        hdr.addView(ico, iLp);

        TextView title = new TextView(this);
        title.setText("Готовность системы");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hdr.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText(readyCount + " / 4 готово");
        badge.setTextColor(readyCount == 4 ? 0xFF4ADE80 : 0xFF93C5FD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setPadding(dp(9), dp(4), dp(9), dp(4));
        badge.setBackground(GlassTheme.makeGlassRect(
                this,
                readyCount == 4 ? 0x2622C55E : 0x262B8BFF,
                Color.TRANSPARENT, 10f, 0f
        ));
        hdr.addView(badge);
        card.addView(hdr);

        addDivider(card);
        addReadinessRow(card, "home", "Главный экран", "Оболочка Doxaiz по умолчанию",
                okHome, this::openDefaultHomeSettings);
        addDivider(card);
        addReadinessRow(card, "bell", "Уведомления и ответы", "Шторка, быстрый ответ, плеер, бейджи",
                okNotif, () -> {
                    DoxaizNotificationService.ensureBoundIfPermitted(this);
                    DoxaizNotificationService.openSettings(this);
                });
        addDivider(card);
        addReadinessRow(card, "bolt", "Служба жестов и ОЗУ", "ОЗУ в недавних, блокировка и снимок",
                okA11y, this::openAccessibilitySettings);
        addDivider(card);
        addReadinessRow(card, "sun", "Яркость и сон экрана", "Ползунок яркости, автояркость и тайм-аут",
                okWrite, () -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Throwable e) {
                        startSafeSystemIntent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    }
                });
        return card;
    }

    private void addReadinessRow(LinearLayout parent, String glyph, String title, String sub,
                                 boolean ok, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        FrameLayout icWrap = new FrameLayout(this);
        icWrap.setBackground(GlassTheme.makeGlassRect(
                this, ok ? 0x2222C55E : 0xFF28282E, Color.TRANSPARENT, 10f, 0f
        ));
        ImageView iv = VectorGlyphs.makeIconView(this, glyph, 16, ok ? 0xFF4ADE80 : 0xFF9EA0A6);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(dp(16), dp(16));
        ivLp.gravity = Gravity.CENTER;
        icWrap.addView(iv, ivLp);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        wLp.rightMargin = dp(10);
        row.addView(icWrap, wLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(theme.textPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        texts.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(theme.textSecondary);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f);
        texts.addView(s);
        row.addView(texts);

        TextView btn = new TextView(this);
        btn.setText(ok ? "Готово" : "Включить");
        btn.setTextColor(ok ? 0xFF4ADE80 : Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setPadding(dp(10), dp(6), dp(10), dp(6));
        btn.setBackground(GlassTheme.makeGlassRect(
                this,
                ok ? 0x2022C55E : 0xFF2B8BFF,
                Color.TRANSPARENT, 10f, 0f
        ));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bLp.leftMargin = dp(8);
        row.addView(btn, bLp);

        row.setOnClickListener(v -> {
            if (prefs.isHapticFeedbackEnabled()) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            onClick.run();
        });
        parent.addView(row);
    }

    private void applySettingsSearchFilter() {
        if (settingsContent == null) return;
        // Убираем предыдущее сообщение «Ничего не найдено», если было
        View oldEmpty = settingsContent.findViewWithTag("settings_search_empty");
        if (oldEmpty != null) settingsContent.removeView(oldEmpty);

        String q = settingsSearchQuery != null ? settingsSearchQuery.trim().toLowerCase(Locale.ROOT) : "";
        int count = settingsContent.getChildCount();
        if (q.isEmpty()) {
            for (int i = 0; i < count; i++) {
                settingsContent.getChildAt(i).setVisibility(View.VISIBLE);
            }
            return;
        }

        int matchedSections = 0;
        for (int i = 0; i < count; i++) {
            View child = settingsContent.getChildAt(i);
            Object tag = child.getTag();
            if ("settings_footer".equals(tag)) {
                child.setVisibility(View.GONE);
                continue;
            }
            if ("section_header".equals(tag)) {
                View card = (i + 1 < count) ? settingsContent.getChildAt(i + 1) : null;
                StringBuilder sb = new StringBuilder();
                collectVisibleText(child, sb);
                if (card != null) collectVisibleText(card, sb);
                boolean hit = sb.toString().toLowerCase(Locale.ROOT).contains(q);
                child.setVisibility(hit ? View.VISIBLE : View.GONE);
                if (card != null) {
                    card.setVisibility(hit ? View.VISIBLE : View.GONE);
                    i++;
                }
                if (hit) matchedSections++;
            } else {
                StringBuilder sb = new StringBuilder();
                collectVisibleText(child, sb);
                boolean hit = sb.toString().toLowerCase(Locale.ROOT).contains(q);
                child.setVisibility(hit ? View.VISIBLE : View.GONE);
                if (hit) matchedSections++;
            }
        }

        if (matchedSections == 0) {
            TextView empty = new TextView(this);
            empty.setTag("settings_search_empty");
            empty.setText("По запросу «" + settingsSearchQuery + "» настройки не найдены");
            empty.setTextColor(theme.textSecondary);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(28), dp(16), dp(28));
            settingsContent.addView(empty);
        }
    }

    private void collectVisibleText(View v, StringBuilder out) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null) out.append(' ').append(cs);
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectVisibleText(vg.getChildAt(i), out);
            }
        }
    }

    private String resolveSettingsSectionGlyph(String text) {
        String l = text != null ? text.toLowerCase(Locale.ROOT) : "";
        if (l.contains("внешний")) return "grid";
        if (l.contains("шторка") || l.contains("звук")) return "eq";
        if (l.contains("озу") || l.contains("недавн")) return "bolt";
        if (l.contains("сеть") || l.contains("связ")) return "wifi";
        if (l.contains("уведомл") || l.contains("приват")) return "bell";
        if (l.contains("раскладк") || l.contains("перенос")) return "backup";
        if (l.contains("жест")) return "gesture";
        if (l.contains("поиск") || l.contains("меню")) return "search";
        if (l.contains("страниц")) return "pages";
        return "settings";
    }

    private void addSectionTitle(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setTag("section_header");
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.leftMargin = dp(6);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        ImageView iv = VectorGlyphs.makeIconView(this, resolveSettingsSectionGlyph(text), 14, theme.sectionIconColor);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        iLp.rightMargin = dp(7);
        row.addView(iv, iLp);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(theme.textSecondary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(tv);

        settingsContent.addView(row);
    }

    private LinearLayout createGlassSectionCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(GlassTheme.makeStyledCard(
                this, theme.cardBgColor, theme.cardBorderColor, 20f, 1f, true
        ));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(6);
        card.setLayoutParams(lp);
        return card;
    }

    private void addDivider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(theme.dividerColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
        );
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        parent.addView(d, lp);
    }

    private interface IntConsumer { void accept(int value); }
    private interface BoolConsumer { void accept(boolean value); }

    private void addOptionGroup(LinearLayout parent, String title, String[] labels,
                                int selectedIndex, final IntConsumer onSelect) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(theme.textPrimary);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        parent.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowLp.topMargin = dp(9);
        row.setLayoutParams(rowLp);

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean active = (i == selectedIndex);
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, labels.length >= 5 ? 11.2f : (labels.length >= 4 ? 12f : 13f));
            chip.setTypeface(Typeface.create(active ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            chip.setPadding(dp(labels.length >= 5 ? 3 : 6), dp(9), dp(labels.length >= 5 ? 3 : 6), dp(9));
            chip.setTextColor(active ? theme.chipActiveText : theme.textSecondary);
            chip.setBackground(GlassTheme.makeGlassRect(
                    this,
                    active ? theme.chipActiveBg : theme.chipInactiveBg,
                    active ? theme.chipActiveBorder : Color.TRANSPARENT,
                    12f, active ? 1f : 0f
            ));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            if (i > 0) cLp.leftMargin = dp(labels.length >= 5 ? 4 : 6);
            chip.setLayoutParams(cLp);
            chip.setOnClickListener(v -> onSelect.accept(idx));
            row.addView(chip);
        }
        parent.addView(row);
    }

    private void addToggleRow(LinearLayout parent, String title, String subtitle,
                              final boolean checked, final BoolConsumer onToggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOnClickListener(v -> onToggle.accept(!checked));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(theme.textPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        texts.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(theme.textSecondary);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            texts.addView(s);
        }

        FrameLayout track = new FrameLayout(this);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(dp(44), dp(26));
        trLp.leftMargin = dp(12);
        track.setLayoutParams(trLp);
        track.setPadding(dp(3), dp(3), dp(3), dp(3));
        track.setBackground(GlassTheme.makeGlassRect(
                this,
                checked ? theme.switchTrackOn : theme.switchTrackOff,
                Color.TRANSPARENT, 13f, 0f
        ));

        View thumb = new View(this);
        thumb.setBackground(GlassTheme.makeGlassRect(
                this,
                checked ? theme.switchThumb : Color.WHITE,
                Color.TRANSPARENT, 10f, 0f
        ));
        FrameLayout.LayoutParams thLp = new FrameLayout.LayoutParams(dp(20), dp(20));
        thLp.gravity = checked ? (Gravity.END | Gravity.CENTER_VERTICAL) : (Gravity.START | Gravity.CENTER_VERTICAL);
        track.addView(thumb, thLp);

        row.addView(texts);
        row.addView(track);
        parent.addView(row);
    }

    private void addActionRow(LinearLayout parent, String title, String subtitle, final Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOnClickListener(v -> onClick.run());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(theme.textPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        texts.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(theme.textSecondary);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            texts.addView(s);
        }

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        tintIcon(chev, theme.textHint);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        cLp.leftMargin = dp(8);
        row.addView(texts);
        row.addView(chev, cLp);
        parent.addView(row);
    }

    // =========================================================================
    // Системные переходы и хелперы
    // =========================================================================

    private boolean isDefaultLauncher() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null
                    && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void openDefaultHomeSettings() {
        // На Android 10–14 сначала предлагаем нативный системный диалог выбора главного экрана в 1 тап (RoleManager)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                android.app.role.RoleManager rm = (android.app.role.RoleManager)
                        getSystemService(Context.ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)
                        && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                    Intent roleIntent = rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME);
                    startActivityForResult(roleIntent, REQ_ROLE_HOME);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        String[] actions = new String[]{
                Settings.ACTION_HOME_SETTINGS,
                Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                Settings.ACTION_SETTINGS
        };
        for (String action : actions) {
            try {
                Intent intent = new Intent(action);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private void openWallpaperChooser() {
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Выбрать обои"));
        } catch (Throwable ignored) {
            Toast.makeText(this, "Откройте выбор обоев в настройках телефона", Toast.LENGTH_SHORT).show();
        }
    }

    private void openSystemSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    private void openStorageSettings() {
        String[] actions = new String[]{
                Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
                Settings.ACTION_MEMORY_CARD_SETTINGS,
                Settings.ACTION_SETTINGS
        };
        for (String action : actions) {
            try {
                startActivity(new Intent(action));
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private String getNotifBadgeModeLabel(int mode) {
        switch (mode) {
            case Prefs.NOTIF_BADGE_APP_ICONS: return "Значки приложений";
            case Prefs.NOTIF_BADGE_NUMBERS:   return "Цифры";
            case Prefs.NOTIF_BADGE_NONE:      return "Отсутствуют";
            case Prefs.NOTIF_BADGE_DOT:
            default:                          return "Значок уведомления";
        }
    }

    private void showNotifStatusSettingsModal() {
        LinearLayout box = createModalBox();
        box.setBackground(GlassTheme.makeRoundedRect(0xFF1F1F21, dp(24)));

        // Верхняя ручка bottom-sheet
        View handle = new View(this);
        handle.setBackground(GlassTheme.makeRoundedRect(0x55FFFFFF, dp(2)));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(38), dp(4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = dp(12);
        box.addView(handle, hLp);

        TextView title = new TextView(this);
        title.setText("Уведомления и строка состояния");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Способ оповещения на иконках рабочего стола и строка состояния");
        sub.setTextColor(GlassTheme.TEXT_SECONDARY);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        sub.setPadding(0, dp(3), 0, dp(12));
        box.addView(sub);

        final String[] modes = {
                "Значок уведомления",
                "Значки приложений",
                "Цифры",
                "Отсутствуют"
        };
        int currentMode = prefs.getNotifBadgeMode();

        for (int i = 0; i < modes.length; i++) {
            final int modeVal = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(12), dp(4), dp(12));

            TextView lbl = new TextView(this);
            lbl.setText(modes[i]);
            lbl.setTextColor(Color.WHITE);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView radio = new TextView(this);
            boolean sel = (modeVal == currentMode);
            radio.setText(sel ? "●" : "○");
            radio.setTextColor(sel ? GlassTheme.ACCENT_GREEN : GlassTheme.TEXT_SECONDARY);
            radio.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            row.addView(radio);

            row.setOnClickListener(v -> {
                prefs.setNotifBadgeMode(modeVal);
                rebuildDesktop();
                if (currentScreen == SCREEN_SETTINGS) rebuildSettingsContent();
                showNotifStatusSettingsModal();
            });
            box.addView(row);
        }

        View div = new View(this);
        div.setBackgroundColor(GlassTheme.DIVIDER);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dLp.setMargins(0, dp(6), 0, dp(6));
        box.addView(div, dLp);

        addToggleRow(box, "Показывать процент батареи",
                "Отображать процент заряда в Dynamic Bar и строке статуса",
                prefs.isShowBatteryPct(),
                v -> {
                    prefs.setShowBatteryPct(v);
                    if (dynamicBarHelper != null) dynamicBarHelper.refresh();
                });

        addToggleRow(box, "Показывать скорость сети",
                "Живая скорость приёма и отдачи (КБ/с, МБ/с) в шторке без счётчика расхода трафика",
                prefs.isShowNetSpeed(),
                v -> prefs.setShowNetSpeed(v));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(8), 0, 0);
        addDialogButton(btns, "Доступ к уведомлениям", GlassTheme.TEXT_SECONDARY, () -> {
            dismissModal();
            DoxaizNotificationService.openSettings(this);
        });
        addDialogButton(btns, "Готово", GlassTheme.ACCENT_GREEN, this::dismissModal);
        box.addView(btns);

        showModalCard(box, Gravity.BOTTOM, false);
    }

    private static final class ActiveWindowTab {
        final String packageName;
        final String appLabel;
        final String statusText;
        final List<String[]> realLines;
        final boolean isBackgroundService;
        final AppEntry entry;

        ActiveWindowTab(String packageName, String appLabel, String statusText,
                        List<String[]> realLines, boolean isBackgroundService, AppEntry entry) {
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.statusText = statusText;
            this.realLines = realLines;
            this.isBackgroundService = isBackgroundService;
            this.entry = entry;
        }
    }

    /**
     * Экран работающих приложений в виде окон с реальным живым содержимым (Doxaiz 3.7):
     * - БЕЗ кнопок «Открыть» / «Закрыть»: тап по окну открывает приложение,
     *   зажатие и свайп окна вверх — закрывает и останавливает его;
     * - ОЗУ перенесено ВНИЗ и красиво оформлено прямо над круглой кнопкой-мусоркой;
     * - нажатие на мусорку каскадно закрывает ВСЕ работающие окна и останавливает фоновые процессы.
     */
    private void showBackgroundAppsModal() {
        final FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackground(GlassTheme.makeGlassRect(
                this, 0xF5101116, 0x2BFFFFFF, 28f, 1f
        ));
        rootFrame.setClipToOutline(true);
        rootFrame.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(16), dp(14), dp(16));

        final List<ActiveWindowTab> tabs = collectActiveWindowTabs();

        // Чистая верхняя строка: заголовок со счётчиком активных окон + кнопка закрытия
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(this);
        title.setText(tabs.isEmpty() ? "Активные приложения" : ("Активные приложения • " + tabs.size()));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hdr.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (DoxaizLockService.isRunning()) {
            TextView sysBtn = new TextView(this);
            sysBtn.setText("Системные окна");
            sysBtn.setTextColor(0xFF93C5FD);
            sysBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            sysBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            sysBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
            sysBtn.setBackground(GlassTheme.makeRoundedRect(0x262B8BFF, dp(12)));
            sysBtn.setOnClickListener(v -> {
                dismissModal();
                DoxaizLockService.openSystemRecents();
            });
            LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            sbLp.rightMargin = dp(8);
            hdr.addView(sysBtn, sbLp);
        }
        hdr.addView(makeVectorCircleBtn("close", "Закрыть", this::dismissModal),
                new LinearLayout.LayoutParams(dp(32), dp(32)));
        box.addView(hdr);

        final LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(4), dp(12), dp(4), dp(10));
        // Плавное смыкание соседних карточек окон после закрытия свайпом вверх (Doxaiz 4.4)
        android.animation.LayoutTransition stripTrans = new android.animation.LayoutTransition();
        stripTrans.setDuration(180L);
        stripTrans.disableTransitionType(android.animation.LayoutTransition.APPEARING);
        stripTrans.disableTransitionType(android.animation.LayoutTransition.DISAPPEARING);
        strip.setLayoutTransition(stripTrans);

        // Нижняя капсула ОЗУ (объявляем заранее, чтобы обновлять при закрытии окон и нажатии мусорки)
        MemoryHelper.Snapshot mem = MemoryHelper.read(this);
        final TextView ramPillText = new TextView(this);
        ramPillText.setText(mem.recentsHeadline);
        ramPillText.setTextColor(0xF0FFFFFF);
        ramPillText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        ramPillText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ramPillText.setSingleLine(true);

        if (tabs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Все приложения закрыты — память чиста");
            empty.setTextColor(GlassTheme.TEXT_SECONDARY);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(52), dp(12), dp(52));
            box.addView(empty);
        } else {
            TextView hint = new TextView(this);
            hint.setText("Нажмите на окно, чтобы открыть • потяните вверх, чтобы закрыть");
            hint.setTextColor(GlassTheme.TEXT_SECONDARY);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            hint.setPadding(0, dp(4), 0, dp(4));
            box.addView(hint);

            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);

            for (final ActiveWindowTab tab : tabs) {
                View card = buildActiveWindowCard(tab, strip, ramPillText, title);
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(218), dp(330));
                cLp.rightMargin = dp(12);
                strip.addView(card, cLp);
            }
            hsv.addView(strip);
            box.addView(hsv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        // Низ по центру (фиксированная высота — ничего не сдвигается при раскрытии плашки батареи!):
        // 1) Маленькая свёрнутая плашка температуры и батареи (раскрывается поверх по нажатию)
        // 2) Красивая капсула ОЗУ прямо НАД корзиной
        // 3) Круглая кнопка-мусорка 58dp
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.VERTICAL);
        bottomRow.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomRow.setPadding(0, dp(8), 0, dp(2));

        final BatteryInfoHelper.Snapshot batSnap = BatteryInfoHelper.read(this);

        LinearLayout batPill = new LinearLayout(this);
        batPill.setOrientation(LinearLayout.HORIZONTAL);
        batPill.setGravity(Gravity.CENTER_VERTICAL);
        batPill.setBackground(GlassTheme.makeGlassRect(
                this, 0xCC1C1E26, 0x30FFFFFF, 16f, 1f
        ));
        batPill.setPadding(dp(12), dp(5), dp(11), dp(5));

        ImageView batIco = VectorGlyphs.makeIconView(
                this, batSnap.isCharging ? "bolt" : "battery", 13, batSnap.tempColor
        );
        LinearLayout.LayoutParams biLp = new LinearLayout.LayoutParams(dp(13), dp(13));
        biLp.rightMargin = dp(6);
        batPill.addView(batIco, biLp);

        TextView batPillTv = new TextView(this);
        batPillTv.setText(batSnap.pillSummary.isEmpty() ? "Батарея • датчики" : batSnap.pillSummary);
        batPillTv.setTextColor(0xF0FFFFFF);
        batPillTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.8f);
        batPillTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        batPillTv.setSingleLine(true);
        batPill.addView(batPillTv);

        final ImageView batChev = VectorGlyphs.makeIconView(this, "chevron_down", 13, GlassTheme.TEXT_SECONDARY);
        LinearLayout.LayoutParams bcLp = new LinearLayout.LayoutParams(dp(13), dp(13));
        bcLp.leftMargin = dp(5);
        batPill.addView(batChev, bcLp);

        LinearLayout.LayoutParams bpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bpLp.bottomMargin = dp(7);
        bottomRow.addView(batPill, bpLp);

        LinearLayout ramPill = new LinearLayout(this);
        ramPill.setOrientation(LinearLayout.HORIZONTAL);
        ramPill.setGravity(Gravity.CENTER_VERTICAL);
        ramPill.setBackground(GlassTheme.makeGlassRect(
                this, 0xCC1C1E26, 0x30FFFFFF, 16f, 1f
        ));
        ramPill.setPadding(dp(13), dp(5), dp(13), dp(5));

        View dot = new View(this);
        dot.setBackground(GlassTheme.makeRoundedRect(GlassTheme.ACCENT_GREEN, dp(4)));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dLp.rightMargin = dp(7);
        ramPill.addView(dot, dLp);
        ramPill.addView(ramPillText);

        LinearLayout.LayoutParams rpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rpLp.bottomMargin = dp(11);
        bottomRow.addView(ramPill, rpLp);

        final FrameLayout trashBtn = new FrameLayout(this);
        trashBtn.setContentDescription("Закрыть все приложения и очистить ОЗУ");
        trashBtn.setBackground(GlassTheme.makeGlassRect(
                this, 0xFF242731, 0x4AFFFFFF, 29f, 1f
        ));
        final ImageView trashIv = VectorGlyphs.makeIconView(this, "trash", 24, Color.WHITE);
        FrameLayout.LayoutParams tiLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        tiLp.gravity = Gravity.CENTER;
        trashBtn.addView(trashIv, tiLp);

        // Doxaiz 4.4: независимый overlay-слой для раскрытой карточки батареи (строго поверх, БЕЗ сдвига окон!)
        // Строим детальную карточку лениво при первом нажатии на плашку, чтобы Recents открывались мгновенно
        final FrameLayout batOverlay = new FrameLayout(this);
        batOverlay.setBackgroundColor(0x66000000);
        batOverlay.setVisibility(View.GONE);
        batOverlay.setClickable(true);

        final boolean[] batOpen = new boolean[]{false};
        final Runnable[] setBatOpenRef = new Runnable[1];
        final LinearLayout[] batDetailCardRef = new LinearLayout[1];

        setBatOpenRef[0] = () -> {
            boolean open = batOpen[0];
            batChev.animate().rotation(open ? 180f : 0f).setDuration(140L).start();
            if (open) {
                if (batDetailCardRef[0] == null) {
                    LinearLayout built = buildBatteryDetailCard(batSnap, tabs, () -> {
                        batOpen[0] = false;
                        if (setBatOpenRef[0] != null) setBatOpenRef[0].run();
                    });
                    built.setClickable(true);
                    FrameLayout.LayoutParams bdcLp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
                    );
                    bdcLp.leftMargin = dp(14);
                    bdcLp.rightMargin = dp(14);
                    bdcLp.bottomMargin = dp(144);
                    batOverlay.addView(built, bdcLp);
                    batDetailCardRef[0] = built;
                }
                LinearLayout batDetailCard = batDetailCardRef[0];
                batOverlay.animate().cancel();
                batOverlay.setAlpha(0f);
                batOverlay.setVisibility(View.VISIBLE);
                batDetailCard.setTranslationY(dp(12));
                batDetailCard.setScaleX(0.95f);
                batDetailCard.setScaleY(0.95f);
                batOverlay.animate().alpha(1f).setDuration(140L).withLayer().start();
                batDetailCard.animate()
                        .translationY(0f).scaleX(1f).scaleY(1f)
                        .setDuration(155L)
                        .setInterpolator(SMOOTH_INTERPOLATOR)
                        .withLayer()
                        .start();
            } else if (batOverlay.getVisibility() == View.VISIBLE) {
                batOverlay.animate().cancel();
                batOverlay.animate().alpha(0f).setDuration(125L).withLayer()
                        .withEndAction(() -> batOverlay.setVisibility(View.GONE))
                        .start();
            }
        };

        batPill.setOnClickListener(v -> {
            if (prefs.isHapticFeedbackEnabled()) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            batOpen[0] = !batOpen[0];
            setBatOpenRef[0].run();
        });
        batOverlay.setOnClickListener(v -> {
            batOpen[0] = false;
            setBatOpenRef[0].run();
        });

        final boolean[] trashCleaning = new boolean[]{false};
        trashBtn.setOnClickListener(v -> {
            if (trashCleaning[0]) return;
            trashCleaning[0] = true;
            if (batOpen[0]) {
                batOpen[0] = false;
                setBatOpenRef[0].run();
            }
            if (prefs.isHapticFeedbackEnabled()) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
            trashBtn.animate().scaleX(0.84f).scaleY(0.84f).setDuration(90)
                    .withEndAction(() -> trashBtn.animate().scaleX(1f).scaleY(1f).setDuration(140).start())
                    .start();
            trashIv.animate().rotation(-18f).setDuration(90)
                    .withEndAction(() -> trashIv.animate().rotation(0f).setDuration(150).start())
                    .start();

            java.util.Set<String> lockedPkgs = prefs.getLockedRecentsPackages();
            final List<View> toRemove = new ArrayList<>();
            int lockedVisible = 0;
            int animIdx = 0;
            int count = strip.getChildCount();
            for (int i = 0; i < count; i++) {
                final View child = strip.getChildAt(i);
                Object tagObj = child.getTag();
                String pkg = (tagObj instanceof String) ? (String) tagObj : "";
                if (lockedPkgs.contains(pkg)) {
                    lockedVisible++;
                    // Закреплённое замком приложение защищено: мягко подпрыгивает и остаётся!
                    child.animate().scaleX(1.04f).scaleY(1.04f).setDuration(110)
                            .withEndAction(() -> child.animate().scaleX(1f).scaleY(1f).setDuration(140).start())
                            .start();
                } else {
                    toRemove.add(child);
                    child.animate()
                            .translationY(-dp(290))
                            .scaleX(0.90f)
                            .scaleY(0.90f)
                            .alpha(0f)
                            .setStartDelay(animIdx * 45L)
                            .setDuration(180L)
                            .withLayer()
                            .start();
                    animIdx++;
                }
            }
            for (ActiveWindowTab t : tabs) {
                if (!lockedPkgs.contains(t.packageName)) {
                    stopBackgroundPackage(t.packageName);
                }
            }
            DoxaizNotificationService.stopActiveMediaExcept(this, lockedPkgs);
            DoxaizNotificationService.clearAllClearableExcept(lockedPkgs);
            prefs.markAllRecentTabsClearedExcept(lockedPkgs);
            MemoryHelper.BoostResult res = MemoryHelper.boostRam(this, visibleApps, lockedPkgs);
            ramPillText.setText(MemoryHelper.read(this).recentsHeadline);
            refreshDrawerMemory();
            refreshDrawerBgPill();
            if (toRemove.isEmpty() && lockedVisible > 0) {
                Toast.makeText(this, "Все окна защищены замком (" + lockedVisible + ") • ОЗУ освежено", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, res.message, Toast.LENGTH_SHORT).show();
            }

            long finishDelay = Math.min(460L, 210L + animIdx * 45L);
            strip.postDelayed(() -> {
                trashCleaning[0] = false;
                if (isFinishing() || isDestroyed() || rootFrame.getParent() != modalOverlay) return;
                for (View rem : toRemove) {
                    strip.removeView(rem);
                }
                int remCount = strip.getChildCount();
                title.setText(remCount > 0 ? ("Активные приложения • " + remCount) : "Активные приложения");
                if (remCount == 0) {
                    dismissModal();
                }
            }, finishDelay);
        });

        bottomRow.addView(trashBtn, new LinearLayout.LayoutParams(dp(58), dp(58)));
        box.addView(bottomRow);

        rootFrame.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        rootFrame.addView(batOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        showModalCard(rootFrame, Gravity.CENTER, false);
    }

    /**
     * Doxaiz 4.4: Компактная карточка контроля батареи (раскрывается строго ПОВЕРХ в Недавних,
     * без сдвига окон, без перехода в системные настройки и без кнопки «Усыпить»).
     */
    private LinearLayout buildBatteryDetailCard(
            BatteryInfoHelper.Snapshot snap,
            List<ActiveWindowTab> activeTabs,
            Runnable onClose
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GlassTheme.makeGlassRect(this, 0xF51A1C24, 0x38FFFFFF, 20f, 1f));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Батарея и нагрев" + (snap.levelPct >= 0 ? (" • " + snap.levelPct + "%") : ""));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hdr.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout closeBtn = makeVectorCircleBtn("close", "Свернуть", onClose);
        hdr.addView(closeBtn, new LinearLayout.LayoutParams(dp(26), dp(26)));
        card.addView(hdr);

        // Сетка 2×2 с реальными показателями
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1Lp.topMargin = dp(8);
        String tempVal = BatteryInfoHelper.formatTemp(snap.tempC);
        if (tempVal.isEmpty()) tempVal = "—";
        String tempSub = snap.tempC >= 43.0f ? "Горячая"
                : (snap.tempC >= 38.5f ? "Тёплая" : "Норма");
        View t1 = makeBatteryStatTile("Температура", tempVal, tempSub, snap.tempColor);
        LinearLayout.LayoutParams t1Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t1Lp.rightMargin = dp(6);
        row1.addView(t1, t1Lp);

        String pwrVal = (snap.powerWatts >= 0.2f)
                ? BatteryInfoHelper.formatWatts(snap.powerWatts)
                : (snap.isCharging ? "Зарядка" : "От АКБ");
        View t2 = makeBatteryStatTile("Питание", pwrVal, snap.plugLabel,
                snap.isCharging ? 0xFF60A5FA : Color.WHITE);
        row1.addView(t2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row1, r1Lp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = dp(6);
        View t3 = makeBatteryStatTile("Напряжение",
                BatteryInfoHelper.formatVoltage(snap.voltageMv),
                snap.technology, Color.WHITE);
        LinearLayout.LayoutParams t3Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t3Lp.rightMargin = dp(6);
        row2.addView(t3, t3Lp);

        View t4 = makeBatteryStatTile("Состояние АКБ", snap.healthLabel,
                snap.healthGood ? "Датчик в норме" : "Требует внимания",
                snap.healthGood ? GlassTheme.ACCENT_GREEN : 0xFFF59E0B);
        row2.addView(t4, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row2, r2Lp);

        // Информационная строка о фоновых приложениях (только просмотр, без кнопки «Усыпить»)
        StringBuilder bgNames = new StringBuilder();
        if (activeTabs != null && !activeTabs.isEmpty()) {
            int maxShow = Math.min(4, activeTabs.size());
            for (int i = 0; i < maxShow; i++) {
                if (i > 0) bgNames.append(", ");
                bgNames.append(activeTabs.get(i).appLabel);
            }
            if (activeTabs.size() > maxShow) {
                bgNames.append(" и ещё ").append(activeTabs.size() - maxShow);
            }
        }
        TextView bgInfo = new TextView(this);
        bgInfo.setText(bgNames.length() > 0
                ? ("В фоне сейчас: " + bgNames)
                : "Фоновой активности приложений нет");
        bgInfo.setTextColor(GlassTheme.TEXT_SECONDARY);
        bgInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        bgInfo.setSingleLine(true);
        bgInfo.setEllipsize(TextUtils.TruncateAt.END);
        bgInfo.setPadding(dp(2), dp(7), dp(2), 0);
        card.addView(bgInfo);

        return card;
    }

    private View makeBatteryStatTile(String label, String value, String sub, int valueColor) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(GlassTheme.makeRoundedRect(0xFF26272E, dp(12)));
        tile.setPadding(dp(10), dp(7), dp(10), dp(7));

        TextView lTv = new TextView(this);
        lTv.setText(label);
        lTv.setTextColor(GlassTheme.TEXT_SECONDARY);
        lTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        lTv.setSingleLine(true);
        tile.addView(lTv);

        TextView vTv = new TextView(this);
        vTv.setText(value);
        vTv.setTextColor(valueColor);
        vTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        vTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        vTv.setSingleLine(true);
        vTv.setPadding(0, dp(2), 0, 0);
        tile.addView(vTv);

        TextView sTv = new TextView(this);
        sTv.setText(sub);
        sTv.setTextColor(0x99FFFFFF);
        sTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        sTv.setSingleLine(true);
        sTv.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(sTv);

        return tile;
    }

    private List<ActiveWindowTab> collectActiveWindowTabs() {
        List<ActiveWindowTab> out = new ArrayList<>();
        java.util.Set<String> seenPkgs = new java.util.HashSet<>();
        PackageManager pm = getPackageManager();
        long now = System.currentTimeMillis();

        Map<String, Long> bgMap = DoxaizNotificationService.snapshotBackgroundApps();
        Map<String, String> bgDetails = DoxaizNotificationService.snapshotBackgroundTabDetails();
        Map<String, AppEntry> entryByPkg = new HashMap<>();
        for (AppEntry e : visibleApps) {
            if (!entryByPkg.containsKey(e.packageName)) {
                entryByPkg.put(e.packageName, e);
            }
        }

        // 1. Сначала реально работающие фоновые приложения с их реальными строками содержимого
        for (Map.Entry<String, Long> e : bgMap.entrySet()) {
            String pkg = e.getKey();
            if (!seenPkgs.add(pkg)) continue;
            AppEntry ae = entryByPkg.get(pkg);
            String label = (ae != null) ? ae.displayLabel : resolvePkgLabel(pm, pkg);
            List<String[]> realLines = new ArrayList<>(DoxaizNotificationService.snapshotRealAppLines(pkg));
            if (realLines.isEmpty()) {
                String d = bgDetails.get(pkg);
                if (d != null && !d.isEmpty()) {
                    realLines.add(new String[]{label, d});
                }
            }
            out.add(new ActiveWindowTab(
                    pkg, label, "Активно " + formatRunningDuration(now - e.getValue()),
                    realLines, true, ae
            ));
        }

        // 2. Дополняем недавно открытыми окнами пользователя (с их реальными уведомлениями, если есть)
        List<AppEntry> recent = prefs.getRecentlyLaunchedApps(visibleApps, 6);
        for (AppEntry ae : recent) {
            if (out.size() >= 6) break;
            if (!seenPkgs.add(ae.packageName)) continue;
            List<String[]> realLines = DoxaizNotificationService.snapshotRealAppLines(ae.packageName);
            out.add(new ActiveWindowTab(
                    ae.packageName, ae.displayLabel, "Открытая вкладка",
                    realLines, false, ae
            ));
        }
        return out;
    }

    private String resolvePkgLabel(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence lbl = pm.getApplicationLabel(ai);
            if (lbl != null && lbl.length() > 0) return lbl.toString();
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    private View buildActiveWindowCard(final ActiveWindowTab tab,
                                       final LinearLayout strip,
                                       final TextView ramPillText,
                                       final TextView headerTitle) {
        final LinearLayout cardWrap = new LinearLayout(this);
        cardWrap.setOrientation(LinearLayout.VERTICAL);
        cardWrap.setTag(tab.packageName);

        // Чистая шапка окна: оригинальная иконка + название + статус + значок замка защиты от корзины
        LinearLayout tabHdr = new LinearLayout(this);
        tabHdr.setOrientation(LinearLayout.HORIZONTAL);
        tabHdr.setGravity(Gravity.CENTER_VERTICAL);
        tabHdr.setPadding(dp(4), 0, dp(2), dp(8));

        ImageView miniIcon = new ImageView(this);
        miniIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bindWindowAppIcon(tab, miniIcon);
        LinearLayout.LayoutParams miLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        miLp.rightMargin = dp(8);
        tabHdr.addView(miniIcon, miLp);

        LinearLayout nameCol = new LinearLayout(this);
        nameCol.setOrientation(LinearLayout.VERTICAL);

        TextView nameTv = new TextView(this);
        nameTv.setText(tab.appLabel);
        nameTv.setTextColor(Color.WHITE);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        nameCol.addView(nameTv);

        final TextView stTv = new TextView(this);
        stTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        stTv.setSingleLine(true);
        nameCol.addView(stTv);

        tabHdr.addView(nameCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final FrameLayout lockBtn = new FrameLayout(this);
        lockBtn.setContentDescription("Закрепить приложение (защита от корзины)");
        final ImageView lockIv = new ImageView(this);
        lockIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams liLp = new FrameLayout.LayoutParams(dp(15), dp(15));
        liLp.gravity = Gravity.CENTER;
        lockBtn.addView(lockIv, liLp);
        tabHdr.addView(lockBtn, new LinearLayout.LayoutParams(dp(28), dp(28)));
        cardWrap.addView(tabHdr);

        // Тело окна (БЕЗ кнопок «Открыть» / «Закрыть»: тап открывает, потянуть вверх — закрывает, удержание — замок!)
        final LinearLayout windowBody = new LinearLayout(this);
        windowBody.setOrientation(LinearLayout.VERTICAL);
        windowBody.setPadding(dp(12), dp(12), dp(12), dp(12));

        final Runnable renderLockVisual = () -> {
            boolean locked = prefs.isPackageLockedInRecents(tab.packageName);
            stTv.setText(locked ? "Закреплено • не закроется корзиной" : tab.statusText);
            stTv.setTextColor(locked ? 0xFFFBBF24 : (tab.isBackgroundService ? GlassTheme.ACCENT_GREEN : GlassTheme.TEXT_SECONDARY));
            lockBtn.setBackground(GlassTheme.makeGlassRect(
                    this,
                    locked ? 0x38FBBF24 : 0x1AFFFFFF,
                    locked ? 0x99FBBF24 : 0x26FFFFFF,
                    14f,
                    1f
            ));
            lockIv.setImageDrawable(VectorGlyphs.create(
                    this,
                    locked ? "lock" : "unlock",
                    15,
                    locked ? 0xFFFBBF24 : 0x88FFFFFF
            ));
            windowBody.setBackground(GlassTheme.makeGlassRect(
                    this,
                    0xFF1A1C24,
                    locked ? 0x88FBBF24 : 0x33FFFFFF,
                    22f,
                    locked ? 1.4f : 1f
            ));
        };
        renderLockVisual.run();

        final Runnable toggleLock = () -> {
            boolean nowLocked = prefs.togglePackageLockedInRecents(tab.packageName);
            if (prefs.isHapticFeedbackEnabled()) {
                lockBtn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
            renderLockVisual.run();
            cardWrap.animate().scaleX(1.03f).scaleY(1.03f).setDuration(90)
                    .withEndAction(() -> cardWrap.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                    .start();
            Toast.makeText(this,
                    nowLocked ? ("Закреплено: «" + tab.appLabel + "» (корзина не закроет)")
                              : ("Замок снят: «" + tab.appLabel + "»"),
                    Toast.LENGTH_SHORT).show();
        };
        lockBtn.setOnClickListener(v -> toggleLock.run());

        // Реальное живое содержимое приложения: обложка/трек плеера или реальные сообщения/уведомления пакета
        DoxaizNotificationService.MediaInfo activeMedia = DoxaizNotificationService.getActiveMediaInfo();
        boolean isActiveMediaPkg = activeMedia != null && tab.packageName.equals(activeMedia.packageName);

        if (isActiveMediaPkg) {
            // Реальное содержимое плеера: настоящая обложка альбома + название трека и исполнителя
            if (activeMedia.albumArt != null && !activeMedia.albumArt.isRecycled()) {
                ImageView artIv = new ImageView(this);
                artIv.setImageBitmap(activeMedia.albumArt);
                artIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(136)
                );
                aLp.bottomMargin = dp(10);
                windowBody.addView(artIv, aLp);
            } else {
                ImageView bigIcon = new ImageView(this);
                bigIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                bindWindowAppIcon(tab, bigIcon);
                LinearLayout.LayoutParams biLp = new LinearLayout.LayoutParams(dp(56), dp(56));
                biLp.gravity = Gravity.CENTER_HORIZONTAL;
                biLp.topMargin = dp(18);
                biLp.bottomMargin = dp(12);
                windowBody.addView(bigIcon, biLp);
            }
            TextView trackTv = new TextView(this);
            trackTv.setText(activeMedia.title.isEmpty() ? tab.appLabel : activeMedia.title);
            trackTv.setTextColor(Color.WHITE);
            trackTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            trackTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            trackTv.setMaxLines(2);
            trackTv.setEllipsize(TextUtils.TruncateAt.END);
            windowBody.addView(trackTv);

            if (!activeMedia.artist.isEmpty()) {
                TextView artTv = new TextView(this);
                artTv.setText(activeMedia.artist);
                artTv.setTextColor(GlassTheme.TEXT_SECONDARY);
                artTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                artTv.setSingleLine(true);
                artTv.setEllipsize(TextUtils.TruncateAt.END);
                windowBody.addView(artTv);
            }
        } else if (tab.realLines != null && !tab.realLines.isEmpty()) {
            // Реальные последние сообщения / строки активного содержимого этого приложения
            for (int i = 0; i < Math.min(3, tab.realLines.size()); i++) {
                String[] pair = tab.realLines.get(i);
                LinearLayout bubble = new LinearLayout(this);
                bubble.setOrientation(LinearLayout.VERTICAL);
                bubble.setBackground(GlassTheme.makeRoundedRect(0xFF242733, dp(13)));
                bubble.setPadding(dp(10), dp(8), dp(10), dp(8));
                if (pair[0] != null && !pair[0].isEmpty()) {
                    TextView bTitle = new TextView(this);
                    bTitle.setText(pair[0]);
                    bTitle.setTextColor(0xFF93C5FD);
                    bTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                    bTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    bTitle.setSingleLine(true);
                    bTitle.setEllipsize(TextUtils.TruncateAt.END);
                    bubble.addView(bTitle);
                }
                if (pair[1] != null && !pair[1].isEmpty()) {
                    TextView bBody = new TextView(this);
                    bBody.setText(pair[1]);
                    bBody.setTextColor(0xE8FFFFFF);
                    bBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                    bBody.setMaxLines(3);
                    bBody.setEllipsize(TextUtils.TruncateAt.END);
                    bubble.addView(bBody);
                }
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                bLp.bottomMargin = dp(8);
                windowBody.addView(bubble, bLp);
            }
        } else {
            // Компактное окно вкладки с оригинальной иконкой по центру
            LinearLayout center = new LinearLayout(this);
            center.setOrientation(LinearLayout.VERTICAL);
            center.setGravity(Gravity.CENTER);
            ImageView bigIcon = new ImageView(this);
            bigIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            bindWindowAppIcon(tab, bigIcon);
            center.addView(bigIcon, new LinearLayout.LayoutParams(dp(60), dp(60)));
            TextView lbl = new TextView(this);
            lbl.setText(tab.appLabel);
            lbl.setTextColor(0xD8FFFFFF);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            lbl.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lLp.topMargin = dp(10);
            center.addView(lbl, lLp);
            windowBody.addView(center, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ));
        }

        // Жесты окна:
        // - Простое нажатие (тап) → сразу открывает приложение;
        // - Зажать и потянуть вверх → плавно улетает вверх и закрывает приложение;
        // - Долгое удержание без свайпа → системная карточка «О приложении» (принудительная остановка).
        final float[] touchStart = new float[2];
        final long[] touchDownMs = new long[1];
        final boolean[] swipingUp = new boolean[]{false};
        final boolean[] moved = new boolean[]{false};
        windowBody.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchStart[0] = ev.getRawX();
                    touchStart[1] = ev.getRawY();
                    touchDownMs[0] = android.os.SystemClock.uptimeMillis();
                    swipingUp[0] = false;
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - touchStart[0];
                    float dy = ev.getRawY() - touchStart[1];
                    if (Math.abs(dx) > dp(10) || Math.abs(dy) > dp(10)) {
                        moved[0] = true;
                    }
                    if (!swipingUp[0] && dy < -dp(12) && Math.abs(dy) > Math.abs(dx) * 1.25f) {
                        swipingUp[0] = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (swipingUp[0]) {
                        float ty = Math.min(0f, dy);
                        float frac = Math.min(1f, Math.abs(ty) / dp(220));
                        cardWrap.setTranslationY(ty);
                        cardWrap.setAlpha(Math.max(0.22f, 1f - frac * 0.78f));
                        float sc = 1f - frac * 0.07f;
                        cardWrap.setScaleX(sc);
                        cardWrap.setScaleY(sc);
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP:
                    if (swipingUp[0]) {
                        swipingUp[0] = false;
                        if (cardWrap.getTranslationY() < -dp(64)) {
                            if (prefs.isPackageLockedInRecents(tab.packageName)) {
                                if (prefs.isHapticFeedbackEnabled()) {
                                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                }
                                cardWrap.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                                        .setDuration(155L).setInterpolator(SMOOTH_INTERPOLATOR).withLayer().start();
                                lockBtn.animate().scaleX(1.22f).scaleY(1.22f).setDuration(90)
                                        .withEndAction(() -> lockBtn.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                                        .start();
                                Toast.makeText(this, "Закреплено замком — нажмите на замок, чтобы снять", Toast.LENGTH_SHORT).show();
                            } else {
                                if (prefs.isHapticFeedbackEnabled()) {
                                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                                }
                                v.setOnTouchListener(null);
                                dismissWindowTabCard(tab, cardWrap, strip, ramPillText, headerTitle);
                            }
                        } else {
                            cardWrap.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                                    .setDuration(145L).setInterpolator(SMOOTH_INTERPOLATOR).withLayer().start();
                        }
                        return true;
                    }
                    if (!moved[0]) {
                        long held = android.os.SystemClock.uptimeMillis() - touchDownMs[0];
                        if (held >= 550L) {
                            toggleLock.run();
                        } else {
                            dismissModal();
                            openWindowTabApp(tab, windowBody);
                        }
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_CANCEL:
                    swipingUp[0] = false;
                    cardWrap.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(145L).withLayer().start();
                    return false;
                default:
                    return false;
            }
        });

        cardWrap.addView(windowBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));
        return cardWrap;
    }

    private void bindWindowAppIcon(ActiveWindowTab tab, ImageView target) {
        if (tab.entry != null && repository != null) {
            repository.bindIcon(tab.entry, target);
            return;
        }
        try {
            Drawable d = getPackageManager().getApplicationIcon(tab.packageName);
            if (d != null) target.setImageDrawable(d);
        } catch (Throwable ignored) {
        }
    }

    private void openWindowTabApp(ActiveWindowTab tab, View anchor) {
        if (tab.entry != null) {
            launchAndReturnHome(tab.entry, anchor);
            return;
        }
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(tab.packageName);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            }
        } catch (Throwable ignored) {
        }
    }

    private void dismissWindowTabCard(final ActiveWindowTab tab,
                                      final View cardWrap,
                                      final LinearLayout strip,
                                      final TextView ramLine,
                                      final TextView headerTitle) {
        stopBackgroundPackage(tab.packageName);
        DoxaizNotificationService.stopMediaForPackage(this, tab.packageName);
        DoxaizNotificationService.dismissPackageNotifications(tab.packageName);
        prefs.markRecentPackageDismissed(tab.packageName);
        cardWrap.animate()
                .translationY(-dp(250))
                .scaleX(0.90f)
                .scaleY(0.90f)
                .alpha(0f)
                .setDuration(160L)
                .setInterpolator(SMOOTH_INTERPOLATOR)
                .withLayer()
                .withEndAction(() -> {
                    if (isFinishing() || isDestroyed() || !strip.isAttachedToWindow()) return;
                    strip.removeView(cardWrap);
                    ramLine.setText(MemoryHelper.read(this).recentsHeadline);
                    refreshDrawerMemory();
                    refreshDrawerBgPill();
                    int left = strip.getChildCount();
                    if (headerTitle != null) {
                        headerTitle.setText(left > 0 ? ("Активные приложения • " + left) : "Активные приложения");
                    }
                    Toast.makeText(this, "Приложение «" + tab.appLabel + "» закрыто", Toast.LENGTH_SHORT).show();
                    if (left == 0) {
                        dismissModal();
                    }
                })
                .start();
    }

    private void stopBackgroundPackage(String pkg) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(pkg);
            }
        } catch (Throwable ignored) {
        }
    }

    private void openPackageAppInfo(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.fromParts("package", pkg, null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, "Нажмите «Остановить» на экране приложения", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static String formatRunningDuration(long diffMs) {
        long sec = Math.max(0L, diffMs / 1000L);
        if (sec < 60L) return "меньше минуты";
        long min = sec / 60L;
        if (min < 60L) return min + " мин";
        long hrs = min / 60L;
        long remMin = min % 60L;
        return remMin > 0 ? (hrs + " ч " + remMin + " мин") : (hrs + " ч");
    }

    private static String formatAppsRu(int n) {
        int m100 = n % 100;
        int m10 = n % 10;
        if (m100 >= 11 && m100 <= 14) return n + " приложений";
        if (m10 == 1) return n + " приложение";
        if (m10 >= 2 && m10 <= 4) return n + " приложения";
        return n + " приложений";
    }

    private void showPrivateDnsModal() {
        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Быстрый Частный DNS");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        NetworkQualityHelper.Status dnsSt = (networkHelper != null) ? networkHelper.inspect() : null;
        String currentDnsLine = (dnsSt != null && !dnsSt.dnsSummary.isEmpty())
                ? ("Текущее состояние: " + dnsSt.dnsSummary + "\n\n") : "";
        TextView sub = new TextView(this);
        sub.setText(currentDnsLine + "Выберите сервер — адрес скопируется в буфер, и откроются настройки сети. Вставьте его в пункт «Частный DNS» (ускоряет открытие сайтов без сторонних приложений).");
        sub.setTextColor(theme.textSecondary);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sLp.topMargin = dp(6);
        sLp.bottomMargin = dp(8);
        box.addView(sub, sLp);

        View div = new View(this);
        div.setBackgroundColor(theme.dividerColor);
        box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        String[][] providers = new String[][]{
                {"Google DNS (быстрый и стабильный)", "dns.google"},
                {"Cloudflare 1.1.1.1 (минимальная задержка)", "one.one.one.one"},
                {"Яндекс DNS (быстрый отклик в РФ)", "common.dot.dns.yandex.net"},
                {"AdGuard DNS (без рекламных доменов)", "dns.adguard-dns.com"},
                {"Quad9 DNS (защита от вредоносных доменов)", "dns.quad9.net"}
        };
        for (final String[] p : providers) {
            addModalAction(box, p[0] + "\n" + p[1], theme.textPrimary, () -> {
                Toast.makeText(this, "Скопировано: " + p[1] + " → вставьте в «Частный DNS»", Toast.LENGTH_LONG).show();
                NetworkQualityHelper.copyDnsAndOpenSettings(this, p[1]);
            });
        }
        showModalCard(box, Gravity.BOTTOM, false);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable ignored) {
            openSystemSettings();
        }
    }

    private void runSystemLayoutImport() {
        LayoutImporter.Result res = LayoutImporter.tryImportFromSystemLauncher(
                this, appsByKey, desktop, prefs
        );
        if (res.success) {
            currentPage = 0;
            rebuildDesktop();
            Toast.makeText(
                    this,
                    "Перенесено из " + res.sourceName + ": иконок " + res.importedApps
                            + ", папок " + res.importedFolders + " (" + res.detectedCols + " ст.)",
                    Toast.LENGTH_LONG
            ).show();
            showScreen(SCREEN_HOME);
            return;
        }

        LinearLayout box = createModalBox();
        TextView title = new TextView(this);
        title.setText("Импорт со штатного экрана");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        TextView msg = new TextView(this);
        msg.setText(
                "Android 14/15 защищает базу данных штатной оболочки от чтения другими приложениями без root-доступа.\n\n"
                        + "• Ваш экран в штатной оболочке полностью нетронут — при возврате всё останется на своих местах.\n"
                        + "• Расставьте нужные иконки в Doxaiz один раз и включите «Зафиксировать месторасположение» + «Сохранить раскладку в файл (.json)» — после этого места иконок больше никогда не изменятся."
        );
        msg.setTextColor(theme.textSecondary);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        msg.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        mLp.topMargin = dp(10);
        mLp.bottomMargin = dp(14);
        box.addView(msg, mLp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        addDialogButton(btns, "Понятно", theme.accentColor, this::dismissModal);
        box.addView(btns);
        showModalCard(box, Gravity.CENTER, false);
    }

    private void startExportBackup() {
        if (!prefs.isProtectHiddenApps()) {
            launchExportBackupPicker();
            return;
        }
        if (!BiometricHelper.isDeviceSecure(this)) {
            Toast.makeText(this, "Для сохранения защищённой копии сначала установите PIN или пароль экрана", Toast.LENGTH_LONG).show();
            return;
        }
        pendingPrivateSpaceAction = this::launchExportBackupPicker;
        BiometricHelper.authenticate(this, "Сохранение копии",
                "Файл содержит список скрытых приложений. Подтвердите отпечатком или PIN.",
                () -> { pendingPrivateSpaceAction = null; launchExportBackupPicker(); },
                () -> pendingPrivateSpaceAction = null);
    }

    private void launchExportBackupPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "doxaiz-layout.json");
            startActivityForResult(intent, REQ_EXPORT_BACKUP);
        } catch (Throwable ignored) {
            Toast.makeText(this, "Не удалось открыть сохранение файла", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchImportBackupPicker(boolean authVerified) {
        pendingImportAuthVerified = authVerified;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_IMPORT_BACKUP);
        } catch (Throwable ignored) {
            pendingImportAuthVerified = false;
            Toast.makeText(this, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show();
        }
    }

    private void startImportBackup() {
        if (prefs.isProtectHiddenApps()) {
            if (!BiometricHelper.isDeviceSecure(this)) {
                Toast.makeText(this, "Для восстановления защищённой копии сначала установите PIN или пароль экрана", Toast.LENGTH_LONG).show();
                return;
            }
            pendingPrivateSpaceAction = () -> launchImportBackupPicker(true);
            BiometricHelper.authenticate(
                    this,
                    "Восстановление копии",
                    "Подтвердите отпечатком или PIN для импорта защищённых настроек",
                    () -> {
                        pendingPrivateSpaceAction = null;
                        launchImportBackupPicker(true);
                    },
                    () -> pendingPrivateSpaceAction = null
            );
            return;
        }
        launchImportBackupPicker(false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROLE_HOME) {
            if (currentScreen == SCREEN_SETTINGS) {
                rebuildSettingsContent();
            }
            return;
        }
        if (requestCode == BiometricHelper.REQ_DEVICE_CREDENTIAL) {
            Runnable action = pendingPrivateSpaceAction;
            pendingPrivateSpaceAction = null;
            if (isFinishing() || isDestroyed()) return;
            if (resultCode == RESULT_OK && action != null) {
                action.run();
            } else if (currentScreen == SCREEN_SETTINGS) {
                // При отмене PIN/отпечатка возвращаем визуальный переключатель в фактическое состояние
                rebuildSettingsContent();
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQ_IMPORT_BACKUP) pendingImportAuthVerified = false;
            return;
        }
        Uri uri = data.getData();

        if (requestCode == REQ_EXPORT_BACKUP) {
            // Snapshot is taken on the UI thread; slow provider I/O is never performed there.
            final String json = BackupHelper.exportBackupJson(prefs, desktop);
            if (json == null) {
                Toast.makeText(this, "Ошибка формирования копии", Toast.LENGTH_SHORT).show();
                return;
            }
            final byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > BackupHelper.MAX_BACKUP_BYTES) {
                Toast.makeText(this, "Копия слишком велика для восстановления", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                backupIo.execute(() -> {
                    boolean ok = false;
                    try {
                        OutputStream stream;
                        try { stream = getContentResolver().openOutputStream(uri, "wt"); }
                        catch (Exception e) { stream = getContentResolver().openOutputStream(uri); }
                        if (stream != null) {
                            try (OutputStream out = stream) {
                                out.write(encoded);
                                out.flush();
                            }
                            ok = true;
                        }
                    } catch (Exception ignored) { }
                    final boolean result = ok;
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) Toast.makeText(this,
                                result ? "Раскладка сохранена в файл" : "Не удалось записать файл",
                                Toast.LENGTH_SHORT).show();
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) { }
        } else if (requestCode == REQ_IMPORT_BACKUP) {
            // The authentication token is single-use, including failures and oversized input.
            final boolean importAuth = pendingImportAuthVerified;
            pendingImportAuthVerified = false;
            try {
                backupIo.execute(() -> {
                    String json = null;
                    boolean tooLarge = false;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in != null) {
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            byte[] buffer = new byte[4096];
                            int n;
                            while ((n = in.read(buffer)) != -1) {
                                if (out.size() + n > BackupHelper.MAX_BACKUP_BYTES) {
                                    tooLarge = true;
                                    break;
                                }
                                out.write(buffer, 0, n);
                            }
                            if (!tooLarge) json = new String(out.toByteArray(), StandardCharsets.UTF_8);
                        }
                    } catch (Exception ignored) { }
                    final boolean oversized = tooLarge;
                    final String input = json;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (oversized) {
                            Toast.makeText(this, "Файл слишком большой для резервной копии Doxaiz", Toast.LENGTH_LONG).show();
                        } else if (input == null) {
                            Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show();
                        } else if (prefs.isProtectHiddenApps() && !BiometricHelper.isDeviceSecure(this)) {
                            Toast.makeText(this, "Для восстановления защищённой копии установите PIN или пароль экрана", Toast.LENGTH_LONG).show();
                        } else if (BackupHelper.importBackupJson(input, prefs, desktop, importAuth && BiometricHelper.isDeviceSecure(this))) {
                            // Apply restored privacy state before any desktop rebuild or async repository refresh.
                            hiddenAppKeys.clear();
                            hiddenAppKeys.addAll(prefs.getHiddenApps());
                            currentPage = 0;
                            applyTheme();
                            repository.reload();
                            Toast.makeText(this, "Раскладка восстановлена", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Файл не является резервной копией Doxaiz", Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) { }
        }
    }

    private void requestUninstall(AppEntry entry) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + entry.packageName));
            // Корректное удаление приложений из клона / рабочего профиля (как в AOSP Launcher3)
            if (entry.userHandle != null) {
                i.putExtra(Intent.EXTRA_USER, entry.userHandle);
            }
            startActivity(i);
        } catch (Throwable ignored) {
            repository.openAppInfo(entry);
        }
    }

    private int getIconDp() {
        int pct = prefs.getIconScalePercent();
        return Math.max(44, Math.min(64, Math.round(54f * pct / 100f)));
    }

    private void tintIcon(ImageView iv, int color) {
        iv.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    private int dp(float v) {
        return GlassTheme.dp(this, v);
    }
}
