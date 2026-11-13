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
      if (background) {
        val plate = PerfectNightPixels.backgroundPlate(pixels, width, height, check) ?: return null
        PerfectNightPixels.recolorPlate(pixels, width, height, plate, check)
      } else {
        val plate = PerfectNightPixels.bakedForegroundPlate(pixels, width, height, check)
        if (plate != null) PerfectNightPixels.recolorPlate(pixels, width, height, plate, check)
        else PerfectNightPixels.liftForeground(pixels, width, height, check)
      }
      for (y in 0 until height) {
        check()
        for (x in 0 until width) {
          val offset = y * width + x
          pixels[offset] = PerfectNightPixels.dim(pixels[offset])
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
