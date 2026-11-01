package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic raster fixtures; no theme application, package installation, or device settings. */
@RunWith(AndroidJUnit4::class)
class ColorOsFallbackRendererTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val renderer = ColorOsFallbackRenderer(context)

  @Test
  fun transparentFlatArtworkGetsTheSourcePlateAnd38DpForeground() {
    val source = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
    Canvas(source).drawRect(8f, 8f, 52f, 52f, Paint().apply { color = Color.RED })
    val drawable = BitmapDrawable(context.resources, source)
    val light = renderer.rasterize(drawable, false, 60, density = 1f)
    val dark = renderer.rasterize(drawable, true, 60, density = 1f)
    try {
      assertEquals(0xfffbfbfb.toInt(), light.getPixel(0, 30))
      assertEquals(0xfffbfbfb.toInt(), light.getPixel(15, 30))
      assertEquals(Color.RED, light.getPixel(30, 30))
      assertEquals(0xffd3d3d3.toInt(), dark.getPixel(0, 30))
      assertEquals(0xffd60000.toInt(), dark.getPixel(30, 30))
      assertEquals(Color.TRANSPARENT, source.getPixel(0, 0))
    } finally {
      light.recycle(); dark.recycle(); source.recycle()
    }
  }

  @Test
  fun interiorAlphaHolesDoNotTurnOpaqueEdgeArtworkIntoASmallForeground() {
    val source = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
    source.eraseColor(Color.RED)
    source.setPixel(30, 30, Color.TRANSPARENT)
    val result = renderer.rasterize(BitmapDrawable(context.resources, source), false, 60)
    try {
      assertEquals(Color.RED, result.getPixel(0, 30))
      assertEquals(Color.TRANSPARENT, result.getPixel(30, 30))
    } finally {
      result.recycle(); source.recycle()
    }
  }

  @Test
  fun sparseAlphaBelowTheSixGroupThresholdKeepsTheFullSizeBackground() {
    val source = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
    source.eraseColor(Color.RED)
    for (x in listOf(15, 30, 45)) for (y in 1..3) source.setPixel(x, y, Color.TRANSPARENT)
    val result = renderer.rasterize(BitmapDrawable(context.resources, source), false, 60)
    try {
      assertEquals(Color.RED, result.getPixel(0, 30))
      assertEquals(Color.TRANSPARENT, result.getPixel(15, 2))
    } finally {
      result.recycle(); source.recycle()
    }
  }

  @Test
  fun adaptiveLayersUseTheExpandedViewportAndRetainBlackUnderpaint() {
    val foreground = object : ColorDrawable(0x800000ff.toInt()) {
      var drawnBounds: Rect? = null
      override fun draw(canvas: Canvas) {
        drawnBounds = Rect(bounds)
        super.draw(canvas)
      }
    }
    val drawable = AdaptiveIconDrawable(null, foreground)
    val result = renderer.rasterize(drawable, false, 129)
    try {
      assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), result.colorSpace)
      assertEquals(Rect(-32, -32, 160, 160), foreground.drawnBounds)
      assertEquals(Color.rgb(0, 0, 128), result.getPixel(0, 0))
      assertEquals(Color.rgb(0, 0, 128), result.getPixel(64, 64))
    } finally {
      result.recycle()
    }
  }

  @Test
  fun maskContributesOnlyAlphaAndItsOwnershipStaysWithTheCaller() {
    val mask = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    Canvas(mask).drawRect(0f, 0f, 16f, 32f, Paint().apply { color = 0x80ff0000.toInt() })
    val original = mask.copy(Bitmap.Config.ARGB_8888, false)!!
    val result = renderer.rasterize(ColorDrawable(Color.BLUE), false, 32, mask)
    try {
      assertEquals(128, Color.alpha(result.getPixel(4, 16)))
      assertEquals(255, Color.blue(result.getPixel(4, 16)))
      assertEquals(0, Color.red(result.getPixel(4, 16)))
      assertEquals(Color.TRANSPARENT, result.getPixel(28, 16))
      assertFalse(mask.isRecycled)
      assertTrue(original.sameAs(mask))
    } finally {
      result.recycle(); original.recycle(); mask.recycle()
    }
  }

  @Test
  fun monochromeAcceptsTheRealAlphaGlyphAndRejectsMissingOrFlatLayers() {
    val source = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
    Canvas(source).drawRect(36f, 36f, 72f, 72f, Paint().apply { color = Color.RED })
    val glyph = BitmapDrawable(context.resources, source)
    val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.BLUE), null, glyph)
    val result = renderer.rasterize(adaptive, true, 60, monochrome = true)
    try {
      assertEquals(Color.WHITE, result.getPixel(30, 30))
      assertEquals(Color.TRANSPARENT, result.getPixel(0, 0))
      assertThrows(RuntimeException::class.java) {
        renderer.rasterize(AdaptiveIconDrawable(null, glyph), false, 60, monochrome = true)
      }
      assertThrows(RuntimeException::class.java) {
        renderer.rasterize(AdaptiveIconDrawable(null, glyph, ColorDrawable(Color.WHITE)),
          false, 60, monochrome = true)
      }
    } finally {
      result.recycle(); source.recycle()
    }
  }

  @Test
  fun nestedAnimationsAreSkippedBeforeDrawOrStop() {
    val animation = object : AnimationDrawable() {
      var draws = 0
      var stops = 0
      override fun draw(canvas: Canvas) { draws++; super.draw(canvas) }
      override fun stop() { stops++; super.stop() }
    }.apply { addFrame(ColorDrawable(Color.RED), 100) }
    val nested = AdaptiveIconDrawable(ColorDrawable(Color.WHITE), LayerDrawable(arrayOf(animation)))
    assertThrows(RuntimeException::class.java) { renderer.rasterize(nested, false, 60) }
    assertEquals(0, animation.draws)
    assertEquals(0, animation.stops)
  }

  @Test
  fun clockAndCalendarMetadataPreserveDynamicsWithoutPackageNameGuessing() {
    assertEquals(ColorOsFallbackRenderer.SkipReason.DYNAMIC_CLOCK,
      ColorOsFallbackRenderer.dynamicReason("com.android.deskclock", null))
    assertEquals(ColorOsFallbackRenderer.SkipReason.DYNAMIC_CALENDAR,
      ColorOsFallbackRenderer.dynamicReason("com.android.calendar", null))
    assertEquals(ColorOsFallbackRenderer.SkipReason.DYNAMIC_CLOCK,
      ColorOsFallbackRenderer.dynamicReason("example.clock", Bundle().apply {
        putInt("com.android.launcher3.LEVEL_PER_TICK_ICON_ROUND", 123)
      }))
    assertEquals(ColorOsFallbackRenderer.SkipReason.DYNAMIC_CALENDAR,
      ColorOsFallbackRenderer.dynamicReason("example.calendar", Bundle().apply {
        putInt("example.calendar.dynamic_icons", 123)
      }))
    assertNull(ColorOsFallbackRenderer.dynamicReason("example.clock", null))
    assertNull(ColorOsFallbackRenderer.dynamicReason("example.calendar", null))
  }

  @Test
  fun publicApiLoadsTheActualLauncherActivityAndReportsMissingTargets() {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
    val activity = intent.component!!
    val target = IconPackComponent(activity.packageName, activity.className)
    val result = renderer.render(target, false, 60)
    assertNotNull(result)
    result?.recycle()
    val skipped = mutableListOf<ColorOsFallbackRenderer.SkipReason>()
    val reporter = ColorOsFallbackRenderer(context) { _, reason -> skipped += reason }
    assertNull(reporter.render(IconPackComponent("invalid.missing.application", "MissingActivity"), false, 60))
    assertEquals(listOf(ColorOsFallbackRenderer.SkipReason.MISSING_ACTIVITY), skipped)
  }

  @Test
  fun cancellationPropagatesFromPublicAndRasterPaths() {
    val cancellation = CancellationException("fixture cancelled")
    val publicFailure = assertThrows(CancellationException::class.java) {
      renderer.render(IconPackComponent(context.packageName), false, 60) { throw cancellation }
    }
    assertSame(cancellation, publicFailure)
    var checks = 0
    val rasterFailure = assertThrows(CancellationException::class.java) {
      renderer.rasterize(ColorDrawable(Color.WHITE), false, 60) {
        if (++checks >= 4) throw cancellation
      }
    }
    assertSame(cancellation, rasterFailure)
  }
}
