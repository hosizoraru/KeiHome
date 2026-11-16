package os.kei.hyper.home.ui.main

import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PerfectListOrderingTest {
  @Test fun cancelledPreviewFilteringStopsBeforeTraversingTheRemainingCatalog() {
    var reads = 0
    val large = object : AbstractList<PerfectPreviewListItem>() {
      override val size = 40_000
      override fun get(index: Int): PerfectPreviewListItem {
        reads++
        return preview[index % preview.size]
      }
    }
    val cancelled = CancellationException("superseded query")
    val failure = assertThrows(CancellationException::class.java) {
      perfectPreviewViews(large, "org", false, false, PerfectPreviewSort.NAME, false, Locale.US) {
        if (reads == 3) throw cancelled
      }
    }
    assertSame(cancelled, failure)
    assertEquals(3, reads)
  }

  private val recommended = listOf("HyperOS4", "HyperOSClassic", "HyperOS3", "Hyper", "default")
  private val downloaded = setOf("HyperOSClassic", "default", "Hyper")
  private fun resources(
    filter: PerfectResourceFilter = PerfectResourceFilter.ALL,
    sort: PerfectResourceSort = PerfectResourceSort.RECOMMENDED, reverse: Boolean = false,
  ) = perfectResourceRows(recommended, downloaded,
    mapOf("HyperOSClassic" to 20L, "default" to 10L, "Hyper" to 0L, "HyperOS4" to 100L),
    filter, sort, reverse, "Default", Locale.US)

  @Test fun recommendedOrderAndDownloadFiltersPreserveSourcePriority() {
    assertEquals(listOf("HyperOSClassic", "Hyper", "default", "HyperOS4", "HyperOS3"), resources())
    assertEquals(listOf("HyperOSClassic", "Hyper", "default"), resources(PerfectResourceFilter.DOWNLOADED))
    assertEquals(listOf("HyperOS4", "HyperOS3"), resources(PerfectResourceFilter.NOT_DOWNLOADED))
    assertEquals(listOf("default", "Hyper", "HyperOSClassic", "HyperOS3", "HyperOS4"), resources(reverse = true))
  }

  @Test fun sizeSortKeepsUnknownAndUndownloadedSizesLastInEitherDirection() {
    assertEquals(listOf("HyperOSClassic", "default", "Hyper", "HyperOS3", "HyperOS4"),
      resources(sort = PerfectResourceSort.SIZE))
    assertEquals(listOf("default", "HyperOSClassic", "HyperOS4", "HyperOS3", "Hyper"),
      resources(sort = PerfectResourceSort.SIZE, reverse = true))
  }

  @Test fun downloadStatusAndLocalizedDisplayNameAreIndependentSorts() {
    assertEquals(listOf("default", "Hyper", "HyperOSClassic", "HyperOS3", "HyperOS4"),
      resources(sort = PerfectResourceSort.DOWNLOADS))
    assertEquals(listOf("default", "Hyper", "HyperOS3", "HyperOS4", "HyperOSClassic"),
      resources(sort = PerfectResourceSort.NAME))
  }

  @Test fun sortingRetainsSelectionAndFilteringPrunesOnlyHiddenKeys() {
    val selected = listOf("HyperOS4", "default", "Hyper")
    assertEquals(selected, retainedPerfectSelection(selected, resources(sort = PerfectResourceSort.NAME)))
    assertEquals(listOf("default", "Hyper"),
      retainedPerfectSelection(selected, resources(PerfectResourceFilter.DOWNLOADED)))
    assertEquals(listOf("HyperOS4"),
      retainedPerfectSelection(selected, resources(PerfectResourceFilter.NOT_DOWNLOADED)))
  }

  @Test fun localizedDefaultDisplayNameControlsNameOrdering() {
    assertEquals(listOf("default", "Alpha"), perfectResourceRows(listOf("Alpha", "default"),
      emptySet(), emptyMap(), PerfectResourceFilter.ALL, PerfectResourceSort.NAME, false, "Aardvark", Locale.US))
  }

  @Test fun equalLabelsHaveDeterministicIdentityAndKeyTies() {
    val tied = listOf(
      PerfectPreviewListItem("c", "org.b", "Same", emptyList(), true, false, false),
      PerfectPreviewListItem("b", "org.a", "Same", emptyList(), true, false, false),
      PerfectPreviewListItem("a", "org.a", "Same", emptyList(), true, false, false),
    )
    assertEquals(listOf("a", "b", "c"), perfectPreviewRows(tied, "", 0, false,
      PerfectPreviewSort.NAME, false, Locale.US).map { it.key })
  }

  private val preview = listOf(
    PerfectPreviewListItem("z", "org.z.DefaultIcon", "Alpha", listOf("org.z"), true, false, true),
    PerfectPreviewListItem("a", "org.a", "Zulu", emptyList(), true, true, false),
    PerfectPreviewListItem("b", "org.b", null, listOf("foreign.Icon"), false, false, false),
  )
  private fun previews(query: String = "", category: Int = 0, installed: Boolean = false,
    sort: PerfectPreviewSort = PerfectPreviewSort.ORIGINAL, reverse: Boolean = false) =
    perfectPreviewRows(preview, query, category, installed, sort, reverse, Locale.US).map { it.key }

  @Test fun previewViewCountsShareSearchNightAndInstalledConstraints() {
    fun groups(query: String = "", night: Boolean = false, installed: Boolean = false) =
      perfectPreviewViews(preview, query, night, installed, PerfectPreviewSort.NAME, true, Locale.US)
        .map { rows -> rows.map { it.key } }
    assertEquals(listOf(3, 2, 1), groups().map { it.size })
    assertEquals(listOf(listOf("z"), listOf("z"), emptyList<String>()), groups("ORG.Z", night = true, installed = true))
    assertEquals(listOf(2, 1, 1), groups(installed = true).map { it.size })
  }

  @Test fun previewCategoriesInstalledScopeAndSearchComposeWithoutReplacingResources() {
    assertEquals(listOf("z", "a", "b"), previews())
    assertEquals(listOf("z", "a"), previews(installed = true))
    assertEquals(listOf("z", "b"), previews(category = 1))
    assertEquals(listOf("a"), previews(category = 2))
    assertEquals(listOf("z"), previews(category = 3, installed = true, query = "ORG.Z"))
    assertEquals(listOf("b"), previews(query = "foreign.icon"))
    assertTrue(previews(query = "foreign.icon", installed = true).isEmpty())
  }

  @Test fun previewSortUsesLabelsOrResourceIdentityAndSupportsReverse() {
    assertEquals(listOf("z", "b", "a"), previews(sort = PerfectPreviewSort.NAME))
    assertEquals(listOf("a", "b", "z"), previews(sort = PerfectPreviewSort.IDENTITY))
    assertEquals(listOf("z", "b", "a"), previews(sort = PerfectPreviewSort.IDENTITY, reverse = true))
    assertEquals(listOf("b", "a", "z"), previews(reverse = true))
  }
}
