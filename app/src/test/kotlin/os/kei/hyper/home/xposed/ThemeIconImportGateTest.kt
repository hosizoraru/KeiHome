package os.kei.hyper.home.xposed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeIconImportGateTest {
  @Test fun activeNativeImportRejectsRetirementWithoutDisablingTheOldOwner() {
    val gate = ThemeIconImportGate()
    assertTrue(gate.enter())
    var saved = false
    assertFalse(gate.retire { saved = true })
    assertFalse(saved)
    assertTrue(gate.enter())
    gate.leave()
    gate.leave()
    assertTrue(gate.retire { saved = true })
    assertTrue(saved)
    assertFalse(gate.enter())
  }

  @Test fun failedStateTransferLeavesExistingBinderUsable() {
    val gate = ThemeIconImportGate()
    assertFalse(gate.retire { error("Framework rejected the saved state") })
    assertTrue(gate.enter())
    gate.leave()
    assertTrue(gate.retire {})
    assertFalse(gate.enter())
  }

  @Test fun retirementClosesReentrantAdmissionAndSavesOnlyOnce() {
    val gate = ThemeIconImportGate()
    var saves = 0
    assertTrue(gate.retire {
      saves++
      assertFalse(gate.enter())
      assertFalse(gate.retire { saves++ })
    })
    assertTrue(gate.retire { saves++ })
    assertEquals(1, saves)
    assertFalse(gate.enter())
  }

  @Test fun queuedInitializationAndThreadReturnBothMustFinishBeforeRetirement() {
    val gate = ThemeIconImportGate()
    assertTrue(gate.enter()) // Queued attach callback owns admission before its worker starts.
    var alive = false
    assertFalse(gate.retire(workersStopped = { !alive }) { error("Callback still queued") })
    alive = true
    gate.leave() // Worker finally ran, but the thread has not returned from its entry point.
    assertFalse(gate.retire(workersStopped = { !alive }) { error("Thread still alive") })
    assertTrue(gate.enter())
    gate.leave()
    alive = false
    assertFalse(gate.retire(workersStopped = { !alive }) { error("State transfer rejected") })
    assertTrue(gate.enter()) // Failure keeps the old owner usable.
    gate.leave()
    assertTrue(gate.retire(workersStopped = { !alive }) {})
    assertFalse(gate.enter())
  }

  @Test fun binderAdmissionReturnsWhileStateExportWaitsForAnExternalOwner() {
    val gate = ThemeIconImportGate()
    val exporting = CountDownLatch(1)
    val release = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val retirement = executor.submit<Boolean> {
        gate.retire {
          exporting.countDown()
          check(release.await(5, TimeUnit.SECONDS))
        }
      }
      assertTrue(exporting.await(5, TimeUnit.SECONDS))
      assertFalse(executor.submit<Boolean> { gate.enter() }.get(3, TimeUnit.SECONDS))
      release.countDown()
      assertTrue(retirement.get(5, TimeUnit.SECONDS))
      assertFalse(gate.enter())
    } finally {
      release.countDown()
      executor.shutdownNow()
    }
  }
}
