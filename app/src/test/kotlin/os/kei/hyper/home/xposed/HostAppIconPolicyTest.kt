package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAppIconPolicyTest {
  @Test fun ownerPackageUriPreservesExactIdentity() {
    assertEquals("com.miui.voiceassistProxy", sidebarIconPackage("pkg_icon://com.miui.voiceassistProxy"))
    assertEquals("os.kei.hyper.home", sidebarIconPackage("pkg_icon://os.kei.hyper.home"))
  }

  @Test fun cloneAndManagedSchemesStayNative() {
    for (uri in listOf("pkg_icon_xspace://com.android.settings",
      "pkg_icon_managed_profile://com.android.settings", "pkg_icon://10@com.android.settings",
      "pkg_icon://com.android.settings/10", "pkg_icon://com.android.settings?user=10"))
      assertNull(uri, sidebarIconPackage(uri))
  }

  @Test fun nonPackageAssetsAreNotEligible() {
    for (uri in listOf(null, "", "file:///sdcard/photo.png", "https://a.b/icon.png",
      "pkg_icon://.android.settings", "pkg_icon://com..settings", "pkg_icon://../etc/passwd",
      "pkg_icon://com.android.settings#icon", "pkg_icon://com.android.settings%2F1"))
      assertNull(uri, sidebarIconPackage(uri))
  }

  @Test fun genericRuntimeIsRestrictedToApprovedHostProcesses() {
    assertTrue(HostAppIconRuntime.supports("com.miui.securitycenter"))
    assertTrue(HostAppIconRuntime.supports("com.miui.securitycenter:ui"))
    assertTrue(HostAppIconRuntime.supports("com.android.settings"))
    assertFalse(HostAppIconRuntime.supports("com.miui.securitycenter:remote"))
    assertFalse(HostAppIconRuntime.supports("com.android.systemui"))
    assertFalse(HostAppIconRuntime.supports("org.example.gallery"))
  }
}
