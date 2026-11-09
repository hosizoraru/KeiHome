package os.kei.hyper.home.iconpack

import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTwoToneMaskTest {
  // Actual Settings iconBg_0/iconBg_1 endpoint palettes; geometry below is a synthetic fixture.
  private val dayBackground = 0xffd6f8
  private val dayForeground = 0x643662
  private val nightBackground = 0x643662
  private val nightForeground = 0xefb4e8

  @Test fun derivesWhiteMasksFromBothPalettesIncludingAntialiasAndSourceAlpha() {
    val pair = fixture()
    val dayBefore = pair.day.copyOf()
    val nightBefore = pair.night.copyOf()
    val result = requireNotNull(derive(pair))
    for (i in pair.day.indices) {
      val sourceAlpha = pair.day[i] ushr 24
      assertEquals(sourceAlpha, result.backgroundPixels[i] ushr 24)
      assertEquals(0xffffff, result.backgroundPixels[i] and 0xffffff)
      assertEquals(0xffffff, result.foregroundPixels[i] and 0xffffff)
      val expected = (pair.coverage[i] * sourceAlpha + 127) / 255
      assertTrue(abs(expected - (result.foregroundPixels[i] ushr 24)) <= 1)
    }
    assertArrayEquals(dayBefore, pair.day)
    assertArrayEquals(nightBefore, pair.night)
    assertTrue(result.foregroundPixels.any { it ushr 24 == 0 })
    assertTrue(result.foregroundPixels.any { it ushr 24 == 255 })
    assertTrue(result.backgroundPixels.any { it ushr 24 == 0 })
    assertTrue(result.backgroundPixels.any { it ushr 24 == 255 })
    result.backgroundPixels.fill(0)
    assertArrayEquals(dayBefore, pair.day)
    assertNotNull(derive(pair))
  }

  @Test fun acceptsBothSizeBoundsAndRectangularImages() {
    for ((width, height) in listOf(32 to 32, 32 to 64, 320 to 320, 512 to 512)) {
      assertNotNull("$width x $height", derive(fixture(width, height)))
    }
  }

  @Test fun acceptsRoundedShapeTouchingAllFourCanvasEdges() {
    val pair = fixture()
    for (y in 0..63) for (x in 0..63) {
      val inset = maxOf(0, 8 - minOf(y, 63 - y))
      if (x in inset until 64 - inset && pair.day[index(x, y)] ushr 24 == 0) {
        val alpha = if (x == inset || x == 63 - inset || y == 0 || y == 63) 128 else 255
        pair.day[index(x, y)] = argb(dayBackground, alpha)
        pair.night[index(x, y)] = argb(nightBackground, alpha)
      }
    }
    assertNotNull(derive(pair))
  }

  @Test fun rejectsInvalidDimensionsAndEitherArrayLengthWithoutOverflow() {
    val pair = fixture()
    for ((width, height) in listOf(0 to 64, -32 to 64, 31 to 64, 64 to 513,
      513 to 64, 64 to 31, Int.MAX_VALUE to Int.MAX_VALUE, Int.MIN_VALUE to 64)) {
      assertNull(NativeTwoToneMask.derive(width, height, pair.day, pair.night))
    }
    assertNull(NativeTwoToneMask.derive(64, 64, pair.day.copyOf(4095), pair.night))
    assertNull(NativeTwoToneMask.derive(64, 64, pair.day, pair.night.copyOf(4097)))
    assertNull(NativeTwoToneMask.derive(32, 128, pair.day.copyOf(4095), pair.night))
  }

  @Test fun ignoresHiddenRgbButValidatesEvenAlphaOneVisiblePixels() {
    val pair = fixture()
    pair.day[0] = 0x123456
    pair.night[0] = 0xabcdef
    assertNotNull(derive(pair))
    pair.night[0] = 0x0100ff00
    assertNull(derive(pair))
  }

  @Test fun acceptsOneAlphaUnitAndUsesExactDayAlpha() {
    val pair = fixture()
    val edge = index(8, 24)
    pair.day[edge] = argb(dayBackground, 128)
    pair.night[edge] = argb(nightBackground, 129)
    pair.night[0] = argb(nightBackground, 1)
    val result = requireNotNull(derive(pair))
    assertEquals(128, result.backgroundPixels[edge] ushr 24)
    assertEquals(0, result.foregroundPixels[edge] ushr 24)
    assertEquals(0, result.backgroundPixels[0] ushr 24)
    pair.night[edge] = argb(nightBackground, 127)
    assertNotNull(derive(pair))
    pair.night[edge] = argb(nightBackground, 130)
    assertNull(derive(pair))
  }

  @Test fun rejectsForegroundOverlapOnPartiallyTransparentSource() {
    for (alpha in listOf(1, 128, 254)) for (coverage in listOf(1, 128, 255)) {
      val pair = fixture()
      val i = index(24, 28)
      pair.day[i] = blend(dayBackground, dayForeground, coverage, alpha)
      pair.night[i] = blend(nightBackground, nightForeground, coverage, alpha)
      assertNull("alpha=$alpha coverage=$coverage", derive(pair))
    }
  }

  @Test fun acceptsTranslucentBackgroundOutlineWithoutIncreasingCompositeAlpha() {
    for (alpha in listOf(1, 128, 254)) {
      val pair = fixture()
      for (y in 8..55) for (x in 8..55) {
        if (x == 8 || x == 55 || y == 8 || y == 55) {
          pair.day[index(x, y)] = argb(dayBackground, alpha)
          pair.night[index(x, y)] = argb(nightBackground, alpha)
        }
      }
      val result = requireNotNull(derive(pair))
      for (i in pair.day.indices) {
        val foregroundAlpha = result.foregroundPixels[i] ushr 24
        val backgroundAlpha = result.backgroundPixels[i] ushr 24
        val sourceAlpha = pair.day[i] ushr 24
        assertEquals(sourceAlpha, backgroundAlpha)
        if (sourceAlpha in 1..254) assertEquals(0, foregroundAlpha)
        val compositeAlpha = foregroundAlpha +
          (backgroundAlpha * (255 - foregroundAlpha) + 127) / 255
        assertEquals(sourceAlpha, compositeAlpha)
      }
    }
  }

  @Test fun keepsOpaqueAntialiasedForegroundCoverage() {
    val pair = fixture()
    val i = index(24, 28)
    pair.day[i] = blend(dayBackground, dayForeground, 128)
    pair.night[i] = blend(nightBackground, nightForeground, 128)
    val result = requireNotNull(derive(pair))
    assertEquals(255, result.backgroundPixels[i] ushr 24)
    assertEquals(128, result.foregroundPixels[i] ushr 24)
  }

  @Test fun rejectsPartialAlphaInEitherVariantDespiteOneUnitAlphaTolerance() {
    for ((dayAlpha, nightAlpha) in listOf(254 to 255, 255 to 254)) {
      val pair = fixture()
      val i = index(24, 28)
      pair.day[i] = blend(dayBackground, dayForeground, 128, dayAlpha)
      pair.night[i] = blend(nightBackground, nightForeground, 128, nightAlpha)
      assertNull(derive(pair))
    }
  }

  @Test fun rejectsEvenOneVariantWithTinyForegroundCoverageOnTranslucentBackground() {
    for (night in listOf(false, true)) {
      val pair = fixture()
      val i = index(24, 28)
      pair.day[i] = blend(dayBackground, dayForeground, if (night) 0 else 1, 128)
      pair.night[i] = blend(nightBackground, nightForeground, if (night) 1 else 0, 128)
      assertNull(derive(pair))
    }
  }

  @Test fun acceptsAtMostTwoChannelResidualAndRejectsThree() {
    val pair = fixture()
    val i = index(24, 28)
    pair.day[i] = argb(dayBackground - 0x020000)
    pair.night[i] = argb(nightBackground)
    assertNotNull(derive(pair))
    pair.day[i] = argb(dayBackground - 0x030000)
    assertNull(derive(pair))
  }

  @Test fun acceptsRoundingCoverageDifferenceButRejectsDifferentArtwork() {
    val pair = fixture()
    val i = index(24, 28)
    pair.day[i] = blend(dayBackground, dayForeground, 128)
    pair.night[i] = blend(nightBackground, nightForeground, 129)
    assertNotNull(derive(pair))
    pair.night[i] = blend(nightBackground, nightForeground, 132)
    assertNull(derive(pair))
  }

  @Test fun rejectsMovedGlyphAndChecksEveryPixelIncludingLast() {
    val pair = fixture()
    pair.night[index(28, 28)] = argb(nightBackground)
    assertNull(derive(pair))
    val last = fixture()
    last.day[last.day.lastIndex] = argb(dayBackground)
    assertNull(derive(last))
  }

  @Test fun rejectsOneMulticolorPixelInEitherVariantIncludingSemitransparentPixels() {
    for (night in listOf(false, true)) {
      for (alpha in listOf(1, 128, 255)) {
        val pair = fixture()
        val i = index(24, 28)
        pair.day[i] = blend(dayBackground, dayForeground, 128, alpha)
        pair.night[i] = blend(nightBackground, nightForeground, 128, alpha)
        (if (night) pair.night else pair.day)[i] = argb(0x00ff00, alpha)
        assertNull(derive(pair))
      }
    }
  }

  @Test fun rejectsIdenticalVariantsButAllowsOneEndpointToRemainUnchanged() {
    val pair = fixture()
    assertNull(NativeTwoToneMask.derive(64, 64, pair.day, pair.day.copyOf()))
    for ((bg, fg) in listOf(dayBackground to 0x102010, 0xffffff to dayForeground)) {
      assertNotNull(derive(fixture(nightBg = bg, nightFg = fg)))
    }
  }

  @Test fun rejectsLowContrastInEitherVariantAndSingleColorArt() {
    assertNull(derive(fixture(dayFg = 0xf0c0e0)))
    assertNull(derive(fixture(nightFg = 0x704070)))
    assertNull(derive(fixture(dayFg = dayBackground)))
    assertNull(derive(fixture(nightFg = nightBackground)))
  }

  @Test fun rejectsTransparentOpaqueAndSemitransparentOnlySources() {
    for (alpha in listOf(0, 128, 255)) {
      val pair = fixture()
      for (i in pair.day.indices) {
        pair.day[i] = argb(pair.day[i], alpha)
        pair.night[i] = argb(pair.night[i], alpha)
      }
      assertNull(derive(pair))
    }
  }

  @Test fun rejectsInsignificantEndpointAndTiedBackground() {
    val tiny = fixture()
    for (i in tiny.day.indices) if (tiny.coverage[i] > 0) {
      tiny.day[i] = argb(dayBackground, tiny.day[i] ushr 24)
      tiny.night[i] = argb(nightBackground, tiny.night[i] ushr 24)
    }
    tiny.day[index(28, 28)] = argb(dayForeground)
    tiny.night[index(28, 28)] = argb(nightForeground)
    assertNull(derive(tiny))
    val tied = fixture()
    for (y in 8..55) for (x in 8..55) {
      tied.day[index(x, y)] = argb(if (x < 32) dayBackground else dayForeground)
      tied.night[index(x, y)] = argb(if (x < 32) nightBackground else nightForeground)
    }
    assertNull(derive(tied))
  }

  @Test fun rejectsTinyOccupiedRegionEvenWithBothSignificantColors() {
    val pair = fixture()
    for (y in 0..63) for (x in 0..63) if (x !in 20..43 || y !in 20..43) {
      pair.day[index(x, y)] = 0
      pair.night[index(x, y)] = 0
    }
    assertNull(derive(pair))
  }

  @Test fun rejectsSplitColoredArtAtHorizontalAndVerticalShapeBorders() {
    for ((x, y) in listOf(8 to 28, 28 to 8)) {
      val pair = fixture()
      pair.day[index(x, y)] = argb(dayForeground)
      pair.night[index(x, y)] = argb(nightForeground)
      assertNull(derive(pair))
    }
  }

  @Test fun rejectsHolesDisconnectedShapesAndOccupiedCanvasCorners() {
    val hole = fixture()
    hole.day[index(16, 16)] = 0
    hole.night[index(16, 16)] = 0
    assertNull(derive(hole))
    val disconnected = fixture()
    disconnected.day[index(4, 28)] = argb(dayBackground)
    disconnected.night[index(4, 28)] = argb(nightBackground)
    assertNull(derive(disconnected))
    val corner = fixture()
    corner.day[0] = argb(dayBackground)
    corner.night[0] = argb(nightBackground)
    assertNull(derive(corner))
  }

  @Test fun cancellationPropagatesAtEntryAndDuringHistogramPixelAndShapePasses() {
    val pair = fixture(512, 512)
    var calls = 0
    assertNotNull(NativeTwoToneMask.derive(512, 512, pair.day, pair.night) { calls++ })
    // Entry, histogram, mask derivation, row scan, column scan, and final return check.
    for (cancelAt in listOf(1, 2, 500, 1500, 2500, 3500, calls)) {
      val cancellation = CancellationException("mask proof cancelled")
      var seen = 0
      val thrown = assertThrows(CancellationException::class.java) {
        NativeTwoToneMask.derive(512, 512, pair.day, pair.night) {
          if (++seen == cancelAt) throw cancellation
        }
      }
      assertSame(cancellation, thrown)
    }
    assertTrue(calls >= 4 * (512 * 512 / 256))
  }

  private data class PairPixels(
    val width: Int, val height: Int, val day: IntArray, val night: IntArray, val coverage: IntArray,
  )

  private fun fixture(
    width: Int = 64,
    height: Int = 64,
    dayFg: Int = dayForeground,
    nightBg: Int = nightBackground,
    nightFg: Int = nightForeground,
  ): PairPixels {
    val day = IntArray(width * height)
    val night = IntArray(width * height)
    val coverages = IntArray(width * height)
    val left = width / 8
    val right = width - left - 1
    val top = height / 8
    val bottom = height - top - 1
    for (y in top..bottom) for (x in left..right) {
      val i = y * width + x
      val glyph = x in width * 3 / 8 until width * 5 / 8 &&
        y in height * 3 / 8 until height * 5 / 8
      val coverage = if (!glyph) 0 else if (x == width * 3 / 8) (y * 37) % 254 + 1 else 255
      val alpha = if (x == left || x == right || y == top || y == bottom) 128 else 255
      coverages[i] = coverage
      day[i] = blend(dayBackground, dayFg, coverage, alpha)
      night[i] = blend(nightBg, nightFg, coverage, alpha)
    }
    return PairPixels(width, height, day, night, coverages)
  }

  private fun derive(pair: PairPixels): NativeTwoToneMask.Result? =
    NativeTwoToneMask.derive(pair.width, pair.height, pair.day, pair.night)

  private fun index(x: Int, y: Int): Int = y * 64 + x

  private fun argb(rgb: Int, alpha: Int = 255): Int = (alpha shl 24) or (rgb and 0xffffff)

  private fun blend(bg: Int, fg: Int, coverage: Int, alpha: Int = 255): Int {
    var rgb = 0
    for (shift in intArrayOf(16, 8, 0)) {
      val channel = (((bg ushr shift) and 255) * (255 - coverage) +
        ((fg ushr shift) and 255) * coverage + 127) / 255
      rgb = rgb or (channel shl shift)
    }
    return argb(rgb, alpha)
  }
}
