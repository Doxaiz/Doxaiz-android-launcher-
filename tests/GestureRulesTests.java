package com.doxaiz.launcher;

/**
 * Проверки правил жестов каталога (без телефона).
 *
 * Главный случай — тот, из-за которого каталог «багался» на устройстве:
 * свайп вверх по рабочему столу открывает каталог, и хвост этого жеста
 * приходил в ветку каталога уже без ACTION_DOWN. Стартовые координаты
 * оставались нулевыми, дельта равнялась всему экрану, контейнер уезжал вниз и
 * каталог закрывался тем же движением. Правило: закрывать свайпом вниз можно
 * только жест, начавшийся на экране каталога.
 *
 * Запуск: {@code java com.doxaiz.launcher.GestureRulesTests} или через
 * {@code ./gradlew test} (обёртка LauncherChecksTest).
 */
public final class GestureRulesTests {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) {
        // Свайп, которым каталог открыли: ACTION_DOWN был на рабочем столе.
        noCloseWithoutOwnDown();
        // Обычное закрытие: жест начался на экране каталога.
        closingSwipeStillWorks();
        // Горизонтальный жест не должен закрывать каталог.
        horizontalSwipeIsNotClose();
        // Короткое движение — не закрытие, а «не дотянул».
        tinyMoveIsNotClose();
        // Вверх по каталогу — это прокрутка списка.
        upwardMoveIsNotClose();
        // Список прокручен: тянем вниз только из шапки.
        midListDragNeedsHeader();
        // Открытие каталога свайпом вверх по-прежнему срабатывает.
        swipeUpStillOpens();

        // Токен вида ИМЯ=<число> — контракт с обёрткой LauncherChecksTest: она
        // требует его и падает, если проверок ноль. Иначе набор, из которого
        // убрали все проверки, «проходил» бы молча (см. AUDIT §48).
        if (n <= 0) throw new AssertionError("ни одной проверки не выполнено");
        System.out.println("GESTURE_RULES_CHECKS=" + n);
    }

    private static void noCloseWithoutOwnDown() {
        float screenHeight = 2000f;
        // dy = весь экран, как было при нулевых стартовых координатах.
        check(!GestureRules.allowsDrawerDragDown(false, screenHeight, 4f, 8f, 44f, true, true),
                "хвост чужого жеста не должен закрывать каталог");
        check(!GestureRules.allowsDrawerDragDown(false, 300f, 2f, 8f, 44f, true, true),
                "без ACTION_DOWN на экране каталога закрытие запрещено всегда");
    }

    private static void closingSwipeStillWorks() {
        check(GestureRules.allowsDrawerDragDown(true, 260f, 6f, 8f, 44f, true, false),
                "жест из шапки каталога закрывает его");
        check(GestureRules.allowsDrawerDragDown(true, 180f, 10f, 8f, 44f, false, true),
                "жест из начала списка закрывает каталог");
        check(GestureRules.allowsDrawerDragDown(true, 60f, 3f, 8f, 44f, true, true),
                "короткий жест из шапки всё ещё тянет каталог вниз");
        check(!GestureRules.allowsDrawerDragDown(true, 40f, 3f, 8f, 44f, true, true),
                "жест короче 22dp каталог не тянет");
    }

    private static void horizontalSwipeIsNotClose() {
        check(!GestureRules.allowsDrawerDragDown(true, 120f, 200f, 8f, 44f, true, true),
                "горизонтальный жест — не закрытие");
    }

    private static void tinyMoveIsNotClose() {
        check(!GestureRules.allowsDrawerDragDown(true, 10f, 1f, 8f, 44f, true, true),
                "дрожание пальца на 10 px не закрывает каталог");
        check(!GestureRules.allowsDrawerDragDown(true, 0f, 0f, 8f, 44f, true, true),
                "нулевое смещение не закрывает каталог");
        check(!GestureRules.allowsDrawerDragDown(true, -300f, 2f, 8f, 44f, true, true),
                "смещение вверх не закрывает каталог");
    }

    private static void upwardMoveIsNotClose() {
        check(!GestureRules.allowsDrawerDragDown(true, -400f, 5f, 8f, 44f, true, true),
                "движение вверх по каталогу — прокрутка, не закрытие");
    }

    private static void midListDragNeedsHeader() {
        // Середина списка: тянем вниз, но список не в начале — закрывать нельзя.
        check(!GestureRules.allowsDrawerDragDown(true, 200f, 4f, 8f, 44f, false, false),
                "из середины прокрученного списка каталог не закрывается");
        // Та же позиция, но жест начался в шапке — можно.
        check(GestureRules.allowsDrawerDragDown(true, 200f, 4f, 8f, 44f, true, false),
                "из шапки закрытие работает при любой прокрутке");
    }

    private static void swipeUpStillOpens() {
        // Порог тот же, что в приложении: max(slop * 1.6, 36dp).
        float slop = 8f;
        float minPx = 72f;
        check(GestureRules.isVerticalSwipe(-300f, 10f, slop, minPx),
                "свайп вверх на 300 px открывает каталог");
        check(!GestureRules.isVerticalSwipe(-40f, 2f, slop, minPx),
                "короткое движение вверх каталог не открывает");
        check(!GestureRules.isVerticalSwipe(-200f, 220f, slop, minPx),
                "диагональный жест не считается свайпом вверх");
        check(GestureRules.isVerticalSwipe(260f, 8f, slop, minPx),
                "свайп вниз по рабочему столу тоже срабатывает");
    }
}
