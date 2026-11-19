package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestureGlassFinishDeviceTest {
  @Test fun lightSpreadsAlongTheShoulderAndFollowsTheAnimatedBounds() {
    val glass = GestureGlassFinish()
    val bitmap = Bitmap.createBitmap(440, 46, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    fun render(left: Int, right: Int, scale: Float) {
      bitmap.eraseColor(Color.BLACK)
      glass.update(left, 12, right, 32, 10f, 0f, scale)
      glass.draw(canvas)
    }
    render(35, 405, 1f)
    val shoulder = bitmap.getPixel(105, 14)
    val core = Color.red(bitmap.getPixel(220, 22))
    assertTrue(core < 56)
    assertTrue(Color.red(shoulder) > core + 20)
    assertTrue((50..210).count { Color.red(bitmap.getPixel(it, 14)) > core + 20 } >= 65)
    assertTrue(Color.red(bitmap.getPixel(220, 14)) < Color.red(shoulder))
    val outer = bitmap.getPixel(42, 14)
    render(17, 423, 1.1f)
    assertNotEquals(outer, bitmap.getPixel(42, 14))
    assertTrue(Color.red(bitmap.getPixel(220, 22)) < 56)
    render(35, 405, 1f)
    assertEquals(shoulder, bitmap.getPixel(105, 14))
    assertEquals(Color.BLACK, bitmap.getPixel(34, 22))
    glass.alpha = 0
    render(35, 405, 1f)
    assertEquals(Color.BLACK, bitmap.getPixel(42, 14))
  }
}
