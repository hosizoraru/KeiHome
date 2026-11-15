package os.kei.hyper.home.iconpack.perfect

import org.junit.Assert.assertEquals
import org.junit.Test

class PerfectIconStylesTest {
  @Test fun downloadedStylesLeadWithoutChangingPriorityInsideEitherGroup() {
    assertEquals(listOf("HyperOS4", "default", "iOS", "HyperOSClassic", "Hyper", "Rounded"),
      PerfectIconStyles.ordered(listOf("Rounded", "iOS", "Hyper", "", "HyperOSClassic", "HyperOS4"),
        listOf("iOS", "", "HyperOS4", "Unavailable")))
  }
  @Test fun preferredStylesPrecedeDefaultAndRemainingStyles() {
    assertEquals(listOf("HyperOS4", "HyperOSClassic", "HyperOS3", "Hyper", "default", "Aquamarine", "Rounded"),
      PerfectIconStyles.ordered(listOf("Rounded", "", "HyperOS3", "Aquamarine", "HyperOS4", "Hyper",
        "default", "HyperOSClassic", "HyperOS4")))
  }

  @Test fun orderingDoesNotInventUnavailableStyles() {
    assertEquals(listOf("default", "Rounded"), PerfectIconStyles.ordered(listOf("Rounded", "default")))
    assertEquals("HyperOS4", PerfectIconStyles.initialOptions.style)
  }
}
