package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.iconpack.OriginalAppIcons

@RunWith(AndroidJUnit4::class)
class OriginalAppIconGeometryDeviceTest {
  @Test
  fun completedLaunchDrawablePreservesFixedLayersAcrossOutputSizes() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val icon = LayerDrawable(arrayOf(ColorDrawable(Color.BLUE), ColorDrawable(Color.WHITE)))
    icon.setLayerSize(0, 108, 108)
    icon.setLayerSize(1, 36, 36)
    icon.setLayerGravity(1, Gravity.CENTER)
    val originalBounds = Rect(4, 5, 112, 113)
    icon.bounds = originalBounds
    val snapshot = OriginalAppIcons.rasterize(icon, 192)
    try {
      assertEquals(originalBounds, icon.bounds)
      assertEquals(Color.BLUE, snapshot.getPixel(180, 96))
      assertEquals(Color.WHITE, snapshot.getPixel(96, 96))
      for (size in listOf(96, 246)) {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
          val drawable = SplashIconArtwork.drawable(context, snapshot)
          drawable.setBounds(0, 0, size, size)
          drawable.draw(Canvas(output))
          assertEquals(Color.BLUE, output.getPixel(size * 9 / 10, size / 2))
          val white = (0 until size).filter { output.getPixel(it, size / 2) == Color.WHITE }
          assertTrue("Fixed layer keeps its authored one-third width", white.size in size / 3 - 3..size / 3 + 3)
          assertTrue("Fixed layer stays centered", kotlin.math.abs(white.first() + white.last() - (size - 1)) <= 2)
        } finally {
          output.recycle()
        }
      }
    } finally {
      snapshot.recycle()
    }
  }
}
