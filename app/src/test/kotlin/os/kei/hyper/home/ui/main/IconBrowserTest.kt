package os.kei.hyper.home.ui.main

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.*
import os.kei.hyper.home.iconpack.*
import java.io.File

class IconBrowserTest {
  @Test fun selectedSystemFallbackIsPreviewedButDoesNotClaimPackRedrawOrApkMonochrome() {
    val source = source().copy(systemFallback = SystemIconFallbackManifest(SystemIconFallback.DEFAULT,
      setOf("os.demo.covered")))
    val apps = listOf(app("os.demo.covered", system = true))
    val row = browserRows(apps, source, IconBrowserOptions(), "").single()
    assertNotNull(row.entry)
    assertFalse(row.covered)
    assertEquals(SystemIconFallback.DEFAULT, row.systemFallback)
    assertEquals(IconCapabilityStatus.NOT_DETECTED, row.app.monochrome)
    assertTrue(browserRows(apps, source, IconBrowserOptions(coverage = 1), "").isEmpty())
    assertEquals(1, browserRows(apps, source, IconBrowserOptions(scope = 2), "").count { it.installed })
  }
  private fun app(pkg: String, system: Boolean = false, launcher: Boolean = true, updated: Long = 0) =
    InstalledIconCapability(pkg, pkg, 0, true, IconCapabilityStatus.DETECTED,
      IconCapabilityStatus.DETECTED, systemApp = system, hasLauncher = launcher, updatedAt = updated)
  private fun source(): IconBrowserSource {
    fun entry(name: String) = IconPackEntry(name, nativeName = name)
    val entries = listOf(entry("os.demo.covered"), entry("os.demo.dynamic"), entry("os.demo.absent"))
    val record = StoredIconPack("test", "test", 0, IconPackFormat.MIUI_ICONS, File("unused"), null, 0, null)
    val pack = IconPackDescriptor("test", "test", IconPackFormat.MIUI_ICONS, IconPackSource.Imported(record), entries)
    return IconBrowserSource("test", pack, entries.associateBy { it.nativeName!! }, emptySet(), setOf("os.demo.dynamic"))
  }
  @Test fun coveredArtworkDoesNotInheritApkMonochromeSupport() {
    val rows = browserRows(listOf(app("os.demo.covered"), app("os.demo.original")), source(), IconBrowserOptions(), "")
    assertEquals(IconCapabilityStatus.NOT_DETECTED, rows.first { it.covered }.app.monochrome)
    assertEquals(IconCapabilityStatus.DETECTED, rows.first { !it.covered }.app.monochrome)
  }
  @Test fun nativeDayNightPairIsCountedEvenWhenBothFallbackEntriesPointToTheDayPng() {
    val source = source().copy(nightNames = setOf("os.demo.covered"))
    val row = browserRows(listOf(app("os.demo.covered")), source, IconBrowserOptions(support = 1), "").single()
    assertEquals(IconCapabilityStatus.DETECTED, row.app.nightMode)
  }
  @Test fun packagePrefixNeverEstablishesCoverage() {
    assertFalse(browserRows(listOf(app("os.demo.covered.extra")), source(), IconBrowserOptions(), "").single().covered)
  }
  @Test fun allPackIconsIncludesUninstalledResourcesButNotUncoveredApps() {
    val rows = browserRows(listOf(app("os.demo.covered"), app("os.demo.other")), source(), IconBrowserOptions(scope = 2), "")
    assertEquals(3, rows.size)
    assertEquals(2, rows.count { !it.installed })
    assertTrue(rows.all { it.covered })
  }
  @Test fun systemAndLauncherFiltersAreIndependent() {
    val apps = listOf(app("os.sys.hidden", true, false), app("os.sys.launcher", true), app("os.user.app"))
    assertEquals(2, browserRows(apps, null, IconBrowserOptions(origin = 2), "").size)
    assertEquals("os.sys.launcher", browserRows(apps, null, IconBrowserOptions(scope = 1, origin = 2), "").single().app.packageName)
  }
  @Test fun dynamicResourcesRemainUnknownAndSearchUsesPackageNames() {
    val row = browserRows(listOf(app("os.demo.dynamic")), source(), IconBrowserOptions(support = 4), "DYNAMIC").single()
    assertEquals(IconCapabilityStatus.UNKNOWN, row.app.monochrome)
    assertTrue(row.dynamic)
  }
  @Test fun overlaysAreHiddenByDefaultWithoutGuessingFromThePackageName() {
    val overlay = app("os.demo.resource").copy(overlayApp = true)
    val normal = app("os.demo.overlay.editor")
    assertEquals(listOf(normal), browserRows(listOf(overlay, normal), null, IconBrowserOptions(), "").map { it.app })
    assertEquals(2, browserRows(listOf(overlay, normal), null, IconBrowserOptions(showOverlays = true), "").size)
  }
  @Test fun testAndBaselinePackagesAreHiddenByDefaultAndCanBeShown() {
    val apps = listOf(app("com.example.test"), app("com.example.test.runner"),
      app("com.example.baselineprofile"), app("com.example.release"))
    assertEquals(listOf("com.example.release"),
      browserRows(apps, null, IconBrowserOptions(), "").map { it.app.packageName })
    assertEquals(4, browserRows(apps, null, IconBrowserOptions(showDevelopmentPackages = true), "").size)
    assertFalse(isDevelopmentPackage("com.example.baseline"))
  }
  @Test fun recentlyUpdatedSortRetainsUserProfileIdentity() {
    val a = app("os.old.app", updated = 1)
    val b = app("os.new.app", updated = 10).copy(userId = 10, personalProfile = false)
    val rows = browserRows(listOf(a, b), null, IconBrowserOptions(sort = 2), "")
    assertEquals(b, rows.first().app)
    assertNotEquals(rows.first().key, rows.last().key)
  }
  @Test fun reverseAppliesToEachSelectedRule() {
    val apps = listOf(
      app("os.apple", updated = 1).copy(label = "Zulu"),
      app("os.banana", updated = 3).copy(label = "Alpha"),
      app("os.demo.covered", updated = 2).copy(label = "Bravo"),
    )
    val expected = listOf(
      listOf("os.apple", "os.demo.covered", "os.banana"),
      listOf("os.demo.covered", "os.banana", "os.apple"),
      listOf("os.apple", "os.demo.covered", "os.banana"),
      listOf("os.apple", "os.banana", "os.demo.covered"),
    )
    expected.forEachIndexed { sort, packages ->
      assertEquals("Rule $sort", packages,
        browserRows(apps, source(), IconBrowserOptions(sort = sort, reverse = true), "").map { it.app.packageName })
    }
  }
}
