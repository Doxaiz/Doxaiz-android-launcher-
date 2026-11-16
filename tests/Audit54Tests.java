package com.doxaiz.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure JVM checks; these are not Android/device or integration tests. */
public final class Audit54Tests {
    private static int passed;
    private static void check(boolean value, String what) {
        if (!value) throw new AssertionError(what);
        passed++;
    }
    private static JSONObject desk(JSONArray page, JSONArray dock) throws Exception {
        JSONObject d = new JSONObject();
        d.put("pages", new JSONArray().put(page));
        if (dock != null) d.put("dock", dock);
        return d;
    }
    public static void main(String[] args) throws Exception {
        check(!BackupHelper.validateDesktopJson(null), "null desktop");
        check(!BackupHelper.validateDesktopJson(new JSONObject()), "missing pages");
        check(!BackupHelper.validateDesktopJson(new JSONObject().put("pages", new JSONArray())), "no pages");
        check(BackupHelper.validateDesktopJson(desk(new JSONArray(), new JSONArray())), "empty desktop round-trip");
        check(!BackupHelper.validateDesktopJson(desk(new JSONArray(), null)), "arbitrary empty data");
        check(BackupHelper.validateDesktopJson(desk(new JSONArray().put(new JSONObject().put("key", "a/b#0")), null)), "legacy app");
        check(BackupHelper.validateDesktopJson(desk(new JSONArray().put(new JSONObject().put("type", "folder").put("apps", new JSONArray())), null)), "empty folder");
        check(!BackupHelper.validateDesktopJson(desk(new JSONArray().put(new JSONObject().put("type", "folder")), new JSONArray())), "folder missing apps");
        check(!BackupHelper.validateDesktopJson(desk(new JSONArray().put(new JSONObject().put("type", "other").put("key", "k")), new JSONArray())), "unknown type");
        check(!BackupHelper.validateDesktopJson(desk(new JSONArray().put(new JSONObject().put("type", "app")), new JSONArray())), "missing app key");
        check(!BackupHelper.validateDesktopJson(desk(new JSONArray().put(new JSONObject().put("key", 123)), new JSONArray())), "non-string app key");
        check(!BackupHelper.validateDesktopJson(desk(new JSONArray(), new JSONArray().put(23))), "non-string dock key");
        check(!BackupHelper.validateDesktopJson(desk(new JSONArray().put(new JSONObject().put("type", "folder").put("apps", new JSONArray().put(3))), new JSONArray())), "non-string folder key");
        JSONArray many = new JSONArray();
        for (int i=0;i<4097;i++) many.put("a/b#0");
        check(!BackupHelper.validateDesktopJson(desk(new JSONArray().put(new JSONObject().put("type", "folder").put("apps", many)), new JSONArray())), "folder bomb");
        check(!BackupHelper.validateDesktopJson(desk(new JSONArray().put(new JSONObject().put("key", new String(new char[1025]).replace('\0','a'))), new JSONArray())), "oversized key");
        check(BackupHelper.resolveImportedProtectHidden(true,false,false), "privacy unverified");
        check(!BackupHelper.resolveImportedProtectHidden(true,false,true), "privacy verified");
        check(BackupHelper.resolveImportedProtectHidden(false,true,false), "privacy on");
        check(!BackupHelper.resolveImportedProtectHidden(false,false,false), "privacy off");
        check("wifi,torch".equals(BackupHelper.sanitizeTileCsv("wifi, !@# ,torch")), "tile csv");
        check("".equals(BackupHelper.sanitizeTileCsv(null)), "null csv");
        check(IconDiskCache.isSafeIconDimensions(192,192), "normal icon");
        check(IconDiskCache.isSafeIconDimensions(1024,1024), "max icon");
        check(!IconDiskCache.isSafeIconDimensions(0,1), "invalid width");
        check(!IconDiskCache.isSafeIconDimensions(1,1025), "oversized icon");
        check(!IconDiskCache.isSafeIconDimensions(Integer.MAX_VALUE,Integer.MAX_VALUE), "overflow icon");
        check(IconDiskCache.isStaleVersionFile("foo_1.img","foo_","foo_2.img"), "old file");
        check(!IconDiskCache.isStaleVersionFile("foo_2.img","foo_","foo_1.img"), "preserve newer file");
        check(!IconDiskCache.isStaleVersionFile("foo_1.img","foo_","foo_1.img"), "preserve same file");
        check(!IconDiskCache.isStaleVersionFile("foo_bad.img","foo_","foo_2.img"), "bad version");
        check(!IconDiskCache.isStaleVersionFile("foobar_1.img","foo_","foo_2.img"), "other key");
        check(DoxaizLockService.hasExplicitRecentsClass("com.android.systemui.recents.RecentsActivity"), "recents class");
        check(DoxaizLockService.hasExplicitRecentsClass("com.vendor.OverviewActivity"), "overview class");
        check(!DoxaizLockService.hasExplicitRecentsClass("android.widget.FrameLayout"), "ambiguous root is not proof");
        check(!DoxaizLockService.hasExplicitRecentsClass(null), "null root is not proof");
        check(Prefs.sanitizeVisualStyle(Prefs.STYLE_CLASSIC_LIGHT)==Prefs.STYLE_CLASSIC_LIGHT, "light theme");
        check(Prefs.sanitizeVisualStyle(Prefs.STYLE_CLASSIC_DARK)==Prefs.STYLE_CLASSIC_DARK, "dark theme");
        check(Prefs.sanitizeVisualStyle(Prefs.STYLE_SAKURA_JADE)==Prefs.STYLE_CLASSIC_DARK, "no sakura theme");
        check(Prefs.sanitizeWallpaperMode(0)==Prefs.WALLPAPER_KURUMI_XIAOBAI, "bundled wallpaper");
        check(Prefs.sanitizeWallpaperMode(1)==Prefs.WALLPAPER_SYSTEM, "system wallpaper");
        check(AnimeBackdropView.computeSampleSize(2000,4000,500,1000)==4, "sample size");
        check(AnimeBackdropView.computeSampleSize(0,100,1,1)==1, "invalid size");
        check(AnimeBackdropView.computeScrimAlphas(0)[1] < AnimeBackdropView.computeScrimAlphas(2)[1], "scrim order");
        check(Prefs.isNewUnopenedApp(false, 1000L, false, 1001L), "new app");
        check(!Prefs.isNewUnopenedApp(true, 1000L, false, 1001L), "system app");
        check(!Prefs.isNewUnopenedApp(false, 1000L, true, 1001L), "opened app");
        check(!Prefs.isNewUnopenedApp(false, 1000L, false, 999L), "future app");
        check(QuickShadeHelper.computeClampedSpeedBps(1000,2000,1000)==1000, "speed");
        check(QuickShadeHelper.computeClampedSpeedBps(2000,1000,1000)==0, "negative speed");
        check(QuickShadeHelper.smoothSpeedBps(0,100000)==72000, "smooth speed");
        check("4.18 В".equals(BatteryInfoHelper.formatVoltage(4180)), "voltage");
        check("34.5°C".equals(BatteryInfoHelper.formatTemp(34.5f)), "temperature");
        System.out.println("AUDIT54_TESTS_PASSED="+passed);
    }
}
