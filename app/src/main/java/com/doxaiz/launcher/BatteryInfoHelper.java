package com.doxaiz.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import java.util.Locale;

/**
 * BatteryInfoHelper (Doxaiz 4.1) — лёгкое чтение реальных системных датчиков батареи
 * без лишних разрешений и без фоновых служб (sticky ACTION_BATTERY_CHANGED + BatteryManager).
 */
public final class BatteryInfoHelper {

    public static final class Snapshot {
        public final int levelPct;
        public final float tempC;
        public final int voltageMv;
        public final float powerWatts;
        public final boolean isCharging;
        public final boolean isFull;
        public final String plugLabel;
        public final String healthLabel;
        public final boolean healthGood;
        public final String technology;
        public final int tempColor;
        public final String pillSummary;
        public final String lockOverlaySuffix;

        Snapshot(int levelPct, float tempC, int voltageMv, float powerWatts,
                 boolean isCharging, boolean isFull, String plugLabel,
                 String healthLabel, boolean healthGood, String technology) {
            this.levelPct = levelPct;
            this.tempC = tempC;
            this.voltageMv = voltageMv;
            this.powerWatts = powerWatts;
            this.isCharging = isCharging;
            this.isFull = isFull;
            this.plugLabel = plugLabel;
            this.healthLabel = healthLabel;
            this.healthGood = healthGood;
            this.technology = technology;
            this.tempColor = tempAccentColor(tempC);

            String tempStr = formatTemp(tempC);
            StringBuilder sb = new StringBuilder();
            if (levelPct >= 0) sb.append(levelPct).append('%');
            if (!tempStr.isEmpty()) {
                if (sb.length() > 0) sb.append(" • ");
                sb.append(tempStr);
            }
            if (isCharging && powerWatts >= 1.5f) {
                sb.append(" • ").append(formatWatts(powerWatts));
            } else if (!healthLabel.isEmpty()) {
                sb.append(" • ").append(healthLabel);
            }
            this.pillSummary = sb.toString();

            StringBuilder shortSb = new StringBuilder();
            if (levelPct >= 0) shortSb.append(levelPct).append('%');
            if (!tempStr.isEmpty()) {
                if (shortSb.length() > 0) shortSb.append(' ');
                shortSb.append(tempStr);
            }
            this.lockOverlaySuffix = shortSb.toString();
        }
    }

    private BatteryInfoHelper() {}

    public static Snapshot read(Context context) {
        int pct = -1;
        float tempC = 0f;
        int voltageMv = 0;
        boolean charging = false;
        boolean full = false;
        int plugged = 0;
        int health = BatteryManager.BATTERY_HEALTH_GOOD;
        String tech = "Li-Po";

        try {
            Intent sticky = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) {
                int level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) {
                    pct = Math.max(0, Math.min(100, Math.round((level * 100f) / scale)));
                }
                int rawTemp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                if (rawTemp > 0) tempC = rawTemp / 10f;
                int rawVolt = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                if (rawVolt > 0) {
                    // На некоторых устройствах напряжение приходит в вольтах (< 20) вместо мВ
                    voltageMv = (rawVolt < 20) ? (rawVolt * 1000) : rawVolt;
                }
                int status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL);
                full = (status == BatteryManager.BATTERY_STATUS_FULL) || (charging && pct >= 100);
                plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                health = sticky.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD);
                String t = sticky.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
                if (t != null && !t.trim().isEmpty()) tech = t.trim();
            }
        } catch (Throwable ignored) {
        }

        float watts = 0f;
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null && voltageMv > 0) {
                int currentRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (currentRaw == 0 || currentRaw == Integer.MIN_VALUE) {
                    currentRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
                }
                watts = computeWatts(currentRaw, voltageMv, charging, full);
            }
        } catch (Throwable ignored) {
        }

        String plugLbl = resolvePlugLabel(plugged, charging, full, watts);
        String healthLbl = resolveHealthLabel(health);
        boolean good = (health == BatteryManager.BATTERY_HEALTH_GOOD);

        return new Snapshot(pct, tempC, voltageMv, watts, charging, full, plugLbl, healthLbl, good, tech);
    }

    static float computeWatts(int currentRaw, int voltageMv) {
        return computeWatts(currentRaw, voltageMv, true, false);
    }

    static float computeWatts(int currentRaw, int voltageMv, boolean charging, boolean full) {
        if (currentRaw == 0 || currentRaw == Integer.MIN_VALUE || voltageMv <= 0) return 0f;
        long absCur = Math.abs((long) currentRaw);
        // Отсекаем физически невозможные мусорные sentinel-значения драйвера (> 50 А)
        if (absCur > 50_000_000L) return 0f;
        double volts = voltageMv / 1000.0;
        // На стандартном Android датчик отдаёт мкА (напр. 1_500_000 мкА = 1.5 А); на части OEM/MediaTek — мА (< 15_000).
        // При капельной дозарядке (full) или разряде от батареи физически невозможные значения (> 12 Вт / > 20 Вт)
        // означают, что датчик работает в мкА, а не в мА.
        double amps;
        if (absCur > 15000L) {
            amps = absCur / 1_000_000.0;
        } else {
            double asMaWatts = (absCur / 1_000.0) * volts;
            if ((full && asMaWatts > 12.0) || (!charging && asMaWatts > 20.0)) {
                amps = absCur / 1_000_000.0;
            } else {
                amps = absCur / 1_000.0;
            }
        }
        double w = amps * volts;
        if (w < 0.1 || w > 180.0) return 0f;
        return (float) w;
    }

    static String formatTemp(float tempC) {
        if (tempC <= 0.1f) return "";
        return String.format(Locale.US, "%.1f°C", tempC);
    }

    static String formatVoltage(int voltageMv) {
        if (voltageMv <= 0) return "—";
        return String.format(Locale.US, "%.2f В", voltageMv / 1000.0);
    }

    static String formatWatts(float watts) {
        if (watts < 0.2f) return "—";
        if (watts >= 10f) return String.format(Locale.US, "%.0f Вт", watts);
        return String.format(Locale.US, "%.1f Вт", watts);
    }

    static int tempAccentColor(float tempC) {
        if (tempC >= 43.0f) return 0xFFEF4444; // Горячая (красный)
        if (tempC >= 38.5f) return 0xFFF59E0B; // Тёплая (янтарный)
        return 0xFF22C55E;                     // Норма (зелёный)
    }

    static String resolveHealthLabel(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Отличное";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Перегрев";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Износ";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Высок. напр.";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Холодная";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Сбой";
            default: return "Норма";
        }
    }

    static String resolvePlugLabel(int plugged, boolean charging, boolean full, float watts) {
        if (full) return "Полностью заряжена";
        if (!charging && plugged == 0) return "От батареи";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) return "Беспроводная";
        if ((plugged & 8) != 0) return "Док-станция"; // BatteryManager.BATTERY_PLUGGED_DOCK (API 33+)
        if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) return "USB-зарядка";
        if (watts >= 15.0f) return "Быстрая зарядка";
        return "От сети (AC)";
    }
}
