package os.kei.hyper.home.iconpack.perfect

import kotlin.math.abs
import kotlin.math.roundToInt

/** Recovers two text stencils from a flat snapshot and its independently supplied plate. */
internal object PerfectCompositeTextCoverage {
  fun night(
    flat: IntArray, background: IntArray, foreground: IntArray, nightBackground: IntArray,
    width: Int, height: Int, check: () -> Unit,
  ): IntArray? {
    if (width !in 8..2048 || height !in 8..2048) return null
    val count = width * height
    if (listOf(flat, background, foreground, nightBackground).any { it.size != count }) return null
    val opaque = BooleanArray(count)
    val residual = BooleanArray(count)
    val coloredCores = HashMap<Int, Int>()
    var visible = 0
    var blackCore = 0
    var residualCount = 0
    for (i in 0 until count) {
      if (i % width == 0) check()
      val plate = background[i]
      // A separate smooth, light, neutral plate is essential evidence, not a guessed matte.
      if (plate ushr 24 != 255 || flat[i] ushr 24 != 255 || nightBackground[i] ushr 24 != 255 ||
        minimum(plate) < 160 || maximum(plate) - minimum(plate) > 1) return null
      if (i % width != 0 && difference(plate, background[i - 1]) > 4 ||
        i >= width && difference(plate, background[i - width]) > 4) return null
      val alpha = foreground[i] ushr 24
      if (alpha > 0) {
        visible++
        val ink = foreground[i]
        if (maximum(ink) > 96 || maximum(ink) - minimum(ink) > 2) return null
        opaque[i] = alpha == 255
        if (alpha == 255 && maximum(ink) <= 2) blackCore++
        if (difference(flat[i], blend(plate, ink, alpha / 255.0)) > 2) return null
      } else if (difference(flat[i], plate) > 0) {
        residual[i] = true
        residualCount++
        val rgb = flat[i] and 0xffffff
        coloredCores[rgb] = (coloredCores[rgb] ?: 0) + 1
      }
    }
    if (visible < 16 || blackCore * 2 < visible || visible * 4 > count ||
      residualCount < 16 || residualCount * 8 > count) return null
    val ink = (coloredCores.maxByOrNull { it.value } ?: return null).let {
      if (it.value * 2 < residualCount) return null
      it.key or (255 shl 24)
    }
    if (maximum(ink) - minimum(ink) < 48) return null

    // Only a narrow gray rim around a predominantly black, separately supplied glyph is
    // normalized. Interior shading, colored artwork and broad gradients are not stencils.
    val depth = distanceFrom(opaque, width, height, check) { i ->
      i % width == 0 || i % width == width - 1 || i < width || i >= count - width ||
        !opaque[i - 1] || !opaque[i + 1] || !opaque[i - width] || !opaque[i + width]
    }
    val rim = (minOf(width, height) / 80).coerceIn(2, 4)
    for (i in 0 until count) {
      if (i % width == 0) check()
      if (opaque[i] && maximum(foreground[i]) > 2 && depth[i] > rim) return null
    }
    val coreDistance = distanceFrom(residual, width, height, check) { i ->
      difference(flat[i], ink) == 0
    }
    val result = nightBackground.clone()
    val nightInk = PerfectNightPixels.dim(ink)
    val white = PerfectNightPixels.dim(-1)
    for (i in 0 until count) {
      if (i % width == 0) check()
      val alpha = foreground[i] ushr 24
      if (alpha > 0) {
        result[i] = blend(nightBackground[i], white, alpha / 255.0)
      } else if (residual[i]) {
        // Colored text must be one solid ink with at most two pixels of antialias fringe.
        if (coreDistance[i] !in 1..3) return null
        val coverage = coverage(flat[i], background[i], ink) ?: return null
        result[i] = blend(nightBackground[i], nightInk, coverage)
      }
    }
    return result
  }

  private fun coverage(pixel: Int, plate: Int, ink: Int): Double? {
    var dot = 0.0
    var norm = 0.0
    for (shift in intArrayOf(0, 8, 16)) {
      val delta = channel(plate, shift) - channel(ink, shift)
      dot += (channel(plate, shift) - channel(pixel, shift)) * delta.toDouble()
      norm += delta * delta.toDouble()
    }
    if (norm < 4096) return null
    val alpha = dot / norm
    if (alpha !in -0.01..1.01) return null
    val clamped = alpha.coerceIn(0.0, 1.0)
    if (difference(pixel, blend(plate, ink, clamped)) > 2) return null
    return clamped
  }

  private fun distanceFrom(
    domain: BooleanArray, width: Int, height: Int, check: () -> Unit, seed: (Int) -> Boolean,
  ): IntArray {
    val distance = IntArray(domain.size)
    val queue = IntArray(domain.size)
    var head = 0
    var tail = 0
    for (i in domain.indices) {
      if (i % width == 0) check()
      if (domain[i] && seed(i)) {
        distance[i] = 1
        queue[tail++] = i
      }
    }
    while (head < tail) {
      if (head % 1024 == 0) check()
      val i = queue[head++]
      fun visit(j: Int) {
        if (domain[j] && distance[j] == 0) {
          distance[j] = distance[i] + 1
          queue[tail++] = j
        }
      }
      if (i % width > 0) visit(i - 1)
      if (i % width + 1 < width) visit(i + 1)
      if (i >= width) visit(i - width)
      if (i + width < width * height) visit(i + width)
    }
    return distance
  }

  private fun blend(plate: Int, ink: Int, alpha: Double): Int {
    fun value(shift: Int) =
      (channel(plate, shift) * (1 - alpha) + channel(ink, shift) * alpha).roundToInt().coerceIn(0, 255)
    return (255 shl 24) or (value(16) shl 16) or (value(8) shl 8) or value(0)
  }

  private fun channel(pixel: Int, shift: Int) = pixel ushr shift and 255
  private fun maximum(pixel: Int) = maxOf(channel(pixel, 0), channel(pixel, 8), channel(pixel, 16))
  private fun minimum(pixel: Int) = minOf(channel(pixel, 0), channel(pixel, 8), channel(pixel, 16))
  private fun difference(a: Int, b: Int) =
    maxOf(abs(channel(a, 0) - channel(b, 0)), abs(channel(a, 8) - channel(b, 8)),
      abs(channel(a, 16) - channel(b, 16)))
}
