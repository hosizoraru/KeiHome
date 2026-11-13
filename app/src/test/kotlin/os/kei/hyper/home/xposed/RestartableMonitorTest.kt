package os.kei.hyper.home.xposed

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartableMonitorTest {
  @Test
  fun failedRegistrationReleasesRetainOwnershipAndRetryOnlyFailures() {
    val registrations = MonitorRegistrations()
    val failures = mutableListOf<Exception>()
    var fail = true
    var owner: Any? = Any()
    var failedAttempts = 0
    var otherReleases = 0
    registrations.own {
      failedAttempts++
      if (fail) throw IllegalStateException("unregister failed")
      owner = null
    }
    registrations.own { otherReleases++ }
    assertFalse(registrations.release(failures::add))
    assertFalse(registrations.complete)
    assertTrue(owner != null)
    assertEquals(1, failedAttempts)
    assertEquals(1, otherReleases)
    assertEquals("unregister failed", failures.single().message)

    fail = false
    assertTrue(registrations.release(failures::add))
    assertTrue(registrations.complete)
    assertEquals(null, owner)
    assertEquals(2, failedAttempts)
    assertEquals(1, otherReleases)
    assertTrue(registrations.release(failures::add))
    assertEquals(2, failedAttempts)
    assertEquals(1, failures.size)
  }

  @Test
  fun throwingDiagnosticsRetainFailedOwnershipAndDoNotAbortOtherReleases() {
    val registrations = MonitorRegistrations()
    var fail = true
    var owner: Any? = Any()
    var failedAttempts = 0
    var otherReleases = 0
    var diagnostics = 0
    registrations.own {
      failedAttempts++
      if (fail) throw IllegalStateException("unregister failed")
      owner = null
    }
    registrations.own { otherReleases++ }
    assertFalse(registrations.release {
      diagnostics++
      throw AssertionError("diagnostic failed")
    })
    assertFalse(registrations.complete)
    assertTrue(owner != null)
    assertEquals(1, otherReleases)
    assertEquals(1, diagnostics)

    fail = false
    assertTrue(registrations.release { throw AssertionError("unexpected failure", it) })
    assertTrue(registrations.complete)
    assertEquals(null, owner)
    assertEquals(2, failedAttempts)
    assertEquals(1, otherReleases)
  }

  @Test
  fun deadWorkerWithFailedCleanupCannotRetireAndOnlyRetriesOnExplicitStop() {
    val live = AtomicInteger()
    val worker = CleanupWorker(exitOnFailure = true, live = live)
    val monitor = RestartableMonitor<Int>({ worker }, { throw AssertionError(it) })
    try {
      monitor.start(1)
      assertTrue(worker.started.await(2, TimeUnit.SECONDS))
      monitor.stop()
      worker.thread.join(2_000)
      assertFalse(worker.thread.isAlive)
      assertFalse(worker.cleanupComplete)
      repeat(3) { assertFalse(monitor.awaitStopped(deadline(20))) }
      monitor.withWorker { throw AssertionError("Closed worker accepted a request") }
      assertEquals(1, worker.attempts.get())
      assertEquals(1, live.get())

      worker.allowCleanup.set(true)
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertEquals(2, worker.attempts.get())
      assertEquals(0, live.get())
      monitor.stop()
      assertEquals(2, worker.attempts.get())
    } finally {
      worker.allowCleanup.set(true)
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun rollbackStartRetriesCleanupBeforeReplacingDeadWorkerOwnership() {
    val created = CopyOnWriteArrayList<CleanupWorker>()
    val live = AtomicInteger()
    val monitor = RestartableMonitor<Int>(
      createWorker = {
        assertEquals("Previous registrations must be released before replacement", 0, live.get())
        CleanupWorker(exitOnFailure = true, live = live).also(created::add)
      },
      onFailure = { throw AssertionError(it) },
    )
    try {
      monitor.start(1)
      val first = created.single()
      monitor.stop()
      first.thread.join(2_000)
      assertFalse(first.thread.isAlive)
      monitor.start(2)
      assertEquals(1, created.size)
      assertEquals(2, first.attempts.get())
      assertEquals(1, live.get())

      first.allowCleanup.set(true)
      monitor.start(3)
      assertEquals(2, created.size)
      assertTrue(created[1].started.await(2, TimeUnit.SECONDS))
      assertEquals(3, first.attempts.get())
      assertEquals(1, live.get())
    } finally {
      created.forEach { it.allowCleanup.set(true) }
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertEquals(0, live.get())
    }
  }

  @Test
  fun rollbackStartRetriesCleanupOnIdleClosedWorkerWithoutDuplicatingObservers() {
    val created = CopyOnWriteArrayList<CleanupWorker>()
    val live = AtomicInteger()
    val secondStarted = CountDownLatch(1)
    val monitor = RestartableMonitor<Int>(
      createWorker = { value ->
        assertEquals(0, live.get())
        CleanupWorker(exitOnFailure = false, live = live,
          notified = if (value == 2) secondStarted else null).also(created::add)
      },
      onFailure = { throw AssertionError(it) },
    )
    try {
      monitor.start(1)
      val first = created.single()
      assertTrue(first.started.await(2, TimeUnit.SECONDS))
      monitor.stop()
      assertTrue(first.thread.isAlive)
      assertFalse(monitor.awaitStopped(deadline(20)))
      assertEquals(1, first.attempts.get())
      first.allowCleanup.set(true)
      monitor.start(2)
      assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
      assertFalse(first.thread.isAlive)
      assertTrue(first.cleanupComplete)
      assertEquals(2, first.attempts.get())
      assertEquals(2, created.size)
      assertEquals(1, live.get())
    } finally {
      created.forEach { it.allowCleanup.set(true) }
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertEquals(0, live.get())
    }
  }

  @Test
  fun rejectedReloadResumesOnlyAfterThePreviousWorkerExits() {
    val created = CopyOnWriteArrayList<FakeWorker>()
    val failures = CopyOnWriteArrayList<Throwable>()
    val secondStarted = CountDownLatch(1)
    val monitor = RestartableMonitor<Int>(
      createWorker = { value ->
        FakeWorker(value, if (value == 2) secondStarted else null).also(created::add)
      },
      onFailure = failures::add,
    )
    try {
      monitor.start(1)
      assertTrue(created[0].started.await(2, TimeUnit.SECONDS))
      monitor.stop()
      assertFalse(monitor.awaitStopped(deadline(20)))

      monitor.start(2)
      assertEquals(1, created.size)
      created[0].release.countDown()
      assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
      assertEquals(listOf(1, 2), created.map(FakeWorker::value))
      assertFalse(created[0].thread.isAlive)
      assertTrue(failures.isEmpty())
    } finally {
      monitor.stop()
      created.forEach { it.release.countDown() }
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun laterStopCancelsADeferredRestart() {
    val created = CopyOnWriteArrayList<FakeWorker>()
    val monitor = RestartableMonitor<Int>(
      createWorker = { FakeWorker(it).also(created::add) },
      onFailure = { throw AssertionError(it) },
    )
    try {
      monitor.start(1)
      assertTrue(created[0].started.await(2, TimeUnit.SECONDS))
      monitor.stop()
      monitor.start(2)
      monitor.stop()
      created[0].release.countDown()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertEquals(listOf(1), created.map(FakeWorker::value))
    } finally {
      monitor.stop()
      created.forEach { it.release.countDown() }
    }
  }

  @Test
  fun requestsSkipWorkerUntilStartReturns() {
    val allowStartReturn = CountDownLatch(1)
    val worker = FakeWorker(1, afterStart = { allowStartReturn.await() })
    val monitor = RestartableMonitor<Int>({ worker }, { throw AssertionError(it) })
    val requests = AtomicInteger()
    val starting = inBackground { monitor.start(1) }
    try {
      assertTrue(worker.started.await(2, TimeUnit.SECONDS))
      inBackground { monitor.withWorker { requests.incrementAndGet() } }.result()
      assertEquals(0, requests.get())

      allowStartReturn.countDown()
      starting.result()
      monitor.withWorker {
        assertSame(worker, it)
        requests.incrementAndGet()
      }
      assertEquals(1, requests.get())
    } finally {
      allowStartReturn.countDown()
      starting.result()
      monitor.stop()
      worker.release.countDown()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun stopDuringStartWaitsForStartAndNeverReportsStoppedEarly() {
    val allowThreadStart = CountDownLatch(1)
    val allowStartReturn = CountDownLatch(1)
    val worker = FakeWorker(
      1,
      beforeStart = { allowThreadStart.await() },
      afterStart = { allowStartReturn.await() },
    )
    val monitor = RestartableMonitor<Int>({ worker }, { throw AssertionError(it) })
    val requests = AtomicInteger()
    val starting = inBackground { monitor.start(1) }
    try {
      assertTrue(worker.startEntered.await(2, TimeUnit.SECONDS))
      inBackground { monitor.withWorker { requests.incrementAndGet() } }.result()
      inBackground { monitor.stop() }.result()
      inBackground { monitor.stop() }.result()
      assertEquals(0, requests.get())
      assertEquals(0, worker.stopCalls.get())
      assertFalse(worker.thread.isAlive)
      assertFalse(inBackground { monitor.awaitStopped(deadline(20)) }.result())

      allowThreadStart.countDown()
      assertTrue(worker.started.await(2, TimeUnit.SECONDS))
      worker.release.countDown()
      worker.thread.join(2_000)
      assertFalse(worker.thread.isAlive)
      assertEquals(0, worker.stopCalls.get())
      assertFalse(inBackground { monitor.awaitStopped(deadline(20)) }.result())

      allowStartReturn.countDown()
      starting.result()
      assertTrue(worker.closed)
      assertEquals(1, worker.stopCalls.get())
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    } finally {
      allowThreadStart.countDown()
      allowStartReturn.countDown()
      starting.result()
      monitor.stop()
      worker.release.countDown()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun stopDuringCreationRetiresReservedWorkerBeforeLatestRestart() {
    val creating = CountDownLatch(1)
    val allowCreation = CountDownLatch(1)
    val restarted = CountDownLatch(1)
    val created = CopyOnWriteArrayList<FakeWorker>()
    val failures = CopyOnWriteArrayList<Throwable>()
    val monitor = RestartableMonitor<Int>(
      createWorker = { value ->
        if (value == 1) {
          creating.countDown()
          allowCreation.await()
        }
        FakeWorker(value, if (value == 3) restarted else null).also(created::add)
      },
      onFailure = failures::add,
    )
    val starting = inBackground { monitor.start(1) }
    try {
      assertTrue(creating.await(2, TimeUnit.SECONDS))
      inBackground { monitor.stop() }.result()
      assertFalse(inBackground { monitor.awaitStopped(deadline(20)) }.result())
      inBackground { monitor.start(2) }.result()
      inBackground { monitor.start(3) }.result()
      assertTrue(created.isEmpty())

      allowCreation.countDown()
      starting.result()
      assertEquals(listOf(1), created.map(FakeWorker::value))
      assertTrue(created[0].closed)
      assertEquals(1, created[0].stopCalls.get())
      created[0].release.countDown()
      assertTrue(restarted.await(2, TimeUnit.SECONDS))
      assertEquals(listOf(1, 3), created.map(FakeWorker::value))
      assertFalse(created[0].thread.isAlive)
      assertTrue(failures.isEmpty())
    } finally {
      allowCreation.countDown()
      inBackground { monitor.stop() }.result()
      starting.result()
      created.forEach { it.release.countDown() }
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun blockedStopKeepsOwnershipAfterThreadExitWithoutBlockingSnapshots() {
    val allowStop = CountDownLatch(1)
    val created = CopyOnWriteArrayList<FakeWorker>()
    val monitor = RestartableMonitor<Int>(
      createWorker = { value ->
        FakeWorker(value, beforeStop = { if (value == 1) allowStop.await() })
          .also(created::add)
      },
      onFailure = { throw AssertionError(it) },
    )
    monitor.start(1)
    val first = created.single()
    val requests = AtomicInteger()
    val stopping = inBackground { monitor.stop() }
    try {
      assertTrue(first.stopEntered.await(2, TimeUnit.SECONDS))
      inBackground { monitor.withWorker { requests.incrementAndGet() } }.result()
      assertEquals(0, requests.get())
      assertFalse(inBackground { monitor.awaitStopped(deadline(20)) }.result())
      inBackground { monitor.start(2) }.result()
      assertEquals(listOf(1), created.map(FakeWorker::value))

      inBackground { monitor.stop() }.result()
      first.release.countDown()
      first.thread.join(2_000)
      assertFalse(first.thread.isAlive)
      assertFalse(inBackground { monitor.awaitStopped(deadline(20)) }.result())
      inBackground { monitor.start(3) }.result()
      assertEquals(listOf(1), created.map(FakeWorker::value))

      allowStop.countDown()
      stopping.result()
      assertEquals(listOf(1, 3), created.map(FakeWorker::value))
      assertTrue(created[1].started.await(2, TimeUnit.SECONDS))
      assertEquals(1, first.stopCalls.get())
    } finally {
      allowStop.countDown()
      stopping.result()
      monitor.stop()
      created.forEach { it.release.countDown() }
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun rejectedReloadStartupRemainsTrackedAndAllowsConcurrentStop() {
    val restarting = CountDownLatch(1)
    val allowRestart = CountDownLatch(1)
    val created = CopyOnWriteArrayList<FakeWorker>()
    val failures = CopyOnWriteArrayList<Throwable>()
    val monitor = RestartableMonitor<Int>(
      createWorker = { value ->
        FakeWorker(value, beforeStart = {
          if (value == 2) {
            restarting.countDown()
            allowRestart.await()
          }
        }).also(created::add)
      },
      onFailure = failures::add,
    )
    try {
      monitor.start(1)
      monitor.stop()
      monitor.start(2)
      created[0].release.countDown()
      assertTrue(restarting.await(2, TimeUnit.SECONDS))
      assertEquals(listOf(1, 2), created.map(FakeWorker::value))
      assertFalse(created[0].thread.isAlive)
      assertFalse(created[1].thread.isAlive)

      val requests = AtomicInteger()
      inBackground { monitor.withWorker { requests.incrementAndGet() } }.result()
      inBackground { monitor.stop() }.result()
      assertEquals(0, requests.get())
      assertEquals(0, created[1].stopCalls.get())
      assertFalse(inBackground { monitor.awaitStopped(deadline(20)) }.result())

      allowRestart.countDown()
      created[1].release.countDown()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertTrue(created[1].closed)
      assertEquals(1, created[1].stopCalls.get())
      assertTrue(failures.isEmpty())
    } finally {
      allowRestart.countDown()
      inBackground { monitor.stop() }.result()
      created.forEach { it.release.countDown() }
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun blockedWorkerActionDoesNotHoldLifecycleLock() {
    val entered = CountDownLatch(1)
    val allowAction = CountDownLatch(1)
    val worker = FakeWorker(1)
    val monitor = RestartableMonitor<Int>({ worker }, { throw AssertionError(it) })
    monitor.start(1)
    val action = inBackground {
      monitor.withWorker {
        entered.countDown()
        allowAction.await()
      }
    }
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      inBackground { monitor.stop() }.result()
      assertTrue(worker.closed)
      assertFalse(inBackground { monitor.awaitStopped(deadline(20)) }.result())
    } finally {
      allowAction.countDown()
      action.result()
      monitor.stop()
      worker.release.countDown()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  private fun <T> inBackground(action: () -> T): FutureTask<T> =
    FutureTask<T> { action() }.also {
      Thread(it, "test-monitor-call").apply { isDaemon = true }.start()
    }

  private fun <T> FutureTask<T>.result(): T = get(2, TimeUnit.SECONDS)

  private fun deadline(timeoutMillis: Long): Long =
    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

  private class CleanupWorker(
    private val exitOnFailure: Boolean,
    live: AtomicInteger,
    private val notified: CountDownLatch? = null,
  ) : MonitorWorker {
    private val registrations = MonitorRegistrations()
    private val exit = CountDownLatch(1)
    val started = CountDownLatch(1)
    val allowCleanup = AtomicBoolean()
    val attempts = AtomicInteger()
    val failures = mutableListOf<Exception>()
    override val thread = Thread({
      started.countDown()
      notified?.countDown()
      exit.await()
    }, "test-monitor-cleanup").apply { isDaemon = true }
    @Volatile override var closed = false
      private set
    override val cleanupComplete: Boolean get() = closed && registrations.complete

    init {
      live.incrementAndGet()
      registrations.own {
        attempts.incrementAndGet()
        if (!allowCleanup.get()) throw IllegalStateException("unregister failed")
        live.decrementAndGet()
      }
    }

    override fun start() = thread.start()

    override fun stop() {
      if (closed && registrations.complete) return
      closed = true
      val complete = registrations.release(failures::add)
      if (complete || exitOnFailure) exit.countDown()
    }
  }

  private class FakeWorker(
    val value: Int,
    private val notified: CountDownLatch? = null,
    private val beforeStart: () -> Unit = {},
    private val afterStart: () -> Unit = {},
    private val beforeStop: () -> Unit = {},
  ) : MonitorWorker {
    val startEntered = CountDownLatch(1)
    val started = CountDownLatch(1)
    val stopEntered = CountDownLatch(1)
    val stopCalls = AtomicInteger()
    val release = CountDownLatch(1)
    override val thread = Thread({
      started.countDown()
      notified?.countDown()
      release.await()
    }, "test-monitor-$value")
    @Volatile override var closed = false
      private set

    override fun start() {
      startEntered.countDown()
      beforeStart()
      thread.start()
      afterStart()
    }

    override fun stop() {
      stopCalls.incrementAndGet()
      stopEntered.countDown()
      beforeStop()
      closed = true
    }
  }
}
