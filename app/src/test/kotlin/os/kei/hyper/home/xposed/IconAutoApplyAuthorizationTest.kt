package os.kei.hyper.home.xposed

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.status.IconImportLocalState
import os.kei.hyper.home.status.IconPackImportRequest
import os.kei.hyper.home.status.NativeIconImportReport

class IconAutoApplyAuthorizationTest {
  private val request = IconPackImportRequest("00000000-0000-0000-0000-000000000000",
    0, 100, "source", autoApply = true)
  private val imported = NativeIconImportReport(request.token, request.userId, request.sourceId,
    "IMPORTED", localId = "native-icon", title = "Existing import", convertedReady = true)

  @Test
  fun bridgeRecoveryAddsAuthorizationWithoutChangingTheDurableImportOrRepeatingDeliveryWork() {
    val fixture = Fixture()
    fixture.authorize = {
      if (fixture.calls == 1) throw IOException("Themes bridge not ready")
      true
    }
    val delivered = requireNotNull(fixture.deliver())
    assertEquals(imported.copy(autoApplyToken = request.token), delivered)
    assertEquals(2, fixture.calls)
    assertEquals(listOf(250L), fixture.waits)
    assertEquals(listOf("Themes bridge not ready"), fixture.failures.map { it.message })

    repeat(2) { assertEquals(delivered, fixture.deliver()) }
    assertEquals(2, fixture.calls)
    assertTrue(fixture.state.matches(request.copy(recheck = 42), imported))

    // A report replay must retain the app's existing single-consumption navigation contract.
    val accepted = IconImportLocalState().begin(request).accept(delivered).state
    val navigated = requireNotNull(accepted.consumeNavigation(delivered))
    assertNull(navigated.accept(delivered).state.consumeNavigation(delivered))
  }

  @Test
  fun exhaustedBridgeBudgetPreservesImportAndReportsEveryFailureWithoutResettingOnRecheck() {
    val fixture = Fixture()
    fixture.authorize = { throw IOException("Bridge unavailable ${fixture.calls}") }
    val delivered = requireNotNull(fixture.deliver())
    assertTrue(delivered.imported)
    assertEquals(imported.localId, delivered.localId)
    assertEquals(true, delivered.convertedReady)
    assertNull(delivered.autoApplyToken)
    assertTrue(delivered.message!!.contains("Bridge unavailable 3"))
    assertEquals(3, fixture.calls)
    assertEquals(3, fixture.failures.size)
    assertEquals(listOf(250L, 750L), fixture.waits)
    fixture.authorize = { error("Exhausted requests must not re-authorize") }
    assertEquals(delivered, fixture.deliver())
    assertTrue(fixture.state.matches(request.copy(recheck = 99), imported))
  }

  @Test
  fun consumedOrDeclinedTicketNeverRetriesEvenAfterARecheckOrDeliveryReplay() {
    val fixture = Fixture()
    fixture.authorize = { false }
    repeat(3) { assertEquals(imported, fixture.deliver()) }
    assertEquals(1, fixture.calls)
    assertTrue(fixture.waits.isEmpty())
    assertTrue(fixture.failures.isEmpty())

    // A replacement worker still asks the persistent Themes gate, which refuses consumed tokens.
    val replacement = Fixture()
    replacement.authorize = { false }
    assertNull(requireNotNull(replacement.deliver()).autoApplyToken)
    assertEquals(1, replacement.calls)
  }

  @Test
  fun contractOrSecurityErrorsAreReportedAndNeverTreatedAsTransientBridgeFailures() {
    for (failure in listOf(SecurityException("Wrong service"), IllegalArgumentException("Wrong local ID"),
      IllegalStateException("Rejected transaction"))) {
      val fixture = Fixture()
      fixture.authorize = { throw failure }
      val delivered = requireNotNull(fixture.deliver())
      assertTrue(delivered.imported)
      assertNull(delivered.autoApplyToken)
      assertEquals(1, fixture.calls)
      assertTrue(fixture.waits.isEmpty())
      assertSame(failure, fixture.failures.single())
      assertTrue(delivered.message!!.contains(failure.message!!))
    }
  }

  @Test
  fun aSlowBridgeFailureCannotAdmitAnotherAttemptAfterTheTimeBudget() {
    val fixture = Fixture()
    fixture.authorize = { fixture.now += 15_000; throw IOException("Timed out") }
    assertNull(requireNotNull(fixture.deliver()).autoApplyToken)
    assertEquals(1, fixture.calls)
    assertTrue(fixture.waits.isEmpty())
    assertEquals(1, fixture.failures.size)
  }

  @Test
  fun deadlineIsRecheckedAfterTheRetryWaitBeforeAnotherBinderCall() {
    val fixture = Fixture()
    fixture.authorize = { throw IOException("Disconnected") }
    fixture.afterPause = { fixture.now += 15_000 }
    assertNull(requireNotNull(fixture.deliver()).autoApplyToken)
    assertEquals(1, fixture.calls)
    assertEquals(listOf(250L), fixture.waits)
  }

  @Test
  fun expiredAndFutureDatedRequestsNeverAuthorizeEvenWhenAnImportedJournalExists() {
    for (at in listOf(request.startedAt - 1, request.startedAt + 600_001)) {
      val fixture = Fixture().apply { now = at }
      fixture.authorize = { error("Stale request cannot acquire a fresh ticket") }
      val delivered = requireNotNull(fixture.deliver())
      assertTrue(delivered.imported)
      assertNull(delivered.autoApplyToken)
      assertTrue(delivered.message!!.contains("expired"))
      assertEquals(0, fixture.calls)
    }
  }

  @Test
  fun freshnessAndUserChangesDuringBackoffCancelWithoutAnotherCallOrNotification() {
    val fixture = Fixture()
    fixture.authorize = { throw IOException("Not ready") }
    fixture.afterPause = { fixture.current = false }
    assertNull(fixture.deliver())
    assertEquals(1, fixture.calls)
    assertEquals(1, fixture.failures.size)
  }

  @Test
  fun expiryDuringBackoffKeepsTheImportButDoesNotAuthorize() {
    val fixture = Fixture().apply { now = request.startedAt + 599_900 }
    fixture.authorize = { throw IOException("Not ready") }
    val delivered = requireNotNull(fixture.deliver())
    assertTrue(delivered.imported)
    assertNull(delivered.autoApplyToken)
    assertTrue(delivered.message!!.contains("expired"))
    assertEquals(1, fixture.calls)
  }

  @Test
  fun interruptionDuringBackoffPropagatesWithoutASecondCallOrTerminalReport() {
    val fixture = Fixture()
    val cancelled = InterruptedException("Retirement")
    fixture.authorize = { throw IOException("Not ready") }
    fixture.afterPause = { throw cancelled }
    assertSame(cancelled, assertThrows(InterruptedException::class.java) { fixture.deliver() })
    assertEquals(1, fixture.calls)
    assertEquals(1, fixture.failures.size)
  }

  @Test
  fun authorizationCannotPublishToAChangedRequestAfterTheBinderReturns() {
    val fixture = Fixture()
    fixture.authorize = { fixture.current = false; true }
    assertNull(fixture.deliver())
    assertEquals(1, fixture.calls)
  }

  @Test
  fun requestExpiringDuringAuthorizationCannotPublishItsLateTicket() {
    val fixture = Fixture()
    fixture.authorize = { fixture.now = request.startedAt + 600_001; true }
    val delivered = requireNotNull(fixture.deliver())
    assertTrue(delivered.imported)
    assertNull(delivered.autoApplyToken)
    assertTrue(delivered.message!!.contains("expired"))
    assertEquals(1, fixture.calls)
  }

  @Test
  fun pendingRejectedConversionAndForeignReportsCannotAcquireAuthorization() {
    val fixture = Fixture()
    for (report in listOf(imported.copy(status = "IMPORTING"), imported.copy(convertedReady = false),
      imported.copy(localId = "foreign"), imported.copy(token = "foreign"))) {
      assertThrows(IllegalArgumentException::class.java) {
        fixture.state.forDelivery(report, { fixture.now }, { true },
          authorize = { error("Unapproved report cannot authorize") },
          pause = { error("Unapproved report cannot retry") }, onFailure = { throw AssertionError(it) })
      }
    }
    assertEquals(0, fixture.calls)
  }

  @Test
  fun authorizationCacheCannotCrossRequestPolicyUserSourceOrLocalIdentity() {
    val state = Fixture().state
    for (other in listOf(request.copy(token = "11111111-1111-1111-1111-111111111111"),
      request.copy(userId = 10), request.copy(sourceId = "another-source"),
      request.copy(startedAt = 101), request.copy(autoApply = false),
      request.copy(convertedOrigin = "CONVERTED_APK"))) {
      assertFalse(state.matches(other, imported))
    }
    for (other in listOf(imported.copy(token = "foreign"), imported.copy(userId = 10),
      imported.copy(sourceId = "foreign"), imported.copy(localId = "foreign"))) {
      assertFalse(state.matches(request, other))
    }
  }

  @Test
  fun retirementRefusesAnInFlightBinderAndDropsItsLateAuthorizationBeforeThreadExit() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val notification = AtomicReference<NativeIconImportReport?>()
    val worker = object : MonitorWorker {
      @Volatile override var closed = false
      override val thread = Thread {
        try {
          val state = IconAutoApplyAuthorization(request, requireNotNull(imported.localId), 100)
          notification.set(state.forDelivery(imported, { 100 }, { !closed },
            authorize = {
              entered.countDown()
              // Model a Binder transaction that does not finish when the Java thread is interrupted.
              var done = false
              while (!done) {
                try { done = release.await(2, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { /* The remote call still owns this worker. */ }
              }
              true
            }, pause = { error("No retry expected") }, onFailure = { throw AssertionError(it) }))
        } catch (unexpected: Throwable) { failure.set(unexpected) }
      }
      override fun start() = thread.start()
      override fun stop() { closed = true; thread.interrupt() }
    }
    val monitor = RestartableMonitor<Int>({ worker }, { throw AssertionError(it) })
    try {
      monitor.start(1)
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      monitor.stop()
      assertFalse(monitor.awaitStopped(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)))
      assertTrue(worker.thread.isAlive)
      release.countDown()
      assertTrue(monitor.awaitStopped(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)))
      assertNull(notification.get())
      assertNull(failure.get())
    } finally {
      release.countDown()
      monitor.stop()
      worker.thread.join(2_000)
    }
  }

  private inner class Fixture {
    var now = 100L
    var current = true
    var calls = 0
    val waits = mutableListOf<Long>()
    val failures = mutableListOf<Exception>()
    var authorize: () -> Boolean = { true }
    var afterPause: () -> Unit = {}
    val state by lazy { IconAutoApplyAuthorization(request, requireNotNull(imported.localId), now) }

    fun deliver() = state.forDelivery(imported, { now }, { current },
      authorize = { calls++; authorize() },
      pause = { waits += it; now += it; afterPause() },
      onFailure = { failures += it })
  }
}
