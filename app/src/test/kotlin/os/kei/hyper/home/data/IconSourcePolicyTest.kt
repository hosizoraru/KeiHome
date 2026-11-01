package os.kei.hyper.home.data

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.xposed.MonetColorPolicy

class IconSourcePolicyTest {
  @Test fun ordinaryColorOsStylesNeverOptIntoMonet() {
    ColorOsStyle.entries.filterNot { it == ColorOsStyle.INSPIRATION }.forEach { assertFalse(it.usesMonet) }
    assertTrue(ColorOsStyle.INSPIRATION.usesMonet)
    assertNotEquals(ColorOsStyle.CLASSIC.importSourceId("same-artwork", IconPackAppearance.SYSTEM),
      ColorOsStyle.INSPIRATION.importSourceId("same-artwork", IconPackAppearance.SYSTEM))
    assertNotEquals(ColorOsStyle.CLASSIC.importSourceId("same-artwork", IconPackAppearance.LIGHT),
      ColorOsStyle.CLASSIC.importSourceId("same-artwork", IconPackAppearance.DARK))
  }

  @Test fun forcedAppearanceUsesTheSameRolesInPreviewAndRuntime() {
    val palette = MonetSchemes.generate(0xff44bccc.toInt(), MonetSchemeStyle.COLOROS_INSPIRATION)
    for (appearance in IconPackAppearance.entries) for (dark in listOf(false, true)) {
      val choice = MonetCustomization(style = MonetSchemeStyle.COLOROS_INSPIRATION, iconAppearance = appearance)
      val preview = MonetSchemes.iconRoles(palette, dark, choice)!!
      val runtime = MonetColorPolicy.selectPair(palette,
        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO, choice)!!
      assertEquals(preview.foreground, runtime.foreground)
      assertEquals(preview.background, runtime.background)
      assertTrue(preview.contrastRatio >= 4.5)
    }
  }
}
