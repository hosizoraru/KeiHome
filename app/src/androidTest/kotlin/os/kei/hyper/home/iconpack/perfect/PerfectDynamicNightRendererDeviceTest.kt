package os.kei.hyper.home.iconpack.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class PerfectDynamicNightRendererDeviceTest {
  @Test fun foregroundKeepsTextureDimensionsAndEveryAlphaValue() {
    val bytes = image(256, 2) { x, _ -> Color.argb(x, 255, 128, 64) }
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val night = requireNotNull(PerfectDynamicNightRenderer.render(bytes, false) {})
    val decoded = BitmapFactory.decodeByteArray(night, 0, night.size)
    try {
      assertEquals(source.width, decoded.width)
      assertEquals(source.height, decoded.height)
      for (x in 0..255) {
        assertEquals(Color.alpha(source.getPixel(x, 0)), Color.alpha(decoded.getPixel(x, 0)))
      }
      val opaque = decoded.getPixel(255, 0)
      assertEquals(214, Color.red(opaque))
      assertEquals(107, Color.green(opaque))
      assertEquals(54, Color.blue(opaque))
    } finally { source.recycle(); decoded.recycle() }
  }

  @Test fun compressedGradientBackgroundDoesNotRetainItsLowerColorBand() {
    val bytes = image(365, 365) { x, y ->
      val ringing = if (x < 4 || y < 4 || x >= 361 || y >= 361)
        if ((x + y) % 2 == 0) 18 else -18 else 0
      Color.argb(if (x == 0 || x == 364) 180 else 255, 250,
        (110 - 75 * y / 364 + ringing).coerceIn(0, 255),
        (140 - 80 * y / 364 + ringing).coerceIn(0, 255))
    }
    decoded(bytes, true) { source, night ->
      for (y in 0 until 365) for (x in 0 until 365) {
        val p = night.getPixel(x, y)
        assertEquals(Color.alpha(source.getPixel(x, y)), Color.alpha(p))
        assertTrue("Background retained color at $x,$y", Color.red(p) == Color.green(p) &&
          Color.green(p) == Color.blue(p) && Color.red(p) < 64)
      }
    }
  }

  @Test fun plainPlateGetsVerticalGradientWithoutAddingAnOpaqueSquare() {
    val bytes = image(32, 32) { x, y ->
      Color.argb(if (x == 0 || y == 0) 0 else 255, 240 - y, 240 - y, 240 - y)
    }
    val night = requireNotNull(PerfectDynamicNightRenderer.render(bytes, true) {})
    val decoded = BitmapFactory.decodeByteArray(night, 0, night.size)
    try {
      assertEquals(0, Color.alpha(decoded.getPixel(0, 16)))
      assertEquals(0, Color.alpha(decoded.getPixel(16, 0)))
      assertEquals(46, Color.red(decoded.getPixel(16, 1)))
      assertEquals(18, Color.red(decoded.getPixel(16, 31)))
    } finally { decoded.recycle() }
  }

  @Test fun bakedMarksRemainLegibleOnSegmentedPlate() {
    val bytes = image(32, 32) { x, y ->
      if (x in 11..19 && y in 8..24) Color.BLACK else Color.WHITE
    }
    decoded(bytes, true) { source, night ->
      assertEquals(Color.BLACK, source.getPixel(15, 16))
      assertEquals(Color.rgb(214, 214, 214), night.getPixel(15, 16))
      assertEquals(Color.rgb(47, 47, 47), night.getPixel(0, 0))
    }
  }

  @Test fun blackStencilsLiftAndRetainEveryAlphaValue() {
    val bytes = image(32, 32) { x, _ -> if (x in 10..20) Color.BLACK else Color.TRANSPARENT }
    decoded(bytes, false) { source, night ->
      assertEquals(Color.BLACK, source.getPixel(15, 16))
      assertEquals(Color.rgb(214, 214, 214), night.getPixel(15, 16))
      for (y in 0 until source.height) for (x in 0 until source.width)
        assertEquals(Color.alpha(source.getPixel(x, y)), Color.alpha(night.getPixel(x, y)))
    }
  }

  @Test fun retainedCalendarDateThirteenLiftsItsOpaqueCoreAndKeepsAntialiasAlpha() {
    val fixture = androidx.test.platform.app.InstrumentationRegistry.getArguments()
      .getString("perfectCalendarFrameFixture")
    org.junit.Assume.assumeTrue("Retained source graphics are supplied separately", fixture != null)
    val file = java.io.File(requireNotNull(fixture))
    assertTrue(file.length() in 1..1024L * 1024)
    val bytes = file.readBytes()
    assertEquals("c68993bd9978ab18cf88087f78e3df318cb79236cbc90d3578cfdeabce7de6cd",
      java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    val original = bytes.clone()
    decoded(bytes, false) { source, night ->
      assertEquals(137, source.width)
      assertEquals(172, source.height)
      var opaque = 0
      var nightLuma = 0L
      for (y in 0 until source.height) for (x in 0 until source.width) {
        val dayPixel = source.getPixel(x, y)
        val nightPixel = night.getPixel(x, y)
        assertEquals(Color.alpha(dayPixel), Color.alpha(nightPixel))
        if (Color.alpha(dayPixel) >= 240) {
          opaque++
          val luma = (299 * Color.red(nightPixel) + 587 * Color.green(nightPixel) +
            114 * Color.blue(nightPixel)) / 1000
          // Neutral RGB 150 gives >4.5:1 contrast against the brightest dark plate (RGB 47).
          assertTrue("Opaque date glyph remains dark at $x,$y", luma >= 150)
          nightLuma += luma
        }
      }
      assertTrue(opaque > 3500)
      assertTrue("Night date must contrast with the dark plate", nightLuma / opaque > 180)
    }
    assertArrayEquals(original, bytes)
  }

  @Test fun nearUniformTwoDimensionalGradientPlateIsRecognized() {
    val bytes = image(32, 32) { x, y ->
      Color.rgb(180 + x / 3 + y / 3, 40 + x / 3, 30 + y / 3)
    }
    decoded(bytes, true) { _, night ->
      assertEquals(Color.rgb(47, 47, 47), night.getPixel(0, 0))
      assertEquals(Color.rgb(18, 18, 18), night.getPixel(31, 31))
      for (y in 0 until night.height) for (x in 0 until night.width) {
        val p = night.getPixel(x, y)
        assertEquals(Color.red(p), Color.green(p))
        assertEquals(Color.red(p), Color.blue(p))
      }
    }
  }

  @Test fun opaqueForegroundPlateIsSegmentedButSolidColoredSpriteIsNot() {
    val baked = image(32, 32) { x, y ->
      if (x in 11..19 && y in 8..24) Color.RED else Color.WHITE
    }
    decoded(baked, false) { _, night ->
      assertEquals(Color.rgb(47, 47, 47), night.getPixel(0, 0))
      assertEquals(Color.rgb(214, 0, 0), night.getPixel(15, 16))
    }
    decoded(image(32, 32) { _, _ -> Color.RED }, false) { _, night ->
      assertEquals(Color.rgb(214, 0, 0), night.getPixel(0, 0))
    }
  }

  @Test fun fullBleedArtDeclinesPlateReplacementAndRetainsForegroundArtwork() {
    val bytes = image(32, 32) { x, y ->
      Color.rgb((x * 19 + y * 13) % 256, (x * 7 + y * 31) % 256, (x * 23 + y * 11) % 256)
    }
    assertNull(PerfectDynamicNightRenderer.render(bytes, true) {})
    decoded(bytes, false) { source, night ->
      for (y in 0 until source.height) for (x in 0 until source.width) {
        val pixel = source.getPixel(x, y)
        fun dim(value: Int) = (value * 214 + 127) / 255
        assertEquals(Color.rgb(dim(Color.red(pixel)), dim(Color.green(pixel)), dim(Color.blue(pixel))),
          night.getPixel(x, y))
      }
    }
  }

  @Test fun cancellationDuringPlateScanPropagates() {
    val bytes = image(64, 64) { _, _ -> Color.WHITE }
    var checks = 0
    try {
      PerfectDynamicNightRenderer.render(bytes, true) {
        if (++checks == 10) throw kotlinx.coroutines.CancellationException()
      }
      fail()
    } catch (_: kotlinx.coroutines.CancellationException) {
      assertEquals(10, checks)
    }
  }

  @Test fun lowRangeMonochromeTextureIsNotMistakenForPlateOrDarkGlyph() {
    val bytes = image(32, 32) { x, y ->
      val value = (x * 13 + y * 7) % 97
      Color.rgb(value, value, value)
    }
    assertNull(PerfectDynamicNightRenderer.render(bytes, true) {})
    decoded(bytes, false) { source, night ->
      for (y in 0 until source.height) for (x in 0 until source.width) {
        val gray = (Color.red(source.getPixel(x, y)) * 214 + 127) / 255
        assertEquals(Color.rgb(gray, gray, gray), night.getPixel(x, y))
      }
    }
  }

  @Test fun opaqueLowKeyArtworkAndSolidBlackSpriteAreNeverInverted() {
    for (detailed in listOf(false, true)) {
      val bytes = image(32, 32) { x, y ->
        when {
          !detailed -> Color.BLACK
          x in 15..17 && y in 15..17 -> Color.WHITE
          else -> {
            val value = 8 + (x + y) % 12
            Color.rgb(value, value, value)
          }
        }
      }
      decoded(bytes, false) { source, night ->
        assertTrue(Color.red(night.getPixel(5, 5)) < 64)
        if (detailed) assertEquals(Color.rgb(214, 214, 214), night.getPixel(16, 16))
        else assertEquals(Color.BLACK, night.getPixel(16, 16))
        for (y in 0 until source.height) for (x in 0 until source.width)
          assertEquals(Color.alpha(source.getPixel(x, y)), Color.alpha(night.getPixel(x, y)))
      }
    }
  }

  @Test fun transparentLowKeyArtWithBrightDetailIsNotAStencil() {
    val bytes = image(32, 32) { x, y ->
      when {
        x !in 8..23 || y !in 8..23 -> Color.TRANSPARENT
        x == 16 && y == 16 -> Color.WHITE
        else -> Color.rgb(12, 12, 12)
      }
    }
    decoded(bytes, false) { _, night ->
      assertEquals(Color.rgb(10, 10, 10), night.getPixel(10, 10))
      assertEquals(Color.rgb(214, 214, 214), night.getPixel(16, 16))
    }
  }

  @Test fun transparentLowKeyTextureWithoutDominantCoreIsNotAStencil() {
    val bytes = image(32, 32) { x, y ->
      if (x !in 8..23 || y !in 8..23) Color.TRANSPARENT
      else {
        val value = (x * 13 + y * 7) % 97
        Color.rgb(value, value, value)
      }
    }
    decoded(bytes, false) { source, night ->
      for (y in 8..23) for (x in 8..23) {
        val value = (Color.red(source.getPixel(x, y)) * 214 + 127) / 255
        assertEquals(Color.rgb(value, value, value), night.getPixel(x, y))
      }
    }
  }

  @Test fun cancellationStopsBeforeAllocation() {
    var called = false
    try {
      PerfectDynamicNightRenderer.render(byteArrayOf(), false) {
        called = true
        throw kotlinx.coroutines.CancellationException()
      }
      fail()
    } catch (_: kotlinx.coroutines.CancellationException) { assertTrue(called) }
  }

  private fun decoded(bytes: ByteArray, background: Boolean, test: (Bitmap, Bitmap) -> Unit) {
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val result = requireNotNull(PerfectDynamicNightRenderer.render(bytes, background) {})
    val night = BitmapFactory.decodeByteArray(result, 0, result.size)
    try { test(source, night) } finally { source.recycle(); night.recycle() }
  }

  private fun image(width: Int, height: Int, color: (Int, Int) -> Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
      for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, color(x, y))
      return ByteArrayOutputStream().also {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }.toByteArray()
    } finally { bitmap.recycle() }
  }
}
