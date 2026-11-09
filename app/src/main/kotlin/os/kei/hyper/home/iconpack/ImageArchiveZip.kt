package os.kei.hyper.home.iconpack

import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Writes encoded payloads unchanged; XML and other text retain ZIP deflation.
 * Callers own path/size validation, stream lifetime and failed-output cleanup. */
internal object ImageArchiveZip {
  private const val CHUNK_SIZE = 32 * 1024
  private val compressedExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "avif", "heic", "heif", "zip", "mtz", "apk")

  fun writeBytes(
    output: ZipOutputStream, name: String, bytes: ByteArray, time: Long = 0, check: () -> Unit = {},
  ) {
    check()
    val stored = alreadyCompressed(name)
    val crc = if (stored) CRC32().also { checksum ->
      chunks(bytes, check) { offset, length -> checksum.update(bytes, offset, length) }
    }.value else 0L
    output.putNextEntry(entry(name, time, stored, bytes.size.toLong(), crc))
    chunks(bytes, check) { offset, length -> output.write(bytes, offset, length) }
    check()
    output.closeEntry()
  }

  /** Known ZIP size/CRC avoid a preliminary read. ZipOutputStream checks both as it writes. */
  fun copyEntry(
    output: ZipOutputStream, source: ZipFile, original: ZipEntry,
    name: String = original.name, time: Long = original.time,
    preserveMethod: Boolean = false, check: () -> Unit = {},
  ) {
    check()
    val stored = !original.isDirectory && if (preserveMethod) original.method == ZipEntry.STORED else alreadyCompressed(name)
    val metadata = if (stored && (original.size < 0 || original.crc < 0))
      source.getInputStream(original).use { measure(it, check) }
    else original.size to original.crc
    output.putNextEntry(entry(name, time, stored, metadata.first, metadata.second))
    if (!original.isDirectory) source.getInputStream(original).use { transfer(it, output, check) }
    check()
    output.closeEntry()
  }

  /** A completed nested ZIP (including MTZ's extensionless `icons`) needs a streaming CRC pass. */
  fun writeFile(
    output: ZipOutputStream, name: String, file: File, time: Long = 0, check: () -> Unit = {},
  ) {
    check()
    val stored = alreadyCompressed(name)
    val metadata = if (stored) file.inputStream().use { measure(it, check) } else 0L to 0L
    output.putNextEntry(entry(name, time, stored, metadata.first, metadata.second))
    file.inputStream().use { transfer(it, output, check) }
    check()
    output.closeEntry()
  }

  private fun alreadyCompressed(name: String): Boolean =
    name == "icons" || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in compressedExtensions

  private fun entry(name: String, time: Long, stored: Boolean, size: Long, crc: Long) =
    ZipEntry(name).apply {
      this.time = time
      method = if (stored) ZipEntry.STORED else ZipEntry.DEFLATED
      if (stored) {
        this.size = size
        compressedSize = size
        this.crc = crc
      }
    }

  private inline fun chunks(bytes: ByteArray, check: () -> Unit, block: (Int, Int) -> Unit) {
    var offset = 0
    while (offset < bytes.size) {
      check()
      val length = minOf(CHUNK_SIZE, bytes.size - offset)
      block(offset, length)
      offset += length
    }
  }

  private fun measure(input: InputStream, check: () -> Unit): Pair<Long, Long> {
    val crc = CRC32()
    var size = 0L
    readChunks(input, check) { buffer, count -> crc.update(buffer, 0, count); size += count }
    return size to crc.value
  }

  private fun transfer(input: InputStream, output: ZipOutputStream, check: () -> Unit) =
    readChunks(input, check) { buffer, count -> output.write(buffer, 0, count) }

  private inline fun readChunks(input: InputStream, check: () -> Unit, block: (ByteArray, Int) -> Unit) {
    val buffer = ByteArray(CHUNK_SIZE)
    while (true) {
      check()
      val count = input.read(buffer)
      if (count < 0) break
      if (count > 0) block(buffer, count)
    }
  }
}
