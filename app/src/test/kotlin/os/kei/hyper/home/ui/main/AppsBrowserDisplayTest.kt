package os.kei.hyper.home.ui.main

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.data.InstalledIconCapability

class AppsBrowserDisplayTest {
  @Test fun cachedCapabilitiesWithoutCompletedProjectionShowLoadingInsteadOfNoMatches() {
    val display = AppsBrowserDisplay(Any(), null)

    assertTrue(display.loading)
    assertFalse(display.showEmpty)
    assertTrue(display.rows.isEmpty())
  }

  @Test fun completedEmptyResultShowsNoMatchesOnlyForItsCurrentRequest() {
    val request = Any()
    val result = AppsBrowserResult(request, emptyList(), 42)

    val completed = AppsBrowserDisplay(request, result)
    assertFalse(completed.loading)
    assertTrue(completed.showEmpty)
    assertEquals(42, completed.total)

    val nextQuery = AppsBrowserDisplay(Any(), result)
    assertTrue(nextQuery.loading)
    assertFalse(nextQuery.showEmpty)
    assertEquals(0, nextQuery.total)

    val nextDataset = AppsBrowserDisplay(request, result, Any())
    assertTrue(nextDataset.loading)
    assertFalse(nextDataset.showEmpty)
  }

  @Test fun completedNonemptyResultKeepsFilteredRowsAndUnfilteredCountTogether() {
    val request = Any()
    val rows = listOf(row("com.example.match"))
    val display = AppsBrowserDisplay(request, AppsBrowserResult(request, rows, 42))

    assertFalse(display.loading)
    assertFalse(display.showEmpty)
    assertSame(rows, display.rows)
    assertEquals(42, display.total)
  }

  @Test fun anotherRequestsEmptyAndNonemptyRowsAndCountsAreHiddenWhileCurrentWorkIsPending() {
    val oldRequest = Any()
    val newRequest = Any()
    for (rows in listOf(emptyList(), listOf(row("com.example.old")))) {
      val display = AppsBrowserDisplay(newRequest, AppsBrowserResult(oldRequest, rows, 99))
      assertTrue(display.loading)
      assertFalse(display.showEmpty)
      assertEquals(emptyList<IconBrowserRow>(), display.rows)
      assertEquals(0, display.total)
    }
  }

  @Test fun returningToAnEarlierQueryRequiresItsNewComputationToComplete() {
    val firstRequest = Any()
    val firstResult = AppsBrowserResult(firstRequest, emptyList(), 12)
    val otherRequest = Any()
    val otherResult = AppsBrowserResult(otherRequest, listOf(row("com.example.other")), 20)
    val returnedRequest = Any()

    for (oldResult in listOf(firstResult, otherResult)) {
      val pending = AppsBrowserDisplay(returnedRequest, oldResult)
      assertTrue(pending.loading)
      assertFalse(pending.showEmpty)
      assertTrue(pending.rows.isEmpty())
    }
    val completed = AppsBrowserDisplay(returnedRequest, AppsBrowserResult(returnedRequest, emptyList(), 21))
    assertFalse(completed.loading)
    assertTrue(completed.showEmpty)
    assertEquals(21, completed.total)
  }

  @Test fun queryChangeRetainsUsableRowsWithLoadingUntilEvenAnEmptyCurrentResultCompletes() {
    val dataset = Any()
    val nextQuery = Any()
    val cachedRows = listOf(row("com.example.cached"))
    val cached = AppsBrowserResult(Any(), cachedRows, 42, dataset)

    val pending = AppsBrowserDisplay(nextQuery, cached, dataset)
    assertTrue(pending.loading)
    assertFalse(pending.showEmpty)
    assertSame(cachedRows, pending.rows)

    val anotherDataset = AppsBrowserDisplay(nextQuery, cached, Any())
    assertTrue(anotherDataset.loading)
    assertFalse(anotherDataset.showEmpty)
    assertTrue(anotherDataset.rows.isEmpty())

    val completed = AppsBrowserDisplay(nextQuery,
      AppsBrowserResult(nextQuery, emptyList(), 42, dataset), dataset)
    assertFalse(completed.loading)
    assertTrue(completed.showEmpty)
    assertTrue(completed.rows.isEmpty())
  }

  @Test fun filteringKeepsExistingOrderAndTheUnfilteredTotal() = runTest {
    val request = Any()
    val first = row("com.example.match_z")
    val second = row("com.example.match_a")
    val rows = listOf(first, row("com.example.other"), second)

    val result = computeAppsBrowserMatches(request, rows, "match")

    assertSame(request, result.request)
    assertEquals(listOf(first, second), result.rows)
    assertEquals(3, result.total)
    assertFalse(AppsBrowserDisplay(request, result).loading)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test fun supersededFilterStopsMidListAndCannotOverwriteANewerCompletedResult() = runTest {
    val currentRequest = Any()
    val currentResult = AppsBrowserResult(currentRequest, emptyList(), 42)
    var published = currentResult
    lateinit var obsolete: Job
    var reads = 0
    val oldRow = row("com.example.obsolete")
    val oldRows = object : AbstractList<IconBrowserRow>() {
      override val size = 1000
      override fun get(index: Int): IconBrowserRow {
        reads++
        if (index == 2) obsolete.cancel()
        return oldRow
      }
    }

    obsolete = launch {
      published = computeAppsBrowserMatches(Any(), oldRows, "")
    }
    runCurrent()

    assertTrue(obsolete.isCancelled)
    assertEquals("Canceled work must stop reading the old list promptly", 3, reads)
    assertSame(currentResult, published)
    val display = AppsBrowserDisplay(currentRequest, published)
    assertFalse(display.loading)
    assertTrue(display.showEmpty)
    assertEquals(42, display.total)
  }

  private fun row(packageName: String) = IconBrowserRow(
    InstalledIconCapability(packageName, packageName, 0, false,
      IconCapabilityStatus.UNKNOWN, IconCapabilityStatus.UNKNOWN), null)
}
