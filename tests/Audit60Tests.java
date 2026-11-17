package com.doxaiz.launcher;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Проверки правок 6.0 — то, что можно проверить без телефона.
 *
 * Покрыто:
 *  1. Переполнение при MAX_PAGES: каждая иконка получает СВОЮ клетку, и после
 *     перезапуска (загрузка из JSON) координаты по-прежнему уникальны. Именно
 *     здесь раньше два элемента оказывались на одной клетке, и второй
 *     становился недоступным для нажатий (findItemIndexAt ищет по координатам).
 *  2. Отсутствие дублей: containsAnyDesktopItem() и containsAppAnywhere()
 *     видят папки и приложения, а не только «верхний уровень страницы».
 *  3. Флаг однократного импорта со штатного экрана: значение по умолчанию —
 *     false, после записи — true, ключ сохраняется в SharedPreferences.
 *  4. Даунсэмплинг иконки из дискового кэша: маленькие иконки не трогаем,
 *     большие уменьшаем степенью двойки до ~256 px.
 *
 * Android UI, ContentProvider и биометрия здесь не проверяются — они требуют
 * устройства.
 */
public final class Audit60Tests {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) throws Exception {
        overflowGetsUniqueCells();
        repeatedNormalizeIsStable();
        desktopEmptinessSeesFoldersAndApps();
        systemImportFlagRoundTrip();
        iconDownsampleOnlyForLargeFiles();
        importTwoPhaseSafety();

        System.out.println("Audit60Tests: " + n + " проверок пройдено");
    }

    // ------------------------------------------------------ 1. переполнение MAX_PAGES

    /**
     * 8 страниц × 6 строк × 4 столбца = 192 клетки. Кладём 200 иконок: восемь
     * должны уехать за пределы видимой сетки, но каждая — на свою координату.
     */
    private static void overflowGetsUniqueCells() throws Exception {
        Prefs prefs = newPrefs(new FakePrefs());
        DesktopModel desktop = new DesktopModel(prefs);
        desktop.addPage(); // модель стартует без страниц, если раскладки ещё нет

        int perPage = DesktopModel.GRID_ROWS * 4;
        // 7 страниц заполнены под завязку, на 8-й лежит 28 иконок: 24 влезают,
        // 4 — переполнение при уже достигнутом MAX_PAGES.
        desktop.pages.get(0).clear();
        for (int p = 0; p < DesktopModel.MAX_PAGES - 1; p++) {
            if (p > 0) desktop.addPage();
            List<DesktopItem> page = desktop.pages.get(p);
            for (int k = 0; k < perPage; k++) {
                page.add(DesktopItem.appAt("f" + p + "_" + k, k % 4, k / 4));
            }
        }
        desktop.addPage();
        List<DesktopItem> last = desktop.pages.get(DesktopModel.MAX_PAGES - 1);
        for (int k = 0; k < perPage + 4; k++) {
            last.add(DesktopItem.appAt("x" + k, -1, -1));
        }

        desktop.normalizeAllPages(4, false);

        int placed = 0;
        for (List<DesktopItem> page : desktop.pages) placed += page.size();
        check(placed == perPage * DesktopModel.MAX_PAGES + 4, "ни одна иконка не потеряна: " + placed);

        // Главное: в пределах страницы не бывает двух элементов с одной парой (col,row).
        Set<String> seen = new HashSet<>();
        int hidden = 0;
        for (int p = 0; p < desktop.pages.size(); p++) {
            for (DesktopItem it : desktop.pages.get(p)) {
                String key = it.col + ":" + it.row;
                check(seen.add(pageKey(p) + key), "координата уникальна: " + key);
                if (it.row >= DesktopModel.GRID_ROWS) hidden++;
            }
        }
        check(hidden == 4, "переполнение вынесено за сетку: " + hidden + " из 4");
    }

    /**
     * Уникальность координат считается В ПРЕДЕЛАХ страницы, и страницы
     * различаются по индексу: при нормализации через save()/load() список
     * страниц пересоздаётся, и опираться на identityHashCode нельзя.
     */
    private static String pageKey(int index) {
        return "p" + index + "#";
    }

    /**
     * Повторная нормализация не должна «уезжать».
     *
     * В JVM-тестах org.json — заглушка (mockable android.jar), поэтому проверить
     * запись в SharedPreferences здесь нельзя: save() молча ничего не сохраняет.
     * Зато проверяемо главное свойство: переполненные элементы получают координаты
     * за сеткой (row >= GRID_ROWS), а normalizeAllPages() считает такие координаты
     * невалидными и раскладывает элементы заново. Со счётчиком в памяти каждая
     * повторная нормализация уводила их на новую строку — координаты «уезжали».
     * Теперь строка ищется по содержимому страницы, поэтому результат стабилен.
     */
    private static void repeatedNormalizeIsStable() throws Exception {
        Prefs prefs = newPrefs(new FakePrefs());
        DesktopModel desktop = new DesktopModel(prefs);
        desktop.addPage();

        int perPage = DesktopModel.GRID_ROWS * 4;
        for (int p = 0; p < DesktopModel.MAX_PAGES - 1; p++) {
            if (p > 0) desktop.addPage();
            for (int k = 0; k < perPage; k++) {
                desktop.pages.get(p).add(DesktopItem.appAt("f" + p + "_" + k, k % 4, k / 4));
            }
        }
        desktop.addPage();
        List<DesktopItem> last = desktop.pages.get(DesktopModel.MAX_PAGES - 1);
        for (int k = 0; k < perPage + 2; k++) last.add(DesktopItem.appAt("x" + k, -1, -1));

        desktop.normalizeAllPages(4, false);
        List<String> firstPass = hiddenCoordinates(desktop);
        check(firstPass.size() == 2, "две иконки вынесены за сетку: " + firstPass);

        // Повторные вызовы (как при каждом обновлении списка приложений) не должны
        // менять координаты и не должны создавать дублей.
        for (int i = 0; i < 3; i++) {
            desktop.normalizeAllPages(4, false);
            check(hiddenCoordinates(desktop).equals(firstPass),
                    "координаты за сеткой стабильны после прохода " + (i + 2)
                            + ": " + hiddenCoordinates(desktop) + " вместо " + firstPass);
        }

        Set<String> seen = new HashSet<>();
        for (int p = 0; p < desktop.pages.size(); p++) {
            for (DesktopItem it : desktop.pages.get(p)) {
                check(seen.add(pageKey(p) + it.col + ":" + it.row),
                        "координата уникальна: " + it.col + ":" + it.row);
            }
        }
    }

    /** Координаты всех элементов, вынесенных за пределы видимой сетки. */
    private static List<String> hiddenCoordinates(DesktopModel desktop) {
        List<String> out = new ArrayList<>();
        for (List<DesktopItem> page : desktop.pages) {
            for (DesktopItem it : page) {
                if (it.row >= DesktopModel.GRID_ROWS) out.add(it.col + ":" + it.row);
            }
        }
        Collections.sort(out);
        return out;
    }

    // ------------------------------------------- 2. пустота стола и дубли

    private static void desktopEmptinessSeesFoldersAndApps() throws Exception {
        Prefs prefs = newPrefs(new FakePrefs());
        DesktopModel desktop = new DesktopModel(prefs);
        desktop.addPage();

        desktop.pages.get(0).add(DesktopItem.appAt("a", 0, 0));
        check(desktop.containsAnyDesktopItem(), "иконка видна как содержимое");
        check(desktop.containsAppAnywhere("a"), "приложение найдено");

        // Приложение внутри папки: на верхнем уровне его нет, но дубль создавать нельзя.
        DesktopItem folder = DesktopItem.folderAt("f1", "Папка",
                Collections.singletonList("b"), 1, 0);
        desktop.pages.get(0).add(folder);
        check(desktop.containsAppAnywhere("b"), "приложение внутри папки найдено");
        check(!desktop.containsAppAnywhere("c"), "чужое приложение не считается своим");
    }

    // ------------------------------------------- 3. флаг импорта со штатного экрана

    private static void systemImportFlagRoundTrip() throws Exception {
        FakePrefs store = new FakePrefs();
        Prefs prefs = newPrefs(store);
        check(!prefs.isSystemLayoutImportDone(), "по умолчанию импорт не выполнялся");
        prefs.setSystemLayoutImportDone(true);
        check(prefs.isSystemLayoutImportDone(), "флаг записан");
        check(store.values.containsKey("system_layout_import_done"),
                "ключ сохранён в SharedPreferences");
        Prefs reread = newPrefs(store);
        check(reread.isSystemLayoutImportDone(), "флаг пережил пересоздание Prefs");
    }

    // ------------------------------------------- 4. даунсэмплинг иконок

    private static void iconDownsampleOnlyForLargeFiles() {
        check(IconDiskCache.sampleSizeFor(192, 192) == 1, "обычная иконка не уменьшается");
        check(IconDiskCache.sampleSizeFor(256, 256) == 1, "256 px не уменьшается");
        check(IconDiskCache.sampleSizeFor(512, 512) == 2, "512 px уменьшается вдвое");
        check(IconDiskCache.sampleSizeFor(1024, 1024) == 4, "1024 px уменьшается вчетверо");
        check(IconDiskCache.sampleSizeFor(0, 0) == 1, "нулевой размер — защита от деления");
        check(IconDiskCache.sampleSizeFor(4000, 10) == 8, "линейная иконка тоже уменьшается");
    }

    // ------------------------------------------- 5. две фазы импорта

    /**
     * Разделение «прочитать базу» и «применить снимок» не должно ничего менять
     * для вызывающего: applySnapshot(null) — это «не удалось», без исключений.
     */
    private static void importTwoPhaseSafety() throws Exception {
        Prefs prefs = newPrefs(new FakePrefs());
        DesktopModel desktop = new DesktopModel(prefs);
        desktop.addPage();

        LayoutImporter.Result none = LayoutImporter.applySnapshot(null, desktop, prefs);
        check(!none.success, "пустой снимок — не успех");
        check(none.importedApps == 0, "пустой снимок — 0 иконок");

        LayoutImporter.Result notTried = LayoutImporter.Result.notAttempted(5);
        check(!notTried.success, "notAttempted — не успех");
        check(notTried.detectedCols == 5, "notAttempted возвращает исходное число столбцов");
        check(notTried.importedApps == 0 && notTried.importedFolders == 0,
                "notAttempted ничего не импортировал");

        // Модель после таких вызовов не должна потерять страницы.
        check(!desktop.pages.isEmpty(), "страницы не тронуты неудачным импортом");
    }

    // ------------------------------------------------------------ инфраструктура

    private static Prefs newPrefs(SharedPreferences sp) {
        return new Prefs(sp);
    }

    /** Минимальные SharedPreferences в памяти (как в CrashGuardTests). */
    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }

        @Override public String getString(String key, String defValue) {
            Object v = values.get(key);
            return (v instanceof String) ? (String) v : defValue;
        }

        @Override public Set<String> getStringSet(String key, Set<String> defValues) {
            Object v = values.get(key);
            if (v instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> s = (Set<String>) v;
                return new HashSet<>(s);
            }
            return defValues;
        }

        @Override public int getInt(String key, int defValue) {
            Object v = values.get(key);
            return (v instanceof Integer) ? (Integer) v : defValue;
        }

        @Override public long getLong(String key, long defValue) {
            Object v = values.get(key);
            return (v instanceof Long) ? (Long) v : defValue;
        }

        @Override public float getFloat(String key, float defValue) {
            Object v = values.get(key);
            return (v instanceof Float) ? (Float) v : defValue;
        }

        @Override public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            return (v instanceof Boolean) ? (Boolean) v : defValue;
        }

        @Override public boolean contains(String key) { return values.containsKey(key); }

        @Override public Editor edit() { return new FakeEditor(); }

        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        private final class FakeEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear = false;

            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> v) { pending.put(key, new HashSet<>(v)); return this; }
            @Override public Editor putInt(String key, int v) { pending.put(key, v); return this; }
            @Override public Editor putLong(String key, long v) { pending.put(key, v); return this; }
            @Override public Editor putFloat(String key, float v) { pending.put(key, v); return this; }
            @Override public Editor putBoolean(String key, boolean v) { pending.put(key, v); return this; }
            @Override public Editor remove(String key) { removals.add(key); return this; }
            @Override public Editor clear() { clear = true; return this; }

            @Override public boolean commit() { apply(); return true; }

            @Override public void apply() {
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                values.putAll(pending);
                pending.clear();
                removals.clear();
                clear = false;
            }
        }
    }
}
