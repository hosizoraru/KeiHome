package os.kei.hyper.home.xposed

import android.content.res.AssetFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.io.InputStream

/**
 * Takes ownership of [asset], including on construction failure. Regular files
 * use positional reads so a provider's cached/duplicated FD cursor is irrelevant.
 * Non-regular descriptors retain createInputStream's sequential semantics.
 * No path lookup or alternate-file fallback is performed.
 */
internal fun themeProviderInputStream(asset: AssetFileDescriptor): InputStream {
  try {
    val start = asset.startOffset
    val length = asset.declaredLength
    require(start >= 0 && (length >= 0 || length == AssetFileDescriptor.UNKNOWN_LENGTH)) {
      "Invalid provider asset window"
    }
    require(length < 0 || start <= Long.MAX_VALUE - length) {
      "Provider asset window overflows"
    }
    val regular = try {
      OsConstants.S_ISREG(Os.fstat(asset.fileDescriptor).st_mode)
    } catch (failure: ErrnoException) {
      throw IOException("Cannot inspect provider descriptor", failure)
    }
    val sequential = if (regular) null else asset.createInputStream()
    return ThemeProviderWindowInputStream(asset, start, length, sequential)
  } catch (failure: Throwable) {
    try {
      asset.close()
    } catch (closeFailure: Throwable) {
      failure.addSuppressed(closeFailure)
    }
    throw failure
  }
}

private class ThemeProviderWindowInputStream(
  private val asset: AssetFileDescriptor,
  private val start: Long,
  length: Long,
  private val sequential: InputStream?,
) : InputStream() {
  private val limit = if (length == AssetFileDescriptor.UNKNOWN_LENGTH) Long.MAX_VALUE - start else length
  private var position = 0L
  private var closed = false
  private val single = ByteArray(1)

  override fun read(): Int =
    if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xff

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (closed) throw IOException("Provider stream is closed")
    if (offset < 0 || length < 0 || offset > buffer.size - length) {
      throw IndexOutOfBoundsException()
    }
    if (length == 0) return 0
    val remaining = limit - position
    if (remaining == 0L) return -1
    val count = minOf(length.toLong(), remaining).toInt()
    if (sequential != null) {
      val read = sequential.read(buffer, offset, count)
      if (read > 0) position += read
      return read
    }
    while (true) {
      try {
        val result = Os.pread(asset.fileDescriptor, buffer, offset, count, start + position)
        if (result > 0) position += result
        return if (result == 0) -1 else result
      } catch (failure: ErrnoException) {
        if (failure.errno != OsConstants.EINTR) {
          throw IOException("Cannot read provider asset window", failure)
        }
      }
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    asset.use { sequential?.close() }
  }
}
