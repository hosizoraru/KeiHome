package os.kei.hyper.home.iconpack.perfect

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class PerfectIconOutputLimitTest {
  @Test fun integerAndBulkWritesShareTheExactLimit() {
    val bytes = ByteArrayOutputStream()
    val output = PerfectIconOutputLimit(bytes, 3)
    output.write(byteArrayOf(1, 2))
    output.write(3)
    assertThrows(IOException::class.java) { output.write(4) }
    assertThrows(IOException::class.java) { output.write(byteArrayOf(4, 5)) }
    assertArrayEquals(byteArrayOf(1, 2, 3), bytes.toByteArray())
  }

  @Test fun zipFinalizationCannotWriteBeyondTheLimit() {
    val bytes = ByteArrayOutputStream()
    assertThrows(IOException::class.java) {
      ZipOutputStream(PerfectIconOutputLimit(bytes, 60)).use { zip ->
        zip.putNextEntry(ZipEntry("a"))
        zip.write(byteArrayOf(1, 2, 3))
        zip.closeEntry()
      }
    }
    assertTrue(bytes.size() <= 60)
  }

  @Test fun cancellationAndInvalidSlicesDoNotWriteAnything() {
    val bytes = ByteArrayOutputStream()
    val cancelled = PerfectIconOutputLimit(bytes, 3) { throw CancellationException() }
    assertThrows(CancellationException::class.java) { cancelled.write(byteArrayOf(1)) }
    assertThrows(IndexOutOfBoundsException::class.java) {
      PerfectIconOutputLimit(bytes, 3).write(byteArrayOf(1), 0, 2)
    }
    assertEquals(0, bytes.size())
  }
}
