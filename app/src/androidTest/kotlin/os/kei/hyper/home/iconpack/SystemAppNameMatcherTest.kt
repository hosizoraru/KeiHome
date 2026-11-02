package os.kei.hyper.home.iconpack

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.zip.ZipFile
import os.kei.hyper.home.data.SystemIconFallback
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemAppNameMatcherTest {
  @Test fun onlySystemAppsUseCrossOemArtworkAfterExactMappingFails() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val manager = IconPackEntry("manager", component = IconPackComponent("com.oplus.phonemanager"),
      light = IconPackArtwork.Resource(IconDrawableName("phone_manager")))
    val themes = IconPackEntry("themes", component = IconPackComponent("com.heytap.themestore"),
      light = IconPackArtwork.Resource(IconDrawableName("theme_store")))
    val store = IconPackEntry("store", component = IconPackComponent("com.oppo.market"),
      light = IconPackArtwork.Resource(IconDrawableName("app_market")))
    val enabled = SystemAppNameMatcher(context, listOf(manager, themes, store), true)
    assertEquals(manager, enabled.match(IconPackComponent("com.miui.securitycenter")))
    assertEquals(themes, enabled.match(IconPackComponent("com.android.thememanager")))
    assertEquals(store, enabled.match(IconPackComponent("com.xiaomi.market")))
    assertNull(enabled.match(IconPackComponent(context.packageName)))
    assertNull(SystemAppNameMatcher(context, listOf(manager, themes, store), false)
      .match(IconPackComponent("com.miui.securitycenter")))
  }

  @Test fun installedArcticonsExposesSystemNameCandidates() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val info = context.packageManager.getPackageInfo("com.donnnno.arcticons.daynight", 0)
    val pack = requireNotNull(AndroidIconPackResources(context).index(info))
    val matcher = SystemAppNameMatcher(context, pack.entries, true)
    assertNotNull(matcher.match(IconPackComponent("com.miui.securitycenter")))
    assertNotNull(matcher.match(IconPackComponent("com.android.thememanager")))
    assertNotNull(matcher.match(IconPackComponent("com.xiaomi.market")))
    assertNull(matcher.match(IconPackComponent(context.packageName)))
  }

  @Test fun realPackExportPrefersNameMatchBeforeSelectedSystemFallback() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val info = context.packageManager.getPackageInfo("com.donnnno.arcticons.daynight", 0)
    val index = requireNotNull(AndroidIconPackResources(context).index(info))
    val target = IconPackComponent("com.android.thememanager")
    val pack = IconPackDescriptor("installed:${info.packageName}", index.displayName,
      IconPackFormat.INSTALLED_APK, IconPackSource.Installed(info.packageName, info.longVersionCode,
        info.lastUpdateTime, info.versionName),
      index.entries.filterNot { it.component?.packageName == target.packageName })
    val exporter = IconPackExporter(context, IconPackLimits())
    val matched = exporter.export(pack, listOf(target), false, 96,
      SystemIconFallback.DEFAULT, true) {}
    val fallback = exporter.export(pack, listOf(target), false, 96,
      SystemIconFallback.DEFAULT, false) {}
    try {
      assertEquals(listOf(target), matched.exportedTargets)
      assertEquals(listOf(target), fallback.exportedTargets)
      fun icon(archive: java.io.File) = ZipFile(archive).use { zip ->
        zip.getInputStream(requireNotNull(zip.getEntry("res/drawable-xxhdpi/${target.packageName}.png"))).readBytes()
      }
      assertFalse("Name matched art must differ from default system fallback",
        icon(matched.archive).contentEquals(icon(fallback.archive)))
    } finally {
      matched.archive.delete()
      fallback.archive.delete()
    }
  }
}
