package os.kei.hyper.home.iconpack.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerfectStaticNightRendererDeviceTest {
  @Test fun backgroundGradientHasExactEndpointsAndWholeForegroundIsMultiplied() {
    val background = image(8, 8) { _, _ -> Color.rgb(220, 70, 30) }
    val foreground = image(8, 8) { x, y ->
      if (x in 2..5 && y in 2..5) Color.rgb(255, 128, 64) else Color.TRANSPARENT
    }
    rendered(listOf(background, foreground)) { day, night ->
      assertEquals(Color.rgb(220, 70, 30), day.getPixel(0, 0))
      assertEquals(Color.rgb(255, 128, 64), day.getPixel(256, 256))
      assertRgb(night.getPixel(0, 0), dim(56), dim(56), dim(56), 0)
      assertRgb(night.getPixel(0, 511), dim(21), dim(21), dim(21), 0)
      val middleGray = 56 - (35 * 256 + 255) / 511
      assertRgb(night.getPixel(0, 256), dim(middleGray), dim(middleGray), dim(middleGray), 0)
      assertRgb(night.getPixel(256, 256), 214, dim(128), dim(64), 0)
    }
  }

  @Test fun dayUsesDeclaredSourceOverOrderAndNightDimsFinishedComposite() {
    val background = image(1, 1) { _, _ -> Color.argb(128, 255, 0, 0) }
    val foreground = image(1, 1) { _, _ -> Color.argb(128, 0, 0, 255) }
    rendered(listOf(background, foreground)) { day, night ->
      val light = day.getPixel(256, 0)
      assertEquals(192, Color.alpha(light))
      assertRgb(light, 85, 0, 170)
      val dark = night.getPixel(256, 0)
      assertEquals(Color.alpha(light), Color.alpha(dark))
      assertRgb(dark, dim(56.0 / 3), dim(56.0 / 3), dim(56.0 / 3 + 170))
    }
  }

  @Test fun transparentBackgroundDoesNotInventCoverageAndAllAlphaIsRetained() {
    val background = image(512, 512) { x, _ -> Color.argb(x % 256, 80, 120, 200) }
    val foreground = image(512, 512) { x, y ->
      Color.argb(if (y < 256) 0 else x % 256, 240, 120, 60)
    }
    rendered(listOf(background, foreground)) { day, night ->
      assertEquals(0, Color.alpha(night.getPixel(0, 0)))
      assertEquals(128, Color.alpha(night.getPixel(128, 0)))
      assertEquals(192, Color.alpha(night.getPixel(128, 400)))
      assertAlphaEqual(day, night)
    }
  }

  @Test fun flatFallbackDimsOriginalRgbWithoutReplacingOpaqueOrPartialPixels() {
    val flat = image(512, 512) { x, y ->
      Color.argb(if (x < 256) 255 else y % 256, 200, 100, 50)
    }
    rendered(listOf(flat)) { day, night ->
      for (y in listOf(0, 256, 511)) {
        assertEquals(Color.rgb(200, 100, 50), day.getPixel(0, y))
        assertRgb(night.getPixel(0, y), dim(200), dim(100), dim(50), 0)
      }
      assertEquals(128, Color.alpha(night.getPixel(400, 128)))
      assertRgb(night.getPixel(400, 128), dim(200), dim(100), dim(50))
      assertEquals(0, Color.alpha(night.getPixel(400, 0)))
      assertAlphaEqual(day, night)
    }
  }

  @Test fun sourceBytesRemainUnchangedAndRepeatedOutputIsDeterministicSrgbPng() {
    val layers = listOf(
      image(4, 4) { _, _ -> Color.argb(128, 180, 80, 20) },
      image(4, 4) { x, _ -> if (x < 2) Color.GREEN else Color.TRANSPARENT },
    )
    val originals = layers.map(ByteArray::clone)
    val first = PerfectStaticNightRenderer.render(layers, null)
    val second = PerfectStaticNightRenderer.render(layers, null)
    layers.indices.forEach { assertArrayEquals(originals[it], layers[it]) }
    assertArrayEquals(first.day, second.day)
    assertArrayEquals(first.night, second.night)
    assertEquals("perfect-static-night-1", PerfectStaticNightRenderer.REVISION)
    for (bytes in listOf(first.day, first.night)) {
      assertArrayEquals(PNG_SIGNATURE, bytes.copyOf(8))
      val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
      try {
        assertEquals(512, bitmap.width)
        assertEquals(512, bitmap.height)
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bitmap.colorSpace)
      } finally { bitmap.recycle() }
    }
  }

  @Test fun nativeMaskScalesItsViewportAndAntialiasesBothWithoutReplacingAlpha() {
    val mask = "M50,0 C77.614237,0 100,22.385763 100,50 " +
      "C100,77.614237 77.614237,100 50,100 " +
      "C22.385763,100 0,77.614237 0,50 C0,22.385763 22.385763,0 50,0 Z"
    val source = image(1, 1) { _, _ -> Color.argb(128, 200, 100, 50) }
    rendered(listOf(source), mask) { day, night ->
      assertEquals(0, Color.alpha(day.getPixel(0, 0)))
      assertEquals(0, Color.alpha(night.getPixel(511, 511)))
      assertEquals(128, Color.alpha(day.getPixel(256, 256)))
      assertEquals(128, Color.alpha(night.getPixel(256, 256)))
      var partialEdges = 0
      for (y in 0 until 512) for (x in 0 until 512) {
        val alpha = Color.alpha(day.getPixel(x, y))
        assertTrue(alpha <= 128)
        if (alpha in 1..127) partialEdges++
      }
      assertTrue("Mask edges must contain antialiased coverage", partialEdges > 0)
      assertAlphaEqual(day, night)
    }
    rendered(listOf(source, image(1, 1) { _, _ -> Color.TRANSPARENT }), mask) { day, night ->
      assertEquals(128, Color.alpha(night.getPixel(256, 256)))
      assertEquals(0, Color.alpha(night.getPixel(0, 0)))
      assertAlphaEqual(day, night)
    }
  }

  @Test fun asymmetricMaskUsesFullCanvasWithoutAdaptiveInset() {
    val mask = "M0,0 H25 V100 H0 Z"
    rendered(listOf(image(1, 1) { _, _ -> Color.RED }), mask) { day, night ->
      assertEquals(255, Color.alpha(day.getPixel(0, 0)))
      assertEquals(255, Color.alpha(day.getPixel(64, 511)))
      assertEquals(0, Color.alpha(day.getPixel(256, 256)))
      assertAlphaEqual(day, night)
    }
  }

  @Test fun nonsquareLayersStretchIndependentlyAcrossTheEntireCanvas() {
    val background = image(2, 8) { x, _ -> if (x == 0) Color.RED else Color.GREEN }
    val foreground = image(8, 2) { _, y -> if (y == 0) Color.BLUE else Color.TRANSPARENT }
    rendered(listOf(background, foreground)) { day, night ->
      assertEquals(Color.BLUE, day.getPixel(0, 0))
      assertEquals(Color.BLUE, day.getPixel(511, 0))
      assertEquals(Color.RED, day.getPixel(0, 511))
      assertEquals(Color.GREEN, day.getPixel(511, 511))
      assertRgb(night.getPixel(0, 0), 0, 0, 214, 0)
      assertRgb(night.getPixel(511, 511), dim(21), dim(21), dim(21), 0)
    }
  }

  @Test fun maximumRasterAndExtremeAspectRatiosUseBoundedSampledDecode() {
    // Stream PNG rows so this test never allocates a full 8192-square fixture bitmap.
    for ((width, height) in listOf(8192 to 8192, 8192 to 1, 1 to 8192)) {
      rendered(listOf(solidPng(width, height))) { day, night ->
        assertEquals(Color.rgb(200, 100, 50), day.getPixel(0, 0))
        assertEquals(Color.rgb(200, 100, 50), day.getPixel(511, 511))
        assertRgb(night.getPixel(256, 256), dim(200), dim(100), dim(50), 0)
      }
    }
    rendered(listOf(solidPng(1537, 3))) { day, _ ->
      assertEquals(Color.rgb(200, 100, 50), day.getPixel(511, 511))
    }
  }

  @Test fun rejectsInvalidLayerCountsByteBoundsImagesAndRasterBounds() {
    val valid = image(1, 1) { _, _ -> Color.RED }
    for (layers in listOf(
      emptyList(), listOf(valid, valid, valid), listOf(byteArrayOf()),
      listOf(ByteArray(8 * 1024 * 1024 + 1)), listOf("not an image".toByteArray()),
      listOf(valid, byteArrayOf(1, 2, 3)), listOf(solidPng(8193, 1)),
      listOf(solidPng(1, 8193)), listOf(solidPng(0, 1)),
    )) {
      rejected { PerfectStaticNightRenderer.render(layers, null) }
    }
  }

  @Test fun rejectsInvalidNativeMasks() {
    val layers = listOf(image(1, 1) { _, _ -> Color.RED })
    for (mask in listOf("", "M0,0", "M0,0 H101 V100 H0 Z", "M-1,0 H100 V100 H0 Z")) {
      rejected { PerfectStaticNightRenderer.render(layers, mask) }
    }
  }

  @Test fun cancellationPropagatesBeforeDecodeDuringRowsMaskingAndEncoding() {
    val layers = listOf(
      image(2, 2) { _, _ -> Color.RED },
      image(2, 2) { _, _ -> Color.argb(128, 0, 0, 255) },
    )
    val mask = "M0,0 H100 V100 H0 Z"
    var totalChecks = 0
    PerfectStaticNightRenderer.render(layers, mask) { totalChecks++ }
    assertTrue(totalChecks > 1024)
    val checkpoints = listOf(1, 6, 7, 32, 512, 700) + (totalChecks - 7..totalChecks)
    for (cancelAt in checkpoints) {
      var calls = 0
      val failure = CancellationException("cancel-$cancelAt")
      try {
        PerfectStaticNightRenderer.render(layers, mask) {
          if (++calls == cancelAt) throw failure
        }
        fail("Cancellation at check $cancelAt must propagate")
      } catch (actual: CancellationException) {
        assertSame(failure, actual)
        assertEquals(cancelAt, calls)
      }
    }
  }

  private fun rendered(
    layers: List<ByteArray>,
    mask: String? = null,
    inspect: (Bitmap, Bitmap) -> Unit,
  ) {
    val pair = PerfectStaticNightRenderer.render(layers, mask)
    val day = requireNotNull(BitmapFactory.decodeByteArray(pair.day, 0, pair.day.size))
    try {
      val night = requireNotNull(BitmapFactory.decodeByteArray(pair.night, 0, pair.night.size))
      try {
        assertEquals(512, day.width)
        assertEquals(512, day.height)
        assertEquals(512, night.width)
        assertEquals(512, night.height)
        inspect(day, night)
      } finally { night.recycle() }
    } finally { day.recycle() }
  }

  private fun image(width: Int, height: Int, pixel: (Int, Int) -> Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
      for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, pixel(x, y))
      return ByteArrayOutputStream().also {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }.toByteArray()
    } finally { bitmap.recycle() }
  }

  private fun assertAlphaEqual(day: Bitmap, night: Bitmap) {
    val light = IntArray(512)
    val dark = IntArray(512)
    for (y in 0 until 512) {
      day.getPixels(light, 0, 512, 0, y, 512, 1)
      night.getPixels(dark, 0, 512, 0, y, 512, 1)
      for (x in 0 until 512) {
        assertEquals("Alpha at $x,$y", Color.alpha(light[x]), Color.alpha(dark[x]))
      }
    }
  }

  private fun dim(value: Int): Int = (value * 214 + 127) / 255
  private fun dim(value: Double): Int = (value * 214 / 255 + 0.5).toInt()

  private fun assertRgb(color: Int, red: Int, green: Int, blue: Int, tolerance: Int = 2) {
    assertEquals(red.toFloat(), Color.red(color).toFloat(), tolerance.toFloat())
    assertEquals(green.toFloat(), Color.green(color).toFloat(), tolerance.toFloat())
    assertEquals(blue.toFloat(), Color.blue(color).toFloat(), tolerance.toFloat())
  }

  private fun rejected(block: () -> Unit) {
    try {
      block()
      fail("Invalid static artwork must be rejected")
    } catch (_: IllegalArgumentException) { }
  }

  private fun solidPng(width: Int, height: Int): ByteArray {
    val output = ByteArrayOutputStream()
    output.write(PNG_SIGNATURE)
    fun chunk(type: String, data: ByteArray) {
      val typeBytes = type.toByteArray(Charsets.US_ASCII)
      val stream = DataOutputStream(output)
      stream.writeInt(data.size)
      stream.write(typeBytes)
      stream.write(data)
      val crc = CRC32().apply { update(typeBytes); update(data) }
      stream.writeInt(crc.value.toInt())
    }
    val header = ByteArrayOutputStream().also {
      DataOutputStream(it).apply {
        writeInt(width); writeInt(height)
        write(byteArrayOf(8, 6, 0, 0, 0))
      }
    }.toByteArray()
    chunk("IHDR", header)
    val compressed = ByteArrayOutputStream()
    val row = ByteArray(1 + width * 4)
    for (x in 0 until width) {
      row[1 + x * 4] = 200.toByte()
      row[2 + x * 4] = 100
      row[3 + x * 4] = 50
      row[4 + x * 4] = 255.toByte()
    }
    DeflaterOutputStream(compressed).use { deflater ->
      repeat(height) { deflater.write(row) }
    }
    chunk("IDAT", compressed.toByteArray())
    chunk("IEND", byteArrayOf())
    return output.toByteArray()
  }

  private companion object {
    val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
  }
}
