package com.doxaiz.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Импорт раскладки рабочего стола (страницы, точные координаты (cellX, cellY), папки и док)
 * из штатной оболочки устройства (XOS Launcher / Transsion / HiOS / AOSP Launcher3).
 *
 * Doxaiz 3.4:
 *  - Двухпроходный импорт с автоопределением 5-колоночной сетки XOS (если cellX >= 4 -> cols=5),
 *    благодаря чему 5-й столбец иконок никогда не сдвигается и не накладывается.
 *  - Динамический поиск ContentProvider у всех установленных системных HOME-оболочек.
 *  - Безопасное сопоставление по ComponentName и резервному packageName.
 */
public final class LayoutImporter {

    public static final class Result {
        public final boolean success;
        public final String sourceName;
        public final int importedApps;
        public final int importedFolders;
        public final int detectedCols;

        public Result(boolean success, String sourceName, int importedApps, int importedFolders, int detectedCols) {
            this.success = success;
            this.sourceName = sourceName;
            this.importedApps = importedApps;
            this.importedFolders = importedFolders;
            this.detectedCols = detectedCols;
        }

        /**
         * Результат «импорт не выполнялся»: нужен, когда чужую базу штатной
         * оболочки уже спрашивали раньше и получили отказ. Отличается от
         * неуспеха тем, что ничего не читает и ничего не меняет.
         */
        public static Result notAttempted(int fallbackCols) {
            return new Result(false, "", 0, 0, fallbackCols);
        }

        /**
         * Есть ли что применять. Нужен вызывающему, чтобы не перестраивать
         * рабочий стол впустую, когда чужая оболочка ничего не дала.
         */
        public boolean hasContent() {
            return success || importedApps > 0 || importedFolders > 0;
        }
    }

    private static final String[][] KNOWN_PROVIDERS = new String[][]{
            {"content://com.transsion.XOSLauncher.settings/favorites?notify=false", "XOS Launcher"},
            {"content://com.transsion.XOSLauncher.launcher.settings/favorites?notify=false", "XOS Launcher"},
            {"content://com.transsion.hilauncher.settings/favorites?notify=false", "HiOS Launcher"},
            {"content://com.transsion.magiclauncher.settings/favorites?notify=false", "Magic Launcher"},
            {"content://com.transsion.launcher.settings/favorites?notify=false", "Transsion Launcher"},
            {"content://com.infinix.xos.launcher.settings/favorites?notify=false", "XOS Launcher"},
            {"content://com.android.launcher3.settings/favorites?notify=false", "Launcher3"},
            {"content://com.google.android.apps.nexuslauncher.settings/favorites?notify=false", "System Launcher"}
    };

    /**
     * Предел строк, которые читаются из базы чужой оболочки за один импорт.
     *
     * Зачем: ContentProvider принадлежит ДРУГОМУ приложению, и его ответ никем не
     * ограничен. Курсор на несколько сотен тысяч строк (битая база, чужой
     * провайдер с тем же именем authority) складывался в список целиком — это
     * OOM в фоновом потоке импорта. Реальная раскладка — это 8 страниц × 24
     * иконки + док + папки, то есть меньше 300 строк, так что предел с запасом.
     */
    static final int MAX_IMPORT_ROWS = 2000;

    /** Предел длины подписи иконки при импорте: чужое имя может быть любым. */
    private static final int MAX_IMPORT_TEXT_LEN = 256;

    private static final int CONTAINER_DESKTOP = -100;
    private static final int CONTAINER_HOTSEAT = -101;
    private static final int ITEM_TYPE_APPLICATION = 0;
    private static final int ITEM_TYPE_SHORTCUT = 1;
    private static final int ITEM_TYPE_FOLDER = 2;

    private LayoutImporter() {}

    public static Result tryImportFromSystemLauncher(
            Context ctx,
            Map<String, AppEntry> appsByKey,
            DesktopModel desktop,
            int cols
    ) {
        return tryImportFromSystemLauncher(ctx, appsByKey, desktop, null, cols);
    }

    public static Result tryImportFromSystemLauncher(
            Context ctx,
            Map<String, AppEntry> appsByKey,
            DesktopModel desktop,
            Prefs prefs
    ) {
        int fallbackCols = (prefs != null) ? prefs.getColumns() : 4;
        return tryImportFromSystemLauncher(ctx, appsByKey, desktop, prefs, fallbackCols);
    }

    /**
     * Шаг 1 из 2: ЧИТАЕТ базу штатной оболочки. Может блокироваться надолго
     * (IPC к чужому ContentProvider), поэтому вызывать только из фонового потока.
     *
     * @return снимок прочитанных строк или null, если прочитать не удалось
     */
    public static Snapshot readSystemLauncher(
            Context ctx,
            Map<String, AppEntry> appsByKey,
            Prefs prefs
    ) {
        int fallbackCols = (prefs != null) ? prefs.getColumns() : 4;
        if (ctx == null || appsByKey == null || appsByKey.isEmpty()) return null;

        Map<String, String> byComponent = new HashMap<>();
        Map<String, String> byPackage = new HashMap<>();
        for (AppEntry e : appsByKey.values()) {
            if (e == null || e.componentName == null) continue;
            String cmpKey = e.componentName.flattenToString();
            if (!byComponent.containsKey(cmpKey) || e.isMainProfile) {
                byComponent.put(cmpKey, e.key);
            }
            if (!byPackage.containsKey(e.packageName) || e.isMainProfile) {
                byPackage.put(e.packageName, e.key);
            }
        }

        List<String[]> candidates = buildProviderCandidates(ctx);
        for (String[] provider : candidates) {
            Cursor cursor = null;
            try {
                cursor = ctx.getContentResolver().query(
                        Uri.parse(provider[0]), null, null, null, null
                );
                if (cursor == null || cursor.getCount() == 0) continue;
                Snapshot snap = readFavoritesCursor(cursor, provider[1], byComponent, byPackage, fallbackCols);
                if (snap != null && snap.hasContent()) return snap;
            } catch (Throwable t) {
                // Android 14+ ограничивает доступ к БД OEM-лаунчера — идём дальше,
                // но оставляем след: раньше «импорт ничего не нашёл» было
                // неотличимо от «доступ запрещён», и это выяснялось только
                // догадками на телефоне.
                Diagnostics.record("LayoutImporter.readProvider", t);
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Throwable ignored) { }
                }
            }
        }
        return null;
    }

    /**
     * Шаг 2 из 2: ПРИМЕНЯЕТ прочитанный снимок к модели рабочего стола.
     * Чистая работа с памятью — безопасно вызывать в UI-потоке.
     */
    public static Result applySnapshot(Snapshot snap, DesktopModel desktop, Prefs prefs) {
        int fallbackCols = (prefs != null) ? prefs.getColumns() : 4;
        if (snap == null || desktop == null) return new Result(false, "", 0, 0, fallbackCols);
        return applyRows(snap, desktop, prefs, fallbackCols);
    }

    private static Result tryImportFromSystemLauncher(
            Context ctx,
            Map<String, AppEntry> appsByKey,
            DesktopModel desktop,
            Prefs prefs,
            int initialCols
    ) {
        if (ctx == null || appsByKey == null || appsByKey.isEmpty() || desktop == null) {
            return new Result(false, "", 0, 0, initialCols);
        }
        // Старый синхронный путь оставлен для кнопки «Импортировать» в настройках,
        // где пользователь сам инициировал действие. Автоматический импорт при
        // первом запуске идёт через readSystemLauncher() в фоновом потоке.
        Snapshot snap = readSystemLauncher(ctx, appsByKey, prefs);
        if (snap == null) return new Result(false, "", 0, 0, initialCols);
        return applyRows(snap, desktop, prefs, initialCols);
    }

    /**
     * Формирует упорядоченный список URI-кандидатов: известные XOS/Transsion + динамически
     * обнаруженные ContentProvider всех установленных в системе HOME-оболочек.
     */
    private static List<String[]> buildProviderCandidates(Context ctx) {
        List<String[]> out = new ArrayList<>();
        Set<String> seenUri = new LinkedHashSet<>();

        for (String[] p : KNOWN_PROVIDERS) {
            if (seenUri.add(p[0])) out.add(p);
        }

        try {
            PackageManager pm = ctx.getPackageManager();
            String selfPkg = ctx.getPackageName();
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> homes = pm.queryIntentActivities(homeIntent, 0);
            if (homes != null) {
                for (ResolveInfo ri : homes) {
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(selfPkg) || "android".equals(pkg)) continue;

                    String label = "Штатная оболочка";
                    try {
                        CharSequence cs = ri.loadLabel(pm);
                        if (cs != null && cs.length() > 0) label = cs.toString();
                    } catch (Throwable ignored) {
                    }

                    // Проверяем реальные ProviderInfo этого пакета
                    try {
                        PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS);
                        if (pi != null && pi.providers != null) {
                            for (ProviderInfo prov : pi.providers) {
                                if (prov == null || prov.authority == null || prov.authority.isEmpty()) continue;
                                String[] auths = prov.authority.split(";");
                                for (String auth : auths) {
                                    String a = auth.trim();
                                    if (a.isEmpty()) continue;
                                    String uri = "content://" + a + "/favorites?notify=false";
                                    if (seenUri.add(uri)) out.add(new String[]{uri, label});
                                }
                            }
                        }
                    } catch (Throwable t) {
                        Diagnostics.record("LayoutImporter.providers", t);
                    }

                    // Запасной стандартный суффикс .settings
                    String fallbackUri = "content://" + pkg + ".settings/favorites?notify=false";
                    if (seenUri.add(fallbackUri)) out.add(new String[]{fallbackUri, label});
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static final class RawRow {
        final long id;
        final int itemType;
        final long container;
        final int screen;
        final int cellX;
        final int cellY;
        final String title;
        final String intentUri;

        RawRow(long id, int itemType, long container, int screen, int cellX, int cellY,
               String title, String intentUri) {
            this.id = id;
            this.itemType = itemType;
            this.container = container;
            this.screen = screen;
            this.cellX = cellX;
            this.cellY = cellY;
            this.title = title;
            this.intentUri = intentUri;
        }
    }

    /** Снимок прочитанной базы: строки + уже сопоставленные ключи приложений. */
    public static final class Snapshot {
        final String sourceName;
        final List<RawRow> rows;
        final Map<String, String> byComponent;
        final Map<String, String> byPackage;
        final int initialCols;

        Snapshot(String sourceName, List<RawRow> rows, Map<String, String> byComponent,
                 Map<String, String> byPackage, int initialCols) {
            this.sourceName = sourceName;
            this.rows = rows;
            this.byComponent = byComponent;
            this.byPackage = byPackage;
            this.initialCols = initialCols;
        }

        boolean hasContent() {
            return rows != null && !rows.isEmpty();
        }
    }

    private static Snapshot readFavoritesCursor(
            Cursor c,
            String sourceName,
            Map<String, String> byComponent,
            Map<String, String> byPackage,
            int initialCols
    ) {
        int idxId = c.getColumnIndex("_id");
        int idxIntent = c.getColumnIndex("intent");
        int idxTitle = c.getColumnIndex("title");
        int idxType = c.getColumnIndex("itemType");
        int idxContainer = c.getColumnIndex("container");
        int idxScreen = c.getColumnIndex("screen");
        int idxCellX = c.getColumnIndex("cellX");
        int idxCellY = c.getColumnIndex("cellY");

        if (idxIntent < 0 || idxContainer < 0) {
            return null;
        }

        // Проход 1: вычитываем все строки и определяем реальную ширину сетки (4 или 5 столбцов)
        List<RawRow> rows = new ArrayList<>();
        int maxDesktopCellX = 0;

        while (rows.size() < MAX_IMPORT_ROWS && c.moveToNext()) {
            long id = (idxId >= 0) ? c.getLong(idxId) : -1L;
            int itemType = (idxType >= 0) ? c.getInt(idxType) : ITEM_TYPE_APPLICATION;
            long container = c.getLong(idxContainer);
            int screen = (idxScreen >= 0) ? c.getInt(idxScreen) : 0;
            int cellX = (idxCellX >= 0) ? c.getInt(idxCellX) : 0;
            int cellY = (idxCellY >= 0) ? c.getInt(idxCellY) : 0;
            String title = (idxTitle >= 0 && !c.isNull(idxTitle))
                    ? truncateForImport(c.getString(idxTitle), MAX_IMPORT_TEXT_LEN) : "";
            String intentUri = (!c.isNull(idxIntent))
                    ? truncateForImport(c.getString(idxIntent), MAX_IMPORT_TEXT_LEN * 8) : "";

            rows.add(new RawRow(id, itemType, container, screen, cellX, cellY, title, intentUri));
            boolean isImportableType = (itemType == ITEM_TYPE_APPLICATION
                    || itemType == ITEM_TYPE_SHORTCUT
                    || itemType == ITEM_TYPE_FOLDER);
            if (isImportableType && container == CONTAINER_DESKTOP && cellX > maxDesktopCellX) {
                maxDesktopCellX = cellX;
            }
        }

        return new Snapshot(sourceName, rows, byComponent, byPackage,
                detectGridColumns(maxDesktopCellX, initialCols));
    }

    /**
     * Применяет прочитанные строки к модели: раскладывает док, папки и страницы
     * по точным координатам (cellX, cellY). Только память, без ввода-вывода.
     */
    private static Result applyRows(
            Snapshot snap,
            DesktopModel desktop,
            Prefs prefs,
            int fallbackCols
    ) {
        final String sourceName = snap.sourceName;
        final List<RawRow> rows = snap.rows;
        final Map<String, String> byComponent = snap.byComponent;
        final Map<String, String> byPackage = snap.byPackage;
        int initialCols = (snap.initialCols > 0) ? snap.initialCols : fallbackCols;
        int effectiveCols = initialCols;

        // Проход 2: раскладываем док, папки и страницы по точным координатам
        class HotseatSlot {
            final int order;
            final String appKey;
            HotseatSlot(int order, String appKey) {
                this.order = order;
                this.appKey = appKey;
            }
        }
        class FolderHolder {
            final int screen;
            final int col;
            final int row;
            final String title;
            final List<String> apps = new ArrayList<>();
            FolderHolder(int screen, int col, int row, String title) {
                this.screen = screen;
                this.col = col;
                this.row = row;
                this.title = (title != null && !title.trim().isEmpty()) ? title.trim() : "Папка";
            }
        }

        List<HotseatSlot> hotseat = new ArrayList<>();
        Map<Long, FolderHolder> foldersById = new HashMap<>();
        TreeMap<Integer, List<DesktopItem>> desktopByScreen = new TreeMap<>();
        Set<String> placedOnDesktop = new HashSet<>();
        int importedApps = 0;

        // Сначала собираем все папки (чтобы потом гарантированно привязать к ним дочерние иконки)
        for (RawRow r : rows) {
            if (r.itemType == ITEM_TYPE_FOLDER && r.container == CONTAINER_DESKTOP && r.id >= 0) {
                int col = clampCol(r.cellX, effectiveCols);
                int row = clampRow(r.cellY);
                foldersById.put(r.id, new FolderHolder(Math.max(0, r.screen), col, row, r.title));
            }
        }

        // Затем раскладываем иконки: док, рабочий стол, содержимое папок
        for (RawRow r : rows) {
            if (r.itemType != ITEM_TYPE_APPLICATION && r.itemType != ITEM_TYPE_SHORTCUT) {
                continue;
            }
            String appKey = resolveKeyFromIntent(r.intentUri, byComponent, byPackage);
            if (appKey == null) continue;

            if (r.container == CONTAINER_HOTSEAT) {
                int slot = (r.screen >= 0) ? r.screen : r.cellX;
                hotseat.add(new HotseatSlot(slot, appKey));
                importedApps++;
            } else if (r.container == CONTAINER_DESKTOP) {
                int screen = Math.max(0, r.screen);
                int col = clampCol(r.cellX, effectiveCols);
                int row = clampRow(r.cellY);
                List<DesktopItem> list = desktopByScreen.get(screen);
                if (list == null) {
                    list = new ArrayList<>();
                    desktopByScreen.put(screen, list);
                }
                if (placedOnDesktop.add(appKey)) {
                    list.add(DesktopItem.appAt(appKey, col, row));
                    importedApps++;
                }
            } else if (r.container > 0) {
                FolderHolder fh = foldersById.get(r.container);
                if (fh != null && !fh.apps.contains(appKey)) {
                    fh.apps.add(appKey);
                    importedApps++;
                }
            }
        }

        int importedFolders = 0;
        int folderSeq = 1;
        for (FolderHolder fh : foldersById.values()) {
            if (fh.apps.isEmpty()) continue;
            List<DesktopItem> list = desktopByScreen.get(fh.screen);
            if (list == null) {
                list = new ArrayList<>();
                desktopByScreen.put(fh.screen, list);
            }
            list.add(DesktopItem.folderAt("imp_" + (folderSeq++), fh.title, fh.apps, fh.col, fh.row));
            importedFolders++;
        }

        if (importedApps < 2 && importedFolders == 0) {
            return new Result(false, sourceName, 0, 0, initialCols);
        }

        if (prefs != null && effectiveCols != prefs.getColumns()) {
            prefs.setColumns(effectiveCols);
        }

        Collections.sort(hotseat, new Comparator<HotseatSlot>() {
            @Override
            public int compare(HotseatSlot a, HotseatSlot b) {
                return Integer.compare(a.order, b.order);
            }
        });

        desktop.dock.clear();
        int maxDock = (prefs != null && !prefs.isShowDockDrawerBtn())
                ? DesktopModel.MAX_DOCK_APPS_EXTENDED
                : DesktopModel.MAX_DOCK_APPS;
        for (HotseatSlot hs : hotseat) {
            if (desktop.dock.size() >= maxDock) break;
            if (!desktop.dock.contains(hs.appKey)) {
                desktop.dock.add(hs.appKey);
            }
        }

        desktop.pages.clear();
        for (List<DesktopItem> pageItems : desktopByScreen.values()) {
            if (desktop.pages.size() >= DesktopModel.MAX_PAGES) break;
            desktop.pages.add(pageItems);
        }
        if (desktop.pages.isEmpty()) {
            desktop.pages.add(new ArrayList<>());
        }
        desktop.normalizeAllPages(effectiveCols, false);
        // Импорт — это тоже построенная раскладка: пустая страница после него
        // не должна считаться «ещё не построенной» и перестраиваться.
        desktop.markInitialized();
        desktop.save();

        return new Result(true, sourceName, importedApps, importedFolders, effectiveCols);
    }

    /** Чистая функция автовыбора числа столбцов (4 или 5) — покрывается unit-тестами. */
    /**
     * Обрезает строку, пришедшую из чужой базы. Возвращает пустую для null:
     * вызывающему не нужно проверять дважды.
     *
     * Отдельный метод, потому что данные чужие: провайдер может вернуть строку
     * любого размера (в том числе в несколько мегабайт), а она попадает в модель
     * рабочего стола и в JSON раскладки. Проверяется тестом
     * {@code Audit60Tests.importTruncatesForeignStrings()}.
     */
    static String truncateForImport(String value, int maxLen) {
        if (value == null) return "";
        if (maxLen <= 0) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    static int detectGridColumns(int maxDesktopCellX, int fallbackCols) {
        if (maxDesktopCellX >= 4) return 5;
        return (fallbackCols == 5) ? 5 : 4;
    }

    private static String resolveKeyFromIntent(
            String intentUri,
            Map<String, String> byComponent,
            Map<String, String> byPackage
    ) {
        if (intentUri == null || intentUri.isEmpty()) return null;
        try {
            Intent intent = Intent.parseUri(intentUri, 0);
            ComponentName cn = intent.getComponent();
            if (cn != null) {
                String exact = byComponent.get(cn.flattenToString());
                if (exact != null) return exact;
                String byPkg = byPackage.get(cn.getPackageName());
                if (byPkg != null) return byPkg;
            }
            String pkg = intent.getPackage();
            if (pkg != null) {
                return byPackage.get(pkg);
            }
        } catch (Throwable t) {
            Diagnostics.record("LayoutImporter.matchIntent", t);
        }
        return null;
    }

    /** Ограничивает колонку реальным числом столбцов: 5-я иконка при 4 столбцах не должна «уезжать» за сетку. */
    private static int clampCol(int cellX, int cols) {
        int c = Math.max(1, cols);
        if (cellX < 0) return 0;
        if (cellX >= c) return c - 1;
        return cellX;
    }

    private static int clampRow(int cellY) {
        if (cellY < 0) return 0;
        if (cellY >= DesktopModel.GRID_ROWS) return DesktopModel.GRID_ROWS - 1;
        return cellY;
    }
}
