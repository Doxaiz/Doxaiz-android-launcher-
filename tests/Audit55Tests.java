package com.doxaiz.launcher;
/** Pure calculations only; Android UI and hardware are not exercised. */
public final class Audit55Tests {
  private static int n;
  private static void check(boolean v, String label) { if (!v) throw new AssertionError(label); n++; }
  public static void main(String[] args) {
    check(QuickShadeHelper.popupWidthForWindow(1080, 680)==680,"width max");
    check(QuickShadeHelper.popupWidthForWindow(200, 680)==176,"split-screen width");
    check(QuickShadeHelper.popupHeightForWindow(200)==164,"landscape height");
    check(QuickShadeHelper.popupWidthForWindow(0,0)==1,"zero width");
    check(QuickShadeHelper.popupHeightForWindow(0)==1,"zero height");
    for(int size=1; size<=3000; size++) {
      check(QuickShadeHelper.popupWidthForWindow(size,680)<=size,"width bound");
      check(QuickShadeHelper.popupHeightForWindow(size)<=size,"height bound");
    }
    check(SoundPanelHelper.volumeToPercent(2,2,10)==0,"minimum volume");
    check(SoundPanelHelper.volumeToPercent(6,2,10)==50,"middle volume");
    check(SoundPanelHelper.volumeToPercent(10,2,10)==100,"maximum volume");
    check(SoundPanelHelper.volumeToPercent(100,2,10)==100,"clamped volume");
    check(SoundPanelHelper.ratioToVolume(-1f,2,10)==2,"clamped ratio low");
    check(SoundPanelHelper.ratioToVolume(1.5f,2,10)==10,"clamped ratio high");
    check(SoundPanelHelper.ratioToVolume(.5f,2,10)==6,"middle ratio");
    System.out.println("AUDIT55_CALCULATION_TESTS="+n);
  }
}
