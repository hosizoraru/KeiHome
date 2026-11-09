package os.kei.hyper.home.iconpack

import kotlin.math.abs
import kotlin.math.roundToInt

/** Pixel proof only: the caller must establish that the two images are genuine native variants. */
internal object NativeTwoToneMask {
  data class Result(val foregroundPixels: IntArray, val backgroundPixels: IntArray)

  /**
   * Accepts unpremultiplied ARGB pixels. Returns white RGB masks using the day image's alpha.
   * Deliberately rejects ambiguous artwork: background must be a strict majority of opaque samples
   * (alpha >= 254 to allow one-unit alpha rounding). Each endpoint must occupy at least 1% of the
   * canvas, and contrast must reach 96 in one RGB channel.
   * The silhouette must have transparent corners, cover at least 25% of the canvas, and have
   * contiguous row/column spans bounded by background color. This is not a semantic icon classifier.
   * Partial source alpha is supported only for background-only pixels in both variants: overlapping
   * foreground would increase source-over alpha above the preserved background alpha.
   * [check] runs at entry, at most 256 visited pixels/colors apart, and before success; exceptions
   * propagate. Inputs are never modified and must not be mutated during this call.
   */
  fun derive(
    width: Int,
    height: Int,
    dayPixels: IntArray,
    nightPixels: IntArray,
    check: () -> Unit = {},
  ): Result? {
    check()
    if (width !in 32..512 || height !in 32..512) return null
    val size = width * height
    if (dayPixels.size != size || nightPixels.size != size) return null
    val dayColors = HashMap<Int, Int>()
    val nightColors = HashMap<Int, Int>()
    var occupied = 0
    var opaque = 0
    var transparent = false
    for (i in 0 until size) {
      if (i and 255 == 0) check()
      val dayAlpha = dayPixels[i] ushr 24
      val nightAlpha = nightPixels[i] ushr 24
      if (abs(dayAlpha - nightAlpha) > 1) return null
      if (dayAlpha > 1) occupied++
      if (dayAlpha == 0) transparent = true
      if (dayAlpha >= 254) {
        opaque++
        count(dayColors, dayPixels[i])
      }
      if (nightAlpha >= 254) count(nightColors, nightPixels[i])
      // A segment with +/-2 residual in each other channel has at most 256 * 5 * 5 RGBs.
      if (dayColors.size > 6400 || nightColors.size > 6400) return null
    }
    if (!transparent || occupied < size / 4 || opaque < size / 4) return null
    val day = palette(dayColors, size, check) ?: return null
    val night = palette(nightColors, size, check) ?: return null
    if (day.background == night.background && day.foreground == night.foreground) return null

    val foreground = IntArray(size)
    val background = IntArray(size)
    val borderBackground = BooleanArray(size)
    var clearForeground = false
    var solidForeground = false
    for (i in 0 until size) {
      if (i and 255 == 0) check()
      val dayAlpha = dayPixels[i] ushr 24
      val nightAlpha = nightPixels[i] ushr 24
      val dayCoverage = if (dayAlpha == 0) 0.0 else day.coverage(dayPixels[i]) ?: return null
      val nightCoverage = if (nightAlpha == 0) 0.0 else night.coverage(nightPixels[i]) ?: return null
      if (dayAlpha > 0 && nightAlpha > 0 && abs(dayCoverage - nightCoverage) > 2.0) return null
      if ((dayAlpha in 1..254 || nightAlpha in 1..254) &&
        (dayCoverage > 0.0 || nightCoverage > 0.0)) return null
      val coverage = when {
        dayAlpha == 0 -> 0.0
        nightAlpha == 0 -> dayCoverage
        else -> (dayCoverage + nightCoverage) / 2.0
      }
      val alpha = (coverage * dayAlpha / 255.0).roundToInt()
      foreground[i] = (alpha shl 24) or 0xffffff
      background[i] = (dayAlpha shl 24) or 0xffffff
      borderBackground[i] = dayCoverage <= 2.0 && nightCoverage <= 2.0
      if (dayAlpha == 255 && alpha == 0) clearForeground = true
      if (alpha == 255) solidForeground = true
    }
    if (!clearForeground || !solidForeground) return null
    if (!plausibleShape(width, height, background, borderBackground, check)) return null
    check()
    return Result(foreground, background)
  }

  private fun count(colors: MutableMap<Int, Int>, color: Int) {
    val rgb = color and 0xffffff
    colors[rgb] = (colors[rgb] ?: 0) + 1
  }

  private fun palette(colors: Map<Int, Int>, size: Int, check: () -> Unit): Palette? {
    var first = 0
    var second = 0
    var firstCount = 0
    var secondCount = 0
    var total = 0
    var visited = 0
    for ((color, count) in colors) {
      if (visited++ and 255 == 0) check()
      total += count
      if (count > firstCount) {
        second = first
        secondCount = firstCount
        first = color
        firstCount = count
      } else if (count > secondCount) {
        second = color
        secondCount = count
      }
    }
    if (total < size / 4 || firstCount * 2 <= total || secondCount < (size + 99) / 100) return null
    val shifts = intArrayOf(16, 8, 0)
    val axis = shifts.maxBy { abs(channel(second, it) - channel(first, it)) }
    if (abs(channel(second, axis) - channel(first, axis)) < 96) return null
    return Palette(first, second, axis)
  }

  private data class Palette(val background: Int, val foreground: Int, val axis: Int) {
    fun coverage(pixel: Int): Double? {
      // Use the strongest channel, avoiding instability in near-constant channels.
      val fraction = ((channel(pixel, axis) - channel(background, axis)).toDouble() /
        (channel(foreground, axis) - channel(background, axis))).coerceIn(0.0, 1.0)
      for (shift in 16 downTo 0 step 8) {
        val expected = channel(background, shift) +
          fraction * (channel(foreground, shift) - channel(background, shift))
        if (abs(channel(pixel, shift) - expected) > 2.0) return null
      }
      return fraction * 255.0
    }
  }

  private fun channel(color: Int, shift: Int): Int = (color ushr shift) and 255

  private fun plausibleShape(
    width: Int,
    height: Int,
    alphaPixels: IntArray,
    borderBackground: BooleanArray,
    check: () -> Unit,
  ): Boolean {
    // Ignore alpha <=1 only for geometry, so permitted day/night alpha rounding cannot add holes.
    val corners = intArrayOf(0, width - 1, (height - 1) * width, width * height - 1)
    if (corners.any { alphaPixels[it] ushr 24 > 1 }) return false
    var occupiedRows = 0
    var occupiedColumns = 0
    for (vertical in listOf(false, true)) {
      val lines = if (vertical) width else height
      val length = if (vertical) height else width
      for (line in 0 until lines) {
        var first = -1
        var last = -1
        var ended = false
        for (offset in 0 until length) {
          if ((line * length + offset) and 255 == 0) check()
          val i = if (vertical) offset * width + line else line * width + offset
          if (alphaPixels[i] ushr 24 > 1) {
            if (ended) return false
            if (first == -1) first = i
            last = i
          } else if (first != -1) {
            ended = true
          }
        }
        if (first != -1) {
          if (!borderBackground[first] || !borderBackground[last]) return false
          if (vertical) occupiedColumns++ else occupiedRows++
        }
      }
    }
    return occupiedRows * 2 >= height && occupiedColumns * 2 >= width
  }
}
