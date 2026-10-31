package os.kei.hyper.home.xposed

import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeMaterialLeaseTest {
  private val process = NativeMonetProcessIdentity(42, 100L)

  @Test fun materialOptInDoesNotRequireAMonetPolicyAndHeartbeatsNeverRefreshUnchangedIcons() {
    val lease = ThemeMaterialLease()
    var exchanges = 0
    var time = 0L
    fun sync(enabled: Boolean): Boolean = lease.synchronize(enabled, 0, { time }, { true }) {
      it()
      exchanges++
      process
    }
    assertFalse(sync(false))
    assertTrue(sync(true))
    repeat(10) {
      time += 15_000
      assertFalse(sync(true))
    }
    assertTrue(sync(false))
    time += 15_000
    assertFalse(sync(false))
    assertTrue(sync(true))
    assertEquals(15, exchanges)
  }

  @Test fun expiredEnableNeedsReconciliationWhileDisabledDoesNot() {
    val lease = ThemeMaterialLease()
    fun sync(enabled: Boolean, time: Long) = lease.synchronize(enabled, 0, { time }, { true }) { process }
    assertTrue(sync(true, 0))
    assertFalse(sync(true, 44_999))
    assertTrue(sync(true, 89_999))
    assertTrue(sync(false, 90_000))
    assertFalse(sync(false, 150_000))
  }

  @Test fun rejectedOrMissingCapabilityDoesNotCacheAnAcknowledgementOrExtendTheLease() {
    val lease = ThemeMaterialLease()
    assertFalse(lease.synchronize(true, 0, { 0L }, { true }) { null })
    assertTrue(lease.synchronize(true, 0, { 1L }, { true }) { process })
    assertFalse(lease.synchronize(true, 0, { 30_000L }, { true }) { null })
    assertTrue(lease.synchronize(true, 0, { 45_001L }, { true }) { process })
    // A rejected disable cannot make the next confirmed disable look unchanged.
    assertFalse(lease.synchronize(false, 0, { 45_002L }, { true }) { null })
    assertTrue(lease.synchronize(false, 0, { 45_003L }, { true }) { process })
  }

  @Test fun staleGenerationCannotSendOrAcceptAnAckOrSuppressSuccessorReconciliation() {
    val lease = ThemeMaterialLease()
    var exchanges = 0
    assertFalse(lease.synchronize(true, 0, { 0L }, { false }) { exchanges++; process })
    assertEquals(0, exchanges)
    var current = true
    assertFalse(lease.synchronize(true, 0, { 0L }, { current }) {
      it()
      current = false
      process
    })
    assertTrue(lease.synchronize(true, 0, { 1L }, { true }) { process })
    current = true
    assertFalse(lease.synchronize(false, 0, { 2L }, { current }) {
      it()
      current = false
      process
    })
    assertTrue(lease.synchronize(false, 0, { 3L }, { true }) { process })
  }

  @Test fun missingAckThenReturningToPreviousValueReconcilesOnceInEitherDirection() {
    for (original in listOf(false, true)) {
      val lease = ThemeMaterialLease()
      var time = 0L
      fun sync(enabled: Boolean) = lease.synchronize(enabled, 0, { time }, { true }) {
        it()
        process
      }
      assertEquals(original, sync(original))
      assertFalse(lease.synchronize(!original, 0, { ++time }, { true }) {
        it() // The native side may have committed this opposite value.
        null
      })
      assertTrue(sync(original))
      repeat(10) {
        time += 15_000
        assertFalse(sync(original))
      }
    }
  }

  @Test fun staleAckThenReturningToPreviousValueReconcilesOnceInEitherDirection() {
    for (original in listOf(false, true)) {
      val lease = ThemeMaterialLease()
      assertEquals(original, lease.synchronize(original, 0, { 0L }, { true }) { it(); process })
      var current = true
      assertFalse(lease.synchronize(!original, 0, { 1L }, { current }) {
        it()
        current = false
        process
      })
      assertTrue(lease.synchronize(original, 0, { 2L }, { true }) { it(); process })
      assertFalse(lease.synchronize(original, 0, { 3L }, { true }) { it(); process })
    }
  }

  @Test fun skippedOrFailedBeforeWriteDoesNotDirtyThePreviouslyConfirmedValue() {
    for (original in listOf(false, true)) {
      val lease = ThemeMaterialLease()
      assertEquals(original, lease.synchronize(original, 0, { 0L }, { true }) { it(); process })
      var exchanges = 0
      assertFalse(lease.synchronize(!original, 0, { 1L }, { false }) {
        exchanges++
        it()
        process
      })
      assertEquals(0, exchanges)
      repeat(3) {
        // Connection, capability and pre-write cancellation failures report no write.
        assertFalse(lease.synchronize(!original, 0, { 2L }, { true }) { null })
        assertFalse(lease.synchronize(original, 0, { 3L }, { true }) { it(); process })
      }
    }
  }

  @Test fun dirtySurvivesSkippedAndUnconfirmedExchangesUntilTheNextCurrentAck() {
    val lease = ThemeMaterialLease()
    assertFalse(lease.synchronize(false, 0, { 0L }, { true }) { it(); process })
    repeat(3) {
      assertFalse(lease.synchronize(true, 0, { 1L }, { true }) { it(); null })
    }
    assertFalse(lease.synchronize(false, 0, { 2L }, { false }) {
      throw AssertionError("A stale owner must not exchange")
    })
    assertFalse(lease.synchronize(false, 0, { 3L }, { true }) { null })
    var current = true
    assertFalse(lease.synchronize(false, 0, { 4L }, { current }) {
      it()
      current = false
      process
    })
    assertTrue(lease.synchronize(false, 0, { 5L }, { true }) { it(); process })
    assertFalse(lease.synchronize(false, 0, { 6L }, { true }) { it(); process })
  }

  @Test fun exceptionsOnlyLeaveDirtyStateIfAWriteWasAttempted() {
    for (attempted in listOf(false, true)) {
      val lease = ThemeMaterialLease()
      assertFalse(lease.synchronize(false, 0, { 0L }, { true }) { it(); process })
      assertThrows(InterruptedIOException::class.java) {
        lease.synchronize(true, 0, { 1L }, { true }) {
          if (attempted) it()
          throw InterruptedIOException()
        }
      }
      assertEquals(attempted, lease.synchronize(false, 0, { 2L }, { true }) { it(); process })
      assertFalse(lease.synchronize(false, 0, { 3L }, { true }) { it(); process })
    }
  }

  @Test fun uncertainFirstEnableStillReconcilesTheFirstConfirmedDisableOnce() {
    val lease = ThemeMaterialLease()
    assertFalse(lease.synchronize(true, 0, { 0L }, { true }) { it(); null })
    assertTrue(lease.synchronize(false, 0, { 1L }, { true }) { it(); process })
    assertFalse(lease.synchronize(false, 0, { 2L }, { true }) { it(); process })
  }

  @Test fun launcherProcessAndForegroundUserChangesReconcileOnlyAnEnabledSetting() {
    val lease = ThemeMaterialLease()
    assertTrue(lease.synchronize(true, 0, { 0L }, { true }) { process })
    val reusedPid = process.copy(startTicks = 200)
    assertTrue(lease.synchronize(true, 0, { 1L }, { true }) { reusedPid })
    assertFalse(lease.synchronize(true, 0, { 2L }, { true }) { reusedPid })
    assertTrue(lease.synchronize(true, 10, { 3L }, { true }) { reusedPid })
    val other = process.copy(pid = 99)
    assertFalse(lease.synchronize(false, 11, { 4L }, { true }) { other })
    assertFalse(lease.synchronize(false, 11, { 5L }, { true }) { other })
  }

  @Test fun leaseAgeIncludesExchangeTimeAndJavaReloadDoesNotReplayAnotherOwnersHeartbeat() {
    val oldOwner = ThemeMaterialLease()
    var time = 1L
    assertTrue(oldOwner.synchronize(true, 0, { time }, { true }) {
      time += 2_000
      process
    })
    time = 45_001
    assertTrue(oldOwner.synchronize(true, 0, { time }, { true }) { process })
    val newOwner = ThemeMaterialLease()
    assertTrue(newOwner.synchronize(true, 0, { time }, { true }) { process })
    assertFalse(newOwner.synchronize(true, 0, { time + 15_000 }, { true }) { process })
  }
}
