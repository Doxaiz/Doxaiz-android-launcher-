package com.doxaiz.launcher;
public final class SimpleThemeTest {
 static int n; static void check(boolean b){ if(!b)throw new AssertionError("test "+n); n++;}
 public static void main(String[] args) {
  check(Prefs.sanitizeVisualStyle(Prefs.STYLE_CLASSIC_DARK)==Prefs.STYLE_CLASSIC_DARK);
  check(Prefs.sanitizeVisualStyle(Prefs.STYLE_CLASSIC_LIGHT)==Prefs.STYLE_CLASSIC_LIGHT);
  check(Prefs.sanitizeVisualStyle(Prefs.STYLE_SAKURA_JADE)==Prefs.STYLE_CLASSIC_DARK);
  check(Prefs.sanitizeVisualStyle(Prefs.STYLE_CYBER_ANIME)==Prefs.STYLE_CLASSIC_DARK);
  check(Prefs.sanitizeVisualStyle(-1)==Prefs.STYLE_CLASSIC_DARK);
  check(Prefs.sanitizeVisualStyle(999)==Prefs.STYLE_CLASSIC_DARK);
  check(Prefs.sanitizeWallpaperMode(0)==Prefs.WALLPAPER_KURUMI_XIAOBAI);
  check(Prefs.sanitizeWallpaperMode(1)==Prefs.WALLPAPER_SYSTEM);
  check(Prefs.sanitizeWallpaperMode(99)==Prefs.WALLPAPER_KURUMI_XIAOBAI);
  check(AnimeBackdropView.computeSampleSize(941,1672,1080,2400)==1);
  check(AnimeBackdropView.computeSampleSize(2000,4000,500,1000)==4);
  check(AnimeBackdropView.computeScrimAlphas(0)[1]<AnimeBackdropView.computeScrimAlphas(1)[1]);
  check(AnimeBackdropView.computeScrimAlphas(1)[1]<AnimeBackdropView.computeScrimAlphas(2)[1]);
  check(BackupHelper.sanitizeTileCsv("wifi, !@# ,torch").equals("wifi,torch"));
  System.out.println("SIMPLE_THEME_TESTS="+n);
 }
}
