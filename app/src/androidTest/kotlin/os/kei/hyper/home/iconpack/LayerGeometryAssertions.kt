package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*

/** Compare geometry independently of the small Skia Drawable/raster edge differences. */
internal object LayerGeometryAssertions {
  fun assertNative(expected: Bitmap, actual: Bitmap, label: String) {
    assertEquals(expected.width, actual.width)
    assertEquals(expected.height, actual.height)
    val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
      "layer-geometry-evidence").apply { mkdirs() }
    for ((suffix, bitmap) in listOf("expected" to expected, "actual" to actual)) {
      File(folder, "$label-$suffix.png").outputStream().use {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }
    }
    var error = 0L
    var maximum = 0
    var cornerOutliers = 0
    val background = expected.getPixel(0, 0)
    val ink = arrayOf(mutableListOf<Pair<Int, Int>>(), mutableListOf())
    for (y in 0 until expected.height) for (x in 0 until expected.width) {
      val pixels = intArrayOf(expected.getPixel(x, y), actual.getPixel(x, y))
      var pixelMaximum = 0
      for (shift in 0..3) {
        val delta = abs((pixels[0] ushr (shift * 8) and 255) - (pixels[1] ushr (shift * 8) and 255))
        error += delta
        maximum = maxOf(maximum, delta)
        pixelMaximum = maxOf(pixelMaximum, delta)
      }
      if (pixelMaximum > 12) cornerOutliers++
      pixels.forEachIndexed { index, pixel ->
        val contrast = (0..2).sumOf { shift ->
          abs((pixel ushr (shift * 8) and 255) - (background ushr (shift * 8) and 255))
        }
        if (Color.alpha(pixel) >= 128 && contrast > 128) ink[index] += x to y
      }
    }
    val mean = error.toDouble() / (expected.width * expected.height * 4)
    // The device's two Skia paths differ by up to 18/255 at one filtered corner.
    assertTrue("$label mean=$mean max=$maximum outliers=$cornerOutliers",
      mean < .5 && maximum <= 20 && cornerOutliers <= 1)
    assertTrue("$label has foreground", ink[0].isNotEmpty() && ink[1].isNotEmpty())
    val bounds = ink.map { points -> listOf(points.minOf { it.first }, points.minOf { it.second },
      points.maxOf { it.first }, points.maxOf { it.second }) }
    assertEquals("$label foreground bounds", bounds[0], bounds[1])
    assertTrue("$label foreground coverage", abs(ink[0].size - ink[1].size) <= maxOf(1, ink[0].size / 100))
  }
}
