package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockGlassLeaseTest {
  private val process = NativeMonetProcessIdentity(9000, 123L)

  @Test fun firstOffReconcilesResidentStateButUnchangedHeartbeatsDoNotRefresh() {
    val lease = DockGlassLease()
    fun sync(enabled: Boolean, at: Long) = lease.synchronize(enabled, 0, { at }, { true }) { process }
    assertTrue(sync(false, 100))
    assertEquals(false, lease.acknowledgedEnabled(process, 0, 100))
    assertFalse(sync(false, 15_100))
    assertTrue(sync(true, 15_200))
    assertFalse(sync(true, 30_200))
    assertNull(lease.acknowledgedEnabled(process, 0, 75_200))
    assertTrue(sync(true, 75_200))
  }

  @Test fun lostAckMakesStateUnknownAndNextAckReconcilesThePossibleWrite() {
    val lease = DockGlassLease()
    assertTrue(lease.synchronize(true, 0, { 100 }, { true }) { process })
    assertFalse(lease.synchronize(false, 0, { 200 }, { true }) { attempted -> attempted(); null })
    assertNull(lease.acknowledgedEnabled(process, 0, 200))
    assertFalse(lease.hasAcknowledgement(0, 200))
    assertTrue(lease.synchronize(true, 0, { 300 }, { true }) { process })
  }

  @Test fun userProcessAndClockChangesCannotReuseOldAck() {
    val lease = DockGlassLease()
    assertTrue(lease.synchronize(true, 0, { 100 }, { true }) { process })
    assertNull(lease.acknowledgedEnabled(process, 10, 100))
    assertNull(lease.acknowledgedEnabled(process.copy(startTicks = 124), 0, 100))
    assertNull(lease.acknowledgedEnabled(process, 0, 99))
    assertFalse(lease.synchronize(false, 0, { 200 }, { false }) { error("must not exchange") })
    var current = true
    assertFalse(lease.synchronize(false, 0, { 200 }, { current }) { attempted ->
      attempted(); current = false; process
    })
    assertNull(lease.acknowledgedEnabled(process, 0, 200))
    assertTrue(lease.synchronize(false, 10, { 300 }, { true }) { process })
    assertEquals(false, lease.acknowledgedEnabled(process, 10, 300))
  }
  @Test fun cornerChangesRefreshOnlyAnEnabledPlateAndHeartbeatsStayQuiet() {
    val lease = DockGlassLease()
    fun sync(enabled: Boolean, corner: Int) =
      lease.synchronize(enabled, 0, { 100L }, { true }, corner) { process }
    assertTrue(sync(false, 0))
    assertFalse(sync(false, 10_240))
    assertTrue(sync(true, 10_240))
    assertFalse(sync(true, 10_240))
    assertTrue(sync(true, 12_800))
    assertFalse(sync(true, 12_800))
  }

  @Test fun styleOnlyChangesReconcileOnceAndUnchangedRenewalsStayQuiet() {
    val lease = DockGlassLease()
    fun sync(enabled: Boolean, style: Int) =
      lease.synchronize(enabled, 0, { 100L }, { true }, 10_240, style) { process }
    assertTrue(sync(true, 1))
    assertEquals(1, lease.acknowledgedStyle(process, 0, 100))
    assertFalse(sync(true, 1))
    assertTrue(sync(true, 0))
    assertEquals(0, lease.acknowledgedStyle(process, 0, 100))
    assertFalse(sync(true, 0))
    assertTrue(sync(false, 0))
    assertTrue(sync(false, 1))
    assertFalse(sync(false, 1))
  }

  @Test fun styleAckCannotSurviveLostWritesForeignIdentityExpiryOrStaleSelection() {
    val lease = DockGlassLease()
    assertTrue(lease.synchronize(true, 0, { 100L }, { true }, cornerStyle = 1) { process })
    assertNull(lease.acknowledgedStyle(process.copy(startTicks = 124), 0, 100))
    assertNull(lease.acknowledgedStyle(process, 10, 100))
    assertNull(lease.acknowledgedStyle(process, 0, 99))
    assertNull(lease.acknowledgedStyle(process, 0, 45_100))
    assertFalse(lease.synchronize(true, 0, { 200L }, { true }, cornerStyle = 0) { attempted ->
      attempted(); null
    })
    assertNull(lease.acknowledgedStyle(process, 0, 200))
    assertTrue(lease.synchronize(true, 0, { 300L }, { true }, cornerStyle = 1) { process })
    var current = true
    assertFalse(lease.synchronize(true, 0, { 400L }, { current }, cornerStyle = 0) { attempted ->
      attempted(); current = false; process
    })
    assertNull(lease.acknowledgedStyle(process, 0, 400))
    assertTrue(lease.synchronize(true, 0, { 500L }, { true }, cornerStyle = 1) { process })
    assertFalse(lease.synchronize(true, 0, { 600L }, { true }, cornerStyle = 1) { process })
  }

}
