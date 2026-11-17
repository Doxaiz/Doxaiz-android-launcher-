package com.doxaiz.launcher;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Проверки машины состояний аварийного возврата (F-22).
 *
 * Сами действия (запуск другого лаунчера, диалог роли HOME) требуют Android и
 * проверяются на устройстве/эмуляторе. Здесь — то, что легко сломать незаметно
 * и что определяет, снимем ли мы роль HOME у работающего приложения:
 *
 *  - разовый сбой не должен увеличивать счётчик до порога;
 *  - второй сбой подряд — должен;
 *  - успешный старт обнуляет счётчик;
 *  - старый «висящий» запуск (выгрузка системой) не считается падением;
 *  - выключенная настройка полностью отключает автоматику;
 *  - сброс стирает и счётчик, и запомненный лаунчер.
 *
 * Prefs создаётся через reflection (в unit-тестах нет Context), поэтому
 * SharedPreferences подменяется реализацией в памяти.
 */
public final class CrashGuardTests {

    private static int checks = 0;

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        freshLaunchAfterCleanInstall();
        singleCrashDoesNotReachThreshold();
        twoCrashesReachThreshold();
        successfulStartResets();
        stalePendingIsNotACrash();
        disabledGuardIsSilent();
        resetClearsEverything();

        System.out.println("CrashGuardTests: " + checks + " проверок пройдено");
    }

    // ---------------------------------------------------------------- сценарии

    private static void freshLaunchAfterCleanInstall() throws Exception {
        FakePrefs store = new FakePrefs();
        Prefs prefs = newPrefs(store);
        int streak = CrashGuard.onDesktopLaunchStart(prefs);
        check(streak == 0, "первый запуск — не падение");
        check(store.getLong("crash_guard_pending", 0L) > 0L, "отметка запуска поставлена");
    }

    private static void singleCrashDoesNotReachThreshold() throws Exception {
        FakePrefs store = new FakePrefs();
        Prefs prefs = newPrefs(store);

        CrashGuard.onDesktopLaunchStart(prefs);          // запуск №1
        int streak = CrashGuard.onDesktopLaunchStart(prefs); // процесс умер, запуск №2
        check(streak == 1, "один сбой → 1");
        check(streak < CrashGuard.CRASHES_TO_FALLBACK, "порог не достигнут с первого сбоя");
    }

    private static void twoCrashesReachThreshold() throws Exception {
        FakePrefs store = new FakePrefs();
        Prefs prefs = newPrefs(store);

        CrashGuard.onDesktopLaunchStart(prefs);              // запуск №1 (умрёт)
        check(CrashGuard.onDesktopLaunchStart(prefs) == 1, "первый сбой");
        int streak = CrashGuard.onDesktopLaunchStart(prefs);  // тоже умер
        check(streak == CrashGuard.CRASHES_TO_FALLBACK, "два сбоя подряд → порог");
        check(streak >= CrashGuard.CRASHES_TO_FALLBACK, "пора возвращать лаунчер");
    }

    private static void successfulStartResets() throws Exception {
        FakePrefs store = new FakePrefs();
        Prefs prefs = newPrefs(store);

        CrashGuard.onDesktopLaunchStart(prefs);
        int streak = CrashGuard.onDesktopLaunchStart(prefs);
        check(streak == 1, "сбой зафиксирован");

        // Рабочий стол показан и сбой был разовым — метки стираются целиком.
        CrashGuard.onStartupComplete(prefs, false);
        check(store.getInt("crash_guard_streak", -1) == 0, "счётчик обнулён");
        check(store.getLong("crash_guard_pending", -1L) == 0L, "отметка запуска снята");

        int after = CrashGuard.onDesktopLaunchStart(prefs);
        check(after == 0, "после успешного старта следующий запуск чистый");
    }

    private static void stalePendingIsNotACrash() throws Exception {
        FakePrefs store = new FakePrefs();
        Prefs prefs = newPrefs(store);

        // Процесс был выгружен системой давно: отметка старше окна падения.
        long stale = System.currentTimeMillis() - CrashGuard.CRASH_WINDOW_MS - 60_000L;
        store.putLong("crash_guard_pending", stale);
        check(CrashGuard.onDesktopLaunchStart(prefs) == 0, "старая отметка — не падение");
        check(store.getInt("crash_guard_streak", -1) == 0, "счётчик сброшен, а не увеличен");
    }

    private static void disabledGuardIsSilent() throws Exception {
        FakePrefs store = new FakePrefs();
        Prefs prefs = newPrefs(store);

        CrashGuard.onDesktopLaunchStart(prefs);
        CrashGuard.onDesktopLaunchStart(prefs);
        check(store.getInt("crash_guard_streak", 0) == 1, "сбой зафиксирован до отключения");

        CrashGuard.setEnabled(prefs, false);
        check(!CrashGuard.isEnabled(prefs), "настройка выключена");
        int streak = CrashGuard.onDesktopLaunchStart(prefs);
        check(streak == 0, "выключенный возврат не считает сбои");
    }

    private static void resetClearsEverything() throws Exception {
        FakePrefs store = new FakePrefs();
        Prefs prefs = newPrefs(store);
        CrashGuard.onDesktopLaunchStart(prefs);
        CrashGuard.onDesktopLaunchStart(prefs);
        prefs.setCrashGuardFallback("com.example.homelauncher", System.currentTimeMillis());
        check("com.example.homelauncher".equals(prefs.getCrashGuardFallback()),
                "лаунчер запомнен");

        CrashGuard.resetStreak(prefs);
        check(prefs.getCrashGuardStreak() == 0, "счётчик сброшен");
        check(prefs.getCrashGuardPending() == 0L, "отметка запуска сброшена");
        check(prefs.getCrashGuardFallback() == null, "запомненный лаунчер сброшен");
        check(prefs.getCrashGuardLastFallback() == 0L, "время возврата сброшено");
    }

    // ------------------------------------------------------------- инфраструктура

    /** Prefs поверх поддельного хранилища: конструктор от SharedPreferences есть в самом классе. */
    private static Prefs newPrefs(SharedPreferences sp) {
        return new Prefs(sp);
    }

    /** Минимальные SharedPreferences в памяти. */
    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }

        @Override public String getString(String key, String defValue) {
            Object v = values.get(key);
            return (v instanceof String) ? (String) v : defValue;
        }

        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }

        @Override public int getInt(String key, int defValue) {
            Object v = values.get(key);
            return (v instanceof Integer) ? (Integer) v : defValue;
        }

        @Override public long getLong(String key, long defValue) {
            Object v = values.get(key);
            return (v instanceof Long) ? (Long) v : defValue;
        }

        /** Помощник для теста: положить значение напрямую, минуя Editor. */
        void putLong(String key, long value) { values.put(key, value); }

        @Override public float getFloat(String key, float defValue) { return defValue; }

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
