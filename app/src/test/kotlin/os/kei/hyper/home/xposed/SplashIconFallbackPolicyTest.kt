package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class SplashIconFallbackPolicyTest {
  private val eligible = SplashIconFallbackPolicy.Input(true,
    1, 6, false, false, false, false, true, true)

  @Test fun onlyOptedInPlainWindowsCanBeFilled() {
    assertEquals("fill", SplashIconFallbackPolicy.reason(eligible))
    assertEquals("fill", SplashIconFallbackPolicy.reason(eligible.copy(finalType = 3)))
    assertEquals("disabled", SplashIconFallbackPolicy.reason(eligible.copy(enabled = false)))
  }

  @Test fun preservesSnapshotsAuthoredArtAndSpecialWindows() {
    val excluded = listOf(
      eligible.copy(hasSnapshot = true) to "snapshot",
      eligible.copy(hasExplicitIcon = true) to "explicit-icon",
      eligible.copy(hasBranding = true) to "branding",
      eligible.copy(protectedWindow = true) to "protected-window",
      eligible.copy(plainBackground = false) to "artwork-or-transparency",
      eligible.copy(plainOverlay = false) to "artwork-or-transparency",
    ) + listOf(0, 2, 4, 5, 7).map { eligible.copy(initialType = it) to "initial-type" } +
      listOf(0, 1, 2, 4, 5, 7).map {
        eligible.copy(finalType = it) to "existing-icon-or-special-type"
      }
    excluded.forEach { (input, reason) ->
      assertEquals(input.toString(), reason, SplashIconFallbackPolicy.reason(input))
    }
  }

  @Test fun fillsPreviewDisabledOnlyWhenShellAlreadyBuiltAnEligibleColdSplash() {
    val cold = eligible.copy(previewDisabled = true, startingFlags = 0x9, neutralBacking = true)
    assertEquals("fill", SplashIconFallbackPolicy.reason(cold))
    assertEquals("fill", SplashIconFallbackPolicy.reason(cold.copy(startingFlags = 0x8b)))
    listOf(
      cold.copy(startingFlags = null),
      cold.copy(startingFlags = 0x2), // Task switch without a new task.
      cold.copy(startingFlags = 0x5), // Process running.
      cold.copy(startingFlags = 0x11), // Activity created.
      cold.copy(startingFlags = 0x21), // Solid splash requested.
      cold.copy(startingFlags = 0x41), // Activity drawn.
      cold.copy(startingFlags = 0x101), // Windowless.
      cold.copy(startingFlags = Int.MIN_VALUE or 1), // Legacy splash.
      cold.copy(finalType = 3),
      cold.copy(initialType = 6),
      cold.copy(neutralBacking = false),
    ).forEach { input ->
      assertEquals(input.toString(), "disabled-preview", SplashIconFallbackPolicy.reason(input))
    }
    assertEquals("disabled", SplashIconFallbackPolicy.reason(cold.copy(enabled = false)))
    assertEquals("protected-window", SplashIconFallbackPolicy.reason(cold.copy(protectedWindow = true)))
    assertEquals("snapshot", SplashIconFallbackPolicy.reason(cold.copy(hasSnapshot = true)))
    assertEquals("branding", SplashIconFallbackPolicy.reason(cold.copy(hasBranding = true)))
    assertEquals("explicit-icon", SplashIconFallbackPolicy.reason(cold.copy(hasExplicitIcon = true)))
    assertEquals("artwork-or-transparency", SplashIconFallbackPolicy.reason(cold.copy(plainOverlay = false)))
  }
}
