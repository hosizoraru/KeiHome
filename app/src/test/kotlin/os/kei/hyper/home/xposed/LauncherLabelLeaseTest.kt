package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test

class LauncherLabelLeaseTest {
  private val process = NativeMonetProcessIdentity(42, 100)

  @Test fun switchingBetweenAllModesRefreshesButHeartbeatsDoNot() {
    val lease = LauncherLabelLease()
    var time = 0L
    fun sync(mode: Int) = lease.synchronize(mode, 0, { time++ }, { true }) { it(); process }
    assertFalse(sync(0))
    for (mode in listOf(1, 2, 0, 2, 1, 0)) {
      assertTrue(sync(mode))
      repeat(4) { assertFalse(sync(mode)) }
    }
  }

  @Test fun aMissingAckCannotHideAReturnToThePreviouslyConfirmedMode() {
    for (original in 0..2) {
      val lease = LauncherLabelLease()
      lease.synchronize(original, 0, { 0L }, { true }) { it(); process }
      assertFalse(lease.synchronize((original+1)%3, 0, { 1L }, { true }) { it(); null })
      assertNull(lease.acknowledgedMode(process, 2))
      assertTrue(lease.synchronize(original, 0, { 2L }, { true }) { it(); process })
      assertFalse(lease.synchronize(original, 0, { 3L }, { true }) { it(); process })
    }
  }

  @Test fun staleAckIsNotReportedAndForcesSuccessorReconciliation() {
    val lease = LauncherLabelLease()
    var current = true
    assertFalse(lease.synchronize(1, 0, { 0L }, { current }) { it(); current = false; process })
    assertNull(lease.acknowledgedMode(process, 1))
    assertTrue(lease.synchronize(1, 0, { 1L }, { true }) { it(); process })
  }

  @Test fun processAndLeaseBoundariesCannotReuseAnOldAcknowledgement() {
    val lease = LauncherLabelLease()
    assertTrue(lease.synchronize(2, 0, { 1L }, { true }) { it(); process })
    assertEquals(2, lease.acknowledgedMode(process, 45_000))
    assertNull(lease.acknowledgedMode(process, 45_001))
    assertNull(lease.acknowledgedMode(process.copy(startTicks = 101), 2))
    assertTrue(lease.synchronize(2, 0, { 45_001L }, { true }) { it(); process })
    assertFalse(lease.synchronize(2, 0, { 45_002L }, { true }) { it(); process })
  }

  @Test fun invalidAndInactiveRequestsNeverWrite() {
    val lease = LauncherLabelLease()
    var calls = 0
    for (mode in listOf(-1, 3, Int.MAX_VALUE))
      assertFalse(lease.synchronize(mode, 0, { 0L }, { true }) { calls++; process })
    assertFalse(lease.synchronize(1, 0, { 0L }, { false }) { calls++; process })
    assertEquals(0, calls)
  }
}
