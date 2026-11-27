package os.kei.hyper.home.ui.perfect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.data.InstalledIconCapability

class PerfectCustomStaticHelpersTest {
  @Test
  fun installedAppLabelsPrefersPersonalProfileAndMapsNativeActivityNames() {
    val capabilities = listOf(
      InstalledIconCapability(
        label = "Work App",
        packageName = "com.example.chat",
        userId = 10L,
        personalProfile = false,
        nightMode = IconCapabilityStatus.UNKNOWN,
        monochrome = IconCapabilityStatus.UNKNOWN,
        componentName = "com.example.chat.MainActivity",
      ),
      InstalledIconCapability(
        label = "Personal Chat",
        packageName = "com.example.chat",
        userId = 0L,
        personalProfile = true,
        nightMode = IconCapabilityStatus.DETECTED,
        monochrome = IconCapabilityStatus.NOT_DETECTED,
        componentName = "com.example.chat.MainActivity",
      ),
      InstalledIconCapability(
        label = "Foreign Alias",
        packageName = "com.example.alias",
        userId = 0L,
        personalProfile = true,
        nightMode = IconCapabilityStatus.DETECTED,
        monochrome = IconCapabilityStatus.NOT_DETECTED,
        componentName = "org.other.LaunchActivity",
      ),
    )

    val labels = installedAppLabels(capabilities)
    assertEquals("Personal Chat", labels["com.example.chat"])
    assertEquals("Personal Chat", labels["com.example.chat.MainActivity"])
    assertEquals("Foreign Alias", labels["com.example.alias"])
    assertEquals("Foreign Alias", labels["com.example.alias#org.other.LaunchActivity"])
    assertNull(labels["com.example.missing"])
  }
}
