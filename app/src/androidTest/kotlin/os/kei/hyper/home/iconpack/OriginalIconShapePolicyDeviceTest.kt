package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OriginalIconShapePolicyDeviceTest {
  @Test fun nativeStockShapeTakesPrecedenceOverRetainedHyperOs4() {
    val native = NativeAdaptiveIconMask.fromPathData("native-fixture", "M0 0 H100 V100 H0 Z")
    assertSame(native, resolveOriginalIconShape { native })
  }

  @Test fun missingNativeShapeRetainsHyperOs4WithoutChangingSourceArtwork() {
    val resolved = resolveOriginalIconShape { null }
    assertEquals(NativeAdaptiveIconMask.HYPER_OS4_PATH_DATA, resolved.pathData)
    val raw = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
      density = 320
      eraseColor(Color.BLUE)
    }
    val output = IconMaskPolicy.clip(raw, resolved)
    try {
      assertEquals(0, Color.alpha(output.getPixel(0, 0)))
      assertEquals(Color.BLUE, output.getPixel(50, 50))
      assertEquals(raw.density, output.density)
      assertEquals(Color.BLUE, raw.getPixel(0, 0))
      assertFalse(raw.isRecycled)
    } finally {
      output.recycle()
      raw.recycle()
    }
  }
}
