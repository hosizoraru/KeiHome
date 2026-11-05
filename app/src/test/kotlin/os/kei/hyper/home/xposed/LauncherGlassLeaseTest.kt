package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherGlassLeaseTest {
  private val process = NativeMonetProcessIdentity(9000, 123L)

  @Test fun systemReconcilesResidentStateAndAllStyleChangesNotifyOnce() {
    val lease = LauncherGlassLease()
    var at = 100L
    fun sync(style: Int) = lease.synchronize(style, 0, { at++ }, { true }) { attempted -> attempted(); process }
    assertTrue(sync(0))
    repeat(3) { assertFalse(sync(0)) }
    for (style in listOf(1, 2, 0, 2, 1, 0)) {
      assertTrue(sync(style))
      assertEquals(style, lease.acknowledgedStyle(process, 0, at))
      repeat(4) { assertFalse(sync(style)) }
    }
  }

  @Test fun aWriteWithoutAnAckInvalidatesTheLeaseAndReconcilesEvenThePreviousChoice() {
    for (style in 0..2) {
      val lease = LauncherGlassLease()
      assertTrue(lease.synchronize(style, 0, { 100L }, { true }) { process })
      assertFalse(lease.synchronize((style + 1) % 3, 0, { 200L }, { true }) { attempted ->
        attempted(); null
      })
      assertNull(lease.acknowledgedStyle(process, 0, 200))
      assertTrue(lease.synchronize(style, 0, { 300L }, { true }) { process })
      assertFalse(lease.synchronize(style, 0, { 400L }, { true }) { process })
    }
  }

  @Test fun failuresBeforeWritingDoNotEraseThePreviouslyAcceptedLease() {
    val lease = LauncherGlassLease()
    lease.synchronize(1, 0, { 100L }, { true }) { process }
    assertFalse(lease.synchronize(2, 0, { 200L }, { true }) { null })
    assertEquals(1, lease.acknowledgedStyle(process, 0, 200))
    assertThrows(IllegalStateException::class.java) {
      lease.synchronize(2, 0, { 300L }, { true }) { attempted ->
        attempted(); error("ACK lost")
      }
    }
    assertNull(lease.acknowledgedStyle(process, 0, 300))
  }

  @Test fun userProcessClockAndLeaseExpiryCannotReuseAnOldAck() {
    val lease = LauncherGlassLease()
    lease.synchronize(2, 0, { 100L }, { true }) { process }
    assertEquals(2, lease.acknowledgedStyle(process, 0, 45_099))
    assertNull(lease.acknowledgedStyle(process, 0, 45_100))
    assertNull(lease.acknowledgedStyle(process, 10, 100))
    assertNull(lease.acknowledgedStyle(process.copy(startTicks = 124), 0, 100))
    assertNull(lease.acknowledgedStyle(null, 0, 100))
    assertNull(lease.acknowledgedStyle(process, 0, 99))
    assertTrue(lease.synchronize(2, 0, { 45_100L }, { true }) { process })
    assertFalse(lease.synchronize(2, 0, { 45_101L }, { true }) { process })
    assertTrue(lease.synchronize(2, 10, { 45_102L }, { true }) { process })
    assertTrue(lease.synchronize(2, 10, { 45_103L }, { true }) { process.copy(startTicks = 124) })
  }

  @Test fun staleSelectionAfterAWriteCannotBeReportedAsAccepted() {
    val lease = LauncherGlassLease()
    var current = true
    assertFalse(lease.synchronize(1, 0, { 100L }, { current }) { attempted ->
      attempted(); current = false; process
    })
    assertNull(lease.acknowledgedStyle(process, 0, 100))
    assertTrue(lease.synchronize(0, 0, { 200L }, { true }) { process })
    assertFalse(lease.synchronize(0, 0, { 300L }, { true }) { process })
  }

  @Test fun inactiveAndInvalidRequestsDoNotExchange() {
    val lease = LauncherGlassLease()
    for (style in listOf(-1, 3, Int.MAX_VALUE))
      assertFalse(lease.synchronize(style, 0, { 0L }, { true }) { error("must not exchange") })
    assertFalse(lease.synchronize(1, -1, { 0L }, { true }) { error("must not exchange") })
    assertFalse(lease.synchronize(1, 0, { 0L }, { false }) { error("must not exchange") })
  }
}
