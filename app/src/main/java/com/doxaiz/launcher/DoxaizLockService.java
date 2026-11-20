package com.doxaiz.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Лёгкая служба Doxaiz (работает полностью офлайн, без передачи и хранения данных):
 * 1. Отображает нижнюю панель ОЗУ прямо над круглой кнопкой-мусоркой
 *    ТОЛЬКО когда открыто системное меню недавних приложений (Recents / Overview).
 * 2. По нажатию мусорки нажимает системную команду «Очистить всё» в Recents,
 *    ставит на паузу фоновое медиа, очищает уведомления и останавливает фоновые процессы.
 * 3. Выполняет нативную блокировку экрана двойным касанием (Android 9+).
 */
public final class DoxaizLockService extends AccessibilityService {

    private static volatile DoxaizLockService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private LinearLayout overlayView;
    private LinearLayout batDetailBox;
    private TextView batPillText;
    private ImageView batPillIcon;
    private ImageView batChevIcon;
    private TextView batTempVal;
    private TextView batPwrVal;
    private TextView batVoltVal;
    private TextView batHealthVal;
    private boolean batExpanded = false;
    private TextView ramHeadView;
    private FrameLayout cleanBtnCircle;
    private ImageView cleanBtnIcon;
    private boolean overlayAttached = false;
    private boolean previewMode = false;
    private long lastDefaultCheckMs = 0L;
    private boolean cachedIsDefault = false;

    /** Период опроса ОЗУ/батареи в оверлее Недавних (было 1 с на каждый тик). */
    private static final long OVERLAY_REFRESH_MS = 2000L;
    /** Кратность опроса батареи: sticky-запрос дороже чтения MemoryInfo. */
    private static final int BATTERY_TICK_EVERY = 2;
    private int overlayTick = 0;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!overlayAttached) return;
            if (!previewMode && !isForegroundStillRecents()) {
                hideOverlay();
                return;
            }
            // ОЗУ читаем каждый тик, батарею (sticky registerReceiver + BatteryManager) — реже.
            boolean withBattery = (overlayTick++ % BATTERY_TICK_EVERY) == 0;
            updateOverlayNumbers(withBattery);
            handler.postDelayed(this, OVERLAY_REFRESH_MS);
        }
    };

    private final Runnable stopPreviewRunnable = () -> {
        previewMode = false;
        hideOverlay();
    };

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            previewMode = false;
            hideOverlay();
        }
    };
    private boolean screenReceiverRegistered = false;

    public static boolean isRunning() {
        return instance != null;
    }

    public static boolean lockScreenNow() {
        DoxaizLockService svc = instance;
        if (svc == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return svc.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }

    public static boolean takeScreenshotNow() {
        DoxaizLockService svc = instance;
        if (svc == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return svc.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
        }
        return false;
    }

    public static boolean openSystemRecents() {
        DoxaizLockService svc = instance;
        if (svc == null) return false;
        return svc.performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    /**
     * Открывает системную шторку уведомлений.
     *
     * Раньше это делалось только рефлексией MainActivity по
     * android.app.StatusBarManager#expandNotificationsPanel(). В API 33/34 такого
     * метода в android.jar нет вовсе (проверено по классу платформы), а
     * непубличный API закрыт с Android 11. Значит, жест «шторка» в системном
     * стиле молча не работал: NoSuchMethodException проглатывался.
     *
     * Правильный путь для лаунчера — GLOBAL_ACTION_NOTIFICATIONS, у него нет
     * ограничений hidden-API, но он требует включённой службы специальных
     * возможностей. Если её нет, вызывающий код показывает понятный диалог, а
     * не «ничего не произошло».
     */
    public static boolean expandNotificationShade() {
        DoxaizLockService svc = instance;
        if (svc == null) return false;
        return svc.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    public static boolean previewRamOverlay() {
        DoxaizLockService svc = instance;
        if (svc == null) return false;
        svc.handler.post(svc::showPreviewBriefly);
        return true;
    }

    public static void refreshVisibilityFromPrefs() {
        // Панель ОЗУ и кнопка-мусорка работают всегда по умолчанию
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            // RECEIVER_NOT_EXPORTED существует с API 33; на 26–32 — обычная
            // регистрация (поля RECEIVER_EXPORTED до 33 нет вовсе).
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenOffReceiver, filter);
            }
            screenReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();

        // Doxaiz 4.4: мгновенно скрываем оверлей, если пользователь вышел из Недавних жестом/переключением
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (overlayAttached && !previewMode && !isForegroundStillRecents()) {
                hideOverlay();
            }
            return;
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        CharSequence pkgCs = event.getPackageName();
        CharSequence clsCs = event.getClassName();
        String pkg = pkgCs != null ? pkgCs.toString().toLowerCase(Locale.ROOT) : "";
        String cls = clsCs != null ? clsCs.toString().toLowerCase(Locale.ROOT) : "";

        // Пропускаем временные всплывающие элементы (Toast, клавиатура, громкость)
        if (isTransientWindow(pkg, cls)) {
            return;
        }

        if (isTrustedRecentsHost(pkg) && isRecentsWindow(pkg, cls, event)) {
            previewMode = false;
            handler.removeCallbacks(stopPreviewRunnable);
            showOverlay();
        } else if (!pkg.isEmpty() && !previewMode) {
            hideOverlay();
        }
    }

    /**
     * Doxaiz 4.4: Жёсткая проверка активного корневого окна, чтобы плашка температуры и ОЗУ
     * никогда не оставалась поверх других приложений или рабочего стола.
     */
    private boolean isForegroundStillRecents() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            CharSequence pCs = root.getPackageName();
            CharSequence cCs = root.getClassName();
            String pkg = pCs != null ? pCs.toString().toLowerCase(Locale.ROOT) : "";
            String cls = cCs != null ? cCs.toString().toLowerCase(Locale.ROOT) : "";
            if (pkg.isEmpty() || !isTrustedRecentsHost(pkg)) return false;
            // Собственный лаунчер Doxaiz или любое стороннее приложение — это точно НЕ системные Недавние
            if (pkg.equals(getPackageName().toLowerCase(Locale.ROOT))) return false;
            boolean isHost = isStockXosHostPackage(pkg);
            return isRecentsWindowSpec(pkg, cls, "", isHost && isDoxaizDefaultLauncher())
                    || (pkg.contains("systemui") && (cls.contains("recents") || cls.contains("overview")));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isTrustedRecentsHost(String packageName) {
        if (packageName == null || packageName.isEmpty() || packageName.equals(getPackageName())) return false;
        try {
            android.content.pm.ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            return info != null && (info.flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM
                    | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (android.content.pm.PackageManager.NameNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** Explicit class evidence is required before automating a destructive system action. */
    static boolean hasExplicitRecentsClass(String className) {
        if (className == null) return false;
        String c = className.toLowerCase(Locale.ROOT);
        return c.contains("recents") || c.contains("overview") || c.contains("taskswitcher")
                || c.contains("multitask") || c.contains("quicksteprecents") || c.contains("fallbackrecents");
    }

    static boolean isStockXosHostPackage(String pkgLower) {
        return "com.transsion.xoslauncher".equals(pkgLower)
                || "com.transsion.hilauncher".equals(pkgLower)
                || "com.transsion.launcher".equals(pkgLower);
    }

    private boolean isTransientWindow(String pkg, String cls) {
        if (pkg.isEmpty()) return true;
        // Не реагируем на события от собственного overlay-окна Doxaiz
        if (pkg.equals(getPackageName().toLowerCase(Locale.ROOT)) && !cls.contains("mainactivity")) {
            return true;
        }
        if (cls.contains("toast") || cls.contains("inputmethod") || cls.contains("volumedialog")) {
            return true;
        }
        return false;
    }

    private boolean isRecentsWindow(String pkg, String cls, AccessibilityEvent event) {
        String label = "";
        boolean isLauncherOrSysUi = pkg.contains("launcher")
                || pkg.contains("systemui")
                || pkg.contains("transsion");
        if (isLauncherOrSysUi) {
            StringBuilder textBuf = new StringBuilder();
            List<CharSequence> texts = event.getText();
            if (texts != null) {
                for (CharSequence cs : texts) {
                    if (cs != null) textBuf.append(' ').append(cs);
                }
            }
            if (event.getContentDescription() != null) {
                textBuf.append(' ').append(event.getContentDescription());
            }
            label = textBuf.toString().toLowerCase(Locale.ROOT);
        }
        // Не делаем лишний IPC PackageManager.resolveActivity при переключении обычных приложений!
        boolean isDefault = isStockXosHostPackage(pkg) && isDoxaizDefaultLauncher();
        return isRecentsWindowSpec(pkg, cls, label, isDefault);
    }

    /**
     * Чистая тестируемая логика определения системного экрана Недавних (без ложных срабатываний
     * на обычные окна, шторку и настройки).
     */
    static boolean isRecentsWindowSpec(String pkg, String cls, String label, boolean isDoxaizDefault) {
        String p = pkg != null ? pkg.toLowerCase(Locale.ROOT) : "";
        String c = cls != null ? cls.toLowerCase(Locale.ROOT) : "";
        String l = label != null ? label.toLowerCase(Locale.ROOT) : "";

        // 1. Прямые классы Recents / Overview на AOSP, Pixel, One UI, HyperOS и XOS
        if (c.contains("recents")
                || c.contains("overview")
                || c.contains("taskswitcher")
                || c.contains("multitask")
                || c.contains("quicksteprecents")
                || c.contains("fallbackrecents")) {
            return true;
        }

        // 2. Текстовый заголовок строго для системных окон Recents (убрано слишком широкое «активно»)
        boolean isLauncherOrSysUi = p.contains("launcher")
                || p.contains("systemui")
                || p.contains("transsion");
        if (isLauncherOrSysUi && !l.isEmpty()) {
            if (l.contains("недавн")
                    || l.contains("общий обзор")
                    || l.contains("recent")
                    || l.contains("overview")) {
                return true;
            }
        }

        // 3. Когда Doxaiz выбран основной оболочкой, штатный хост-лаунчер выходит
        //    на передний план исключительно при нажатии кнопки «Недавние» (≡)
        //    (исключаем окна его настроек, обоев и системных диалогов)
        boolean isStockXosHost = p.equals("com.transsion.xoslauncher")
                || p.equals("com.transsion.hilauncher")
                || p.equals("com.transsion.launcher");
        if (isStockXosHost && (c.contains("setting") || c.contains("preference")
                || c.contains("dialog") || c.contains("wallpaper"))) {
            return false;
        }
        return isStockXosHost && isDoxaizDefault;
    }

    private boolean isDoxaizDefaultLauncher() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastDefaultCheckMs < 5000L) {
            return cachedIsDefault;
        }
        lastDefaultCheckMs = now;
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            cachedIsDefault = ri != null && ri.activityInfo != null
                    && getPackageName().equals(ri.activityInfo.packageName);
            return cachedIsDefault;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void showPreviewBriefly() {
        previewMode = true;
        handler.removeCallbacks(stopPreviewRunnable);
        showOverlay();
        handler.postDelayed(stopPreviewRunnable, 3500L);
    }

    private void showOverlay() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (windowManager == null) return;

        // Единая красивая нижняя панель (как в MIUI): строка ОЗУ прямо над круглой кнопкой-мусоркой
        if (!overlayAttached) {
            buildOverlayView();
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            lp.format = PixelFormat.TRANSLUCENT;
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = getNavBarHeight() + GlassTheme.dp(this, 18);

            try {
                windowManager.addView(overlayView, lp);
                overlayAttached = true;
            } catch (Throwable ignored) {
                overlayAttached = false;
                return;
            }
        }

        updateOverlayNumbers();
        handler.removeCallbacks(refreshRunnable);
        if (overlayAttached) {
            handler.postDelayed(refreshRunnable, 1200L);
        }
    }

    private void onCleanTrashClicked() {
        if (previewMode || !isForegroundStillRecents()) {
            hideOverlay();
            return;
        }
        if (cleanBtnCircle != null) {
            cleanBtnCircle.animate().scaleX(0.84f).scaleY(0.84f).setDuration(90)
                    .withEndAction(() -> {
                        if (cleanBtnCircle != null) {
                            cleanBtnCircle.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        }
                    }).start();
        }
        if (cleanBtnIcon != null) {
            cleanBtnIcon.animate().rotation(-18f).setDuration(90)
                    .withEndAction(() -> {
                        if (cleanBtnIcon != null) {
                            cleanBtnIcon.animate().rotation(0f).setDuration(150).start();
                        }
                    }).start();
        }

        Prefs svcPrefs = new Prefs(this);
        java.util.Set<String> lockedPkgs = svcPrefs.getLockedRecentsPackages();

        // 1. Если нет закреплённых замком приложений — нажимаем системную команду «Очистить всё»,
        //    чтобы Android физически закрыл все карточки. Если есть закреплённые — бережно
        //    чистим только незакреплённые, чтобы не закрыть защищённые программы.
        final boolean systemCleared = lockedPkgs.isEmpty() && tryClickSystemClearAll();

        // 2. Останавливаем незакреплённый плеер, чистим незакреплённые уведомления и фоновые процессы.
        DoxaizNotificationService.stopActiveMediaExcept(this, lockedPkgs);
        DoxaizNotificationService.clearAllClearableExcept(lockedPkgs);
        svcPrefs.markAllRecentTabsClearedExcept(lockedPkgs);

        // 3. Возвращаемся на чистый рабочий стол и прячем оверлей сразу: очистка
        // ОЗУ идёт в фоне и не должна задерживать выход из «Недавних».
        handler.postDelayed(() -> {
            hideOverlay();
            if (!systemCleared) {
                try {
                    performGlobalAction(GLOBAL_ACTION_HOME);
                } catch (Throwable ignored) {
                }
            }
        }, 420L);

        // 4. Сама очистка ОЗУ — в фоне. boostRam() опрашивает ActivityManager и
        // вызывает killBackgroundProcesses() для каждого пакета: это сотни
        // Binder-вызовов. Раньше они выполнялись прямо здесь, в потоке
        // AccessibilityService, и кнопка корзины «залипала» на устройствах с
        // большим числом приложений (а служба специальных возможностей обязана
        // отвечать быстро, иначе система начинает её ограничивать).
        MemoryHelper.boostRamAsync(this, null, lockedPkgs, result -> {
            updateOverlayNumbers();
            try {
                Toast.makeText(getApplicationContext(), result.message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * Ищет системную кнопку «Очистить всё» в активном окне Recents и нажимает её,
     * чтобы полностью закрыть все работающие задачи системы.
     */
    private boolean tryClickSystemClearAll() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return false;
            CharSequence rootPkg = root.getPackageName();
            CharSequence rootClass = root.getClassName();
            if (rootPkg == null || !isTrustedRecentsHost(rootPkg.toString())
                    || !hasExplicitRecentsClass(rootClass != null ? rootClass.toString() : null)
                    || !isForegroundStillRecents()) return false;
            int[] visited = new int[]{0};
            return findAndClickClearAll(root, 0, visited);
        } catch (Throwable ignored) {
            return false;
        } finally {
            // Корень получен нами и больше не нужен (см. recycleNode).
            recycleNode(root);
        }
    }

    /**
     * Обход дерева «Недавних» в поисках кнопки «Очистить всё».
     *
     * Каждый getChild()/getParent() возвращает НОВУЮ обёртку над нативным узлом,
     * которую нужно освобождать явно: без этого дерево из ~180 узлов на каждое
     * нажатие корзины оставляло занятыми нативные объекты, а служба специальных
     * возможностей живёт долго — это накопительная утечка памяти, из-за которой
     * система в итоге отключает службу «за нестабильность».
     */
    private boolean findAndClickClearAll(AccessibilityNodeInfo node, int depth, int[] visited) {
        if (node == null || depth > 12 || visited[0] > 180) return false;
        visited[0]++;

        if (isClearAllNode(node)) {
            // Поднимаемся к ближайшему нажимаемому предку. Начальный узел
            // принадлежит вызывающему и не освобождается; каждый следующий
            // получен нами через getParent() и освобождается ровно один раз —
            // либо когда уходим выше, либо после выхода из цикла.
            AccessibilityNodeInfo current = node;
            boolean clicked = false;
            for (int hops = 0; hops <= 4; hops++) {
                if (current.isClickable() && current.isEnabled() && safeClick(current)) {
                    clicked = true;
                    break;
                }
                if (hops == 4) break;
                AccessibilityNodeInfo parent = current.getParent();
                if (current != node) recycleNode(current);
                if (parent == null) {
                    current = null;
                    break;
                }
                current = parent;
            }
            if (current != null && current != node) recycleNode(current);
            if (clicked) return true;
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                if (child != null && findAndClickClearAll(child, depth + 1, visited)) {
                    return true;
                }
            } finally {
                recycleNode(child);
            }
        }
        return false;
    }

    /** Нажатие на узел без исключений (дерево могло измениться между вызовами). */
    private static boolean safeClick(AccessibilityNodeInfo target) {
        try {
            return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Освобождение узла. Начиная с API 33 этот вызов ничего не делает (объекты
     * собирает GC), а на старых версиях он обязателен, поэтому версия проверяется.
     */
    private static void recycleNode(AccessibilityNodeInfo node) {
        if (node == null) return;
        try {
            if (Build.VERSION.SDK_INT < 33) node.recycle();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isClearAllNode(AccessibilityNodeInfo node) {
        if (!node.isVisibleToUser()) return false;
        String id = node.getViewIdResourceName();
        if (id != null) {
            String lowId = id.toLowerCase(Locale.ROOT);
            if (lowId.contains("clear_all") || lowId.contains("clearall")
                    || lowId.contains("clean_all") || lowId.contains("close_all")
                    || lowId.contains("remove_all") || lowId.contains("dismiss_all")
                    || lowId.contains("recents_clear") || lowId.contains("recent_clean")
                    || lowId.contains("clear_memory") || lowId.contains("clean_memory")
                    || lowId.contains("btn_clear") || lowId.contains("clear_btn")) {
                return true;
            }
        }
        CharSequence txt = node.getText();
        if (matchesClearAllLabel(txt)) return true;
        CharSequence desc = node.getContentDescription();
        return matchesClearAllLabel(desc);
    }

    private static boolean matchesClearAllLabel(CharSequence cs) {
        if (cs == null || cs.length() == 0 || cs.length() > 40) return false;
        String low = cs.toString().toLowerCase(Locale.ROOT).trim();
        return low.contains("очистить вс")
                || low.contains("закрыть вс")
                || low.contains("удалить вс")
                || low.equals("очистить")
                || low.contains("clear all")
                || low.contains("close all")
                || low.contains("dismiss all")
                || low.contains("clean all");
    }

    private void hideOverlay() {
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(stopPreviewRunnable);
        batExpanded = false;
        if (!overlayAttached || overlayView == null || windowManager == null) return;
        try {
            windowManager.removeViewImmediate(overlayView);
        } catch (Throwable ignored) {
        }
        overlayAttached = false;
        overlayView = null;
        batDetailBox = null;
        batPillText = null;
        batPillIcon = null;
        batChevIcon = null;
        batTempVal = null;
        batPwrVal = null;
        batVoltVal = null;
        batHealthVal = null;
        ramHeadView = null;
        cleanBtnCircle = null;
        cleanBtnIcon = null;
    }

    private void buildOverlayView() {
        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setGravity(Gravity.CENTER_HORIZONTAL);
        overlayView.setBackground(null);

        // 1) Компактная раскрывающаяся карточка батареи (скрыта по умолчанию — раскрывается ТОЛЬКО при тапе на маленькую плашку!)
        batDetailBox = new LinearLayout(this);
        batDetailBox.setOrientation(LinearLayout.VERTICAL);
        // Плашка батареи на экране блокировки лежит поверх обоев, поэтому фон
        // должен быть плотным, а не прозрачным: 0xEE181920 пропускал картинку.
        batDetailBox.setBackground(GlassTheme.makeGlassRect(
                this, GlassTheme.SHADE_CARD, GlassTheme.CARD_BORDER_LIVE, 18f, 1f
        ));
        batDetailBox.setPadding(
                GlassTheme.dp(this, 12), GlassTheme.dp(this, 10),
                GlassTheme.dp(this, 12), GlassTheme.dp(this, 10)
        );
        batDetailBox.setVisibility(android.view.View.GONE);

        LinearLayout bdHdr = new LinearLayout(this);
        bdHdr.setOrientation(LinearLayout.HORIZONTAL);
        bdHdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView bdTitle = new TextView(this);
        bdTitle.setText("Батарея и нагрев");
        bdTitle.setTextColor(GlassTheme.TEXT_PRIMARY);
        bdTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        bdTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bdHdr.addView(bdTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView bdClose = new TextView(this);
        bdClose.setText("✕");
        bdClose.setTextColor(GlassTheme.TEXT_SECONDARY);
        bdClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        bdClose.setPadding(GlassTheme.dp(this, 8), 0, GlassTheme.dp(this, 2), 0);
        bdClose.setOnClickListener(v -> toggleOverlayBatteryDetail(false));
        bdHdr.addView(bdClose);
        batDetailBox.addView(bdHdr);

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
                GlassTheme.dp(this, 248), LinearLayout.LayoutParams.WRAP_CONTENT);
        r1Lp.topMargin = GlassTheme.dp(this, 7);
        batTempVal = makeOverlayBatTile(r1, "Температура", true);
        batPwrVal = makeOverlayBatTile(r1, "Питание", false);
        batDetailBox.addView(r1, r1Lp);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                GlassTheme.dp(this, 248), LinearLayout.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = GlassTheme.dp(this, 6);
        batVoltVal = makeOverlayBatTile(r2, "Напряжение", true);
        batHealthVal = makeOverlayBatTile(r2, "Состояние", false);
        batDetailBox.addView(r2, r2Lp);

        LinearLayout.LayoutParams bdLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bdLp.bottomMargin = GlassTheme.dp(this, 7);
        overlayView.addView(batDetailBox, bdLp);

        // 2) Маленькая компактная плашка батареи/температуры (всегда свёрнута, раскрывается только по нажатию)
        LinearLayout batPill = new LinearLayout(this);
        batPill.setOrientation(LinearLayout.HORIZONTAL);
        batPill.setGravity(Gravity.CENTER_VERTICAL);
        // Плашки экрана блокировки лежат поверх обоев и чужих окон: фон плотный
        // из темы, иначе светлые обои просвечивают и белая подпись исчезает.
        batPill.setBackground(GlassTheme.makeGlassRect(
                this, GlassTheme.SHADE_CARD, GlassTheme.borderOnElevatedSurface(), 16f, 1f
        ));
        batPill.setPadding(
                GlassTheme.dp(this, 11), GlassTheme.dp(this, 5),
                GlassTheme.dp(this, 10), GlassTheme.dp(this, 5)
        );
        batPillIcon = VectorGlyphs.makeIconView(this, "battery", 13, GlassTheme.ACCENT_GREEN);
        LinearLayout.LayoutParams biLp = new LinearLayout.LayoutParams(
                GlassTheme.dp(this, 13), GlassTheme.dp(this, 13));
        biLp.rightMargin = GlassTheme.dp(this, 6);
        batPill.addView(batPillIcon, biLp);

        batPillText = new TextView(this);
        batPillText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        batPillText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        batPillText.setTextColor(GlassTheme.TEXT_PRIMARY);
        batPillText.setSingleLine(true);
        batPill.addView(batPillText);

        batChevIcon = VectorGlyphs.makeIconView(this, "chevron_down", 12, GlassTheme.TEXT_SECONDARY);
        LinearLayout.LayoutParams bcLp = new LinearLayout.LayoutParams(
                GlassTheme.dp(this, 12), GlassTheme.dp(this, 12));
        bcLp.leftMargin = GlassTheme.dp(this, 5);
        batPill.addView(batChevIcon, bcLp);

        batPill.setOnClickListener(v -> toggleOverlayBatteryDetail(!batExpanded));
        LinearLayout.LayoutParams bpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bpLp.bottomMargin = GlassTheme.dp(this, 7);
        overlayView.addView(batPill, bpLp);

        // 3) Красивая строка ОЗУ прямо над круглой корзиной
        LinearLayout ramPill = new LinearLayout(this);
        ramPill.setOrientation(LinearLayout.HORIZONTAL);
        ramPill.setGravity(Gravity.CENTER_VERTICAL);
        ramPill.setBackground(GlassTheme.makeGlassRect(
                this, GlassTheme.SHADE_CARD, GlassTheme.borderOnElevatedSurface(), 16f, 1f
        ));
        ramPill.setPadding(
                GlassTheme.dp(this, 13), GlassTheme.dp(this, 5),
                GlassTheme.dp(this, 13), GlassTheme.dp(this, 5)
        );

        android.view.View dot = new android.view.View(this);
        dot.setBackground(GlassTheme.makeRoundedRect(GlassTheme.ACCENT_GREEN, GlassTheme.dp(this, 4)));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                GlassTheme.dp(this, 7), GlassTheme.dp(this, 7)
        );
        dLp.rightMargin = GlassTheme.dp(this, 7);
        ramPill.addView(dot, dLp);

        ramHeadView = new TextView(this);
        ramHeadView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        ramHeadView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ramHeadView.setTextColor(GlassTheme.TEXT_PRIMARY);
        ramHeadView.setSingleLine(true);
        ramPill.addView(ramHeadView);

        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        pLp.bottomMargin = GlassTheme.dp(this, 10);
        overlayView.addView(ramPill, pLp);

        // 4) Круглая кнопка-мусорка 58dp внизу по центру
        int btnPx = GlassTheme.dp(this, 58);
        cleanBtnCircle = new FrameLayout(this);
        cleanBtnCircle.setContentDescription("Очистить все приложения и ОЗУ");
        // Плавающая кнопка «очистить ОЗУ» лежит поверх чужих окон: фон плотный,
        // иначе сквозь него просвечивает приложение под ней.
        cleanBtnCircle.setBackground(GlassTheme.makeGlassRect(
                this, GlassTheme.SHADE_CARD, GlassTheme.borderOnElevatedSurface(), 29f, 1f
        ));
        cleanBtnIcon = VectorGlyphs.makeIconView(this, "trash", 24, GlassTheme.TEXT_PRIMARY);
        FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(
                GlassTheme.dp(this, 24), GlassTheme.dp(this, 24)
        );
        iLp.gravity = Gravity.CENTER;
        cleanBtnCircle.addView(cleanBtnIcon, iLp);
        cleanBtnCircle.setOnClickListener(v -> onCleanTrashClicked());
        overlayView.addView(cleanBtnCircle, new LinearLayout.LayoutParams(btnPx, btnPx));
    }

    private TextView makeOverlayBatTile(LinearLayout row, String label, boolean rightGap) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(GlassTheme.makeRoundedRect(GlassTheme.SURFACE_ELEVATED,
                GlassTheme.dp(this, 11)));
        tile.setPadding(GlassTheme.dp(this, 9), GlassTheme.dp(this, 6),
                GlassTheme.dp(this, 9), GlassTheme.dp(this, 6));

        TextView lTv = new TextView(this);
        lTv.setText(label);
        lTv.setTextColor(GlassTheme.TEXT_SECONDARY);
        lTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        lTv.setSingleLine(true);
        tile.addView(lTv);

        TextView vTv = new TextView(this);
        vTv.setText("—");
        vTv.setTextColor(GlassTheme.TEXT_PRIMARY);
        vTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        vTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        vTv.setSingleLine(true);
        tile.addView(vTv);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (rightGap) lp.rightMargin = GlassTheme.dp(this, 6);
        row.addView(tile, lp);
        return vTv;
    }

    private void toggleOverlayBatteryDetail(boolean open) {
        batExpanded = open;
        if (batDetailBox != null) {
            batDetailBox.setVisibility(open ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        if (batChevIcon != null) {
            batChevIcon.animate().rotation(open ? 180f : 0f).setDuration(140).start();
        }
        if (open) updateOverlayNumbers();
    }

    private void updateOverlayNumbers() {
        updateOverlayNumbers(true);
    }

    private void updateOverlayNumbers(boolean withBattery) {
        if (ramHeadView == null) return;
        MemoryHelper.Snapshot snap = MemoryHelper.read(this);
        ramHeadView.setText(snap.recentsHeadline);
        if (!withBattery) return;

        BatteryInfoHelper.Snapshot bat = BatteryInfoHelper.read(this);
        if (batPillText != null) {
            batPillText.setText(bat.pillSummary.isEmpty() ? "Батарея • датчики" : bat.pillSummary);
        }
        if (batPillIcon != null) {
            batPillIcon.setImageDrawable(VectorGlyphs.create(
                    this, bat.isCharging ? "bolt" : "battery", 13, bat.tempColor));
        }
        if (batTempVal != null) {
            String t = BatteryInfoHelper.formatTemp(bat.tempC);
            batTempVal.setText(t.isEmpty() ? "—" : t);
            batTempVal.setTextColor(bat.tempColor);
        }
        if (batPwrVal != null) {
            String pwr = (bat.powerWatts >= 0.2f)
                    ? BatteryInfoHelper.formatWatts(bat.powerWatts)
                    : (bat.isCharging ? "Зарядка" : "От АКБ");
            batPwrVal.setText(pwr);
            batPwrVal.setTextColor(bat.isCharging
                    ? GlassTheme.ACCENT_BLUE_ON_SOFT : GlassTheme.TEXT_PRIMARY);
        }
        if (batVoltVal != null) {
            batVoltVal.setText(BatteryInfoHelper.formatVoltage(bat.voltageMv));
        }
        if (batHealthVal != null) {
            batHealthVal.setText(bat.healthLabel);
            batHealthVal.setTextColor(bat.healthGood ? GlassTheme.ACCENT_GREEN : 0xFFF59E0B);
        }
    }

    private int getNavBarHeight() {
        if (Build.VERSION.SDK_INT >= 30 && windowManager != null) {
            try {
                android.view.WindowMetrics wm = windowManager.getCurrentWindowMetrics();
                int bottom = wm.getWindowInsets()
                        .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
                        .bottom;
                if (bottom >= 0) return bottom;
            } catch (Throwable ignored) {
            }
        }
        // Внутренний ресурс android:navigation_bar_height не публичный: на части
        // прошивок его нет, а с Android 11 ресурсная рефлексия к платформе
        // ограничена. На 26–29 используем настраиваемый запас высоты (для
        // панели ОЗУ разница в пару dp не критична), на 30+ работает
        // WindowInsets выше.
        return GlassTheme.dp(this, 44);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (overlayAttached) {
            boolean wasPreview = previewMode;
            hideOverlay();
            if (wasPreview || isForegroundStillRecents()) {
                previewMode = wasPreview;
                showOverlay();
            }
        }
    }

    @Override
    public void onInterrupt() {
        hideOverlay();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        handler.removeCallbacksAndMessages(null);
        hideOverlay();
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenOffReceiver); } catch (Throwable ignored) {}
            screenReceiverRegistered = false;
        }
        if (instance == this) instance = null;
    }
}
