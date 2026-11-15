package os.kei.hyper.home.ui.main

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.iconpack.*

class CustomIconPreviewChoiceTest {
  private fun pack(vararg entries: IconPackEntry) = IconPackDescriptor("test", "Test", IconPackFormat.APK,
    IconPackSource.Installed("test", 1, 0), entries.toList())

  @Test fun exactActivityWinsOverThePackageFallback() {
    val generic = IconPackEntry("generic", component = IconPackComponent("com.target"))
    val exact = IconPackEntry("activity", component = IconPackComponent("com.target", "com.target.Main"))
    val choice = customIconPreviewChoice(pack(generic, exact), exact.component!!)
    assertEquals(exact, choice.match)
    assertEquals(listOf(exact), choice.examples)
  }

  @Test fun namespacedActivityWinsWhileLegacyNativeNamesRemainCompatible() {
    val other = IconPackEntry("foreign", nativeName = "foreign.launcher.Main")
    val owned = IconPackEntry("owned", nativeName = "com.target#foreign.launcher.Main")
    assertEquals(owned, customIconPreviewChoice(pack(other, owned),
      IconPackComponent("com.target", "foreign.launcher.Main")).match)
    assertEquals(other, customIconPreviewChoice(pack(other), IconPackComponent("com.target", "foreign.launcher.Main")).match)
  }

  @Test fun similarPrefixesAreSamplesAndNeverClaimDirectCoverage() {
    val unrelated = IconPackEntry("other", nativeName = "com.target.other")
    val choice = customIconPreviewChoice(pack(unrelated), IconPackComponent("com.target"))
    assertNull(choice.match)
    assertEquals(listOf(unrelated), choice.examples)
  }

  @Test fun uncoveredPacksUseConsistentRepresentativeAppsBeforeArbitraryEntries() {
    val random = IconPackEntry("random", nativeName = "com.random")
    val examples = listOf("com.android.settings", "com.android.contacts", "com.android.camera")
      .map { IconPackEntry(it, nativeName = it) }
    assertEquals(examples, customIconPreviewChoice(pack(random, *examples.toTypedArray()),
      IconPackComponent("com.target")).examples)
  }
}
