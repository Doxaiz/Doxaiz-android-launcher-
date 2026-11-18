package com.doxaiz.launcher;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Реальный замер качества сети: сколько времени занимает резолв имени и
 * TCP-подключение.
 *
 * Зачем это появилось. Раньше «качество сети» оценивалось только по косвенным
 * системным сигналам: есть ли NET_CAPABILITY_VALIDATED, какой уровень сигнала
 * сообщил Wi-Fi. Этого достаточно, чтобы заметить залипание канала, но
 * невозможно отличить «канал отвечает медленно» от «канал отвечает быстро»:
 * система одинаково ставит VALIDATED и при 40 мс, и при 900 мс.
 *
 * Честные границы замера. Здесь НЕ измеряется скорость скачивания — для этого
 * нужен файл на десятки мегабайт и расход трафика пользователя. Измеряется
 * задержка установления соединения (DNS + TCP). В интерфейсе это называется
 * «задержка», а не «скорость»: обещать «замер скорости» было бы неправдой.
 *
 * Замер идёт только по явной кнопке. В фоне никаких запросов нет: приложение
 * не превращается в постоянный источник трафика.
 */
public final class NetworkProbe {

    /** Узел для проверки — тот же, что использует сама Android для captive-portal. */
    public static final String DEFAULT_HOST = "connectivitycheck.gstatic.com";
    public static final int DEFAULT_PORT = 443;

    private NetworkProbe() {}

    /** Результат одного замера. Времена в миллисекундах, -1 — шаг не удался. */
    public static final class Result {
        public final String host;
        public final int dnsMs;
        public final int connectMs;
        public final boolean reachable;
        /** Причина отказа для интерфейса; пусто, если замер удался. */
        public final String error;

        public Result(String host, int dnsMs, int connectMs, String error) {
            this.host = (host != null) ? host : DEFAULT_HOST;
            this.dnsMs = dnsMs;
            this.connectMs = connectMs;
            this.reachable = dnsMs >= 0 && connectMs >= 0;
            this.error = (error != null) ? error : "";
        }

        public static Result failed(String host, String error) {
            return new Result(host, -1, -1, error);
        }

        public int totalMs() {
            return NetworkProbe.totalMs(dnsMs, connectMs);
        }
    }

    public interface Callback {
        void onResult(Result result);
    }

    /** Суммарное время; -1, если хотя бы один шаг не удался. */
    public static int totalMs(int dnsMs, int connectMs) {
        if (dnsMs < 0 || connectMs < 0) return -1;
        return dnsMs + connectMs;
    }

    /**
     * Оценка задержки словами.
     *
     * Пороги выбраны по ощущениям в интерфейсе, а не по обещаниям скорости:
     * до 120 мс — незаметно, до 300 мс — рабочий интернет, до 800 мс — заметно
     * медленно, дальше — соединение фактически не работает. Значения — предмет
     * для настройки, поэтому метод чистый и его проверяет тест.
     */
    public static String grade(int totalMs) {
        if (totalMs < 0) return "";
        if (totalMs <= 120) return "Отличная";
        if (totalMs <= 300) return "Хорошая";
        if (totalMs <= 800) return "Средняя";
        return "Слабая";
    }

    /** Строка для интерфейса: задержка + оценка + из чего сложилась. */
    public static String describe(Result r) {
        if (r == null) return "";
        if (!r.reachable) {
            if (r.error.isEmpty()) return "Замер не удался: сеть не ответила";
            return "Замер не удался: " + r.error;
        }
        return "Задержка " + r.totalMs() + " мс (" + grade(r.totalMs()) + ")"
                + " • DNS " + r.dnsMs + " мс, соединение " + r.connectMs + " мс";
    }

    /**
     * Замер в фоновом потоке.
     *
     * Колбэк вызывается из этого же фонового потока — вызывающий код обязан
     * сам перейти в UI-поток. Так сделано намеренно: иначе класс пришлось бы
     * связывать с Handler и Activity, и он перестал бы быть проверяемым.
     */
    public static void runAsync(final String host, final int timeoutMs, final Callback callback) {
        final String target = (host != null && !host.trim().isEmpty()) ? host.trim() : DEFAULT_HOST;
        Thread t = new Thread(() -> {
            Result r;
            try {
                r = measure(target, DEFAULT_PORT, timeoutMs);
            } catch (Throwable th) {
                r = Result.failed(target, "нет доступа в сеть");
            }
            if (callback != null) callback.onResult(r);
        }, "doxaiz-net-probe");
        t.setDaemon(true);
        t.start();
    }

    /** Синхронный замер: сначала DNS, затем TCP-подключение. */
    public static Result measure(String host, int port, int timeoutMs) {
        int timeout = (timeoutMs > 0) ? timeoutMs : 2500;
        long t0 = System.nanoTime();
        InetAddress addr;
        try {
            addr = InetAddress.getByName(host);
        } catch (Throwable t) {
            return Result.failed(host, "DNS не ответил");
        }
        int dnsMs = (int) ((System.nanoTime() - t0) / 1_000_000L);

        long t1 = System.nanoTime();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(addr, port), timeout);
        } catch (Throwable t) {
            return new Result(host, dnsMs, -1, "соединение не установлено");
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
        int connectMs = (int) ((System.nanoTime() - t1) / 1_000_000L);
        return new Result(host, dnsMs, connectMs, "");
    }
}
