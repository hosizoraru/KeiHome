package os.kei.hyper.home.ui.main

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackEntry

class CustomIconScopesTest {
  private val packages = mapOf("com.example.system" to true, "com.android.user" to false)
  private fun app(pkg: String, system: Boolean, activity: String) =
    PreservedIconApp(pkg, pkg, 1, 0, "", 0, activeLauncherClass = activity, systemApp = system)

  @Test fun unknownOwnersRemainInAllOnly() {
    assertTrue(customIconMatchesOrigin(null, 0))
    assertFalse(customIconMatchesOrigin(null, 1))
    assertFalse(customIconMatchesOrigin(null, 2))
  }

  @Test fun explicitFlagsDriveUserAndSystemScopes() {
    assertTrue(customIconMatchesOrigin(false, 1))
    assertFalse(customIconMatchesOrigin(true, 1))
    assertTrue(customIconMatchesOrigin(true, 2))
    assertFalse(customIconMatchesOrigin(false, 2))
  }

  @Test fun packagePrefixesDoNotOverrideInstalledMetadata() {
    val origins = CustomIconOrigins(packages, emptyList())
    assertEquals(false, origins.system(IconPackEntry("user", component = IconPackComponent("com.android.user"))))
    assertEquals(true, origins.system(IconPackEntry("system", nativeName = "com.example.system#Launcher")))
    assertNull(origins.system(IconPackEntry("unknown", nativeName = "com.android.missing")))
  }

  @Test fun hiddenPackagesCanBeClassifiedWithoutVisibleAppRows() {
    assertEquals(true, CustomIconOrigins(packages, emptyList())
      .system(IconPackEntry("hidden", nativeName = "com.example.system")))
  }

  @Test fun declaredLauncherAliasesAreClassifiedButUnrelatedNamesAreNot() {
    val origins = CustomIconOrigins(packages, listOf(app("com.example.system", true, "vendor.launcher.Real")))
    assertEquals(true, origins.system(IconPackEntry("alias", nativeName = "vendor.launcher.Real")))
    assertNull(origins.system(IconPackEntry("alias", nativeName = "vendor.launcher.Other")))
  }

  @Test fun ambiguousActivityNamesDoNotChooseAnArbitraryOwner() {
    val origins = CustomIconOrigins(packages, listOf(app("com.example.system", true, "shared.Main"),
      app("com.android.user", false, "shared.Main")))
    assertNull(origins.system(IconPackEntry("alias", nativeName = "shared.Main")))
  }
}
