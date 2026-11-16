package com.doxaiz.launcher;

import java.util.ArrayList;
import java.util.List;

public final class DesktopItem {
    public static final int TYPE_APP = 0;
    public static final int TYPE_FOLDER = 1;

    public final int type;
    public String appKey;
    public String folderId;
    public String folderName;
    public final List<String> folderApps = new ArrayList<>();
    /** Свободные координаты на сетке страницы (-1 = ещё не назначена). */
    public int col = -1;
    public int row = -1;

    private DesktopItem(int type) {
        this.type = type;
    }

    public static DesktopItem app(String appKey) {
        return appAt(appKey, -1, -1);
    }

    public static DesktopItem appAt(String appKey, int col, int row) {
        DesktopItem item = new DesktopItem(TYPE_APP);
        item.appKey = appKey;
        item.col = col;
        item.row = row;
        return item;
    }

    public static DesktopItem folder(String id, String name, List<String> appKeys) {
        return folderAt(id, name, appKeys, -1, -1);
    }

    public static DesktopItem folderAt(String id, String name, List<String> appKeys, int col, int row) {
        DesktopItem item = new DesktopItem(TYPE_FOLDER);
        item.folderId = id;
        item.folderName = (name != null && !name.trim().isEmpty()) ? name.trim() : "Папка";
        item.col = col;
        item.row = row;
        if (appKeys != null) {
            for (String k : appKeys) {
                if (k != null && !k.isEmpty() && !item.folderApps.contains(k)) {
                    item.folderApps.add(k);
                }
            }
        }
        return item;
    }
}
