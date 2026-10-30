package os.kei.hyper.home.xposed

import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IconProbeCancellationTest {
  @Test
  fun anInterruptedAwaitCannotBecomeAnUnknownResult() {
    var interrupted: InterruptedException? = null
    var cleanedUp = false
    try {
      val failure = assertThrows(IconProbeCancelledException::class.java) {
        runCancellableIconProbe({ true }) {
          try {
            Thread.currentThread().interrupt()
            CountDownLatch(1).await()
          } catch (failure: InterruptedException) {
            assertFalse(Thread.currentThread().isInterrupted)
            interrupted = failure
            throw failure
          } finally {
            cleanedUp = true
          }
        }
      }
      assertSame(interrupted, failure.cause)
      assertTrue(cleanedUp)
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun preInterruptedOrStaleProbeDoesNotStartWork() {
    var started = false
    try {
      Thread.currentThread().interrupt()
      assertThrows(IconProbeCancelledException::class.java) {
        runCancellableIconProbe({ true }) { started = true }
      }
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
    assertThrows(IconProbeCancelledException::class.java) {
      runCancellableIconProbe({ false }) { started = true }
    }
    assertFalse(started)
    assertFalse(Thread.currentThread().isInterrupted)
  }

  @Test
  fun cancellationDuringWorkDiscardsTheCompletedFallback() {
    var current = true
    assertThrows(IconProbeCancelledException::class.java) {
      runCancellableIconProbe({ current }) {
        current = false
        setOf("all.packages.fallback")
      }
    }
    assertFalse(Thread.currentThread().isInterrupted)
  }

  @Test
  fun interruptionDuringCleanupDiscardsAnAlreadyComputedResult() {
    try {
      assertThrows(IconProbeCancelledException::class.java) {
        runCancellableIconProbe({ true }) {
          try { setOf("computed.result") } finally { Thread.currentThread().interrupt() }
        }
      }
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun anActiveCompletedProbeKeepsItsResult() {
    val result = setOf("normal.result")
    assertSame(result, runCancellableIconProbe({ true }) { result })
  }
}
