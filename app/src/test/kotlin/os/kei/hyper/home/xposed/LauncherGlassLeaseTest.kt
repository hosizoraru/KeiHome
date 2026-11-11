package os.kei.hyper.home.xposed

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherGlassLeaseTest {
  private val process = NativeMonetProcessIdentity(9000, 123L)

  @Test fun pairLeaseKeepsAllNineCombinationsDistinctAndSamePolicyRenewalsQuiet() {
    val lease = LauncherGlassLease()
    var at = 100L
    var exchanges = 0
    fun sync(policy: Int) = lease.synchronize(policy, 0, { at++ }, { true }) { attempted ->
      exchanges++; attempted(); process
    }
    for (dock in 0..2) for (search in 0..2) {
      val policy = dock or (search shl 2)
      assertTrue("policy=$policy", sync(policy))
      assertEquals(policy, lease.acknowledgedStyle(process, 0, at))
      repeat(3) { assertFalse(sync(policy)) }
    }
    assertEquals(36, exchanges)
  }

  @Test fun changingOnlyDockOrOnlySearchInvalidatesThePreviousPairChoice() {
    val lease = LauncherGlassLease()
    var at = 100L
    for (policy in listOf(0, 4, 8, 9, 10, 6, 2, 1, 0)) {
      assertTrue(lease.synchronize(policy, 0, { at++ }, { true }) { process })
      assertEquals(policy, lease.acknowledgedStyle(process, 0, at))
      assertFalse(lease.synchronize(policy, 0, { at++ }, { true }) { process })
    }
  }

  @Test fun aQuietPairRenewalExtendsItsExpiryWithoutReusingTheOriginalDeadline() {
    val lease = LauncherGlassLease()
    assertTrue(lease.synchronize(9, 0, { 100L }, { true }) { process })
    assertFalse(lease.synchronize(9, 0, { 45_099L }, { true }) { process })
    assertEquals(9, lease.acknowledgedStyle(process, 0, 45_100))
    assertEquals(9, lease.acknowledgedStyle(process, 0, 90_098))
    assertNull(lease.acknowledgedStyle(process, 0, 90_099))
    assertTrue(lease.synchronize(9, 0, { 90_099L }, { true }) { process })
  }

  @Test fun pairPreflightRejectionPreservesOldUserAndOriginalExpiryWithoutWriting() {
    val lease = LauncherGlassLease()
    assertTrue(lease.synchronize(9, 0, { 100L }, { true }) { process })
    val output = ByteArrayOutputStream()
    var writes = 0
    assertFalse(lease.synchronize(6, 10, { 200L }, { true }) { attempted ->
      val accepted = NativeMonetProtocol.exchange(ByteArrayInputStream(words(0x334d484b, 1, 1_010_123, 16)),
        output, 1_010_123, 1_010_123, NativeMonetProtocol.launcherGlassPairRequest(6),
        onWriteAttempt = { writes++; attempted() })
      if (accepted) process else null
    })
    assertEquals(0, writes)
    assertEquals(0, output.size())
    assertEquals(9, lease.acknowledgedStyle(process, 0, 45_099))
    assertNull(lease.acknowledgedStyle(process, 10, 200))
    assertNull(lease.acknowledgedStyle(process, 0, 45_100))
    assertTrue(lease.synchronize(9, 0, { 45_100L }, { true }) { process })
  }

  @Test fun failedPairWriteInvalidatesOldUserAckAndRequiresReconciliation() {
    val lease = LauncherGlassLease()
    lease.synchronize(9, 0, { 100L }, { true }) { process }
    var writes = 0
    val output = object : OutputStream() {
      override fun write(value: Int) {
        assertEquals(1, writes)
        throw InterruptedIOException("partial pair")
      }
    }
    assertThrows(InterruptedIOException::class.java) {
      lease.synchronize(6, 10, { 200L }, { true }) { attempted ->
        NativeMonetProtocol.exchange(ByteArrayInputStream(words(0x334d484b, 1, 1_010_123, 80)),
          output, 1_010_123, 1_010_123, NativeMonetProtocol.launcherGlassPairRequest(6),
          onWriteAttempt = { writes++; attempted() })
        process
      }
    }
    assertEquals(1, writes)
    assertNull(lease.acknowledgedStyle(process, 0, 200))
    assertNull(lease.acknowledgedStyle(process, 10, 200))
    assertTrue(lease.synchronize(9, 0, { 300L }, { true }) { process })
    assertFalse(lease.synchronize(9, 0, { 400L }, { true }) { process })
  }

  @Test fun pairLeaseDoesNotTransferAcrossUsersProcessesOrRetiredSelection() {
    val lease = LauncherGlassLease()
    assertTrue(lease.synchronize(9, 0, { 100L }, { true }) { process })
    assertTrue(lease.synchronize(9, 10, { 200L }, { true }) { process })
    assertNull(lease.acknowledgedStyle(process, 0, 200))
    val restarted = process.copy(startTicks = process.startTicks + 1)
    assertTrue(lease.synchronize(9, 10, { 300L }, { true }) { restarted })
    assertNull(lease.acknowledgedStyle(process, 10, 300))
    var current = true
    assertFalse(lease.synchronize(6, 10, { 400L }, { current }) { attempted ->
      attempted(); current = false; restarted
    })
    assertNull(lease.acknowledgedStyle(restarted, 10, 400))
    assertTrue(lease.synchronize(9, 10, { 500L }, { true }) { restarted })
  }

  @Test fun invalidPairPoliciesCannotExchangeOrEraseAnAcceptedPair() {
    val lease = LauncherGlassLease()
    assertTrue(lease.synchronize(9, 0, { 100L }, { true }) { process })
    for (policy in listOf(-1, Int.MIN_VALUE, 3, 7, 11, 12, 15, 16, 64, Int.MAX_VALUE)) {
      assertFalse(lease.synchronize(policy, 0, { 200L }, { true }) { error("must not exchange") })
      assertEquals(9, lease.acknowledgedStyle(process, 0, 200))
    }
  }

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

  private fun words(vararg values: Int): ByteArray =
    ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
      values.forEach { putInt(it) }
    }.array()
}
