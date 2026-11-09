package os.kei.hyper.home.xposed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiThemeCompatibilityTest {
  @Test fun activeCallbackRejectsReloadWithoutDisablingRuntime() {
    val lifecycle = ThemeCompatibilityLifecycle(drainTimeoutMillis = 0, isMainThread = { false })
    assertTrue(lifecycle.enter())
    assertFalse(lifecycle.retire { error("Must not retire in-flight work") })
    lifecycle.leave()
    assertTrue(lifecycle.enter())
    lifecycle.leave()
    assertTrue(lifecycle.retire {})
    assertFalse(lifecycle.enter())
  }

  @Test fun finishedActionStillWaitsForItsOwnedThreadToExit() {
    val lifecycle = ThemeCompatibilityLifecycle()
    assertFalse(lifecycle.retire(workersStopped = { false }) {})
    assertTrue(lifecycle.retire(workersStopped = { true }) {})
    assertFalse(lifecycle.enter())
  }

  @Test fun failedStateTransferKeepsOldRuntimeAvailable() {
    val lifecycle = ThemeCompatibilityLifecycle()
    assertFalse(lifecycle.retire { throw IllegalStateException("Framework refused state") })
    assertTrue(lifecycle.enter())
    lifecycle.leave()
    assertTrue(lifecycle.retire {})
  }

  @Test fun externalCleanupDoesNotHoldTheCallbackAdmissionMonitor() {
    val lifecycle = ThemeCompatibilityLifecycle()
    val entered = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val retirement = executor.submit<Boolean> {
        lifecycle.retire {
          entered.countDown()
          check(finish.await(5, TimeUnit.SECONDS))
        }
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      assertFalse(executor.submit<Boolean> { lifecycle.enter() }.get(3, TimeUnit.SECONDS))
      assertFalse(lifecycle.retire { error("Must not retire twice concurrently") })
      finish.countDown()
      assertTrue(retirement.get(5, TimeUnit.SECONDS))
      assertFalse(lifecycle.enter())
    } finally {
      finish.countDown()
      executor.shutdownNow()
    }
  }

  @Test fun frameworkListenerLockCannotInvertWithRetirement() {
    val lifecycle = ThemeCompatibilityLifecycle()
    val frameworkLock = ReentrantLock()
    val listenerReady = CountDownLatch(1)
    val unregisterStarted = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val callback = executor.submit<Boolean> {
        frameworkLock.lock()
        try {
          listenerReady.countDown()
          check(unregisterStarted.await(5, TimeUnit.SECONDS))
          lifecycle.enter()
        } finally {
          frameworkLock.unlock()
        }
      }
      assertTrue(listenerReady.await(5, TimeUnit.SECONDS))
      val retirement = executor.submit<Boolean> {
        lifecycle.retire {
          unregisterStarted.countDown()
          check(frameworkLock.tryLock(2, TimeUnit.SECONDS))
          frameworkLock.unlock()
        }
      }
      assertFalse(callback.get(5, TimeUnit.SECONDS))
      assertTrue(retirement.get(5, TimeUnit.SECONDS))
    } finally {
      unregisterStarted.countDown()
      executor.shutdownNow()
    }
  }

  @Test fun workerReadinessCheckAlsoRunsOutsideAdmissionMonitor() {
    val lifecycle = ThemeCompatibilityLifecycle()
    val executor = Executors.newSingleThreadExecutor()
    try {
      var checks = 0
      var admitted: Boolean? = null
      assertFalse(lifecycle.retire(workersStopped = {
        checks++
        admitted = executor.submit<Boolean> { lifecycle.enter() }.get(3, TimeUnit.SECONDS)
        false
      }) { error("Worker still owns the runtime") })
      assertEquals(1, checks)
      assertEquals(false, admitted)
      assertTrue(lifecycle.enter())
      lifecycle.leave()
    } finally {
      executor.shutdownNow()
    }
  }

  @Test(timeout = 10_000) fun retirementClosesAdmissionAndWaitsForAsyncCallbackDrain() {
    val admissionClosed = CountDownLatch(1)
    val lifecycle = ThemeCompatibilityLifecycle(
      drainTimeoutMillis = 5_000,
      isMainThread = { admissionClosed.countDown(); false },
    )
    val callbackEntered = CountDownLatch(1)
    val releaseCallback = CountDownLatch(1)
    val callbackFinished = CountDownLatch(1)
    val workersChecked = CountDownLatch(1)
    val stateSaved = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val callback = executor.submit<Boolean> {
        check(lifecycle.enter())
        try {
          callbackEntered.countDown()
          check(releaseCallback.await(5, TimeUnit.SECONDS))
          callbackFinished.countDown()
          true
        } finally {
          lifecycle.leave()
        }
      }
      assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
      val retirement = executor.submit<Boolean> {
        lifecycle.retire(workersStopped = {
          assertEquals(0L, callbackFinished.count)
          assertFalse(lifecycle.enter())
          workersChecked.countDown()
          true
        }) { stateSaved.countDown() }
      }
      assertTrue(admissionClosed.await(5, TimeUnit.SECONDS))
      assertFalse(lifecycle.enter())
      assertFalse(lifecycle.retire { error("Must not retire twice concurrently") })
      assertFalse(retirement.isDone)
      assertEquals(1L, workersChecked.count)
      assertEquals(1L, stateSaved.count)

      releaseCallback.countDown()
      assertTrue(callback.get(5, TimeUnit.SECONDS))
      assertTrue(retirement.get(5, TimeUnit.SECONDS))
      assertEquals(0L, workersChecked.count)
      assertEquals(0L, stateSaved.count)
      assertFalse(lifecycle.enter())
    } finally {
      releaseCallback.countDown()
      executor.shutdownNow()
    }
  }

  @Test(timeout = 10_000) fun callbackDrainTimeoutReopensAdmissionAndAllowsRetry() {
    val lifecycle = ThemeCompatibilityLifecycle(
      drainTimeoutMillis = 100,
      isMainThread = { false },
    )
    val executor = Executors.newSingleThreadExecutor()
    var workerChecks = 0
    var saves = 0
    assertTrue(lifecycle.enter())
    try {
      val retirement = executor.submit<Boolean> {
        lifecycle.retire(workersStopped = { workerChecks++; true }) { saves++ }
      }
      assertFalse(retirement.get(5, TimeUnit.SECONDS))
      assertEquals(0, workerChecks)
      assertEquals(0, saves)
      assertTrue(lifecycle.enter())
      lifecycle.leave()
    } finally {
      lifecycle.leave()
      executor.shutdownNow()
    }
    assertTrue(lifecycle.retire(workersStopped = { true }) {})
    assertFalse(lifecycle.enter())
  }

  @Test(timeout = 10_000) fun mainThreadRefusesToWaitAndLeavesRuntimeUsable() {
    val lifecycle = ThemeCompatibilityLifecycle(
      drainTimeoutMillis = 60_000,
      isMainThread = { true },
    )
    var workerChecks = 0
    var saves = 0
    assertTrue(lifecycle.enter())
    assertFalse(lifecycle.retire(workersStopped = { workerChecks++; true }) { saves++ })
    assertEquals(0, workerChecks)
    assertEquals(0, saves)
    assertTrue(lifecycle.enter())
    lifecycle.leave()
    lifecycle.leave()
    assertTrue(lifecycle.retire {})
  }
}
