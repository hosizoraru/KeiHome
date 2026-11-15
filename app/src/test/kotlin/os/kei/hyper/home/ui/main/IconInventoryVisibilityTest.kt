package os.kei.hyper.home.ui.main

import org.junit.Assert.*
import org.junit.Test

class IconInventoryVisibilityTest {
  @Test fun backgroundDefaultIconsAreHiddenButVendorAppsWithTheirOwnArtworkRemainAvailable() {
    assertTrue(hiddenIconInventoryPackage("vendor.example.service", false, 0, false))
    assertTrue(hiddenIconInventoryPackage("com.example.service", false,
      android.R.drawable.sym_def_app_icon, false))
    assertFalse(hiddenIconInventoryPackage("vendor.example.camera", true, 123, false))
    assertFalse(hiddenIconInventoryPackage("com.example.settings", false, 123, false))
    assertFalse(hiddenIconInventoryPackage("com.example.launcher", true, 0, false))
  }

  @Test fun overlayAndDevelopmentRulesMatchTheAppsBrowser() {
    assertTrue(hiddenIconInventoryPackage("com.example.overlay", false, 123, true))
    assertTrue(hiddenIconInventoryPackage("com.example.test", true, 123, false))
    assertTrue(hiddenIconInventoryPackage("com.example.baselineprofile", false, 123, false))
  }

  @Test fun savedChoicesAndExplicitAdvancedModeStayReachable() {
    val normal = IconInventoryVisibility(retainedPackages = setOf("vendor.saved"))
    assertTrue(normal.includes("vendor.saved", true))
    assertFalse(normal.includes("vendor.other", true))
    assertTrue(normal.includes("com.example.app", false))
    assertTrue(IconInventoryVisibility(showHidden = true).includes("vendor.other", true))
  }
}
