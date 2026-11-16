package com.doxaiz.launcher;

import java.util.Locale;

/**
 * Умный поиск приложений на русском и английском:
 * - обычное вхождение подстроки
 * - исправление ошибочной раскладки клавиатуры (EN <-> RU, напр. "ntktu" -> "телег")
 * - транслитерация кириллицы в латиницу (напр. "телег" -> "teleg" для Telegram)
 */
public final class SearchHelper {

    private static final String EN_KEYS = "qwertyuiop[]asdfghjkl;'zxcvbnm,.";
    private static final String RU_KEYS = "йцукенгшщзхъфывапролджэячсмитьбю";

    /**
     * Предвычисленный поисковый запрос: создаётся 1 раз на ввод буквы и переиспользуется
     * по всем приложениям и внутри сортировки без повторных аллокаций StringBuilder.
     */
    public static final class PreparedQuery {
        public final String q;
        public final String swapped;
        public final String translitQ;
        public final String translitSwapped;
        public final boolean empty;
        final boolean hasSwapped;
        final boolean hasTranslitQ;
        final boolean hasTranslitSwapped;

        PreparedQuery(String rawQuery) {
            String clean = (rawQuery != null) ? rawQuery.trim().toLowerCase(Locale.ROOT) : "";
            this.q = clean;
            this.empty = clean.isEmpty();
            if (this.empty) {
                this.swapped = "";
                this.translitQ = "";
                this.translitSwapped = "";
                this.hasSwapped = false;
                this.hasTranslitQ = false;
                this.hasTranslitSwapped = false;
            } else {
                this.swapped = swapKeyboardLayout(clean);
                this.translitQ = translitRuToLat(clean);
                this.translitSwapped = translitRuToLat(this.swapped);
                this.hasSwapped = !this.swapped.equals(clean);
                this.hasTranslitQ = !this.translitQ.equals(clean);
                this.hasTranslitSwapped = !this.translitSwapped.equals(this.swapped);
            }
        }
    }

    private SearchHelper() {}

    public static PreparedQuery prepare(String rawQuery) {
        return new PreparedQuery(rawQuery);
    }

    public static boolean matches(AppEntry entry, String rawQuery) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) return true;
        return matches(entry, prepare(rawQuery));
    }

    public static boolean matches(AppEntry entry, PreparedQuery pq) {
        if (pq == null || pq.empty) return true;
        if (entry == null) return false;
        String hay = entry.searchKey;
        if (hay.contains(pq.q)) return true;
        if (pq.hasSwapped && hay.contains(pq.swapped)) return true;
        if (pq.hasTranslitQ && hay.contains(pq.translitQ)) return true;
        if (pq.hasTranslitSwapped && hay.contains(pq.translitSwapped)) return true;

        String hayLat = entry.translitSearchKey;
        if (hayLat != null && !hayLat.equals(hay)) {
            if (hayLat.contains(pq.q) || (pq.hasTranslitQ && hayLat.contains(pq.translitQ))) return true;
        }
        return false;
    }

    /**
     * Ранжирует совпадения: начало названия выше середины названия, выше имени пакета.
     * Учитывает смену раскладки, транслит запроса и предвычисленный транслит названия без аллокаций.
     */
    public static int matchScore(AppEntry entry, String rawQuery) {
        if (entry == null || rawQuery == null || rawQuery.trim().isEmpty()) return 0;
        return matchScore(entry, prepare(rawQuery));
    }

    public static int matchScore(AppEntry entry, PreparedQuery pq) {
        if (entry == null || pq == null || pq.empty) return 0;
        String label = entry.labelLower != null ? entry.labelLower : "";
        String labelLat = entry.labelTranslit != null ? entry.labelTranslit : label;

        if (label.startsWith(pq.q)
                || (pq.hasSwapped && label.startsWith(pq.swapped))
                || (pq.hasTranslitQ && label.startsWith(pq.translitQ))
                || (pq.hasTranslitSwapped && label.startsWith(pq.translitSwapped))
                || labelLat.startsWith(pq.q)
                || (pq.hasTranslitQ && labelLat.startsWith(pq.translitQ))) {
            return 3;
        }
        if (label.contains(pq.q)
                || (pq.hasSwapped && label.contains(pq.swapped))
                || (pq.hasTranslitQ && label.contains(pq.translitQ))
                || (pq.hasTranslitSwapped && label.contains(pq.translitSwapped))
                || labelLat.contains(pq.q)
                || (pq.hasTranslitQ && labelLat.contains(pq.translitQ))) {
            return 2;
        }
        return 1;
    }

    public static String swapKeyboardLayout(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int idxEn = EN_KEYS.indexOf(c);
            if (idxEn >= 0) {
                sb.append(RU_KEYS.charAt(idxEn));
                continue;
            }
            int idxRu = RU_KEYS.indexOf(c);
            if (idxRu >= 0) {
                sb.append(EN_KEYS.charAt(idxRu));
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String translitRuToLat(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 4);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case 'а': sb.append('a'); break;
                case 'б': sb.append('b'); break;
                case 'в': sb.append('v'); break;
                case 'г': sb.append('g'); break;
                case 'д': sb.append('d'); break;
                case 'е': sb.append('e'); break;
                case 'ё': sb.append('e'); break;
                case 'ж': sb.append("zh"); break;
                case 'з': sb.append('z'); break;
                case 'и': sb.append('i'); break;
                case 'й': sb.append('y'); break;
                case 'к': sb.append('k'); break;
                case 'л': sb.append('l'); break;
                case 'м': sb.append('m'); break;
                case 'н': sb.append('n'); break;
                case 'о': sb.append('o'); break;
                case 'п': sb.append('p'); break;
                case 'р': sb.append('r'); break;
                case 'с': sb.append('s'); break;
                case 'т': sb.append('t'); break;
                case 'у': sb.append('u'); break;
                case 'ф': sb.append('f'); break;
                case 'х': sb.append('h'); break;
                case 'ц': sb.append("ts"); break;
                case 'ч': sb.append("ch"); break;
                case 'ш': sb.append("sh"); break;
                case 'щ': sb.append("sh"); break;
                case 'ъ': break;
                case 'ы': sb.append('y'); break;
                case 'ь': break;
                case 'э': sb.append('e'); break;
                case 'ю': sb.append("yu"); break;
                case 'я': sb.append("ya"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }
}
