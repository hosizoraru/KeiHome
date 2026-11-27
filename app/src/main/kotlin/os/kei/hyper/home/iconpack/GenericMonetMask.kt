package os.kei.hyper.home.iconpack

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Generation-time contrast extraction. Alpha-only tinting would erase opaque logos. */
internal object GenericMonetMask {
  /** Input is a completed mask. Adaptive child expansion has already happened upstream. */
  fun insetFullBleed(mask: IntArray, size: Int, clipAlpha: IntArray? = null,
    check: () -> Unit = {}): IntArray {
    require(size in 1..512 && mask.size == size * size && (clipAlpha == null || clipAlpha.size == mask.size))
    if (size < 16) return mask
    // Move the visible, completed silhouette inward, including its final outer shape.
    // Minimum coverage keeps already clipped antialiased edges from being multiplied twice.
    fun alpha(index: Int): Int = min(mask[index] ushr 24, clipAlpha?.get(index) ?: 255)
    val band = max(1, size / 10)
    val middle = size / 5 until size - size / 5
    var left = 0; var right = 0; var top = 0; var bottom = 0
    var visible = 0
    for (y in 0 until size) {
      check()
      for (x in 0 until size) {
        if (alpha(y * size + x) < 64) continue
        visible++
        if (y in middle) {
          if (x < band) left++
          if (x >= size - band) right++
        }
        if (x in middle) {
          if (y < band) top++
          if (y >= size - band) bottom++
        }
      }
    }
    val threshold = max(1, band * middle.count() / 6)
    // Opposite occupied edge bands distinguish full artwork from a small protruding detail.
    if (visible < mask.size / 8 ||
      !((left >= threshold && right >= threshold) || (top >= threshold && bottom >= threshold))) return mask
    val inset = (size * .20f).roundToInt()
    val inner = size - 2 * inset
    val result = IntArray(mask.size) { 0x00ffffff }
    for (y in 0 until inner) {
      check()
      val sy = ((y + .5f) * size / inner - .5f).coerceIn(0f, size - 1f)
      val y0 = sy.toInt(); val y1 = (y0 + 1).coerceAtMost(size - 1)
      val fy = sy - y0
      for (x in 0 until inner) {
        val sx = ((x + .5f) * size / inner - .5f).coerceIn(0f, size - 1f)
        val x0 = sx.toInt(); val x1 = (x0 + 1).coerceAtMost(size - 1)
        val fx = sx - x0
        val a = alpha(y0 * size + x0) * (1 - fx) + alpha(y0 * size + x1) * fx
        val b = alpha(y1 * size + x0) * (1 - fx) + alpha(y1 * size + x1) * fx
        result[(y + inset) * size + x + inset] = ((a * (1 - fy) + b * fy).roundToInt() shl 24) or 0x00ffffff
      }
    }
    return result
  }

  fun background(pixels: IntArray, size: Int, check: () -> Unit = {}): IntArray {
    require(size in 1..512 && pixels.size == size * size)
    val radius = size * 0.22f
    return IntArray(pixels.size) { i ->
      if (i % size == 0) check()
      val x = i % size + 0.5f
      val y = i / size + 0.5f
      val dx = max(radius - x, max(0f, x - (size - radius)))
      val dy = max(radius - y, max(0f, y - (size - radius)))
      val coverage = (radius + 0.5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
      (((pixels[i] ushr 24) * coverage).roundToInt() shl 24) or 0x00ffffff
    }
  }

  fun foreground(
    pixels: IntArray, size: Int, check: () -> Unit = {},
  ): IntArray? = foreground(pixels, size, plate = null, check = check)

  fun foreground(
    pixels: IntArray, size: Int, plate: IntArray?, check: () -> Unit = {},
  ): IntArray? {
    require(size in 1..512 && pixels.size == size * size && (plate == null || plate.size == pixels.size))
    FlatIconStencil.prove(pixels, size, check)?.let { stencil ->
      val effectivePlate = plate ?: background(pixels, size, check)
      return IntArray(pixels.size) { i ->
        if (i % size == 0) check()
        val alpha = ((effectivePlate[i] ushr 24) * (stencil.ink[i].toInt() and 255) + 127) / 255
        (alpha shl 24) or 0x00ffffff
      }
    }
    val border = IntArray(4096)
    var borderCount = 0
    fun bucket(c: Int) = ((c ushr 20 and 15) shl 8) or
      ((c ushr 12 and 15) shl 4) or (c ushr 4 and 15)
    var transparent = 0
    for (y in 0 until size) {
      check()
      for (x in 0 until size) {
        val c = pixels[y * size + x]
        if (c ushr 24 < 32) transparent++
        if ((x < size / 8 + 1 || x >= size - size / 8 - 1 ||
            y < size / 8 + 1 || y >= size - size / 8 - 1) && c ushr 24 >= 224) {
          border[bucket(c)]++
          borderCount++
        }
      }
    }
    // A cutout with no opaque plate already supplies its own foreground silhouette.
    val cutout = borderCount == 0 && transparent > pixels.size / 4
    val dominant = border.indices.maxBy { border[it] }
    if (!cutout && borderCount == 0) return null
    val br = (dominant ushr 8 and 15) * 17
    val bg = (dominant ushr 4 and 15) * 17
    val bb = (dominant and 15) * 17
    val contrasts = IntArray(pixels.size)
    val histogram = IntArray(256)
    for (y in 0 until size) {
      check()
      for (x in 0 until size) {
        val i = y * size + x
        val c = pixels[i]
        val contrast = max(abs((c ushr 16 and 255) - br),
          max(abs((c ushr 8 and 255) - bg), abs((c and 255) - bb)))
        contrasts[i] = contrast
        if (c ushr 24 >= 32) histogram[contrast]++
      }
    }
    var remaining = histogram.sum() / 50
    var peak = 255
    while (peak > 0 && remaining >= histogram[peak]) remaining -= histogram[peak--]
    if (!cutout && peak < 24) return null // A uniform plate has no recoverable mark.
    val effectivePlate = plate ?: background(pixels, size, check)
    return IntArray(pixels.size) { i ->
      val alpha = effectivePlate[i] ushr 24
      val mask = if (cutout) alpha else
        (alpha * ((contrasts[i] - 12).toFloat() / (peak - 12)).coerceIn(0f, 1f)).roundToInt()
      (mask shl 24) or 0x00ffffff
    }
  }

  /** Uses an authored color layer only when its alpha separates a mark from the plate. */
  fun layeredForeground(
    foreground: IntArray, plate: IntArray, size: Int, check: () -> Unit,
  ): IntArray? {
    require(size in 1..512 && foreground.size == size * size && plate.size == foreground.size)
    var interior = 0
    var clear = 0
    var ink = 0
    for (i in foreground.indices) {
      if (i % size == 0) check()
      val alpha = foreground[i] ushr 24
      if (plate[i] ushr 24 >= 224) {
        interior++
        if (alpha <= 8) clear++
        if (alpha >= 64) ink++
      }
    }
    if (interior == 0 || clear * 10 < interior || ink * 100 < interior) return null
    return IntArray(foreground.size) { i ->
      if (i % size == 0) check()
      (minOf(foreground[i] ushr 24, plate[i] ushr 24) shl 24) or 0x00ffffff
    }
  }
}
