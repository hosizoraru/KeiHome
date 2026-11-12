package os.kei.hyper.home.ui.main

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.StoredIconPack

class IconPackCatalogFilterTest {
  private fun installed(id: String, label: String, matches: Int, updated: Long) = IconPackCardData(
    IconPackDescriptor(id, label, IconPackFormat.INSTALLED_APK,
      IconPackSource.Installed("org.example.$id", 1, updated), emptyList()), matches, emptyList())

  private fun imported(id: String, label: String, matches: Int, format: IconPackFormat = IconPackFormat.MIUI_ICONS) =
    IconPackCardData(IconPackDescriptor(id, label, format,
      IconPackSource.Imported(StoredIconPack(id, "$id-theme.zip", 40, format,
        File("unused"), null, 0, null)), emptyList()), matches, emptyList())

  private val alpha = installed("alpha", "Alpha", 3, 10)
  private val current = installed("current", "Current pack", 1, 20)
  private val zero = installed("zero", "Zero", 0, 30)
  private val local = imported("local", "Local matched", 2)
  private val localZero = imported("local-zero", "Local original", 0)
  private val state = IconPacksUiState(packs = listOf(zero, localZero, current, local, alpha))

  private fun rows(section: String = "installed", active: String? = "current", query: String = "",
    order: Int = 0, reverse: Boolean = false, filter: IconPackCatalogFilter = IconPackCatalogFilter()) =
    catalogPackRows(state, section, active, query, order, reverse, filter).map { it.pack.id }

  @Test fun defaultFilterPreservesTheExistingListAndSectionTotals() {
    assertFalse(IconPackCatalogFilter().isActive)
    assertEquals(listOf("current", "alpha", "zero"), rows())
    assertEquals(listOf("local", "local-zero"), rows(section = "imported"))
    assertEquals(3, catalogPackCount(state, "installed"))
    assertEquals(2, catalogPackCount(state, "imported"))
    assertEquals(0, catalogPackCount(state, "home"))
    for (order in 0..3) for (reverse in listOf(false, true)) {
      assertEquals(catalogPackRows(state, "installed", "current", "", order, reverse),
        catalogPackRows(state, "installed", "current", "", order, reverse, IconPackCatalogFilter()))
    }
  }

  @Test fun currentAndOtherUseTheActiveReceiptIdentity() {
    val withReceipt = state.copy(nativeSources = mapOf("loaded" to "current"))
    val active = activeCatalogPackId("loaded", withReceipt)
    assertEquals(listOf("current"), rows(active = active,
      filter = IconPackCatalogFilter(status = IconPackCatalogStatus.CURRENT)))
    assertEquals(listOf("alpha", "zero"), rows(active = active,
      filter = IconPackCatalogFilter(status = IconPackCatalogStatus.OTHER)))
    assertTrue(rows(active = null, filter = IconPackCatalogFilter(status = IconPackCatalogStatus.CURRENT)).isEmpty())
    assertEquals(rows(active = null),
      rows(active = null, filter = IconPackCatalogFilter(status = IconPackCatalogStatus.OTHER)))
    assertTrue(rows(active = "local", filter = IconPackCatalogFilter(status = IconPackCatalogStatus.CURRENT)).isEmpty())
    assertEquals(listOf("local"), rows(section = "imported", active = "local",
      filter = IconPackCatalogFilter(status = IconPackCatalogStatus.CURRENT)))
  }

  @Test fun matchingUsesExistingInstalledAppCoverageInBothSections() {
    assertEquals(listOf("current", "alpha"),
      rows(filter = IconPackCatalogFilter(matches = IconPackCatalogMatches.MATCHED)))
    assertEquals(listOf("zero"),
      rows(filter = IconPackCatalogFilter(matches = IconPackCatalogMatches.UNMATCHED)))
    assertEquals(listOf("local"), rows(section = "imported",
      filter = IconPackCatalogFilter(matches = IconPackCatalogMatches.MATCHED)))
    assertEquals(listOf("local-zero"), rows(section = "imported",
      filter = IconPackCatalogFilter(matches = IconPackCatalogMatches.UNMATCHED)))
  }

  @Test fun bothFiltersIntersectWithSearchInsteadOfForcingTheCurrentPackIntoResults() {
    val otherMatched = IconPackCatalogFilter(IconPackCatalogStatus.OTHER, IconPackCatalogMatches.MATCHED)
    assertEquals(listOf("alpha"), rows(query = "  EXAMPLE.ALPHA ", filter = otherMatched))
    assertTrue(rows(query = "Current", filter = otherMatched).isEmpty())
    assertTrue(rows(filter = otherMatched.copy(status = IconPackCatalogStatus.CURRENT,
      matches = IconPackCatalogMatches.UNMATCHED)).isEmpty())
    assertEquals(listOf("local-zero"), rows(section = "imported", query = "LOCAL-ZERO-THEME",
      filter = IconPackCatalogFilter(matches = IconPackCatalogMatches.UNMATCHED)))
    assertTrue(rows(query = "not-found", filter = IconPackCatalogFilter(status = IconPackCatalogStatus.CURRENT)).isEmpty())
  }

  @Test fun everySortAndReverseKeepsTheCurrentPackFirstAmongSurvivingRows() {
    for (order in 0..3) for (reverse in listOf(false, true)) {
      val actual = rows(order = order, reverse = reverse,
        filter = IconPackCatalogFilter(matches = IconPackCatalogMatches.MATCHED))
      assertEquals("current", actual.first())
      assertEquals(setOf("current", "alpha"), actual.toSet())
    }
    assertEquals(listOf("zero", "alpha"), rows(order = 2,
      filter = IconPackCatalogFilter(status = IconPackCatalogStatus.OTHER)))
    assertEquals(listOf("alpha", "zero"), rows(order = 2, reverse = true,
      filter = IconPackCatalogFilter(status = IconPackCatalogStatus.OTHER)))
  }

  @Test fun resettingOnlyTheFilterRetainsTheCallersQuerySortAndDirection() {
    val filter = IconPackCatalogFilter(IconPackCatalogStatus.CURRENT, IconPackCatalogMatches.UNMATCHED)
    assertTrue(filter.isActive)
    assertTrue(rows(query = "org.example", order = 3, reverse = true, filter = filter).isEmpty())
    assertEquals(listOf("current", "zero", "alpha"),
      rows(query = "org.example", order = 3, reverse = true, filter = IconPackCatalogFilter()))
    assertEquals(3, catalogPackCount(state, "installed"))
  }

  @Test fun filtersKeepApkClassificationAndExcludeBuiltinAndGeneratedSources() {
    val apk = imported("apk", "APK file", 0, IconPackFormat.APK)
    val builtin = local.copy(pack = local.pack.copy(id = "builtin", builtinKey = "builtin"))
    val generated = local.copy(pack = local.pack.copy(id = "generated",
      source = IconPackSource.Generated(File("unused"), "digest", 1)))
    val extras = state.copy(packs = state.packs + listOf(apk, builtin, generated))
    assertEquals(listOf("apk", "zero"), catalogPackRows(extras, "installed", null, "", 0, false,
      IconPackCatalogFilter(matches = IconPackCatalogMatches.UNMATCHED)).map { it.pack.id })
    assertEquals(listOf("local"), catalogPackRows(extras, "imported", null, "", 0, false,
      IconPackCatalogFilter(matches = IconPackCatalogMatches.MATCHED)).map { it.pack.id })
    assertEquals(4, catalogPackCount(extras, "installed"))
    assertEquals(2, catalogPackCount(extras, "imported"))
  }
}
