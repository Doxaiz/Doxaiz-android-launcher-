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
 * Честный контроль и тихое авто-улучшение качества сети без root и без разрешения INTERNET:
 * - Отслеживает тип сети (Wi-Fi / 4G-5G / VPN), валидацию доступа, пропускную способность,
 *   реальное состояние Частного DNS (LinkProperties API 28+), лимитность сети (NOT_METERED)
 *   и уровень сигнала (API 29+ getSignalStrength без геолокации).
 * - Поддерживает 3 режима (Мягкий 30с / Сбалансированный 15с / Активный 10с) + контроль слабого Wi-Fi.
 * - Работает тихо в фоне без всплывашек: фиксирует статистику и журнал восстановлений в памяти.
 */
public final class NetworkQualityHelper {

    public static final int MAX_LOG_EVENTS = 6;
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

        public Status(boolean connected, boolean validated, boolean hasVpn,
                      String transportName, int downMbps, int upMbps,
                      String headline, String details) {
            this(connected, validated, hasVpn, false, false, Integer.MIN_VALUE,
                    false, "", transportName, downMbps, upMbps, headline, details,
                    formatPrivateDnsSummary(false, ""), "");
        }

        public Status(boolean connected, boolean validated, boolean hasVpn, boolean isWifi,
                      boolean metered, int signalDbm, boolean privateDnsActive, String privateDnsServer,
                      String transportName, int downMbps, int upMbps,
                      String headline, String details, String dnsSummary, String signalSummary) {
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
            int threshold = (mode == Prefs.NET_MODE_ACTIVE) ? WEAK_WIFI_ACTIVE_DBM : WEAK_WIFI_BALANCED_DBM;
            return signalDbm <= threshold;
        }
        return false;
    }

    public static String formatSignalQuality(int dbm) {
        if (dbm == Integer.MIN_VALUE || dbm <= -120 || dbm >= 0) return "";
        String level;
        if (dbm >= -60) level = "Отличный";
        else if (dbm >= -70) level = "Хороший";
        else if (dbm >= -79) level = "Средний";
        else level = "Слабый";
        return level + " (" + dbm + " дБм)";
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
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    LinkProperties lp = cm.getLinkProperties(net);
                    if (lp != null) {
                        dnsActive = lp.isPrivateDnsActive();
                        String srv = lp.getPrivateDnsServerName();
                        if (srv != null) dnsServer = srv.trim();
                    }
                } catch (Throwable ignored) {
                }
            }

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

            String sigSummary = formatSignalQuality(signalDbm);
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
                    headline, det.toString(), dnsSummary, sigSummary);
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
        if (cm == null) return false;
        try {
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            // reportNetworkConnectivity() вне @SystemApi доступен, но поведение зависит
            // от прошивки: часть OEM-сборок молча игнорирует вызов. Раньше результат
            // никак не отражался и функция выглядела «сработавшей» всегда.
            cm.reportNetworkConnectivity(net, false);
            Status st = inspect();
            boolean reported = st.connected && st.validated;
            lastReprobeAccepted = reported;
            recordHealEvent(st.transportName, reported ? reason : (reason + " (без эффекта)"));
            return reported;
        } catch (Throwable ignored) {
            lastReprobeAccepted = false;
            return false;
        }
    }

    /** true, если последний reportNetworkConnectivity() действительно оставил сеть валидной. */
    public boolean wasLastReprobeAccepted() {
        return lastReprobeAccepted;
    }

    private void recordHealEvent(String transport, String reason) {
        long now = System.currentTimeMillis();
        synchronized (statsLock) {
            healCount++;
            lastHealTimeMs = now;
            recentEvents.add(0, new HealEvent(now, transport, reason));
            while (recentEvents.size() > MAX_LOG_EVENTS) {
                recentEvents.remove(recentEvents.size() - 1);
            }
        }
    }

    public int getHealCount() {
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
        synchronized (statsLock) {
            return new ArrayList<>(recentEvents);
        }
    }

    public void clearHealStats() {
        synchronized (statsLock) {
            healCount = 0;
            lastHealTimeMs = 0L;
            recentEvents.clear();
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
        startForegroundMonitor(autoHealEnabled, Prefs.NET_MODE_BALANCED, true, listener);
    }

    public synchronized void startForegroundMonitor(
            final boolean autoHealEnabled,
            final int mode,
            final boolean weakWifiEnabled,
            final AutoHealListener listener
    ) {
        // Не перерегистрируем системный NetworkCallback впустую, если параметры не изменились
        if (activeCallback != null
                && activeAutoHeal == autoHealEnabled
                && activeMode == mode
                && activeWeakWifi == weakWifiEnabled) {
            return;
        }
        stopForegroundMonitor();
        if (cm == null) return;
        try {
            activeCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    evaluate(autoHealEnabled, mode, weakWifiEnabled, listener);
                }

                @Override
                public void onAvailable(Network network) {
                    evaluate(autoHealEnabled, mode, weakWifiEnabled, listener);
                }

                @Override
                public void onLost(Network network) {
                    evaluate(false, mode, false, listener);
                }
            };
            cm.registerDefaultNetworkCallback(activeCallback);
            activeAutoHeal = autoHealEnabled;
            activeMode = mode;
            activeWeakWifi = weakWifiEnabled;
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
        }
    }

    private void evaluate(boolean autoHealEnabled, int mode, boolean weakWifiEnabled, AutoHealListener listener) {
        Status st = inspect();
        boolean healed = false;
        if (shouldAutoReprobe(autoHealEnabled, mode, weakWifiEnabled,
                st.connected, st.validated, st.isWifi, st.signalDbm)) {
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
