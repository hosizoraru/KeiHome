package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test

class SplashOwnPaletteTest {
  private val input = SplashIconFallbackPolicy.Input(false, 1, 1, false, true,
    false, false, true, true, ownPalette = true)

  @Test fun onlyOurOwnPackageWithAnEnabledPaletteCanReplaceItsDeclaredIcon() {
    assertTrue(SplashIconFallbackPolicy.ownPalette("os.kei.hyper.home", "os.kei.hyper.home", 1L shl 48))
    assertFalse(SplashIconFallbackPolicy.ownPalette("com.other", "os.kei.hyper.home", 1L shl 48))
    assertFalse(SplashIconFallbackPolicy.ownPalette("os.kei.hyper.home", "os.kei.hyper.home", 0L))
    assertEquals("fill", SplashIconFallbackPolicy.reason(input))
    assertEquals("disabled", SplashIconFallbackPolicy.reason(input.copy(ownPalette = false)))
  }

  @Test fun ourOwnPaletteStillProtectsSnapshotsBrandingAndSpecialWindows() {
    assertEquals("snapshot", SplashIconFallbackPolicy.reason(input.copy(hasSnapshot = true)))
    assertEquals("branding", SplashIconFallbackPolicy.reason(input.copy(hasBranding = true)))
    assertEquals("protected-window", SplashIconFallbackPolicy.reason(input.copy(protectedWindow = true)))
    assertEquals("artwork-or-transparency", SplashIconFallbackPolicy.reason(input.copy(plainBackground = false)))
    assertEquals("existing-icon-or-special-type", SplashIconFallbackPolicy.reason(input.copy(finalType = 2)))
  }
}
