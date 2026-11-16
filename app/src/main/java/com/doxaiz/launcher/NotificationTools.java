package com.doxaiz.launcher;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Чистые pure-JVM утилиты шторки уведомлений Doxaiz 4.5 (100% офлайн, без Android UI):
 * - Умное извлечение одноразовых кодов подтверждения (OTP / SMS / банк) с защитой от сумм, дат и карт;
 * - Форматирование живого относительного времени («сейчас», «5 мин», «2 ч»);
 * - Поиск по локальной истории уведомлений.
 */
public final class NotificationTools {

    public static final int FILTER_ALL = 0;
    public static final int FILTER_MESSAGES = 1;
    public static final int FILTER_CODES = 2;

    private static final String[] OTP_KEYWORDS = {
            "код", "парол", "подтвер", "однораз", "смс", "вход", "авториз",
            "вериф", "секрет", "никому", "не сообщай", "не передав", "активац",
            "code", "otp", "pin", "passcode", "verify", "verification",
            "login", "sign in", "2fa", "auth", "one-time", "security"
    };

    // Код из 4-8 цифр или 3+3 через пробел/дефис (например 482-910 или 482 910),
    // но НЕ часть телефонного номера вида 912 345 6789 или 912-345-67-89
    private static final Pattern CODE_GROUPED = Pattern.compile("(?<![\\d+\\-*•#(])(\\d{3})[\\- ](\\d{3})(?![\\d\\- ]\\d)");
    private static final Pattern CODE_DIGITS = Pattern.compile("(?<![\\d+\\-*•#№])(\\d{4,8})(?!\\d)");

    private NotificationTools() {}

    /**
     * Извлекает код подтверждения (4-8 цифр) из заголовка и текста уведомления.
     * Возвращает null, если в тексте нет явного контекста кода или число похоже на сумму/дату/карту/время.
     */
    public static String extractOtpCode(String title, String text) {
        String t = title != null ? title.trim() : "";
        String b = text != null ? text.trim() : "";
        String combined = (t.isEmpty() ? b : (b.isEmpty() ? t : (t + " " + b))).trim();
        if (combined.length() < 4) return null;

        String low = combined.toLowerCase(Locale.ROOT);
        boolean hasKeyword = false;
        for (String kw : OTP_KEYWORDS) {
            if (low.contains(kw)) {
                hasKeyword = true;
                break;
            }
        }
        if (!hasKeyword) return null;

        // 1) Сначала проверяем сгруппированные 6-значные коды вида 482-910 или 482 910
        Matcher mg = CODE_GROUPED.matcher(combined);
        while (mg.find()) {
            String candidate = mg.group(1) + mg.group(2);
            if (!isFalsePositiveMatch(combined, mg.start(), mg.end(), candidate)) {
                return candidate;
            }
        }

        // 2) Затем обычные 4-8-значные коды, отдавая приоритет кодам рядом с ключевым словом
        String best = null;
        int bestScore = -1;
        Matcher md = CODE_DIGITS.matcher(combined);
        while (md.find()) {
            String candidate = md.group(1);
            int start = md.start(1);
            int end = md.end(1);
            if (isFalsePositiveMatch(combined, start, end, candidate)) {
                continue;
            }
            int score = scoreCandidate(low, start, end, candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isFalsePositiveMatch(String src, int start, int end, String digits) {
        // Не брать часть времени (14:30), даты (15.11.2026), дроби (12.50), номера заказа (#/№) или телефона,
        // но точку в конце предложения («Код: 482910. Никому не сообщайте») допускаем!
        if (start > 0) {
            char prev = src.charAt(start - 1);
            if (prev == ':' || prev == '/' || prev == '*' || prev == '•' || prev == '+'
                    || prev == '#' || prev == '№' || prev == '(') {
                return true;
            }
            if (prev == '.' && start > 1 && Character.isDigit(src.charAt(start - 2))) {
                return true;
            }
        }
        if (end < src.length()) {
            char next = src.charAt(end);
            if (next == ':' || next == '/' || next == '%' || next == ')') {
                return true;
            }
            if (next == '.' && end + 1 < src.length() && Character.isDigit(src.charAt(end + 1))) {
                return true;
            }
        }

        // Проверяем ближайший контекст справа (валюта/единицы) и слева (карта/счёт/заказ/телефон)
        String after = src.substring(end, Math.min(src.length(), end + 10)).toLowerCase(Locale.ROOT).trim();
        if (after.startsWith("₽") || after.startsWith("руб") || after.startsWith("р.")
                || after.startsWith("rub") || after.startsWith("usd") || after.startsWith("$")
                || after.startsWith("€") || after.startsWith("мб") || after.startsWith("гб")
                || after.startsWith("кб") || after.startsWith("мин") || after.startsWith("сек")) {
            return true;
        }
        String before = src.substring(Math.max(0, start - 16), start).toLowerCase(Locale.ROOT).trim();
        if (before.endsWith("карт") || before.endsWith("карты") || before.endsWith("счет")
                || before.endsWith("счёт") || before.endsWith("счета") || before.endsWith("счёта")
                || before.endsWith("card") || before.endsWith("mir") || before.endsWith("visa")
                || before.endsWith("заказ") || before.endsWith("заказа") || before.endsWith("чек")
                || before.endsWith("билет") || before.endsWith("order") || before.endsWith("ticket")
                || before.endsWith("invoice") || before.endsWith("№") || before.endsWith("#")
                || before.endsWith("тел") || before.endsWith("тел.") || before.endsWith("номер")
                || before.endsWith("+7") || before.endsWith("8(")
                || before.matches(".*(?:^|\\s)(?:\\+?7|8)\\s*\\(?\\d{3}\\)?$")) {
            return true;
        }
        // Если перед числом стоит другая группа цифр вида «8 912 » или «912-345-», это часть телефона или суммы
        if (before.matches(".*(?:^|\\s)(?:\\+?7|8)$") || before.matches(".*\\d{3}[\\- ]\\d{3}$")) {
            return true;
        }

        // Отсекаем явные годы 2020..2035, если код 4-значный и рядом нет «код» вплотную
        if (digits.length() == 4) {
            int val = Integer.parseInt(digits);
            if (val >= 2020 && val <= 2035) {
                boolean directCode = before.endsWith("код") || before.endsWith("код:")
                        || before.endsWith("code") || before.endsWith("code:")
                        || before.endsWith("pin") || before.endsWith("otp");
                if (!directCode) return true;
            }
        }
        return false;
    }

    private static int scoreCandidate(String low, int start, int end, String digits) {
        int score = (digits.length() == 6 || digits.length() == 4) ? 20 : 12;
        String nearBefore = low.substring(Math.max(0, start - 22), start);
        String nearAfter = low.substring(end, Math.min(low.length(), end + 22));
        for (String kw : OTP_KEYWORDS) {
            if (nearBefore.contains(kw)) score += 30;
            if (nearAfter.contains(kw)) score += 15;
        }
        return score;
    }

    /**
     * Форматирует живое относительное время уведомления («сейчас», «3 мин», «2 ч» или HH:mm).
     */
    public static String formatRelativeTime(long postTimeMs, long nowMs, String hhmmFallback) {
        String fb = (hhmmFallback != null && !hhmmFallback.isEmpty()) ? hhmmFallback : "";
        if (postTimeMs <= 0L || nowMs <= 0L) return fb;
        long diff = nowMs - postTimeMs;
        if (diff < 0L) return fb;
        if (diff < 45_000L) return "сейчас";
        long mins = diff / 60_000L;
        if (mins < 60L) return Math.max(1L, mins) + " мин";
        long hours = mins / 60L;
        if (hours < 24L) return hours + " ч";
        return fb;
    }

    /**
     * Быстрый регистронезависимый поиск по локальной истории уведомлений.
     */
    public static boolean matchesHistoryQuery(String appLabel, String title, String text, String query) {
        if (query == null) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        if (appLabel != null && appLabel.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (title != null && title.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (text != null && text.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }
}
