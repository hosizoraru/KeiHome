package os.kei.hyper.home.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.ui.main.resolveMonetInspiration

class MonetPaletteJsonTest {
  @Test fun parsesSignedUnsignedAndOrderedTonesWhileRejectingInvalidEntries() {
    val raw = """
      {
        "primary90": -1611228,
        "primary30": 4282532418,
        "primary101": -1,
        "primary-1": -1,
        "primary50": 123.5,
        "secondary20": "-13421773",
        "neutral-variant80": 4291611852,
        "unknown50": -1
      }
    """.trimIndent()
    val families = parseMonetPaletteJson(raw)
    assertEquals(listOf("primary", "secondary", "neutral-variant"), families.map { it.name })
    assertEquals(listOf(30, 90), families[0].colors.map { it.tone })
    assertEquals(0xFF424242.toInt(), families[0].colors[0].value)
    assertEquals(-1611228, families[0].colors[1].value)
    assertEquals(listOf(20), families[1].colors.map { it.tone })
    assertEquals(-13421773, families[1].colors[0].value)
    assertEquals(listOf(80), families[2].colors.map { it.tone })
  }

  @Test fun malformedJsonReturnsEmpty() {
    assertTrue(parseMonetPaletteJson("").isEmpty())
    assertTrue(parseMonetPaletteJson("{not-json").isEmpty())
    assertTrue(parseMonetPaletteJson("[]").isEmpty())
  }

  @Test fun resolveMonetInspirationHonorsExplicitPreferenceThenSnapshotAndComponent() {
    val emptySource = MonetPaletteSource("none", MonetReadState.NOT_PROVIDED, emptyList())
    val inspirationSnapshot = MonetPaletteSnapshot(
      source = null, style = null,
      wallpaper = WallpaperColorSnapshot(MonetReadState.NOT_PROVIDED, emptyList()),
      launcherPalette = emptySource, frameworkPalette = emptySource,
      monochrome = MonochromeIconSettings(true, null),
      customization = MonetCustomization(style = MonetSchemeStyle.COLOROS_INSPIRATION),
    )
    assertFalse(resolveMonetInspiration(false, inspirationSnapshot, true))
    assertTrue(resolveMonetInspiration(true, null, false))
    assertTrue(resolveMonetInspiration(null, inspirationSnapshot, false))
    assertTrue(resolveMonetInspiration(null, null, true))
    assertFalse(resolveMonetInspiration(null, null, false))
  }
}
