package os.kei.hyper.home.xposed

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SystemUiInitializationTest {
  @Test fun optionalFailureDoesNotPreventIndependentIconOrSplashInitialization() {
    val failures = mutableListOf<String>()
    val stages = SystemUiInitialization { feature, _, _, _ -> failures += feature }
    assertFalse(stages.run("material profile") { error("unsupported material") })
    assertTrue(stages.run("notification icons") { true })
    assertTrue(stages.run("splash hooks") { true })
    assertEquals(listOf("material profile"), failures)
  }

  @Test fun laterLifecycleSignalRetriesFailureAndSuccessfulStagesAreNotRepeated() {
    val stages = SystemUiInitialization { _, _, _, _ -> }
    var attempts = 0
    assertFalse(stages.run("preferences") { attempts++; error("not ready") })
    assertEquals(1, attempts)
    assertTrue(stages.run("preferences") { attempts++; true })
    assertTrue(stages.run("preferences") { error("duplicate registration") })
    assertEquals(2, attempts)
  }

  @Test fun persistentFailureHasABoundedBudgetPerGeneration() {
    val records = mutableListOf<Pair<Int, Boolean>>()
    val stages = SystemUiInitialization { _, count, exhausted, _ -> records += count to exhausted }
    var attempts = 0
    repeat(20) { assertFalse(stages.run("profile") { attempts++; error("shape rejected") }) }
    assertEquals(2, attempts)
    assertEquals(listOf(1 to false, 2 to true), records)
    assertTrue(stages.run("independent") { true })
  }

  @Test fun absentPrerequisiteDoesNotBecomeACompletedStageOrAnError() {
    val stages = SystemUiInitialization { _, _, _, _ -> fail("ordinary early discovery") }
    repeat(3) { assertFalse(stages.run("live plugin") { false }) }
    assertTrue(stages.run("live plugin") { true })
    assertTrue(stages.run("live plugin") { fail("already adopted"); false })
  }

  @Test fun completedAndExhaustedStagesDoNotScheduleMoreWorkers() {
    val stages = SystemUiInitialization { _, _, _, _ -> }
    assertTrue(stages.canRun("icons"))
    assertTrue(stages.run("icons") { true })
    assertFalse(stages.canRun("icons"))
    repeat(2) { stages.run("material") { error("unsupported") } }
    assertFalse(stages.canRun("material"))
    assertTrue(stages.canRun("splash"))
  }

  @Test fun lifecycleSignalDuringInFlightFailureIsReplayedWithoutOverlap() {
    val entered = CountDownLatch(1)
    val finishFirst = CountDownLatch(1)
    val calls = AtomicInteger()
    val active = AtomicInteger()
    val stages = SystemUiInitialization { _, _, _, _ -> }
    val executor = Executors.newSingleThreadExecutor()
    try {
      val first = executor.submit<Boolean> {
        stages.run("profile") {
          assertEquals(1, active.incrementAndGet())
          try {
            if (calls.incrementAndGet() == 1) {
              entered.countDown()
              assertTrue(finishFirst.await(3, TimeUnit.SECONDS))
              error("early failure")
            }
            true
          } finally { active.decrementAndGet() }
        }
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      assertFalse(stages.run("profile") { fail("concurrent initialization"); false })
      finishFirst.countDown()
      assertTrue(first.get(3, TimeUnit.SECONDS))
      assertEquals(2, calls.get())
      assertEquals(0, active.get())
    } finally { finishFirst.countDown(); executor.shutdownNow() }
  }

  @Test fun concurrentSignalDoesNotRepeatSuccessfulWork() {
    val stages = SystemUiInitialization { _, _, _, _ -> }
    var calls = 0
    assertTrue(stages.run("icons") {
      calls++
      assertFalse(stages.run("icons") { fail("overlap"); false })
      true
    })
    assertEquals(1, calls)
  }

  @Test fun failureReporterRunsOutsideTheStateLockAndCannotPoisonOtherStages() {
    val executor = Executors.newSingleThreadExecutor()
    val independentCompleted = AtomicBoolean()
    lateinit var stages: SystemUiInitialization
    stages = SystemUiInitialization { _, _, _, _ ->
      independentCompleted.set(
        executor.submit<Boolean> { stages.run("other") { true } }.get(3, TimeUnit.SECONDS),
      )
      error("diagnostic IPC failed")
    }
    try {
      assertFalse(stages.run("profile") { error("profile rejected") })
      assertTrue(independentCompleted.get())
      assertTrue(stages.run("profile") { true })
    } finally { executor.shutdownNow() }
  }

  @Test fun wrappedFatalVmErrorsAreNotConvertedToOptionalInitializationFailures() {
    val stages = SystemUiInitialization { _, _, _, _ -> fail("fatal must escape") }
    val fatal = object : VirtualMachineError("test") {}
    try {
      stages.run("profile") { throw InvocationTargetException(fatal) }
      fail("expected fatal error")
    } catch (actual: VirtualMachineError) { assertSame(fatal, actual) }
  }

  @Test fun interruptionIsRetainedAndTheFailedStageRemainsRetryable() {
    val stages = SystemUiInitialization { _, _, _, _ -> }
    try {
      assertFalse(stages.run("preferences") { throw InterruptedException() })
      assertTrue(Thread.currentThread().isInterrupted)
    } finally { Thread.interrupted() }
    assertTrue(stages.run("preferences") { true })
  }
}
