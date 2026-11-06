package os.kei.hyper.home.xposed

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.IconPackLimits

class CurrentIconSnapshotBudgetTest {
  private class Sink : OutputStream() {
    var count = 0L
    override fun write(value: Int) { count++ }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length }
  }

  private fun generated(size: Long): InputStream = object : InputStream() {
    private var remaining = size
    override fun read(): Int = if (remaining == 0L) -1 else { remaining--; 0 }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      assertTrue("Copy must use a bounded buffer", length <= 32 * 1024)
      if (remaining == 0L) return -1
      val count = minOf(remaining, length.toLong()).toInt()
      remaining -= count
      return count
    }
  }

  @Test fun sharedDefaultsAcceptRealPhoneExpandedSizeAndLargeDescriptor() {
    val budget = CurrentIconSnapshotBudget()
    val sink = Sink()
    val descriptor = 1_039_745L
    budget.copy(generated(descriptor), sink, descriptor)
    val remaining = 71_742_038L - descriptor
    budget.copy(generated(remaining), sink, remaining)
    assertEquals(71_742_038L, sink.count)
  }

  @Test fun exactSharedEntryAndExpandedLimitsSucceedThenOneByteFails() {
    val limits = IconPackLimits()
    val budget = CurrentIconSnapshotBudget(limits)
    val sink = Sink()
    var remaining = limits.maxExpandedBytes
    while (remaining > 0) {
      val size = minOf(remaining, limits.maxEntryBytes)
      assertEquals(size, budget.copy(generated(size), sink, size))
      remaining -= size
    }
    assertEquals(limits.maxExpandedBytes, sink.count)
    assertThrows(IllegalArgumentException::class.java) { budget.copy(generated(1), sink, 1) }
    assertThrows(IllegalArgumentException::class.java) {
      CurrentIconSnapshotBudget(limits).copy(generated(limits.maxEntryBytes + 1), sink, limits.maxEntryBytes + 1)
    }
  }

  @Test fun exactSharedEntryCountSucceedsIncludingEmptyEntries() {
    val limits = IconPackLimits()
    val budget = CurrentIconSnapshotBudget(limits)
    val sink = Sink()
    repeat(limits.maxEntries) { budget.copy(generated(0), sink, 0) }
    assertThrows(IllegalArgumentException::class.java) { budget.copy(generated(0), sink, 0) }
  }

  @Test fun copiedDataIsPreservedAndMetadataAndFallbackShareExpandedBudget() {
    val budget = CurrentIconSnapshotBudget(IconPackLimits(maxExpandedBytes = 5))
    val output = ByteArrayOutputStream()
    budget.copy(byteArrayOf(1, 2, 3).inputStream(), output, 3)
    budget.copy(byteArrayOf(4, 5).inputStream(), output, 2)
    assertTrue(byteArrayOf(1, 2, 3, 4, 5).contentEquals(output.toByteArray()))
    assertThrows(IllegalArgumentException::class.java) { budget.copy(generated(1), output, 1) }
  }

  @Test fun incorrectDeclaredLengthsFailBeforeExcessBytesAreWritten() {
    val sink = Sink()
    assertThrows(IllegalArgumentException::class.java) {
      CurrentIconSnapshotBudget().copy(generated(4), sink, 3)
    }
    assertEquals(0L, sink.count)
    assertThrows(IllegalArgumentException::class.java) {
      CurrentIconSnapshotBudget().copy(generated(2), Sink(), 3)
    }
    assertThrows(IllegalArgumentException::class.java) {
      CurrentIconSnapshotBudget().copy(generated(0), Sink(), -1)
    }
  }

  @Test fun exactCompressedArchiveLimitSucceedsAndOneByteFails() {
    val limits = IconPackLimits()
    val sink = Sink()
    val output = CurrentIconSnapshotBudget(limits).boundedOutput(sink)
    val buffer = ByteArray(32 * 1024)
    repeat((limits.maxInputBytes / buffer.size).toInt()) { output.write(buffer) }
    assertEquals(limits.maxInputBytes, sink.count)
    assertThrows(IllegalArgumentException::class.java) { output.write(1) }
    assertThrows(IllegalArgumentException::class.java) { output.write(buffer, 0, 1) }
    assertEquals(limits.maxInputBytes, sink.count)
  }

  @Test fun cancellationDuringEntryCopyStopsBeforeNextWrite() {
    var active = true
    val budget = CurrentIconSnapshotBudget { check(active) }
    val sink = Sink()
    val input = object : InputStream() {
      override fun read(): Int = error("Use buffered reads")
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        active = false
        return length
      }
    }
    assertThrows(IllegalStateException::class.java) { budget.copy(input, sink, 65536) }
    assertEquals(0L, sink.count)
    assertThrows(IllegalStateException::class.java) { budget.boundedOutput(sink).write(1) }
  }
}
