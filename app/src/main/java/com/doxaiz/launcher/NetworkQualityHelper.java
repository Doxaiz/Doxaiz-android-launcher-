package com.doxaiz.launcher;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Честный контроль и тихое авто-улучшение качества сети без root:
 * - Отслеживает тип сети (Wi-Fi / 4G-5G / VPN), валидацию доступа, пропускную способность,
 *   реальное состояние Частного DNS (LinkProperties API 28+), лимитность сети (NOT_METERED),
 *   уровень сигнала (API 29+) и диапазон 2,4/5 ГГц (LinkProperties#getFrequency, API 30+).
 * - Поддерживает 3 режима (Мягкий 30с / Сбалансированный 15с / Активный 10с) + контроль
 *   слабого Wi-Fi с настраиваемой чувствительностью.
 * - Работает тихо в фоне и НЕ делает сетевых запросов: авто-улучшение читает только
 *   системное состояние. Реальный замер задержки — отдельно и только по кнопке (NetworkProbe).
 * - Журнал перепроверок переживает перезапуск (20 событий), причины отказа различаются.
 *
 * Разрешение INTERNET нужно исключительно для замера по кнопке, см. AUDIT §29.5.
 */
public final class NetworkQualityHelper {

    /** Сколько событий держим в памяти. Пользователь выбрал 20 — столько же
     *  сохраняется на диск, поэтому история не обрывается на 6 строках. */
    public static final int MAX_LOG_EVENTS = 20;
    private static final int WEAK_WIFI_BALANCED_DBM = -82;
    private static final int WEAK_WIFI_ACTIVE_DBM = -76;

    public static final class Status {
        public final boolean connected;
        public final boolean validated;
        public final boolean hasVpn;
        public final boolean isWifi;
        public final boolean metered;
        public final int signalDbm;
        public final boolean privateDnsActive;
        public final String privateDnsServer;
        public final String transportName;
        public final int downMbps;
        public final int upMbps;
        public final String headline;
        public final String details;
        public final String dnsSummary;
        public final String signalSummary;
        /** Что конкретно сделать при текущем состоянии сети; пусто — всё в порядке. */
        public final String advice;
        /** Диапазон 5 ГГц (только для Wi-Fi). */
        public final boolean is5Ghz;

        public Status(boolean connected, boolean validated, boolean hasVpn,
                      String transportName, int downMbps, int upMbps,
                      String headline, String details) {
            // Заглушка «служба недоступна»: совет «включите Wi-Fi» здесь был бы
            // ложью — сеть не проверялась вовсе.
            this(connected, validated, hasVpn, false, false, Integer.MIN_VALUE,
                    false, "", transportName, downMbps, upMbps, headline, details,
                    formatPrivateDnsSummary(false, ""), "", false, false);
        }

        public Status(boolean connected, boolean validated, boolean hasVpn, boolean isWifi,
                      boolean metered, int signalDbm, boolean privateDnsActive, String privateDnsServer,
                      String transportName, int downMbps, int upMbps,
                      String headline, String details, String dnsSummary, String signalSummary) {
            this(connected, validated, hasVpn, isWifi, metered, signalDbm, privateDnsActive,
                    privateDnsServer, transportName, downMbps, upMbps, headline, details,
                    dnsSummary, signalSummary, false, true);
        }

        public Status(boolean connected, boolean validated, boolean hasVpn, boolean isWifi,
                      boolean metered, int signalDbm, boolean privateDnsActive, String privateDnsServer,
                      String transportName, int downMbps, int upMbps,
                      String headline, String details, String dnsSummary, String signalSummary,
                      boolean is5Ghz) {
            this(connected, validated, hasVpn, isWifi, metered, signalDbm, privateDnsActive,
                    privateDnsServer, transportName, downMbps, upMbps, headline, details,
                    dnsSummary, signalSummary, is5Ghz, true);
        }

        private Status(boolean connected, boolean validated, boolean hasVpn, boolean isWifi,
                       boolean metered, int signalDbm, boolean privateDnsActive, String privateDnsServer,
                       String transportName, int downMbps, int upMbps,
                       String headline, String details, String dnsSummary, String signalSummary,
                       boolean is5Ghz, boolean withAdvice) {
            this.is5Ghz = is5Ghz;
            this.connected = connected;
            this.validated = validated;
            this.hasVpn = hasVpn;
            this.isWifi = isWifi;
            this.metered = metered;
            this.signalDbm = signalDbm;
            this.privateDnsActive = privateDnsActive;
            this.privateDnsServer = (privateDnsServer != null) ? privateDnsServer : "";
            this.transportName = transportName;
            this.downMbps = downMbps;
            this.upMbps = upMbps;
            this.headline = headline;
            this.details = details;
            this.dnsSummary = (dnsSummary != null) ? dnsSummary : "";
            this.signalSummary = (signalSummary != null) ? signalSummary : "";
            this.advice = withAdvice
                    ? networkAdvice(connected, validated, isWifi, metered,
                            signalDbm, is5Ghz, privateDnsActive)
                    : "";
        }
    }

    public static final class HealEvent {
        public final long timeMs;
        public final String transport;
        public final String reason;

        public HealEvent(long timeMs, String transport, String reason) {
            this.timeMs = timeMs;
            this.transport = (transport != null) ? transport : "Сеть";
            this.reason = (reason != null) ? reason : "Перепроверка канала";
        }
    }

    public interface AutoHealListener {
        void onNetworkStatusChanged(Status status, boolean autoReprobed);
    }

    /** Ключи учёта трафика: прежние имена, чтобы не потерять накопленное. */
    private static final String KEY_TR_DAY = "tr_day_key";
    private static final String KEY_TR_MONTH = "tr_mon_key";
    private static final String KEY_TR_LAST_RAW = "tr_last_raw";
    private static final String KEY_TR_DAY_ACC = "tr_day_acc";
    private static final String KEY_TR_MONTH_ACC = "tr_mon_acc";

    private final Context appContext;
    private final ConnectivityManager cm;
    /** Настройки нужны только для редкой записи накопленного трафика. */
    private final Prefs prefs;
    private ConnectivityManager.NetworkCallback activeCallback;
    private boolean activeAutoHeal = false;
    private int activeMode = -1;
    private boolean activeWeakWifi = false;
    private int activeSensitivity = -1;
    private volatile long lastAutoReprobeTs = 0L;

    private volatile boolean lastReprobeAccepted = false;
    /**
     * «Недавние» уже посчитали трафик этого часа: второй замер раздул бы сумму
     * на ту же дельту. Так панель сети может стоять на экране подолгу, а
     * TrafficStats читается на каждый кадр её обновления.
     */
    private volatile long lastTrafficSampleHour = -1L;
    private final Object statsLock = new Object();
    // Сам SimpleDateFormat не держим: он не потокобезопасен, а панель сети
    // форматирует время из фонового потока. Шаблон и локаль — неизменяемые.
    static final String TIME_PATTERN = "HH:mm";
    static final Locale TIME_LOCALE = Locale.getDefault();
    private int healCount = 0;
    private long lastHealTimeMs = 0L;
    private final List<HealEvent> recentEvents = new ArrayList<>();

    public NetworkQualityHelper(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = new Prefs(appContext);
        this.cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public static long cooldownForMode(int mode) {
        if (mode == Prefs.NET_MODE_GENTLE) return 30_000L;
        if (mode == Prefs.NET_MODE_ACTIVE) return 10_000L;
        return 15_000L;
    }

    public static String modeShortLabel(int mode) {
        if (mode == Prefs.NET_MODE_GENTLE) return "Мягкий (30с)";
        if (mode == Prefs.NET_MODE_ACTIVE) return "Активный (10с)";
        return "Сбаланс. (15с)";
    }

    public static String modeDescription(int mode) {
        if (mode == Prefs.NET_MODE_GENTLE) {
            return "Перепроверка только при полном залипании канала (интервал 30 с, нулевой расход батареи)";
        }
        if (mode == Prefs.NET_MODE_ACTIVE) {
            return "Быстрая реакция (10 с) при залипании канала и просадке слабого Wi-Fi";
        }
        return "Оптимальный режим (15 с): устранение залипаний + контроль слабого Wi-Fi";
    }

    /**
     * Почему перепроверка канала не сработала.
     *
     * Раньше `triggerSystemReprobe` возвращал boolean, а внутри стоял
     * `catch (Throwable ignored)`: вызов `reportNetworkConnectivity()` без
     * разрешения `CHANGE_NETWORK_STATE` бросал SecurityException на КАЖДОМ
     * нажатии, и снаружи это выглядело как «система не подтвердила» —
     * неотличимо от честного «нет подключения». Из-за этого «улучшение сети»
     * не работало никогда, а сообщения были правдоподобными.
     *
     * Теперь причина называется прямо, а интерфейс говорит, что делать.
     */
    public enum ReprobeResult {
        /** Система приняла запрос и сеть валидна. */
        ACCEPTED,
        /** Запрос ушёл, но сеть так и не стала валидной. */
        NO_EFFECT,
        /** Активной сети нет. */
        NO_NETWORK,
        /** Нет разрешения CHANGE_NETWORK_STATE и нет права менять настройки. */
        NO_PERMISSION,
        /** Прошивка не поддержала вызов. */
        UNSUPPORTED
    }

    public static boolean shouldAutoReprobe(
            boolean autoEnabled,
            int mode,
            boolean weakWifiEnabled,
            boolean connected,
            boolean validated,
            boolean isWifi,
            int signalDbm
    ) {
        if (!autoEnabled || !connected) return false;
        if (!validated) return true;
        if (isWifi && weakWifiEnabled && mode != Prefs.NET_MODE_GENTLE
                && signalDbm != Integer.MIN_VALUE && signalDbm > -120 && signalDbm < 0) {
            return signalDbm <= weakWifiThresholdDbm(mode, false);
        }
        return false;
    }

    /** То же, но с учётом диапазона Wi-Fi: на 5 ГГц порог ниже. */
    public static boolean shouldAutoReprobe(
            boolean autoEnabled,
            int mode,
            boolean weakWifiEnabled,
            boolean connected,
            boolean validated,
            boolean isWifi,
            int signalDbm,
            boolean is5Ghz
    ) {
        return shouldAutoReprobe(autoEnabled, mode, weakWifiEnabled, connected, validated,
                isWifi, signalDbm, is5Ghz, Prefs.NET_SENSITIVITY_NORMAL);
    }

    /** То же с явной чувствительностью — её выбирает пользователь в настройках. */
    public static boolean shouldAutoReprobe(
            boolean autoEnabled,
            int mode,
            boolean weakWifiEnabled,
            boolean connected,
            boolean validated,
            boolean isWifi,
            int signalDbm,
            boolean is5Ghz,
            int sensitivity
    ) {
        if (!autoEnabled || !connected) return false;
        if (!validated) return true;
        if (isWifi && weakWifiEnabled && mode != Prefs.NET_MODE_GENTLE
                && signalDbm != Integer.MIN_VALUE && signalDbm > -120 && signalDbm < 0) {
            return signalDbm <= weakWifiThresholdDbm(mode, is5Ghz, sensitivity);
        }
        return false;
    }

    public static String formatSignalQuality(int dbm) {
        return formatSignalQuality(dbm, false);
    }

    public static String formatSignalQuality(int dbm, boolean is5Ghz) {
        String level = signalLevel(dbm, is5Ghz);
        if (level.isEmpty()) return "";
        String band = is5Ghz ? "5 ГГц" : "2,4 ГГц";
        return level + " (" + dbm + " дБм, " + band + ")";
    }

    /**
     * Уровень сигнала словами.
     *
     * Пороги 2,4 ГГц и 5 ГГц различаются по физике: на 5 ГГц те же −75 дБм
     * означают нормальный канал, а на 2,4 ГГц — уже просадку. Раньше порог был
     * один, поэтому на 5 ГГц приложение звало «слабый Wi-Fi» там, где всё
     * работало, и наоборот.
     *
     * @param dbm    уровень сигнала (dBm), значения вне диапазона — «нет данных»
     * @param is5Ghz признак диапазона 5 ГГц (если неизвестно — false)
     */
    public static String signalLevel(int dbm, boolean is5Ghz) {
        if (dbm == Integer.MIN_VALUE || dbm <= -120 || dbm >= 0) return "";
        int excellent = is5Ghz ? -62 : -58;
        int good = is5Ghz ? -70 : -67;
        int fair = is5Ghz ? -76 : -75;
        if (dbm >= excellent) return "Отличный";
        if (dbm >= good) return "Хороший";
        if (dbm >= fair) return "Средний";
        return "Слабый";
    }

    /**
     * Признак 5 ГГц.
     *
     * Без разрешений и без геолокации узнать диапазон можно только через
     * {@code LinkProperties#getFrequency()} (API 30+). Если данных нет,
     * возвращаем false: тогда работают консервативные пороги 2,4 ГГц, и
     * приложение не поднимает ложную тревогу на хорошем канале.
     */
    static boolean is5GhzWifi(boolean isWifi, int frequencyMhz) {
        return isWifi && frequencyMhz >= 4900 && frequencyMhz <= 5900;
    }

    /** Прежний вызов без диапазона — консервативные пороги 2,4 ГГц. */
    public static String signalLevel(int dbm) {
        return signalLevel(dbm, false);
    }

    /**
     * Порог «слабого Wi-Fi» для авто-перепроверки.
     *
     * Вынесен отдельно, чтобы его проверял тест: на 5 ГГц тревога должна
     * подниматься позже, иначе приложение дёргает канал там, где он в порядке.
     */
    public static int weakWifiThresholdDbm(int mode, boolean is5Ghz) {
        return weakWifiThresholdDbm(mode, is5Ghz, Prefs.NET_SENSITIVITY_NORMAL);
    }

    /**
     * Порог «слабого Wi-Fi» с учётом выбранной чувствительности.
     *
     * Решение пользователя: пороги зависят от диапазона и от того, насколько
     * рано человек хочет, чтобы лаунчер вмешивался. Терпеливая настройка
     * опускает порог на 6 дБ (сигнал должен стать совсем плохим), тревожная —
     * поднимает на 6 дБ. Сдвиг в дБ, а не готовое число, чтобы правка базовых
     * порогов не превращала уже выбранную настройку в другую.
     */
    public static int weakWifiThresholdDbm(int mode, boolean is5Ghz, int sensitivity) {
        int base = (mode == Prefs.NET_MODE_ACTIVE) ? WEAK_WIFI_ACTIVE_DBM : WEAK_WIFI_BALANCED_DBM;
        if (is5Ghz) base -= 4;
        if (sensitivity == Prefs.NET_SENSITIVITY_CALM) base -= 6;
        else if (sensitivity == Prefs.NET_SENSITIVITY_EAGER) base += 6;
        return base;
    }

    /** Подпись для интерфейса: какие пороги действуют сейчас. */
    public static String sensitivitySummary(int mode, int sensitivity) {
        int dbm24 = weakWifiThresholdDbm(mode, false, sensitivity);
        int dbm5 = weakWifiThresholdDbm(mode, true, sensitivity);
        return "Порог тревоги: " + dbm24 + " дБм (2,4 ГГц) / " + dbm5 + " дБм (5 ГГц)";
    }

    public static String sensitivityLabel(int sensitivity) {
        if (sensitivity == Prefs.NET_SENSITIVITY_CALM) return "Терпеливая";
        if (sensitivity == Prefs.NET_SENSITIVITY_EAGER) return "Тревожная";
        return "Обычная";
    }

    /**
     * Подсказка «что сделать», а не только «плохо».
     *
     * Прежний экран говорил «Канал не отвечает (залипание)» и предлагал
     * перепроверку — даже когда причина была в другом: сеть лимитная, сигнал
     * слабый, DNS частный и медленный. Совет теперь зависит от причины.
     */
    public static String networkAdvice(boolean connected, boolean validated, boolean isWifi,
                                       boolean metered, int signalDbm, boolean is5Ghz,
                                       boolean privateDnsActive) {
        if (!connected) {
            return "Включите Wi-Fi или мобильный интернет — активной сети нет";
        }
        if (!validated) {
            if (metered) {
                return "Сеть отвечает не сразу: проверьте остаток трафика и переключитесь на Wi-Fi";
            }
            return "Канал не отвечает: перепроверьте соединение или переподключите Wi-Fi";
        }
        String level = signalLevel(signalDbm, is5Ghz);
        if (isWifi && "Слабый".equals(level)) {
            return "Сигнал слабый: подойдите ближе к роутеру или переключитесь на "
                    + (is5Ghz ? "2,4 ГГц" : "5 ГГц");
        }
        if (isWifi && privateDnsActive && metered) {
            return "Частный DNS включён на лимитной сети: при медленном ответе "
                    + "попробуйте автоматический DNS";
        }
        if (metered) {
            return "Лимитная сеть: следите за расходом — учёт трафика есть в этой панели";
        }
        return "";
    }

    public static String formatPrivateDnsSummary(boolean active, String serverName) {
        if (serverName != null && !serverName.trim().isEmpty()) {
            return "Вкл (" + serverName.trim() + ")";
        }
        if (active) return "Авто (системный)";
        return "Выкл";
    }

    public Status inspect() {
        accumulateTraffic(System.currentTimeMillis());
        if (!permissionFlagChecked) {
            permissionFlagChecked = true;
            refreshPermissionFlag();
        }
        if (cm == null) {
            return new Status(false, false, false, "Нет данных", 0, 0,
                    "Сеть недоступна", "Служба подключения не отвечает");
        }
        try {
            Network net = cm.getActiveNetwork();
            if (net == null) {
                return new Status(false, false, false, "Нет подключения", 0, 0,
                        "Нет подключения к сети", "Включите Wi-Fi или мобильный интернет");
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) {
                return new Status(false, false, false, "Нет подключения", 0, 0,
                        "Нет данных о сети", "Попробуйте переподключить канал");
            }

            boolean isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            boolean isCell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            boolean isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            boolean hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            boolean metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);

            int signalDbm = Integer.MIN_VALUE;
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    signalDbm = caps.getSignalStrength();
                } catch (Throwable ignored) {
                }
            }

            boolean dnsActive = false;
            String dnsServer = "";
            int wifiFrequencyMhz = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    LinkProperties lp = cm.getLinkProperties(net);
                    if (lp != null) {
                        dnsActive = lp.isPrivateDnsActive();
                        String srv = lp.getPrivateDnsServerName();
                        if (srv != null) dnsServer = srv.trim();
                        // Частота нужна, чтобы различать 2,4 и 5 ГГц: пороги
                        // «слабого сигнала» у них разные. API 30+, без разрешений.
                        if (Build.VERSION.SDK_INT >= 30) {
                            wifiFrequencyMhz = lp.getFrequency();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            boolean is5Ghz = is5GhzWifi(isWifi, wifiFrequencyMhz);

            String transport;
            if (isWifi) transport = "Wi-Fi";
            else if (isCell) transport = "Мобильная сеть";
            else if (isVpn) transport = "VPN";
            else transport = "Сеть";
            if (isVpn && (isWifi || isCell)) {
                transport += " + VPN";
            }

            int downKbps = Math.max(0, caps.getLinkDownstreamBandwidthKbps());
            int upKbps = Math.max(0, caps.getLinkUpstreamBandwidthKbps());
            int downMbps = Math.round(downKbps / 1000f);
            int upMbps = Math.round(upKbps / 1000f);

            String sigSummary = formatSignalQuality(signalDbm, is5Ghz);
            String dnsSummary = formatPrivateDnsSummary(dnsActive, dnsServer);

            String headline;
            if (validated) {
                headline = transport + " • Связь стабильна";
            } else if (hasInternet) {
                headline = transport + " • Канал не отвечает (залипание)";
            } else {
                headline = transport + " • Нет выхода в интернет";
            }

            StringBuilder det = new StringBuilder();
            if (downMbps > 0) {
                det.append(String.format(Locale.getDefault(), "Канал: ↓ %d / ↑ %d Мбит/с", downMbps, upMbps));
            } else {
                det.append("Оценка канала обновляется системой");
            }
            if (!sigSummary.isEmpty()) {
                det.append(" • Сигнал: ").append(sigSummary);
            }
            det.append(metered ? " • Лимитная сеть" : " • Безлимитная");
            if (!validated && hasInternet) {
                det.append(" • Рекомендуется перепроверка");
            }

            return new Status(true, validated, isVpn, isWifi, metered, signalDbm,
                    dnsActive, dnsServer, transport, downMbps, upMbps,
                    headline, det.toString(), dnsSummary, sigSummary, is5Ghz);
        } catch (Throwable ignored) {
            return new Status(false, false, false, "Нет данных", 0, 0,
                    "Не удалось проверить сеть", "Откройте системные параметры сети");
        }
    }

    /**
     * Просит Android ConnectivityService немедленно перепроверить текущее подключение
     * и при обрыве автоматически переключиться на рабочий канал (например, со слабого Wi-Fi на 4G).
     */
    public boolean triggerSystemReprobe() {
        return triggerSystemReprobe("Ручное обновление");
    }

    public boolean triggerSystemReprobe(String reason) {
        return triggerSystemReprobeDetailed(reason) == ReprobeResult.ACCEPTED;
    }

    /**
     * Перепроверка канала с причиной результата.
     *
     * Ключевое отличие от прежней версии: проверяем разрешение ДО вызова и
     * различаем «система отказала», «сети нет», «нет права» и «нет эффекта».
     * `reportNetworkConnectivity()` требует `CHANGE_NETWORK_STATE` либо права
     * менять системные настройки (`Settings.System.canWrite`), поэтому одного
     * манифеста мало: на части прошивок разрешение выдаётся только вручную.
     */
    public ReprobeResult triggerSystemReprobeDetailed(String reason) {
        if (cm == null) return ReprobeResult.NO_NETWORK;
        Network net;
        try {
            net = cm.getActiveNetwork();
        } catch (Throwable t) {
            return ReprobeResult.UNSUPPORTED;
        }
        if (net == null) {
            lastReprobeAccepted = false;
            recordHealEvent("Сеть", (reason != null ? reason : "Перепроверка") + " (нет сети)");
            return ReprobeResult.NO_NETWORK;
        }
        if (!canReportConnectivity()) {
            lastReprobeAccepted = false;
            recordHealEvent("Сеть", (reason != null ? reason : "Перепроверка") + " (нет разрешения)");
            // Право не выдаётся само по себе и не появляется от повторных попыток:
            // запоминаем отказ, чтобы автоцикл не писал одно и то же в журнал
            // каждые 10 секунд и не будил систему впустую.
            permissionDeniedThisSession = true;
            return ReprobeResult.NO_PERMISSION;
        }
        try {
            // Второй аргумент — «удалось ли приложению выйти в интернет».
            // false заставляет систему перепроверить канал заново; true не
            // используется намеренно: он лишь подтверждает статус-кво.
            cm.reportNetworkConnectivity(net, false);
        } catch (SecurityException se) {
            // Разрешение могло быть отозвано после проверки — не выдаём это за «нет эффекта».
            lastReprobeAccepted = false;
            recordHealEvent("Сеть", (reason != null ? reason : "Перепроверка") + " (нет разрешения)");
            return ReprobeResult.NO_PERMISSION;
        } catch (Throwable t) {
            lastReprobeAccepted = false;
            recordHealEvent("Сеть", (reason != null ? reason : "Перепроверка") + " (прошивка не поддержала)");
            return ReprobeResult.UNSUPPORTED;
        }
        Status st = inspect();
        boolean reported = st.connected && st.validated;
        lastReprobeAccepted = reported;
        recordHealEvent(st.transportName,
                reported ? (reason != null ? reason : "Перепроверка") : (reason + " (без эффекта)"));
        return reported ? ReprobeResult.ACCEPTED : ReprobeResult.NO_EFFECT;
    }

    /**
     * Есть ли право перепроверить канал.
     *
     * В манифесте `CHANGE_NETWORK_STATE` объявлен (protection level — normal,
     * то есть выдаётся при установке). Но система принимает и право менять
     * системные настройки, а на части прошивок именно оно и спрашивается,
     * поэтому проверяем оба пути. Отдельный метод — чтобы это можно было
     * проверить тестом без Android.
     */
    public boolean canReportConnectivity() {
        if (hasChangeNetworkState) return true;
        try {
            return Settings.System.canWrite(appContext);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Признак выданного CHANGE_NETWORK_STATE; выставляется проверкой при старте. */
    private boolean hasChangeNetworkState = false;
    private volatile boolean permissionFlagChecked = false;
    /** Право на перепроверку уже отказано в этом сеансе — автоцикл молчит. */
    private volatile boolean permissionDeniedThisSession = false;
    /** Журнал с диска догружается один раз и лениво. */
    private boolean journalLoaded = false;

    /**
     * Проверяет разрешение через PackageManager и запоминает результат.
     * Вызывается из {@link #inspect()} лениво: лишний IPC на каждый кадр не нужен.
     */
    private void refreshPermissionFlag() {
        try {
            // checkSelfPermission есть с API 23, minSdk проекта — 26.
            hasChangeNetworkState = appContext.checkSelfPermission(
                    android.Manifest.permission.CHANGE_NETWORK_STATE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            hasChangeNetworkState = false;
        }
        // Право могли выдать после отказа (человек сходил в настройки) —
        // тогда автоцикл снова имеет смысл.
        if (hasChangeNetworkState) permissionDeniedThisSession = false;
    }

    /** Человеческое объяснение результата для интерфейса. */
    public static String reprobeMessage(ReprobeResult result) {
        switch (result) {
            case ACCEPTED:
                return "Канал перепроверен: сеть активна";
            case NO_EFFECT:
                return "Перепроверка не помогла — сеть так и не ответила";
            case NO_NETWORK:
                return "Активной сети нет";
            case NO_PERMISSION:
                return "Нет права менять состояние сети — включите «Изменение настроек» для Doxaiz";
            case UNSUPPORTED:
            default:
                return "Прошивка не поддержала перепроверку канала";
        }
    }

    /** true, если последний reportNetworkConnectivity() действительно оставил сеть валидной. */
    public boolean wasLastReprobeAccepted() {
        return lastReprobeAccepted;
    }

    private void recordHealEvent(String transport, String reason) {
        // Сначала подтягиваем историю с диска: если записать новое событие при
        // ещё не загруженном журнале, сохранённая история будет затёрта одной
        // строкой. Вызов идёт до synchronized — ensureJournalLoaded сам берёт lock.
        ensureJournalLoaded();
        long now = System.currentTimeMillis();
        synchronized (statsLock) {
            healCount++;
            lastHealTimeMs = now;
            recentEvents.add(0, new HealEvent(now, transport, reason));
            while (recentEvents.size() > MAX_LOG_EVENTS) {
                recentEvents.remove(recentEvents.size() - 1);
            }
            persistJournalLocked();
        }
    }

    // Журнал перепроверок: раньше жил только в памяти и исчезал при перезапуске,
    // поэтому ответить на вопрос «а помогает ли это вообще?» было нельзя.
    // Пишем в те же SharedPreferences, что и настройки, но отдельными ключами:
    // строка на событие, чтобы не заводить JSON и не ломать чтение старых данных.
    static final String KEY_JOURNAL_COUNT = "net_journal_count";
    static final String KEY_JOURNAL_EVENT = "net_journal_ev";
    /** Сколько событий переживает перезапуск. Пользователь выбрал 20. */
    public static final int MAX_PERSISTED_EVENTS = 20;

    /** Формат строки журнала: время|транспорт|причина. Разделитель — табуляция. */
    static String encodeEvent(long timeMs, String transport, String reason) {
        return timeMs + "\t" + sanitizeField(transport) + "\t" + sanitizeField(reason);
    }

    /** Разбор строки журнала; null, если строка повреждена. */
    static HealEvent decodeEvent(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split("\t", 3);
        if (parts.length != 3) return null;
        try {
            long ts = Long.parseLong(parts[0]);
            return new HealEvent(ts, parts[1], parts[2]);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Табуляция и перевод строки ломают формат — заменяем пробелом. */
    private static String sanitizeField(String v) {
        if (v == null) return "";
        return v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Догрузка журнала с диска; вызывается лениво из getRecentEvents()/getHealCount(). */
    private void ensureJournalLoaded() {
        if (journalLoaded) return;
        journalLoaded = true;
        try {
            SharedPreferences sp = prefs.getSharedPreferences();
            if (sp == null) return;
            int cnt = Math.min(MAX_PERSISTED_EVENTS, Math.max(0, sp.getInt(KEY_JOURNAL_COUNT, 0)));
            List<HealEvent> loaded = new ArrayList<>();
            for (int i = 0; i < cnt; i++) {
                HealEvent ev = decodeEvent(sp.getString(KEY_JOURNAL_EVENT + i, null));
                if (ev != null) loaded.add(ev);
            }
            long lastTs = sp.getLong("net_journal_last_ts", 0L);
            synchronized (statsLock) {
                // Свежие события сеанса важнее и стоят первыми (типичный случай —
                // первый запуск, когда в памяти ещё пусто).
                if (recentEvents.isEmpty()) {
                    recentEvents.addAll(loaded);
                    healCount = Math.max(healCount, sp.getInt("net_journal_total", 0));
                } else {
                    // Уже есть события этого сеанса — дописываем историю следом,
                    // иначе счётчик показывал бы цифру с диска при пустом журнале.
                    for (HealEvent old : loaded) {
                        if (recentEvents.size() >= MAX_PERSISTED_EVENTS) break;
                        recentEvents.add(old);
                    }
                }
                if (lastHealTimeMs == 0L) lastHealTimeMs = lastTs;
            }
        } catch (Throwable t) {
            // Битый журнал не должен мешать работе — начинаем заново.
        }
    }

    /** Сохранение журнала. Вызывается под statsLock из recordHealEvent. */
    private void persistJournalLocked() {
        try {
            SharedPreferences sp = prefs.getSharedPreferences();
            if (sp == null) return;
            int cnt = Math.min(MAX_PERSISTED_EVENTS, recentEvents.size());
            SharedPreferences.Editor ed = sp.edit();
            ed.putInt(KEY_JOURNAL_COUNT, cnt);
            for (int i = 0; i < cnt; i++) {
                HealEvent ev = recentEvents.get(i);
                ed.putString(KEY_JOURNAL_EVENT + i, encodeEvent(ev.timeMs, ev.transport, ev.reason));
            }
            ed.putLong("net_journal_last_ts", lastHealTimeMs);
            ed.putInt("net_journal_total", healCount);
            ed.apply();
        } catch (Throwable t) {
            // Не сохранили — не повод падать: журнал не критичен для работы сети.
        }
    }

    public int getHealCount() {
        // Без догрузки карточка настроек после перезапуска показывала бы
        // «перепроверок не было», хотя журнал лежит на диске.
        ensureJournalLoaded();
        synchronized (statsLock) {
            return healCount;
        }
    }

    public long getLastHealTimeMs() {
        synchronized (statsLock) {
            return lastHealTimeMs;
        }
    }

    public List<HealEvent> getRecentEvents() {
        ensureJournalLoaded();
        synchronized (statsLock) {
            return new ArrayList<>(recentEvents);
        }
    }

    /** Сколько событий переживает перезапуск (для подписи в настройках). */
    public static int persistedCapacity() {
        return MAX_PERSISTED_EVENTS;
    }

    public void clearHealStats() {
        synchronized (statsLock) {
            healCount = 0;
            lastHealTimeMs = 0L;
            recentEvents.clear();
            // Кнопка «Сбросить» должна стирать и сохранённую историю: иначе
            // после перезапуска журнал возвращался бы, будто сброса не было.
            try {
                SharedPreferences sp = prefs.getSharedPreferences();
                if (sp != null) {
                    SharedPreferences.Editor ed = sp.edit();
                    ed.putInt(KEY_JOURNAL_COUNT, 0);
                    ed.putLong("net_journal_last_ts", 0L);
                    ed.putInt("net_journal_total", 0);
                    for (int i = 0; i < MAX_PERSISTED_EVENTS; i++) {
                        ed.remove(KEY_JOURNAL_EVENT + i);
                    }
                    ed.apply();
                }
            } catch (Throwable ignored) {
            }
            journalLoaded = true;
        }
    }

    /**
     * Суточный и месячный учёт трафика по реальным дельтам TrafficStats.
     *
     * <h3>Почему здесь</h3>
     * Раньше это был {@code Prefs.updateAndReadTrafficBytes()}, который не
     * вызывался ниоткуда: панель сети показывала только оценку канала, а
     * накопленные значения в настройках не обновлялись никогда. Панель сети —
     * единственное место, которое и так читает состояние сети, поэтому учёт
     * живёт здесь, а не в настройках.
     *
     * <h3>Что важно</h3>
     * Запись в SharedPreferences идёт не чаще раза в час и только при
     * фактическом изменении: считать трафик на каждый кадр и писать настройки
     * на каждый кадр — разные вещи, и второе недопустимо.
     */
    void accumulateTraffic(long nowMs) {
        try {
            long hour = nowMs / 3600_000L;
            if (hour == lastTrafficSampleHour) return;
            lastTrafficSampleHour = hour;

            long total = TrafficStats.getTotalRxBytes();
            if (total < 0L) return; // счётчик недоступен на части прошивок

            java.text.SimpleDateFormat dayFmt =
                    quickFormat("yyyy-MM-dd");
            java.text.SimpleDateFormat monFmt =
                    quickFormat("yyyy-MM");
            java.util.Date now = new java.util.Date(nowMs);
            String todayKey = dayFmt.format(now);
            String monthKey = monFmt.format(now);

            SharedPreferences sp = prefs.getSharedPreferences();
            if (sp == null) return;
            String savedDay = sp.getString(KEY_TR_DAY, "");
            String savedMonth = sp.getString(KEY_TR_MONTH, "");
            long lastRaw = sp.getLong(KEY_TR_LAST_RAW, -1L);
            long dayAcc = savedDay.equals(todayKey) ? sp.getLong(KEY_TR_DAY_ACC, 0L) : 0L;
            long monthAcc = savedMonth.equals(monthKey) ? sp.getLong(KEY_TR_MONTH_ACC, 0L) : 0L;

            long[] next = nextTrafficTotals(dayAcc, monthAcc, lastRaw, total);
            sp.edit()
                    .putString(KEY_TR_DAY, todayKey)
                    .putString(KEY_TR_MONTH, monthKey)
                    .putLong(KEY_TR_LAST_RAW, total)
                    .putLong(KEY_TR_DAY_ACC, next[0])
                    .putLong(KEY_TR_MONTH_ACC, next[1])
                    .apply();
        } catch (Throwable t) {
            Diagnostics.record("NetworkQualityHelper.accumulateTraffic", t);
        }
    }

    /**
     * Чистый расчёт новых сумм: без Android API, поэтому проверяется JVM-тестом
     * {@code Audit60Tests.trafficTotalsIgnoreAnomalies()}.
     *
     * @param lastRaw предыдущее показание счётчика, -1 если его ещё не было
     * @return {суточная сумма, месячная сумма}
     */
    static long[] nextTrafficTotals(long dayAcc, long monthAcc, long lastRaw, long currentTotal) {
        long day = Math.max(0L, dayAcc);
        long month = Math.max(0L, monthAcc);
        long current = Math.max(0L, currentTotal);
        if (lastRaw < 0L) {
            // Первый замер: стартуем от текущего счётчика сеанса, иначе первая
            // же дельта оказалась бы равна всему трафику с загрузки телефона.
            return new long[]{current, current};
        }
        // Счётчик мог обнулиться (перезагрузка) — тогда берём текущее значение,
        // но аномальный скачок (больше 50 ГБ за час) не засчитываем вовсе.
        long delta = (current >= lastRaw) ? (current - lastRaw) : current;
        final long MAX_HOURLY_DELTA = 50L * 1024L * 1024L * 1024L;
        if (delta > 0L && delta < MAX_HOURLY_DELTA) {
            day += delta;
            month += delta;
        }
        return new long[]{day, month};
    }

    /**
     * Строка «израсходовано за сутки / за месяц» для панели сети.
     *
     * Раньше эти числа не показывались нигде: учёт не вызывался, а значит и
     * выводить было нечего. Пустая строка — если данных ещё нет, чтобы панель
     * не показывала «0 Б» как будто трафик посчитан.
     */
    String formatTrafficLine() {
        try {
            SharedPreferences sp = prefs.getSharedPreferences();
            if (sp == null) return "";
            java.text.SimpleDateFormat dayFmt = quickFormat("yyyy-MM-dd");
            java.text.SimpleDateFormat monFmt = quickFormat("yyyy-MM");
            java.util.Date now = new java.util.Date();
            String todayKey = dayFmt.format(now);
            String monthKey = monFmt.format(now);
            long dayAcc = todayKey.equals(sp.getString(KEY_TR_DAY, ""))
                    ? sp.getLong(KEY_TR_DAY_ACC, 0L) : 0L;
            long monthAcc = monthKey.equals(sp.getString(KEY_TR_MONTH, ""))
                    ? sp.getLong(KEY_TR_MONTH_ACC, 0L) : 0L;
            if (dayAcc <= 0L && monthAcc <= 0L) return "";
            return "Трафик: за сутки " + formatTrafficBytes(dayAcc)
                    + " • за месяц " + formatTrafficBytes(monthAcc);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Байты в короткую подпись. Своя, а не {@code Formatter.formatShortFileSize}:
     * тот требует контекст, а расчёт проверяется JVM-тестом.
     */
    static String formatTrafficBytes(long bytes) {
        if (bytes < 0L) return "—";
        if (bytes < 1024L) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f КБ", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f МБ", mb);
        double gb = mb / 1024.0;
        if (gb < 1024.0) return String.format(java.util.Locale.US, "%.1f ГБ", gb);
        return String.format(java.util.Locale.US, "%.1f ТБ", gb / 1024.0);
    }

    /** Форматтер без общего состояния: SimpleDateFormat не потокобезопасен. */
    private static java.text.SimpleDateFormat quickFormat(String pattern) {
        return new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
    }

    public String formatStatsLine() {
        int cnt;
        long lastTs;
        synchronized (statsLock) {
            cnt = healCount;
            lastTs = lastHealTimeMs;
        }
        if (cnt == 0) {
            return "Залипаний не было • Работает тихо без всплывашек";
        }
        // Блокировка больше не нужна: форматтер создаётся внутри вызова.
        String hhmm = QuickShadeHelper.formatWithCurrentTimeZone(
                TIME_PATTERN, TIME_LOCALE, new Date(lastTs));
        return "Перепроверок за сеанс: " + cnt + " • Последняя в " + hhmm;
    }

    public synchronized void startForegroundMonitor(final boolean autoHealEnabled, final AutoHealListener listener) {
        startForegroundMonitor(autoHealEnabled, Prefs.NET_MODE_BALANCED,
                prefs.isAutoNetworkWeakWifi(), prefs.getAutoNetworkSensitivity(), listener);
    }

    public synchronized void startForegroundMonitor(
            final boolean autoHealEnabled,
            final int mode,
            final boolean weakWifiEnabled,
            final AutoHealListener listener
    ) {
        startForegroundMonitor(autoHealEnabled, mode, weakWifiEnabled,
                prefs.getAutoNetworkSensitivity(), listener);
    }

    public synchronized void startForegroundMonitor(
            final boolean autoHealEnabled,
            final int mode,
            final boolean weakWifiEnabled,
            final int sensitivity,
            final AutoHealListener listener
    ) {
        // Не перерегистрируем системный NetworkCallback впустую, если параметры не изменились
        if (activeCallback != null
                && activeAutoHeal == autoHealEnabled
                && activeMode == mode
                && activeWeakWifi == weakWifiEnabled
                && activeSensitivity == sensitivity) {
            return;
        }
        stopForegroundMonitor();
        if (cm == null) return;
        try {
            activeCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    evaluate(autoHealEnabled, mode, weakWifiEnabled, sensitivity, listener);
                }

                @Override
                public void onAvailable(Network network) {
                    evaluate(autoHealEnabled, mode, weakWifiEnabled, sensitivity, listener);
                }

                @Override
                public void onLost(Network network) {
                    evaluate(false, mode, false, sensitivity, listener);
                }
            };
            cm.registerDefaultNetworkCallback(activeCallback);
            activeAutoHeal = autoHealEnabled;
            activeMode = mode;
            activeWeakWifi = weakWifiEnabled;
            activeSensitivity = sensitivity;
        } catch (Throwable ignored) {
            activeCallback = null;
            activeMode = -1;
        }
    }

    public synchronized void stopForegroundMonitor() {
        if (cm != null && activeCallback != null) {
            try {
                cm.unregisterNetworkCallback(activeCallback);
            } catch (Throwable ignored) {
            }
            activeCallback = null;
            activeMode = -1;
            activeSensitivity = -1;
        }
    }

    private void evaluate(boolean autoHealEnabled, int mode, boolean weakWifiEnabled,
                          int sensitivity, AutoHealListener listener) {
        Status st = inspect();
        boolean healed = false;
        if (!permissionDeniedThisSession
                && shouldAutoReprobe(autoHealEnabled, mode, weakWifiEnabled,
                st.connected, st.validated, st.isWifi, st.signalDbm, st.is5Ghz, sensitivity)) {
            long now = SystemClock.elapsedRealtime();
            long cooldown = cooldownForMode(mode);
            if (lastAutoReprobeTs == 0L || (now - lastAutoReprobeTs) >= cooldown) {
                lastAutoReprobeTs = now;
                String reason = !st.validated ? "Авто: залипание канала" : "Авто: слабый Wi-Fi";
                healed = triggerSystemReprobe(reason);
            }
        }
        if (listener != null) {
            listener.onNetworkStatusChanged(st, healed);
        }
    }

    /**
     * Открывает мгновенную системную панель подключения Android 10+ (Wi-Fi + Мобильная сеть).
     */
    public static void openInternetQuickPanel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Intent i = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        openNetworkSettings(ctx);
    }

    public static void openNetworkSettings(Context ctx) {
        String[] actions = new String[]{
                Settings.ACTION_WIRELESS_SETTINGS,
                Settings.ACTION_WIFI_SETTINGS,
                Settings.ACTION_SETTINGS
        };
        for (String a : actions) {
            try {
                Intent i = new Intent(a);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    public static void openMobileNetworkSettings(Context ctx) {
        String[] actions = new String[]{
                Settings.ACTION_DATA_ROAMING_SETTINGS,
                Settings.ACTION_NETWORK_OPERATOR_SETTINGS,
                Settings.ACTION_WIRELESS_SETTINGS,
                Settings.ACTION_SETTINGS
        };
        for (String a : actions) {
            try {
                Intent i = new Intent(a);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    public static void copyDnsAndOpenSettings(Context ctx, String dnsHost) {
        try {
            ClipboardManager cb = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("Private DNS", dnsHost));
            }
        } catch (Throwable ignored) {
        }
        openNetworkSettings(ctx);
    }
}
