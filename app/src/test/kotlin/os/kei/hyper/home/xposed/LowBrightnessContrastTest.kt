package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class LowBrightnessContrastTest {
  @Test fun passthroughAtNormalBrightnessAndForUnavailableMeasurements() {
    for (nits in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 20f, 100f)) {
      assertEquals(0f, lowBrightnessMaskAlpha(nits), 0f)
    }
  }

  @Test fun attenuationTapersAsThePanelApproachesTheCutoff() {
    assertEquals(0.225f, lowBrightnessMaskAlpha(5f), 0.00001f)
    assertEquals(0.15f, lowBrightnessMaskAlpha(10f), 0.00001f)
    assertEquals(0.015f, lowBrightnessMaskAlpha(19f), 0.00001f)
  }
}
