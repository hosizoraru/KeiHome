package os.kei.hyper.home.xposed

import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Local temporary FDs only: no provider permissions, live service, or device settings. */
@RunWith(AndroidJUnit4::class)
class ThemeProviderInputStreamDeviceTest {
  private fun regular(bytes: ByteArray, block: (ParcelFileDescriptor) -> Unit) {
    val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    val directory = Files.createTempDirectory(cache.toPath(), "theme-provider-stream-").toFile()
    try {
      val file = File(directory, "asset").apply { writeBytes(bytes) }
      ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use(block)
    } finally {
      directory.deleteRecursively()
    }
  }

  private fun asset(
    original: ParcelFileDescriptor,
    start: Long = 0,
    length: Long = AssetFileDescriptor.UNKNOWN_LENGTH,
  ) = AssetFileDescriptor(ParcelFileDescriptor.dup(original.fileDescriptor), start, length)

  @Test fun unknownLengthRegularAtEofReadsWholeAssetWithoutMovingCachedDupCursor() {
    val bytes = ByteArray(257) { it.toByte() }
    regular(bytes) { original ->
      val eof = Os.lseek(original.fileDescriptor, 0, OsConstants.SEEK_END)
      repeat(2) {
        val descriptor = asset(original)
        val ownedFd = descriptor.fileDescriptor
        themeProviderInputStream(descriptor).use { input ->
          assertEquals(0, input.read(byteArrayOf(), 0, 0))
          assertArrayEquals(bytes, input.readBytes())
          assertEquals(-1, input.read())
          assertEquals(0, input.read(byteArrayOf(), 0, 0))
          assertEquals("pread must not move the provider's shared cursor", eof,
            Os.lseek(original.fileDescriptor, 0, OsConstants.SEEK_CUR))
        }
        assertFalse("Closing the stream must close its owned duplicate", ownedFd.valid())
        assertTrue("The provider's cached FD remains owned by the provider", original.fileDescriptor.valid())
      }
    }
  }

  @Test fun nonzeroOffsetFiniteLengthReadsOnlyWindowAndLeavesPaddingUnconsumed() {
    val prefix = "prefix-not-an-icon:".toByteArray()
    val payload = byteArrayOf(0x80.toByte(), 2, 3, 4, 5, 6, 0xff.toByte())
    val padding = ":padding-not-an-icon".toByteArray()
    regular(prefix + payload + padding) { original ->
      val eof = Os.lseek(original.fileDescriptor, 0, OsConstants.SEEK_END)
      val descriptor = asset(original, prefix.size.toLong(), payload.size.toLong())
      val ownedFd = descriptor.fileDescriptor
      themeProviderInputStream(descriptor).use { input ->
        assertEquals(128, input.read())
        val buffer = ByteArray(64) { 0x55 }
        val count = input.read(buffer, 3, buffer.size - 3)
        assertEquals(payload.size - 1, count)
        assertArrayEquals(payload.drop(1).toByteArray(), buffer.copyOfRange(3, 3 + count))
        assertTrue(buffer.take(3).all { it == 0x55.toByte() })
        assertTrue(buffer.drop(3 + count).all { it == 0x55.toByte() })
        assertEquals(-1, input.read(buffer))
        assertEquals(-1, input.read())
        assertEquals(0, input.read(buffer, 0, 0))
        assertEquals(eof, Os.lseek(original.fileDescriptor, 0, OsConstants.SEEK_CUR))
      }
      assertFalse(ownedFd.valid())
      themeProviderInputStream(asset(original, prefix.size.toLong(), 0)).use {
        assertEquals("A declared zero length must not become the backing-file size", -1, it.read())
      }
      assertEquals(eof, Os.lseek(original.fileDescriptor, 0, OsConstants.SEEK_CUR))
    }
  }

  @Test fun logicalPositionAlsoIgnoresLaterSeeksOnTheProviderDuplicate() {
    val bytes = "independent-logical-position".toByteArray()
    regular(bytes) { original ->
      themeProviderInputStream(asset(original)).use { input ->
        assertEquals(bytes[0].toInt() and 0xff, input.read())
        Os.lseek(original.fileDescriptor, 4, OsConstants.SEEK_SET)
        assertArrayEquals(bytes.drop(1).toByteArray(), input.readBytes())
        assertEquals(4L, Os.lseek(original.fileDescriptor, 0, OsConstants.SEEK_CUR))
      }
    }
  }

  @Test fun pipeUnknownLengthRemainsReadableWithoutSeeking() {
    val pipe = ParcelFileDescriptor.createPipe()
    val bytes = "sequential-pipe-payload".toByteArray()
    pipe[0].use { original ->
      ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
      val failure = assertThrows(ErrnoException::class.java) {
        Os.lseek(original.fileDescriptor, 0, OsConstants.SEEK_CUR)
      }
      assertEquals(OsConstants.ESPIPE, failure.errno)
      val descriptor = asset(original)
      val ownedFd = descriptor.fileDescriptor
      themeProviderInputStream(descriptor).use { assertArrayEquals(bytes, it.readBytes()) }
      assertFalse(ownedFd.valid())
      assertTrue(original.fileDescriptor.valid())
    }
  }

  @Test fun finitePipeDoesNotConsumePaddingBeyondTheDeclaredWindow() {
    val pipe = ParcelFileDescriptor.createPipe()
    val payload = "window".toByteArray()
    val padding = ":untouched-padding".toByteArray()
    pipe[0].use { original ->
      ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(payload + padding) }
      themeProviderInputStream(asset(original, length = payload.size.toLong())).use {
        assertArrayEquals(payload, it.readBytes())
        assertEquals(-1, it.read())
      }
      val remainder = ByteArray(padding.size)
      assertEquals(padding.size, Os.read(original.fileDescriptor, remainder, 0, remainder.size))
      assertArrayEquals(padding, remainder)
    }
  }

  @Test fun closeIsIdempotentClosesOwnedDescriptorAndRejectsFurtherReads() {
    regular("close-test".toByteArray()) { original ->
      val descriptor = asset(original)
      val ownedFd = descriptor.fileDescriptor
      val input = themeProviderInputStream(descriptor)
      input.close()
      input.close()
      assertFalse(ownedFd.valid())
      assertTrue(original.fileDescriptor.valid())
      assertThrows(IOException::class.java) { input.read() }
      assertThrows(IOException::class.java) { input.read(ByteArray(4)) }
    }
  }

  @Test fun overflowingWindowFailsClosedAndReleasesItsOwnedDescriptor() {
    regular("asset".toByteArray()) { original ->
      val descriptor = asset(original, Long.MAX_VALUE - 1, 4)
      val ownedFd = descriptor.fileDescriptor
      assertThrows(IllegalArgumentException::class.java) { themeProviderInputStream(descriptor) }
      assertFalse(ownedFd.valid())
      assertTrue(original.fileDescriptor.valid())
    }
  }
}
