package os.kei.hyper.home.data

import com.materialkolor.hct.Hct
import com.materialkolor.utils.ColorUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.xposed.MonetColorPolicy

class MonetSchemesTest {
  private val families = listOf("primary", "secondary", "tertiary", "neutral", "neutral-variant")

  @Test
  fun generatesAllFiveOpaqueTonalPalettesWithOrderedLightness() {
    for (style in MonetSchemeStyle.entries) {
      val palette = MonetSchemes.generate(0xff6750a4.toInt(), style)
      assertEquals(505, palette.size)
      for (family in families) {
        assertEquals(0xff000000.toInt(), palette.getValue("${family}0"))
        assertEquals(0xffffffff.toInt(), palette.getValue("${family}100"))
        var previous = -1.0
        for (tone in 0..100) {
          val color = palette.getValue("$family$tone")
          assertEquals(255, color ushr 24)
          val lightness = ColorUtils.lstarFromArgb(color)
          assertEquals("$style $family$tone", tone.toDouble(), lightness, 0.5)
          assertTrue("$style $family$tone", lightness >= previous)
          previous = lightness
        }
      }
    }
  }

  @Test
  fun existingPolicyAcceptsBothAospIconRolesAcrossStylesAndSeeds() {
    val seeds = listOf(0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffff00, 0xff6750a4, 0xff000000, 0xffffffff)
    for (style in MonetSchemeStyle.entries) for (seed in seeds) {
      val palette = MonetSchemes.generate(seed.toInt(), style)
      // Android UI_MODE_NIGHT_NO/YES; the calculation itself needs no Android runtime.
      val light = MonetColorPolicy.selectPair(palette, 0x10)
      val dark = MonetColorPolicy.selectPair(palette, 0x20)
      assertNotNull("$style light", light)
      assertNotNull("$style dark", dark)
      assertEquals(palette.getValue("primary90"), light!!.background)
      assertEquals(palette.getValue("primary30"), light.foreground)
      assertEquals(palette.getValue("secondary20"), dark!!.background)
      assertEquals(palette.getValue("primary80"), dark.foreground)
      for ((isDark, policy) in listOf(false to light, true to dark)) {
        val preview = MonetSchemes.iconRoles(palette, isDark)!!
        assertEquals(policy.foreground, preview.foreground)
        assertEquals(policy.background, preview.background)
        assertTrue("$style icon contrast", preview.contrastRatio in 4.5..21.0)
      }
    }
  }

  @Test
  fun vibrantTonalSpotAndNeutralHaveDifferentChroma() {
    fun chroma(style: MonetSchemeStyle) = Hct.fromInt(
      MonetSchemes.generate(0xff6750a4.toInt(), style).getValue("primary50"),
    ).chroma
    val vibrant = chroma(MonetSchemeStyle.VIBRANT)
    val tonal = chroma(MonetSchemeStyle.TONAL_SPOT)
    val neutral = chroma(MonetSchemeStyle.NEUTRAL)
    assertTrue(vibrant > tonal + 10)
    assertTrue(tonal > neutral + 10)
  }

  @Test
  fun monochromeIsAchromaticAndIndependentOfSeed() {
    val first = MonetSchemes.generate(0xffff0000.toInt(), MonetSchemeStyle.MONOCHROME)
    assertEquals(first, MonetSchemes.generate(0xff0000ff.toInt(), MonetSchemeStyle.MONOCHROME))
    for (color in first.values) {
      assertEquals(color ushr 16 and 255, color ushr 8 and 255)
      assertEquals(color ushr 8 and 255, color and 255)
    }
  }

  @Test
  fun expressiveAndFruitSaladRotateTheIntendedHues() {
    val seed = 0xff6750a4.toInt()
    val seedHue = Hct.fromInt(seed).hue
    fun distance(a: Double, b: Double): Double {
      val difference = kotlin.math.abs(a - b) % 360.0
      return minOf(difference, 360.0 - difference)
    }
    val expressive = MonetSchemes.generate(seed, MonetSchemeStyle.EXPRESSIVE)
    assertTrue(distance(seedHue, Hct.fromInt(expressive.getValue("primary50")).hue) > 90.0)
    val fruitSalad = MonetSchemes.generate(seed, MonetSchemeStyle.FRUIT_SALAD)
    val shiftedHue = (seedHue + 310.0) % 360.0
    for (family in listOf("primary", "secondary")) {
      assertTrue(distance(shiftedHue, Hct.fromInt(fruitSalad.getValue("${family}50")).hue) < 3.0)
    }
    assertTrue(distance(seedHue, Hct.fromInt(fruitSalad.getValue("tertiary50")).hue) < 3.0)
  }

  @Test
  fun rainbowHasChromaticAccentsButAchromaticNeutralPalettes() {
    val palette = MonetSchemes.generate(0xff6750a4.toInt(), MonetSchemeStyle.RAINBOW)
    assertTrue(Hct.fromInt(palette.getValue("primary50")).chroma > 40.0)
    for (family in listOf("neutral", "neutral-variant")) for (tone in 0..100) {
      val color = palette.getValue("$family$tone")
      assertEquals(color ushr 16 and 255, color ushr 8 and 255)
      assertEquals(color ushr 8 and 255, color and 255)
    }
  }

  @Test
  fun contrastMeasuresActualSrgbAndIsSymmetric() {
    val black = 0xff000000.toInt()
    val white = 0xffffffff.toInt()
    val red = 0xffff0000.toInt()
    assertEquals(21.0, MonetSchemes.opaqueContrastRatio(black, white)!!, 0.00001)
    assertEquals(1.0, MonetSchemes.opaqueContrastRatio(red, red)!!, 0.00001)
    assertEquals(3.99848, MonetSchemes.opaqueContrastRatio(red, white)!!, 0.00001)
    assertEquals(MonetSchemes.opaqueContrastRatio(red, white), MonetSchemes.opaqueContrastRatio(white, red))
    // A preview reports even low contrast honestly; it is not the runtime acceptance policy.
    val lowContrast = MonetSchemes.iconRoles(mapOf("primary30" to red, "primary90" to red), false)!!
    assertEquals(1.0, lowContrast.contrastRatio, 0.00001)
  }

  @Test
  fun incompleteOrTransparentRolesDoNotProduceAContrastClaim() {
    assertNull(MonetSchemes.opaqueContrastRatio(0x00ff0000, 0xffffffff.toInt()))
    assertNull(MonetSchemes.opaqueContrastRatio(0xff000000.toInt(), 0x80ffffff.toInt()))
    val palette = MonetSchemes.generate(0xff6750a4.toInt(), MonetSchemeStyle.EXPRESSIVE)
    for ((dark, key) in listOf(false to "primary30", false to "primary90", true to "primary80", true to "secondary20")) {
      assertNull(MonetSchemes.iconRoles(palette - key, dark))
      assertNull(MonetSchemes.iconRoles(palette + (key to 0x80777777.toInt()), dark))
    }
  }

  @Test
  fun sameSeedAndStyleAreRepeatableAndSeedAlphaDoesNotChangeTones() {
    for (style in MonetSchemeStyle.entries) {
      val first = MonetSchemes.generate(0xff42856a.toInt(), style)
      assertEquals(first, MonetSchemes.generate(0xff42856a.toInt(), style))
      assertEquals(first, MonetSchemes.generate(0x0042856a, style))
    }
  }

  @Test
  fun ranksChromaticSeedsInsteadOfTheLargestGrayArea() {
    val pixels = IntArray(100) {
      when {
        it < 80 -> 0xff777777.toInt()
        it < 90 -> 0xffff0000.toInt()
        else -> 0xff0000ff.toInt()
      }
    }
    assertEquals(listOf(0xffff0000.toInt(), 0xff0000ff.toInt()), MonetSchemes.wallpaperSeeds(pixels))
  }

  @Test
  fun grayWallpaperFallsBackToItsDominantColor() {
    val pixels = IntArray(100) { if (it < 80) 0xff777777.toInt() else 0xff222222.toInt() }
    assertEquals(listOf(0xff777777.toInt()), MonetSchemes.wallpaperSeeds(pixels))
    for (color in listOf(0xff000000.toInt(), 0xffffffff.toInt())) {
      assertEquals(listOf(color), MonetSchemes.wallpaperSeeds(IntArray(16) { color }))
    }
  }

  @Test
  fun emptyOrTransparentSamplesDoNotInventWallpaperColors() {
    assertTrue(MonetSchemes.wallpaperSeeds(intArrayOf()).isEmpty())
    assertTrue(MonetSchemes.wallpaperSeeds(intArrayOf(0, 0x00ff0000, 0x80777777.toInt())).isEmpty())
    assertEquals(listOf(0xffff0000.toInt()), MonetSchemes.wallpaperSeeds(
      intArrayOf(0, 0x00ff0000, 0xffff0000.toInt()),
    ))
  }

  @Test
  fun wallpaperSeedsAreBoundedRepeatableAndLeaveInputIntact() {
    val colors = longArrayOf(0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffff00, 0xff00ffff, 0xffff00ff)
    val pixels = IntArray(600) { colors[it % colors.size].toInt() }
    val original = pixels.copyOf()
    val seeds = MonetSchemes.wallpaperSeeds(pixels)
    assertTrue(seeds.size in 1..4)
    assertEquals(seeds.size, seeds.distinct().size)
    assertTrue(seeds.all { it ushr 24 == 255 })
    assertEquals(seeds, MonetSchemes.wallpaperSeeds(pixels))
    assertArrayEquals(original, pixels)
  }
}
