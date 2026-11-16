package com.doxaiz.launcher;

import android.content.ComponentName;
import android.os.UserHandle;

import java.util.Locale;

public final class AppEntry {
    public final String packageName;
    public final String className;
    public final ComponentName componentName;
    public final UserHandle userHandle;
    public final long userSerial;
    public final String key;
    public final String originalLabel;
    public final String displayLabel;
    public final String labelLower;
    public final String labelTranslit;
    public final String searchKey;
    public final String translitSearchKey;
    public final boolean isSystemApp;
    public final boolean isMainProfile;
    public int category;
    public final long firstInstallTime;

    public AppEntry(
            ComponentName componentName,
            UserHandle userHandle,
            long userSerial,
            String originalLabel,
            String customLabel,
            boolean isSystemApp,
            boolean isMainProfile,
            int category,
            long firstInstallTime
    ) {
        this.componentName = componentName;
        this.packageName = componentName.getPackageName();
        this.className = componentName.getClassName();
        this.userHandle = userHandle;
        this.userSerial = userSerial;
        this.key = packageName + "/" + className + "#" + userSerial;
        this.originalLabel = originalLabel != null ? originalLabel.trim() : packageName;
        String effective = (customLabel != null && !customLabel.trim().isEmpty())
                ? customLabel.trim()
                : this.originalLabel;
        this.displayLabel = effective;
        this.labelLower = effective.toLowerCase(Locale.ROOT);
        this.labelTranslit = SearchHelper.translitRuToLat(this.labelLower);
        this.searchKey = (effective + " " + this.originalLabel + " " + packageName)
                .toLowerCase(Locale.ROOT);
        this.translitSearchKey = SearchHelper.translitRuToLat(this.searchKey);
        this.isSystemApp = isSystemApp;
        this.isMainProfile = isMainProfile;
        this.category = category;
        this.firstInstallTime = firstInstallTime;
    }

    public String getIndexChar() {
        if (displayLabel.isEmpty()) return "#";
        char ch = Character.toUpperCase(displayLabel.charAt(0));
        if (Character.isLetter(ch)) {
            return (ch == 'Ё') ? "Е" : String.valueOf(ch);
        }
        return "#";
    }
}
