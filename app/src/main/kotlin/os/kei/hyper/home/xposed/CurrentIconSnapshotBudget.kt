package os.kei.hyper.home.xposed

import java.io.InputStream
import java.io.OutputStream
import os.kei.hyper.home.iconpack.IconPackLimits

/** One expanded budget includes source artwork, generated metadata and stock fallbacks. */
internal class CurrentIconSnapshotBudget(
  private val limits: IconPackLimits = IconPackLimits(),
  private val check: () -> Unit = {},
) {
  private var entries = 0
  private var expanded = 0L
  private val buffer = ByteArray(32 * 1024)

  fun copy(input: InputStream, output: OutputStream, expectedSize: Long): Long {
    check()
    require(entries < limits.maxEntries) { "Snapshot entry limit exceeded" }
    require(expectedSize in 0..limits.maxEntryBytes &&
      expectedSize <= limits.maxExpandedBytes - expanded) { "Snapshot expansion limit exceeded" }
    entries++
    var copied = 0L
    while (true) {
      check()
      val count = input.read(buffer)
      check()
      if (count < 0) break
      require(count.toLong() <= expectedSize - copied &&
        count.toLong() <= limits.maxExpandedBytes - expanded) { "Snapshot expansion limit exceeded" }
      output.write(buffer, 0, count)
      copied += count
      expanded += count
    }
    require(copied == expectedSize) { "Snapshot entry length mismatch" }
    return copied
  }

  /** Counts ZIP headers and the central directory as well as compressed payload bytes. */
  fun boundedOutput(output: OutputStream): OutputStream = object : OutputStream() {
    private var written = 0L

    override fun write(value: Int) {
      check()
      require(written < limits.maxInputBytes) { "Snapshot archive size limit exceeded" }
      output.write(value)
      written++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
      check()
      require(length.toLong() <= limits.maxInputBytes - written) { "Snapshot archive size limit exceeded" }
      output.write(bytes, offset, length)
      written += length
    }

    override fun flush() = output.flush()
    override fun close() = output.close()
  }
}
