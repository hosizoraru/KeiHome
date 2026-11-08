package os.kei.hyper.home.xposed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ModuleRetirementMaintenanceTest {
  @Test fun noWorkerNeedsNoShutdown() {
    assertTrue(ModuleRetirementMaintenance().awaitStopped())
  }

  @Test fun blockedWorkHasABoundedWaitAndIsNeverInterruptedToForceSuccess() {
    val work = ModuleRetirementMaintenance()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val interrupted = AtomicBoolean(false)
    work.start {
      entered.countDown()
      try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { interrupted.set(true) }
    }
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      assertFalse(work.awaitStopped(10))
      assertFalse(interrupted.get())
      assertThrows(IllegalStateException::class.java) { work.start { error("Second worker") } }
    } finally {
      release.countDown()
    }
    assertTrue(work.awaitStopped(5_000))
  }

  @Test fun workerCannotJoinItself() {
    val work = ModuleRetirementMaintenance()
    val result = AtomicBoolean(true)
    work.start { result.set(work.awaitStopped()) }
    assertTrue(work.awaitStopped(5_000))
    assertFalse(result.get())
  }

  @Test fun interruptedCallerDoesNotLoseItsInterruptOrCertifyTheWorkerStopped() {
    val work = ModuleRetirementMaintenance()
    val release = CountDownLatch(1)
    work.start { release.await(5, TimeUnit.SECONDS) }
    try {
      Thread.currentThread().interrupt()
      assertFalse(work.awaitStopped(10))
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
      release.countDown()
    }
    assertTrue(work.awaitStopped(5_000))
  }
}
