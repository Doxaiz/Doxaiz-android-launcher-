package com.doxaiz.launcher;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * F-06/F-21: приватность экспорта и откат неудачного импорта резервной копии.
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
 * Скоуп приватных полей при экспорте/импорте, чтение актуальной раскладки,
 * обратная совместимость старых файлов и поведение отката на поддельных
 * SharedPreferences: значения возвращаются, лишние ключи исчезают, а результат
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
        privateExportIsScopedAndUsesLiveLayout();
        layoutOnlyImportIgnoresInjectedPrivateFields();
        protectedImportRequiresPrivacyAuthorization();
        legacyBackupStillImportsPrivateFields();

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

    /**
     * Экспорт по умолчанию исключает чувствительные привязки приложений и берёт
     * актуальную раскладку из модели, а не из ещё не обновлённой SharedPreferences.
     */
    private static void privateExportIsScopedAndUsesLiveLayout() throws Exception {
        FakePrefs store = new FakePrefs();
        Prefs prefs = seedPrivatePrefs(store, false);
        final String staleKey = "com.stale.desktop/com.stale.Main#0";
        final String liveKey = "com.visible.home/com.visible.Main#0";
        prefs.setDesktopJson(validDesktopJson(staleKey).toString());
        DesktopModel desktop = new DesktopModel(prefs);
        desktop.pages.clear();
        desktop.pages.add(new java.util.ArrayList<>());
        desktop.pages.get(0).add(DesktopItem.appAt(liveKey, 1, 2));
        desktop.markInitialized();

        String compactJson = BackupHelper.exportBackupJson(prefs, desktop, false);
        check(compactJson != null, "копия без приватных данных сформирована");
        JSONObject compact = new JSONObject(compactJson);
        JSONObject settings = compact.getJSONObject("settings");
        String encoded = compact.toString();
        check(!compact.optBoolean("privacyIncluded", true), "экспорт отмечен как без приватных данных");
        check(!compact.has("hidden") && !compact.has("renames"), "нет скрытых приложений и переименований");
        check(!compact.has("customCategories") && !compact.has("customSections"),
                "нет пользовательских категорий и разделов с package names");
        check(!compact.has("iconSwipeUp") && !compact.has("lockedRecents"),
                "нет per-app жестов и закреплений в Недавних");
        check(!settings.has("protectHidden") && !settings.has("hiddenStdCats"),
                "нет настроек защиты/скрытия категорий");
        check(encoded.contains(liveKey) && !encoded.contains(staleKey),
                "экспорт берёт текущую раскладку, а не устаревшую строку Prefs");
        check(!encoded.contains("com.private.secret"), "в компактной копии нет приватных package names");

        String fullJson = BackupHelper.exportBackupJson(prefs, desktop, true);
        check(fullJson != null, "полная копия сформирована");
        JSONObject full = new JSONObject(fullJson);
        JSONObject fullSettings = full.getJSONObject("settings");
        check(full.optBoolean("privacyIncluded", false), "полная копия отмечена как приватная");
        check(full.has("hidden") && full.has("renames"), "полная копия содержит список скрытых и имена");
        check(full.has("customCategories") && full.has("customSections"),
                "полная копия содержит категории и пользовательские разделы");
        check(full.has("iconSwipeUp") && full.has("lockedRecents"),
                "полная копия содержит per-app жесты и закрепления");
        check(fullSettings.has("protectHidden") && fullSettings.has("hiddenStdCats"),
                "полная копия сохраняет приватные настройки каталога");
        check(full.toString().contains("com.private.secret"), "полная копия содержит выбранные private package names");
    }

    /** Поле privacyIncluded=false нельзя обойти, вручную добавив private JSON-ключи. */
    private static void layoutOnlyImportIgnoresInjectedPrivateFields() throws Exception {
        Prefs prefs = seedPrivatePrefs(new FakePrefs(), false);
        DesktopModel desktop = new DesktopModel(prefs);
        JSONObject file = privateSourceBackup();
        file.put("privacyIncluded", false); // Представляет подменённый/старый редактором «безопасный» файл.

        BackupHelper.ImportResult result = BackupHelper.importBackupJsonDetailed(
                file.toString(), prefs, desktop, false);
        check(result.outcome == BackupHelper.ImportOutcome.IMPORTED,
                "раскладка импортируется, даже если private-поля проигнорированы");
        assertSeedPrivateStateUnchanged(prefs, "layout-only import", false);
    }

    /** Если приватное пространство защищено, полная копия без auth не меняет private-состояние. */
    private static void protectedImportRequiresPrivacyAuthorization() throws Exception {
        Prefs prefs = seedPrivatePrefs(new FakePrefs(), true);
        DesktopModel desktop = new DesktopModel(prefs);
        JSONObject file = privateSourceBackup();
        file.put("privacyIncluded", true);
        file.put("hidden", new JSONArray().put("com.attacker.hidden/com.attacker.Main#0"));
        file.put("renames", new JSONObject().put("com.kept.app/com.kept.Main#0", "Подменено"));

        BackupHelper.ImportResult result = BackupHelper.importBackupJsonDetailed(
                file.toString(), prefs, desktop, false);
        check(result.outcome == BackupHelper.ImportOutcome.IMPORTED,
                "обычная раскладка импортируется без права менять защищённые данные");
        assertSeedPrivateStateUnchanged(prefs, "protected import without auth", true);
    }

    /** Старые файлы без privacyIncluded по-прежнему трактуются как полные копии. */
    private static void legacyBackupStillImportsPrivateFields() throws Exception {
        Prefs sourcePrefs = seedPrivatePrefs(new FakePrefs(), false);
        DesktopModel sourceDesktop = new DesktopModel(sourcePrefs);
        sourceDesktop.pages.clear();
        sourceDesktop.pages.add(new java.util.ArrayList<>());
        sourceDesktop.pages.get(0).add(DesktopItem.appAt(
                "com.visible.home/com.visible.Main#0", 0, 0));
        sourceDesktop.markInitialized();
        JSONObject legacy = new JSONObject(BackupHelper.exportBackupJson(sourcePrefs, sourceDesktop, true));
        legacy.remove("privacyIncluded");

        Prefs targetPrefs = new Prefs(new FakePrefs());
        DesktopModel targetDesktop = new DesktopModel(targetPrefs);
        BackupHelper.ImportResult result = BackupHelper.importBackupJsonDetailed(
                legacy.toString(), targetPrefs, targetDesktop, false);
        check(result.outcome == BackupHelper.ImportOutcome.IMPORTED,
                "старый формат без privacyIncluded импортирован");
        check(targetPrefs.getHiddenApps().contains("com.private.secret/com.private.Main#0"),
                "совместимость: старый hidden импортирован");
        check("Личное".equals(targetPrefs.getCustomSections().get(0).name),
                "совместимость: старый customSections импортирован");
        check("com.private.target/com.private.Main#0".equals(targetPrefs.getIconSwipeUpTarget(
                "com.private.secret/com.private.Main#0")), "совместимость: старый iconSwipeUp импортирован");
        check(targetPrefs.getLockedRecentsPackages().contains("com.private.secret"),
                "совместимость: старые lockedRecents импортированы");
    }

    private static Prefs seedPrivatePrefs(FakePrefs store, boolean protectedSpace) {
        Prefs prefs = new Prefs(store);
        String privateKey = "com.private.secret/com.private.Main#0";
        prefs.hideApp(privateKey);
        prefs.setCustomLabel(privateKey, "Личное приложение");
        prefs.setCustomCategory("com.private.secret", AppCategoryHelper.CAT_FINANCE);
        prefs.saveCustomSections(java.util.Collections.singletonList(
                new CustomSection("sec_private", "Личное", java.util.Collections.singletonList("com.private.secret"))));
        prefs.setIconSwipeUpTarget(privateKey, "com.private.target/com.private.Main#0");
        prefs.setLockedRecentsPackages(new HashSet<>(java.util.Collections.singletonList("com.private.secret")));
        prefs.setHiddenStdCategories(java.util.Collections.singleton(AppCategoryHelper.CAT_FINANCE));
        prefs.setProtectHiddenApps(protectedSpace);
        return prefs;
    }

    private static void assertSeedPrivateStateUnchanged(Prefs prefs, String label, boolean expectedProtected) {
        String privateKey = "com.private.secret/com.private.Main#0";
        check(prefs.getHiddenApps().contains(privateKey), label + ": hidden сохранён");
        check("Личное приложение".equals(prefs.getCustomLabels().get(privateKey)), label + ": rename сохранён");
        check(prefs.getCustomCategory("com.private.secret") == AppCategoryHelper.CAT_FINANCE,
                label + ": custom category сохранена");
        check(!prefs.getCustomSections().isEmpty()
                        && "Личное".equals(prefs.getCustomSections().get(0).name),
                label + ": custom section сохранён");
        check("com.private.target/com.private.Main#0".equals(prefs.getIconSwipeUpTarget(privateKey)),
                label + ": iconSwipeUp сохранён");
        check(prefs.getLockedRecentsPackages().contains("com.private.secret"),
                label + ": lockedRecents сохранён");
        check(prefs.getHiddenStdCategories().contains(AppCategoryHelper.CAT_FINANCE),
                label + ": hiddenStdCats сохранён");
        check(prefs.isProtectHiddenApps() == expectedProtected, label + ": состояние защиты сохранено");
    }

    private static JSONObject privateSourceBackup() throws Exception {
        JSONObject root = new JSONObject();
        root.put("desktop", validDesktopJson("com.imported.app/com.imported.Main#0"));
        root.put("privacyIncluded", true);
        root.put("settings", new JSONObject()
                .put("protectHidden", false)
                .put("hiddenStdCats", ""));
        root.put("hidden", new JSONArray().put("com.attacker.hidden/com.attacker.Main#0"));
        root.put("renames", new JSONObject().put("com.kept.app/com.kept.Main#0", "Импортировано"));
        root.put("customSections", new JSONArray().put(new JSONObject()
                .put("id", "sec_new").put("name", "Новый раздел")
                .put("pkgs", new JSONArray().put("com.new.app"))));
        root.put("iconSwipeUp", new JSONObject().put("com.kept.app", "com.other.app"));
        root.put("lockedRecents", new JSONArray().put("com.other.app"));
        root.put("customCategories", new JSONObject().put("com.other.app", AppCategoryHelper.CAT_FINANCE));
        return root;
    }

    private static JSONObject validDesktopJson(String appKey) throws Exception {
        JSONObject item = new JSONObject();
        item.put("type", "app");
        item.put("key", appKey);
        item.put("col", 0);
        item.put("row", 0);
        JSONArray page = new JSONArray().put(item);
        return new JSONObject()
                .put("pages", new JSONArray().put(page))
                .put("dock", new JSONArray());
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
