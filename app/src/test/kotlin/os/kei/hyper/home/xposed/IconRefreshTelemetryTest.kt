package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IconRefreshTelemetryTest {
  private val generation = UUID.randomUUID().toString()

  private fun report(
    sequence: Long = 1,
    kind: IconRefreshReportKind = IconRefreshReportKind.HOOK_INSTALLED,
    callbacks: Int? = null,
    start: Long = 100,
    id: String = generation,
    boot: Int = 7,
  ) = IconRefreshReport(
    bootCount = boot,
    generation = id,
    generationStartedNanos = start,
    sequence = sequence,
    completedElapsedNanos = start + sequence,
    completedWallMillis = 1_700_000_000_000,
    kind = kind,
    callbackCount = callbacks,
  )

  private fun merge(history: IconRefreshHistory?, event: IconRefreshReport) =
    mergeIconRefreshReport(history, event, currentBoot = 7, nowNanos = 10_000)

  @Test
  fun senderIdentityMustBeSystemUid() {
    assertTrue(isTrustedReportSender(1_000))
    assertFalse(isTrustedReportSender(-1))
    assertFalse(isTrustedReportSender(0))
    assertFalse(isTrustedReportSender(10_000))
    assertFalse(isTrustedReportSender(101_000))
  }

  @Test
  fun invalidAndForeignBootReportsAreRejected() {
    val event = report()
    assertNull(merge(null, event.copy(bootCount = 6)))
    assertNull(merge(null, event.copy(generation = "not-a-uuid")))
    assertNull(merge(null, event.copy(generationStartedNanos = 0)))
    assertNull(merge(null, event.copy(completedElapsedNanos = 10_001)))
    assertNull(merge(null, event.copy(completedElapsedNanos = 99)))
    assertNull(merge(null, event.copy(sequence = 0)))
    assertNull(merge(null, event.copy(callbackCount = 0)))
    assertNull(merge(null, report(kind = IconRefreshReportKind.REFRESH_FINISHED)))
    assertNull(merge(null, report(kind = IconRefreshReportKind.REFRESH_FAILED, callbacks = -1)))
  }

  @Test
  fun newerGenerationWinsEvenWhenOldReportsArriveLate() {
    val oldHook = report()
    val oldRefresh = report(2, IconRefreshReportKind.REFRESH_FINISHED, 4)
    val replacement = report(
      kind = IconRefreshReportKind.HOOK_FAILED,
      start = 200,
      id = UUID.randomUUID().toString(),
    )
    val afterOld = merge(merge(null, oldHook), oldRefresh)
    val afterNew = merge(afterOld, replacement)!!
    assertEquals(replacement, afterNew.hook)
    assertTrue(afterNew.refreshes.isEmpty())
    assertEquals(afterNew, merge(afterNew, oldRefresh.copy(sequence = 100)))
    assertEquals(afterNew, merge(afterNew, oldHook))
  }

  @Test
  fun matchingTimestampWithDifferentGenerationFailsClosed() {
    val first = merge(null, report())!!
    val ambiguous = report(id = UUID.randomUUID().toString())
    assertEquals(first, merge(first, ambiguous))
  }

  @Test
  fun currentBootReplacesOldBootButForeignBootCannotOverwrite() {
    val old = report(boot = 6)
    val oldHistory = mergeIconRefreshReport(null, old, 6, 10_000)!!
    val current = report(start = 50)
    val history = merge(oldHistory, current)!!
    assertEquals(current, history.hook)
    assertEquals(history, merge(history, old))
  }

  @Test
  fun sameGenerationOutOfOrderAndFailuresRemainDistinct() {
    val refresh = report(2, IconRefreshReportKind.REFRESH_PARTIAL, 3)
    val hook = report(kind = IconRefreshReportKind.HOOK_FAILED)
    val withRefresh = merge(null, refresh)!!
    assertNull(withRefresh.hook)
    val complete = merge(withRefresh, hook)!!
    assertEquals(hook, complete.hook)
    assertEquals(listOf(refresh), complete.refreshes)
    assertEquals(complete, merge(complete, refresh))
    assertEquals(complete, merge(complete, refresh.copy(callbackCount = 4)))
    assertEquals(complete, merge(complete, hook.copy(kind = IconRefreshReportKind.HOOK_INSTALLED)))
  }

  @Test
  fun refreshHistoryIsBoundedAndDoesNotReinsertEvictedReports() {
    var history = merge(null, report())
    for (sequence in 2L..42L) {
      history = merge(history, report(sequence, IconRefreshReportKind.REFRESH_FINISHED, 0))
    }
    assertEquals(MAX_STORED_REFRESH_REPORTS, history!!.refreshes.size)
    assertEquals(11L, history.refreshes.first().sequence)
    assertEquals(42L, history.refreshes.last().sequence)
    assertEquals(history, merge(history, report(2, IconRefreshReportKind.REFRESH_FINISHED, 0)))
  }

  @Test
  fun dispatcherRejectsRetirementDuringIpcAndBoundsQueue() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val calls = AtomicInteger()
    val dispatcher = IconRefreshReportDispatcher(
      send = {
        calls.incrementAndGet()
        entered.countDown()
        release.await(2, TimeUnit.SECONDS)
        false
      },
      retryDelayMillis = 60_000,
      queueCapacity = 2,
    )
    try {
      assertTrue(dispatcher.enqueue(report()))
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      assertTrue(dispatcher.enqueue(report(2, IconRefreshReportKind.REFRESH_FINISHED, 1)))
      assertFalse(dispatcher.enqueue(report(3, IconRefreshReportKind.REFRESH_FINISHED, 1)))
      assertFalse(dispatcher.stopIfIdle())
      release.countDown()
      assertTrue(waitUntil { dispatcher.stopIfIdle() })
      assertTrue(dispatcher.awaitStopped(2_000))
      assertFalse(dispatcher.enqueue(report()))
      assertEquals(1, calls.get())
    } finally {
      release.countDown()
      waitUntil { dispatcher.stopIfIdle() }
      dispatcher.awaitStopped(2_000)
    }
  }

  @Test
  fun dispatcherRetriesFailuresAndStopsAfterSuccess() {
    val calls = AtomicInteger()
    val finished = CountDownLatch(1)
    val dispatcher = IconRefreshReportDispatcher(
      send = {
        when (calls.incrementAndGet()) {
          1 -> error("unavailable")
          2 -> false
          else -> {
            finished.countDown()
            true
          }
        }
      },
      retryDelayMillis = 1,
      maxAttempts = 3,
    )
    try {
      assertTrue(dispatcher.enqueue(report()))
      assertTrue(finished.await(2, TimeUnit.SECONDS))
      assertTrue(waitUntil { dispatcher.stopIfIdle() })
      assertTrue(dispatcher.awaitStopped(2_000))
      assertEquals(3, calls.get())
    } finally {
      waitUntil { dispatcher.stopIfIdle() }
      dispatcher.awaitStopped(2_000)
    }
  }

  @Test
  fun exhaustedReportDoesNotBlockTheNextReport() {
    val attempts = AtomicInteger()
    val nextSent = CountDownLatch(1)
    val dispatcher = IconRefreshReportDispatcher(
      send = {
        if (it.sequence == 1L) {
          attempts.incrementAndGet()
          false
        } else {
          nextSent.countDown()
          true
        }
      },
      retryDelayMillis = 1,
      maxAttempts = 2,
    )
    try {
      assertTrue(dispatcher.enqueue(report()))
      assertTrue(dispatcher.enqueue(report(2, IconRefreshReportKind.REFRESH_FAILED, 0)))
      assertTrue(nextSent.await(2, TimeUnit.SECONDS))
      assertTrue(waitUntil { dispatcher.stopIfIdle() })
      assertTrue(dispatcher.awaitStopped(2_000))
      assertEquals(2, attempts.get())
    } finally {
      waitUntil { dispatcher.stopIfIdle() }
      dispatcher.awaitStopped(2_000)
    }
  }

  private fun waitUntil(condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (System.nanoTime() < deadline) {
      if (condition()) return true
      Thread.sleep(5)
    }
    return condition()
  }
}
