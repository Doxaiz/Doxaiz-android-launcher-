package com.doxaiz.launcher;

/** Pure text formatting shared by folder-tile construction and in-place refresh. */
final class FolderTileLabels {

    private FolderTileLabels() {
    }

    static String displayName(String folderName) {
        return (folderName != null) ? folderName : "Папка";
    }

    static String contentDescription(String folderName, int appCount) {
        String name = displayName(folderName);
        return name + (appCount > 0 ? ", приложений: " + appCount : "");
    }
}
