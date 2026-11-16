package com.doxaiz.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Хранит раскладку рабочего стола Doxaiz со свободной сеткой (cols x GRID_ROWS):
 * нижний док + до 8 страниц + папки и свободные координаты (col, row) каждого значка.
 *
 * Doxaiz 3.4: двойная защита сохранности раскладки (SharedPreferences + атомарный файл
 * doxaiz_layout_guard.json) + автовосстановление ключей при смене Activity-класса приложения
 * без сдвига и пропажи иконок.
 */
public final class DesktopModel {
    public static final int MAX_DOCK_APPS = 4;
    public static final int MAX_DOCK_APPS_EXTENDED = 5;
    public static final int MAX_PAGES = 8;
    public static final int GRID_ROWS = 6;

    public static final int DROP_NONE = 0;
    public static final int DROP_MOVED = 1;
    public static final int DROP_SWAPPED = 2;
    public static final int DROP_FOLDER_CREATED = 3;
    public static final int DROP_ADDED_TO_FOLDER = 4;

    private static final String GUARD_FILE = "doxaiz_layout_guard.json";
    private static final ExecutorService GUARD_WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "doxaiz-layout-guard");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<String> pendingGuardJson = new AtomicReference<>(null);

    private final Context appContext;
    private final Prefs prefs;
    public final List<String> dock = new ArrayList<>();
    public final List<List<DesktopItem>> pages = new ArrayList<>();
    private int nextFolderSeq = 1;

    public DesktopModel(Prefs prefs) {
        this(null, prefs);
    }

    public DesktopModel(Context context, Prefs prefs) {
        this.appContext = (context != null) ? context.getApplicationContext() : null;
        this.prefs = prefs;
        load();
    }

    public boolean isInitialized() {
        return !pages.isEmpty();
    }

    /** Синхронизирован по той же причине, что и save(): перезагрузка и запись не должны пересекаться. */
    public synchronized void load() {
        dock.clear();
        pages.clear();
        String raw = (prefs != null) ? prefs.getDesktopJson() : null;
        boolean recoveredFromGuard = false;
        if (raw == null || raw.isEmpty()) {
            String guard = readGuardFile();
            if (guard != null && !guard.isEmpty()) {
                raw = guard;
                recoveredFromGuard = true;
            }
        }
        if (raw == null || raw.isEmpty()) {
            return;
        }
        if (!parseLayoutJson(raw)) {
            // Если SharedPreferences был повреждён — пробуем резервную копию на диске
            String guard = readGuardFile();
            if (guard != null && !guard.isEmpty() && !guard.equals(raw) && parseLayoutJson(guard)) {
                if (prefs != null) prefs.setDesktopJson(guard);
            }
            return;
        }
        if (recoveredFromGuard && !pages.isEmpty() && prefs != null) {
            prefs.setDesktopJson(raw);
        }
    }

    private boolean parseLayoutJson(String raw) {
        dock.clear();
        pages.clear();
        try {
            JSONObject root = new JSONObject(raw);
            nextFolderSeq = root.optInt("seq", 1);

            JSONArray dockArr = root.optJSONArray("dock");
            if (dockArr != null) {
                for (int i = 0; i < dockArr.length(); i++) {
                    String k = dockArr.optString(i, "");
                    if (!k.isEmpty() && !dock.contains(k)) {
                        dock.add(k);
                    }
                }
            }

            JSONArray pagesArr = root.optJSONArray("pages");
            if (pagesArr != null) {
                for (int p = 0; p < pagesArr.length(); p++) {
                    JSONArray pageJson = pagesArr.optJSONArray(p);
                    List<DesktopItem> page = new ArrayList<>();
                    if (pageJson != null) {
                        for (int i = 0; i < pageJson.length(); i++) {
                            JSONObject obj = pageJson.optJSONObject(i);
                            if (obj == null) continue;
                            String type = obj.optString("type", "app");
                            int col = obj.optInt("col", -1);
                            int row = obj.optInt("row", -1);
                            if ("folder".equals(type)) {
                                String fid = obj.optString("id", "f_" + (nextFolderSeq++));
                                String name = obj.optString("name", "Папка");
                                List<String> fApps = new ArrayList<>();
                                JSONArray fa = obj.optJSONArray("apps");
                                if (fa != null) {
                                    for (int j = 0; j < fa.length(); j++) {
                                        String ak = fa.optString(j, "");
                                        if (!ak.isEmpty()) fApps.add(ak);
                                    }
                                }
                                page.add(DesktopItem.folderAt(fid, name, fApps, col, row));
                            } else {
                                String key = obj.optString("key", "");
                                if (!key.isEmpty()) {
                                    page.add(DesktopItem.appAt(key, col, row));
                                }
                            }
                        }
                    }
                    pages.add(page);
                }
            }
            return true;
        } catch (Throwable ignored) {
            dock.clear();
            pages.clear();
            return false;
        }
    }

    /**
     * Сериализация и запись раскладки. Метод синхронизирован: save() вызывается и с
     * главного потока, и из фоновых (импорт бэкапа, guard-writer), а раньше два
     * конкурентных вызова могли перемешать итерацию по pages/dock и записать
     * повреждённый или устаревший JSON поверх свежего.
     */
    public synchronized void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("seq", nextFolderSeq);

            JSONArray dockArr = new JSONArray();
            for (String k : dock) dockArr.put(k);
            root.put("dock", dockArr);

            JSONArray pagesArr = new JSONArray();
            for (List<DesktopItem> page : pages) {
                JSONArray pageJson = new JSONArray();
                for (DesktopItem item : page) {
                    JSONObject obj = new JSONObject();
                    obj.put("col", item.col);
                    obj.put("row", item.row);
                    if (item.type == DesktopItem.TYPE_FOLDER) {
                        obj.put("type", "folder");
                        obj.put("id", item.folderId);
                        obj.put("name", item.folderName);
                        JSONArray fa = new JSONArray();
                        for (String ak : item.folderApps) fa.put(ak);
                        obj.put("apps", fa);
                    } else {
                        obj.put("type", "app");
                        obj.put("key", item.appKey);
                    }
                    pageJson.put(obj);
                }
                pagesArr.put(pageJson);
            }
            root.put("pages", pagesArr);
            String json = root.toString();
            if (prefs != null) prefs.setDesktopJson(json);
            scheduleGuardWrite(json);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Защита от исчезновения и сдвига иконок при обновлении приложений (когда разработчик
     * переименовывает стартовый Activity-класс, а имя пакета остаётся тем же):
     * обновляет ключ прямо на месте, сохраняя точную страницу и клетку (col, row).
     */
    public boolean reconcileRenamedActivities(Map<String, AppEntry> appsByKey) {
        if (appsByKey == null || appsByKey.isEmpty()) return false;
        Map<String, String> pkgSerialToKey = new HashMap<>();
        for (AppEntry e : appsByKey.values()) {
            String ps = e.packageName + "#" + e.userSerial;
            if (!pkgSerialToKey.containsKey(ps)) {
                pkgSerialToKey.put(ps, e.key);
            }
        }
        boolean changed = false;

        for (int i = 0; i < dock.size(); i++) {
            String oldKey = dock.get(i);
            if (!appsByKey.containsKey(oldKey)) {
                String remapped = remapKey(oldKey, pkgSerialToKey);
                if (remapped != null && !dock.contains(remapped)) {
                    dock.set(i, remapped);
                    changed = true;
                }
            }
        }

        for (List<DesktopItem> page : pages) {
            for (DesktopItem item : page) {
                if (item.type == DesktopItem.TYPE_APP) {
                    if (!appsByKey.containsKey(item.appKey)) {
                        String remapped = remapKey(item.appKey, pkgSerialToKey);
                        if (remapped != null) {
                            item.appKey = remapped;
                            changed = true;
                        }
                    }
                } else if (item.type == DesktopItem.TYPE_FOLDER) {
                    for (int i = 0; i < item.folderApps.size(); i++) {
                        String oldKey = item.folderApps.get(i);
                        if (!appsByKey.containsKey(oldKey)) {
                            String remapped = remapKey(oldKey, pkgSerialToKey);
                            if (remapped != null && !item.folderApps.contains(remapped)) {
                                item.folderApps.set(i, remapped);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        if (changed) save();
        return changed;
    }

    static String remapKey(String oldKey, Map<String, String> pkgSerialToKey) {
        if (oldKey == null || oldKey.isEmpty()) return null;
        int slash = oldKey.indexOf('/');
        int hash = oldKey.lastIndexOf('#');
        if (slash <= 0 || hash <= slash) return null;
        String pkg = oldKey.substring(0, slash);
        String serial = oldKey.substring(hash + 1);
        return pkgSerialToKey.get(pkg + "#" + serial);
    }

    /**
     * Собирает список ключей приложений на доке и заданной странице для мгновенного
     * прогрева дискового кэша иконок в ОЗУ.
     */
    public List<String> collectPriorityIconKeys(int pageIndex) {
        List<String> keys = new ArrayList<>();
        keys.addAll(dock);
        if (pageIndex >= 0 && pageIndex < pages.size()) {
            for (DesktopItem item : pages.get(pageIndex)) {
                if (item.type == DesktopItem.TYPE_APP) {
                    keys.add(item.appKey);
                } else if (item.type == DesktopItem.TYPE_FOLDER) {
                    for (int i = 0; i < Math.min(9, item.folderApps.size()); i++) {
                        keys.add(item.folderApps.get(i));
                    }
                }
            }
        }
        return keys;
    }

    private void scheduleGuardWrite(String json) {
        if (appContext == null || json == null || json.isEmpty()) return;
        // Дедуплицируем частые сохранения и выносим атомарную запись с UI-потока
        boolean shouldSubmit = (pendingGuardJson.getAndSet(json) == null);
        if (shouldSubmit) {
            try {
                GUARD_WRITER.execute(() -> {
                    String latest;
                    while ((latest = pendingGuardJson.getAndSet(null)) != null) {
                        writeGuardFile(latest);
                    }
                });
            } catch (Throwable ignored) {
                String fallback = pendingGuardJson.getAndSet(null);
                if (fallback != null) writeGuardFile(fallback);
            }
        }
    }

    private synchronized void writeGuardFile(String json) {
        if (appContext == null || json == null || json.isEmpty()) return;
        try {
            File target = new File(appContext.getFilesDir(), GUARD_FILE);
            File tmp = new File(appContext.getFilesDir(), GUARD_FILE + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                try { fos.getFD().sync(); } catch (Throwable ignored) {}
            }
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private String readGuardFile() {
        if (appContext == null) return null;
        try {
            File target = new File(appContext.getFilesDir(), GUARD_FILE);
            if (!target.isFile() || target.length() <= 2L || target.length() > BackupHelper.MAX_BACKUP_BYTES) {
                return null;
            }
            try (FileInputStream fis = new FileInputStream(target);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Стартовая раскладка со свободной сеткой:
     * - Док: 4 главных приложения (Телефон, Сообщения, Браузер, Камера)
     * - Страница 1: 8 основных приложений в нижних 2 рядах (удобно одной рукой + пустое место сверху)
     * - Страница 2: папка «Инструменты» + остальные приложения сверху вниз
     */
    public void buildInitialLayout(List<AppEntry> visible, int cols, boolean bottomAlignPage0) {
        dock.clear();
        pages.clear();
        Set<String> used = new HashSet<>();

        String[][] dockGroups = new String[][]{
                {"dialer", "phone", "contacts", "телефон"},
                {"messaging", "mms", "sms", "сообщения"},
                {"chrome", "browser", "yandex.browser", "firefox", "opera", "браузер"},
                {"camera", "камера"}
        };
        for (String[] g : dockGroups) {
            AppEntry e = findFirst(visible, used, g);
            if (e != null) {
                dock.add(e.key);
                used.add(e.key);
            }
        }
        for (AppEntry e : visible) {
            if (dock.size() >= MAX_DOCK_APPS) break;
            if (e.isMainProfile && !used.contains(e.key)) {
                dock.add(e.key);
                used.add(e.key);
            }
        }

        // Страница 1
        List<DesktopItem> page1 = new ArrayList<>();
        String[][] homeGroups = new String[][]{
                {"telegram", "телеграм"},
                {"whatsapp", "viber", "max"},
                {"vkontakte", "vk"},
                {"gallery", "photos", "галерея", "фото"},
                {"youtube", "vkvideo", "rutube", "music", "музыка"},
                {"maps", "2gis", "navi", "карты"},
                {"vending", "rustore", "store", "market", "play"},
                {"settings", "настройки"}
        };
        int maxP1 = cols * 2;
        for (String[] g : homeGroups) {
            if (page1.size() >= maxP1) break;
            AppEntry e = findFirst(visible, used, g);
            if (e != null) {
                page1.add(DesktopItem.app(e.key));
                used.add(e.key);
            }
        }
        for (AppEntry e : visible) {
            if (page1.size() >= maxP1) break;
            if (e.isMainProfile && !used.contains(e.key)) {
                page1.add(DesktopItem.app(e.key));
                used.add(e.key);
            }
        }
        assignSequentialCoords(page1, cols, bottomAlignPage0);
        pages.add(page1);

        // Страница 2: папка «Инструменты» + остальные приложения
        List<DesktopItem> page2 = new ArrayList<>();
        List<String> toolsKeys = new ArrayList<>();
        String[][] toolGroups = new String[][]{
                {"clock", "deskclock", "alarm", "часы"},
                {"calculator", "calc", "калькулятор"},
                {"file", "explorer", "documents", "файлы"},
                {"calendar", "календарь"},
                {"note", "keep", "заметки"},
                {"recorder", "sound", "диктофон"}
        };
        for (String[] g : toolGroups) {
            AppEntry e = findFirst(visible, used, g);
            if (e != null) {
                toolsKeys.add(e.key);
                used.add(e.key);
            }
        }
        if (toolsKeys.isEmpty()) {
            for (AppEntry e : visible) {
                if (toolsKeys.size() >= 3) break;
                if (e.isMainProfile && !used.contains(e.key)) {
                    toolsKeys.add(e.key);
                    used.add(e.key);
                }
            }
        }
        if (!toolsKeys.isEmpty()) {
            page2.add(DesktopItem.folder("f_" + (nextFolderSeq++), "Инструменты", toolsKeys));
        }

        int maxP2 = Math.min(16, cols * GRID_ROWS);
        for (AppEntry e : visible) {
            if (page2.size() >= maxP2) break;
            if (!used.contains(e.key)) {
                page2.add(DesktopItem.app(e.key));
                used.add(e.key);
            }
        }
        assignSequentialCoords(page2, cols, false);
        pages.add(page2);
        save();
    }

    private void assignSequentialCoords(List<DesktopItem> items, int cols, boolean bottomAlign) {
        int count = items.size();
        int rowsNeeded = Math.max(1, (count + cols - 1) / cols);
        int startRow = bottomAlign ? Math.max(0, GRID_ROWS - rowsNeeded) : 0;
        for (int i = 0; i < count; i++) {
            int r = startRow + (i / cols);
            int c = i % cols;
            if (r >= GRID_ROWS) r = GRID_ROWS - 1;
            items.get(i).col = c;
            items.get(i).row = r;
        }
    }

    /**
     * Перевыравнивает элементы 1-й страницы (вверх или вниз) при смене настройки выравнивания.
     */
    public void realignPage0(int cols, boolean bottomAlign) {
        if (pages.isEmpty()) return;
        List<DesktopItem> p0 = pages.get(0);
        if (p0.isEmpty()) return;
        assignSequentialCoords(p0, cols, bottomAlign);
        save();
    }

    /**
     * Гарантирует корректные непересекающиеся координаты (col, row) для всех элементов
     * (например, после обновления с v1.1 или переключения 5 <-> 4 колонок).
     */
    public void normalizeAllPages(int cols, boolean bottomAlignPage0) {
        boolean changed = false;
        if (pages.isEmpty()) {
            pages.add(new ArrayList<>());
            changed = true;
        }
        for (int p = 0; p < pages.size(); p++) {
            List<DesktopItem> page = pages.get(p);
            boolean[][] occ = new boolean[GRID_ROWS][cols];
            List<DesktopItem> needsPlacement = new ArrayList<>();

            for (DesktopItem it : page) {
                if (it.col >= 0 && it.col < cols && it.row >= 0 && it.row < GRID_ROWS
                        && !occ[it.row][it.col]) {
                    occ[it.row][it.col] = true;
                } else {
                    needsPlacement.add(it);
                }
            }

            if (!needsPlacement.isEmpty()) {
                changed = true;
                boolean preferBottom = (p == 0 && bottomAlignPage0);
                // Если все элементы страницы без координат (миграция со старой версии) — раскладываем ровными рядами
                if (needsPlacement.size() == page.size() && page.size() <= cols * GRID_ROWS) {
                    assignSequentialCoords(page, cols, preferBottom);
                } else {
                    List<DesktopItem> overflow = new ArrayList<>();
                    for (DesktopItem it : needsPlacement) {
                        int[] free = findFirstFreeInMatrix(occ, cols, preferBottom);
                        if (free != null) {
                            it.col = free[0];
                            it.row = free[1];
                            occ[free[1]][free[0]] = true;
                        } else {
                            it.col = -1;
                            it.row = -1;
                        }
                    }
                    // Выносим переполненные элементы без наложения в угол
                    for (int i = page.size() - 1; i >= 0; i--) {
                        DesktopItem it = page.get(i);
                        if (it.col < 0 || it.row < 0) {
                            overflow.add(0, page.remove(i));
                        }
                    }
                    if (!overflow.isEmpty()) {
                        if (p + 1 < pages.size()) {
                            pages.get(p + 1).addAll(overflow);
                        } else if (pages.size() < MAX_PAGES) {
                            pages.add(new ArrayList<>(overflow));
                        } else {
                            // Достигнут лимит MAX_PAGES: раскладываем по свободным ячейкам других страниц
                            for (DesktopItem ov : overflow) {
                                boolean placed = false;
                                for (int other = 0; other < pages.size(); other++) {
                                    if (other == p) continue;
                                    int[] freeOther = findFirstEmptyCell(other, cols, other == 0 && bottomAlignPage0);
                                    if (freeOther != null) {
                                        ov.col = freeOther[0];
                                        ov.row = freeOther[1];
                                        pages.get(other).add(ov);
                                        placed = true;
                                        break;
                                    }
                                }
                                if (!placed) {
                                    // Все MAX_PAGES страниц заполнены до предела — сохраняем в списке без потери
                                    ov.col = cols - 1;
                                    ov.row = GRID_ROWS - 1;
                                    page.add(ov);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (changed) save();
    }

    private int[] findFirstFreeInMatrix(boolean[][] occ, int cols, boolean preferBottom) {
        if (preferBottom) {
            for (int r = GRID_ROWS - 1; r >= 0; r--) {
                for (int c = 0; c < cols; c++) {
                    if (!occ[r][c]) return new int[]{c, r};
                }
            }
        } else {
            for (int r = 0; r < GRID_ROWS; r++) {
                for (int c = 0; c < cols; c++) {
                    if (!occ[r][c]) return new int[]{c, r};
                }
            }
        }
        return null;
    }

    public int[] findFirstEmptyCell(int pageIndex, int cols, boolean preferBottom) {
        if (pageIndex < 0 || pageIndex >= pages.size()) return new int[]{0, 0};
        boolean[][] occ = new boolean[GRID_ROWS][cols];
        for (DesktopItem it : pages.get(pageIndex)) {
            if (it.col >= 0 && it.col < cols && it.row >= 0 && it.row < GRID_ROWS) {
                occ[it.row][it.col] = true;
            }
        }
        return findFirstFreeInMatrix(occ, cols, preferBottom);
    }

    public int findItemIndexAt(int pageIndex, int col, int row) {
        if (pageIndex < 0 || pageIndex >= pages.size()) return -1;
        List<DesktopItem> page = pages.get(pageIndex);
        for (int i = 0; i < page.size(); i++) {
            DesktopItem it = page.get(i);
            if (it.col == col && it.row == row) return i;
        }
        return -1;
    }

    /**
     * Выполняет Drag & Drop элемента в целевую клетку (toPage, toCol, toRow):
     * - Пустая клетка -> переносит туда элемент
     * - Приложение поверх приложения -> мгновенно создаёт папку из обоих
     * - Приложение поверх папки -> добавляет в папку
     * - Папка поверх элемента -> меняет их местами
     */
    public int dropItemOnCell(int fromPage, int fromItemIdx, int toPage, int toCol, int toRow, int cols) {
        if (fromPage < 0 || fromPage >= pages.size()) return DROP_NONE;
        List<DesktopItem> srcList = pages.get(fromPage);
        if (fromItemIdx < 0 || fromItemIdx >= srcList.size()) return DROP_NONE;
        if (toPage < 0 || toPage >= pages.size()) return DROP_NONE;
        if (toCol < 0 || toCol >= cols || toRow < 0 || toRow >= GRID_ROWS) return DROP_NONE;

        DesktopItem dragged = srcList.get(fromItemIdx);
        if (fromPage == toPage && dragged.col == toCol && dragged.row == toRow) {
            return DROP_NONE;
        }

        int targetIdx = findItemIndexAt(toPage, toCol, toRow);
        if (targetIdx < 0) {
            // Перенос в пустую ячейку
            if (fromPage != toPage) {
                srcList.remove(fromItemIdx);
                pages.get(toPage).add(dragged);
            }
            dragged.col = toCol;
            dragged.row = toRow;
            save();
            return DROP_MOVED;
        }

        List<DesktopItem> dstList = pages.get(toPage);
        DesktopItem target = dstList.get(targetIdx);
        if (target == dragged) return DROP_NONE;

        if (dragged.type == DesktopItem.TYPE_APP && target.type == DesktopItem.TYPE_FOLDER) {
            // Добавляем приложение в существующую папку
            if (!target.folderApps.contains(dragged.appKey)) {
                target.folderApps.add(dragged.appKey);
            }
            srcList.remove(fromItemIdx);
            save();
            return DROP_ADDED_TO_FOLDER;
        }

        if (dragged.type == DesktopItem.TYPE_APP && target.type == DesktopItem.TYPE_APP) {
            if (dragged.appKey != null && dragged.appKey.equals(target.appKey)) {
                // Слияние дубликата одного и того же приложения без создания папки из 1 иконки
                srcList.remove(fromItemIdx);
                save();
                return DROP_MOVED;
            }
            // Объединяем два разных приложения в новую папку
            List<String> keys = new ArrayList<>();
            keys.add(target.appKey);
            keys.add(dragged.appKey);
            DesktopItem folder = DesktopItem.folderAt(
                    "f_" + (nextFolderSeq++), "Папка", keys, toCol, toRow
            );
            dstList.set(targetIdx, folder);
            srcList.remove(fromItemIdx);
            save();
            return DROP_FOLDER_CREATED;
        }

        // Папка на приложение или на другую папку -> обмен местами
        int oldCol = dragged.col;
        int oldRow = dragged.row;
        if (fromPage == toPage) {
            dragged.col = toCol;
            dragged.row = toRow;
            target.col = oldCol;
            target.row = oldRow;
        } else {
            srcList.set(fromItemIdx, target);
            dstList.set(targetIdx, dragged);
            dragged.col = toCol;
            dragged.row = toRow;
            target.col = oldCol;
            target.row = oldRow;
        }
        save();
        return DROP_SWAPPED;
    }

    /**
     * Doxaiz 4.1: Прямое перетаскивание приложения из «Все приложения» в конкретную клетку рабочего стола.
     * - Пустая клетка -> размещает иконку
     * - Папка -> добавляет приложение в папку
     * - Другое приложение -> объединяет оба в новую папку
     */
    public int dropExternalAppOnCell(String appKey, int toPage, int toCol, int toRow, int cols) {
        if (appKey == null || appKey.isEmpty()) return DROP_NONE;
        if (toPage < 0 || toPage >= pages.size()) return DROP_NONE;
        if (toCol < 0 || toCol >= cols || toRow < 0 || toRow >= GRID_ROWS) return DROP_NONE;

        List<DesktopItem> dstList = pages.get(toPage);
        int targetIdx = findItemIndexAt(toPage, toCol, toRow);
        if (targetIdx < 0) {
            dstList.add(DesktopItem.appAt(appKey, toCol, toRow));
            save();
            return DROP_MOVED;
        }

        DesktopItem target = dstList.get(targetIdx);
        if (target.type == DesktopItem.TYPE_FOLDER) {
            if (!target.folderApps.contains(appKey)) {
                target.folderApps.add(appKey);
                save();
                return DROP_ADDED_TO_FOLDER;
            }
            return DROP_NONE;
        }

        if (target.type == DesktopItem.TYPE_APP) {
            if (appKey.equals(target.appKey)) return DROP_NONE;
            List<String> keys = new ArrayList<>();
            keys.add(target.appKey);
            keys.add(appKey);
            DesktopItem folder = DesktopItem.folderAt(
                    "f_" + (nextFolderSeq++), "Папка", keys, toCol, toRow
            );
            dstList.set(targetIdx, folder);
            save();
            return DROP_FOLDER_CREATED;
        }
        return DROP_NONE;
    }

    /**
     * Раскладывает все видимые приложения по свободным ячейкам страниц.
     */
    public void spreadAllAppsAcrossPages(List<AppEntry> visible, int cols) {
        Set<String> inDock = new HashSet<>(dock);
        pages.clear();
        int cap = cols * GRID_ROWS;
        List<DesktopItem> cur = new ArrayList<>();
        for (AppEntry e : visible) {
            if (inDock.contains(e.key)) continue;
            if (cur.size() >= cap && pages.size() < MAX_PAGES - 1) {
                assignSequentialCoords(cur, cols, false);
                pages.add(cur);
                cur = new ArrayList<>();
            }
            if (cur.size() < cap) {
                cur.add(DesktopItem.app(e.key));
            }
        }
        assignSequentialCoords(cur, cols, false);
        pages.add(cur);
        if (pages.size() < 2) {
            pages.add(new ArrayList<>());
        }
        save();
    }

    private AppEntry findFirst(List<AppEntry> list, Set<String> used, String[] keywords) {
        for (String kw : keywords) {
            for (AppEntry e : list) {
                if (!e.isMainProfile || used.contains(e.key)) continue;
                String hay = (e.packageName + " " + e.originalLabel).toLowerCase(Locale.ROOT);
                if (hay.contains(kw)) return e;
            }
        }
        return null;
    }

    public int addPage() {
        if (pages.size() >= MAX_PAGES) return pages.size() - 1;
        pages.add(new ArrayList<>());
        save();
        return pages.size() - 1;
    }

    public boolean removePage(int pageIndex) {
        if (pages.size() <= 1 || pageIndex < 0 || pageIndex >= pages.size()) return false;
        pages.remove(pageIndex);
        save();
        return true;
    }

    /**
     * Убирает автоматически созданную при перетаскивании пустую последнюю страницу,
     * если пользователь в итоге ничего на неё не положил (как в AOSP Launcher3).
     */
    public boolean pruneTrailingEmptyPage() {
        if (pages.size() <= 1) return false;
        int last = pages.size() - 1;
        if (pages.get(last).isEmpty()) {
            pages.remove(last);
            save();
            return true;
        }
        return false;
    }

    /**
     * Корректно пересчитывает индекс главного экрана после удаления страницы removedPage.
     */
    public static int adjustHomePageAfterRemoval(int oldHome, int removedPage, int newPageCount) {
        if (newPageCount <= 1) return 0;
        int next = (removedPage < oldHome) ? (oldHome - 1) : oldHome;
        return Math.max(0, Math.min(next, newPageCount - 1));
    }

    public boolean containsAppAnywhere(String appKey) {
        if (dock.contains(appKey)) return true;
        for (List<DesktopItem> p : pages) {
            for (DesktopItem item : p) {
                if (item.type == DesktopItem.TYPE_APP && appKey.equals(item.appKey)) return true;
                if (item.type == DesktopItem.TYPE_FOLDER && item.folderApps.contains(appKey)) return true;
            }
        }
        return false;
    }

    public boolean addAppToPage(int pageIndex, String appKey, int cols, boolean preferBottom) {
        return addAppToPageAt(pageIndex, appKey, -1, -1, cols, preferBottom);
    }

    public boolean addAppToPageAt(int pageIndex, String appKey, int col, int row, int cols, boolean preferBottom) {
        if (pages.isEmpty()) pages.add(new ArrayList<>());
        int idx = Math.max(0, Math.min(pageIndex, pages.size() - 1));
        List<DesktopItem> p = pages.get(idx);
        for (DesktopItem it : p) {
            if (it.type == DesktopItem.TYPE_APP && appKey.equals(it.appKey)) return true;
        }
        int targetCol = col;
        int targetRow = row;
        if (targetCol < 0 || targetRow < 0 || targetCol >= cols || targetRow >= GRID_ROWS
                || findItemIndexAt(idx, targetCol, targetRow) >= 0) {
            int[] free = findFirstEmptyCell(idx, cols, preferBottom);
            if (free == null) {
                if (pages.size() < MAX_PAGES) {
                    idx = addPage();
                    p = pages.get(idx);
                    targetCol = 0;
                    targetRow = 0;
                } else {
                    return false;
                }
            } else {
                targetCol = free[0];
                targetRow = free[1];
            }
        }
        p.add(DesktopItem.appAt(appKey, targetCol, targetRow));
        save();
        return true;
    }

    public void removeItem(int pageIndex, int itemIndex) {
        if (pageIndex < 0 || pageIndex >= pages.size()) return;
        List<DesktopItem> p = pages.get(pageIndex);
        if (itemIndex < 0 || itemIndex >= p.size()) return;
        p.remove(itemIndex);
        save();
    }

    public int moveItemToPage(int fromPage, int itemIndex, int toPage, int cols, boolean preferBottomPage0) {
        if (fromPage < 0 || fromPage >= pages.size()) return fromPage;
        List<DesktopItem> src = pages.get(fromPage);
        if (itemIndex < 0 || itemIndex >= src.size()) return fromPage;
        if (toPage == pages.size() && pages.size() < MAX_PAGES) {
            pages.add(new ArrayList<>());
        }
        if (toPage < 0 || toPage >= pages.size() || toPage == fromPage) return fromPage;
        int[] free = findFirstEmptyCell(toPage, cols, toPage == 0 && preferBottomPage0);
        if (free == null) return fromPage;
        DesktopItem item = src.remove(itemIndex);
        item.col = free[0];
        item.row = free[1];
        pages.get(toPage).add(item);
        save();
        return toPage;
    }

    public DesktopItem createFolderAt(int pageIndex, String name, List<String> initialKeys,
                                      int col, int row, int cols, boolean preferBottom) {
        if (pages.isEmpty()) pages.add(new ArrayList<>());
        int idx = Math.max(0, Math.min(pageIndex, pages.size() - 1));
        int tc = col;
        int tr = row;
        if (tc < 0 || tr < 0 || tc >= cols || tr >= GRID_ROWS || findItemIndexAt(idx, tc, tr) >= 0) {
            int[] free = findFirstEmptyCell(idx, cols, preferBottom);
            if (free != null) {
                tc = free[0];
                tr = free[1];
            } else {
                tc = 0;
                tr = 0;
            }
        }
        DesktopItem folder = DesktopItem.folderAt("f_" + (nextFolderSeq++), name, initialKeys, tc, tr);
        pages.get(idx).add(folder);
        save();
        return folder;
    }

    public DesktopItem findFolder(int pageIndex, String folderId) {
        if (pageIndex < 0 || pageIndex >= pages.size() || folderId == null) return null;
        for (DesktopItem item : pages.get(pageIndex)) {
            if (item.type == DesktopItem.TYPE_FOLDER && folderId.equals(item.folderId)) {
                return item;
            }
        }
        return null;
    }

    public List<DesktopItem> getFoldersOnPage(int pageIndex) {
        List<DesktopItem> out = new ArrayList<>();
        if (pageIndex < 0 || pageIndex >= pages.size()) return out;
        for (DesktopItem item : pages.get(pageIndex)) {
            if (item.type == DesktopItem.TYPE_FOLDER) out.add(item);
        }
        return out;
    }

    public void addAppToFolder(int pageIndex, String folderId, String appKey) {
        DesktopItem folder = findFolder(pageIndex, folderId);
        if (folder == null) return;
        if (!folder.folderApps.contains(appKey)) {
            folder.folderApps.add(appKey);
            save();
        }
    }

    public void removeAppFromFolder(int pageIndex, String folderId, String appKey,
                                    boolean placeOnPage, int cols, boolean preferBottom) {
        DesktopItem folder = findFolder(pageIndex, folderId);
        if (folder == null) return;
        if (folder.folderApps.remove(appKey)) {
            if (folder.folderApps.isEmpty() && pageIndex >= 0 && pageIndex < pages.size()) {
                pages.get(pageIndex).remove(folder);
            }
            if (placeOnPage) {
                addAppToPage(pageIndex, appKey, cols, preferBottom);
            } else {
                save();
            }
        }
    }

    public void moveAppInFolder(int pageIndex, String folderId, String appKey, int delta) {
        DesktopItem folder = findFolder(pageIndex, folderId);
        if (folder == null) return;
        int idx = folder.folderApps.indexOf(appKey);
        int target = idx + delta;
        if (idx < 0 || target < 0 || target >= folder.folderApps.size()) return;
        folder.folderApps.remove(idx);
        folder.folderApps.add(target, appKey);
        save();
    }

    public void renameFolder(int pageIndex, String folderId, String newName) {
        DesktopItem folder = findFolder(pageIndex, folderId);
        if (folder == null) return;
        folder.folderName = (newName != null && !newName.trim().isEmpty()) ? newName.trim() : "Папка";
        save();
    }

    public void dissolveFolder(int pageIndex, String folderId, boolean unpackApps, int cols, boolean preferBottom) {
        if (pageIndex < 0 || pageIndex >= pages.size()) return;
        List<DesktopItem> p = pages.get(pageIndex);
        for (int i = 0; i < p.size(); i++) {
            DesktopItem it = p.get(i);
            if (it.type == DesktopItem.TYPE_FOLDER && folderId.equals(it.folderId)) {
                int fc = it.col;
                int fr = it.row;
                p.remove(i);
                if (unpackApps) {
                    boolean first = true;
                    for (String k : it.folderApps) {
                        if (first) {
                            addAppToPageAt(pageIndex, k, fc, fr, cols, preferBottom);
                            first = false;
                        } else {
                            addAppToPage(pageIndex, k, cols, preferBottom);
                        }
                    }
                }
                save();
                return;
            }
        }
    }

    public boolean addToDock(String appKey, int maxDockApps) {
        if (dock.contains(appKey)) return true;
        if (dock.size() >= maxDockApps) return false;
        dock.add(appKey);
        save();
        return true;
    }

    public void replaceAll(List<String> newDock, List<List<DesktopItem>> newPages) {
        dock.clear();
        if (newDock != null) dock.addAll(newDock);
        pages.clear();
        if (newPages != null) pages.addAll(newPages);
        if (pages.isEmpty()) pages.add(new ArrayList<>());
        save();
    }

    public void removeFromDock(String appKey) {
        if (dock.remove(appKey)) save();
    }

    public void moveInDock(String appKey, int delta) {
        int idx = dock.indexOf(appKey);
        int target = idx + delta;
        if (idx < 0 || target < 0 || target >= dock.size()) return;
        dock.remove(idx);
        dock.add(target, appKey);
        save();
    }

    public void purgeAppKey(String appKey) {
        boolean changed = dock.remove(appKey);
        for (List<DesktopItem> p : pages) {
            for (int i = p.size() - 1; i >= 0; i--) {
                DesktopItem it = p.get(i);
                if (it.type == DesktopItem.TYPE_APP && appKey.equals(it.appKey)) {
                    p.remove(i);
                    changed = true;
                } else if (it.type == DesktopItem.TYPE_FOLDER && it.folderApps.remove(appKey)) {
                    if (it.folderApps.isEmpty()) {
                        p.remove(i);
                    }
                    changed = true;
                }
            }
        }
        if (changed) save();
    }

    /**
     * Точечно убирает с рабочего стола, из дока и папок ярлыки окончательно удалённого пакета
     * для конкретного профиля пользователя (userSerial), не затрагивая остальные иконки и координаты.
     */
    public boolean purgePackageForUser(String packageName, long userSerial) {
        if (packageName == null || packageName.isEmpty()) return false;
        String prefix = packageName + "/";
        String suffix = "#" + userSerial;
        boolean changed = false;

        for (int i = dock.size() - 1; i >= 0; i--) {
            String k = dock.get(i);
            if (k != null && k.startsWith(prefix) && k.endsWith(suffix)) {
                dock.remove(i);
                changed = true;
            }
        }
        for (List<DesktopItem> p : pages) {
            for (int i = p.size() - 1; i >= 0; i--) {
                DesktopItem it = p.get(i);
                if (it.type == DesktopItem.TYPE_APP) {
                    if (it.appKey != null && it.appKey.startsWith(prefix) && it.appKey.endsWith(suffix)) {
                        p.remove(i);
                        changed = true;
                    }
                } else if (it.type == DesktopItem.TYPE_FOLDER) {
                    boolean fChanged = false;
                    for (int j = it.folderApps.size() - 1; j >= 0; j--) {
                        String k = it.folderApps.get(j);
                        if (k != null && k.startsWith(prefix) && k.endsWith(suffix)) {
                            it.folderApps.remove(j);
                            fChanged = true;
                        }
                    }
                    if (fChanged) {
                        if (it.folderApps.isEmpty()) {
                            p.remove(i);
                        }
                        changed = true;
                    }
                }
            }
        }
        if (changed) save();
        return changed;
    }

    /**
     * Удаляет ссылки на удалённые с телефона приложения, чтобы они не занимали невидимые ячейки.
     */
    public void pruneMissingApps(Set<String> existingKeys) {
        if (existingKeys == null || existingKeys.size() < 3) return;
        boolean changed = false;
        for (int i = dock.size() - 1; i >= 0; i--) {
            if (!existingKeys.contains(dock.get(i))) {
                dock.remove(i);
                changed = true;
            }
        }
        for (List<DesktopItem> p : pages) {
            for (int i = p.size() - 1; i >= 0; i--) {
                DesktopItem it = p.get(i);
                if (it.type == DesktopItem.TYPE_APP && !existingKeys.contains(it.appKey)) {
                    p.remove(i);
                    changed = true;
                } else if (it.type == DesktopItem.TYPE_FOLDER) {
                    for (int j = it.folderApps.size() - 1; j >= 0; j--) {
                        if (!existingKeys.contains(it.folderApps.get(j))) {
                            it.folderApps.remove(j);
                            changed = true;
                        }
                    }
                    if (it.folderApps.isEmpty()) {
                        p.remove(i);
                        changed = true;
                    }
                }
            }
        }
        if (changed) save();
    }
}
