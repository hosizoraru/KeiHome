package os.kei.hyper.home.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherVersionLabelTest {
  @Test fun installedVersionUsesTheSameLastBuildTokenAsFrameworkHero() {
    assertEquals("Home 8593", compactLauncherVersion("RELEASE-6.01.02.0000-07231848-8593"))
    assertEquals("Home 9239", compactLauncherVersion("4.39.0.9239-10161513"))
    assertEquals("Home 9239", compactLauncherVersion("4.39.0.8593-9239"))
  }

  @Test fun buildExtractionUsesDigitBoundariesRatherThanTruncatingLongNumbers() {
    assertEquals("Home 8593", compactLauncherVersion("4.39.0.8593-20261111"))
    assertEquals("Home 12345", compactLauncherVersion("release-12345"))
    assertNull(compactLauncherVersion("release-123456-20261111"))
  }

  @Test fun aShortNumericVersionIsUsefulWithoutABuildToken() {
    assertEquals("Home 4.39.0", compactLauncherVersion("4.39.0"))
    assertEquals("Home 6.1", compactLauncherVersion(" 6.1 "))
  }

  @Test fun unknownOrLongUnstructuredVersionsNeverBecomeAGiantPill() {
    for (value in listOf(null, "", "  ", "unknown", "RELEASE", "vendor-release-with-no-build".repeat(20),
      "4.39.0.12.12.12.12", "123456789")) {
      assertNull(value, compactLauncherVersion(value))
    }
  }

  @Test fun anArbitrarilyLongVersionWithABuildStillProducesAShortLabel() {
    val version = "very-long-vendor-release-".repeat(50) + "9239"
    assertEquals("Home 9239", compactLauncherVersion(version))
  }
}
