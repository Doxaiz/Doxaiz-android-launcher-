package com.doxaiz.launcher;
public final class V52Tests {
 private static int count;
 private static void test(boolean ok) {count++; if(!ok) throw new AssertionError("test "+count);}
 public static void main(String[] args) throws Exception {
  test(Prefs.sanitizeVisualStyle(-1)==0); test(Prefs.sanitizeVisualStyle(0)==0); test(Prefs.sanitizeVisualStyle(1)==1); test(Prefs.sanitizeVisualStyle(2)==2); test(Prefs.sanitizeVisualStyle(3)==0);
  test(Prefs.sanitizeWallpaperMode(0)==0); test(Prefs.sanitizeWallpaperMode(1)==1); test(Prefs.sanitizeWallpaperMode(2)==0); test(Prefs.sanitizeWallpaperMode(-1)==0);
  test(Prefs.sanitizeWallpaperDim(-1)==1); test(Prefs.sanitizeWallpaperDim(0)==0); test(Prefs.sanitizeWallpaperDim(1)==1); test(Prefs.sanitizeWallpaperDim(2)==2); test(Prefs.sanitizeWallpaperDim(3)==1);
  test(AnimeBackdropView.computeSampleSize(0,100,1,1)==1); test(AnimeBackdropView.computeSampleSize(941,1672,1080,2400)==1); test(AnimeBackdropView.computeSampleSize(2000,4000,500,1000)==4); test(AnimeBackdropView.computeSampleSize(2000,1000,800,1000)==1);
  test(AnimeBackdropView.computeScrimAlphas(0)[1] < AnimeBackdropView.computeScrimAlphas(1)[1]); test(AnimeBackdropView.computeScrimAlphas(1)[1] < AnimeBackdropView.computeScrimAlphas(2)[1]);
  test(AnimeParticlesView.shouldAnimate(true,0,true,true,false)); test(AnimeParticlesView.shouldAnimate(true,1,true,true,false)); test(!AnimeParticlesView.shouldAnimate(true,2,true,true,false)); test(!AnimeParticlesView.shouldAnimate(false,0,true,true,false)); test(!AnimeParticlesView.shouldAnimate(true,0,false,true,false)); test(!AnimeParticlesView.shouldAnimate(true,0,true,false,false)); test(!AnimeParticlesView.shouldAnimate(true,0,true,true,true));
  test(BackupHelper.sanitizeTileCsv("wifi, !@# ,torch").equals("wifi,torch"));
  System.out.println("V52 TESTS PASSED: "+count);
 }
}
