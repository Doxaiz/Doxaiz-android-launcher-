package com.doxaiz.launcher;
public final class V52Tests {
 private static int count;
 private static void test(boolean ok) {count++; if(!ok) throw new AssertionError("test "+count);}
 public static void main(String[] args) throws Exception {
  // Обновлено под 6.0 (AUDIT §27): в 5.5 Сакура(0) и Кибер(1) были внутренними
  // id палитр и при сохранении сворачивались в графит, из-за чего стили нельзя
  // было выбрать вообще. Теперь палитры доступны, и допустимы все четыре
  // значения; «мусор» по-прежнему приводится к графиту.
  test(Prefs.sanitizeVisualStyle(-1)==Prefs.STYLE_CLASSIC_DARK); test(Prefs.sanitizeVisualStyle(0)==Prefs.STYLE_SAKURA_JADE); test(Prefs.sanitizeVisualStyle(1)==Prefs.STYLE_CYBER_ANIME); test(Prefs.sanitizeVisualStyle(2)==Prefs.STYLE_CLASSIC_DARK); test(Prefs.sanitizeVisualStyle(3)==Prefs.STYLE_CLASSIC_LIGHT); test(Prefs.sanitizeVisualStyle(99)==Prefs.STYLE_CLASSIC_DARK);
  test(Prefs.sanitizeWallpaperMode(0)==Prefs.WALLPAPER_KURUMI_XIAOBAI); test(Prefs.sanitizeWallpaperMode(1)==Prefs.WALLPAPER_SYSTEM); test(Prefs.sanitizeWallpaperMode(2)==Prefs.WALLPAPER_KURUMI_XIAOBAI); test(Prefs.sanitizeWallpaperMode(-1)==Prefs.WALLPAPER_KURUMI_XIAOBAI);
  test(Prefs.sanitizeWallpaperDim(-1)==Prefs.WALLPAPER_DIM_MEDIUM); test(Prefs.sanitizeWallpaperDim(0)==Prefs.WALLPAPER_DIM_LIGHT); test(Prefs.sanitizeWallpaperDim(1)==Prefs.WALLPAPER_DIM_MEDIUM); test(Prefs.sanitizeWallpaperDim(2)==Prefs.WALLPAPER_DIM_STRONG); test(Prefs.sanitizeWallpaperDim(3)==Prefs.WALLPAPER_DIM_MEDIUM);
  test(AnimeBackdropView.computeSampleSize(0,100,1,1)==1); test(AnimeBackdropView.computeSampleSize(941,1672,1080,2400)==1); test(AnimeBackdropView.computeSampleSize(2000,4000,500,1000)==4); test(AnimeBackdropView.computeSampleSize(2000,1000,800,1000)==1);
  test(AnimeBackdropView.computeScrimAlphas(0)[1] < AnimeBackdropView.computeScrimAlphas(1)[1]); test(AnimeBackdropView.computeScrimAlphas(1)[1] < AnimeBackdropView.computeScrimAlphas(2)[1]);
  test(AnimeParticlesView.shouldAnimate(true,0,true,true,false)); test(AnimeParticlesView.shouldAnimate(true,1,true,true,false)); test(!AnimeParticlesView.shouldAnimate(true,2,true,true,false)); test(!AnimeParticlesView.shouldAnimate(false,0,true,true,false)); test(!AnimeParticlesView.shouldAnimate(true,0,false,true,false)); test(!AnimeParticlesView.shouldAnimate(true,0,true,false,false)); test(!AnimeParticlesView.shouldAnimate(true,0,true,true,true));
  test(BackupHelper.sanitizeTileCsv("wifi, !@# ,torch").equals("wifi,torch"));
  System.out.println("V52 TESTS PASSED: "+count);
 }
}
