package com.doxaiz.launcher;

/**
 * Чистые правила жестов, которые нужно проверить без телефона.
 *
 * Зачем отдельный класс: логика «каталог закрывается свайпом вниз» жила прямо
 * в {@code MainActivity.dispatchTouchEvent} и зависела от координат ACTION_DOWN.
 * Каталог открывается свайпом вверх по рабочему столу — то есть жест начался,
 * когда экран каталога ещё не был активен, и его ACTION_DOWN ушёл в ветку
 * рабочего стола. Ветка каталога видела только ACTION_MOVE с уже нулевыми
 * стартовыми координатами, и дельта получалась равной всему экрану: контейнер
 * уезжал вниз и каталог закрывался тем же движением, которым его открыли.
 *
 * Отсюда правило: закрывать каталог свайпом вниз можно только жестом,
 * который НАЧАЛСЯ на экране каталога (armed). Всё остальное — не закрытие.
 * Правило вынесено в статический метод, чтобы его проверял обычный JVM-тест.
 */
final class GestureRules {

    /** Множитель наклона: вниз считается только заметно вертикальный жест. */
    static final float DOWN_DRAG_DIRECTION_RATIO = 1.4f;

    /** Минимальная длина жеста вниз в dp, ниже которой это ещё не закрытие. */
    static final float DOWN_DRAG_MIN_DP = 22f;

    private GestureRules() {
    }

    /**
     * Можно ли начать интерактивное закрытие каталога свайпом вниз.
     *
     * @param armed       жест начался на экране каталога (был ACTION_DOWN в его ветке)
     * @param dy          смещение пальца по вертикали, px (вниз — положительное)
     * @param dx          смещение пальца по горизонтали, px (по модулю)
     * @param slopPx      системный touch slop в px
     * @param minPx       минимальная длина движения в px (22dp), ниже — не закрытие
     * @param inTopHeader жест начался в верхней шапке каталога
     * @param listAtTop   список приложений прокручен в самое начало
     * @return true — можно тянуть каталог вниз и закрыть его
     */
    static boolean allowsDrawerDragDown(boolean armed, float dy, float dx,
                                        float slopPx, float minPx,
                                        boolean inTopHeader, boolean listAtTop) {
        if (!armed) return false;
        if (dy <= 0f) return false;
        if (dy <= Math.max(slopPx * DOWN_DRAG_DIRECTION_RATIO, minPx)) return false;
        if (dy <= Math.abs(dx) * DOWN_DRAG_DIRECTION_RATIO) return false;
        return inTopHeader || listAtTop;
    }

    /**
     * Пора ли считать жест вверх сработавшим (открытие каталога, второе
     * приложение иконки, жест вниз).
     *
     * @param dy       смещение пальца по вертикали, px (вверх — отрицательное)
     * @param dx       смещение пальца по горизонтали, px (по модулю)
     * @param slopPx   системный touch slop в px
     * @param minUpPx  минимальная длина жеста, px
     */
    static boolean isVerticalSwipe(float dy, float dx, float slopPx, float minUpPx) {
        float need = Math.max(slopPx * 1.6f, minUpPx);
        return Math.abs(dy) > need && Math.abs(dy) > Math.abs(dx) * 1.35f;
    }
}
