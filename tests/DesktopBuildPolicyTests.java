package com.doxaiz.launcher;

/**
 * Проверки решения «строить рабочий стол или отложить» (§51.5).
 *
 * <p>Правка холодного старта откладывалась именно потому, что её нельзя было
 * проверить: на эмуляторе всегда есть 19 системных приложений, а CI не умеет
 * поднимать устройство без единого приложения. Поэтому логика вынесена в
 * {@link DesktopBuildPolicy} — чистую функцию, у которой проверяются все
 * исходы, включая те, что на эмуляторе недостижимы.
 */
public final class DesktopBuildPolicyTests {
    private static int checks;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        checks++;
    }

    private static void isBuild(DesktopBuildPolicy.Decision d, String label) {
        check(d == DesktopBuildPolicy.Decision.BUILD, label);
    }

    private static void isDefer(DesktopBuildPolicy.Decision d, String label) {
        check(d == DesktopBuildPolicy.Decision.DEFER, label);
    }

    public static void main(String[] args) {
        // 1. Приложения есть — строим всегда, при любом состоянии загрузки.
        isBuild(DesktopBuildPolicy.decide(18, true, false, false, false), "apps: ранняя загрузка");
        isBuild(DesktopBuildPolicy.decide(18, true, true, false, false), "apps: загрузка завершена");
        isBuild(DesktopBuildPolicy.decide(18, true, true, true, false), "apps: загрузка с ошибкой");
        isBuild(DesktopBuildPolicy.decide(1, false, false, false, false), "apps: один, загрузка не начиналась");

        // 2. Приложений нет, загрузка идёт — откладываем. Это и есть устранение
        //    того единственного впустую потраченного прохода сборки из §51.
        isDefer(DesktopBuildPolicy.decide(0, true, false, false, false), "нет данных: загрузка идёт");
        isDefer(DesktopBuildPolicy.decide(0, true, false, false, false), "нет данных: повторный вызов тоже DEFER");

        // 3. Загрузка не начиналась — откладывать нельзя, иначе экран останется
        //    пустым навсегда (ровно эта ошибка уже была в истории проекта).
        isBuild(DesktopBuildPolicy.decide(0, false, false, false, false), "нет данных: загрузка не начиналась");

        // 4. Загрузка провалилась — пустой список это ответ, а не ожидание.
        isBuild(DesktopBuildPolicy.decide(0, true, true, true, false), "нет данных: загрузка с ошибкой");

        // 5. Уже что-то построено — не стираем готовое ради ожидания.
        isBuild(DesktopBuildPolicy.decide(0, true, false, false, true), "нет данных, но стол уже построен");

        // 6. Завершённая успешная загрузка с пустым списком: приложений
        //    действительно нет (например, все скрыты). Ждать нечего.
        // Завершённая загрузка (успешная или с ошибкой) означает «ждать больше
        // нечего». Успешное завершение с нулём приложений — не выдумка: все
        // приложения могут быть скрыты. DEFER здесь оставил бы экран пустым без
        // объяснения и без надежды на следующий onAppsUpdated.
        isBuild(DesktopBuildPolicy.decide(0, true, true, false, false), "нет данных: загрузка завершена без ошибки");
        isBuild(DesktopBuildPolicy.decide(0, true, true, false, true), "завершена без ошибки, стол уже построен");

        // 7. Отрицательное число приложений (защита от неверного вызова):
        //    трактуется как «данных нет», а не как «есть приложения».
        isBuild(DesktopBuildPolicy.decide(-1, false, false, false, false), "отрицательный счёт: BUILD");
        isDefer(DesktopBuildPolicy.decide(-1, true, false, false, false), "отрицательный счёт: DEFER");

        // 8. Все комбинации пяти флагов дают ровно два исхода и никогда не
        //    бросают исключений: перебор, а не доверие к чтению кода.
        int build = 0, defer = 0;
        for (int mask = 0; mask < 32; mask++) {
            boolean started = (mask & 1) != 0;
            boolean finished = (mask & 2) != 0;
            boolean failed = (mask & 4) != 0;
            boolean built = (mask & 8) != 0;
            int count = ((mask & 16) != 0) ? 3 : 0;
            DesktopBuildPolicy.Decision d =
                    DesktopBuildPolicy.decide(count, started, finished, failed, built);
            if (d == null) throw new AssertionError("null решение при mask=" + mask);
            if (d == DesktopBuildPolicy.Decision.BUILD) build++;
            else defer++;
        }
        check(build + defer == 32, "перебор покрыл все комбинации");
        check(build > 0 && defer > 0, "оба исхода достижимы");

        // 9. DEFER невозможен без запущенной загрузки — главная гарантия
        //    безопасности правки: откладываем только когда данные точно придут.
        for (int mask = 0; mask < 16; mask++) {
            boolean started = false;
            boolean finished = (mask & 1) != 0;
            boolean failed = (mask & 2) != 0;
            boolean built = (mask & 4) != 0;
            for (int count = 0; count <= 2; count++) {
                DesktopBuildPolicy.Decision d =
                        DesktopBuildPolicy.decide(count, started, finished, failed, built);
                check(d == DesktopBuildPolicy.Decision.BUILD,
                        "без запущенной загрузки DEFER запрещён: count=" + count + " mask=" + mask);
            }
        }

        System.out.println("DESKTOP_BUILD_POLICY_CHECKS=" + checks);
    }
}
