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

  fun tint(pixels: IntArray, dark: Boolean): Int {
    var result = 0xff000000.toInt()
    for (shift in 0..16 step 8) {
      val mean = pixels.sumOf { (it ushr shift) and 255 } / pixels.size
      val channel = if (dark) mean / 5 else 204 + mean / 5
      result = result or (channel shl shift)
    }
    return result
  }

  fun darkHandle(pixels: IntArray, previous: Boolean?): Boolean? {
    if (pixels.any { it ushr 24 != 255 }) return null
    val light = pixels.map { pixel ->
      0.2126 * linear[(pixel ushr 16) and 255] + 0.7152 * linear[(pixel ushr 8) and 255] +
        0.0722 * linear[pixel and 255]
    }.sorted()
    // Compare the weaker quarter of each handle against its background, tolerating isolated highlights.
    val darkContrast = (light[light.size / 4] + 0.05) / 0.05
    val whiteContrast = 1.05 / (light[light.size - 1 - light.size / 4] + 0.05)
    return when (previous) {
      true -> darkContrast * 1.18 >= whiteContrast
      false -> darkContrast > whiteContrast * 1.18
      null -> darkContrast >= whiteContrast
    }
  }
}
