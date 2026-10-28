package os.kei.hyper.home.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.data.InstalledIconCapability

class IconCapabilityFilterTest {
  private fun icon(night: IconCapabilityStatus, mono: IconCapabilityStatus) =
    InstalledIconCapability("App", "example.app", 0, true, night, mono)

  @Test
  fun defaultFilterShowsEitherDetectedFeatureButNotUnknown() {
    val yes = IconCapabilityStatus.DETECTED
    val no = IconCapabilityStatus.NOT_DETECTED
    val unknown = IconCapabilityStatus.UNKNOWN
    assertTrue(icon(yes, no).matchesFilter(0))
    assertTrue(icon(no, yes).matchesFilter(0))
    assertFalse(icon(no, no).matchesFilter(0))
    assertFalse(icon(unknown, no).matchesFilter(0))
  }

  @Test
  fun individualAndUnknownFiltersPreserveThreeStates() {
    val yes = IconCapabilityStatus.DETECTED
    val no = IconCapabilityStatus.NOT_DETECTED
    val unknown = IconCapabilityStatus.UNKNOWN
    assertTrue(icon(yes, unknown).matchesFilter(1))
    assertFalse(icon(no, yes).matchesFilter(1))
    assertTrue(icon(no, yes).matchesFilter(2))
    assertTrue(icon(yes, unknown).matchesFilter(3))
    assertTrue(icon(no, no).matchesFilter(4))
  }

  @Test
  fun queryMatchesLabelAndPackageNameCaseInsensitively() {
    val capability = icon(
      night = IconCapabilityStatus.DETECTED,
      mono = IconCapabilityStatus.NOT_DETECTED,
    )

    assertTrue(capability.matchesQuery("app"))
    assertTrue(capability.matchesQuery("EXAMPLE.APP"))
    assertTrue(capability.matchesQuery("  example  "))
    assertTrue(capability.matchesQuery(""))
    assertFalse(capability.matchesQuery("calendar"))
  }
}
