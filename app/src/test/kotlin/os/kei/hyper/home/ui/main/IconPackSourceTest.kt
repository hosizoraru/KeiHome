package os.kei.hyper.home.ui.main

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.iconpack.*

class IconPackSourceTest {
  private val pack = IconPackDescriptor("asset", "ColorOS", IconPackFormat.CLASSIC_ZIP,
    IconPackSource.Imported(StoredIconPack("asset", "ColorOS", 0, IconPackFormat.CLASSIC_ZIP,
      File("unused"), null, 0, null)), emptyList(), builtinKey = "coloros-classic-art-on")
  private fun state(forward: Map<String, String>, reverse: Map<String, String> = emptyMap()) =
    IconPacksUiState(packs = listOf(IconPackCardData(pack, 0, emptyList())),
      nativeAssociations = forward, nativeSources = reverse)
  private val original = "coloros:classic:SYSTEM:asset"
  private val variant = "$original:system-fallback-v1:DEFAULT"

  @Test fun regenerationCanRecoverAnOlderOwnedColorOsComponentWithoutChangingItsAppearance() {
    val source = currentIconPackSource("old", "Classic ART+", state(mapOf(variant to "new")),
      mapOf("old" to false)) { _, _ -> "Classic ART+" }
    assertEquals(original, source?.id)
    assertEquals(pack.builtinKey, source?.builtinKey)
  }

  @Test fun foreignTitlesAndAmbiguousHistoricalModesAreNeverGuessed() {
    assertNull(currentIconPackSource("foreign", "Classic ART+", state(mapOf(variant to "new")),
      emptyMap()) { _, _ -> "Classic ART+" })
    val ambiguous = state(mapOf(variant to "new", "coloros:classic:DARK:asset" to "other"))
    assertNull(currentIconPackSource("old", "Classic ART+", ambiguous,
      mapOf("old" to false)) { _, _ -> "Classic ART+" })
    assertNull(currentIconPackSource("old", "Renamed", state(mapOf(variant to "new")),
      mapOf("old" to false)) { _, _ -> "Classic ART+" })
  }

  @Test fun exactReverseOwnershipSurvivesAChangedTitleAndNewerForwardImport() {
    assertEquals(original, currentIconPackSource("old", "Renamed", state(mapOf(variant to "new"),
      mapOf("old" to variant)), emptyMap()) { _, _ -> "Classic ART+" }?.id)
  }

  @Test fun refreshedBuiltinAssetsRetainTheAppliedStyleAppearanceAndArtChoice() {
    assertEquals(original, currentIconPackSource("old", "Classic ART+",
      state(emptyMap(), mapOf("old" to "coloros:classic:SYSTEM:old-asset-hash:system-fallback-v1:DEFAULT")),
      emptyMap()) { _, art -> if (art) "Classic ART+" else "Classic" }?.id)
    assertNull(currentIconPackSource("old", "Renamed",
      state(emptyMap(), mapOf("old" to "coloros:classic:SYSTEM:old-asset-hash")),
      emptyMap()) { _, _ -> "Classic ART+" })
  }
}
