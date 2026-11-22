package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Color

/** Extracts the known foreground from a baked icon without bundling its pixels. */
internal enum class ExtraFlatArtwork {
  LIGHT_MARK,
  BLUE_BUBBLE,
  WALLET_CARD;

  fun isolate(layer: Bitmap, tint: Int?, check: () -> Unit): Boolean {
    val width = layer.width
    val height = layer.height
    val pixels = IntArray(width * height)
    layer.getPixels(pixels, 0, width, 0, 0, width, height)
    var marked = 0
    for (y in 0 until height) {
      check()
      val row = y * width
      var left = width
      var right = -1
      if (this != LIGHT_MARK) {
        for (x in 0 until width) {
          if (edgeCoverage(pixels[row + x]) >= 128) {
            left = minOf(left, x)
            right = x
          }
        }
      }
      for (x in 0 until width) {
        val pixel = pixels[row + x]
        // The outline encloses the question mark or the colored cards.
        val coverage = if (x in left..right) 255 else edgeCoverage(pixel)
        val alpha = Color.alpha(pixel) * coverage / 255
        if (alpha >= 128) {
          if (x < width / 10 || x >= width - width / 10 ||
            y < height / 10 || y >= height - height / 10) return false
          marked++
        }
        pixels[row + x] = (alpha shl 24) or ((tint ?: pixel) and 0x00ffffff)
      }
    }
    if (marked !in pixels.size / 100..pixels.size / 2) return false
    layer.setPixels(pixels, 0, width, 0, 0, width, height)
    return true
  }

  private fun edgeCoverage(pixel: Int): Int {
    val red = Color.red(pixel)
    val green = Color.green(pixel)
    val blue = Color.blue(pixel)
    return when (this) {
      LIGHT_MARK -> (minOf(red, green, blue) - 120) * 255 / 125
      BLUE_BUBBLE -> (blue - red - 24) * 255 / 112
      WALLET_CARD -> (minOf(red, green, blue) - 104) * 255 / 96
    }.coerceIn(0, 255)
  }
}
