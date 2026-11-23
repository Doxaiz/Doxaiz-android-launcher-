package com.doxaiz.launcher;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Кэш карты «скрыто из Недавних».
 *
 * <h3>Зачем правка</h3>
 * {@code Prefs.getRecentlyLaunchedApps()} — горячий путь: панель «Активные
 * приложения» открывается свайпом вверх, жестом «активные окна» и кликом по
 * строке ОЗУ. На каждый вызов разбирался JSON до {@code MAX_DISMISSED_PKGS}=200
 * записей «скрытых», хотя эти записи нужны только для чтения. Теперь разбор
 * однократный, а кэш обновляют оба места записи.
 *
 * <h3>Что проверяется и что нет</h3>
 * Проверяется механика кэша — счётчиком чтений в поддельном хранилище и чтением
 * самого поля через рефлексию. Это то, что изменено, и то, что можно доказать
 * без Android.
 *
 * <p><b>Про заглушки android.jar.</b> В юнит-тестах {@code ComponentName} —
 * заглушка: {@code getPackageName()} возвращает null. Первая версия этого теста
 * на этом и падала ({@code key1=null/null#0 result={}}), а до правки — что хуже —
 * «проходила», ничего не проверяя. Поэтому правило отбора вынесено в чистую
 * функцию {@link Prefs#isRecentVisible} и проверяется здесь целиком, без
 * {@code AppEntry}. Отдельная проверка {@link #appEntryKeysAreNotStubs()}
 * запрещает возврат к зелёным тестам на заглушках.
 */
public final class RecentsCacheTest {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) throws Exception {
        recentVisibilityRule();
        appEntryKeysAreNotStubs();
        cacheLoadsOncePerInstance();
        dismissalUpdatesCacheWithoutReread();
        closeAllUpdatesCache();
        importInvalidatesCache();

        System.out.println("RECENTS_CACHE_TESTS=" + n);
    }

    /**
     * Правило отбора «Недавних» целиком — без AppEntry и без Android.
     *
     * Это те три сравнения, из-за которых в §46 пришлось переписывать тест:
     * порог «очищено», скрытие свайпом и отсутствие записи в журнале.
     */
    private static void recentVisibilityRule() {
        check(Prefs.isRecentVisible(1000L, 0L, null), "свежий запуск виден");
        check(!Prefs.isRecentVisible(0L, 0L, null), "нулевое время запуска не видно");
        check(!Prefs.isRecentVisible(-5L, 0L, null), "отрицательное время не видно");
        check(!Prefs.isRecentVisible(1000L, 1000L, null), "запуск ровно в момент очистки не виден");
        check(!Prefs.isRecentVisible(999L, 1000L, null), "запуск до очистки не виден");
        check(Prefs.isRecentVisible(1001L, 1000L, null), "запуск после очистки виден");
        check(!Prefs.isRecentVisible(1000L, 0L, 1000L), "свайп ровно в момент запуска скрывает");
        check(!Prefs.isRecentVisible(999L, 0L, 1000L), "свайп позже запуска скрывает");
        check(Prefs.isRecentVisible(1001L, 0L, 1000L), "запуск позже свайпа виден снова");
        check(Prefs.isRecentVisible(1L, 0L, null), "самый старый ненулевой запуск виден");
        // Смысл отдельного правила: null и «ноль» — разные вещи. Прежний
        // optLong(pkg, 0L) подставлял 0, и это работало только потому, что
        // ts > 0 проверялось выше. Теперь связь записана явно и проверена.
        check(!Prefs.isRecentVisible(0L, 0L, 0L), "нулевой запуск не виден даже без свайпа");
    }

    /**
     * Ключи AppEntry в юнит-тестах различимы — или отсутствуют как таковые.
     *
     * Зачем это отдельная проверка: в §46 тест на AppEntry «проходил», потому что
     * ComponentName в android.jar — заглушка и любой пакет получал
     * key = "null/null#0". Утверждения вида «скрытый пакет отфильтрован»
     * выполнялись на пустом списке.
     *
     * <p>Состояние зафиксировано явно, а не замаскировано: в JVM (заглушки
     * android.jar) ключи <b>неразличимы</b>, и это печатается в отчёт. Проверка
     * падает только на худшем варианте — ключ «null/null#0», оставшийся от
     * прежнего кода, потому что такой ключ ещё и выглядит правдоподобно.
     */
    private static void appEntryKeysAreNotStubs() {
        AppEntry a = makeApp("com.a");
        AppEntry b = makeApp("com.b");
        check(a.key != null && !a.key.contains("null"),
                "ключ содержит null: " + a.key + " — вернулось состояние §46");
        check(a.packageName != null, "packageName не null");
        boolean distinguishable = !a.key.equals(b.key);
        System.out.println("APP_ENTRY_КЛЮЧИ_В_JVM=" + a.key + " | " + b.key
                + " | различимы=" + distinguishable
                + " (заглушки android.jar: правила на таком наборе не проверяются)");
    }

    /** Второй вызов не читает ключ заново: карта уже в памяти. */
    private static void cacheLoadsOncePerInstance() throws Exception {
        CountingPrefs store = new CountingPrefs();
        store.values.put("dismissed_pkgs_json", dismissedJson("com.b", 4500L));
        Prefs prefs = new Prefs(store);
        List<AppEntry> visible = stubApps();

        prefs.getRecentlyLaunchedApps(visible, 6);
        check(store.dismissedReads == 1, "первый вызов читает карту: " + store.dismissedReads);

        prefs.getRecentlyLaunchedApps(visible, 6);
        prefs.getRecentlyLaunchedApps(visible, 6);
        check(store.dismissedReads == 1, "повторные вызовы карту не перечитывают, а прочитали "
                + store.dismissedReads + " раз — кэш не работает, и каждый вызов панели"
                + " снова разбирает JSON");
        check(cachedDismissed(prefs).containsKey("com.b"), "в кэше ровно то, что в настройках");
        System.out.println("DISMISSED_READS_НА_3_ВЫЗОВА=" + store.dismissedReads);
    }

    /** Свайп по карточке виден сразу: кэш обновляется, а не просто сбрасывается. */
    private static void dismissalUpdatesCacheWithoutReread() throws Exception {
        CountingPrefs store = new CountingPrefs();
        Prefs prefs = new Prefs(store);
        prefs.getRecentlyLaunchedApps(stubApps(), 6);
        int before = store.dismissedReads;

        prefs.markRecentPackageDismissed("com.a");
        // Сама запись читает ключ один раз — она сливает новую запись с уже
        // сохранёнными. Это не лишняя работа: без чтения старые свайпы потерялись
        // бы. Здесь важно другое: следующее ОТКРЫТИЕ панели не добавляет чтения.
        int afterWrite = store.dismissedReads;
        prefs.getRecentlyLaunchedApps(stubApps(), 6);
        check(store.dismissedReads == afterWrite,
                "открытие панели после свайпа перечитало карту (" + before + "→"
                        + afterWrite + "→" + store.dismissedReads + ") — кэш обновлён"
                        + " не тем, что записали");
        check(cachedDismissed(prefs).containsKey("com.a"),
                "скрытый свайпом пакет сразу в кэше: иначе карточка вернётся при"
                        + " следующем открытии панели");
        check(String.valueOf(store.values.get("dismissed_pkgs_json")).contains("com.a"),
                "свайп записан в настройки");
    }

    /**
     * «Закрыть все» в Недавних пишет ту же карту — кэш обязан это увидеть.
     *
     * Именно здесь была настоящая дыра: {@code markAllRecentTabsClearedExcept}
     * писал карту напрямую, кэш не трогал, и закрытые карточки вернулись бы.
     */
    private static void closeAllUpdatesCache() throws Exception {
        // Случай 1: «Закрыть все» с пустым набором — ключ удаляется.
        CountingPrefs store = new CountingPrefs();
        store.values.put("dismissed_pkgs_json", dismissedJson("com.a", 100L));
        Prefs prefs = new Prefs(store);
        prefs.getRecentlyLaunchedApps(stubApps(), 6);
        check(cachedDismissed(prefs).containsKey("com.a"), "до «Закрыть все» com.a в кэше");

        prefs.markAllRecentTabsCleared();
        check(cachedDismissed(prefs).isEmpty(),
                "«Закрыть все» удалило ключ, но кэш остался: Недавние фильтровались бы"
                        + " по карте из прошлого открытия панели");

        // Случай 2: «Закрыть все» с непустым набором закреплённых. Ветку нельзя
        // проверить в JVM: она берёт ключи из журнала запусков (AppEntry.key), а в
        // юнит-тестовом android.jar ComponentName — заглушка, и ключи получаются
        // "null/null#0". Проверяем то, что от неё зависит и здесь наблюдаемо:
        // кэш после вызова согласован с настройками, а не остался старым.
        CountingPrefs store2 = new CountingPrefs();
        store2.values.put("app_usage_json", usageJson(new String[]{"com.x", "com.y"}));
        Prefs prefs2 = new Prefs(store2);
        prefs2.getRecentlyLaunchedApps(stubApps(), 6);
        prefs2.markAllRecentTabsClearedExcept(new HashSet<String>() {{ add("com.keep"); }});

        // Сравнивать JSON-строку с Map.toString() нельзя — это разные форматы,
        // и такой тест падал бы всегда. Сравниваем содержимое по ключам.
        String raw = String.valueOf(store2.values.get("dismissed_pkgs_json"));
        Map<String, Long> inStore = parseFlat(raw);
        Map<String, Long> inCache = cachedDismissed(prefs2);
        check(inStore.equals(inCache),
                "кэш расходится с настройками после «Закрыть все»: в настройках "
                        + inStore + ", в кэше " + inCache);
    }

    /** Импорт копии заменяет настройки — кэш обязан перечитаться. */
    private static void importInvalidatesCache() throws Exception {
        CountingPrefs store = new CountingPrefs();
        store.values.put("dismissed_pkgs_json", dismissedJson("com.a", 9000L));
        Prefs prefs = new Prefs(store);
        prefs.getRecentlyLaunchedApps(stubApps(), 6);
        int before = store.dismissedReads;
        check(cachedDismissed(prefs).containsKey("com.a"), "до импорта com.a в кэше");

        // Импорт меняет настройки в обход Prefs, как это делает BackupHelper.
        store.values.put("dismissed_pkgs_json", dismissedJson("com.other", 1L));
        prefs.invalidateCachesAfterImport();
        prefs.getRecentlyLaunchedApps(stubApps(), 6);

        check(store.dismissedReads > before,
                "после импорта карта перечитана: иначе пользователь увидит Недавние"
                        + " прошлой копии (те же грабли, что у отката импорта, §43)");
        check(cachedDismissed(prefs).containsKey("com.other"), "в кэше состояние из импорта");
        check(!cachedDismissed(prefs).containsKey("com.a"), "старое состояние из кэша ушло");
    }

    // ------------------------------------------------------------ помощники

    /**
     * Список «приложений» для вызова.
     *
     * Пустой список был бы отсечён ранним возвратом до загрузки кэша, поэтому
     * объект нужен. Что его поля в JVM пусты — на механику кэша не влияет:
     * карта «скрытых» загружается до обхода списка.
     */
    private static AppEntry makeApp(String pkg) {
        return new AppEntry(new android.content.ComponentName(pkg, pkg + ".Main"),
                null, 0L, pkg, null, true, true, 0, 0L);
    }

    private static List<AppEntry> stubApps() {
        return new ArrayList<>(Collections.singletonList(
                new AppEntry(new android.content.ComponentName("com.a", "com.a.Main"),
                        null, 0L, "com.a", null, true, true, 0, 0L)));
    }

    /** Карта из кэша: читаем приватное поле, чтобы видеть его содержимое. */
    @SuppressWarnings("unchecked")
    private static Map<String, Long> cachedDismissed(Prefs prefs) throws Exception {
        java.lang.reflect.Field f = Prefs.class.getDeclaredField("dismissedCache");
        f.setAccessible(true);
        Map<String, Long> map = (Map<String, Long>) f.get(prefs);
        check(map != null, "кэш заполнен");
        return map;
    }

    /** Плоский JSON вида {"пакет":время} в карту. */
    private static Map<String, Long> parseFlat(String raw) {
        Map<String, Long> out = new HashMap<>();
        try {
            JSONObject obj = new JSONObject(raw);
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, obj.optLong(k, 0L));
            }
        } catch (JSONException e) {
            throw new AssertionError("не удалось разобрать карту скрытых: " + e.getMessage());
        }
        return out;
    }

    private static String usageJson(String[] pkgs) {
        try {
            JSONObject root = new JSONObject();
            for (String pkg : pkgs) {
                JSONObject o = new JSONObject();
                o.put("c", 3);
                o.put("t", 5000L);
                // Ключ журнала — pkg/class#user, как его пишет recordAppLaunch.
                root.put(pkg + "/" + pkg + ".Main#0", o);
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
     * Хранилище, которое считает чтения ключей.
     *
     * Счётчик — единственный способ доказать, что кэш работает, не меряя
     * наносекунды на эмуляторе и не завися от таймингов.
     */
    private static final class CountingPrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        int dismissedReads;

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }

        @Override public String getString(String key, String defValue) {
            if ("dismissed_pkgs_json".equals(key)) dismissedReads++;
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
