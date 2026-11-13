package os.kei.hyper.home.xposed

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises real worker loopers and controlled registration races without installing hooks. */
class CrossSurfaceIconCacheShutdownTest {
  @Test fun coldRequestsStayBoundedWithoutStartingResourcesAndDrainOnStart() {
    val context = RecordingContext()
    val readyCalls = java.util.concurrent.atomic.AtomicInteger()
    val cache = CrossSurfaceIconCache(context, maxEntries = 1,
      themeFile = java.io.File(context.cacheDir, "qa-absent-theme-${System.nanoTime()}"),
      paletteState = { 0L }, onReady = { readyCalls.incrementAndGet() })
    try {
      assertEquals(null, cache.get("com.example.first"))
      assertEquals(null, cache.get("com.example.last"))
      assertEquals(0, context.receiverRegistrations)
      assertFalse(worker(cache).isAlive)
      cache.start()
      awaitRegistration(cache)
      assertTrue(cache.isMissing("com.example.last"))
      assertFalse(cache.isMissing("com.example.first"))
      assertEquals(0, readyCalls.get())
    } finally {
      assertTrue(cache.stop())
    }
  }

  @Test fun firstFrameworkStopWaitsForCleanupAndWorkerExit() {
    val gate = Gate()
    val context = RecordingContext().apply { unregisterGate = gate }
    val cache = CrossSurfaceIconCache(context) {}
    val result = AtomicReference<Boolean>()
    val finished = CountDownLatch(1)
    val retirement = Thread {
      result.set(cache.stop())
      finished.countDown()
    }
    try {
      cache.start()
      awaitRegistration(cache)
      retirement.start()
      assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
      assertFalse("Retirement returned while cleanup was blocked", finished.await(100, TimeUnit.MILLISECONDS))
      assertEquals(null, cache.get("com.example.retired"))
      cache.invalidate()
      gate.release.countDown()
      assertTrue(finished.await(3, TimeUnit.SECONDS))
      assertEquals(true, result.get())
      assertFalse(worker(cache).isAlive)
      assertEquals(1, context.componentUnregistrations)
      assertEquals(1, context.receiverUnregistrations)
      assertTrue(cache.stop())
    } finally {
      gate.release.countDown()
      retirement.join(3_000)
      cache.stop()
    }
  }

  @Test(timeout = 8_000) fun timeoutFailsClosedUntilCleanupActuallyFinishes() {
    val gate = Gate()
    val context = RecordingContext().apply { unregisterGate = gate }
    val cache = CrossSurfaceIconCache(context) {}
    try {
      cache.start()
      awaitRegistration(cache)
      assertFalse(cache.stop())
      assertTrue(gate.entered.await(1, TimeUnit.SECONDS))
      assertTrue(worker(cache).isAlive)
      assertEquals(null, cache.get("com.example.retired"))
      cache.invalidate()
      gate.release.countDown()
      assertTrue(cache.stop())
      assertFalse(worker(cache).isAlive)
      assertEquals(1, context.componentUnregistrations)
    } finally {
      gate.release.countDown()
      cache.stop()
    }
  }

  @Test fun mainThreadStopDoesNotWaitForAnInFlightRegistration() {
    val gate = Gate()
    val context = RecordingContext().apply { registerGate = gate }
    val cache = CrossSurfaceIconCache(context) {}
    val returned = CountDownLatch(1)
    val result = AtomicReference<Boolean>()
    val mainCall = Thread {
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        result.set(cache.stop())
        returned.countDown()
      }
    }
    try {
      cache.start()
      assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
      mainCall.start()
      assertTrue("Main thread waited for the worker", returned.await(1, TimeUnit.SECONDS))
      assertEquals(false, result.get())
      gate.release.countDown()
      assertTrue(cache.stop())
      assertEquals(1, context.componentUnregistrations)
      assertEquals(0, context.receiverRegistrations)
    } finally {
      gate.release.countDown()
      mainCall.join(3_000)
      cache.stop()
    }
  }

  @Test fun workerStopRequestsCleanupWithoutWaitingForItself() {
    val cache = CrossSurfaceIconCache(RecordingContext()) {}
    val returned = CountDownLatch(1)
    val result = AtomicReference<Boolean>()
    try {
      cache.start()
      awaitRegistration(cache)
      assertTrue(Handler(worker(cache).looper).post {
        result.set(cache.stop())
        returned.countDown()
      })
      assertTrue("Worker waited for itself", returned.await(1, TimeUnit.SECONDS))
      assertEquals(false, result.get())
      assertTrue(cache.stop())
      assertFalse(worker(cache).isAlive)
    } finally {
      cache.stop()
    }
  }

  @Test fun partialRegistrationUnregistersOnlyAcquiredResources() {
    val context = RecordingContext().apply { failReceiverRegistration = true }
    val cache = CrossSurfaceIconCache(context) {}
    try {
      cache.start()
      awaitRegistration(cache)
      assertTrue(cache.stop())
      assertEquals(1, context.componentUnregistrations)
      assertEquals(0, context.receiverUnregistrations)
    } finally {
      cache.stop()
    }
  }

  @Test fun transientUnregistrationFailureRetainsOwnerAndCanRetry() {
    val context = RecordingContext().apply { failComponentUnregistration = true }
    val cache = CrossSurfaceIconCache(context) {}
    try {
      cache.start()
      awaitRegistration(cache)
      assertFalse(cache.stop())
      assertTrue(worker(cache).isAlive)
      assertEquals(1, context.receiverUnregistrations)
      assertFalse(cache.stop())
      context.failComponentUnregistration = false
      assertTrue(cache.stop())
      assertFalse(worker(cache).isAlive)
      assertEquals(1, context.receiverUnregistrations)
    } finally {
      context.failComponentUnregistration = false
      cache.stop()
    }
  }

  @Test fun frameworkLockWaitBeyondOldBudgetCanStillDrain() {
    val gate = Gate()
    val context = RecordingContext().apply { unregisterGate = gate }
    val cache = CrossSurfaceIconCache(context) {}
    val release = Thread {
      assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
      Thread.sleep(1_800)
      gate.release.countDown()
    }
    try {
      cache.start()
      awaitRegistration(cache)
      release.start()
      assertTrue(cache.stop())
      assertFalse(worker(cache).isAlive)
    } finally {
      gate.release.countDown()
      release.join(3_000)
      cache.stop()
    }
  }

  @Test fun interruptedRetirementPreservesInterruptAndRemainsRetryable() {
    val gate = Gate()
    val context = RecordingContext().apply { unregisterGate = gate }
    val cache = CrossSurfaceIconCache(context) {}
    val result = AtomicReference<Boolean>()
    val interrupted = AtomicBoolean()
    val retirement = Thread {
      Thread.currentThread().interrupt()
      result.set(cache.stop())
      interrupted.set(Thread.currentThread().isInterrupted)
    }
    try {
      cache.start()
      awaitRegistration(cache)
      retirement.start()
      retirement.join(3_000)
      assertFalse(retirement.isAlive)
      assertEquals(false, result.get())
      assertTrue(interrupted.get())
      gate.release.countDown()
      assertTrue(cache.stop())
    } finally {
      gate.release.countDown()
      retirement.join(3_000)
      cache.stop()
    }
  }

  @Test fun stopBeforeStartIsTerminalAndIdempotent() {
    val context = RecordingContext()
    val cache = CrossSurfaceIconCache(context) {}
    assertEquals(null, cache.get("com.example.pending"))
    assertTrue(cache.stop())
    cache.start()
    assertFalse(worker(cache).isAlive)
    assertEquals(null, cache.get("com.example.retired"))
    cache.invalidate()
    assertTrue(cache.stop())
    assertEquals(0, context.receiverRegistrations)
  }

  private fun worker(cache: CrossSurfaceIconCache): HandlerThread =
    CrossSurfaceIconCache::class.java.getDeclaredField("thread").apply { isAccessible = true }
      .get(cache) as HandlerThread

  private fun awaitRegistration(cache: CrossSurfaceIconCache) {
    val ready = CountDownLatch(1)
    assertTrue(Handler(worker(cache).looper).post { ready.countDown() })
    assertTrue(ready.await(3, TimeUnit.SECONDS))
  }

  private class Gate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    fun await() {
      entered.countDown()
      check(release.await(10, TimeUnit.SECONDS)) { "Test did not release worker" }
    }
  }

  private class RecordingContext : ContextWrapper(
    InstrumentationRegistry.getInstrumentation().targetContext,
  ) {
    var registerGate: Gate? = null
    var unregisterGate: Gate? = null
    var failReceiverRegistration = false
    var failComponentUnregistration = false
    @Volatile var componentUnregistrations = 0
    @Volatile var receiverRegistrations = 0
    @Volatile var receiverUnregistrations = 0

    override fun registerComponentCallbacks(callback: ComponentCallbacks) {
      registerGate?.await()
    }

    override fun unregisterComponentCallbacks(callback: ComponentCallbacks) {
      unregisterGate?.await()
      componentUnregistrations += 1
      check(!failComponentUnregistration) { "Test component unregister failure" }
    }

    override fun registerReceiver(
      receiver: BroadcastReceiver?,
      filter: IntentFilter,
      broadcastPermission: String?,
      scheduler: Handler?,
      flags: Int,
    ): Intent? {
      check(!failReceiverRegistration) { "Test receiver register failure" }
      receiverRegistrations += 1
      return null
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver) {
      receiverUnregistrations += 1
    }
  }
}
