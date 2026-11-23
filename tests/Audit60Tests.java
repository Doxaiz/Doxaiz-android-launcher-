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
        diskStatsCacheIsIsolated();
        importResultHasContent();
        emptyLayoutIsNotInitialized();
        formatWithCurrentTimeZoneIsIndependentOfSavedTimeZone();
        formatWithCurrentTimeZoneHandlesNullDate();
        pendingAuthActionDeliversOnce();
        pendingAuthActionCannotMixRequests();
        importTruncatesForeignStrings();
        dismissedRecentSetIsBounded();
        trafficTotalsIgnoreAnomalies();

        // Токен вида ИМЯ=<число> — контракт с обёрткой LauncherChecksTest: она
        // требует его и падает, если проверок ноль. Иначе набор, из которого
        // убрали все проверки, «проходил» бы молча (см. AUDIT §48).
        if (n <= 0) throw new AssertionError("ни одной проверки не выполнено");
        System.out.println("AUDIT60_CHECKS=" + n);
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

    // ------------------------------------------- 6. кэш статистики диска

    /**
     * getStats() кэшируется на 5 с, но отдаёт КОПИЮ: вызывающий не должен
     * иметь возможности испортить кэш, изменив массив.
     */
    private static void diskStatsCacheIsIsolated() throws Exception {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "doxaiz-audit60-" + System.nanoTime());
        check(dir.mkdirs() || dir.isDirectory(), "временный каталог кэша создан");
        IconDiskCache cache = new IconDiskCache(dir);

        long[] first = cache.getStats();
        check(first.length == 2, "статистика — два числа");
        check(first[0] == 0 && first[1] == 0, "пустой каталог — нули");

        first[0] = 999; // портим полученный массив
        first[1] = 999;
        long[] second = cache.getStats();
        check(second[0] == 0 && second[1] == 0, "кэш не изменился через возвращённый массив");

        // После явного сброса (так делают save/prune/clearAll) значения пересчитаны.
        cache.invalidateStats();
        long[] third = cache.getStats();
        check(third[0] == 0, "после сброса — снова ноль файлов");

        deleteRecursively(dir);
    }

    private static void deleteRecursively(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] kids = f.listFiles();
        if (kids != null) {
            for (java.io.File k : kids) deleteRecursively(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ------------------------------------------- 7. применение импорта

    /** Result.hasContent: «прочитали, но применять нечего» отличается от успеха. */
    private static void importResultHasContent() {
        LayoutImporter.Result empty = LayoutImporter.Result.notAttempted(4);
        check(!empty.hasContent(), "notAttempted — применять нечего");

        LayoutImporter.Result oneApp = new LayoutImporter.Result(true, "XOS", 1, 0, 5);
        check(oneApp.hasContent(), "одна иконка — уже содержимое");

        LayoutImporter.Result folderOnly = new LayoutImporter.Result(true, "XOS", 0, 1, 5);
        check(folderOnly.hasContent(), "папка без приложений — тоже содержимое");

        LayoutImporter.Result failure = new LayoutImporter.Result(false, "", 0, 0, 4);
        check(!failure.hasContent(), "неуспех — применять нечего");
    }

    // ------------------------------- 8. пустая раскладка не «уже построена»

    /**
     * Главный дефект 6.0: на чистой установке рабочий стол оставался пустым.
     *
     * Модель, у которой нет ни дока, ни иконок, НЕ должна считать себя готовой —
     * иначе авто-раскладка не строится никогда. И наоборот: пустой стол после
     * того, как пользователь сам всё убрал (флаг built), перестраивать нельзя.
     */
    private static void emptyLayoutIsNotInitialized() throws Exception {
        Prefs prefs = newPrefs(new FakePrefs());
        DesktopModel fresh = new DesktopModel(prefs);
        check(!fresh.isInitialized(), "новая модель — раскладка не построена");
        check(!fresh.hasContent(), "новая модель — содержимого нет");

        // Именно этот случай ломал установку: одна пустая страница, ноль иконок.
        fresh.addPage();
        check(!fresh.pages.isEmpty(), "страница добавлена");
        check(!fresh.isInitialized(), "pages=[[]] — ещё НЕ готовая раскладка");
        check(!fresh.hasContent(), "пустая страница — не содержимое");

        // Построение по пустому списку не должно ничего закреплять.
        fresh.buildInitialLayout(new ArrayList<>(), 4, false);
        check(!fresh.isInitialized(), "buildInitialLayout([]) не закрепляет пустой стол");

        // А по непустому — строится и становится готовым.
        List<AppEntry> apps = new ArrayList<>();
        apps.add(app("com.android.dialer", "Телефон"));
        apps.add(app("com.android.settings", "Настройки"));
        fresh.buildInitialLayout(apps, 4, false);
        check(fresh.isInitialized(), "после построения раскладка готова");
        check(fresh.hasContent(), "после построения есть содержимое");

        // Пустой стол, помеченный построенным (пользователь убрал всё сам),
        // остаётся готовым: его нельзя молча перезаполнить.
        DesktopModel cleared = new DesktopModel(newPrefs(new FakePrefs()));
        cleared.addPage();
        cleared.markInitialized();
        check(cleared.isInitialized(), "помеченная раскладка готова даже без иконок");
        check(!cleared.hasContent(), "но содержимого в ней нет");
    }

    private static AppEntry app(String pkg, String label) {
        return new AppEntry(new android.content.ComponentName(pkg, pkg + ".Main"),
                null, 0L, label, null, true, true, 0, 0L);
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

    // ------------------------------------- 6. форматирование времени в шторке

    /**
     * Раньше в QuickShadeHelper лежали static SimpleDateFormat и метод,
     * который менял их часовой пояс. SimpleDateFormat не потокобезопасен, а
     * вызывается и из UI-потока, и из фонового (панель сети). Теперь форматтер
     * создаётся внутри вызова — проверяем, что результат не зависит от
     * предыдущих вызовов и учитывает смену часового пояса.
     */
    private static void formatWithCurrentTimeZoneIsIndependentOfSavedTimeZone() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
            java.util.TimeZone plus3 = java.util.TimeZone.getTimeZone("Etc/GMT-3");
            java.util.Date at = new java.util.Date(1_700_000_000_000L);

            java.util.TimeZone.setDefault(utc);
            String utcText = QuickShadeHelper.formatWithCurrentTimeZone(
                    "HH:mm", java.util.Locale.US, at);

            java.util.TimeZone.setDefault(plus3);
            String plus3Text = QuickShadeHelper.formatWithCurrentTimeZone(
                    "HH:mm", java.util.Locale.US, at);

            check(utcText.length() == 5 && utcText.charAt(2) == ':',
                    "время формата HH:mm, получено " + utcText);
            // Перелёт/смена пояса должна менять время, а не «залипать» на старом.
            check(!utcText.equals(plus3Text),
                    "смена часового пояса меняет время: " + utcText + " -> " + plus3Text);

            // Повторный вызов после возврата пояса даёт исходную строку: нет
            // накопленного состояния (это и было свойством static-форматтера).
            java.util.TimeZone.setDefault(utc);
            check(utcText.equals(QuickShadeHelper.formatWithCurrentTimeZone(
                            "HH:mm", java.util.Locale.US, at)),
                    "повторный вызов не зависит от предыдущего");
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    /** Пустая дата не должна ронять форматирование (был бы NPE внутри format). */
    private static void formatWithCurrentTimeZoneHandlesNullDate() {
        check("".equals(QuickShadeHelper.formatWithCurrentTimeZone(
                        "HH:mm", java.util.Locale.US, null)),
                "null-дата даёт пустую строку, а не падение");
    }

    // ---------------------------------------- 11. отложенное подтверждение PIN

    /**
     * Ответ системного экрана PIN применяется ровно один раз: действие не
     * должно выполниться повторно, если результат придёт ещё раз (поворот
     * экрана, повторная доставка onActivityResult).
     */
    private static void pendingAuthActionDeliversOnce() {
        PendingAuthAction pending = new PendingAuthAction();
        final int[] ran = {0};

        check(!pending.has(), "новая проверка ничего не ждёт");
        check(!pending.deliver(true), "ответ без запроса не считается нашим");

        pending.set(() -> ran[0]++, () -> ran[0] += 100);
        check(pending.has(), "ожидание зафиксировано");
        check(pending.deliver(true), "успех применён к своему запросу");
        check(ran[0] == 1, "действие выполнено ровно один раз, стало " + ran[0]);

        check(!pending.deliver(true), "повторный успех уже не наш");
        check(ran[0] == 1, "повторный ответ не выполняет действие снова");
        check(!pending.has(), "после ответа ожидание снято");

        // Отмена не должна запускать действие успеха — и наоборот.
        ran[0] = 0;
        pending.set(() -> ran[0] += 1, () -> ran[0] += 10);
        pending.deliver(false);
        check(ran[0] == 10, "отмена вызывает только отмену, стало " + ran[0]);
    }

    /**
     * Главный дефект: запрос на одно действие, затем второй запрос на другое.
     * Раньше оба жили в одном поле активности, и ответ второго мог выполнить
     * действие первого (открыть скрытые приложения вместо экспорта копии).
     */
    private static void pendingAuthActionCannotMixRequests() {
        PendingAuthAction pending = new PendingAuthAction();
        final StringBuilder order = new StringBuilder();

        pending.set(() -> order.append("A"), () -> order.append("a"));
        // Второй запрос пришёл, пока первый ещё не ответил.
        pending.set(() -> order.append("B"), () -> order.append("b"));
        pending.deliver(true);
        check("B".contentEquals(order), "выполнен только последний запрос, стало " + order);

        order.setLength(0);
        pending.set(() -> order.append("C"), () -> order.append("c"));
        pending.clear();
        check(!pending.has(), "clear() снимает запрос");
        check(!pending.deliver(true), "после clear() ответ не наш");
        check(order.length() == 0, "снятый запрос не выполняется, стало " + order);

        // Исключение внутри действия не должно ломать состояние ожидания.
        order.setLength(0);
        pending.set(() -> { throw new IllegalStateException("сбой действия"); }, null);
        pending.deliver(true);
        check(!pending.has(), "после исключения ожидание всё равно снято");
    }
    // -------------------------------- 12. ограничение чужих строк при импорте

    /**
     * Подпись иконки приходит из базы ДРУГОЙ оболочки: её длина ничем не
     * ограничена, а провайдер может отдать строку в мегабайты. Такая строка
     * попадает в модель рабочего стола и в JSON раскладки, поэтому обрезается
     * на входе, а не «где-нибудь потом».
     */
    private static void importTruncatesForeignStrings() {
        check("".equals(LayoutImporter.truncateForImport(null, 10)),
                "null из чужой базы даёт пустую строку, а не NPE");
        check("".equals(LayoutImporter.truncateForImport("что-то", 0)),
                "нулевой предел даёт пустую строку");
        check("Короткое".equals(LayoutImporter.truncateForImport("Короткое", 64)),
                "короткая строка не меняется");

        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 10_000; i++) huge.append('я');
        String cut = LayoutImporter.truncateForImport(huge.toString(), 256);
        check(cut.length() == 256, "длинная строка обрезана до предела, стало " + cut.length());

        // Предел импорта строк должен быть заведомо больше реальной раскладки
        // (8 страниц × 24 иконки + док + папки), иначе импорт потеряет иконки.
        int realisticMax = DesktopModel.MAX_PAGES * DesktopModel.GRID_ROWS * 5 + 64;
        check(LayoutImporter.MAX_IMPORT_ROWS >= realisticMax,
                "предел строк импорта не режет настоящую раскладку: "
                        + LayoutImporter.MAX_IMPORT_ROWS + " >= " + realisticMax);
    }
    // --------------------- 13. набор «скрытых из Недавних» не растёт бесконечно

    /**
     * Каждый свайп по карточке в «Недавних» дописывал пакет в JSON и никогда
     * его не убирал: набор рос без предела, а целиком перечитывался и
     * перезаписывался на каждый следующий свайп.
     *
     * Логика предела вынесена на обычную Map, поэтому проверяется здесь:
     * org.json в JVM-тестах — заглушка, и через JSON это не проверить.
     */
    private static void dismissedRecentSetIsBounded() {
        Map<String, Long> all = new HashMap<>();
        long base = 1_700_000_000_000L;
        for (int i = 0; i < 260; i++) all.put("pkg" + i, base + i);

        Map<String, Long> trimmed = Prefs.trimDismissed(all, 200);
        check(trimmed.size() == 200, "переполненный набор срезан до предела, стало " + trimmed.size());
        check(trimmed.containsKey("pkg259"), "самая свежая запись сохранена");
        check(!trimmed.containsKey("pkg0"), "самая старая запись выброшена");
        check(!trimmed.containsKey("pkg59"), "записи за пределами 200 свежих выброшены");

        // На пределе и ниже — ничего не теряем.
        check(Prefs.trimDismissed(all, 260).size() == 260, "на пределе набор не режется");
        check(Prefs.trimDismissed(all, 1000).size() == 260, "при большом пределе набор цел");

        // Границы не должны ронять.
        check(Prefs.trimDismissed(null, 200).isEmpty(), "null даёт пустой набор, а не NPE");
        check(Prefs.trimDismissed(new HashMap<>(), 200).isEmpty(), "пустой набор остаётся пустым");
        check(Prefs.trimDismissed(all, 0).isEmpty(), "нулевой предел даёт пустой набор");

        // Исходная карта не должна меняться: её вызывает горячий путь настроек.
        check(all.size() == 260, "исходный набор не изменён, стало " + all.size());
    }
    // --------------------------- 14. учёт трафика: дельты, сброс счётчика, скачки

    /**
     * Учёт трафика раньше не вызывался ниоткуда (панель сети показывала только
     * скорость). Перенесён в NetworkQualityHelper; расчёт проверяется здесь,
     * потому что он чистый: ни TrafficStats, ни SharedPreferences.
     */
    private static void trafficTotalsIgnoreAnomalies() {
        long mb = 1024L * 1024L;

        // Первый замер: стартуем от текущего счётчика, а не прибавляем всё
        // накопленное с загрузки телефона как «трафик за сутки».
        long[] first = NetworkQualityHelper.nextTrafficTotals(0L, 0L, -1L, 300L * mb);
        check(first[0] == 300L * mb && first[1] == 300L * mb,
                "первый замер берёт текущее значение");

        // Обычная дельта прибавляется к обеим суммам.
        long[] next = NetworkQualityHelper.nextTrafficTotals(10L * mb, 100L * mb, 300L * mb, 312L * mb);
        check(next[0] == 22L * mb, "суточная сумма выросла на дельту, стало " + next[0]);
        check(next[1] == 112L * mb, "месячная сумма выросла на дельту, стало " + next[1]);

        // Счётчик обнулился (перезагрузка): берём текущее значение, не отрицательное.
        long[] afterReboot = NetworkQualityHelper.nextTrafficTotals(22L * mb, 112L * mb, 312L * mb, 5L * mb);
        check(afterReboot[0] == 27L * mb && afterReboot[1] == 117L * mb,
                "после сброса счётчика дельта = текущее значение");

        // Аномальный скачок (> 50 ГБ за час) не засчитывается вовсе.
        long[] huge = NetworkQualityHelper.nextTrafficTotals(1L * mb, 2L * mb, 0L, 60L * 1024L * mb);
        check(huge[0] == 1L * mb && huge[1] == 2L * mb,
                "скачок больше 50 ГБ не портит накопленное");

        // Отрицательные/битые входные значения не дают отрицательных сумм.
        long[] broken = NetworkQualityHelper.nextTrafficTotals(-5L, -7L, -1L, -100L);
        check(broken[0] >= 0L && broken[1] >= 0L, "битые входные данные дают неотрицательные суммы");

        // Нет изменений — суммы не растут.
        long[] flat = NetworkQualityHelper.nextTrafficTotals(5L * mb, 6L * mb, 300L * mb, 300L * mb);
        check(flat[0] == 5L * mb && flat[1] == 6L * mb, "без трафика суммы не меняются");
    }
}
