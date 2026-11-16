package com.doxaiz.launcher;

import android.content.pm.ApplicationInfo;

import java.util.Locale;

/**
 * Локальная классификация установленных приложений по простым русскоязычным категориям
 * на базе стандартного ApplicationInfo.category и имени пакета (без сети и без баз данных).
 */
public final class AppCategoryHelper {

    public static final int CAT_ALL = 0;
    public static final int CAT_COMM = 1;
    public static final int CAT_FINANCE = 2;
    public static final int CAT_MEDIA = 3;
    public static final int CAT_GAMES = 4;
    public static final int CAT_TOOLS = 5;
    public static final int CAT_SYSTEM = 6;

    public static final int[] ALL_CATEGORIES = new int[]{
            CAT_ALL, CAT_COMM, CAT_FINANCE, CAT_MEDIA, CAT_GAMES, CAT_TOOLS, CAT_SYSTEM
    };

    private AppCategoryHelper() {}

    public static String getTitle(int category) {
        switch (category) {
            case CAT_COMM: return "Связь";
            case CAT_FINANCE: return "Банки";
            case CAT_MEDIA: return "Медиа";
            case CAT_GAMES: return "Игры";
            case CAT_TOOLS: return "Инструменты";
            case CAT_SYSTEM: return "Система";
            case CAT_ALL:
            default: return "Все";
        }
    }

    @SuppressWarnings("deprecation")
    public static int classify(ApplicationInfo ai, String pkg, String label, boolean isSystemApp) {
        String p = pkg != null ? pkg.toLowerCase(Locale.ROOT) : "";
        String l = label != null ? label.toLowerCase(Locale.ROOT) : "";

        // 1. Банки, финансы, покупки, госуслуги
        if (containsAny(p, "sber", "tinkoff", "tbank", "vtb", "alfabank", "gazprombank", "raiffeisen",
                "psbank", "pochtabank", "sovcombank", "ozon", "wildberries", "avito", "ymarket",
                "beru", "lamoda", "aliexpress", "bank", "wallet", "pay", "gosuslugi", "nalog", "kaspi")
                || containsAny(l, "сбер", "т-банк", "тинькофф", "втб", "альфа-банк", "озон",
                "ozon", "wildberries", "авито", "госуслуги", "кошелёк", "банк")) {
            return CAT_FINANCE;
        }

        // 2. Игры (по системному флагу Android и категориям)
        if (ai != null) {
            if (ai.category == ApplicationInfo.CATEGORY_GAME
                    || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) {
                return CAT_GAMES;
            }
        }
        if (containsAny(p, "supercell", "mojang", "roblox", "pubg", "garena", "mihoyo", "hoyoverse",
                "ea.game", "gameloft", "rovio", "miniclip", "playrix", "wargaming", "lesta",
                "standoff", "brawlstars", "clash", "brawl", "chess")) {
            return CAT_GAMES;
        }

        // 3. Связь и мессенджеры
        if (containsAny(p, "telegram", "whatsapp", "viber", "vkontakte", "vk.im", "ok.android",
                "discord", "skype", "signal", "icq", "tam.tam", "mail", "gmail", "outlook",
                "dialer", "contacts", "messaging", "mms", "telecom", "incallui", "phone", "max.messenger")
                || containsAny(l, "телеграм", "telegram", "whatsapp", "ватсап", "вконтакте", "почта",
                "сообщения", "телефон", "контакты", "звонки")) {
            return CAT_COMM;
        }

        // 4. Медиа: видео, музыка, фото, камера
        if (containsAny(p, "youtube", "music", "spotify", "kinopoisk", "twitch", "tiktok",
                "rutube", "vkvideo", "gallery", "photos", "camera", "radio", "podcast", "vlc", "mxtech",
                "soundcloud", "deezer", "shazam", "instagram", "pinterest")
                || containsAny(l, "камера", "галерея", "музыка", "видео", "фото", "кино", "диктофон")) {
            return CAT_MEDIA;
        }

        // 5. Системная категория Android (API 26+)
        if (ai != null) {
            switch (ai.category) {
                case ApplicationInfo.CATEGORY_SOCIAL:
                    return CAT_COMM;
                case ApplicationInfo.CATEGORY_AUDIO:
                case ApplicationInfo.CATEGORY_VIDEO:
                case ApplicationInfo.CATEGORY_IMAGE:
                    return CAT_MEDIA;
                case ApplicationInfo.CATEGORY_MAPS:
                case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                case ApplicationInfo.CATEGORY_NEWS:
                    return CAT_TOOLS;
                default:
                    break;
            }
        }

        // 6. Инструменты: браузеры, карты, VPN, заметки, офисы, утилиты
        if (containsAny(p, "chrome", "browser", "firefox", "opera", "yandex.browser", "edge",
                "maps", "2gis", "navi", "taxi", "uber", "translate", "notes", "keep", "calc",
                "calendar", "deskclock", "clock", "alarm", "weather", "vpn", "wireguard", "v2ray",
                "amnezia", "nekobox", "clash", "hiddify", "docs", "sheets", "pdf", "office",
                "scanner", "compass", "files", "filemanager", "documentsui", "rar", "zarchiver")
                || containsAny(l, "браузер", "карты", "навигатор", "такси", "переводчик",
                "заметки", "калькулятор", "календарь", "часы", "будильник", "погода", "файлы", "vpn")) {
            return CAT_TOOLS;
        }

        // 7. Система и магазины приложений
        if (isSystemApp || containsAny(p, "settings", "transsion", "infinix", "vending",
                "rustore", "palmstore", "getapps", "android.gms", "phonemaster", "simtoolkit")) {
            return CAT_SYSTEM;
        }

        return CAT_TOOLS;
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null || haystack.isEmpty()) return false;
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
