package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.iconpack.NativePaletteIcon
import os.kei.hyper.home.data.MonetLaunchSurface

class SplashOwnPaletteColorTest {
  private val blue = NativePaletteIcon.packedState(0xffafcaff.toInt(), 0xff304b82.toInt())
  @Test fun darkLaunchSurfaceUsesTheSameBluePaletteAsTheIcon() {
    val result = MonetLaunchSurface.surfaceColor(blue, 0xff302126.toInt())
    assertTrue((result and 255) > (result ushr 8 and 255))
    assertTrue((result ushr 8 and 255) > (result ushr 16 and 255))
    assertEquals(255, result ushr 24)
    assertTrue((result and 255) < 80)
  }
  @Test fun lightLaunchSurfaceStaysLightWithTheSameHue() {
    val result = MonetLaunchSurface.surfaceColor(blue, 0xfffff8f9.toInt())
    assertTrue((result and 255) > (result ushr 16 and 255))
    assertTrue((result ushr 16 and 255) > 220)
  }
  @Test fun disabledMonetKeepsTheDeclaredLaunchBackground() {
    val original = 0xff302126.toInt()
    assertEquals(original, MonetLaunchSurface.surfaceColor(0L, original))
  }
}
