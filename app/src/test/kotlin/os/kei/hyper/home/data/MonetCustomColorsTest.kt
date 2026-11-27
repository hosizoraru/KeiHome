package os.kei.hyper.home.data

import com.materialkolor.utils.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.xposed.MonetColorPolicy

class MonetCustomColorsTest {
  private val seed = 0xff6750a4.toInt()

  @Test fun extendedChoiceRoundTripsWithoutLosingRequestedChroma() {
    val choice = MonetCustomization(color = 0xff112233.toInt(),
      paletteOverrides = MonetPaletteFamily.entries.associateWith {
        MonetTonalSpec(it.ordinal * 55.5, 200.0 - it.ordinal * 40)
      }, backgroundFamily = MonetPaletteFamily.NEUTRAL,
      iconOverrides = mapOf(MonetIconRole.DARK_FOREGROUND to 0xffaaffcc.toInt()))
    assertEquals(choice, MonetCustomization.decode(choice.encode()))
    assertEquals(choice.encode(), MonetCustomization.decode(choice.encode())!!.encode())
    assertEquals(200.0, MonetCustomization.decode(choice.encode())!!
      .paletteOverrides.getValue(MonetPaletteFamily.PRIMARY).chroma, 0.0)
  }

  @Test fun oldChoicesAndClearedOverridesKeepTheOriginalFormat() {
    val raw = "HOME_WALLPAPER:TONAL_SPOT:0:-1611228"
    val original = MonetCustomization.decode(raw)!!
    assertEquals(raw, original.encode())
    assertFalse(original.hasCustomColors)
    val edited = original.copy(paletteOverrides = mapOf(MonetPaletteFamily.NEUTRAL to MonetTonalSpec(0.0, 0.0)))
    assertEquals(raw, edited.copy(paletteOverrides = emptyMap()).encode())
  }

  @Test fun rejectsNonFinitePaletteOrTranslucentIconColors() {
    val base = "HOME_WALLPAPER:TONAL_SPOT:0:-1611228:v2:"
    for (parameters in listOf("NaN,40", "120,Infinity", "-1,40", "120,-1", "120,201")) {
      assertNull(MonetCustomization.decode("${base}PRIMARY=$parameters:false:"))
    }
    assertNull(MonetCustomization.decode("${base}:false:LIGHT_FOREGROUND=16777215"))
    assertNull(MonetCustomization.decode("${base}UNKNOWN=120,40:false:"))
  }

  @Test fun eachCustomFamilyOnlyReplacesItsOwnToneRamp() {
    val stock = MonetSchemes.generate(seed, MonetSchemeStyle.TONAL_SPOT)
    for (family in MonetPaletteFamily.entries) {
      val palette = MonetSchemes.generate(seed, MonetSchemeStyle.TONAL_SPOT,
        mapOf(family to MonetTonalSpec(135.0, 50.0)))
      assertNotEquals(stock["${family.key}50"], palette["${family.key}50"])
      for (other in MonetPaletteFamily.entries) for (tone in 0..100) {
        val key = "${other.key}$tone"
        if (other != family) assertEquals(stock[key], palette[key])
        assertEquals(tone.toDouble(), ColorUtils.lstarFromArgb(palette.getValue(key)), 0.5)
      }
    }
  }

  @Test fun neutralBackgroundAndExplicitRolesReachBothPreviewAndRuntime() {
    val choice = MonetCustomization(backgroundFamily = MonetPaletteFamily.NEUTRAL,
      paletteOverrides = mapOf(MonetPaletteFamily.NEUTRAL to MonetTonalSpec(0.0, 0.0)),
      iconOverrides = mapOf(MonetIconRole.DARK_FOREGROUND to 0xffffffff.toInt()))
    val palette = MonetSchemes.generate(seed, choice.style, choice.paletteOverrides)
    for (dark in listOf(false, true)) {
      val preview = MonetSchemes.iconRoles(palette, dark, choice)!!
      val runtime = MonetColorPolicy.selectPair(palette, if (dark) 0x20 else 0x10, choice)!!
      assertEquals(preview.foreground, runtime.foreground)
      assertEquals(preview.background, runtime.background)
      assertEquals(palette[if (dark) "neutral20" else "neutral90"], runtime.background)
      if (dark) assertEquals(0xffffffff.toInt(), runtime.foreground)
    }
    val override = choice.copy(iconOverrides = choice.iconOverrides +
      (MonetIconRole.DARK_BACKGROUND to 0xff000000.toInt()))
    assertEquals(0xff000000.toInt(), MonetColorPolicy.selectPair(palette, 0x20, override)!!.background)
  }

  @Test fun roleOverridesDoNotCorruptPaletteTonesAndUnsafePairsAreRejected() {
    val palette = MonetSchemes.generate(seed, MonetSchemeStyle.TONAL_SPOT)
    val choice = MonetCustomization(iconOverrides = mapOf(
      MonetIconRole.LIGHT_FOREGROUND to 0xffeeeeee.toInt(),
      MonetIconRole.LIGHT_BACKGROUND to 0xffffffff.toInt(),
    ))
    assertTrue(MonetSchemes.iconRoles(palette, false, choice)!!.contrastRatio < 4.5)
    assertNull(MonetColorPolicy.selectPair(palette, 0x10, choice))
    assertNotNull(MonetColorPolicy.selectPair(palette, 0x20, choice))
    assertEquals(MonetSchemes.generate(seed, choice.style), palette)
  }

  @Test fun allFiveFamiliesCanSupplyRealIconBackgroundsInBothModes() {
    val palette = MonetSchemes.generate(seed, MonetSchemeStyle.VIBRANT)
    for (family in MonetPaletteFamily.entries) {
      val choice = MonetCustomization(backgroundFamily = family)
      assertEquals(choice, MonetCustomization.decode(choice.encode()))
      for (dark in listOf(false, true)) {
        val pair = MonetColorPolicy.selectPair(palette, if (dark) 0x20 else 0x10, choice)!!
        assertEquals(palette["${family.key}${if (dark) 20 else 90}"], pair.background)
        assertEquals(pair.background, MonetSchemes.iconRoles(palette, dark, choice)!!.background)
      }
    }
  }

  @Test fun recipeParametersIncludeHighChromaVibrantAndZeroChromaNeutrals() {
    for (style in MonetSchemeStyle.entries) {
      assertEquals(MonetPaletteFamily.entries.toSet(), MonetSchemes.familySpecs(seed, style).keys)
    }
    val gray = MonetSchemes.generate(seed, MonetSchemeStyle.VIBRANT,
      mapOf(MonetPaletteFamily.NEUTRAL to MonetTonalSpec(0.0, 0.0)))
    for (tone in 0..100) {
      val value = gray.getValue("neutral$tone")
      assertEquals(value shr 16 and 255, value shr 8 and 255)
      assertEquals(value shr 8 and 255, value and 255)
    }
  }

  @Test fun effectiveRoleFamilyAndToneReflectColorOsInspirationAndDimRounding() {
    val inspiration = MonetCustomization(style = MonetSchemeStyle.COLOROS_INSPIRATION)
    assertEquals(MonetPaletteFamily.PRIMARY, MonetIconRole.LIGHT_FOREGROUND.effectiveFamily(inspiration))
    assertEquals(40, MonetIconRole.LIGHT_FOREGROUND.effectiveTone(inspiration))
    assertEquals(95, MonetIconRole.LIGHT_BACKGROUND.effectiveTone(inspiration))
    assertEquals(MonetPaletteFamily.PRIMARY, MonetIconRole.DARK_BACKGROUND.effectiveFamily(inspiration))
    assertEquals(20, MonetIconRole.DARK_BACKGROUND.effectiveTone(inspiration))
    val standard = MonetCustomization(style = MonetSchemeStyle.TONAL_SPOT)
    assertEquals(MonetPaletteFamily.SECONDARY, MonetIconRole.DARK_BACKGROUND.effectiveFamily(standard))
    assertEquals(30, MonetIconRole.LIGHT_FOREGROUND.effectiveTone(standard))
    // Matches NativeColorOsDynamicIcon.calendarColor rounding: (0x0a * 214 + 127) / 255 = 8, etc.
    assertEquals(0xFF086BD3.toInt(), IconPackAppearance.DIM_AT_NIGHT.tintColor(0xFF0A7FFB.toInt(), true))
    assertEquals(0xFF0A7FFB.toInt(), IconPackAppearance.DIM_AT_NIGHT.tintColor(0xFF0A7FFB.toInt(), false))
  }
}
