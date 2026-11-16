package com.doxaiz.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CustomSection (Doxaiz 4.3) — пользовательский раздел в меню «Все приложения».
 * Поддерживает мульти-принадлежность (одно приложение может состоять в нескольких своих разделах
 * одновременно, оставаясь в своей стандартной категории), свободный порядок (◀ / ▶) и JSON-хранение.
 */
public final class CustomSection {

    public static final int MAX_SECTIONS = 24;
    public static final int MAX_NAME_LEN = 24;

    public final String id;
    public String name;
    public final LinkedHashSet<String> packages;

    public CustomSection(String id, String name, Collection<String> pkgs) {
        this.id = (id != null && !id.trim().isEmpty()) ? id.trim() : ("sec_" + System.currentTimeMillis());
        this.name = sanitizeName(name);
        this.packages = new LinkedHashSet<>();
        if (pkgs != null) {
            for (String p : pkgs) {
                if (p != null && !p.trim().isEmpty()) {
                    this.packages.add(p.trim());
                }
            }
        }
    }

    public static String sanitizeName(String raw) {
        if (raw == null) return "Раздел";
        String s = raw.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return "Раздел";
        if (s.length() > MAX_NAME_LEN) s = s.substring(0, MAX_NAME_LEN).trim();
        return s.isEmpty() ? "Раздел" : s;
    }

    public static String nextUniqueId(List<CustomSection> existing) {
        int maxNum = 0;
        if (existing != null) {
            for (CustomSection s : existing) {
                if (s != null && s.id != null && s.id.startsWith("sec_")) {
                    try {
                        int n = Integer.parseInt(s.id.substring(4));
                        if (n > maxNum) maxNum = n;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return "sec_" + (maxNum + 1);
    }

    public static String toJson(List<CustomSection> sections) {
        JSONArray arr = new JSONArray();
        if (sections != null) {
            int limit = Math.min(sections.size(), MAX_SECTIONS);
            for (int i = 0; i < limit; i++) {
                CustomSection sec = sections.get(i);
                if (sec == null) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", sec.id);
                    o.put("name", sanitizeName(sec.name));
                    JSONArray pa = new JSONArray();
                    for (String pkg : sec.packages) {
                        if (pkg != null && !pkg.isEmpty()) pa.put(pkg);
                    }
                    o.put("pkgs", pa);
                    arr.put(o);
                } catch (Throwable ignored) {
                }
            }
        }
        return arr.toString();
    }

    public static List<CustomSection> fromJson(String raw) {
        List<CustomSection> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            Set<String> seenIds = new LinkedHashSet<>();
            int limit = Math.min(arr.length(), MAX_SECTIONS);
            for (int i = 0; i < limit; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "").trim();
                if (id.isEmpty() || seenIds.contains(id)) continue;
                seenIds.add(id);
                String name = sanitizeName(o.optString("name", "Раздел"));
                List<String> pkgs = new ArrayList<>();
                JSONArray pa = o.optJSONArray("pkgs");
                if (pa != null) {
                    int pLimit = Math.min(pa.length(), 300);
                    for (int j = 0; j < pLimit; j++) {
                        String p = pa.optString(j, "").trim();
                        if (!p.isEmpty()) pkgs.add(p);
                    }
                }
                out.add(new CustomSection(id, name, pkgs));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static CustomSection findById(List<CustomSection> list, String id) {
        if (list == null || id == null) return null;
        for (CustomSection s : list) {
            if (s != null && id.equals(s.id)) return s;
        }
        return null;
    }

    /**
     * Перемещает раздел влево (delta = -1) или вправо (delta = +1). Возвращает true, если порядок изменился.
     */
    public static boolean moveSection(List<CustomSection> list, String id, int delta) {
        if (list == null || id == null || delta == 0) return false;
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) != null && id.equals(list.get(i).id)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return false;
        int target = idx + (delta < 0 ? -1 : 1);
        if (target < 0 || target >= list.size()) return false;
        CustomSection item = list.remove(idx);
        list.add(target, item);
        return true;
    }

    /**
     * Включает/выключает участие приложения в указанном разделе (мульти-принадлежность).
     */
    public static boolean togglePackage(List<CustomSection> list, String sectionId, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return false;
        CustomSection sec = findById(list, sectionId);
        if (sec == null) return false;
        String clean = pkg.trim();
        if (sec.packages.contains(clean)) {
            sec.packages.remove(clean);
            return false;
        }
        sec.packages.add(clean);
        return true;
    }

    public static boolean removeSection(List<CustomSection> list, String sectionId) {
        if (list == null || sectionId == null) return false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) != null && sectionId.equals(list.get(i).id)) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }

    public static List<String> getSectionNamesForPackage(List<CustomSection> list, String pkg) {
        List<String> names = new ArrayList<>();
        if (list == null || pkg == null || pkg.isEmpty()) return names;
        for (CustomSection s : list) {
            if (s != null && s.packages.contains(pkg)) {
                names.add(s.name);
            }
        }
        return names;
    }

    public static int countInstalledPackages(CustomSection sec, Collection<String> installedPkgs) {
        if (sec == null || installedPkgs == null || sec.packages.isEmpty()) return 0;
        int n = 0;
        for (String p : installedPkgs) {
            if (sec.packages.contains(p)) n++;
        }
        return n;
    }

    /**
     * Парсит CSV скрытых стандартных категорий (только 1..6; CAT_ALL=0 скрыть нельзя).
     */
    public static Set<Integer> parseHiddenStdCategories(String csv) {
        Set<Integer> out = new LinkedHashSet<>();
        if (csv == null || csv.trim().isEmpty()) return out;
        String[] parts = csv.split(",");
        for (String p : parts) {
            try {
                int c = Integer.parseInt(p.trim());
                if (c >= AppCategoryHelper.CAT_COMM && c <= AppCategoryHelper.CAT_SYSTEM) {
                    out.add(c);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static String encodeHiddenStdCategories(Set<Integer> hidden) {
        if (hidden == null || hidden.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Integer c : hidden) {
            if (c != null && c >= AppCategoryHelper.CAT_COMM && c <= AppCategoryHelper.CAT_SYSTEM) {
                if (sb.length() > 0) sb.append(',');
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
