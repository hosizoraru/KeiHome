package os.kei.hyper.home.xposed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CoalescingInitializationWorkTest {
  @Test fun aBurstSchedulesOneWorkerAndKeepsTheLatestSignalPerOwner() {
    val dispatch = mutableListOf<() -> Unit>()
    val work = CoalescingInitializationWork({ dispatch += it; true }, { throw AssertionError(it) })
    val seen = mutableListOf<Int>()
    val key = Any()
    repeat(100) { n -> assertTrue(work.submit(key) { seen += n }) }
    work.submit(Any()) { seen += 100 }
    assertEquals(1, dispatch.size)
    dispatch.single().invoke()
    assertEquals(listOf(99, 100), seen)
  }

  @Test fun anOptionalFailureDoesNotDiscardTheOtherOwners() {
    val dispatch = mutableListOf<() -> Unit>()
    val failures = mutableListOf<Throwable>()
    val work = CoalescingInitializationWork({ dispatch += it; true }, { failures += it })
    val failed = IllegalStateException("unsupported optional feature")
    var installed = false
    work.submit(Any()) { throw failed }
    work.submit(Any()) { installed = true }
    dispatch.single().invoke()
    assertTrue(installed)
    assertEquals(listOf(failed), failures)
  }

  @Test fun closeDiscardsPendingClosuresAndRollbackCanAcceptNewWork() {
    val dispatch = mutableListOf<() -> Unit>()
    val work = CoalescingInitializationWork({ dispatch += it; true }, {})
    var calls = 0
    work.submit(Any()) { calls++ }
    work.close()
    assertFalse(work.submit(Any()) { calls++ })
    dispatch.removeAt(0).invoke()
    assertEquals(0, calls)
    work.reopen()
    assertTrue(work.submit(Any()) { calls++ })
    dispatch.removeAt(0).invoke()
    assertEquals(1, calls)
  }

  @Test fun declinedWorkerCreationCanRecoverOnTheNextSignal() {
    val dispatch = mutableListOf<() -> Unit>()
    var allow = false
    val work = CoalescingInitializationWork({
      if (allow) { dispatch += it; true } else false
    }, {})
    val seen = mutableListOf<Int>()
    assertFalse(work.submit(Any()) { seen += 1 })
    assertTrue(dispatch.isEmpty())
    allow = true
    assertTrue(work.submit(Any()) { seen += 2 })
    dispatch.single().invoke()
    assertEquals(listOf(1, 2), seen)
  }

  @Test fun blockedInitializationDoesNotCreateAThreadForEachSignal() {
    val executor = Executors.newSingleThreadExecutor()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val done = CountDownLatch(1)
    val launches = AtomicInteger()
    val calls = AtomicInteger()
    val failures = ConcurrentLinkedQueue<Throwable>()
    val key = Any()
    val work = CoalescingInitializationWork({
      launches.incrementAndGet()
      executor.submit { try { it() } finally { done.countDown() } }
      true
    }, { failures += it })
    try {
      work.submit(key) {
        calls.incrementAndGet()
        entered.countDown()
        assertTrue(release.await(3, TimeUnit.SECONDS))
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      repeat(100) { work.submit(key) { calls.incrementAndGet() } }
      release.countDown()
      assertTrue(done.await(3, TimeUnit.SECONDS))
      assertEquals(1, launches.get())
      assertEquals(2, calls.get())
      assertTrue(failures.isEmpty())
    } finally { release.countDown(); executor.shutdownNow() }
  }

  @Test fun launchingDoesNotHoldTheQueueMonitor() {
    val executor = Executors.newSingleThreadExecutor()
    val nestedCompleted = AtomicBoolean()
    lateinit var work: CoalescingInitializationWork
    var dispatch: (() -> Unit)? = null
    work = CoalescingInitializationWork({
      nestedCompleted.set(executor.submit<Boolean> { work.submit(Any()) {} }.get(3, TimeUnit.SECONDS))
      dispatch = it
      true
    }, {})
    try {
      assertTrue(work.submit(Any()) {})
      assertTrue(nestedCompleted.get())
      requireNotNull(dispatch).invoke()
    } finally { executor.shutdownNow() }
  }

  @Test fun fatalFailuresEscapeTheDispatcher() {
    var dispatch: (() -> Unit)? = null
    val work = CoalescingInitializationWork({ dispatch = it; true }, { fail("must not report fatal as optional") })
    val fatal = object : VirtualMachineError("test") {}
    work.submit(Any()) { throw fatal }
    try {
      requireNotNull(dispatch).invoke()
      fail("expected fatal")
    } catch (actual: VirtualMachineError) { assertSame(fatal, actual) }
  }

  @Test fun priorityFailureAfterLaunchAcceptanceCannotStrandTheDispatcher() {
    val dispatch = mutableListOf<() -> Unit>()
    val priorities = mutableListOf<Throwable>()
    val denied = SecurityException("priority denied")
    val work = CoalescingInitializationWork({ action ->
      dispatch += { runWithInitializationPriority({ throw denied }, { priorities += it }, action) }
      true
    }, { throw AssertionError(it) })
    var calls = 0
    work.submit(Any()) { calls++ }
    dispatch.removeAt(0).invoke()
    assertEquals(1, calls)
    work.submit(Any()) { calls++ }
    dispatch.removeAt(0).invoke()
    assertEquals(2, calls)
    assertEquals(listOf(denied, denied), priorities)
  }

  @Test fun unavailablePriorityDiagnosticsDoNotPreventInitialization() {
    var ran = false
    runWithInitializationPriority(
      { throw SecurityException() },
      { error("logging unavailable") },
      { ran = true },
    )
    assertTrue(ran)
  }

  @Test fun fatalPriorityFailuresRemainFatal() {
    var ran = false
    val fatal = object : VirtualMachineError("test") {}
    try {
      runWithInitializationPriority({ throw fatal }, { fail("fatal") }, { ran = true })
      fail("expected fatal")
    } catch (actual: VirtualMachineError) { assertSame(fatal, actual) }
    assertFalse(ran)
  }
}
