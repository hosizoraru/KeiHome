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

  @Test fun uniformFlatPlateGetsDarkBackgroundWithOriginalAlpha() {
    val flat = image(512, 512) { x, y ->
      Color.argb(if (x < 256) 255 else y % 256, 200, 100, 50)
    }
    rendered(listOf(flat)) { day, night ->
      for (y in listOf(0, 256, 511)) {
        assertEquals(Color.rgb(200, 100, 50), day.getPixel(0, y))
        val gray = 56 - (35 * y + 255) / 511
        assertRgb(night.getPixel(0, y), dim(gray), dim(gray), dim(gray), 0)
      }
      assertEquals(128, Color.alpha(night.getPixel(400, 128)))
      val gray = 56 - (35 * 128 + 255) / 511
      assertRgb(night.getPixel(400, 128), dim(gray), dim(gray), dim(gray))
      assertEquals(0, Color.alpha(night.getPixel(400, 0)))
      assertAlphaEqual(day, night)
    }
  }

  @Test fun bakedBlackGlyphAndItsEnclosedCounterStayLegibleOnFlatPlate() {
    val flat = image(512, 512) { x, y ->
      if (x in 160..350 && y in 160..350 && !(x in 220..290 && y in 220..290))
        Color.BLACK else Color.WHITE
    }
    rendered(listOf(flat)) { day, night ->
      assertEquals(Color.BLACK, day.getPixel(180, 256))
      assertEquals(Color.WHITE, day.getPixel(256, 256))
      assertRgb(night.getPixel(180, 256), 214, 214, 214, 0)
      val gray = dim(56 - (35 * 256 + 255) / 511)
      assertRgb(night.getPixel(256, 256), gray, gray, gray, 0)
      assertAlphaEqual(day, night)
    }
  }

  @Test fun coloredBakedMarkAndLowContrastNeutralDetailArePreserved() {
    for (mark in listOf(Color.RED, Color.rgb(220, 220, 220))) {
      val flat = image(512, 512) { x, y ->
        if (x in 180..330 && y in 180..330) mark else Color.WHITE
      }
      rendered(listOf(flat)) { day, night ->
        assertEquals(mark, day.getPixel(256, 256))
        assertRgb(night.getPixel(256, 256),
          dim(Color.red(mark)), dim(Color.green(mark)), dim(Color.blue(mark)), 0)
        assertTrue(Color.red(night.getPixel(0, 0)) < 64)
      }
    }
  }

  @Test fun opaqueBakedForegroundCannotCoverReplacementPlateWithWhite() {
    val foreground = image(512, 512) { x, y ->
      if (x in 180..330 && y in 180..330) Color.RED else Color.WHITE
    }
    rendered(listOf(image(1, 1) { _, _ -> Color.BLUE }, foreground)) { day, night ->
      assertEquals(Color.WHITE, day.getPixel(0, 0))
      assertRgb(night.getPixel(0, 0), dim(56), dim(56), dim(56), 0)
      assertRgb(night.getPixel(256, 256), 214, 0, 0, 0)
    }
  }

  @Test fun neutralDarkForegroundLiftsWithoutRecoloringColoredOrWhiteSprites() {
    for (color in listOf(Color.BLACK, Color.WHITE, Color.RED)) {
      val foreground = image(512, 512) { x, y ->
        if (x in 96..415 && y in 96..415) color else Color.TRANSPARENT
      }
      rendered(listOf(image(1, 1) { _, _ -> Color.WHITE }, foreground)) { day, night ->
        assertEquals(color, day.getPixel(256, 256))
        val expected = if (color == Color.RED) Color.rgb(214, 0, 0) else Color.rgb(214, 214, 214)
        assertEquals(expected, night.getPixel(256, 256))
      }
    }
  }

  @Test fun fullBleedColoredArtRemainsOriginalArtworkWithConservativeDimming() {
    val art = image(512, 512) { x, y ->
      Color.rgb((x * 19 + y * 13) % 256, (x * 7 + y * 31) % 256, (x * 23 + y * 11) % 256)
    }
    for (layers in listOf(listOf(art), listOf(art, image(1, 1) { _, _ -> Color.TRANSPARENT }))) {
      rendered(layers) { day, night ->
        for ((x, y) in listOf(0 to 0, 123 to 80, 250 to 300, 511 to 511)) {
          val original = day.getPixel(x, y)
          assertRgb(night.getPixel(x, y),
            dim(Color.red(original)), dim(Color.green(original)), dim(Color.blue(original)), 0)
        }
        assertAlphaEqual(day, night)
      }
    }
  }

  @Test fun detailedColoredArtAndEnclosedHighlightsSurviveNeutralBorderSegmentation() {
    val art = image(512, 512) { x, y ->
      when {
        x in 250..260 && y in 250..260 -> Color.WHITE
        x in 90..420 && y in 80..430 ->
          Color.rgb((x * 19 + y * 13) % 180, (x * 7 + y * 31) % 180, (x * 23 + y * 11) % 180)
        else -> Color.WHITE
      }
    }
    rendered(listOf(art)) { day, night ->
      assertTrue(Color.red(night.getPixel(0, 0)) < 64)
      assertRgb(night.getPixel(255, 255), 214, 214, 214, 0)
      for ((x, y) in listOf(123 to 100, 200 to 300, 350 to 350)) {
        val original = day.getPixel(x, y)
        assertRgb(night.getPixel(x, y),
          dim(Color.red(original)), dim(Color.green(original)), dim(Color.blue(original)), 0)
      }
    }
  }

  @Test fun translucentShadowDoesNotHidePlateOrBecomeAWhiteHalo() {
    val flat = image(512, 512) { x, y ->
      when {
        x !in 24..487 || y !in 24..487 -> Color.argb(128, 0, 0, 0)
        x in 180..330 && y in 180..330 -> Color.BLACK
        else -> Color.WHITE
      }
    }
    rendered(listOf(flat)) { day, night ->
      assertEquals(Color.argb(128, 0, 0, 0), night.getPixel(0, 0))
      assertRgb(night.getPixel(256, 256), 214, 214, 214, 0)
      assertTrue(Color.red(night.getPixel(100, 100)) < 64)
      assertAlphaEqual(day, night)
    }
  }

  @Test fun blackGlyphIsNotLiftedOntoPreservedBrightArtwork() {
    val background = image(512, 512) { x, y ->
      Color.rgb(180 + (x * 19 + y * 13) % 76, 160 + (x * 7 + y * 31) % 96,
        60 + (x * 23 + y * 11) % 150)
    }
    val foreground = image(512, 512) { x, y ->
      if (x in 180..330 && y in 180..330) Color.BLACK else Color.TRANSPARENT
    }
    rendered(listOf(background, foreground)) { day, night ->
      assertEquals(Color.BLACK, day.getPixel(256, 256))
      assertEquals(Color.BLACK, night.getPixel(256, 256))
    }
  }

  @Test fun opaqueLowKeyArtworkAndSolidBlackForegroundAreNeverInverted() {
    for (detailed in listOf(false, true)) {
      val foreground = image(512, 512) { x, y ->
        when {
          !detailed -> Color.BLACK
          x in 245..265 && y in 245..265 -> Color.WHITE
          else -> Color.rgb(8 + (x + y) % 12, 8 + (x + y) % 12, 8 + (x + y) % 12)
        }
      }
      rendered(listOf(image(1, 1) { _, _ -> Color.WHITE }, foreground)) { _, night ->
        assertTrue(Color.red(night.getPixel(100, 100)) < 64)
        if (detailed) assertRgb(night.getPixel(256, 256), 214, 214, 214, 0)
        else assertEquals(Color.BLACK, night.getPixel(256, 256))
      }
    }
  }

  @Test fun smoothTwoDimensionalBakedPlateDarkensWithoutChangingGlyphOrAlpha() {
    val flat = image(512, 512) { x, y ->
      if (x in 210..300 && y in 210..300) Color.argb(128, 255, 255, 255)
      else Color.rgb(50 + 180 * x / 511, 30 + 100 * y / 511,
        220 - 160 * x / 511 + 25 * y / 511)
    }
    rendered(listOf(flat)) { day, night ->
      for ((x, y) in listOf(0 to 0, 100 to 100, 400 to 100, 100 to 400, 511 to 511))
        assertTrue(Color.red(night.getPixel(x, y)) < 64 &&
          Color.red(night.getPixel(x, y)) == Color.green(night.getPixel(x, y)))
      assertRgb(night.getPixel(256, 256), 214, 214, 214)
      assertAlphaEqual(day, night)
    }
  }

  @Test fun dominantLowerPlateDarkensWhileSmallerHeaderAndColoredDotsSurvive() {
    val flat = image(512, 512) { x, y ->
      when {
        y < 100 -> Color.BLUE
        x in 200..216 && y in 200..216 -> Color.rgb(40, 140, 250)
        else -> {
          val gray = 232 + x / 128 + y / 128
          Color.rgb(gray, gray, gray)
        }
      }
    }
    rendered(listOf(flat)) { day, night ->
      assertEquals(Color.BLUE, day.getPixel(256, 50))
      assertRgb(night.getPixel(256, 50), 0, 0, 214, 0)
      assertTrue(Color.red(night.getPixel(80, 450)) < 64)
      assertRgb(night.getPixel(208, 208), dim(40), dim(140), dim(250), 0)
      assertAlphaEqual(day, night)
    }
  }

  @Test fun smoothGradientEdgesCannotAuthorizeNonmatchingPhotoInteriors() {
    for ((textured, interior) in listOf(
      false to (48..463), true to (48..463), true to (168..343),
    )) {
      val art = image(512, 512) { x, y ->
        if (x in interior && y in interior) {
          if (textured) Color.rgb((x * 19 + y * 13) % 256,
            (x * 7 + y * 31) % 256, (x * 23 + y * 11) % 256)
          else Color.rgb(160 + x / 8, 150 + y / 8, 180)
        } else Color.rgb(x / 2, y / 2, 60 + x / 5)
      }
      rendered(listOf(art)) { day, night ->
        for ((x, y) in listOf(0 to 0, 100 to 100, 256 to 256, 450 to 450, 511 to 511)) {
          val p = day.getPixel(x, y)
          assertRgb(night.getPixel(x, y),
            dim(Color.red(p)), dim(Color.green(p)), dim(Color.blue(p)), 0)
        }
      }
    }
  }

  @Test fun roundedAlphaGradientHasNoRemainingColoredBottomBand() {
    val flat = image(512, 512) { x, y ->
      val dx = maxOf(0, 64 - x, x - 447)
      val dy = maxOf(0, 64 - y, y - 447)
      when {
        dx * dx + dy * dy > 64 * 64 -> Color.TRANSPARENT
        x in 200..310 && y in 200..300 -> Color.WHITE
        else -> Color.rgb(45, 190 - 90 * y / 511, 245 - 70 * y / 511)
      }
    }
    rendered(listOf(flat)) { day, night ->
      for (y in listOf(10, 100, 350, 450, 500)) {
        val p = night.getPixel(256, y)
        assertTrue("Remaining colored gradient at row $y", Color.red(p) == Color.green(p) &&
          Color.green(p) == Color.blue(p) && Color.red(p) < 64)
      }
      assertRgb(night.getPixel(256, 256), 214, 214, 214, 0)
      assertAlphaEqual(day, night)
    }
  }

  @Test fun softNeutralAndBeigeProjectionsDarkenWithoutChangingEnclosedWhiteBrand() {
    for (warm in listOf(false, true)) {
      val flat = image(512, 512) { x, y ->
        when {
          x in 220..280 && y in 200..290 -> Color.WHITE
          x in 150..360 && y in 140..350 -> Color.rgb(255, 135, 0)
          x in 30..80 && y in 150..200 -> Color.rgb(210, 210, 210)
          else -> {
            val distance = ((x - 256) * (x - 256)).toDouble() / (150 * 150) +
              ((y - 365) * (y - 365)).toDouble() / (110 * 110)
            val shade = (80 * maxOf(0.0, 1 - distance)).toInt()
            Color.rgb(255 - shade, 255 - shade + if (warm) shade / 10 else 0,
              255 - shade + if (warm) shade / 4 else 0)
          }
        }
      }
      rendered(listOf(flat)) { day, night ->
        for (y in listOf(365, 390, 420, 450))
          assertTrue("Pale projection survives at row $y", Color.red(night.getPixel(256, y)) < 64)
        assertRgb(night.getPixel(256, 256), 214, 214, 214, 0)
        assertRgb(night.getPixel(180, 200), 214, dim(135), 0, 0)
        assertRgb(night.getPixel(50, 170), dim(210), dim(210), dim(210), 0)
        assertAlphaEqual(day, night)
      }
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
    assertEquals("perfect-static-night-7", PerfectStaticNightRenderer.REVISION)
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

  @Test fun nonsquareMulticolorArtworkIsScaledAndPreservedAcrossBothAxes() {
    val background = image(2, 8) { x, _ -> if (x == 0) Color.RED else Color.GREEN }
    val foreground = image(8, 2) { _, y -> if (y == 0) Color.BLUE else Color.TRANSPARENT }
    rendered(listOf(background, foreground)) { day, night ->
      assertEquals(Color.BLUE, day.getPixel(0, 0))
      assertEquals(Color.BLUE, day.getPixel(511, 0))
      assertEquals(Color.RED, day.getPixel(0, 511))
      assertEquals(Color.GREEN, day.getPixel(511, 511))
      assertRgb(night.getPixel(0, 0), 0, 0, 214, 0)
      assertRgb(night.getPixel(511, 511), 0, 214, 0, 0)
    }
  }

  @Test fun maximumRasterAndExtremeAspectRatiosUseBoundedSampledDecode() {
    // Stream PNG rows so this test never allocates a full 8192-square fixture bitmap.
    for ((width, height) in listOf(8192 to 8192, 8192 to 1, 1 to 8192)) {
      rendered(listOf(solidPng(width, height))) { day, night ->
        assertEquals(Color.rgb(200, 100, 50), day.getPixel(0, 0))
        assertEquals(Color.rgb(200, 100, 50), day.getPixel(511, 511))
        val gray = dim(56 - (35 * 256 + 255) / 511)
        assertRgb(night.getPixel(256, 256), gray, gray, gray, 0)
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

  @Test fun exactlyEquivalentCenteredLayersKeepDayBytesAndDimShadowThroughForegroundAlpha() {
    val (flat, layers) = equivalentFixture()
    val original = PerfectStaticNightRenderer.render(listOf(flat), null)
    val proved = PerfectStaticNightRenderer.render(listOf(flat), null, equivalentLayers = layers)
    assertArrayEquals(original.day, proved.day)
    val oldNight = requireNotNull(BitmapFactory.decodeByteArray(original.night, 0, original.night.size))
    val night = requireNotNull(BitmapFactory.decodeByteArray(proved.night, 0, proved.night.size))
    val day = requireNotNull(BitmapFactory.decodeByteArray(proved.day, 0, proved.day.size))
    try {
      assertTrue(Color.red(oldNight.getPixel(298, 384)) > 160)
      assertTrue(Color.red(night.getPixel(298, 384)) < 128)
      assertRgb(night.getPixel(240, 210), 214, 214, 214, 0)
      assertAlphaEqual(day, night)
    } finally { oldNight.recycle(); night.recycle(); day.recycle() }
  }

  @Test fun retainedSameThemeLayerFixturesPassProofWithoutChangingFlatDays() {
    val directory = androidx.test.platform.app.InstrumentationRegistry.getArguments()
      .getString("perfectEquivalentLayerFixtures")
    org.junit.Assume.assumeTrue("Optional retained source fixture directory", !directory.isNullOrBlank())
    val root = java.io.File(requireNotNull(directory))
    for (name in listOf("com.android.browser", "com.miui.gallery",
      "com.android.fileexplorer", "com.miui.calculator")) {
      val bytes = (0..2).map { index ->
        val file = java.io.File(root, "$name-$index-source.png")
        assertTrue(file.length() in 1..8L * 1024 * 1024)
        file.readBytes()
      }
      val baseline = PerfectStaticNightRenderer.render(listOf(bytes[0]), null)
      val proved = PerfectStaticNightRenderer.render(listOf(bytes[0]), null,
        equivalentLayers = bytes.drop(1))
      assertArrayEquals("$name day changed", baseline.day, proved.day)
      assertFalse("$name did not use its proved separated rendering",
        baseline.night.contentEquals(proved.night))
      val day = requireNotNull(BitmapFactory.decodeByteArray(proved.day, 0, proved.day.size))
      val night = requireNotNull(BitmapFactory.decodeByteArray(proved.night, 0, proved.night.size))
      try { assertAlphaEqual(day, night) } finally { day.recycle(); night.recycle() }
    }
  }

  @Test fun proofRejectsDifferentArtAndEvenOneUnsampledPixelBeyondRoundingTolerance() {
    val (flat, layers) = equivalentFixture()
    val baseline = PerfectStaticNightRenderer.render(listOf(flat), null)
    for (changeStyle in listOf(false, true)) {
      val decoded = requireNotNull(BitmapFactory.decodeByteArray(layers[1], 0, layers[1].size))
      val changed = requireNotNull(decoded.copy(Bitmap.Config.ARGB_8888, true))
      val bytes = try {
        if (changeStyle) changed.setPixel(35, 35, Color.BLUE)
        else changed.setPixel(13, 13, Color.rgb(252, 255, 255))
        ByteArrayOutputStream().also { assertTrue(changed.compress(Bitmap.CompressFormat.PNG, 100, it)) }
          .toByteArray()
      } finally { decoded.recycle(); changed.recycle() }
      val result = PerfectStaticNightRenderer.render(listOf(flat), null,
        equivalentLayers = listOf(layers[0], bytes))
      assertArrayEquals(baseline.day, result.day)
      assertArrayEquals(baseline.night, result.night)
    }
  }

  @Test fun proofRejectsAlphaMismatchMalformedLayersAndUnmatchedDimensions() {
    val (flat, layers) = equivalentFixture(backgroundAlpha = 200)
    val baseline = PerfectStaticNightRenderer.render(listOf(flat), null)
    val decoded = requireNotNull(BitmapFactory.decodeByteArray(layers[0], 0, layers[0].size))
    val changed = requireNotNull(decoded.copy(Bitmap.Config.ARGB_8888, true))
    val alphaMismatch = try {
      changed.setPixel(13, 13, Color.argb(201, 255, 255, 255))
      ByteArrayOutputStream().also { assertTrue(changed.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        .toByteArray()
    } finally { decoded.recycle(); changed.recycle() }
    for (candidate in listOf(
      listOf(alphaMismatch, layers[1]),
      listOf(byteArrayOf(1, 2, 3), layers[1]),
      listOf(layers[0], image(73, 72) { _, _ -> Color.TRANSPARENT }),
    )) {
      val result = PerfectStaticNightRenderer.render(listOf(flat), null, equivalentLayers = candidate)
      assertArrayEquals(baseline.day, result.day)
      assertArrayEquals(baseline.night, result.night)
    }
  }

  @Test fun provedLayerRenderingRetainsOriginalFlatCoverageAndPropagatesCancellation() {
    val (flat, layers) = equivalentFixture(backgroundAlpha = 200)
    val result = PerfectStaticNightRenderer.render(listOf(flat), null, equivalentLayers = layers)
    val day = requireNotNull(BitmapFactory.decodeByteArray(result.day, 0, result.day.size))
    val night = requireNotNull(BitmapFactory.decodeByteArray(result.night, 0, result.night.size))
    try { assertAlphaEqual(day, night) } finally { day.recycle(); night.recycle() }
    var proofStarted = false
    val observedLayers = object : AbstractList<ByteArray>() {
      override val size: Int get() = layers.size
      override fun get(index: Int): ByteArray {
        proofStarted = true
        return layers[index]
      }
    }
    try {
      PerfectStaticNightRenderer.render(listOf(flat), null, equivalentLayers = observedLayers) {
        if (proofStarted) throw CancellationException("cancel proof")
      }
      fail("Expected cancellation")
    } catch (expected: CancellationException) {
      assertEquals("cancel proof", expected.message)
    }
  }

  private fun equivalentFixture(backgroundAlpha: Int = 255): Pair<ByteArray, List<ByteArray>> {
    val background = image(72, 72) { _, _ -> Color.argb(backgroundAlpha, 255, 255, 255) }
    val foreground = image(72, 72) { x, y ->
      when {
        x in 32..38 && y in 28..36 -> Color.WHITE
        x in 20..49 && y in 20..43 -> Color.rgb(255, 140, 0)
        x in 32..48 && y in 44..52 -> Color.argb(128, 210, 180, 140)
        else -> Color.TRANSPARENT
      }
    }
    val bg = requireNotNull(BitmapFactory.decodeByteArray(background, 0, background.size))
    val fg = requireNotNull(BitmapFactory.decodeByteArray(foreground, 0, foreground.size))
    val native = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
    try {
      android.graphics.Canvas(native).apply {
        drawBitmap(bg, 0f, 0f, null)
        drawBitmap(fg, 0f, 0f, null)
      }
      val flat = Bitmap.createBitmap(native, 12, 12, 48, 48)
      try {
        return ByteArrayOutputStream().also {
          assertTrue(flat.compress(Bitmap.CompressFormat.PNG, 100, it))
        }.toByteArray() to listOf(background, foreground)
      } finally { flat.recycle() }
    } finally { native.recycle(); bg.recycle(); fg.recycle() }
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
