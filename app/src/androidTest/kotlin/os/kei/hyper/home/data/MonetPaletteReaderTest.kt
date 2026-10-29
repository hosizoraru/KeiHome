package os.kei.hyper.home.data

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonetPaletteReaderTest {
  @Test
  fun monochromeStatusUsesTheSystemSettingsNamespace() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val enabled = Settings.System.getString(context.contentResolver, "key_monochrome")
    val color = Settings.System.getString(context.contentResolver, "key_monochrome_color")
      ?.toLongOrNull()
      ?.takeIf { it >= Int.MIN_VALUE.toLong() && it <= UInt.MAX_VALUE.toLong() }
      ?.toInt()
    val actual = MonetPaletteReader(context).read().monochrome

    assertEquals(
      when (enabled) {
        "1" -> true
        "0" -> false
        else -> null
      },
      actual.enabled,
    )
    assertEquals(color, actual.colorArgb)
  }

  @Test
  fun frameworkSuffixesMapToActualTones() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val palette = MonetPaletteReader(context).read().frameworkPalette
    val primary = palette.families.single { it.name == "primary" }
    val expected = listOf(
      1000 to 0, 900 to 10, 800 to 20, 700 to 30, 600 to 40,
      500 to 50, 400 to 60, 300 to 70, 200 to 80, 100 to 90,
      50 to 95, 10 to 99, 0 to 100,
    )
    val expectedColors = expected.map { (suffix, tone) ->
      val id = context.resources.getIdentifier("system_accent1_$suffix", "color", "android")
      assertNotEquals("missing system_accent1_$suffix", 0, id)
      MonetColor(tone, context.resources.getColor(id, context.theme))
    }

    assertEquals(expectedColors, primary.colors)
    assertEquals(65, palette.generatedColorCount)
  }
}
