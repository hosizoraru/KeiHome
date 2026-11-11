package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.SystemIconFallback

/** Synthetic DEFAULT edges; does not install artwork or change the active theme or mask. */
@RunWith(AndroidJUnit4::class)
class SystemIconFallbackEdgeTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val renderer = SystemIconFallbackRenderer(context, SystemIconFallback.DEFAULT)

  @Test
  fun opaqueBlackAdaptiveLayersDoNotAcquireLightEdgePixels() {
    // Target HyperOS adds a 0x33000000 adaptive stroke, not a white outline. Black isolates
    // added light pixels without treating the vendor's dark stroke as a regression.
    for (size in listOf(48, 63, 192)) {
      val source = AdaptiveIconDrawable(
        ColorDrawable(Color.BLACK),
        ColorDrawable(Color.TRANSPARENT),
      )
      val result = renderer.renderAdaptive(source, size) {}
      try {
        assertEquals(Color.BLACK, result.getPixel(size / 2, size / 2))
        for (y in 0 until size) {
          for (x in 0 until size) {
            val pixel = result.getPixel(x, y)
            if (Color.alpha(pixel) != 0) {
              assertEquals("Light pixel at $x,$y in ${size}px DEFAULT edge",
                0, pixel and 0x00ffffff)
            }
          }
        }
      } finally {
        result.recycle()
      }
    }
  }

  @Test
  fun intentionalLightContourInNativeLayerArtworkIsPreserved() {
    for (alpha in listOf(128, 255)) {
      val background = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLACK)
      }
      val foreground = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
      // A 96px native layer exposes its central 64px adaptive viewport. This contour
      // lies 4..8px inside that viewport, clear of the framework's outer stroke.
      Canvas(foreground).drawRect(22f, 22f, 74f, 74f, Paint().apply {
        color = Color.argb(alpha, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
      })
      try {
        for (size in listOf(64, 192)) {
          // IconCustomizerUtils uses 0.png below foreground layers ordered 1, 2, 3, 4.
          val source = AdaptiveIconDrawable(
            BitmapDrawable(context.resources, background),
            LayerDrawable(arrayOf(
              ColorDrawable(Color.TRANSPARENT),
              ColorDrawable(Color.TRANSPARENT),
              ColorDrawable(Color.TRANSPARENT),
              BitmapDrawable(context.resources, foreground),
            )),
          )
          val result = renderer.renderAdaptive(source, size) {}
          try {
            val inset = size * 6 / 64
            val expected = Color.rgb(alpha, alpha, alpha)
            for ((x, y) in listOf(
              size / 2 to inset,
              inset to size / 2,
              size / 2 to size - inset - 1,
              size - inset - 1 to size / 2,
            )) {
              assertEquals("Lost native contour at $x,$y, alpha=$alpha, size=$size",
                expected, result.getPixel(x, y))
            }
            assertEquals(Color.BLACK, result.getPixel(size / 2, size / 2))
          } finally {
            result.recycle()
          }
        }
      } finally {
        foreground.recycle()
        background.recycle()
      }
    }
  }
}
