package os.kei.hyper.home.xposed

import android.content.res.Configuration
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class IconRefreshHotReloadTest {
  private val first = Targets::class.java.getDeclaredMethod("first")
  private val second = Targets::class.java.getDeclaredMethod("second")
  private val hooker = XposedInterface.Hooker { null }

  @Test
  fun replacesOnlyTheHandleWithMatchingIdAndMethod() {
    val mismatchedId = FakeHandle(first, "other")
    val mismatchedMethod = FakeHandle(second, "listener")
    val matching = FakeHandle(first, "listener")
    var newHookCount = 0
    val xposed = xposed { newHookCount += 1 }

    val (replacement, consumed) = installOrReplaceIconHook(
      xposed, findMatchingIconHook(
        listOf(mismatchedId, mismatchedMethod, matching), "listener", first,
      ), "listener", first, hooker,
    )

    assertSame(matching, consumed)
    assertSame(matching.replacement, replacement)
    assertEquals(1, matching.replacements)
    assertEquals(0, mismatchedId.replacements)
    assertEquals(0, mismatchedMethod.replacements)
    assertEquals(0, newHookCount)
  }

  @Test
  fun missingOrMismatchedHookIsInstalledWithoutReplacingTheWrongHandle() {
    val wrongTarget = FakeHandle(second, "listener")
    var newHookCount = 0
    val xposed = xposed { newHookCount += 1 }

    val (installed, consumed) = installOrReplaceIconHook(
      xposed, findMatchingIconHook(listOf(wrongTarget), "listener", first), "listener",
      first, hooker,
    )

    assertNull(consumed)
    assertEquals(first, installed.executable)
    assertEquals("listener", installed.id)
    assertEquals(0, wrongTarget.replacements)
    assertEquals(1, newHookCount)
  }

  @Test
  fun duplicateMatchingHandlesFailWithoutReplacingEither() {
    val firstHandle = FakeHandle(first, "listener")
    val secondHandle = FakeHandle(first, "listener")
    var newHookCount = 0
    val xposed = xposed { newHookCount += 1 }

    assertThrows(IllegalArgumentException::class.java) {
      val matching = findMatchingIconHook(
        listOf(firstHandle, secondHandle), "listener", first,
      )
      installOrReplaceIconHook(
        xposed, matching, "listener", first, hooker,
      )
    }
    assertEquals(0, firstHandle.replacements)
    assertEquals(0, secondHandle.replacements)
    assertEquals(0, newHookCount)
  }

  @Test
  fun failedReplacementDoesNotFallBackToInstallingAnotherHook() {
    val old = FakeHandle(first, "listener", failReplacement = true)
    var newHookCount = 0
    val xposed = xposed { newHookCount += 1 }

    assertThrows(IllegalStateException::class.java) {
      installOrReplaceIconHook(xposed, old, "listener", first, hooker)
    }
    assertEquals(1, old.replacements)
    assertEquals(0, newHookCount)
  }

  @Test
  fun handleMustStillMatchImmediatelyBeforeReplacement() {
    val wrongTarget = FakeHandle(second, "listener")
    var newHookCount = 0
    val xposed = xposed { newHookCount += 1 }

    assertThrows(IllegalArgumentException::class.java) {
      installOrReplaceIconHook(xposed, wrongTarget, "listener", first, hooker)
    }
    assertEquals(0, wrongTarget.replacements)
    assertEquals(0, newHookCount)
  }

  @Test
  fun preflightingBothTargetsDetectsSecondDuplicateBeforeFirstReplacement() {
    val listener = FakeHandle(first, "listener")
    val configuration = FakeHandle(second, "configuration")
    val duplicate = FakeHandle(second, "configuration")
    val hooks = listOf(listener, configuration, duplicate)
    var newHookCount = 0
    val xposed = xposed { newHookCount += 1 }

    assertThrows(IllegalArgumentException::class.java) {
      val oldListener = findMatchingIconHook(hooks, "listener", first)
      findMatchingIconHook(hooks, "configuration", second)
      installOrReplaceIconHook(xposed, oldListener, "listener", first, hooker)
    }
    assertEquals(0, listener.replacements)
    assertEquals(0, configuration.replacements)
    assertEquals(0, duplicate.replacements)
    assertEquals(0, newHookCount)
  }

  @Test
  fun cancelledQueuedTaskCannotClaimTheRepostedRequestAfterRollback() {
    val pending = PendingIconRefresh()
    val request = IconRefreshRequest(1, 0)
    pending.replace(request)
    val oldTicket = pending.markPosted()

    pending.invalidatePost()
    assertEquals(request, pending.request)
    val newTicket = pending.markPosted()

    assertFalse(pending.claim(request, oldTicket))
    assertTrue(pending.claim(request, newTicket))
    assertFalse(pending.claim(request, newTicket))
    assertNull(pending.request)
  }

  @Test
  fun failedPostKeepsTheRequestAndANewerRequestSupersedesReconciliation() {
    val generations = IconRefreshGeneration()
    val pending = PendingIconRefresh()
    val reconciliation = IconRefreshRequest(
      generations.next(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)!!, 0,
    )
    pending.replace(reconciliation)
    val failedTicket = pending.markPosted()
    pending.postFailed(failedTicket)
    assertEquals(reconciliation, pending.request)
    assertFalse(pending.posted)

    val latest = IconRefreshRequest(
      generations.next(Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_NO)!!, 0,
    )
    pending.replace(latest)
    val latestTicket = pending.markPosted()
    assertFalse(pending.claim(reconciliation, failedTicket))
    assertTrue(pending.claim(latest, latestTicket))
    assertFalse(generations.isCurrent(reconciliation.generation))
  }

  @Test
  fun paletteAckReplacingAnAlreadyClaimedResourceJobRetainsResourcePacing() {
    val generations = IconRefreshGeneration()
    val pending = PendingIconRefresh()
    val resource = IconRefreshRequest(generations.nextFullRefresh()!!, 0)
    pending.replace(resource)
    assertTrue(pending.claim(resource, pending.markPosted()))
    assertNull(pending.request) // Resource work is running, no longer queued.

    val palette = IconRefreshRequest(
      generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!, 0,
    )
    pending.replace(palette)
    val ticket = pending.markPosted()
    generations.complete(resource.generation)
    assertFalse(generations.isCurrent(resource.generation))
    assertTrue(pending.claim(palette, ticket))
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(palette.generation))
  }

  @Test
  fun snapshotRetryAndRollbackKeepPaletteCauseUntilResourcesSupersedeIt() {
    val generations = IconRefreshGeneration()
    val pending = PendingIconRefresh()
    val palette = IconRefreshRequest(
      generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!, 0,
    )
    pending.replace(palette)
    assertTrue(pending.claim(palette, pending.markPosted()))
    val retry = palette.copy(attempt = 1)
    pending.replace(retry)
    val beforeRollback = pending.markPosted()
    pending.invalidatePost()
    val afterRollback = pending.markPosted()
    assertFalse(pending.claim(retry, beforeRollback))
    assertEquals(IconRefreshCause.PALETTE_ACK, generations.cause(retry.generation))

    val night = IconRefreshRequest(
      generations.next(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)!!, 0,
    )
    pending.replace(night)
    assertFalse(pending.claim(retry, afterRollback))
    assertFalse(generations.isCurrent(retry.generation))
    assertTrue(pending.claim(night, pending.markPosted()))
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(night.generation))
    assertTrue(generations.needsFullRefresh(night.generation))
  }

  @Test
  fun activeHookRejectsButActiveWorkerCanBeInterruptedForHandover() {
    val lifecycle = IconRefreshReloadLifecycle()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val hook = thread {
      lifecycle.trackHook {
        entered.countDown()
        release.await(2, TimeUnit.SECONDS)
      }
    }
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      var prepared = false
      assertFalse(lifecycle.retire(
        prepare = { prepared = true; true },
        await = { true },
        commit = {},
        rollback = {},
      ))
      assertFalse(prepared)
    } finally {
      release.countDown()
      hook.join(2_000)
    }
    assertFalse(hook.isAlive)

    synchronized(lifecycle.lock) { lifecycle.activeJobs = 1 }
    var prepared = false
    assertTrue(lifecycle.retire(
      prepare = { prepared = true; true },
      await = {
        synchronized(lifecycle.lock) { lifecycle.activeJobs = 0 }
        true
      },
      commit = {},
      rollback = {},
    ))
    assertTrue(prepared)
    assertTrue(lifecycle.retired)
  }

  @Test
  fun busyReporterRejectsWithoutStoppingWorkerOrRunningRollback() {
    val lifecycle = IconRefreshReloadLifecycle()
    var workerStopped = false
    var rolledBack = false
    assertFalse(lifecycle.retire(
      prepare = {
        val reporterStopped = false
        if (reporterStopped) workerStopped = true
        reporterStopped
      },
      await = { error("must not wait") },
      commit = { error("must not commit") },
      rollback = { rolledBack = true },
    ))
    assertFalse(workerStopped)
    assertFalse(rolledBack)
    assertFalse(lifecycle.retiring)
    assertFalse(lifecycle.retired)
  }

  @Test
  fun failedWaitRestoresReporterAndRequeuesPendingWhileOldResourcesRemainTracked() {
    val lifecycle = IconRefreshReloadLifecycle()
    val pending = PendingIconRefresh()
    val request = IconRefreshRequest(4, 1)
    pending.replace(request)
    val oldTicket = pending.markPosted()
    var reporterLive = true
    var waitingReporterCount = 0
    var waitingWorkerCount = 0
    var newTicket = -1L

    val result = lifecycle.retire(
      prepare = {
        reporterLive = false
        waitingReporterCount += 1
        waitingWorkerCount += 1
        pending.invalidatePost()
        true
      },
      await = { false },
      commit = { error("must not commit") },
      rollback = {
        reporterLive = true
        newTicket = pending.markPosted()
      },
    )

    assertFalse(result)
    assertTrue(reporterLive)
    assertEquals(1, waitingReporterCount)
    assertEquals(1, waitingWorkerCount)
    assertFalse(lifecycle.retired)
    assertFalse(pending.claim(request, oldTicket))
    assertTrue(pending.claim(request, newTicket))
  }

  @Test
  fun stateSaveFailureRollsBackAndALaterRetirementCanSucceed() {
    val lifecycle = IconRefreshReloadLifecycle()
    var rollbackCount = 0
    assertFalse(lifecycle.retire(
      prepare = { true },
      await = { true },
      commit = { error("state rejected") },
      rollback = { rollbackCount += 1 },
    ))
    assertEquals(1, rollbackCount)
    assertFalse(lifecycle.retiring)
    assertFalse(lifecycle.retired)

    assertTrue(lifecycle.retire(
      prepare = { true },
      await = { true },
      commit = {},
      rollback = { error("must not roll back") },
    ))
    assertTrue(lifecycle.retired)
  }

  @Test
  fun exceptionDuringPreparationRunsRollbackAndLeavesRuntimeUsable() {
    val lifecycle = IconRefreshReloadLifecycle()
    var reporterLive = true
    var rolledBack = false
    assertFalse(lifecycle.retire(
      prepare = {
        reporterLive = false
        error("shutdown failed")
      },
      await = { error("must not wait") },
      commit = { error("must not commit") },
      rollback = {
        reporterLive = true
        rolledBack = true
      },
    ))
    assertTrue(rolledBack)
    assertTrue(reporterLive)
    assertFalse(lifecycle.retiring)
    assertFalse(lifecycle.retired)
    lifecycle.trackHook { assertEquals(1, lifecycle.activeHooks) }
    assertEquals(0, lifecycle.activeHooks)
  }

  @Test
  fun slowShutdownAndRollbackDoNotHoldTheHookEntryLock() {
    val lifecycle = IconRefreshReloadLifecycle()
    val preparing = CountDownLatch(1)
    val continuePrepare = CountDownLatch(1)
    val recovering = CountDownLatch(1)
    val continueRecovery = CountDownLatch(1)
    val reload = thread {
      assertFalse(lifecycle.retire(
        prepare = { preparing.countDown(); continuePrepare.await(2, TimeUnit.SECONDS) },
        await = { false }, commit = { error("must not commit") },
        rollback = { recovering.countDown(); continueRecovery.await(2, TimeUnit.SECONDS) },
      ))
    }
    try {
      assertTrue(preparing.await(2, TimeUnit.SECONDS))
      val duringPrepare = CountDownLatch(1)
      val first = thread { lifecycle.trackHook { duringPrepare.countDown() } }
      assertTrue(duringPrepare.await(1, TimeUnit.SECONDS))
      first.join(1_000)
      continuePrepare.countDown()
      assertTrue(recovering.await(2, TimeUnit.SECONDS))
      val duringRecovery = CountDownLatch(1)
      val second = thread { lifecycle.trackHook { duringRecovery.countDown() } }
      assertTrue(duringRecovery.await(1, TimeUnit.SECONDS))
      second.join(1_000)
      assertFalse(lifecycle.retire({ true }, { true }, {}, {}))
    } finally {
      continuePrepare.countDown()
      continueRecovery.countDown()
      reload.join(2_000)
    }
    assertFalse(reload.isAlive)
    assertFalse(lifecycle.retired)
    assertTrue(lifecycle.retire({ true }, { true }, {}, {}))
  }

  @Test
  fun hookExceptionsDoNotLeaveActivityCountStuck() {
    val lifecycle = IconRefreshReloadLifecycle()
    assertThrows(IllegalStateException::class.java) {
      lifecycle.trackHook { error("hook failed") }
    }
    assertEquals(0, lifecycle.activeHooks)
    assertTrue(lifecycle.retire(
      prepare = { true },
      await = { true },
      commit = {},
      rollback = { error("must not roll back") },
    ))
  }

  @Test
  fun timedOutWorkerRemainsTrackedUntilItReallyTerminates() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val worker = thread(name = "KeiHomeTestWorker") {
      entered.countDown()
      release.await(2, TimeUnit.SECONDS)
    }
    val closing = RetiringIconRefreshThreads()
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      closing.track(worker)
      assertFalse(closing.awaitStopped(System.nanoTime()))
      closing.pruneStopped()
      assertTrue(closing.hasLiveThreads())

      val lifecycle = IconRefreshReloadLifecycle()
      var reporterStopped = false
      assertFalse(lifecycle.retire(
        prepare = {
          closing.pruneStopped()
          if (closing.hasLiveThreads()) false else {
            reporterStopped = true
            true
          }
        },
        await = { true },
        commit = {},
        rollback = {},
      ))
      assertFalse(reporterStopped)
      assertFalse(lifecycle.retired)
    } finally {
      release.countDown()
      worker.join(2_000)
    }
    assertFalse(worker.isAlive)
    assertTrue(closing.awaitStopped(System.nanoTime()))
    closing.pruneStopped()
    assertFalse(closing.hasLiveThreads())
  }

  @Test
  fun hookStartingDuringWaitRejectsRetirementAndRollsBack() {
    val lifecycle = IconRefreshReloadLifecycle()
    val awaiting = CountDownLatch(1)
    val finishAwait = CountDownLatch(1)
    val hookEntered = CountDownLatch(1)
    val finishHook = CountDownLatch(1)
    val retired = AtomicBoolean(true)
    val rolledBack = AtomicBoolean(false)
    val retirement = thread {
      retired.set(lifecycle.retire(
        prepare = { true },
        await = {
          awaiting.countDown()
          finishAwait.await(2, TimeUnit.SECONDS)
        },
        commit = { error("must not commit while hook is active") },
        rollback = { rolledBack.set(true) },
      ))
    }
    var hook: Thread? = null
    try {
      assertTrue(awaiting.await(2, TimeUnit.SECONDS))
      hook = thread {
        lifecycle.trackHook {
          hookEntered.countDown()
          finishHook.await(2, TimeUnit.SECONDS)
        }
      }
      assertTrue(hookEntered.await(2, TimeUnit.SECONDS))
      finishAwait.countDown()
      retirement.join(2_000)
      assertFalse(retirement.isAlive)
      assertFalse(retired.get())
      assertTrue(rolledBack.get())
      assertFalse(lifecycle.retired)
    } finally {
      finishAwait.countDown()
      finishHook.countDown()
      retirement.join(2_000)
      hook?.join(2_000)
    }
    assertNotNull(hook)
    assertFalse(hook.isAlive)
  }

  @Test
  fun oldSnapshotMayEnterAfterSuccessfulRetirement() {
    val lifecycle = IconRefreshReloadLifecycle()
    assertTrue(lifecycle.retire(
      prepare = { true },
      await = { true },
      commit = {},
      rollback = { error("must not roll back") },
    ))

    lifecycle.trackHook {
      assertTrue(lifecycle.retired)
      assertEquals(1, lifecycle.activeHooks)
    }
    assertEquals(0, lifecycle.activeHooks)
  }

  private fun xposed(onHook: () -> Unit): XposedInterface =
    Proxy.newProxyInstance(
      XposedInterface::class.java.classLoader,
      arrayOf(XposedInterface::class.java),
    ) { _, method, arguments ->
      check(method.name == "hook")
      onHook()
      FakeBuilder(arguments!![0] as Executable)
    } as XposedInterface

  private class FakeBuilder(private val target: Executable) : XposedInterface.HookBuilder {
    private var id: String? = null

    override fun setPriority(priority: Int): XposedInterface.HookBuilder = this

    override fun setExceptionMode(mode: XposedInterface.ExceptionMode): XposedInterface.HookBuilder =
      this

    override fun setId(id: String?): XposedInterface.HookBuilder = apply { this.id = id }

    override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle =
      FakeHandle(target, id)
  }

  private class FakeHandle(
    private val target: Executable,
    private val hookId: String?,
    private val failReplacement: Boolean = false,
  ) : XposedInterface.HookHandle {
    var replacements = 0
    var replacement: FakeHandle? = null

    override fun getExecutable(): Executable = target

    override fun getId(): String? = hookId

    override fun unhook() = Unit

    override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
      replacements += 1
      check(!failReplacement)
      return FakeHandle(target, hookId).also { replacement = it }
    }
  }

  private class Targets {
    fun first() = Unit
    fun second() = Unit
  }
}
