package os.kei.hyper.home.ui.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSearchWorkTest {
  @Test fun rapidTypingCancelsIntermediateQueriesAndRetainsOldRowsUntilPublication() = runTest {
    val scope = Any()
    val options = Any()
    val rows = listOf("Alpha", "Alpine", "Beta")
    var published = LocalSearchSnapshot(LocalSearchRequest(scope, options, ""), rows)
    val previous = published
    val computed = mutableListOf<String>()
    fun search(query: String) = launch {
      val request = LocalSearchRequest(scope, options, query)
      runLocalSearch(request, published, { published = it }) {
        computed += query
        rows.filter { it.startsWith(query) }
      }
    }

    val obsolete = search("A")
    runCurrent()
    advanceTimeBy(LOCAL_SEARCH_DEBOUNCE_MS - 1)
    assertSame(previous, published)
    assertTrue(computed.isEmpty())
    obsolete.cancel()
    search("Alp")
    runCurrent()
    advanceTimeBy(LOCAL_SEARCH_DEBOUNCE_MS)
    runCurrent()

    assertEquals(listOf("Alp"), computed)
    assertEquals(listOf("Alpha", "Alpine"), published.value)
    assertNull(published.error)
  }

  @Test fun slowTypingPublishesEachSettledQueryWithoutDiscardingThePreviousResult() = runTest {
    val scope = Any()
    val options = Any()
    var published = LocalSearchSnapshot(LocalSearchRequest(scope, options, ""), listOf("old"))
    for (query in listOf("a", "ab", "abc")) {
      val previous = published
      launch {
        runLocalSearch(LocalSearchRequest(scope, options, query), published, { published = it }) {
          listOf(query)
        }
      }
      runCurrent()
      assertSame(previous, published)
      advanceTimeBy(LOCAL_SEARCH_DEBOUNCE_MS)
      runCurrent()
      assertEquals(listOf(query), published.value)
    }
  }

  @Test fun cancelledNonCooperativeComputationCannotOverwriteTheNewerSearch() = runTest {
    val scope = Any()
    val gate = CompletableDeferred<Unit>()
    var published: LocalSearchSnapshot<String>? = null
    val obsolete = launch {
      runLocalSearch(LocalSearchRequest(scope, Unit, "old"), null, { published = it }) {
        withContext(NonCancellable) { gate.await() }
        "obsolete"
      }
    }
    runCurrent()
    obsolete.cancel()
    runLocalSearch(LocalSearchRequest(scope, Unit, "new"), null, { published = it }) { "current" }
    gate.complete(Unit)
    runCurrent()

    assertTrue(obsolete.isCancelled)
    assertEquals("current", published?.value)
    assertNull(published?.error)
  }

  @Test fun failureRetainsSameScopeRowsAndExplicitRetryCanComplete() = runTest {
    val scope = Any()
    val previous = LocalSearchSnapshot(LocalSearchRequest(scope, Unit, "a"), listOf("Alpha"))
    var published = previous
    val failure = IllegalStateException("failed")
    runLocalSearch(LocalSearchRequest(scope, Unit, "a"), previous, { published = it }) { throw failure }
    assertSame(previous.value, published.value)
    assertSame(failure, published.error)

    runLocalSearch(LocalSearchRequest(scope, Unit, "a"), published, { published = it }) { listOf("Alpine") }
    assertEquals(listOf("Alpine"), published.value)
    assertNull(published.error)
  }

  @Test fun failedReplacementDatasetNeverRetainsRowsFromTheOldSource() = runTest {
    val previous = LocalSearchSnapshot(LocalSearchRequest(Any(), Unit, ""), listOf("old source"))
    var published = previous
    runLocalSearch(LocalSearchRequest(Any(), Unit, ""), previous, { published = it }) {
      throw IllegalStateException("new source failed")
    }
    assertNull(published.value)
    assertTrue(published.error is IllegalStateException)
  }

  @Test fun initialLoadScopeOptionsChangesAndClearingQueryDoNotDebounce() = runTest {
    val scope = Any()
    val options = Any()
    val previous = LocalSearchSnapshot(LocalSearchRequest(scope, options, "old"), emptyList<String>())
    val start = testScheduler.currentTime
    for ((request, old) in listOf(
      LocalSearchRequest(scope, options, "initial") to null,
      LocalSearchRequest(Any(), options, "new dataset") to previous,
      LocalSearchRequest(scope, Any(), "new options") to previous,
      LocalSearchRequest(scope, options, "") to previous,
    )) {
      var completed = false
      runLocalSearch(request, old, { completed = true }) { emptyList<String>() }
      assertTrue(completed)
      assertEquals(start, testScheduler.currentTime)
    }
  }
}
