package os.kei.hyper.home.xposed

import kotlin.math.abs
import kotlin.math.pow

/** The rendered strip is downsampled horizontally. Accept a solid fill only when all samples agree. */
internal object FooterPalette {
  private val linear = DoubleArray(256) { channel ->
    val value = channel / 255.0
    if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
  }

  fun solid(pixels: IntArray): Int? {
    val middle = pixels[pixels.size / 2]
    for (pixel in pixels) {
      if (pixel ushr 24 != 255) return null
      for (shift in 0..16 step 8) {
        if (abs(((pixel ushr shift) and 255) - ((middle ushr shift) and 255)) > 8) return null
      }
    }
    return middle
  }

  private fun luminance(r: Int, g: Int, b: Int) =
    0.2126 * linear[r] + 0.7152 * linear[g] + 0.0722 * linear[b]

  /** A neutral translucent paint lets each part of the real artwork supply its own hue. */
  fun handlePaint(pixels: IntArray, previousDark: Boolean?): Int? {
    if (pixels.isEmpty() || pixels.any { it ushr 24 != 255 }) return null
    val light = DoubleArray(pixels.size) { i ->
      val rgb = pixels[i]
      luminance(rgb ushr 16 and 255, rgb ushr 8 and 255, rgb and 255)
    }
    val sorted = light.sortedArray()
    // Compare the weaker quarter of each handle against its background, tolerating isolated highlights.
    val darkContrast = (sorted[sorted.size / 4] + 0.05) / 0.05
    val whiteContrast = 1.05 / (sorted[sorted.lastIndex - sorted.size / 4] + 0.05)
    val darkCoverage = light.count { it + .05 >= 3 * .05 }
    val whiteCoverage = light.count { 1.05 >= 3 * (it + .05) }
    val quorum = pixels.size - pixels.size / 4
    val dark = when {
      maxOf(darkCoverage, whiteCoverage) < quorum &&
        abs(darkCoverage - whiteCoverage) > pixels.size / 10 -> darkCoverage > whiteCoverage
      previousDark == true -> darkContrast * 1.18 >= whiteContrast
      previousDark == false -> darkContrast > whiteContrast * 1.18
      else -> darkContrast >= whiteContrast
    }
    val ink = if (dark) 0 else 255
    fun readable(alpha: Int): Int {
      val bias = ink * alpha + 127
      val remaining = 255 - alpha
      fun blend(component: Int) = (component * remaining + bias) / 255
      return pixels.indices.count { i ->
        val rgb = pixels[i]
        val painted = luminance(blend(rgb ushr 16 and 255), blend(rgb ushr 8 and 255), blend(rgb and 255))
        if (dark) light[i] + .05 >= 3 * (painted + .05) else painted + .05 >= 3 * (light[i] + .05)
      }
    }
    // On a split black/white scene one polarity cannot cover both halves. Stop increasing
    // opacity once it covers every sample that even opaque ink could make legible.
    val target = minOf(quorum, if (dark) darkCoverage else whiteCoverage)
    var low = if (dark) 102 else 128
    var high = 255
    while (low < high) {
      val mid = (low + high) / 2
      if (readable(mid) >= target) high = mid else low = mid + 1
    }
    return (low shl 24) or (if (dark) 0 else 0xffffff)
  }
}
