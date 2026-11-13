package os.kei.hyper.home.iconpack

import java.io.InputStream
import java.io.PushbackInputStream

/** Content-based native artwork admission, without bitmap decoding or rewriting its payload. */
internal object IconRasterHeader {
  internal data class Size(val width: Int, val height: Int)

  private const val MAX_JPEG_HEADER = 1024L * 1024
  private const val MAX_WEBP_CHUNKS = 4096
  private val pngSignature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

  fun read(input: InputStream, size: Long, check: () -> Unit = {}): Size {
    val stream = PushbackInputStream(input, 12)
    val peek = Reader(stream, size, check).bytes(12)
    stream.unread(peek)
    val reader = Reader(stream, size, check)
    return when {
      peek.copyOfRange(0, 8).contentEquals(pngSignature) -> {
        // Keep the existing 24-byte PNG dimension fast path.
        val header = reader.bytes(24)
        valid(be32(header, 8) == 13 && ascii(header, 12) == "IHDR", "Invalid PNG header")
        dimensions(be32(header, 16), be32(header, 20))
      }
      u8(peek[0]) == 255 && u8(peek[1]) == 216 -> jpeg(reader)
      ascii(peek, 0) == "RIFF" && ascii(peek, 8) == "WEBP" -> webp(reader)
      else -> throw IconPackException("Unsupported native raster header")
    }
  }

  private fun jpeg(reader: Reader): Size {
    reader.limit = minOf(reader.size, MAX_JPEG_HEADER)
    valid(reader.byte() == 255 && reader.byte() == 216, "Invalid JPEG signature")
    var frame: Size? = null
    var components = emptySet<Int>()
    while (true) {
      valid(reader.byte() == 255, "Invalid JPEG marker")
      var marker = reader.byte()
      while (marker == 255) marker = reader.byte()
      valid(marker == 1 || (marker in 0xc0..0xfe && marker !in 0xd0..0xd9), "Unexpected JPEG marker")
      if (marker == 1) continue // TEM is a standalone marker.
      val length = reader.be16()
      valid(length >= 2, "Invalid JPEG segment length")
      val payload = length - 2
      when {
        marker in 0xc0..0xcf && marker !in setOf(0xc4, 0xc8, 0xcc) -> {
          valid(frame == null && payload >= 6, "Invalid JPEG frame")
          val precision = reader.byte()
          val height = reader.be16()
          val width = reader.be16()
          val count = reader.byte()
          valid(precision in 8..16 && count > 0 && payload == 6 + 3 * count, "Invalid JPEG frame length")
          val ids = mutableSetOf<Int>()
          repeat(count) {
            val id = reader.byte()
            val sampling = reader.byte()
            val table = reader.byte()
            valid(ids.add(id) && sampling shr 4 in 1..4 && sampling and 15 in 1..4 && table <= 3,
              "Invalid JPEG frame component")
          }
          frame = dimensions(width, height)
          components = ids
        }
        marker == 0xda -> {
          val result = frame ?: throw IconPackException("JPEG scan has no frame")
          valid(payload >= 4, "Invalid JPEG scan")
          val count = reader.byte()
          valid(count in 1..components.size && payload == 4 + 2 * count, "Invalid JPEG scan length")
          val ids = mutableSetOf<Int>()
          repeat(count) {
            val id = reader.byte()
            val tables = reader.byte()
            valid(id in components && ids.add(id) && tables shr 4 <= 3 && tables and 15 <= 3,
              "Invalid JPEG scan component")
          }
          reader.skip(3) // Spectral selection and successive approximation; not entropy decoding.
          valid(reader.size - reader.position >= 3, "Incomplete JPEG scan")
          return result
        }
        else -> reader.skip(payload.toLong())
      }
    }
  }

  private fun webp(reader: Reader): Size {
    val riff = reader.bytes(12)
    val end = le(riff, 4, 4) + 8
    valid(end == reader.size && end >= 20, "Invalid WebP container length")
    var canvas: Size? = null
    var image: Size? = null
    var chunks = 0
    while (reader.position < end) {
      valid(++chunks <= MAX_WEBP_CHUNKS && end - reader.position >= 8, "Invalid WebP chunk header")
      val chunk = reader.bytes(8)
      val type = ascii(chunk, 0)
      val length = le(chunk, 4, 4)
      val padded = length + (length and 1)
      valid(padded <= end - reader.position, "Truncated WebP chunk")
      val start = reader.position
      when (type) {
        "VP8X" -> {
          valid(chunks == 1 && canvas == null && length == 10L, "Invalid WebP extended header")
          val header = reader.bytes(10)
          valid(u8(header[0]) and 0xc1 == 0 && header.sliceArray(1..3).all { it == 0.toByte() },
            "Invalid WebP reserved bits")
          canvas = dimensions((le(header, 4, 3) + 1).toInt(), (le(header, 7, 3) + 1).toInt())
        }
        "VP8 " -> {
          valid(image == null && length >= 10, "Invalid WebP VP8 header")
          val header = reader.bytes(10)
          valid(u8(header[0]) and 1 == 0 && (u8(header[0]) shr 1) and 7 <= 3 &&
            u8(header[3]) == 0x9d && u8(header[4]) == 1 && u8(header[5]) == 0x2a,
            "Invalid WebP VP8 signature")
          image = dimensions(le(header, 6, 2).toInt() and 0x3fff, le(header, 8, 2).toInt() and 0x3fff)
        }
        "VP8L" -> {
          valid(image == null && length >= 5, "Invalid WebP lossless header")
          val header = reader.bytes(5)
          val packed = le(header, 1, 4)
          valid(u8(header[0]) == 0x2f && packed shr 29 == 0L, "Invalid WebP lossless signature")
          image = dimensions(((packed and 0x3fff) + 1).toInt(), (((packed shr 14) and 0x3fff) + 1).toInt())
        }
      }
      reader.skip(length - (reader.position - start))
      if (length and 1 != 0L) valid(reader.byte() == 0, "Invalid WebP padding")
    }
    val result = image ?: throw IconPackException("WebP contains no image header")
    valid(canvas == null || canvas == result, "WebP canvas and image dimensions differ")
    return canvas ?: result
  }

  private fun dimensions(width: Int, height: Int): Size {
    valid(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 32L * 1024 * 1024,
      "Native raster dimensions exceed limits")
    return Size(width, height)
  }

  private fun valid(condition: Boolean, message: String) {
    if (!condition) throw IconPackException(message)
  }

  private fun u8(value: Byte) = value.toInt() and 255
  private fun le(bytes: ByteArray, offset: Int, count: Int): Long =
    (0 until count).fold(0L) { value, index -> value or (u8(bytes[offset + index]).toLong() shl (8 * index)) }
  private fun be32(bytes: ByteArray, offset: Int): Int =
    (0 until 4).fold(0) { value, index -> (value shl 8) or u8(bytes[offset + index]) }
  private fun ascii(bytes: ByteArray, offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)

  private class Reader(val input: InputStream, val size: Long, val check: () -> Unit) {
    var position = 0L
      private set
    var limit = size

    private fun available(count: Long) {
      check()
      valid(count >= 0 && position <= limit && count <= limit - position, "Truncated or oversized raster header")
    }

    fun byte(): Int {
      available(1)
      val value = input.read()
      valid(value >= 0, "Truncated raster header")
      position++
      return value
    }

    fun be16() = (byte() shl 8) or byte()

    fun bytes(count: Int): ByteArray {
      available(count.toLong())
      val result = ByteArray(count)
      var offset = 0
      while (offset < count) {
        check()
        val read = input.read(result, offset, count - offset)
        if (read <= 0) {
          result[offset++] = byte().toByte()
        } else {
          offset += read
          position += read
        }
      }
      return result
    }

    fun skip(count: Long) {
      available(count)
      var remaining = count
      while (remaining > 0) {
        check()
        val skipped = input.skip(minOf(remaining, 32 * 1024L))
        if (skipped <= 0) {
          byte()
          remaining--
        } else {
          position += skipped
          remaining -= skipped
        }
      }
    }
  }
}
