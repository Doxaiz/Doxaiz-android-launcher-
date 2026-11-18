package com.doxaiz.launcher;

/**
 * Качество сети: проверки, которые ловят «правдоподобное бездействие».
 *
 * Главная история здесь — дефект, который невозможно заметить глазами:
 * `reportNetworkConnectivity()` требует CHANGE_NETWORK_STATE, разрешения в
 * манифесте не было, вызов падал с SecurityException, а ловился
 * `catch (Throwable ignored)`. Приложение показывало «система не подтвердила»,
 * и это было неотличимо от честного отказа. Проверки ниже фиксируют:
 *
 *  1. результат перепроверки различает «нет права» и «нет эффекта»;
 *  2. совет по сети зависит от причины, а не один на все случаи;
 *  3. порог «слабого Wi-Fi» на 5 ГГц ниже, чем на 2,4 ГГц — иначе приложение
 *     дёргает канал там, где он в порядке.
 */
public final class NetworkQualityTests {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) {
        reprobeResultsAreDistinct();
        messagesExplainCause();
        adviceDependsOnCause();
        onlyCellTriggersAutoReprobe();
        levelsDecideSwitch();
        signalLevelsDontLie();
        journalSurvivesRestart();
        placeholderHasNoFalseAdvice();

        System.out.println("NETWORK_QUALITY_TESTS=" + n);
    }

    private static void reprobeResultsAreDistinct() {
        // Все причины должны быть разными значениями: интерфейс опирается на них.
        NetworkQualityHelper.ReprobeResult[] all = {
                NetworkQualityHelper.ReprobeResult.ACCEPTED,
                NetworkQualityHelper.ReprobeResult.NO_EFFECT,
                NetworkQualityHelper.ReprobeResult.NO_NETWORK,
                NetworkQualityHelper.ReprobeResult.NO_PERMISSION,
                NetworkQualityHelper.ReprobeResult.UNSUPPORTED};
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                check(all[i] != all[j], "результаты перепроверки различаются: "
                        + all[i] + " / " + all[j]);
            }
        }
    }

    private static void messagesExplainCause() {
        // Сообщение «нет права» обязано отличаться от «нет эффекта»: иначе
        // человек снова видит правдоподобное бездействие.
        String noPerm = NetworkQualityHelper.reprobeMessage(
                NetworkQualityHelper.ReprobeResult.NO_PERMISSION);
        String noEffect = NetworkQualityHelper.reprobeMessage(
                NetworkQualityHelper.ReprobeResult.NO_EFFECT);
        check(!noPerm.equals(noEffect), "у «нет права» свой текст");
        check(noPerm.toLowerCase(java.util.Locale.ROOT).contains("изменение настроек"),
                "в тексте про право сказано, что включить: " + noPerm);
        for (NetworkQualityHelper.ReprobeResult r : NetworkQualityHelper.ReprobeResult.values()) {
            String msg = NetworkQualityHelper.reprobeMessage(r);
            check(msg != null && !msg.trim().isEmpty(), "есть текст для " + r);
        }
    }

    private static void adviceDependsOnCause() {
        int unknown = Integer.MIN_VALUE;
        String noNet = NetworkQualityHelper.networkAdvice(
                false, false, false, false, unknown, false, false);
        String stuck = NetworkQualityHelper.networkAdvice(
                true, false, true, false, -50, false, false);
        String weak = NetworkQualityHelper.networkAdvice(
                true, true, true, false, -88, false, false);
        String metered = NetworkQualityHelper.networkAdvice(
                true, true, false, true, unknown, false, false);
        String fine = NetworkQualityHelper.networkAdvice(
                true, true, true, false, -45, true, false);

        check(!noNet.isEmpty(), "нет сети — есть совет");
        check(!stuck.isEmpty(), "залипание — есть совет");
        check(!weak.isEmpty(), "слабый сигнал — есть совет");
        check(!metered.isEmpty(), "лимитная сеть — есть совет");
        check(fine.isEmpty(), "всё в порядке — совет не нужен");
        check(!noNet.equals(stuck) && !stuck.equals(weak) && !weak.equals(metered),
                "советы для разных причин различаются");
        check(weak.contains("роутер"), "совет про сигнал говорит, что сделать: " + weak);
    }

    private static void onlyCellTriggersAutoReprobe() {
        // ГЛАВНОЕ РЕШЕНИЕ: слабый Wi-Fi больше не повод вмешиваться. Раньше
        // лаунчер по дБм угадывал «слабый Wi-Fi» и дёргал канал, из-за чего на
        // прошивках с умным переключением телефон прыгал Wi-Fi ↔ LTE.
        // Активна Wi-Fi, уровень какой угодно — не повод: перепроверка сигнал
        // Wi-Fi не усиливает, зато может заставить телефон уйти на LTE.
        check(NetworkQualityHelper.autoReprobeAction(
                        true, Prefs.NET_MODE_ACTIVE, 0, true, true, false)
                        == NetworkQualityHelper.ACTION_NONE,
                "слабый Wi-Fi сам по себе — не повод перепроверки");

        // Активна мобильная сеть и еле дышит (1 из 4) — просим перепроверить.
        check(NetworkQualityHelper.autoReprobeAction(
                        true, Prefs.NET_MODE_BALANCED, 1, true, true, true)
                        == NetworkQualityHelper.ACTION_REPROBE,
                "слабая мобильная сеть — перепроверка канала");

        // Залипание канала лечим на любом транспорте: система считает, что
        // интернета нет, и перепроверка действительно помогает.
        check(NetworkQualityHelper.autoReprobeAction(
                        true, Prefs.NET_MODE_BALANCED, -1, true, false, false)
                        == NetworkQualityHelper.ACTION_REPROBE_AND_OFFER,
                "залипание на Wi-Fi — перепроверка и предложение переключиться");
        check(NetworkQualityHelper.autoReprobeAction(
                        true, Prefs.NET_MODE_GENTLE, -1, true, false, false)
                        == NetworkQualityHelper.ACTION_REPROBE,
                "в мягком режиме — только перепроверка, без предложений");

        // Выключенное авто-улучшение важнее любого состояния сети.
        check(NetworkQualityHelper.autoReprobeAction(
                        false, Prefs.NET_MODE_ACTIVE, 0, true, true, true)
                        == NetworkQualityHelper.ACTION_NONE,
                "автоулучшение выключено — не вмешиваемся");
        // Нет активной сети — лечить нечего.
        check(NetworkQualityHelper.autoReprobeAction(
                        true, Prefs.NET_MODE_ACTIVE, 0, false, false, false)
                        == NetworkQualityHelper.ACTION_NONE,
                "без активной сети перепроверка не нужна");
    }

    private static void levelsDecideSwitch() {
        // Без данных об уровне мобильной сети решение не выдумываем.
        check(NetworkQualityHelper.autoReprobeAction(
                        true, Prefs.NET_MODE_BALANCED, -1, true, true, true)
                        == NetworkQualityHelper.ACTION_NONE,
                "без данных об уровне сети не вмешиваемся");
        // Уровень > 1 не считается «еле дышит».
        check(NetworkQualityHelper.autoReprobeAction(
                        true, Prefs.NET_MODE_BALANCED, 2, true, true, true)
                        == NetworkQualityHelper.ACTION_NONE,
                "уровень 2 из 4 — не повод вмешиваться");

        check(NetworkQualityHelper.rssiToLevel(-50) == 4, "−50 дБм → 4 из 4");
        check(NetworkQualityHelper.rssiToLevel(-70) == 2, "−70 дБм → 2 из 4");
        check(NetworkQualityHelper.rssiToLevel(-100) == 0, "−100 дБм → 0 из 4");
        check(NetworkQualityHelper.rssiToLevel(0) == -1, "0 дБм — нет данных");
        check(NetworkQualityHelper.signalStrengthToLevel(-60) == 4, "сырой −60 → 4");
        check(NetworkQualityHelper.signalStrengthToLevel(-90) == 1, "сырой −90 → 1");
        check(NetworkQualityHelper.signalStrengthToLevel(0) == -1, "сырой 0 — нет данных");
        check(NetworkQualityHelper.levelLabel(1).equals("Слабый"), "уровень 1 — «Слабый»");
        check(NetworkQualityHelper.levelLabel(-1).isEmpty(), "без данных — пусто");
        check(NetworkQualityHelper.autoReprobeScopeLabel().contains("мобильная"),
                "в подписи сказано, что работаем только с мобильной сетью");
    }

    private static void journalSurvivesRestart() {
        // Кодирование/декодирование переживает перезапуск: табуляция в тексте
        // причины не должна ломать разбор строки.
        long ts = 1700000000000L;
        String raw = NetworkQualityHelper.encodeEvent(ts, "Wi-Fi", "Авто:\tслабый");
        NetworkQualityHelper.HealEvent ev = NetworkQualityHelper.decodeEvent(raw);
        check(ev != null, "своя же строка журнала разбирается");
        check(ev.timeMs == ts, "время журнала сохраняется");
        check(ev.transport.equals("Wi-Fi"), "транспорт сохраняется");
        check(!ev.reason.contains("\t"), "табуляция не ломает формат: " + ev.reason);
        check(NetworkQualityHelper.decodeEvent("мусор") == null, "битая строка не роняет разбор");
        check(NetworkQualityHelper.decodeEvent(null) == null, "null не роняет разбор");
        check(NetworkQualityHelper.persistedCapacity() >= 10,
                "история хранится не на 6 строк");
    }

    private static void placeholderHasNoFalseAdvice() {
        // Заглушка «служба недоступна» раньше дала бы совет «включите Wi-Fi»,
        // хотя сеть никто не проверял.
        NetworkQualityHelper.Status ph = new NetworkQualityHelper.Status(
                false, false, false, "Нет данных", 0, 0, "Проверка...", "");
        check(ph.advice.isEmpty(), "без данных нет совета: " + ph.advice);

        NetworkQualityHelper.Status real = new NetworkQualityHelper.Status(
                false, false, false, false, false, Integer.MIN_VALUE, false, "",
                "Сеть", 0, 0, "Нет связи", "", "", "");
        check(!real.advice.isEmpty(), "при реальной проверке совет есть");
    }


    private static void signalLevelsDontLie() {
        check(NetworkQualityHelper.signalLevel(Integer.MIN_VALUE).isEmpty(),
                "нет данных — нет уровня");
        check(NetworkQualityHelper.signalLevel(0).isEmpty(), "0 дБм — не данные");
        check(NetworkQualityHelper.signalLevel(-200).isEmpty(), "−200 дБм — не данные");
        check(NetworkQualityHelper.signalLevel(-45).equals("Отличный"), "−45 дБм отличный");
        check(NetworkQualityHelper.signalLevel(-90).equals("Слабый"), "−90 дБм слабый");
        // Один и тот же уровень по-разному оценивается на разных диапазонах.
        check(NetworkQualityHelper.signalLevel(-68, false).equals("Средний"),
                "−68 дБм на 2,4 ГГц — уже средний");
        check(NetworkQualityHelper.signalLevel(-68, true).equals("Хороший"),
                "−68 дБм на 5 ГГц — ещё хороший");
        check(NetworkQualityHelper.formatSignalQuality(-60, true).contains("5 ГГц"),
                "в строке сигнала указан диапазон");
        check(NetworkQualityHelper.is5GhzWifi(true, 5180), "5180 МГц — это 5 ГГц");
        check(!NetworkQualityHelper.is5GhzWifi(true, 2437), "2437 МГц — это 2,4 ГГц");
        check(!NetworkQualityHelper.is5GhzWifi(false, 5180), "не Wi-Fi — диапазона нет");
    }
}
