package os.kei.hyper.home.iconpack.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import java.io.ByteArrayOutputStream
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask

internal data class PerfectStaticPair(val day: ByteArray, val night: ByteArray)

/** Optional static conversion only; callers own caching and decide when to use this fallback. */
internal object PerfectStaticNightRenderer {
  const val REVISION = "perfect-static-night-11"
  const val EQUIVALENT_LAYER_POLICY = "centered-native-pixel-proof-4"
  private const val SIZE = 512
  private const val MAX_BYTES = 8 * 1024 * 1024

  fun render(
    layers: List<ByteArray>, maskPath: String?, check: () -> Unit,
  ): PerfectStaticPair = render(layers, maskPath, equivalentLayers = null, check = check)
  private const val MAX_DIMENSION = 8192
  private const val MAX_DECODE = 1024

  /**
   * [maskPath] is native SVG path data in the existing 100x100 viewport, not a file path.
   * Cancellation is cooperative between rows and around bounded native decode/encode calls.
   */
  fun render(
    layers: List<ByteArray>,
    maskPath: String?,
    equivalentLayers: List<ByteArray>? = null,
    nativeViewport: Boolean = false,
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
              PerfectNightPixels.backgroundPlate(pixels, SIZE, SIZE, check)?.let {
                PerfectNightPixels.recolorPlate(pixels, SIZE, SIZE, it, check)
              }
              night.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            } else if (index == 1) {
              val foreground = newBitmap()
              try {
                Canvas(foreground).drawBitmap(layer, null, bounds, paint)
                foreground.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
                val underlay = IntArray(SIZE * SIZE)
                night.getPixels(underlay, 0, SIZE, 0, 0, SIZE, SIZE)
                // Native foregrounds may themselves contain an opaque baked plate.
                val plate = PerfectNightPixels.bakedForegroundPlate(pixels, SIZE, SIZE, check)
                if (plate != null) PerfectNightPixels.recolorPlate(pixels, SIZE, SIZE, plate, check)
                else if (!PerfectNightPixels.liftForeground(pixels, underlay, SIZE, SIZE, check) &&
                  !PerfectNightPixels.liftForegroundComponents(pixels, underlay, SIZE, SIZE, check))
                  PerfectWordmarkPixels.lift(pixels, underlay, SIZE, SIZE, check)
                foreground.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
                nightCanvas.drawBitmap(foreground, 0f, 0f, paint)
              } finally {
                foreground.recycle()
              }
            } else {
              nightCanvas.drawBitmap(layer, null, bounds, paint)
            }
          } finally {
            layer.recycle()
          }
        }
        check()
        night.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        var wholeEquivalent = false
        if (layers.size == 1) {
          val proved = equivalentLayers?.let { equivalentNight(layers.single(), it, check) }
          if (proved != null) {
            wholeEquivalent = true
            // Day follows the original flat path, including its exact interpolated coverage.
            day.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            for (y in 0 until SIZE) {
              check()
              for (x in 0 until SIZE) {
                val i = y * SIZE + x
                pixels[i] = (pixels[i] and -0x1000000) or (proved[i] and 0xffffff)
              }
            }
          } else {
            PerfectNightPixels.plate(pixels, SIZE, SIZE, check)?.let {
              PerfectNightPixels.recolorPlate(pixels, SIZE, SIZE, it, check)
            }
          }
        }
        for (y in 0 until SIZE) {
          check()
          for (x in 0 until SIZE) {
            val offset = y * SIZE + x
            pixels[offset] = PerfectNightPixels.dim(pixels[offset])
          }
        }
        if (layers.size == 1 && !wholeEquivalent && equivalentLayers?.size == 2) {
          PerfectCompositeForegroundProof.repair(
            layers.single(), equivalentLayers[0], equivalentLayers[1], pixels, check)
        }
        night.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (nativeViewport) {
          check()
          applyNativeViewport(day)
          check()
          applyNativeViewport(night)
        }
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

  /**
   * Same-theme inputs are only hints. Prove every native flat pixel against a centered
   * source-over crop before using the separated alpha for night. Never prove sampled images.
   */
  private fun equivalentNight(
    flat: ByteArray, layers: List<ByteArray>, check: () -> Unit,
  ): IntArray? {
    if (layers.size != 2 || (listOf(flat) + layers).any { it.size !in 1..MAX_BYTES }) return null
    val decoded = ArrayList<Bitmap>(3)
    try {
      for (bytes in listOf(flat) + layers) {
        check()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_DECODE || bounds.outHeight !in 1..MAX_DECODE) return null
        decoded += decode(bytes, check)
      }
      val original = decoded[0]
      val background = decoded[1]
      val foreground = decoded[2]
      if (background.width != foreground.width || background.height != foreground.height ||
        original.width > background.width || original.height > background.height) return null
      val dx = background.width - original.width
      val dy = background.height - original.height
      if (dx % 2 != 0 || dy % 2 != 0) return null
      val crop = Rect(dx / 2, dy / 2, dx / 2 + original.width, dy / 2 + original.height)
      val destination = Rect(0, 0, original.width, original.height)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      val proof = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888,
        true, ColorSpace.get(ColorSpace.Named.SRGB))
      try {
        val canvas = Canvas(proof)
        canvas.drawBitmap(background, crop, destination, paint)
        canvas.drawBitmap(foreground, crop, destination, paint)
        val expected = IntArray(original.width)
        val actual = IntArray(original.width)
        for (y in 0 until original.height) {
          check()
          original.getPixels(expected, 0, original.width, 0, y, original.width, 1)
          proof.getPixels(actual, 0, original.width, 0, y, original.width, 1)
          for (x in expected.indices) {
            // PNG premultiplication and Skia src-over can differ by one alpha code.
            // Output coverage still comes from the authoritative flat image.
            if (kotlin.math.abs((expected[x] ushr 24) - (actual[x] ushr 24)) > 1) return null
            for (shift in 0..2) if (kotlin.math.abs(
                (expected[x] ushr (shift * 8) and 255) -
                  (actual[x] ushr (shift * 8) and 255)) > 2) return null
          }
        }
      } finally { proof.recycle() }

      val night = newBitmap()
      try {
        val canvas = Canvas(night)
        val target = Rect(0, 0, SIZE, SIZE)
        canvas.drawBitmap(background, crop, target, paint)
        val pixels = IntArray(SIZE * SIZE)
        night.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val plate = PerfectNightPixels.backgroundPlate(pixels, SIZE, SIZE, check) ?: return null
        // An exact artwork match alone does not authorize partial recoloring or photo conversion.
        for (y in 0 until SIZE) {
          check()
          for (x in 0 until SIZE) {
            val i = y * SIZE + x
            if (pixels[i] ushr 24 >= 32 && !plate[i]) return null
          }
        }
        PerfectNightPixels.recolorPlate(pixels, SIZE, SIZE, plate, check)
        night.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        // Keep the original alpha/art; only isolated neutral stencil components may brighten.
        val overlay = newBitmap()
        try {
          Canvas(overlay).drawBitmap(foreground, crop, target, paint)
          val foregroundPixels = IntArray(SIZE * SIZE)
          overlay.getPixels(foregroundPixels, 0, SIZE, 0, 0, SIZE, SIZE)
          PerfectNightPixels.liftForegroundComponents(foregroundPixels, pixels, SIZE, SIZE, check)
          overlay.setPixels(foregroundPixels, 0, SIZE, 0, 0, SIZE, SIZE)
          canvas.drawBitmap(overlay, 0f, 0f, paint)
        } finally { overlay.recycle() }
        night.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        check()
        return pixels
      } finally { night.recycle() }
    } catch (_: IllegalArgumentException) {
      return null
    } finally {
      decoded.forEach { it.recycle() }
    }
  }

  private fun applyNativeViewport(bitmap: Bitmap) {
    val viewport = newBitmap()
    try {
      val child = BitmapDrawable(null, bitmap).apply { isFilterBitmap = true }
      val adaptive = AdaptiveIconDrawable(child, null)
      adaptive.setBounds(0, 0, SIZE, SIZE)
      // Native scenes include adaptive overscan. Use the framework's child
      // bounds; the final compositor still owns the icon mask.
      child.draw(Canvas(viewport))
      bitmap.eraseColor(0)
      Canvas(bitmap).drawBitmap(viewport, 0f, 0f, null)
    } finally {
      viewport.recycle()
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
