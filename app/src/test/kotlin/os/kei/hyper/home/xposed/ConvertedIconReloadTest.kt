package os.kei.hyper.home.xposed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertedIconReloadTest {
  @Test fun retirementWaitsForAnAlreadyAdmittedShortRegistration() {
    val lifecycle = IconRefreshReloadLifecycle()
    val entered = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val worker = Thread {
      lifecycle.withActiveRuntime { entered.countDown(); check(finish.await(2, TimeUnit.SECONDS)) }
    }.apply { start() }
    assertTrue(entered.await(2, TimeUnit.SECONDS))
    val retiring = FutureTask {
      lifecycle.retire({ true }, { true }, {}, {})
    }
    val handoff = Thread(retiring).apply { start() }
    try {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
      while (!lifecycle.retiring && System.nanoTime() < deadline) Thread.yield()
      assertTrue(lifecycle.retiring)
      assertThrows(IllegalStateException::class.java) { lifecycle.withActiveRuntime {} }
      finish.countDown()
      assertTrue(retiring.get(2, TimeUnit.SECONDS))
      assertTrue(lifecycle.retired)
    } finally {
      finish.countDown()
      worker.join(2_000)
      handoff.join(2_000)
    }
  }

  @Test
  fun registrationMustFinishBeforeRetirementCanStopTheOldWorker() {
    val lifecycle = IconRefreshReloadLifecycle()
    val entered = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val registration = FutureTask {
      lifecycle.withActiveRuntime {
        entered.countDown()
        check(finish.await(2, TimeUnit.SECONDS))
      }
    }
    val worker = Thread(registration).apply { start() }
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      assertFalse(lifecycle.retire(
        prepare = { error("Registration still owns the worker") },
        await = { error("Must not await") },
        commit = { error("Must not commit") },
        rollback = { error("Must not restart") },
      ))
      assertFalse(lifecycle.retired)
    } finally {
      finish.countDown()
      registration.get(2, TimeUnit.SECONDS)
      worker.join(2_000)
    }
    assertTrue(lifecycle.retire({ true }, { true }, {}, {}))
    assertThrows(IllegalStateException::class.java) {
      lifecycle.withActiveRuntime { error("Retired owner cannot register") }
    }
    assertEquals(0, lifecycle.activeHooks)
  }

  @Test
  fun failedWorkerDrainKeepsValidationActiveAndRestoresRegistration() {
    val lifecycle = IconRefreshReloadLifecycle()
    val events = mutableListOf<String>()
    assertFalse(lifecycle.retire(
      prepare = { true },
      await = {
        assertThrows(IllegalStateException::class.java) {
          lifecycle.withActiveRuntime { error("Cannot register during handover") }
        }
        false
      },
      commit = { error("Worker still owns its thread") },
      rollback = { lifecycle.withActiveRuntime { events += "restart" } },
      retireValidation = { events += "retire-validation"; true },
      resumeValidation = { events += "resume-validation" },
    ))
    assertEquals(listOf("restart"), events)
    assertFalse(lifecycle.retiring)
    assertFalse(lifecycle.retired)
  }

  @Test
  fun failedStateSaveResumesValidationBeforeRestartAndLaterReloadSucceeds() {
    val lifecycle = IconRefreshReloadLifecycle()
    val events = mutableListOf<String>()
    var validationActive = true
    assertFalse(lifecycle.retire(
      prepare = { true },
      await = { true },
      commit = { events += "save"; error("State save failed") },
      rollback = {
        assertTrue(validationActive)
        lifecycle.withActiveRuntime { events += "restart" }
      },
      retireValidation = { validationActive = false; events += "retire-validation"; true },
      resumeValidation = { validationActive = true; events += "resume-validation" },
    ))
    assertEquals(listOf("retire-validation", "save", "resume-validation", "restart"), events)
    assertFalse(lifecycle.retired)
    assertTrue(lifecycle.retire(
      prepare = { true },
      await = { true },
      commit = { events += "saved" },
      rollback = { error("Must not restart") },
      retireValidation = { validationActive = false; true },
      resumeValidation = { error("Committed reload must not resume old validation") },
    ))
    assertFalse(validationActive)
    assertTrue(lifecycle.retired)
  }

  @Test
  fun busyValidationRejectsReloadAndRestartsOnlyTheCurrentOwner() {
    val lifecycle = IconRefreshReloadLifecycle()
    var restarts = 0
    assertFalse(lifecycle.retire(
      prepare = { true },
      await = { true },
      commit = { error("Busy validation cannot save state") },
      rollback = { lifecycle.withActiveRuntime { restarts += 1 } },
      retireValidation = { false },
      resumeValidation = { error("Validation never retired") },
    ))
    assertEquals(1, restarts)
    assertFalse(lifecycle.retiring)
    assertFalse(lifecycle.retired)
  }
}
