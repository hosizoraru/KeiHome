package os.kei.hyper.home.iconpack

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.abs

/** Bounded PNG structure checks shared with converted-archive admission. */
internal object GeneratedIconPng {
  private const val SIGNATURE = -8552249625308161526L
  private val allowed = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "sRGB", "gAMA", "cHRM", "iCCP", "sBIT", "pHYs")

  fun validate(bytes: ByteArray, maxDimension: Int) {
    inspect(bytes, maxDimension)
  }

  fun squareSize(bytes: ByteArray): Int {
    require(bytes.size in 33..(4 * 1024 * 1024)) { "Invalid rendered PNG size" }
    val png = inspect(bytes, 512)
    require(png.width == png.height) { "Expected a square PNG" }
    return png.width
  }

  fun requireAlphaMask(bytes: ByteArray, size: Int) {
    require(bytes.size in 33..(4 * 1024 * 1024)) { "Invalid alpha mask PNG size" }
    val png = inspect(bytes, 512, capturePixels = true)
    require(png.width == size && png.height == size) { "Alpha mask dimensions must match day/night artwork" }
    require(png.compression == 0 && png.filter == 0 && png.interlace == 0) { "Unsupported alpha mask PNG encoding" }
    val channels = when (png.colorType) {
      0, 3 -> 1
      2 -> 3
      4 -> 2
      6 -> 4
      else -> error("Unsupported alpha mask PNG color type")
    }
    require(if (png.colorType == 3) png.bitDepth in setOf(1, 2, 4, 8) else png.bitDepth == 8) {
      "Alpha masks must use 8-bit samples or an indexed palette"
    }
    val transparency = png.transparency
    val paletteSize = png.palette?.size?.div(3) ?: 0
    when (png.colorType) {
      3 -> require(paletteSize in 1..(1 shl png.bitDepth) &&
        png.palette!!.size % 3 == 0 && transparency != null && transparency.size <= paletteSize)
      0 -> require(transparency?.size == 2)
      2 -> require(transparency?.size == 6)
      else -> require(transparency == null)
    }
    val stride = (size * channels * png.bitDepth + 7) / 8
    val raw = ByteArray((stride + 1) * size)
    val inflater = Inflater()
    try {
      inflater.setInput(png.pixels!!)
      var offset = 0
      while (offset < raw.size) {
        val count = inflater.inflate(raw, offset, raw.size - offset)
        require(count > 0) { "Truncated alpha mask pixel stream" }
        offset += count
      }
      require(inflater.inflate(ByteArray(1)) == 0 && inflater.finished() && inflater.remaining == 0) {
        "Unexpected alpha mask expanded size"
      }
    } catch (failure: DataFormatException) {
      throw IllegalArgumentException("Invalid alpha mask pixel stream", failure)
    } finally {
      inflater.end()
    }
    val bytesPerPixel = maxOf(1, channels * png.bitDepth / 8)
    var previous = ByteArray(stride)
    var transparent = false
    var visible = false
    fun ByteArray.sample(index: Int) = this[index].toInt() and 255
    fun transparentSample(index: Int) = (transparency!!.sample(index) shl 8) or transparency.sample(index + 1)
    repeat(size) { y ->
      val start = y * (stride + 1)
      val filter = raw[start].toInt() and 255
      require(filter in 0..4) { "Invalid alpha mask row filter" }
      val row = raw.copyOfRange(start + 1, start + 1 + stride)
      for (i in row.indices) {
        val left = if (i >= bytesPerPixel) row.sample(i - bytesPerPixel) else 0
        val up = previous.sample(i)
        val upperLeft = if (i >= bytesPerPixel) previous.sample(i - bytesPerPixel) else 0
        val prediction = when (filter) {
          1 -> left
          2 -> up
          3 -> (left + up) / 2
          4 -> {
            val p = left + up - upperLeft
            val a = abs(p - left); val b = abs(p - up); val c = abs(p - upperLeft)
            if (a <= b && a <= c) left else if (b <= c) up else upperLeft
          }
          else -> 0
        }
        row[i] = (row.sample(i) + prediction).toByte()
      }
      repeat(size) { x ->
        val alpha = when (png.colorType) {
          6 -> row.sample(x * 4 + 3)
          4 -> row.sample(x * 2 + 1)
          3 -> {
            val bit = x * png.bitDepth
            val index = (row.sample(bit / 8) shr (8 - png.bitDepth - bit % 8)) and ((1 shl png.bitDepth) - 1)
            require(index < paletteSize) { "Invalid alpha mask palette index" }
            if (index < transparency!!.size) transparency.sample(index) else 255
          }
          0 -> if (row.sample(x) == transparentSample(0)) 0 else 255
          else -> if ((0..2).all { row.sample(x * 3 + it) == transparentSample(it * 2) }) 0 else 255
        }
        transparent = transparent || alpha == 0
        visible = visible || alpha > 0
      }
      previous = row
    }
    require(transparent && visible) { "Expected a nonempty alpha silhouette with transparent pixels" }
  }

  private data class Png(
    val width: Int, val height: Int, val bitDepth: Int, val colorType: Int,
    val compression: Int, val filter: Int, val interlace: Int,
    val palette: ByteArray?, val transparency: ByteArray?, val pixels: ByteArray?,
  )

  private fun inspect(bytes: ByteArray, maxDimension: Int, capturePixels: Boolean = false): Png {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    require(input.readLong() == SIGNATURE) { "Converted artwork is not PNG" }
    var header: IntArray? = null
    var pixels = false
    var palette: ByteArray? = null
    var transparency: ByteArray? = null
    val compressed = if (capturePixels) ByteArrayOutputStream() else null
    while (input.available() > 0) {
      if (Thread.currentThread().isInterrupted) throw InterruptedException("PNG inspection interrupted")
      val length = input.readInt()
      require(length >= 0 && length <= input.available() - 8) { "Invalid PNG chunk size" }
      val type = ByteArray(4).also(input::readFully)
      val name = String(type, Charsets.US_ASCII)
      require(name in allowed && (header != null || name == "IHDR")) { "Unexpected PNG chunk" }
      val data = ByteArray(length).also(input::readFully)
      require(CRC32().apply { update(type); update(data) }.value ==
        (input.readInt().toLong() and 0xffffffffL)) { "PNG CRC mismatch" }
      when (name) {
        "IHDR" -> {
          require(header == null && length == 13)
          val dims = DataInputStream(ByteArrayInputStream(data))
          header = intArrayOf(dims.readInt(), dims.readInt(), dims.readUnsignedByte(),
            dims.readUnsignedByte(), dims.readUnsignedByte(), dims.readUnsignedByte(), dims.readUnsignedByte())
          require(header[0] in 1..maxDimension && header[1] in 1..maxDimension) { "PNG dimension limit" }
        }
        "PLTE" -> {
          if (capturePixels) require(!pixels && palette == null && length in 3..768 && length % 3 == 0)
          palette = data
        }
        "tRNS" -> {
          if (capturePixels) require(!pixels && transparency == null)
          transparency = data
        }
        "IDAT" -> { pixels = true; compressed?.write(data) }
        "IEND" -> {
          require(length == 0 && header != null && pixels && input.available() == 0) { "Invalid PNG end" }
          return Png(header[0], header[1], header[2], header[3], header[4], header[5], header[6],
            palette, transparency, compressed?.toByteArray())
        }
      }
    }
    error("Missing PNG end")
  }
}
