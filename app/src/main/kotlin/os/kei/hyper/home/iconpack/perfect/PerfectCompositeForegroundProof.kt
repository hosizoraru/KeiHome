package os.kei.hyper.home.iconpack.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Proves a separated foreground without claiming that it explains the entire flat snapshot. */
internal object PerfectCompositeForegroundProof {
  private const val SIZE = 512
  private const val MAX_DECODE = 1024
  private const val MAX_BYTES = 8 * 1024 * 1024
  private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

  /**
   * Restores only source-proven foreground RGB, preserving caller alpha and all other pixels.
   * Replacement RGB is from the normal pair renderer's night output, including its final dim.
   * Rejection or cancellation before the final copy leaves [existingNightPixels512] untouched.
   * A successful call neither proves nor replaces artwork outside the foreground's coverage.
   */
  fun repair(
    flatBytes: ByteArray,
    bgBytes: ByteArray,
    fgBytes: ByteArray,
    existingNightPixels512: IntArray,
    check: () -> Unit,
  ): Boolean {
    check()
    if (existingNightPixels512.size != SIZE * SIZE) return false
    val owned = ArrayList<Bitmap>(8)
    try {
      val flat = decode(flatBytes, owned, check) ?: return false
      val background = decode(bgBytes, owned, check) ?: return false
      val foreground = decode(fgBytes, owned, check) ?: return false
      val size = flat.width
      if (flat.height != size || background.width != background.height ||
        foreground.width != background.width || foreground.height != background.height ||
        size > background.width) return false
      val difference = background.width - size
      if (difference % 2 != 0) return false
      // Match the whole-image proof's integer centered crop; never register or rescale to fit.
      val inset = difference / 2
      val crop = Rect(inset, inset, inset + size, inset + size)

      val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      val proof = bitmap(size, owned)
      val nativeBounds = Rect(0, 0, size, size)
      check()
      Canvas(proof).apply {
        drawBitmap(background, crop, nativeBounds, paint)
        drawBitmap(foreground, crop, nativeBounds, paint)
      }
      val sourceFlat = IntArray(size)
      val sourcePair = IntArray(size)
      val sourceForeground = IntArray(size)
      var hasForeground = false
      for (y in 0 until size) {
        check()
        flat.getPixels(sourceFlat, 0, size, 0, y, size, 1)
        proof.getPixels(sourcePair, 0, size, 0, y, size, 1)
        foreground.getPixels(sourceForeground, 0, size, inset, y + inset, size, 1)
        for (x in 0 until size) {
          if (sourceForeground[x] ushr 24 == 0) continue
          hasForeground = true
          // Every covered source pixel must agree, including faint antialiased edges.
          if (!matches(sourceFlat[x], sourcePair[x])) return false
        }
      }
      if (!hasForeground) return false
      proof.recycle()

      check()
      val pairLayers = if (inset == 0) listOf(bgBytes, fgBytes) else listOf(
        croppedPng(background, crop, owned, check) ?: return false,
        croppedPng(foreground, crop, owned, check) ?: return false,
      )
      val pair = PerfectStaticNightRenderer.render(
        layers = pairLayers, maskPath = null, equivalentLayers = null,
        check = check, nativeViewport = false,
      )
      val pairDay = decode(pair.day, owned, check) ?: return false
      val pairNight = decode(pair.night, owned, check) ?: return false
      if (pairDay.width != SIZE || pairDay.height != SIZE ||
        pairNight.width != SIZE || pairNight.height != SIZE) return false
      val flat512 = bitmap(SIZE, owned)
      val foreground512 = bitmap(SIZE, owned)
      val bounds = Rect(0, 0, SIZE, SIZE)
      check()
      Canvas(flat512).drawBitmap(flat, null, bounds, paint)
      check()
      Canvas(foreground512).drawBitmap(foreground, crop, bounds, paint)

      val replacement = existingNightPixels512.clone()
      val flatRow = IntArray(SIZE)
      val pairDayRow = IntArray(SIZE)
      val pairNightRow = IntArray(SIZE)
      val foregroundRow = IntArray(SIZE)
      var selected = false
      for (y in 0 until SIZE) {
        check()
        flat512.getPixels(flatRow, 0, SIZE, 0, y, SIZE, 1)
        pairDay.getPixels(pairDayRow, 0, SIZE, 0, y, SIZE, 1)
        pairNight.getPixels(pairNightRow, 0, SIZE, 0, y, SIZE, 1)
        foreground512.getPixels(foregroundRow, 0, SIZE, 0, y, SIZE, 1)
        for (x in 0 until SIZE) {
          // Filtering may bring unrelated flat artwork into an edge's interpolation footprint.
          if (foregroundRow[x] ushr 24 == 0 || !matches(flatRow[x], pairDayRow[x])) continue
          val i = y * SIZE + x
          replacement[i] = (replacement[i] and -0x1000000) or (pairNightRow[x] and 0xffffff)
          selected = true
        }
      }
      if (!selected) return false
      check()
      // No callbacks after mutation starts: a cancellation cannot publish a partial repair.
      replacement.copyInto(existingNightPixels512)
      return true
    } finally {
      owned.forEach { if (!it.isRecycled) it.recycle() }
    }
  }

  private fun croppedPng(
    source: Bitmap, crop: Rect, owned: MutableList<Bitmap>, check: () -> Unit,
  ): ByteArray? {
    check()
    val cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
    owned += cropped
    check()
    val bytes = ByteArrayOutputStream().use { output ->
      if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
      output.toByteArray()
    }
    check()
    return bytes.takeIf { it.size in 1..MAX_BYTES }
  }

  private fun matches(a: Int, b: Int): Boolean {
    for (shift in 0..3) {
      if (abs((a ushr (shift * 8) and 255) - (b ushr (shift * 8) and 255)) > 2) return false
    }
    return true
  }

  private fun bitmap(size: Int, owned: MutableList<Bitmap>): Bitmap =
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888, true,
      ColorSpace.get(ColorSpace.Named.SRGB)).also { owned += it }

  private fun decode(bytes: ByteArray, owned: MutableList<Bitmap>, check: () -> Unit): Bitmap? {
    check()
    if (bytes.size !in PNG_SIGNATURE.size..MAX_BYTES ||
      PNG_SIGNATURE.indices.any { bytes[it] != PNG_SIGNATURE[it] }) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    check()
    if (options.outWidth !in 1..MAX_DECODE || options.outHeight !in 1..MAX_DECODE) return null
    options.apply {
      inJustDecodeBounds = false
      inSampleSize = 1
      inScaled = false
      inDensity = 0
      inTargetDensity = 0
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
      inPremultiplied = true
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    owned += decoded
    check()
    if (decoded.width != options.outWidth || decoded.height != options.outHeight ||
      decoded.width !in 1..MAX_DECODE || decoded.height !in 1..MAX_DECODE) return null
    return decoded
  }
}
