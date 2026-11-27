package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.OriginalAppIcons
import os.kei.hyper.home.iconpack.OriginalIconShape

@RunWith(AndroidJUnit4::class)
class OriginalAppIconGeometryDeviceTest {
  @Test fun fullBleedBitmapAndLayerListAreClippedToActiveMask() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    source.eraseColor(Color.WHITE)
    source.setPixel(32, 32, Color.RED)
    val bitmapFallback = OriginalAppIcons.rasterizeLaunchIcon(
      BitmapDrawable(context.resources, source), 64, OriginalIconShape.mask)
    val layerIcon = LayerDrawable(arrayOf(ColorDrawable(Color.BLUE), ColorDrawable(Color.WHITE))).apply {
      setLayerSize(0, 108, 108)
      setLayerSize(1, 94, 94)
      setLayerGravity(1, Gravity.CENTER)
    }
    val layerFallback = OriginalAppIcons.rasterizeLaunchIcon(layerIcon, 64, OriginalIconShape.mask)
    try {
      assertEquals(Color.TRANSPARENT, bitmapFallback.getPixel(0, 0))
      assertEquals(Color.TRANSPARENT, bitmapFallback.getPixel(63, 63))
      assertEquals(Color.RED, bitmapFallback.getPixel(32, 32))
      assertEquals(Color.TRANSPARENT, layerFallback.getPixel(0, 0))
      assertEquals(Color.TRANSPARENT, layerFallback.getPixel(63, 63))
      assertEquals(Color.WHITE, layerFallback.getPixel(32, 32))
    } finally {
      bitmapFallback.recycle()
      layerFallback.recycle()
      source.recycle()
    }
  }

  @Test fun authoredShapedAndAlreadyMaskedArtworkPassThroughUntouched() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val circle = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    Canvas(circle).drawCircle(32f, 32f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE })
    val shaped = OriginalAppIcons.rasterizeLaunchIcon(
      BitmapDrawable(context.resources, circle), 64, OriginalIconShape.mask)
    val rechecked = IconMaskPolicy.clipIfExceeds(shaped, OriginalIconShape.mask)
    try {
      assertEquals(Color.TRANSPARENT, shaped.getPixel(0, 0))
      assertEquals(Color.BLUE, shaped.getPixel(32, 32))
      assertSame("Already-contained artwork must not be re-clipped", shaped, rechecked)
    } finally {
      shaped.recycle()
      circle.recycle()
    }
  }

  @Test fun completedSplashPreservesCornerArtworkAndTransparentPixelsAtWindowSize() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    val output = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    try {
      val paint = Paint().apply { color = Color.RED }
      Canvas(source).drawRect(0f, 0f, 8f, 8f, paint)
      paint.color = Color.BLUE
      Canvas(source).drawRect(24f, 24f, 32f, 32f, paint)
      val drawable = SplashIconArtwork.completedDrawable(context, source)
      drawable.setBounds(0, 0, 96, 96)
      drawable.draw(Canvas(output))
      assertEquals(Color.RED, output.getPixel(2, 2))
      assertEquals(Color.BLUE, output.getPixel(93, 93))
      assertEquals(Color.TRANSPARENT, output.getPixel(48, 48))
    } finally {
      source.recycle()
      output.recycle()
    }
  }

  @Test
  fun completedLaunchDrawablePreservesFixedLayersAndClipsSquareCornersAcrossOutputSizes() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val icon = LayerDrawable(arrayOf(ColorDrawable(Color.BLUE), ColorDrawable(Color.WHITE)))
    icon.setLayerSize(0, 108, 108)
    icon.setLayerSize(1, 36, 36)
    icon.setLayerGravity(1, Gravity.CENTER)
    val originalBounds = Rect(4, 5, 112, 113)
    icon.bounds = originalBounds
    val snapshot = OriginalAppIcons.rasterizeLaunchIcon(icon, 192, OriginalIconShape.mask)
    try {
      assertEquals(originalBounds, icon.bounds)
      assertEquals(Color.TRANSPARENT, snapshot.getPixel(0, 0))
      assertEquals(Color.TRANSPARENT, snapshot.getPixel(191, 191))
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
