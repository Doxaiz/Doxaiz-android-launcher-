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
        signalThresholdsRespectBand();
        signalLevelsDontLie();
        sensitivityShiftsThresholds();
        journalSurvivesRestart();
        probeGradesAndTotals();
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
        check(weak.contains("роутер") || weak.contains("ГГц"),
                "совет про сигнал говорит, что сделать: " + weak);
    }

    private static void signalThresholdsRespectBand() {
        // На 5 ГГц тревога поднимается позже: тот же −80 дБм там нормален.
        int fast24 = NetworkQualityHelper.weakWifiThresholdDbm(Prefs.NET_MODE_BALANCED, false);
        int fast5 = NetworkQualityHelper.weakWifiThresholdDbm(Prefs.NET_MODE_BALANCED, true);
        check(fast5 < fast24, "порог 5 ГГц ниже порога 2,4 ГГц");
        check(NetworkQualityHelper.weakWifiThresholdDbm(Prefs.NET_MODE_ACTIVE, false) < fast24,
                "активный режим поднимает тревогу раньше сбалансированного");

        // Решение о перепроверке учитывает диапазон.
        check(!NetworkQualityHelper.shouldAutoReprobe(
                        true, Prefs.NET_MODE_BALANCED, true, true, true, true, -88, true),
                "на 5 ГГц −88 дБм не повод дёргать канал");
        check(NetworkQualityHelper.shouldAutoReprobe(
                        true, Prefs.NET_MODE_BALANCED, true, true, true, true, -88, false),
                "на 2,4 ГГц −88 дБм — повод перепроверить");
        // Выключенный контроль слабого Wi-Fi и выключенный автолеч по-прежнему важнее.
        check(!NetworkQualityHelper.shouldAutoReprobe(
                        true, Prefs.NET_MODE_BALANCED, false, true, true, true, -95, false),
                "контроль слабого Wi-Fi выключен — не дёргаем");
        check(!NetworkQualityHelper.shouldAutoReprobe(
                        false, Prefs.NET_MODE_ACTIVE, true, true, true, true, -95, false),
                "автоулучшение выключено — не дёргаем");
        // Залипание канала (не validated) лечим в любом режиме, кроме выключенного.
        check(NetworkQualityHelper.shouldAutoReprobe(
                        true, Prefs.NET_MODE_GENTLE, false, true, false, true, -95, false),
                "залипание канала лечим даже в мягком режиме");
        // Нет связи — лечить нечего.
        check(!NetworkQualityHelper.shouldAutoReprobe(
                        true, Prefs.NET_MODE_ACTIVE, true, false, false, false, -95, false),
                "без активной сети перепроверка не нужна");
    }

    private static void sensitivityShiftsThresholds() {
        int calm24 = NetworkQualityHelper.weakWifiThresholdDbm(Prefs.NET_MODE_BALANCED, false,
                Prefs.NET_SENSITIVITY_CALM);
        int normal24 = NetworkQualityHelper.weakWifiThresholdDbm(Prefs.NET_MODE_BALANCED, false,
                Prefs.NET_SENSITIVITY_NORMAL);
        int eager24 = NetworkQualityHelper.weakWifiThresholdDbm(Prefs.NET_MODE_BALANCED, false,
                Prefs.NET_SENSITIVITY_EAGER);
        check(calm24 < normal24 && normal24 < eager24,
                "чем тревожнее настройка, тем выше порог тревоги");
        check(normal24 == NetworkQualityHelper.weakWifiThresholdDbm(
                        Prefs.NET_MODE_BALANCED, false),
                "без указания чувствительности работает обычная");
        check(NetworkQualityHelper.shouldAutoReprobe(
                        true, Prefs.NET_MODE_BALANCED, true, true, true, true, -79, false,
                        Prefs.NET_SENSITIVITY_EAGER),
                "тревожная настройка реагирует на −79 дБм");
        check(!NetworkQualityHelper.shouldAutoReprobe(
                        true, Prefs.NET_MODE_BALANCED, true, true, true, true, -79, false,
                        Prefs.NET_SENSITIVITY_CALM),
                "терпеливая настройка на −79 дБм молчит");
        check(NetworkQualityHelper.sensitivitySummary(Prefs.NET_MODE_BALANCED,
                        Prefs.NET_SENSITIVITY_NORMAL).contains("2,4 ГГц"),
                "в подписи видны оба диапазона");
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

    private static void probeGradesAndTotals() {
        check(NetworkProbe.totalMs(30, 50) == 80, "задержка складывается из DNS и соединения");
        check(NetworkProbe.totalMs(-1, 50) == -1, "неудачный шаг — нет суммы");
        check(NetworkProbe.grade(80).equals("Отличная"), "80 мс — отличная");
        check(NetworkProbe.grade(250).equals("Хорошая"), "250 мс — хорошая");
        check(NetworkProbe.grade(600).equals("Средняя"), "600 мс — средняя");
        check(NetworkProbe.grade(1500).equals("Слабая"), "1500 мс — слабая");
        check(NetworkProbe.grade(-1).isEmpty(), "без замера оценки нет");
        NetworkProbe.Result failed = NetworkProbe.Result.failed("x", "DNS не ответил");
        check(!failed.reachable, "неудачный замер помечен как недоступный");
        check(NetworkProbe.describe(failed).contains("DNS не ответил"),
                "причина отказа попадает в текст");
        NetworkProbe.Result ok = new NetworkProbe.Result("x", 20, 40, "");
        check(NetworkProbe.describe(ok).contains("60 мс"), "в тексте есть итоговая задержка");
        check(NetworkProbe.describe(ok).contains("DNS 20"), "в тексте есть разбивка");
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
