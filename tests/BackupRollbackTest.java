package com.doxaiz.launcher;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * F-21: откат неудачного импорта резервной копии.
 *
 * <h3>Что было не так</h3>
 * В аудите §14.4 записано: «{@code restoreFromRollback} использует {@code commit()}
 * — синхронная запись в фоновом потоке, не блокирует UI, но остаётся». При
 * проверке оказалось, что и это описание мягче факта, и рядом есть дефект хуже:
 * <ul>
 *   <li>{@code importBackupJson} вызывается из {@code runOnUiThread}, то есть
 *       {@code commit()} писал preferences <b>на главном потоке</b>, а снимок
 *       содержит все ключи SharedPreferences;</li>
 *   <li>сбой самого отката уходил в {@code catch (Throwable ignored)}: настройки
 *       оставались частично применёнными, и в журнале не было ни строки;</li>
 *   <li>пользователю в этом случае показывали «Файл не является резервной копией
 *       Doxaiz» — то есть при сбое на его же правильном файле предлагали искать
 *       другую копию.</li>
 * </ul>
 *
 * <h3>Что проверяется</h3>
 * Поведение отката на поддельных SharedPreferences: значения возвращаются,
 * лишние ключи (появившиеся во время импорта) исчезают, а результат отката
 * честно сообщается вызывающему. Проверки идут через реальный код {@link Prefs}
 * и {@link BackupHelper}, а не через копию логики в тесте.
 */
public final class BackupRollbackTest {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) throws Exception {
        rollbackRestoresValues();
        rollbackReportsFailure();
        rejectedFileIsNotConfusedWithBrokenRollback();
        importStillAppliesValidBackup();

        System.out.println("BACKUP_ROLLBACK_TESTS=" + n);
    }

    /** Откат возвращает прежние значения и убирает ключи, которых не было. */
    private static void rollbackRestoresValues() {
        FakePrefs store = new FakePrefs();
        store.values.put("simple_visual_style", 2);
        store.values.put("columns", 4);
        store.values.put("hidden_apps", new HashSet<>(java.util.Arrays.asList("com.a/b#0")));
        Prefs prefs = new Prefs(store);

        Map<String, Object> snapshot = prefs.snapshotForRollback();
        check(snapshot.containsKey("simple_visual_style"), "снимок содержит стиль");
        check(snapshot.containsKey("hidden_apps"), "снимок содержит скрытые приложения");

        // Импорт наполовину применился: стиль сменился, появился новый ключ.
        prefs.setVisualStyle(Prefs.STYLE_CLASSIC_LIGHT);
        store.values.put("appeared_during_import", "мусор");
        check(prefs.getVisualStyle() == Prefs.STYLE_CLASSIC_LIGHT, "до отката стиль изменён");

        boolean restored = prefs.restoreFromRollback(snapshot);
        check(restored, "откат сообщает об успехе");
        check(prefs.getVisualStyle() == 2, "стиль вернулся к снимку");
        check(prefs.getColumns() == 4, "колонки вернулись к снимку");
        check(prefs.getHiddenApps().contains("com.a/b#0"),
                "скрытые приложения вернулись к снимку");
        check(!store.values.containsKey("appeared_during_import"),
                "ключ, появившийся во время импорта, удалён");
    }

    /**
     * Сбой записи не должен выглядеть успехом.
     *
     * Хранилище, которое бросает исключение на {@code edit()}, — это не
     * гипотетика: так ведёт себя диск с ошибкой ввода-вывода. Раньше исключение
     * гасилось, и вызывающий код не мог отличить «вернули» от «не вернули».
     */
    private static void rollbackReportsFailure() {
        SharedPreferences broken = new ThrowingPrefs();
        Prefs prefs = new Prefs(broken);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("simple_visual_style", 2);
        check(!prefs.restoreFromRollback(snapshot), "сбой записи — это не успех");
        check(!prefs.restoreFromRollback(null), "пустой снимок — не успех");
    }

    /**
     * Главное разделение: «файл не подошёл» и «файл подошёл, но применён не
     * полностью». Второй случай обязан отличаться, иначе пользователю снова
     * скажут «ищите другую копию».
     */
    private static void rejectedFileIsNotConfusedWithBrokenRollback() throws Exception {
        Prefs prefs = new Prefs(new FakePrefs());

        BackupHelper.ImportResult garbage =
                BackupHelper.importBackupJsonDetailed("не json", prefs, null, false);
        check(garbage.outcome == BackupHelper.ImportOutcome.REJECTED,
                "мусор — это REJECTED, а не сбой отката");

        JSONObject wrongShape = new JSONObject();
        wrongShape.put("pages", "это не массив");
        BackupHelper.ImportResult wrong =
                BackupHelper.importBackupJsonDetailed(wrongShape.toString(), prefs, null, false);
        check(wrong.outcome == BackupHelper.ImportOutcome.REJECTED,
                "чужая структура — это REJECTED");

        BackupHelper.ImportResult empty =
                BackupHelper.importBackupJsonDetailed("", prefs, null, false);
        check(empty.outcome == BackupHelper.ImportOutcome.REJECTED, "пустая строка — REJECTED");
    }

    /** Исправная копия по-прежнему импортируется (правка не сломала успех). */
    private static void importStillAppliesValidBackup() throws Exception {
        FakePrefs store = new FakePrefs();
        Prefs prefs = new Prefs(store);
        DesktopModel desktop = new DesktopModel(prefs);
        desktop.addPage();

        // Пустая страница без дока — по правилам validateDesktopJson это НЕ
        // бэкап (произвольный {"pages":[[]]} от чужого файла не отличить).
        // Поэтому в копии лежит настоящее приложение.
        JSONObject item = new JSONObject();
        item.put("type", "app");
        item.put("key", "com.android.settings/com.android.settings.Settings#0");
        item.put("x", 0);
        item.put("y", 0);
        JSONArray page = new JSONArray().put(item);
        JSONArray pages = new JSONArray().put(page);
        JSONObject desk = new JSONObject();
        desk.put("pages", pages);
        desk.put("dock", new JSONArray());
        JSONObject root = new JSONObject();
        root.put("desktop", desk);
        JSONObject settings = new JSONObject();
        settings.put("columns", 5);
        root.put("settings", settings);

        BackupHelper.ImportResult ok =
                BackupHelper.importBackupJsonDetailed(root.toString(), prefs, desktop, false);
        check(ok.outcome == BackupHelper.ImportOutcome.IMPORTED, "исправная копия импортирована");
        check(ok.imported(), "imported() совпадает с исходом");
        check(prefs.getColumns() == 5, "настройка из копии применена");
    }

    // ---------------------------------------------------------- инфраструктура

    /** Минимальные SharedPreferences в памяти; откат проверяется на них. */
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
            private boolean clear = false;

            @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, new HashSet<>(v)); return this; }
            @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
            @Override public Editor remove(String k) { return this; }
            @Override public Editor clear() { clear = true; return this; }

            @Override public boolean commit() { apply(); return true; }

            @Override public void apply() {
                if (clear) values.clear();
                values.putAll(pending);
                pending.clear();
            }
        }
    }

    /**
     * Хранилище, у которого запись невозможна: {@code edit()} бросает исключение.
     *
     * Так проверяется, что сбой отката доходит до вызывающего кода, а не
     * исчезает в пустом catch.
     */
    private static final class ThrowingPrefs implements SharedPreferences {
        @Override public Map<String, ?> getAll() { return new HashMap<>(); }
        @Override public String getString(String k, String d) { return d; }
        @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
        @Override public int getInt(String k, int d) { return d; }
        @Override public long getLong(String k, long d) { return d; }
        @Override public float getFloat(String k, float d) { return d; }
        @Override public boolean getBoolean(String k, boolean d) { return d; }
        @Override public boolean contains(String k) { return false; }
        @Override public Editor edit() { throw new IllegalStateException("диск недоступен"); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    }
}
