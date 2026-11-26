package os.kei.hyper.home.ui.main

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.iconpack.apple.AppleAppTarget
import os.kei.hyper.home.iconpack.apple.AppleIconRepository
import os.kei.hyper.home.ui.apple.ApplePackController

class ApplePackLoadingDeviceTest {
  private fun fixture(block: suspend (AppleIconRepository, CoroutineScope) -> Unit) = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val namespace = "apple_loading_${UUID.randomUUID()}"
    val directory = File(context.cacheDir, namespace)
    val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    try { block(AppleIconRepository(context, directory = directory, namespace = namespace), scope) }
    finally {
      job.cancelAndJoin()
      directory.deleteRecursively()
      AppLocalStorage.open(context, namespace).clearAll()
    }
  }

  private suspend fun awaitRevision(controller: ApplePackController, revision: Int) =
    withTimeout(10_000) {
      controller.state.first { !it.loading && it.previewRevision >= revision }
    }

  @Test fun emptySnapshotSurvivesEntryAndTargetInvalidationStillRefreshes() = fixture { repo, scope ->
    val reads = AtomicInteger()
    val controller = ApplePackController(scope, repo,
      loadTargets = { reads.incrementAndGet(); emptyList() }, onGenerated = {})
    withContext(Dispatchers.Main) { controller.ensureLoaded() }
    awaitRevision(controller, 1)
    withContext(Dispatchers.Main) {
      repeat(3) { controller.ensureLoaded() }
      assertFalse(controller.state.value.loading)
      assertEquals(1, reads.get())
      controller.refreshTargetsIfLoaded()
    }
    awaitRevision(controller, 2)
    assertEquals(2, reads.get())
    withContext(Dispatchers.Main) { controller.load() }
    awaitRevision(controller, 3)
    assertEquals(3, reads.get())
  }

  @Test fun entriesShareInFlightWorkAndExplicitInvalidationsQueueOneFollowup() = fixture { repo, scope ->
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val reads = AtomicInteger()
    val controller = ApplePackController(scope, repo, loadTargets = {
      if (reads.incrementAndGet() == 1) {
        entered.complete(Unit)
        release.await()
      }
      emptyList()
    }, onGenerated = {})
    withContext(Dispatchers.Main) { controller.ensureLoaded() }
    withTimeout(10_000) { entered.await() }
    withContext(Dispatchers.Main) {
      repeat(3) { controller.ensureLoaded() }
      repeat(3) { controller.load() }
      controller.refreshTargetsIfLoaded()
    }
    release.complete(Unit)
    awaitRevision(controller, 2)
    assertEquals(2, reads.get())
  }

  @Test fun failedInitialLoadCanRetryOnEntry() = fixture { repo, scope ->
    val reads = AtomicInteger()
    val controller = ApplePackController(scope, repo, loadTargets = {
      if (reads.incrementAndGet() == 1) throw IOException("Owned fixture failure")
      emptyList()
    }, onGenerated = {})
    withContext(Dispatchers.Main) { controller.ensureLoaded() }
    withTimeout(10_000) { controller.state.first { !it.loading && it.error != null } }
    withContext(Dispatchers.Main) { controller.ensureLoaded() }
    awaitRevision(controller, 1)
    assertEquals(2, reads.get())
    assertNull(controller.state.value.error)
  }

  @Test fun optionChangeDuringInitialReadQueuesCurrentRowsAndGenerationState() = fixture { repo, scope ->
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val reads = AtomicInteger()
    val controller = ApplePackController(scope, repo, loadTargets = {
      if (reads.incrementAndGet() == 1) {
        entered.complete(Unit)
        release.await()
      }
      listOf(AppleAppTarget("test.loading.fixture", "Owned fixture", false))
    }, onGenerated = {})
    withContext(Dispatchers.Main) { controller.ensureLoaded() }
    withTimeout(10_000) { entered.await() }
    withContext(Dispatchers.Main) {
      controller.options(controller.state.value.options.copy(country = "cn"))
    }
    release.complete(Unit)
    val state = awaitRevision(controller, 2)
    assertEquals(2, reads.get())
    assertEquals("cn", state.options.country)
    assertEquals("test.loading.fixture", state.rows.single().target.packageName)
    assertTrue(state.pendingChanges)
  }

  @Test fun localeEntryReusesLabelsUntilLanguageChangesIncludingSwitchBack() = fixture { repo, scope ->
    val reads = AtomicInteger()
    val label = AtomicReference("English")
    val controller = ApplePackController(scope, repo, loadTargets = {
      reads.incrementAndGet()
      listOf(AppleAppTarget("test.loading.fixture", label.get(), false))
    }, onGenerated = {})
    withContext(Dispatchers.Main) { controller.ensureLoaded("en-US") }
    awaitRevision(controller, 1)
    withContext(Dispatchers.Main) {
      repeat(3) { controller.ensureLoaded("en-US") }
      assertEquals(1, reads.get())
      label.set("Chinese")
      repeat(3) { controller.ensureLoaded("zh-CN") }
    }
    assertEquals("Chinese", awaitRevision(controller, 2).rows.single().target.label)
    assertEquals(2, reads.get())
    withContext(Dispatchers.Main) {
      label.set("English")
      controller.ensureLoaded("en-US")
    }
    assertEquals("English", awaitRevision(controller, 3).rows.single().target.label)
    assertEquals(3, reads.get())
  }

  @Test fun localeChangeDuringLoadPublishesOnlyLatestLabelsWithOneFollowup() = fixture { repo, scope ->
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val reads = AtomicInteger()
    val label = AtomicReference("English")
    val controller = ApplePackController(scope, repo, loadTargets = {
      val captured = label.get()
      if (reads.incrementAndGet() == 1) {
        entered.complete(Unit)
        release.await()
      }
      listOf(AppleAppTarget("test.loading.fixture", captured, false))
    }, onGenerated = {})
    withContext(Dispatchers.Main) { controller.ensureLoaded("en-US") }
    withTimeout(10_000) { entered.await() }
    withContext(Dispatchers.Main) {
      label.set("Chinese")
      repeat(3) { controller.ensureLoaded("zh-CN") }
    }
    release.complete(Unit)
    val state = awaitRevision(controller, 1)
    assertEquals(2, reads.get())
    assertEquals(1, state.previewRevision)
    assertEquals("Chinese", state.rows.single().target.label)
    withContext(Dispatchers.Main) {
      repeat(3) { controller.ensureLoaded("zh-CN") }
      assertFalse(controller.state.value.loading)
    }
    assertEquals(2, reads.get())
  }

  @Test fun bulkRowsWaitForWorkerWithoutBlockingMain() = fixture { repo, scope ->
    val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val release = CountDownLatch(1)
    val dispatched = CompletableDeferred<Unit>()
    val dispatcher = object : CoroutineDispatcher() {
      override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatched.complete(Unit)
        worker.dispatch(context, Runnable {
          check(release.await(10, TimeUnit.SECONDS))
          block.run()
        })
      }
    }
    try {
      val controller = ApplePackController(scope, repo, rowDispatcher = dispatcher,
        loadTargets = { listOf(AppleAppTarget("test.loading.fixture", "Owned fixture", false)) },
        onGenerated = {})
      withContext(Dispatchers.Main) { controller.ensureLoaded() }
      withTimeout(10_000) { dispatched.await() }
      withContext(Dispatchers.Main) { assertTrue(controller.state.value.loading) }
      release.countDown()
      val state = awaitRevision(controller, 1)
      assertEquals("test.loading.fixture", state.rows.single().target.packageName)
    } finally {
      release.countDown()
      worker.close()
    }
  }
}
