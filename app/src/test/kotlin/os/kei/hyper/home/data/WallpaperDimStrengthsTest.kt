package os.kei.hyper.home.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperDimStrengthsTest {
  @Test fun existingInstallsKeepTheOriginalStrength() {
    assertEquals(0.25f, WallpaperDimStrengths().effectiveAmount(false, 0f), 0f)
    assertEquals(0.25f, WallpaperDimStrengths().effectiveAmount(true, 0f), 0f)
  }

  @Test fun eachSurfaceKeepsTheStrongerOfTheUserAndSystemRequests() {
    val strengths = WallpaperDimStrengths(home = 18, lock = 55)
    assertEquals(0.18f, strengths.effectiveAmount(false, 0.12f), 0f)
    assertEquals(0.40f, strengths.effectiveAmount(false, 0.40f), 0f)
    assertEquals(0.55f, strengths.effectiveAmount(true, 0.40f), 0f)
    assertEquals(0.65f, strengths.effectiveAmount(true, 0.65f), 0f)
    assertEquals(0.40f, WallpaperDimStrengths.Off.effectiveAmount(true, 0.40f), 0f)
  }

  @Test fun colorOsUsesOneStrengthAndRetainsHyperOsValues() {
    val custom = WallpaperDimStrengths(home = 18, lock = 55)
    val colorOs = custom.copy(style = WallpaperDimStyle.COLOR_OS)
    assertEquals(0.24f, colorOs.effectiveAmount(false, 0f), 0f)
    assertEquals(0.24f, colorOs.effectiveAmount(true, 0f), 0f)
    assertEquals(0.4f, colorOs.effectiveAmount(true, 0.4f), 0f)
    assertEquals(custom, colorOs.copy(style = WallpaperDimStyle.HYPER_OS))
  }

  @Test fun overlayDimAlphaPreservesCombinedBrightnessOverPlatformBaseline() {
    assertEquals(0f, wallpaperOverlayDimAlpha(0f, 0f), 1e-6f)
    assertEquals(0.25f, wallpaperOverlayDimAlpha(0.25f, 0f), 1e-6f)
    assertEquals(0f, wallpaperOverlayDimAlpha(0.40f, 0.40f), 1e-6f)
    assertEquals(0f, wallpaperOverlayDimAlpha(0.25f, 0.40f), 1e-6f)
    // Platform baseline 40% leaves 60% brightness; target 55% leaves 45%, so overlay needs 1 - 45/60 = 0.25.
    assertEquals(0.25f, wallpaperOverlayDimAlpha(0.55f, 0.40f), 1e-6f)
    assertEquals(1f, wallpaperOverlayDimAlpha(1f, 0f), 1e-6f)
    assertEquals(0f, wallpaperOverlayDimAlpha(1f, 1f), 1e-6f)
  }
}
