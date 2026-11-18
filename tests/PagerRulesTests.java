package com.doxaiz.launcher;

/**
 * Проверки прокрутки страниц рабочего стола (без телефона).
 *
 * Зачем: «зацикленный» рабочий стол с одной страницей — это ловушка. Пальцем
 * экран тянется вбок (резиновый оттяг), а страниц всего одна, и вернуться
 * якобы некуда; при включённом зацикливании край ещё и пытается «перепрыгнуть»
 * сам на себя. Отсюда правило: при одной странице прокрутки нет вообще.
 *
 * Второе правило — зацикливание должно быть выключаемым (настройка
 * «Зацикленный свайп страниц»), и при выключенном на краю списка жест просто
 * возвращает на ту же страницу.
 */
public final class PagerRulesTests {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) {
        singlePageNeverScrolls();
        loopWrapsBothWays();
        loopOffStopsAtEdges();
        dragDecidesByDistance();
        wrapTransitionOnlyOnEdges();

        System.out.println("PagerRulesTests: " + n + " проверок пройдено");
    }

    private static void singlePageNeverScrolls() {
        for (boolean loop : new boolean[]{true, false}) {
            check(DesktopPagerView.computeTargetPage(0, 1, 0, 1080, -3000f, 400, -700f, loop) == 0,
                    "одна страница + быстрый свайп влево — остаёмся на ней (loop=" + loop + ")");
            check(DesktopPagerView.computeTargetPage(0, 1, 0, 1080, 3000f, 400, 700f, loop) == 0,
                    "одна страница + свайп вправо — остаёмся на ней (loop=" + loop + ")");
            check(DesktopPagerView.computeTargetPage(0, 1, 0, 1080, 0f, 400, 600f, loop) == 0,
                    "одна страница + протягивание — остаёмся на ней (loop=" + loop + ")");
        }
        check(!DesktopPagerView.isLoopWrapTransition(0, 0, 1, -3000f, 400, -700f, true),
                "перехода через край на одной странице быть не может");
    }

    private static void loopWrapsBothWays() {
        check(DesktopPagerView.computeTargetPage(2, 3, 2160, 1080, -3000f, 400, -700f, true) == 0,
                "последняя страница + свайп влево при зацикливании → первая");
        check(DesktopPagerView.computeTargetPage(0, 3, 0, 1080, 3000f, 400, 700f, true) == 2,
                "первая страница + свайп вправо при зацикливании → последняя");
        check(DesktopPagerView.isLoopWrapTransition(2, 0, 3, -3000f, 400, -700f, true),
                "переход последняя → первая помечен как зацикленный");
        check(DesktopPagerView.isLoopWrapTransition(0, 2, 3, 3000f, 400, 700f, true),
                "переход первая → последняя помечен как зацикленный");
    }

    private static void loopOffStopsAtEdges() {
        check(DesktopPagerView.computeTargetPage(2, 3, 2160, 1080, -3000f, 400, -700f, false) == 2,
                "зацикливание выключено: с последней страницы влево не уходим");
        check(DesktopPagerView.computeTargetPage(0, 3, 0, 1080, 3000f, 400, 700f, false) == 0,
                "зацикливание выключено: с первой страницы вправо не уходим");
        check(!DesktopPagerView.isLoopWrapTransition(2, 0, 3, -3000f, 400, -700f, false),
                "выключено — прыжка через край нет даже при расчёте цели 0");
        // Внутри диапазона выключенное зацикливание ничего не меняет.
        check(DesktopPagerView.computeTargetPage(1, 3, 1080, 1080, -3000f, 400, -700f, false) == 2,
                "зацикливание выключено: обычный переход на следующую страницу работает");
    }

    private static void dragDecidesByDistance() {
        // Медленное протягивание: цель — ближайшая страница по смещению.
        check(DesktopPagerView.computeTargetPage(0, 3, 700, 1080, 0f, 400, -700f, false) == 1,
                "протянули больше половины — переходим на следующую");
        check(DesktopPagerView.computeTargetPage(0, 3, 400, 1080, 0f, 400, -400f, false) == 0,
                "протянули меньше половины — возвращаемся");
        // Уверенное протягивание за край (свыше 24% ширины) зацикливает, если включено.
        check(DesktopPagerView.computeTargetPage(2, 3, 2160, 1080, 0f, 400, -400f, true) == 0,
                "протягивание за край больше 24% ширины при зацикливании уводит на первую");
    }

    private static void wrapTransitionOnlyOnEdges() {
        check(!DesktopPagerView.isLoopWrapTransition(1, 2, 3, -3000f, 400, -700f, true),
                "обычный переход внутри списка — не зацикленный");
        check(!DesktopPagerView.isLoopWrapTransition(2, 0, 3, 0f, 400, 700f, true),
                "расчётная цель не совпадает с направлением жеста — не прыжок");
    }
}
