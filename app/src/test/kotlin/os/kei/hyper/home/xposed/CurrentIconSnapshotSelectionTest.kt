package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentIconSnapshotSelectionTest {
  private val installed = "com.sample.app"
  private val activity = "com.sample.app.Main\$Nested"
  private val alias = "com.vendor.alias.Launcher_Alias"

  @Test fun selectsExactPackagesActivitiesAndAliasesInEveryKnownLayoutAndNightVariant() {
    val selection = CurrentIconSnapshotSelection(setOf(installed, activity, alias))
    for (prefix in listOf("", "nightmode/")) {
      for (name in listOf(installed, activity, alias)) {
        for (path in iconPaths(name)) assertTrue(prefix + path, selection.includes(prefix + path))
      }
      for (path in iconPaths("com.uninstalled.app")) {
        assertFalse(prefix + path, selection.includes(prefix + path))
      }
    }
  }

  @Test fun packageAndClassNamesNeverSelectPrefixSuffixOrChildNames() {
    val selection = CurrentIconSnapshotSelection(setOf(installed, activity))
    val unrequested = listOf(
      "com.sample", "com.sample.app.beta", "com.sample.application", "com.sample.app2",
      "com.sample.app.Main", "com.sample.app.Main\$Nested\$Child",
      "com.sample.app.Main\$NestedAlias", "Com.sample.app",
    )
    for (name in unrequested) for (path in iconPaths(name)) {
      assertFalse(path, selection.includes(path))
      assertFalse("nightmode/$path", selection.includes("nightmode/$path"))
    }
  }

  @Test fun emptyInstalledNamesOmitRecognizedPackageArtworkButRetainShapeHelpers() {
    val selection = CurrentIconSnapshotSelection(emptySet())
    for (path in iconPaths(installed)) assertFalse(path, selection.includes(path))
    for (path in iconPaths("icon_mask")) assertTrue(path, selection.includes(path))
  }

  @Test fun alwaysRetainsGlobalConfigurationMetadataAndNonPackagePngs() {
    val selection = CurrentIconSnapshotSelection(emptySet())
    val paths = listOf(
      "description.xml", "manifest.xml", "config.xml", "theme_values.xml", "filters.xml",
      "maml/config.xml", "META-INF/com.vendor.metadata", "keihome/source-names.txt",
      "res/values/config.xml", "res/drawable/config.xml", "res/drawable-night/theme_values.xml",
      "fancy_icons/config.xml", "animating_icons/manifest.xml",
      "icon_mask.png", "icon_background.png", "icon_pattern.png", "icon_border.png",
      "res/drawable/icon_mask.png", "res/drawable-xhdpi/icon_background.png",
      "res/drawable-night-xxhdpi/icon_pattern.png", "res/drawable-640dpi-v35/icon_border.png",
    )
    for (prefix in listOf("", "nightmode/")) for (path in paths) {
      assertTrue(prefix + path, selection.includes(prefix + path))
    }
  }

  @Test fun invalidOrUndottedDynamicNamesRemainConservativelyIncluded() {
    val selection = CurrentIconSnapshotSelection(emptySet())
    for (helper in listOf("icon_mask", "shared", "android", "invalid-name", ".bad", "com..bad", "com.1bad")) {
      for (path in iconPaths(helper)) {
        assertTrue(path, selection.includes(path))
        assertTrue("nightmode/$path", selection.includes("nightmode/$path"))
      }
    }
  }

  @Test fun excludesUninstalledPerIconMetadataAsWellAsItsImagePayloads() {
    val selection = CurrentIconSnapshotSelection(setOf(installed))
    for (path in listOf(
      "fancy_icons/com.uninstalled.app/manifest.xml",
      "animating_icons/com.uninstalled.app/fancy/manifest.xml",
      "layer_animating_icons/com.uninstalled.app/config.xml",
      "res/drawable-xxhdpi/com.uninstalled.app/config.xml",
    )) {
      assertFalse(path, selection.includes(path))
      assertFalse("nightmode/$path", selection.includes("nightmode/$path"))
    }
  }

  @Test fun unfamiliarNonIconLayoutsRemainIncludedWithoutGuessingPackageOwners() {
    val selection = CurrentIconSnapshotSelection(emptySet())
    for (path in listOf(
      "custom/com.uninstalled.app/asset.png",
      "res/mipmap/com.uninstalled.app.png", "res/raw/com.uninstalled.app.dat",
      "com.uninstalled.app.xml", "vendor/config/com.uninstalled.app",
    )) {
      assertTrue(path, selection.includes(path))
      assertTrue("nightmode/$path", selection.includes("nightmode/$path"))
    }
  }

  @Test fun cacheIdentityIsStableAcrossOrderAndRepeatedCalls() {
    val first = CurrentIconSnapshotSelection(linkedSetOf(installed, activity, alias))
    val reversed = CurrentIconSnapshotSelection(linkedSetOf(alias, activity, installed))
    assertEquals(first.cacheIdentity(), reversed.cacheIdentity())
    assertEquals(first.cacheIdentity(), first.cacheIdentity())
    assertTrue(first.cacheIdentity().matches(Regex("[0-9a-f]{64}")))
    assertEquals(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      CurrentIconSnapshotSelection(emptySet()).cacheIdentity(),
    )
  }

  @Test fun installedPackageOrActivityChangesInvalidateCacheIdentity() {
    val original = CurrentIconSnapshotSelection(setOf(installed, activity)).cacheIdentity()
    for (names in listOf(
      emptySet(), setOf(installed), setOf(activity),
      setOf(installed, activity, alias), setOf(installed, "com.sample.app.Other"),
      setOf("com.sample.app.beta", activity),
    )) {
      assertNotEquals(original, CurrentIconSnapshotSelection(names).cacheIdentity())
    }
  }

  @Test fun namesAreSnapshottedSoCallerMutationCannotChangeSelectionOrIdentity() {
    val names = mutableSetOf(installed)
    val selection = CurrentIconSnapshotSelection(names)
    val identity = selection.cacheIdentity()
    names.clear()
    names += "com.other.app"
    assertEquals(identity, selection.cacheIdentity())
    assertTrue(selection.includes("$installed.png"))
    assertFalse(selection.includes("com.other.app.png"))
  }

  @Test fun cacheIdentityFramesNamesSoConcatenationAndDelimiterCollisionsStayDistinct() {
    for ((first, second) in listOf(
      setOf("com.a", "com.bc") to setOf("com.ab", "com.c"),
      setOf("com.a", "com.b") to setOf("com.a\ncom.b"),
      setOf("com.a", "com.b") to setOf("com.acom.b"),
    )) {
      assertNotEquals(
        CurrentIconSnapshotSelection(first).cacheIdentity(),
        CurrentIconSnapshotSelection(second).cacheIdentity(),
      )
    }
  }

  private fun iconPaths(name: String): List<String> = listOf(
    "$name.png",
    "fancy_icons/$name/manifest.xml",
    "fancy_icons/$name/images/iconBg_0.png",
    "animating_icons/$name/fancy/manifest.xml",
    "animating_icons/$name/quiet/image.png",
    "layer_animating_icons/$name/config.xml",
    "layer_animating_icons/$name/0/quiet/quietImage.png",
    "res/drawable/$name.png",
    "res/drawable-xxhdpi/$name.png",
    "res/drawable-night-640dpi-v35/$name.png",
    "res/drawable/$name/0.png",
    "res/drawable-xxhdpi/$name/1.png",
    "res/drawable-night/$name/images/icon.png",
  )


  @Test fun qualifiedNativeNamesAreSelectedExactlyInsteadOfRetainedAsUnknownHelpers() {
    val qualified = "com.sample.debug#com.sample.app.Main"
    val selection = CurrentIconSnapshotSelection(setOf(qualified))
    for (prefix in listOf("", "nightmode/")) {
      for (path in iconPaths(qualified)) assertTrue(prefix + path, selection.includes(prefix + path))
      for (other in listOf("com.other.debug#com.sample.app.Main", "com.sample.debug#com.other.Main")) {
        for (path in iconPaths(other)) assertFalse(prefix + path, selection.includes(prefix + path))
      }
    }
  }
}
