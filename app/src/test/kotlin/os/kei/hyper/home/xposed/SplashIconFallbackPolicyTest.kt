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
}
