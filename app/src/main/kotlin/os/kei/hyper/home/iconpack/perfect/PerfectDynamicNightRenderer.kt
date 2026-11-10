package os.kei.hyper.home.iconpack.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import java.io.ByteArrayOutputStream

/** Offline texture conversion. Native coordinates, alpha and animation programs stay unchanged. */
internal object PerfectDynamicNightRenderer {
  private const val MAX_PIXELS = 4 * 1024 * 1024

  fun render(bytes: ByteArray, background: Boolean, check: () -> Unit): ByteArray? {
    check()
    require(bytes.size in 1..PerfectIconFormat.MAX_BLOB)
    if (animatedRaster(bytes, check)) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    require(options.outWidth in 1..8192 && options.outHeight in 1..8192 &&
      options.outWidth.toLong() * options.outHeight <= MAX_PIXELS) { "Dynamic texture exceeds bounds" }
    options.apply {
      inJustDecodeBounds = false
      inScaled = false
      inDensity = 0
      inTargetDensity = 0
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
    val source = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
    try {
      val width = source.width
      val height = source.height
      val pixels = IntArray(width * height)
      source.getPixels(pixels, 0, width, 0, 0, width, height)
      if (!background) {
        var visible = 0
        var dark = 0
        for (y in 0 until height) {
          check()
          for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            if (pixel ushr 24 < 32) continue
            visible++
            if ((0..2).all { ((pixel ushr (it * 8)) and 255) <= 32 }) dark++
          }
        }
        // Multiplication cannot make a black clock hand or text glyph legible on the new plate.
        if (visible > 0 && dark.toLong() * 10 >= visible.toLong() * 9) return null
      }
      for (y in 0 until height) {
        check()
        if (background) {
          // A plain vertical plate may be recolored; baked marks and maps must survive untouched.
          val mins = intArrayOf(255, 255, 255)
          val maxs = intArrayOf(0, 0, 0)
          for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            if (pixel ushr 24 < 128) continue
            for (channel in 0..2) {
              val value = (pixel ushr (channel * 8)) and 255
              mins[channel] = minOf(mins[channel], value)
              maxs[channel] = maxOf(maxs[channel], value)
            }
          }
          if ((0..2).any { maxs[it] - mins[it] > 12 }) return null
        }
      }
      for (y in 0 until height) {
        check()
        val gray = 56 - (35 * y + maxOf(1, height - 1) / 2) / maxOf(1, height - 1)
        for (x in 0 until width) {
          val offset = y * width + x
          val original = pixels[offset]
          fun dim(shift: Int) = (((original ushr shift) and 255) * 214 + 127) / 255
          val value = (gray * 214 + 127) / 255
          val rgb = if (background) (value shl 16) or (value shl 8) or value
            else (dim(16) shl 16) or (dim(8) shl 8) or dim(0)
          pixels[offset] = (original and -0x1000000) or rgb
        }
      }
      val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      try {
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        check()
        return ByteArrayOutputStream().also {
          require(output.compress(Bitmap.CompressFormat.PNG, 100, it))
          check()
        }.toByteArray()
      } finally { output.recycle() }
    } finally { source.recycle() }
  }

  private fun animatedRaster(bytes: ByteArray, check: () -> Unit): Boolean {
    if (bytes.size >= 21 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
      bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) &&
      bytes.copyOfRange(12, 16).contentEquals("VP8X".toByteArray()) &&
      bytes[20].toInt() and 2 != 0) return true
    if (bytes.size < 8 || bytes[0] != 0x89.toByte() || bytes[1] != 'P'.code.toByte()) return false
    var offset = 8
    var chunks = 0
    while (offset.toLong() + 12 <= bytes.size) {
      if (++chunks > 8192) return true
      if (chunks % 32 == 0) check()
      val length = (0..3).fold(0L) { value, index ->
        (value shl 8) or (bytes[offset + index].toLong() and 255)
      }
      val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
      if (type == "acTL" || type == "fcTL" || type == "fdAT") return true
      val next = offset.toLong() + 12 + length
      if (next > bytes.size) break
      offset = next.toInt()
    }
    return false
  }
}
