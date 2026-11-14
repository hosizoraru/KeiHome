package os.kei.hyper.home.ui.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackSource

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProgressiveIconPackCatalogTest {
  @Test fun publishesCompletionOrderWithoutWaitingForTheSlowFamily() = runTest {
    val imported = CompletableDeferred<String>()
    val installed = CompletableDeferred<String>()
    val published = mutableListOf<Pair<String, String>>()
    val job = launch {
      publishCatalogFamilies(mapOf(
        "imported" to suspend { imported.await() },
        "installed" to suspend { installed.await() },
      )) { family, result -> published += family to result.getOrThrow() }
    }
    runCurrent()
    installed.complete("ready")
    runCurrent()
    assertEquals(listOf("installed" to "ready"), published)
    assertTrue(job.isActive)
    imported.complete("later")
    job.join()
    assertEquals(listOf("installed" to "ready", "imported" to "later"), published)
  }

  @Test fun failurePublishesOnceAndDoesNotCancelOtherFamilies() = runTest {
    val remaining = CompletableDeferred<Int>()
    val published = mutableMapOf<String, Result<Int>>()
    val job = launch {
      publishCatalogFamilies<String, Int>(mapOf(
        "broken" to suspend { error("unreadable") },
        "remaining" to suspend { remaining.await() },
      )) { family, result -> published[family] = result }
    }
    runCurrent()
    assertTrue(published.getValue("broken").isFailure)
    assertTrue(job.isActive)
    remaining.complete(7)
    job.join()
    assertEquals(7, published.getValue("remaining").getOrThrow())
    assertEquals(2, published.size)
  }

  @Test fun cancellationNeverPublishesFailureOrLateCompletion() = runTest {
    val blocked = CompletableDeferred<Int>()
    val published = mutableListOf<Result<Int>>()
    val job = launch {
      publishCatalogFamilies(mapOf("pending" to suspend { blocked.await() })) { _, result ->
        published += result
      }
    }
    runCurrent()
    job.cancelAndJoin()
    blocked.complete(1)
    runCurrent()
    assertTrue(published.isEmpty())
    assertTrue(job.isCancelled)
  }

  @Test fun sourceCancellationReachesTheCallerInsteadOfCompletingTheCatalog() = runTest {
    var cancelled = false
    var published = false
    try {
      publishCatalogFamilies(mapOf("cancelled" to suspend {
        throw CancellationException("source stopped")
      })) { _, _ -> published = true }
    } catch (_: CancellationException) {
      cancelled = true
    }
    assertTrue(cancelled)
    assertFalse(published)
  }

  @Test fun familyReplacementRetainsOtherCardsAndNeverAppendsDuplicates() {
    fun card(id: String) = IconPackCardData(
      IconPackDescriptor(id, id, IconPackFormat.INSTALLED_APK,
        IconPackSource.Installed("example.$id", 1, 1), emptyList()), 0, emptyList())
    val previous = card("old")
    val retained = card("retained")
    val catalog = ProgressiveIconPackCatalog(listOf(previous))
    assertEquals(listOf("old", "retained"),
      catalog.replace("imported", listOf(retained)).map { it.pack.id })
    assertEquals(listOf("new", "retained"),
      catalog.replace("installed", listOf(card("new"))).map { it.pack.id })
    assertEquals(listOf("new", "retained"),
      catalog.replace("installed", listOf(card("new"))).map { it.pack.id })
    assertEquals(listOf("retained"), catalog.replace("installed", emptyList()).map { it.pack.id })
  }

  @Test fun builtinSectionTracksItsOwnPendingFamily() {
    val pending = IconPacksUiState(loading = true, loadingSections = setOf("builtin", "installed"))
    assertTrue(pending.isSectionLoading("builtin"))
    assertFalse(pending.copy(loadingSections = setOf("installed")).isSectionLoading("builtin"))
  }

  @Test fun hiddenFamiliesDoNotBlockNavigationOrReadySections() {
    val state = IconPacksUiState(loading = true, loadingSections = setOf("installed", "apple"))
    assertFalse(state.isSectionLoading("home"))
    assertFalse(state.isSectionLoading("library"))
    assertFalse(state.isSectionLoading("builtin"))
    assertFalse(state.isSectionLoading("imported"))
    assertFalse(state.isSectionLoading("coloros"))
    assertTrue(state.isSectionLoading("installed"))
    assertTrue(state.isSectionLoading("apple"))
    assertTrue(state.copy(loadingSections = setOf("imported")).isSectionLoading("installed"))
  }
}
