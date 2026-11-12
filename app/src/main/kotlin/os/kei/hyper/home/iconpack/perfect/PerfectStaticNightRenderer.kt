package os.kei.hyper.home.iconpack.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask

internal data class PerfectStaticPair(val day: ByteArray, val night: ByteArray)

/** Optional static conversion only; callers own caching and decide when to use this fallback. */
internal object PerfectStaticNightRenderer {
  const val REVISION = "perfect-static-night-3"
  private const val SIZE = 512
  private const val MAX_BYTES = 8 * 1024 * 1024
  private const val MAX_DIMENSION = 8192
  private const val MAX_DECODE = 1024

  /**
   * [maskPath] is native SVG path data in the existing 100x100 viewport, not a file path.
   * Cancellation is cooperative between rows and around bounded native decode/encode calls.
   */
  fun render(
    layers: List<ByteArray>,
    maskPath: String?,
    check: () -> Unit = {},
  ): PerfectStaticPair {
    check()
    require(layers.size in 1..2) { "Expected one flat image or background + foreground" }
    require(layers.all { it.size in 1..MAX_BYTES }) { "Static layer exceeds byte bounds" }
    val bounds = Rect(0, 0, SIZE, SIZE)
    val mask = maskPath?.let {
      NativeAdaptiveIconMask.fromPathData("perfect-static-night", it).pathForBounds(bounds)
    }
    check()
    val day = newBitmap()
    try {
      val night = newBitmap()
      try {
        val dayCanvas = Canvas(day)
        val nightCanvas = Canvas(night)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val pixels = IntArray(SIZE * SIZE)
        for ((index, bytes) in layers.withIndex()) {
          check()
          val layer = decode(bytes, check)
          try {
            dayCanvas.drawBitmap(layer, null, bounds, paint)
            if (index == 0 && layers.size == 2) {
              day.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
              for (y in 0 until SIZE) {
                check()
                // Include both gradient endpoints; replace RGB without changing source coverage.
                val gray = 56 - (35 * y + (SIZE - 1) / 2) / (SIZE - 1)
                val rgb = (gray shl 16) or (gray shl 8) or gray
                for (x in 0 until SIZE) {
                  val offset = y * SIZE + x
                  pixels[offset] = (pixels[offset] and -0x1000000) or rgb
                }
              }
              night.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            } else {
              nightCanvas.drawBitmap(layer, null, bounds, paint)
            }
          } finally {
            layer.recycle()
          }
        }
        check()
        night.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        for (y in 0 until SIZE) {
          check()
          for (x in 0 until SIZE) {
            val offset = y * SIZE + x
            val color = pixels[offset]
            fun dim(shift: Int) = (((color ushr shift) and 255) * 214 + 127) / 255
            pixels[offset] = (color and -0x1000000) or
              (dim(16) shl 16) or (dim(8) shl 8) or dim(0)
          }
        }
        night.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (mask != null) {
          check()
          val coverage = newBitmap()
          try {
            Canvas(coverage).drawPath(mask, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1 })
            val maskPaint = Paint().apply {
              xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            check()
            dayCanvas.drawBitmap(coverage, 0f, 0f, maskPaint)
            check()
            nightCanvas.drawBitmap(coverage, 0f, 0f, maskPaint)
          } finally {
            coverage.recycle()
          }
        }
        val dayPng = encode(day, check)
        val nightPng = encode(night, check)
        check()
        return PerfectStaticPair(dayPng, nightPng)
      } finally {
        night.recycle()
      }
    } finally {
      day.recycle()
    }
  }

  private fun newBitmap(): Bitmap = Bitmap.createBitmap(
    SIZE, SIZE, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.SRGB),
  )

  private fun decode(bytes: ByteArray, check: () -> Unit): Bitmap {
    check()
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    check()
    require(options.outWidth in 1..MAX_DIMENSION && options.outHeight in 1..MAX_DIMENSION) {
      "Invalid static layer raster bounds"
    }
    val longest = maxOf(options.outWidth, options.outHeight)
    var sample = 1
    while ((longest + sample - 1) / sample > MAX_DECODE) sample *= 2
    options.apply {
      inJustDecodeBounds = false
      inSampleSize = sample
      inScaled = false
      inDensity = 0
      inTargetDensity = 0
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
      inPremultiplied = true
    }
    check()
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) {
      "Cannot decode static layer"
    }
    try {
      check()
      require(bitmap.width in 1..MAX_DECODE && bitmap.height in 1..MAX_DECODE) {
        "Static layer decoder exceeded sampled bounds"
      }
      return bitmap
    } catch (failure: Throwable) {
      bitmap.recycle()
      throw failure
    }
  }

  private fun encode(bitmap: Bitmap, check: () -> Unit): ByteArray {
    check()
    val output = ByteArrayOutputStream()
    require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Cannot encode static layer" }
    check()
    return output.toByteArray()
  }
}
