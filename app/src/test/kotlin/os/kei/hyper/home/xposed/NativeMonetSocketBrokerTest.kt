package os.kei.hyper.home.xposed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMonetSocketBrokerTest {
  @Test
  fun synchronousFdRequestsAllowBinderTransportFlagsButRejectOnewayOrMissingReply() {
    assertTrue(NativeMonetBrokerRequest.isSynchronous(true, 0))
    assertTrue(NativeMonetBrokerRequest.isSynchronous(true, 0x10)) // TF_ACCEPT_FDS
    assertFalse(NativeMonetBrokerRequest.isSynchronous(true, 1))
    assertFalse(NativeMonetBrokerRequest.isSynchronous(true, 0x11))
    assertFalse(NativeMonetBrokerRequest.isSynchronous(false, 0))
    assertFalse(NativeMonetBrokerRequest.isSynchronous(false, 0x10))
  }

  @Test
  fun requiresExactFourWordV1RequestAndKernelCallingIdentity() {
    val valid = intArrayOf(0x334d484b, 1, 10141, 1)
    fun accepted(frame: IntArray) = NativeMonetBrokerRequest.matches(frame, 10141, 17240, 8818)
    assertTrue(accepted(valid))
    for (size in 0 until valid.size) assertFalse(accepted(valid.copyOf(size)))
    assertFalse(accepted(valid + 0))
    for ((index, invalid) in listOf(0 to 0, 1 to 2, 2 to 110141, 3 to 0)) {
      assertFalse(accepted(valid.copyOf().apply { this[index] = invalid }))
    }
    assertFalse(NativeMonetBrokerRequest.matches(valid, 110141, 17240, 8818))
    assertFalse(NativeMonetBrokerRequest.matches(valid, 10141, 0, 8818))
    assertFalse(NativeMonetBrokerRequest.matches(valid, 10141, -1, 8818))
    assertFalse(NativeMonetBrokerRequest.matches(valid, 10141, 8818, 8818))
    assertFalse(NativeMonetBrokerRequest.matches(valid, 10141, 17240, 8818, hasFileDescriptors = true))
    assertFalse(NativeMonetBrokerRequest.matches(intArrayOf(0x334d484b, 1, -1, 1), -1, 17240, 8818))
  }

  @Test
  fun cleanupIsAsynchronousAndRetirementWaitsForItsRealCompletion() {
    val worker = NativeMonetBrokerWorker()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val cleanupThread = AtomicReference<Thread>()
    val finished = AtomicBoolean()
    try {
      worker.execute {
        cleanupThread.set(Thread.currentThread())
        entered.countDown()
        release.await()
        finished.set(true)
      }
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      // Shutdown must return without joining a stalled FD close on the lifecycle caller.
      worker.shutdown()
      assertFalse(finished.get())
      assertFalse(worker.awaitStopped(deadline(20)))
      assertFalse(worker.isStopped())
      release.countDown()
      assertTrue(worker.awaitStopped(deadline()))
      assertTrue(finished.get())
      assertFalse(cleanupThread.get().isAlive)
    } finally {
      release.countDown()
      worker.shutdown()
      assertTrue(worker.awaitStopped(deadline()))
    }
  }

  @Test
  fun retirementDrainsQueuedClosesWithoutExecutingFutureExpiryCallbacks() {
    val worker = NativeMonetBrokerWorker()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closes = AtomicInteger()
    val expiryRuns = AtomicInteger()
    val expiry = worker.schedule(TimeUnit.DAYS.toMillis(1)) { expiryRuns.incrementAndGet() }
    try {
      worker.execute { entered.countDown(); release.await(); closes.incrementAndGet() }
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      worker.execute { closes.incrementAndGet() }
      worker.execute { closes.incrementAndGet() }
      worker.shutdown()
      assertFalse(worker.awaitStopped(deadline(20)))
      release.countDown()
      assertTrue(worker.awaitStopped(deadline()))
      assertEquals(3, closes.get())
      assertEquals(0, expiryRuns.get())
      assertTrue(expiry.isCancelled)
    } finally {
      release.countDown()
      worker.shutdown()
      assertTrue(worker.awaitStopped(deadline()))
    }
  }

  @Test
  fun aWorkerCannotDeclareItsOwnThreadRetired() {
    val worker = NativeMonetBrokerWorker()
    val checked = CountDownLatch(1)
    val selfRetired = AtomicBoolean(true)
    try {
      worker.execute {
        worker.shutdown()
        selfRetired.set(worker.awaitStopped(deadline()))
        checked.countDown()
      }
      assertTrue(checked.await(1, TimeUnit.SECONDS))
      assertFalse(selfRetired.get())
      assertTrue(worker.awaitStopped(deadline()))
    } finally {
      worker.shutdown()
      assertTrue(worker.awaitStopped(deadline()))
    }
  }

  private fun deadline(timeoutMs: Long = 1_000) =
    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
}
