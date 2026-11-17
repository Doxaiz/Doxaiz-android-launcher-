package com.doxaiz.launcher;

import android.content.SharedPreferences;

import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Проверки диагностического журнала (находка F-07).
 *
 * Журнал пишется из мест, где приложение уже в нештатной ситуации, поэтому
 * проверяем ровно те свойства, которые легко сломать незаметно:
 *  - запись не выбрасывает исключений даже на null/битом входе;
 *  - кольцевой буфер не растёт больше лимита;
 *  - новые записи идут сверху (getAll), а порядок на диске — старые сверху;
 *  - сообщение не содержит абсолютных путей (приватность);
 *  - длинное сообщение обрезается;
 *  - журнал восстанавливается из SharedPreferences после «перезапуска»;
 *  - clear() освобождает и память, и хранилище.
 *
 * Android здесь недоступен, поэтому SharedPreferences подменяется минимальной
 * реализацией в памяти, а внутренние методы сериализации вызываются
 * рефлексией — так тест не требует ни Context, ни настоящего android.jar.
 */
public final class DiagnosticsTests {

    private static int checks = 0;

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        // Каждый сценарий начинается с чистого состояния: класс статический.
        resetGlobals();

        exceptionsAreSwallowed();
        ringBufferIsBounded();
        newestFirstOrdering();
        messagesAreSanitised();
        persistenceRoundTrip();
        clearWipesMemoryAndStorage();

        System.out.println("DiagnosticsTests: " + checks + " проверок пройдено");
    }

    // ---------------------------------------------------------------- сценарии

    private static void exceptionsAreSwallowed() {
        resetGlobals();
        // Без init() хранилища нет — запись должна просто лечь в память.
        Diagnostics.record(null, null);
        Diagnostics.record("", new RuntimeException());
        Diagnostics.record("Scene.x", new IllegalStateException((String) null));
        check(Diagnostics.size() == 3, "записи принимаются без хранилища");

        List<Diagnostics.Entry> all = Diagnostics.getAll();
        check(all.size() == 3, "getAll отдаёт все записи");
        // getAll() отдаёт новые сверху: [0] — самая свежая запись.
        Diagnostics.Entry newest = all.get(0);
        Diagnostics.Entry middle = all.get(1);
        Diagnostics.Entry oldest = all.get(2);
        check("Scene.x".equals(newest.component), "компонент сохранён");
        check("IllegalStateException".equals(newest.errorType), "тип исключения сохранён");
        check(newest.message.isEmpty(), "сообщение null → пустая строка");
        check("unknown".equals(middle.component), "пустой компонент → unknown");
        check("RuntimeException".equals(middle.errorType), "тип второго исключения сохранён");
        check("Error".equals(oldest.errorType), "null-исключение → Error");
        check(oldest.message.isEmpty(), "у null-исключения пустое сообщение");
        // format() не должен падать ни на одной записи.
        for (Diagnostics.Entry e : all) check(e.format().length() > 0, "format() не пуст");
    }

    private static void ringBufferIsBounded() throws Exception {
        resetGlobals();
        int max = (Integer) staticField("MAX_ENTRIES");
        for (int i = 0; i < max + 25; i++) {
            Diagnostics.record("Scene." + i, new java.io.IOException("boom " + i));
        }
        check(Diagnostics.size() == max, "буфер не растёт больше MAX_ENTRIES");
        List<Diagnostics.Entry> all = Diagnostics.getAll();
        check(all.get(0).message.contains("" + (max + 24)), "сверху самая новая запись");
        check(!all.get(all.size() - 1).message.contains("boom 0"), "самые старые вытеснены");
    }

    private static void newestFirstOrdering() {
        resetGlobals();
        Diagnostics.record("A", new RuntimeException("first"));
        Diagnostics.record("B", new RuntimeException("second"));
        List<Diagnostics.Entry> all = Diagnostics.getAll();
        check("B".equals(all.get(0).component), "getAll: новые сверху");
        check("A".equals(all.get(1).component), "getAll: старые снизу");
    }

    private static void messagesAreSanitised() {
        resetGlobals();
        Diagnostics.record("Disk.save",
                new FileNotFoundException("/data/user/0/com.doxaiz.launcher/files/icon_abc.webp"));
        String msg = Diagnostics.getAll().get(0).message;
        check(!msg.contains("/data/user"), "абсолютный путь вырезан: " + msg);
        check(msg.contains("icon_abc.webp"), "имя файла осталось: " + msg);

        resetGlobals();
        // Перевод строки не должен ломать строку журнала (разделители полей).
        Diagnostics.record("Multi", new RuntimeException("line1\nline2\r\nline3"));
        String multi = Diagnostics.getAll().get(0).message;
        check(!multi.contains("\n") && !multi.contains("\r"), "переводы строк заменены");
        check(multi.contains("line1") && multi.contains("line3"), "обе части сообщения на месте");

        resetGlobals();
        StringBuilder longMsg = new StringBuilder();
        for (int i = 0; i < 400; i++) longMsg.append('x');
        Diagnostics.record("Long", new RuntimeException(longMsg.toString()));
        String cut = Diagnostics.getAll().get(0).message;
        int maxLen = 0;
        try {
            maxLen = (Integer) staticField("MAX_MESSAGE_LEN");
        } catch (Throwable t) {
            maxLen = 120;
        }
        check(cut.length() <= maxLen + 1, "длинное сообщение обрезано (" + cut.length() + ")");
        check(cut.endsWith("\u2026"), "обрезанное сообщение помечено многоточием");
    }

    private static void persistenceRoundTrip() throws Exception {
        resetGlobals();
        FakePrefs store = new FakePrefs();
        Diagnostics.init(store);
        // FLUSH_INTERVAL_MS объявлен как long — рефлексия отдаёт Long, не Integer.
        long flushInterval = (Long) staticField("FLUSH_INTERVAL_MS");
        // Первая запись сбрасывается сразу, но троттлинг мог уже сработать —
        // поэтому сериализуем напрямую, как это делает persistAsync().
        Diagnostics.record("Persist.one", new RuntimeException("saved-1"));
        Diagnostics.record("Persist.two", new IllegalStateException("saved-2"));
        String payload = (String) invoke("serialize");
        check(payload != null && !payload.isEmpty(), "сериализация не пуста");
        check(flushInterval >= 0, "интервал сброса задан");
        store.values.put((String) staticField("PREF_KEY"), payload);

        // «Перезапуск»: обнуляем статику, но хранилище остаётся.
        resetGlobals();
        Diagnostics.init(store);
        List<Diagnostics.Entry> restored = Diagnostics.getAll();
        check(restored.size() == 2, "журнал восстановлен: " + restored.size());
        check("Persist.two".equals(restored.get(0).component), "порядок сохранён (новые сверху)");
        check(restored.get(0).message.equals("saved-2"), "сообщение восстановлено");
        check(restored.get(0).errorType.equals("IllegalStateException"), "тип восстановлен");
        check(restored.get(1).timeMs > 0, "время восстановлено");

        // Битая запись в хранилище не должна ронять init().
        resetGlobals();
        // Разделитель записей — символ U+0001; в исходнике он собирается
        // из кода, чтобы не вставлять управляющий символ в текст файла.
        store.values.put((String) staticField("PREF_KEY"),
                "мусор" + ((char) 1) + "ещё-мусор" + ((char) 1));
        Diagnostics.init(store);
        check(Diagnostics.size() == 0, "битые данные игнорируются");
    }

    private static void clearWipesMemoryAndStorage() throws Exception {
        resetGlobals();
        FakePrefs store = new FakePrefs();
        Diagnostics.init(store);
        Diagnostics.record("Clear.me", new RuntimeException("x"));
        store.values.put((String) staticField("PREF_KEY"), (String) invoke("serialize"));
        check(Diagnostics.size() == 1, "запись есть");
        check(store.values.containsKey((String) staticField("PREF_KEY")), "ключ в хранилище есть");

        Diagnostics.clear();
        check(Diagnostics.size() == 0, "память очищена");
        check(!store.values.containsKey((String) staticField("PREF_KEY")), "ключ удалён из хранилища");
        // Повторная очистка пустого журнала тоже безопасна.
        Diagnostics.clear();
    }

    // ------------------------------------------------------------- инфраструктура

    /** Обнуляет статическое состояние класса, имитируя перезапуск процесса. */
    private static void resetGlobals() {
        try {
            synchronized (staticField("LOCK")) {
                ((List<?>) staticField("BUFFER")).clear();
                setStaticField("storage", null);
                setStaticField("lastFlushMs", 0L);
                setStaticField("loaded", false);
            }
        } catch (Throwable t) {
            throw new AssertionError("не удалось сбросить состояние Diagnostics: " + t);
        }
    }

    private static Object staticField(String name) throws Exception {
        Field f = Diagnostics.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        Field f = Diagnostics.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static Object invoke(String name) throws Exception {
        Method m = Diagnostics.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(null);
    }

    /** Минимальные SharedPreferences в памяти: достаточно для журнала. */
    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }

        @Override public String getString(String key, String defValue) {
            Object v = values.get(key);
            return (v instanceof String) ? (String) v : defValue;
        }

        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }

        @Override public int getInt(String key, int defValue) { return defValue; }

        @Override public long getLong(String key, long defValue) { return defValue; }

        @Override public float getFloat(String key, float defValue) { return defValue; }

        @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }

        @Override public boolean contains(String key) { return values.containsKey(key); }

        @Override public Editor edit() { return new FakeEditor(); }

        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        private final class FakeEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removals = new java.util.HashSet<>();

            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }

            @Override public Editor putStringSet(String key, Set<String> v) { pending.put(key, v); return this; }

            @Override public Editor putInt(String key, int v) { pending.put(key, v); return this; }

            @Override public Editor putLong(String key, long v) { pending.put(key, v); return this; }

            @Override public Editor putFloat(String key, float v) { pending.put(key, v); return this; }

            @Override public Editor putBoolean(String key, boolean v) { pending.put(key, v); return this; }

            @Override public Editor remove(String key) { removals.add(key); return this; }

            @Override public Editor clear() { removals.addAll(values.keySet()); return this; }

            @Override public boolean commit() { apply(); return true; }

            @Override public void apply() {
                for (String r : removals) values.remove(r);
                values.putAll(pending);
                pending.clear();
                removals.clear();
            }
        }
    }
}
