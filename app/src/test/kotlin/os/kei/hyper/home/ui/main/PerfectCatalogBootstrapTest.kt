package os.kei.hyper.home.ui.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.iconpack.perfect.PerfectIconCatalog
import os.kei.hyper.home.iconpack.perfect.PerfectIconOptions
import os.kei.hyper.home.iconpack.perfect.PerfectIconRemote

@OptIn(ExperimentalCoroutinesApi::class)
class PerfectCatalogBootstrapTest {
  private val complete = PerfectIconRemote("revision", checkedAt = 10,
    availableStyles = listOf("default", "HyperOS4", "Glass"))
  private val downloaded = PerfectIconCatalog("revision", listOf("default"), 1, 1,
    downloadedStyles = listOf("default"))

  @Test fun missingOrLegacyDownloadedOnlyCatalogNeedsIndependentMetadata() {
    val bootstrap = PerfectCatalogBootstrap()
    assertTrue(bootstrap.shouldCheck(PerfectPackState(), 10))
    assertTrue(bootstrap.shouldCheck(PerfectPackState(catalog = downloaded), 10))
    assertTrue(bootstrap.shouldCheck(PerfectPackState(remote = complete.copy(availableStyles = emptyList())), 10))
  }

  @Test fun savedCompleteRemoteOrGitCatalogAvoidsNetworkEvenWithoutLocalResources() {
    val bootstrap = PerfectCatalogBootstrap()
    assertFalse(bootstrap.shouldCheck(PerfectPackState(remote = complete), 10))
    assertFalse(bootstrap.shouldCheck(PerfectPackState(catalog = downloaded.copy(
      styles = complete.availableStyles, gitBacked = true)), 10))
  }

  @Test fun attemptedDiscoveryDoesNotRepeatOnReturnOrSelection() {
    val bootstrap = PerfectCatalogBootstrap()
    bootstrap.started()
    assertFalse(bootstrap.shouldCheck(PerfectPackState(), 10))
    assertFalse(bootstrap.shouldCheck(PerfectPackState(options = PerfectIconOptions(style = "Glass")), 100_000))
  }

  @Test fun failedDiscoveryCanRecoverOnALaterResumeWithoutPollingOrIgnoringServerCooldown() {
    val bootstrap = PerfectCatalogBootstrap()
    bootstrap.started()
    bootstrap.failed(1_000)
    assertFalse(bootstrap.shouldCheck(PerfectPackState(), 30_999))
    assertTrue(bootstrap.shouldCheck(PerfectPackState(), 31_000))
    assertFalse(bootstrap.shouldCheck(PerfectPackState(retryAtMillis = 60_000), 31_000))
  }

  @Test fun cancellationOrCooldownCanRetryOnEligibleResumeWithoutIgnoringDeadline() {
    val bootstrap = PerfectCatalogBootstrap()
    assertFalse(bootstrap.shouldCheck(PerfectPackState(retryAtMillis = 20), 10))
    bootstrap.started()
    bootstrap.retryWhenEligible()
    assertFalse(bootstrap.shouldCheck(PerfectPackState(retryAtMillis = 20), 19))
    assertTrue(bootstrap.shouldCheck(PerfectPackState(retryAtMillis = 20), 20))
    assertFalse(bootstrap.shouldCheck(PerfectPackState(checking = true), 20))
  }

  @Test fun sparseAndMissingLocalMetadataRetainsCompleteIndexButNewRevisionReplacesIt() {
    val sparse = complete.copy(checkedAt = 20, availableStyles = emptyList())
    assertEquals(complete.availableStyles, retainPerfectCatalogMetadata(complete, sparse)?.availableStyles)
    assertEquals(20L, retainPerfectCatalogMetadata(complete, sparse)?.checkedAt)
    assertSame(complete, retainPerfectCatalogMetadata(complete, null))
    val replacement = sparse.copy(revision = "new")
    assertSame(replacement, retainPerfectCatalogMetadata(complete, replacement))
    val newCatalog = downloaded.copy(revision = "new", styles = listOf("default", "NewStyle"),
      gitBacked = true)
    assertEquals(newCatalog.styles,
      retainPerfectCatalogMetadata(complete, replacement, newCatalog)?.availableStyles)
  }

  @Test fun removingLastLocalStyleKeepsSavedGitCatalogMetadataWithoutKeepingDownloadsReady() {
    val saved = downloaded.copy(styles = complete.availableStyles, gitBacked = true)
    val retained = retainPerfectCatalogMetadata(null, null, saved)
    assertEquals(complete.availableStyles, retained?.availableStyles)
    val removed = PerfectPackState(catalog = null, remote = retained)
    assertFalse(removed.ready)
    assertFalse(PerfectCatalogBootstrap().shouldCheck(removed, 100))
    assertNull(retainPerfectCatalogMetadata(null, null, downloaded))
  }

  @Test fun overlappingLocalAndDirectEntriesCoalesceAndUpgradeAfterLocalReconciliation() = runTest {
    val localGate = CompletableDeferred<Unit>()
    val events = mutableListOf<String>()
    val work = PerfectResourceResumeWork(this, refreshLocal = {
      events += "local"
      localGate.await()
    }, bootstrap = { events += "metadata" })
    val parent = work.resume(false)
    runCurrent()
    val direct = work.resume(true)
    assertSame(parent, direct)
    assertEquals(listOf("local"), events)
    localGate.complete(Unit)
    direct.join()
    assertEquals(listOf("local", "metadata"), events)
  }

  @Test fun returningAndSelectionRefreshLocalStateWithoutRepeatedNetworkOrPolling() = runTest {
    var localReads = 0
    var checks = 0
    var state = PerfectPackState()
    val bootstrap = PerfectCatalogBootstrap()
    val work = PerfectResourceResumeWork(this, refreshLocal = {
      localReads++
      state = state.copy(resourceBytes = localReads.toLong())
    }, bootstrap = {
      if (bootstrap.shouldCheck(state, 100)) {
        bootstrap.started()
        checks++
        state = state.copy(remote = complete)
      }
    })
    work.resume(true).join()
    state = state.copy(options = PerfectIconOptions(style = "Glass"), catalog = downloaded)
    work.resume(false).join()
    state = state.copy(catalog = null)
    work.resume(true).join()
    assertEquals(3, localReads)
    assertEquals(3L, state.resourceBytes)
    assertEquals(complete, state.remote)
    assertNull(state.catalog)
    assertEquals(1, checks)
    runCurrent()
    assertEquals(3, localReads)
  }

  @Test fun cancellingOneNavigationWaiterDoesNotCancelSharedControllerWork() = runTest {
    val gate = CompletableDeferred<Unit>()
    var checks = 0
    val work = PerfectResourceResumeWork(this, refreshLocal = { gate.await() },
      bootstrap = { checks++ })
    val shared = work.resume(true)
    val navigationWaiter = launch { shared.join() }
    runCurrent()
    navigationWaiter.cancelAndJoin()
    assertTrue(shared.isActive)
    gate.complete(Unit)
    shared.join()
    assertEquals(1, checks)
  }

  @Test fun cancelledResumeWorkCanBeRetriedWithoutStuckInFlightState() = runTest {
    var localReads = 0
    var checks = 0
    val gate = CompletableDeferred<Unit>()
    val work = PerfectResourceResumeWork(this, refreshLocal = {
      if (++localReads == 1) gate.await()
    }, bootstrap = { checks++ })
    val first = work.resume(true)
    runCurrent()
    first.cancelAndJoin()
    work.resume(true).join()
    assertEquals(2, localReads)
    assertEquals(1, checks)
  }
}
