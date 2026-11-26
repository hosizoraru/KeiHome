package os.kei.hyper.home.ui.main

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.StoredIconPack
import os.kei.hyper.home.status.SystemIconThemeReport
import os.kei.hyper.home.ui.apple.appleCurrentThemeEntry
import os.kei.hyper.home.ui.apple.appleCurrentThemeSourceReady

class AppleCurrentThemePreviewPolicyTest {
  private val packageEntry = IconPackEntry("package", nativeName = "probe.app")
  private val componentEntry = IconPackEntry("component", nativeName = "probe.app.Main")
  private val pack = IconPackDescriptor("applied", "Applied", IconPackFormat.MIUI_ICONS,
    IconPackSource.Imported(StoredIconPack("applied", "Applied", 0, IconPackFormat.MIUI_ICONS,
      File("applied.zip"), File("applied.zip"), 1, null)), listOf(packageEntry, componentEntry))
  private val source = IconBrowserSource("snapshot", pack,
    mapOf("probe.app" to packageEntry, "probe.app.Main" to componentEntry), emptySet(), emptySet())
  private val theme = SystemIconThemeReport(SystemIconThemeReport.Source.THEME, "Applied", null,
    2, 0, true, "revision", previewKey = "snapshot")

  @Test fun onlyTheMatchingCompleteAppliedSnapshotMayBeShown() {
    assertTrue(appleCurrentThemeSourceReady(theme, source))
    assertFalse(appleCurrentThemeSourceReady(theme.copy(previewKey = "new-theme"), source))
    assertFalse(appleCurrentThemeSourceReady(theme.copy(previewKey = null), source))
    assertFalse(appleCurrentThemeSourceReady(theme.copy(complete = false), source))
    assertFalse(appleCurrentThemeSourceReady(theme.copy(source = SystemIconThemeReport.Source.UNAVAILABLE), source))
    assertFalse(appleCurrentThemeSourceReady(null, source))
    assertFalse(appleCurrentThemeSourceReady(theme, null))
  }

  @Test fun systemDefaultIsAlsoReadFromItsAuthenticatedSnapshot() {
    assertTrue(appleCurrentThemeSourceReady(theme.copy(source = SystemIconThemeReport.Source.DEFAULT), source))
  }

  @Test fun exactActivityArtworkPrecedesPackageArtwork() {
    assertSame(componentEntry, appleCurrentThemeEntry(source, "probe.app", "probe.app.Main"))
    assertSame(packageEntry, appleCurrentThemeEntry(source, "probe.app", "probe.app.Other"))
    assertSame(packageEntry, appleCurrentThemeEntry(source, "probe.app", null))
  }

  @Test fun noAliasesOrApkFallbackAreInventedForUncoveredPackages() {
    assertNull(appleCurrentThemeEntry(source, "probe.app.extra", null))
    assertNull(appleCurrentThemeEntry(source, "other.app", "other.app.Main"))
  }
}
