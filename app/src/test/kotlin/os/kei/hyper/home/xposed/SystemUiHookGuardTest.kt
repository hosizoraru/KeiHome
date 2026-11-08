package os.kei.hyper.home.xposed

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SystemUiHookGuardTest {
  @Test fun preservesSuccessfulResults() {
    val guard = SystemUiHookGuard { error("Unexpected failure") }
    repeat(3) { assertEquals(it, guard.run(-1) { it }) }
  }

  @Test fun reflectedFailureDisablesOnlyTheFailedEnhancementAndReportsOnce() {
    val failure = NoSuchMethodError("OEM member changed")
    val failures = mutableListOf<Throwable>()
    var calls = 0
    val guard = SystemUiHookGuard { failures += it }
    repeat(5) {
      assertEquals("native", guard.run("native") {
        calls++
        throw InvocationTargetException(InvocationTargetException(failure))
      })
    }
    assertEquals(1, calls)
    assertEquals(listOf(failure), failures)
    assertEquals("enhanced", SystemUiHookGuard {}.run("native") { "enhanced" })
  }

  @Test fun nestedResourceRefreshDoesNotReenterItsOwnEnhancement() {
    val guard = SystemUiHookGuard { error("Unexpected failure") }
    var refreshes = 0
    assertEquals("outer", guard.run("native") {
      refreshes++
      assertEquals("nested-native", guard.run("nested-native") {
        refreshes++
        error("Recursive refresh")
      })
      "outer"
    })
    assertEquals(1, refreshes)
    assertEquals("next", guard.run("native") { "next" })
  }

  @Test fun failureReportingCannotThrowBackIntoSystemUi() {
    val guard = SystemUiHookGuard { throw IllegalStateException("Reporter unavailable") }
    assertEquals(23, guard.run(23) { throw IllegalArgumentException("Optional reflection") })
  }

  @Test fun unrelatedThreadsDoNotShareReentrancyState() {
    val guard = SystemUiHookGuard { error("Unexpected failure") }
    val executor = Executors.newFixedThreadPool(2)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    try {
      val first = executor.submit<Int> {
        guard.run(-1) {
          entered.countDown()
          check(release.await(5, TimeUnit.SECONDS))
          1
        }
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      assertEquals(2, executor.submit<Int> { guard.run(-1) { 2 } }.get(5, TimeUnit.SECONDS))
      release.countDown()
      assertEquals(1, first.get(5, TimeUnit.SECONDS))
    } finally {
      release.countDown()
      executor.shutdownNow()
    }
  }

  @Test fun vmFailuresAreNotDisguisedAsSuccessfulFallbacks() {
    val failure = OutOfMemoryError("test only")
    val guard = SystemUiHookGuard { error("Must not report a VM failure as optional") }
    val observed = assertThrows(OutOfMemoryError::class.java) {
      guard.run(0) { throw InvocationTargetException(failure) }
    }
    assertSame(failure, observed)
    assertEquals(1, guard.run(0) { 1 })
  }

  @Test fun interruptedOptionalWorkPreservesTheInterruptSignal() {
    try {
      assertEquals(0, SystemUiHookGuard {}.run(0) { throw InterruptedException() })
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }
}
