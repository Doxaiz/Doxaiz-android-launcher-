package com.doxaiz.launcher;

/**
 * Регрессионные проверки оптимизаций отзывчивости (5.6).
 *
 * Покрывают только чистые функции и структуры без Android UI: кривую доводки
 * перелистывания, границы видимости страниц, кэш буквы-индекса и кэш меток
 * «новое приложение». Именно эти места правки, поэтому тесты фиксируют
 * поведение после оптимизаций.
 */
public final class Perf56Tests {
    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    /** AppEntry без конструктора: единственный способ проверить чистые методы в песочнице. */
    private static AppEntry allocateWithoutConstructor() {
        try {
            java.lang.reflect.Field unsafeField = Class.forName("sun.misc.Unsafe")
                    .getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            java.lang.reflect.Method allocate = unsafe.getClass()
                    .getMethod("allocateInstance", Class.class);
            return (AppEntry) allocate.invoke(unsafe, AppEntry.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void main(String[] args) {
        // 1. Кривая доводки: монотонна, начинается в 0, заканчивается в 1 без перелёта.
        check(DesktopPagerView.quinticOut(0f) == 0f, "curve start");
        check(Math.abs(DesktopPagerView.quinticOut(1f) - 1f) < 1e-6f, "curve end");
        float prev = -1f;
        for (int i = 0; i <= 100; i++) {
            float t = DesktopPagerView.quinticOut(i / 100f);
            check(t >= prev, "curve monotonic @" + i);
            check(t >= 0f && t <= 1f, "curve bounded @" + i);
            prev = t;
        }
        // Кривая быстрее линейной на всей середине — старт ощущается отзывчивее.
        for (int i = 1; i < 100; i++) {
            float x = i / 100f;
            check(DesktopPagerView.quinticOut(x) >= x, "curve leads linear @" + i);
        }
        check(DesktopPagerView.quinticOut(-5f) == 0f, "curve clamps below");
        check(Math.abs(DesktopPagerView.quinticOut(5f) - 1f) < 1e-6f, "curve clamps above");

        // 2. Границы видимости страниц: кадр ± соседи в работе, дальние — нет.
        check(DesktopPagerView.isPageInTransitionRange(0f), "page at center");
        check(DesktopPagerView.isPageInTransitionRange(0.999f), "page entering");
        check(DesktopPagerView.isPageInTransitionRange(-0.999f), "page leaving");
        check(!DesktopPagerView.isPageInTransitionRange(1.5f), "page far right");
        check(!DesktopPagerView.isPageInTransitionRange(-1.5f), "page far left");

        // 3. Масштаб и прозрачность страницы не выходят из допустимых границ.
        check(DesktopPagerView.computePageScale(0f) == 1f, "scale center");
        check(DesktopPagerView.computePageScale(1f) > 0.9f, "scale edge subtle");
        check(DesktopPagerView.computePageAlpha(0f) == 1f, "alpha center");
        check(DesktopPagerView.computePageAlpha(1f) > 0.5f, "alpha edge subtle");

        // 4. Кэш масштаба под частоту экрана: 120 Гц быстрее, 60 Гц — эталон.
        check(DesktopPagerView.computeRefreshDurationScale(60f) == 1f, "hz 60");
        check(DesktopPagerView.computeRefreshDurationScale(120f) < 1f, "hz 120 faster");
        check(DesktopPagerView.computeRefreshDurationScale(90f)
                > DesktopPagerView.computeRefreshDurationScale(120f), "hz 90 between");

        // 5. Буква-индекса: на устройстве кэшируется в AppEntry.indexCharFast().
        // Здесь проверяем саму функцию через рефлексию по чистому полю displayLabel:
        // ComponentName в android.jar — заглушка, поэтому AppEntry не создать.
        AppEntry probe = allocateWithoutConstructor();
        if (probe == null) {
            System.out.println("PERF56_TESTS=" + n + " (AppEntry-часть пропущена: нет Unsafe)");
            return;
        }
        check(probe != null, "index char probe allocated");
        try {
            java.lang.reflect.Field f = AppEntry.class.getDeclaredField("displayLabel");
            f.setAccessible(true);
            f.set(probe, "Telegram");
        } catch (Throwable ignored) {}
        check("T".equals(probe.getIndexChar()), "index char T");
        check(probe.indexCharFast().equals(probe.getIndexChar()), "index char cached same");
        check(probe.indexCharFast() == probe.indexCharFast(), "index char no realloc");

        // 6. «Новое приложение»: системные и уже запускавшиеся не помечаются.
        check(!Prefs.isNewUnopenedApp(true, System.currentTimeMillis(), false, System.currentTimeMillis()),
                "system app never new");
        check(!Prefs.isNewUnopenedApp(false, System.currentTimeMillis(), true, System.currentTimeMillis()),
                "launched app never new");
        check(Prefs.isNewUnopenedApp(false, System.currentTimeMillis(), false, System.currentTimeMillis()),
                "fresh app is new");
        check(!Prefs.isNewUnopenedApp(false, 0L, false, System.currentTimeMillis()),
                "unknown install time not new");

        System.out.println("PERF56_TESTS=" + n);
    }
}
