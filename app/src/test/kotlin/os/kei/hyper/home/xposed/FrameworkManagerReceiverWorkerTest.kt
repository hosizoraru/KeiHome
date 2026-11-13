package os.kei.hyper.home.xposed

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameworkManagerReceiverWorkerTest {
  @Test
  fun coldStopDoesNotWaitForRegistrationAndCleansLateToken() {
    val entered = CountDownLatch(1)
    val allowRegistration = CountDownLatch(1)
    val cleanupEntered = CountDownLatch(1)
    val allowCleanup = CountDownLatch(1)
    val failures = CopyOnWriteArrayList<Throwable>()
    val token = Any()
    val threads = CopyOnWriteArrayList<Thread>()
    val worker = FrameworkManagerRegistrationWorker(
      token = token,
      register = {
        threads += Thread.currentThread()
        entered.countDown()
        allowRegistration.await()
      },
      unregister = {
        assertSame(token, it)
        threads += Thread.currentThread()
        cleanupEntered.countDown()
        allowCleanup.await()
      },
      onFailure = failures::add,
    )
    val monitor = RestartableMonitor<Unit>({ worker }, failures::add)
    try {
      inBackground { monitor.start(Unit) }.get(2, TimeUnit.SECONDS)
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      assertFalse(worker.accepting)
      inBackground { monitor.stop() }.get(2, TimeUnit.SECONDS)
      assertTrue(worker.closed)
      assertFalse(monitor.awaitStopped(deadline(20)))

      allowRegistration.countDown()
      assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
      assertFalse(worker.accepting)
      assertFalse(worker.cleanupComplete)
      assertFalse(monitor.awaitStopped(deadline(20)))
      allowCleanup.countDown()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertTrue(worker.cleanupComplete)
      assertEquals(listOf(worker.thread, worker.thread), threads)
      assertTrue(failures.isEmpty())
    } finally {
      allowRegistration.countDown()
      allowCleanup.countDown()
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun rollbackWaitsForActualUnregisterWithoutOverlappingRegistrations() {
    val firstRegistered = CountDownLatch(1)
    val cleanupEntered = CountDownLatch(1)
    val allowCleanup = CountDownLatch(1)
    val secondRegistered = CountDownLatch(1)
    val live = AtomicInteger()
    val maximumLive = AtomicInteger()
    val workers = CopyOnWriteArrayList<FrameworkManagerRegistrationWorker<Int>>()
    val failures = CopyOnWriteArrayList<Throwable>()
    val monitor = RestartableMonitor<Int>({ value ->
      FrameworkManagerRegistrationWorker(
        token = value,
        register = {
          val count = live.incrementAndGet()
          maximumLive.updateAndGet { maxOf(it, count) }
          if (value == 1) firstRegistered.countDown() else secondRegistered.countDown()
        },
        unregister = {
          if (it == 1) {
            cleanupEntered.countDown()
            allowCleanup.await()
          }
          live.decrementAndGet()
        },
        onFailure = failures::add,
      ).also(workers::add)
    }, failures::add)
    try {
      monitor.start(1)
      assertTrue(firstRegistered.await(2, TimeUnit.SECONDS))
      inBackground { monitor.stop() }.get(2, TimeUnit.SECONDS)
      assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
      assertFalse(workers[0].accepting)
      assertFalse(monitor.awaitStopped(deadline(20)))
      inBackground { monitor.start(2) }.get(2, TimeUnit.SECONDS)
      assertEquals(1, workers.size)
      assertEquals(1, live.get())
      allowCleanup.countDown()
      assertTrue(secondRegistered.await(2, TimeUnit.SECONDS))
      assertFalse(workers[0].thread.isAlive)
      assertTrue(workers[0].cleanupComplete)
      assertEquals(1, maximumLive.get())
      assertTrue(failures.isEmpty())
    } finally {
      allowCleanup.countDown()
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertEquals(0, live.get())
    }
  }

  @Test
  fun failedCleanupKeepsTheSameTokenAndWorkerUntilExplicitRetry() {
    val registered = CountDownLatch(1)
    val cleanupFailed = CountDownLatch(1)
    val attempts = AtomicInteger()
    val failures = CopyOnWriteArrayList<Throwable>()
    val token = Any()
    val cleanupThreads = CopyOnWriteArrayList<Thread>()
    val worker = FrameworkManagerRegistrationWorker(
      token = token,
      register = { registered.countDown() },
      unregister = {
        assertSame(token, it)
        cleanupThreads += Thread.currentThread()
        if (attempts.incrementAndGet() == 1)
          throw IllegalArgumentException("unknown unregister failure")
      },
      onFailure = { failures += it; cleanupFailed.countDown() },
    )
    val monitor = RestartableMonitor<Unit>({ worker }, failures::add)
    try {
      monitor.start(Unit)
      assertTrue(registered.await(2, TimeUnit.SECONDS))
      monitor.stop()
      assertTrue(cleanupFailed.await(2, TimeUnit.SECONDS))
      assertFalse(worker.accepting)
      assertFalse(worker.cleanupComplete)
      assertTrue(worker.thread.isAlive)
      assertFalse(monitor.awaitStopped(deadline(20)))
      assertEquals(1, attempts.get())
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertEquals(2, attempts.get())
      assertEquals(listOf(worker.thread, worker.thread), cleanupThreads)
      assertEquals(1, failures.size)
    } finally {
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun rollbackCannotDuplicateAStillFailingRegistration() {
    val registered = CountDownLatch(1)
    val firstFailure = CountDownLatch(1)
    val secondFailure = CountDownLatch(1)
    val replacementRegistered = CountDownLatch(1)
    val attempts = AtomicInteger()
    val live = AtomicInteger()
    val maximumLive = AtomicInteger()
    val workers = CopyOnWriteArrayList<FrameworkManagerRegistrationWorker<Int>>()
    val failures = CopyOnWriteArrayList<Throwable>()
    val monitor = RestartableMonitor<Int>({ value ->
      FrameworkManagerRegistrationWorker(
        token = value,
        register = {
          val count = live.incrementAndGet()
          maximumLive.updateAndGet { maxOf(it, count) }
          if (value == 1) registered.countDown() else replacementRegistered.countDown()
        },
        unregister = {
          if (it == 1 && attempts.incrementAndGet() <= 2) error("cleanup still unavailable")
          live.decrementAndGet()
        },
        onFailure = {
          failures += it
          if (attempts.get() == 1) firstFailure.countDown() else secondFailure.countDown()
        },
      ).also(workers::add)
    }, failures::add)
    try {
      monitor.start(1)
      assertTrue(registered.await(2, TimeUnit.SECONDS))
      monitor.stop()
      assertTrue(firstFailure.await(2, TimeUnit.SECONDS))
      monitor.start(2)
      assertTrue(secondFailure.await(2, TimeUnit.SECONDS))
      assertEquals(1, workers.size)
      assertEquals(1, live.get())
      assertFalse(workers[0].cleanupComplete)
      assertFalse(monitor.awaitStopped(deadline(20)))
      monitor.start(2)
      assertTrue(replacementRegistered.await(2, TimeUnit.SECONDS))
      assertFalse(workers[0].thread.isAlive)
      assertTrue(workers[0].cleanupComplete)
      assertEquals(1, maximumLive.get())
      assertEquals(2, failures.size)
    } finally {
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertEquals(0, live.get())
    }
  }

  @Test
  fun stopBeforeWorkerStartSkipsRegistration() {
    val registrations = AtomicInteger()
    val cleanups = AtomicInteger()
    val worker = FrameworkManagerRegistrationWorker(
      token = Any(),
      register = { registrations.incrementAndGet() },
      unregister = { cleanups.incrementAndGet() },
      onFailure = { throw AssertionError(it) },
    )
    worker.stop()
    worker.start()
    worker.thread.join(2_000)
    assertFalse(worker.thread.isAlive)
    assertFalse(worker.accepting)
    assertTrue(worker.cleanupComplete)
    assertEquals(0, registrations.get())
    assertEquals(0, cleanups.get())
  }

  @Test
  fun registrationFailureStillCleansItsPreconstructedTokenBeforeRetirement() {
    val failed = CountDownLatch(1)
    val cleanups = AtomicInteger()
    val failures = CopyOnWriteArrayList<Throwable>()
    val token = Any()
    val worker = FrameworkManagerRegistrationWorker(
      token = token,
      register = { error("registration unavailable") },
      unregister = { assertSame(token, it); cleanups.incrementAndGet() },
      onFailure = { failures += it; failed.countDown() },
    )
    val monitor = RestartableMonitor<Unit>({ worker }, failures::add)
    try {
      monitor.start(Unit)
      assertTrue(failed.await(2, TimeUnit.SECONDS))
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertTrue(worker.closed)
      assertFalse(worker.accepting)
      assertTrue(worker.cleanupComplete)
      assertEquals(1, cleanups.get())
      assertEquals(1, failures.size)
    } finally {
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun installedCallbackWithFailedReplyStaysUnreadyUntilOwnedCleanupFinishes() {
    val installed = AtomicBoolean()
    val registrationEntered = CountDownLatch(1)
    val allowReply = CountDownLatch(1)
    val cleanupEntered = CountDownLatch(1)
    val allowCleanup = CountDownLatch(1)
    val failures = CopyOnWriteArrayList<Throwable>()
    val threads = CopyOnWriteArrayList<Thread>()
    val token = Any()
    val worker = FrameworkManagerRegistrationWorker(
      token = token,
      register = {
        assertSame(token, it)
        threads += Thread.currentThread()
        installed.set(true)
        registrationEntered.countDown()
        allowReply.await()
        error("reply lost after callback installation")
      },
      unregister = {
        assertSame(token, it)
        threads += Thread.currentThread()
        cleanupEntered.countDown()
        allowCleanup.await()
        installed.set(false)
      },
      onFailure = failures::add,
    )
    val monitor = RestartableMonitor<Unit>({ worker }, failures::add)
    try {
      monitor.start(Unit)
      assertTrue(registrationEntered.await(2, TimeUnit.SECONDS))
      assertTrue(installed.get())
      assertFalse(worker.accepting)
      allowReply.countDown()
      assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
      assertTrue(worker.closed)
      assertFalse(worker.accepting)
      assertFalse(worker.cleanupComplete)
      inBackground { monitor.stop() }.get(2, TimeUnit.SECONDS)
      assertFalse(monitor.awaitStopped(deadline(20)))
      allowCleanup.countDown()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertFalse(installed.get())
      assertTrue(worker.cleanupComplete)
      assertEquals(listOf(worker.thread, worker.thread), threads)
      assertEquals(1, failures.size)
    } finally {
      allowReply.countDown()
      allowCleanup.countDown()
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun failedReplyAndCleanupRefuseRetirementUntilTheSameTokenIsReleased() {
    val installed = AtomicBoolean()
    val registrationEntered = CountDownLatch(1)
    val allowReply = CountDownLatch(1)
    val cleanupFailed = CountDownLatch(1)
    val allowCleanup = AtomicBoolean()
    val attempts = AtomicInteger()
    val failures = CopyOnWriteArrayList<Throwable>()
    val token = Any()
    val cleanupThreads = CopyOnWriteArrayList<Thread>()
    val worker = FrameworkManagerRegistrationWorker(
      token = token,
      register = {
        assertSame(token, it)
        installed.set(true)
        registrationEntered.countDown()
        allowReply.await()
        error("reply lost after callback installation")
      },
      unregister = {
        assertSame(token, it)
        cleanupThreads += Thread.currentThread()
        attempts.incrementAndGet()
        if (!allowCleanup.get()) error("cleanup refused")
        installed.set(false)
      },
      onFailure = {
        failures += it
        if (it.message == "cleanup refused") cleanupFailed.countDown()
      },
    )
    val monitor = RestartableMonitor<Unit>({ worker }, failures::add)
    try {
      monitor.start(Unit)
      assertTrue(registrationEntered.await(2, TimeUnit.SECONDS))
      monitor.stop()
      allowReply.countDown()
      assertTrue(cleanupFailed.await(2, TimeUnit.SECONDS))
      assertTrue(installed.get())
      assertFalse(worker.accepting)
      assertFalse(worker.cleanupComplete)
      assertTrue(worker.thread.isAlive)
      assertFalse(monitor.awaitStopped(deadline(20)))
      assertEquals(1, attempts.get())
      allowCleanup.set(true)
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertFalse(installed.get())
      assertTrue(worker.cleanupComplete)
      assertEquals(2, attempts.get())
      assertEquals(listOf(worker.thread, worker.thread), cleanupThreads)
      assertEquals(2, failures.size)
    } finally {
      allowReply.countDown()
      allowCleanup.set(true)
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun repeatedStartDoesNotRegisterTwice() {
    val entered = CountDownLatch(1)
    val allowRegistration = CountDownLatch(1)
    val workers = CopyOnWriteArrayList<FrameworkManagerRegistrationWorker<Int>>()
    val failures = CopyOnWriteArrayList<Throwable>()
    val monitor = RestartableMonitor<Int>({ value ->
      FrameworkManagerRegistrationWorker(
        token = value,
        register = { entered.countDown(); allowRegistration.await() },
        unregister = {},
        onFailure = failures::add,
      ).also(workers::add)
    }, failures::add)
    try {
      monitor.start(1)
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      monitor.start(1)
      monitor.start(2)
      assertEquals(1, workers.size)
    } finally {
      monitor.stop()
      allowRegistration.countDown()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
    assertTrue(failures.isEmpty())
  }

  @Test
  fun diagnosticFailureDoesNotDropAnOutstandingCleanupToken() {
    val registered = CountDownLatch(1)
    val reported = CountDownLatch(1)
    val attempts = AtomicInteger()
    val worker = FrameworkManagerRegistrationWorker(
      token = Any(),
      register = { registered.countDown() },
      unregister = { if (attempts.incrementAndGet() == 1) error("cleanup failed") },
      onFailure = { reported.countDown(); throw AssertionError("diagnostic failed") },
    )
    val monitor = RestartableMonitor<Unit>({ worker }, { throw AssertionError(it) })
    try {
      monitor.start(Unit)
      assertTrue(registered.await(2, TimeUnit.SECONDS))
      monitor.stop()
      assertTrue(reported.await(2, TimeUnit.SECONDS))
      assertFalse(monitor.awaitStopped(deadline(20)))
      assertTrue(worker.thread.isAlive)
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertEquals(2, attempts.get())
    } finally {
      monitor.stop()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  private fun deadline(millis: Long): Long =
    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)

  private fun <T> inBackground(action: () -> T): FutureTask<T> =
    FutureTask(action).also { task -> Thread(task, "framework-manager-test").apply {
      isDaemon = true
      start()
    } }
}
