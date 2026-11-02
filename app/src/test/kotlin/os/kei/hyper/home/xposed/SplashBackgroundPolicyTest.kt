package os.kei.hyper.home.xposed

import com.materialkolor.utils.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashBackgroundPolicyTest {
  @Test fun reflectsLightnessInLabForNeutralNightBackgrounds() {
    assertEquals(0xff000000.toInt(), SplashBackgroundPolicy.adapt(0xffffffff.toInt(), true))
    val gray = 0xfff5f5f5.toInt()
    val dark = SplashBackgroundPolicy.adapt(gray, true)
    assertTrue(kotlin.math.abs(ColorUtils.lstarFromArgb(dark) -
      (100.0 - ColorUtils.lstarFromArgb(gray))) < 0.3)
    assertEquals(dark, SplashBackgroundPolicy.adapt(dark, true))
  }

  @Test fun preservesDayDarkBrandTransparentAndProtectedArtwork() {
    for ((color, night, artwork) in listOf(
      Triple(0xfff5f5f5.toInt(), false, false),
      Triple(0xff181818.toInt(), true, false),
      Triple(0xffb42139.toInt(), true, false),
      Triple(0x80ffffff.toInt(), true, false),
      Triple(0xffffffff.toInt(), true, true),
    )) assertEquals(color, SplashBackgroundPolicy.adapt(color, night, artwork))
  }
}
