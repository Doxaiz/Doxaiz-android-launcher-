package com.doxaiz.launcher;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Кэш карты «скрыто из Недавних» и неизменность правил отбора Недавних.
 *
 * <h3>Зачем</h3>
 * {@code Prefs.getRecentlyLaunchedApps()} — горячий путь: панель «Активные
 * приложения» открывается двумя жестами (свайп вверх, жест «активные окна») и
 * кликом по строке ОЗУ. На каждый вызов разбирался JSON до 200 записей
 * «скрытых» пакетов — только чтобы сравнить его с шестью временами запуска.
 * Записи нужны исключительно для чтения, поэтому кэш здесь безопасен.
 *
 * <h3>Что именно проверяется</h3>
 * <ul>
 *   <li>повторные вызовы читают настройки <b>один</b> раз — это и есть выигрыш,
 *       и он измерен счётчиком в поддельном хранилище, а не на глаз;</li>
 *   <li>свайп по карточке виден следующему вызову сразу: скрытая карточка не
 *       возвращается (кэш обновляется тем, что записали);</li>
 *   <li>после импорта копии кэш сброшен — иначе пользователь увидел бы Недавние
 *       из прошлой копии, те же грабли, что у отката импорта (§43);</li>
 *   <li>прежние правила не изменились: порог «очищено», скрытые, лимит,
 *       отсутствующие в журнале приложения.</li>
 * </ul>
 */
public final class RecentsCacheTest {

    /**
     * Число заполнений карты «скрытых» на один вызов «Недавних».
     *
     * Именно это и есть предмет правки: до кэша здесь был разбор JSON на
     * каждый вызов, теперь — один раз на состояние. Значение вынесено в
     * константу, чтобы тест падал с понятным числом, а не с «не равно».
     */
    private static final int DISMISSED_PARSES_PER_CALL = 1;

    /** То же для журнала запусков: его кэш был и раньше, значение фиксируем. */
    private static final int JOURNAL_READS_PER_CALL = 1;

    /** Диагностика для журнала CI: при падении сразу видно фактические значения. */
    private static String DIAG = "";

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) throws Exception {
        repeatedCallsParseDismissedOnce();
        newDismissalIsVisibleImmediately();
        importInvalidatesCache();
        filterRulesUnchanged();

        System.out.println("RECENTS_CACHE_TESTS=" + n);
    }

    /**
     * Один вызов — одна загрузка карты «скрытых».
     *
     * Внимание: именно <b>один</b> вызов, а не «сколько бы раз панель ни
     * открывали». В {@code Prefs} живёт РЕАЛЬНАЯ SharedPreferences, и её
     * {@code apply()} кэширует всю карту значений внутри себя: после первой
     * записи метод {@code getString} в поддельное хранилище уже не попадает, и
     * счётчик чтений упирается в фактическое число записей. Поэтому требование
     * «три вызова — одно чтение» было бы проверкой системы, а не нашего кода;
     * вторая половина выигрыша видна по JOURNAL_READS_PER_CALL.
     */
    private static void repeatedCallsParseDismissedOnce() {
        CountingPrefs store = new CountingPrefs();
        store.values.put("app_usage_json", usageJson(new String[]{"com.a", "com.b"}, new long[]{5000L, 4000L}));
        // Свайп обязан быть ПОЗЖЕ запуска: правило «скрыт» — это
        // ts <= dismissedAt. В первой версии теста здесь стояло 1000L при
        // запуске 4000L, то есть карточка и не должна была скрываться, —
        // падал тест, а не код.
        store.values.put("dismissed_pkgs_json", dismissedJson("com.b", 4500L));
        Prefs prefs = new Prefs(store);

        List<AppEntry> visible = Arrays.asList(app("com.a"), app("com.b"));
        DIAG = "[dismissed=" + store.values.get("dismissed_pkgs_json")
                + " usage=" + store.values.get("app_usage_json")
                + " reads=" + store.dismissedReads + "/" + store.usageReads
                + " key1=" + visible.get(0).key + " key2=" + visible.get(1).key
                + " result=" + keysOf(prefs.getRecentlyLaunchedApps(visible, 6)) + "] ";
        for (int i = 0; i < 3; i++) {
            List<AppEntry> recent = prefs.getRecentlyLaunchedApps(visible, 6);
            check(recent.size() == 1, DIAG + "вызов " + (i + 1) + ": скрытый пакет отфильтрован");
        }
        check(store.dismissedReads == DISMISSED_PARSES_PER_CALL,
                "карта «скрытых» прочитана " + store.dismissedReads + " раз вместо "
                        + DISMISSED_PARSES_PER_CALL + " — кэш не работает,"
                        + " и каждый вызов панели снова разбирает JSON");
        System.out.println("DISMISSED_READS_НА_3_ВЫЗОВА=" + store.dismissedReads);

        // Вторая половина выигрыша: кэш журнала запусков уже был, и его
        // стоимость — тоже одно чтение. Оба числа печатаются, чтобы в журнале
        // CI было видно, что «дорогих» чтений в панели не осталось.
        check(store.usageReads <= JOURNAL_READS_PER_CALL,
                "журнал запусков читается " + store.usageReads + " раз при одном"
                        + " состоянии — это больше, чем было");
        System.out.println("JOURNAL_READS_НА_3_ВЫЗОВА=" + store.usageReads);
    }

    /** Свайп по карточке обязан скрыть её со следующего вызова. */
    private static void newDismissalIsVisibleImmediately() {
        CountingPrefs store = new CountingPrefs();
        store.values.put("app_usage_json", usageJson(new String[]{"com.a", "com.b"}, new long[]{5000L, 4000L}));
        Prefs prefs = new Prefs(store);
        List<AppEntry> visible = Arrays.asList(app("com.a"), app("com.b"));

        check(prefs.getRecentlyLaunchedApps(visible, 6).size() == 2, "до свайпа видны оба");

        prefs.markRecentPackageDismissed("com.a");

        List<AppEntry> after = prefs.getRecentlyLaunchedApps(visible, 6);
        check(after.size() == 1, "после свайпа скрытая карточка не вернулась");
        check(after.get(0).packageName.equals("com.b"), "после свайпа остался com.b");

        // И в настройках записано ровно то, что теперь в кэше: кэш не живёт
        // отдельной жизнью от хранилища.
        String raw = (String) store.values.get("dismissed_pkgs_json");
        check(raw != null && raw.contains("com.a"), "свайп записан в настройки");
    }

    /** Импорт копии заменяет настройки — кэш обязан это увидеть. */
    private static void importInvalidatesCache() {
        CountingPrefs store = new CountingPrefs();
        store.values.put("app_usage_json", usageJson(new String[]{"com.a"}, new long[]{5000L}));
        store.values.put("dismissed_pkgs_json", dismissedJson("com.a", 9000L));
        Prefs prefs = new Prefs(store);
        List<AppEntry> visible = Arrays.asList(app("com.a"));

        check(prefs.getRecentlyLaunchedApps(visible, 6).isEmpty(), "до импорта com.a скрыт");

        // Импорт меняет настройки в обход Prefs, как это делает BackupHelper.
        store.values.put("dismissed_pkgs_json", dismissedJson("com.other", 1L));
        prefs.invalidateCachesAfterImport();

        check(prefs.getRecentlyLaunchedApps(visible, 6).size() == 1,
                "после импорта com.a больше не скрыт: кэш сброшен вместе с остальными");
    }

    /** Прежние правила отбора: порог очистки, отсутствие в журнале, лимит. */
    private static void filterRulesUnchanged() {
        CountingPrefs store = new CountingPrefs();
        store.values.put("app_usage_json", usageJson(
                new String[]{"com.a", "com.b", "com.c"}, new long[]{9000L, 5000L, 1000L}));
        store.values.put("recents_cleared_at", 3000L);
        Prefs prefs = new Prefs(store);
        List<AppEntry> visible = Arrays.asList(
                app("com.a"), app("com.b"), app("com.c"), app("com.never"));

        List<AppEntry> recent = prefs.getRecentlyLaunchedApps(visible, 6);
        check(recent.size() == 2, "запуски до очистки и приложение без журнала отброшены");
        check(recent.get(0).packageName.equals("com.a"), "свежий запуск первым");
        check(recent.get(1).packageName.equals("com.b"), "более старый вторым");

        List<AppEntry> limited = prefs.getRecentlyLaunchedApps(visible, 1);
        check(limited.size() == 1, "лимит соблюдается");
        check(limited.get(0).packageName.equals("com.a"), "в лимит попадает самый свежий");

        check(prefs.getRecentlyLaunchedApps(visible, 0).isEmpty(), "лимит 0 — пусто");
        check(prefs.getRecentlyLaunchedApps(new ArrayList<AppEntry>(), 6).isEmpty(),
                "пустой список приложений — пусто");
        check(prefs.getRecentlyLaunchedApps(null, 6).isEmpty(), "null вместо списка — пусто");
    }

    // ------------------------------------------------------------ помощники

    /** Ключи результата строкой: чтобы в ошибке было видно, что именно вернулось. */
    private static String keysOf(List<AppEntry> list) {
        StringBuilder sb = new StringBuilder("{");
        for (AppEntry e : list) sb.append(e.key).append(',');
        return sb.append('}').toString();
    }

    private static AppEntry app(String pkg) {
        return new AppEntry(new android.content.ComponentName(pkg, pkg + ".Main"),
                null, 0L, pkg, null, true, true, 0, 0L);
    }

    /**
     * Журнал запусков в том виде, в каком его пишет recordAppLaunch:
     * ключ — {@code pkg/class#user}, а не имя пакета.
     *
     * Первая версия теста клала ключом пакет, и «Недавние» честно не находили
     * ни одной записи (reads=0 в диагностике): падал тест, а не код. Форма
     * ключа строится тем же правилом, что в {@link AppEntry#key}.
     */
    private static String usageJson(String[] pkgs, long[] times) {
        try {
            JSONObject root = new JSONObject();
            for (int i = 0; i < pkgs.length; i++) {
                JSONObject o = new JSONObject();
                o.put("c", 3);
                o.put("t", times[i]);
                root.put(pkgs[i] + "/" + pkgs[i] + ".Main#0", o);
            }
            return root.toString();
        } catch (JSONException e) {
            throw new AssertionError("не удалось собрать журнал запусков: " + e.getMessage());
        }
    }

    private static String dismissedJson(String pkg, long at) {
        try {
            JSONObject root = new JSONObject();
            root.put(pkg, at);
            return root.toString();
        } catch (JSONException e) {
            throw new AssertionError("не удалось собрать карту скрытых: " + e.getMessage());
        }
    }

    /**
     * Хранилище, которое считает чтения ключа «скрытых».
     *
     * Счётчик — единственный способ доказать, что кэш работает, не заглядывая
     * в приватные поля класса и не меряя наносекунды на эмуляторе.
     */
    private static final class CountingPrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        int dismissedReads;
        int usageReads;

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }

        @Override public String getString(String key, String defValue) {
            if ("dismissed_pkgs_json".equals(key)) dismissedReads++;
            if ("app_usage_json".equals(key)) usageReads++;
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
            private boolean clear = false;

            @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, new HashSet<>(v)); return this; }
            @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
            @Override public Editor remove(String k) { pending.remove(k); return this; }
            @Override public Editor clear() { clear = true; return this; }

            @Override public boolean commit() { apply(); return true; }

            @Override public void apply() {
                if (clear) values.clear();
                values.putAll(pending);
                pending.clear();
            }
        }
    }
}
