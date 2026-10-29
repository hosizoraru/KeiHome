package os.kei.hyper.home.xposed

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetColorPolicyTest {
  @Test
  fun onlyChangesUnsetOrPreviouslyManagedColors() {
    assertFalse(MonetColorPolicy.mayReplace(false, null, null))
    assertTrue(MonetColorPolicy.mayReplace(true, null, null))
    assertFalse(MonetColorPolicy.mayReplace(true, 5, null))
    assertFalse(MonetColorPolicy.mayReplace(true, 0, null))
    assertTrue(MonetColorPolicy.mayReplace(true, 0, 0))
    assertTrue(MonetColorPolicy.mayReplace(true, 0xffe0e0ff.toInt(), 0xffe0e0ff.toInt()))
    assertFalse(MonetColorPolicy.mayReplace(true, 0xff122233.toInt(), null))
    assertFalse(MonetColorPolicy.mayReplace(true, 0xff122233.toInt(), 0xffe0e0ff.toInt()))
    assertFalse(MonetColorPolicy.mayReplace(false, 5, 5))
    assertFalse(MonetColorPolicy.mayReplace(true, 0, 5))
  }

  @Test
  fun parsesSignedAndUnsignedDecimalColorsWithoutLosingArgbBits() {
    assertEquals(0xffe0e0ff.toInt(), MonetColorPolicy.parseColor("-2039553"))
    assertEquals(0xffe0e0ff.toInt(), MonetColorPolicy.parseColor("4292927743"))
    assertEquals(-1, MonetColorPolicy.parseColor("4294967295"))
    assertEquals(-1, MonetColorPolicy.parseColor("-1"))
    assertEquals(Int.MIN_VALUE, MonetColorPolicy.parseColor("-2147483648"))
    assertEquals(Int.MIN_VALUE, MonetColorPolicy.parseColor("2147483648"))
    assertEquals(Int.MAX_VALUE, MonetColorPolicy.parseColor("2147483647"))
    assertEquals(0, MonetColorPolicy.parseColor(" \t+0\n"))
    assertEquals(0, MonetColorPolicy.parseColor("-0"))
    assertEquals(1, MonetColorPolicy.parseColor("0001"))
  }

  @Test
  fun rejectsMalformedOrOutOfRangeStoredColors() {
    val invalid = listOf(
      null, "", " ", "+", "-", "--1", "+-1", "1 2", "1.0", "1e2", "NaN",
      "0xffe0e0ff", "#ffe0e0ff", "１２", "1\u0000", "4294967296", "-2147483649",
      "9223372036854775807", "9223372036854775808", "-9223372036854775809",
    )
    invalid.forEach { assertNull("Must reject $it", MonetColorPolicy.parseColor(it)) }
  }

  @Test
  fun rawOwnershipTreatsMalformedSelectionsAsManualAndComparesArgbBits() {
    assertTrue(MonetColorPolicy.mayReplaceRaw(true, null, null))
    assertTrue(MonetColorPolicy.mayReplaceRaw(true, "-1", "4294967295"))
    assertTrue(MonetColorPolicy.mayReplaceRaw(true, " 4294967295 ", "-1"))
    assertTrue(MonetColorPolicy.mayReplaceRaw(true, "0", "0"))
    assertFalse(MonetColorPolicy.mayReplaceRaw(false, "-1", "-1"))
    assertFalse(MonetColorPolicy.mayReplaceRaw(true, "0", null))
    assertFalse(MonetColorPolicy.mayReplaceRaw(true, "-1", "broken"))
    assertFalse(MonetColorPolicy.mayReplaceRaw(true, "-2", "-1"))
    listOf("", " ", "broken", "4294967296", "#ffffff").forEach { selection ->
      assertFalse(MonetColorPolicy.mayReplaceRaw(true, selection, null))
      assertFalse(MonetColorPolicy.mayReplaceRaw(true, selection, selection))
    }
  }

  @Test
  fun selectsExactAospPairsForLightAndDarkModes() {
    val palette = mapOf(
      "primary10" to 0xff21005d.toInt(),
      "primary30" to 0xff4f378b.toInt(),
      "primary80" to 0xffd0bcff.toInt(),
      "primary90" to 0xffeaddff.toInt(),
      "primary95" to 0xfff6edff.toInt(),
      "secondary20" to 0xff332d41.toInt(),
    )
    assertEquals(
      IconColors(foreground = 0xff4f378b.toInt(), background = 0xffeaddff.toInt()),
      MonetColorPolicy.selectPair(palette),
    )
    assertEquals(
      IconColors(foreground = 0xffd0bcff.toInt(), background = 0xff332d41.toInt()),
      MonetColorPolicy.selectPair(
        palette,
        Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_CAR,
      ),
    )
    assertEquals(
      MonetColorPolicy.selectPair(palette),
      MonetColorPolicy.selectPair(
        palette,
        Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_DESK,
      ),
    )
  }

  @Test
  fun missingRequiredPairNeverFallsBackToOtherTonesOrMode() {
    val palette = mapOf(
      "primary10" to 0xff21005d.toInt(),
      "primary30" to 0xff4f378b.toInt(),
      "primary80" to 0xffd0bcff.toInt(),
      "primary90" to 0xffeaddff.toInt(),
      "primary95" to 0xfff6edff.toInt(),
      "secondary10" to 0xff000000.toInt(),
      "secondary20" to 0xff332d41.toInt(),
      "secondary90" to 0xffffffff.toInt(),
      "neutral30" to 0xff000000.toInt(),
      "neutral80" to 0xffffffff.toInt(),
    )
    assertNull(MonetColorPolicy.selectPair(emptyMap()))
    listOf("primary30", "primary90").forEach { missing ->
      assertNull(MonetColorPolicy.selectPair(palette - missing))
    }
    listOf("secondary20", "primary80").forEach { missing ->
      assertNull(MonetColorPolicy.selectPair(palette - missing, Configuration.UI_MODE_NIGHT_YES))
    }
  }

  @Test
  fun rejectsTransparentForegroundOrBackgroundInEitherMode() {
    for (mode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
      val foregroundKey = if (mode == Configuration.UI_MODE_NIGHT_YES) "primary80" else "primary30"
      val backgroundKey = if (mode == Configuration.UI_MODE_NIGHT_YES) "secondary20" else "primary90"
      for (alpha in listOf(0, 0x80, 0xfe)) {
        assertNull(MonetColorPolicy.selectPair(
          mapOf(foregroundKey to (alpha shl 24), backgroundKey to 0xffffffff.toInt()), mode,
        ))
        assertNull(MonetColorPolicy.selectPair(
          mapOf(foregroundKey to 0xff000000.toInt(), backgroundKey to (alpha shl 24 or 0xffffff)),
          mode,
        ))
      }
    }
  }

  @Test
  fun evaluatesActualContrastWithoutRoundingAtFourPointFive() {
    val white = 0xffffffff.toInt()
    // WCAG sRGB reference ratios against white: 4.5422... and 4.4780... .
    assertTrue(MonetColorPolicy.isLegiblePair(0xff767676.toInt(), white))
    assertTrue(MonetColorPolicy.isLegiblePair(white, 0xff767676.toInt()))
    assertFalse(MonetColorPolicy.isLegiblePair(0xff777777.toInt(), white))
    assertFalse(MonetColorPolicy.isLegiblePair(white, 0xff777777.toInt()))
    assertFalse(MonetColorPolicy.isLegiblePair(white, white))
    assertNull(MonetColorPolicy.selectPair(mapOf(
      "primary30" to 0xff777777.toInt(), "primary90" to white,
    )))
    assertNull(MonetColorPolicy.selectPair(
      mapOf("primary80" to white, "secondary20" to 0xff777777.toInt()),
      Configuration.UI_MODE_NIGHT_YES,
    ))
  }
}
