package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/** Extracts the known foreground from a baked icon without bundling its pixels. */
internal enum class ExtraFlatArtwork {
  LIGHT_MARK,
  BLUE_BUBBLE,
  WALLET_CARD,
  MUSIC_MARK,
  OPEN_DISPLAY;

  val isolatesAdaptiveForeground: Boolean get() = this == WALLET_CARD || this == OPEN_DISPLAY

  fun isolate(layer: Bitmap, tint: Int?, check: () -> Unit): Boolean {
    val width = layer.width
    val height = layer.height
    val pixels = IntArray(width * height)
    layer.getPixels(pixels, 0, width, 0, 0, width, height)
    if (this == OPEN_DISPLAY) {
      if (!isolateDisplay(pixels, width, height, check)) return false
      layer.setPixels(pixels, 0, width, 0, 0, width, height)
      return true
    }
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
        // Keep the artwork enclosed by the colored outline, including the music note.
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
      MUSIC_MARK -> (green - red - 12) * 255 / 110
      OPEN_DISPLAY -> 0
    }.coerceIn(0, 255)
  }

  private fun isolateDisplay(pixels: IntArray, width: Int, height: Int, check: () -> Unit): Boolean {
    val top = IntArray(width) { height }
    val bottom = IntArray(width) { -1 }
    var colored = 0
    for (y in 0 until height) {
      check()
      val row = y * width
      for (x in 0 until width) {
        val pixel = pixels[row + x]
        if (Color.blue(pixel) - Color.red(pixel) >= 88) {
          top[x] = minOf(top[x], y)
          bottom[x] = y
          colored++
        }
      }
    }
    if (colored !in pixels.size / 16..pixels.size / 4) return false
    for (y in 0 until height) {
      check()
      val row = y * width
      for (x in 0 until width) {
        val pixel = pixels[row + x]
        val fillRed = if (x < width / 2) 0 else 110
        val fillGreen = if (x < width / 2) 155 else 219
        // Unmix the white backing so antialiased edges carry only ink and blue.
        val fill = ((Color.blue(pixel) - Color.red(pixel) - 3f) / (252 - fillRed)).coerceIn(0f, 1f)
        val ink = ((252 - Color.blue(pixel)) / 235f).coerceIn(0f, 1f - fill)
        val coverage = fill + ink
        if (coverage < .035f) {
          pixels[row + x] = Color.TRANSPARENT
          continue
        }
        // On the blue faces the strokes stay dark, including the sloped smiles.
        val inkColor = if (y in top[x]..bottom[x]) 0x111111 else 0xdbe4f3
        fun channel(fillValue: Int, shift: Int): Int =
          ((fill * fillValue + ink * (inkColor shr shift and 255)) / coverage).roundToInt()
        pixels[row + x] = Color.argb((Color.alpha(pixel) * coverage).roundToInt(),
          channel(fillRed, 16), channel(fillGreen, 8), channel(255, 0))
      }
    }
    return true
  }
}
