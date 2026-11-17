package os.kei.hyper.home.iconpack

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Generation-time contrast extraction. Alpha-only tinting would erase opaque logos. */
internal object GenericMonetMask {
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

  fun foreground(pixels: IntArray, size: Int, check: () -> Unit = {}): IntArray? {
    require(size in 1..512 && pixels.size == size * size)
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
    val plate = background(pixels, size, check)
    return IntArray(pixels.size) { i ->
      val alpha = plate[i] ushr 24
      val mask = if (cutout) alpha else
        (alpha * ((contrasts[i] - 12).toFloat() / (peak - 12)).coerceIn(0f, 1f)).roundToInt()
      (mask shl 24) or 0x00ffffff
    }
  }
}
