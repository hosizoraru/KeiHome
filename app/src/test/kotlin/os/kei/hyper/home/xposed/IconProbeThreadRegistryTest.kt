package os.kei.hyper.home.xposed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IconProbeThreadRegistryTest {
  @Test
  fun ownershipSurvivesBodyCompletionAndTimedOutJoinsUntilTheThreadTerminates() {
    val registry = IconProbeThreadRegistry()
    val bodyFinished = CountDownLatch(1)
    val release = CountDownLatch(1)
    val owned = Thread {
      try { bodyFinished.countDown() } finally { release.await() }
    }
    registry.start(owned)
    try {
      assertTrue(bodyFinished.await(2, TimeUnit.SECONDS))
      registry.reapTerminated()
      assertFalse(registry.awaitStopped(deadline(20)))
      assertFalse(registry.awaitStopped(System.nanoTime() - 1))
      assertTrue(owned.isAlive)
      release.countDown()
      assertTrue(registry.awaitStopped(deadline()))
      assertFalse(owned.isAlive)
    } finally {
      release.countDown()
      owned.join(2_000)
    }
  }

  @Test
  fun aSameNamedThreadOutsideThisRegistryDoesNotBlockRetirement() {
    val release = CountDownLatch(1)
    val unrelated = thread(name = "KeiHomeIconProbeReply") { release.await() }
    try {
      assertTrue(IconProbeThreadRegistry().awaitStopped(System.nanoTime() - 1))
      assertTrue(unrelated.isAlive)
    } finally {
      release.countDown()
      unrelated.join(2_000)
    }
  }

  @Test
  fun registeredButNotYetStartedThreadCannotBeMistakenForTerminated() {
    val registry = IconProbeThreadRegistry()
    val starting = CountDownLatch(1)
    val allowStart = CountDownLatch(1)
    val release = CountDownLatch(1)
    val owned = object : Thread({ release.await() }) {
      override fun start() {
        starting.countDown()
        allowStart.await()
        super.start()
      }
    }
    val starter = thread { registry.start(owned) }
    try {
      assertTrue(starting.await(2, TimeUnit.SECONDS))
      assertFalse(owned.isAlive)
      registry.reapTerminated()
      assertFalse(registry.awaitStopped(deadline(20)))
      allowStart.countDown()
      starter.join(2_000)
      assertFalse(starter.isAlive)
      assertFalse(registry.awaitStopped(deadline(20)))
      release.countDown()
      assertTrue(registry.awaitStopped(deadline()))
    } finally {
      allowStart.countDown()
      release.countDown()
      starter.join(2_000)
      owned.join(2_000)
    }
  }

  @Test
  fun failedStartReleasesOnlyTheThreadThatNeverStarted() {
    val registry = IconProbeThreadRegistry()
    val failed = object : Thread() {
      override fun start() { throw IllegalStateException("start failed") }
    }
    assertThrows(IllegalStateException::class.java) { registry.start(failed) }
    assertTrue(registry.awaitStopped(System.nanoTime() - 1))

    val release = CountDownLatch(1)
    val running = object : Thread({ release.await() }) {
      override fun start() {
        super.start()
        throw IllegalStateException("failure after start")
      }
    }
    try {
      assertThrows(IllegalStateException::class.java) { registry.start(running) }
      assertFalse(registry.awaitStopped(deadline(20)))
      release.countDown()
      assertTrue(registry.awaitStopped(deadline()))
    } finally {
      release.countDown()
      running.join(2_000)
    }
  }

  @Test
  fun interruptedWaitPreservesInterruptAndOwnership() {
    val registry = IconProbeThreadRegistry()
    val release = CountDownLatch(1)
    val owned = Thread { release.await() }
    registry.start(owned)
    val waiting = CountDownLatch(1)
    val stopped = AtomicBoolean(true)
    val interrupted = AtomicBoolean()
    val waiter = thread {
      waiting.countDown()
      stopped.set(registry.awaitStopped(deadline()))
      interrupted.set(Thread.currentThread().isInterrupted)
    }
    try {
      assertTrue(waiting.await(2, TimeUnit.SECONDS))
      waiter.interrupt()
      waiter.join(2_000)
      assertFalse(waiter.isAlive)
      assertFalse(stopped.get())
      assertTrue(interrupted.get())
      assertFalse(registry.awaitStopped(System.nanoTime() - 1))
      release.countDown()
      assertTrue(registry.awaitStopped(deadline()))
    } finally {
      release.countDown()
      waiter.interrupt()
      waiter.join(2_000)
      owned.join(2_000)
    }
  }

  @Test
  fun anOwnedThreadCannotWaitForItsOwnRetirement() {
    val registry = IconProbeThreadRegistry()
    val result = AtomicBoolean(true)
    val checked = CountDownLatch(1)
    val owned = Thread {
      result.set(registry.awaitStopped(deadline()))
      checked.countDown()
    }
    registry.start(owned)
    assertTrue(checked.await(2, TimeUnit.SECONDS))
    assertFalse(result.get())
    assertTrue(registry.awaitStopped(deadline()))
  }

  private fun deadline(milliseconds: Long = 2_000) =
    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds)
}
