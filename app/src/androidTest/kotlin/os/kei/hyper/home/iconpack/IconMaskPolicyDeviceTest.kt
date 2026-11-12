package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconMaskPolicyDeviceTest {
  @Test fun degenerateShapeCannotHideEveryIconAfterImport() {
    assertThrows(IllegalArgumentException::class.java) {
      NativeAdaptiveIconMask.fromPathData("line", "M0 0L100 100Z")
    }
  }
  @Test fun maskingKeepsColorDensityAndCoverageWithoutAddingAWhiteMatte() {
    val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
      density = 480
      eraseColor(Color.rgb(18, 25, 31))
      setPixel(50, 50, Color.argb(128, 20, 30, 40))
    }
    val mask = IconMaskPolicy(true).resolve(null)
    val result = IconMaskPolicy.clip(source, mask)
    try {
      assertEquals(480, result.density)
      assertEquals(0, Color.alpha(result.getPixel(0, 0)))
      assertEquals(source.getPixel(50, 50), result.getPixel(50, 50))
      assertEquals(source.getPixel(10, 50), result.getPixel(10, 50))
      var fractional = 0
      for (y in 0 until 100) for (x in 0 until 100) {
        val color = result.getPixel(x, y)
        val alpha = Color.alpha(color)
        if (alpha in 32..254 && (x != 50 || y != 50)) {
          fractional++
          assertTrue(Color.red(color) <= 22 && Color.green(color) <= 30 && Color.blue(color) <= 36)
        }
      }
      assertTrue(fractional > 0)
      assertEquals(255, Color.alpha(source.getPixel(0, 0)))
    } finally { source.recycle(); result.recycle() }
  }

  @Test fun originalOwnMaskDefaultAndCustomUseTheSameConfigSelection() {
    val square = "M0 0H100V100H0Z"
    val circle = "M50 10 A40 40 0 1 1 50 90 A40 40 0 1 1 50 10Z"
    val own = NativeIconShapeConfig.generated(square)
    assertNull(IconMaskPolicy(false).resolve(null))
    assertEquals(square, IconMaskPolicy(false).resolve(own)?.pathData)
    assertEquals(square, IconMaskPolicy(true).resolve(own)?.pathData)
    assertEquals(NativeAdaptiveIconMask.HYPER_OS4_PATH_DATA, IconMaskPolicy(true).resolve(null)?.pathData)
    assertEquals(circle, IconMaskPolicy(true, CustomIconMask(circle, "test", "a".repeat(64)))
      .resolve(own)?.pathData)
    assertEquals(square, IconMaskPolicy.fromConfig(own)?.pathData)
    assertNull(IconMaskPolicy(true).resolve(null, legacyStyle = true))
  }
}
