package os.kei.hyper.home.xposed

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.VectorDrawable
import android.view.Gravity
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SplashBackgroundDrawableDeviceTest {
  private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
  private val width = 32
  private val height = 24

  @Test fun extractsAnySafeOpaqueBaseAndLeavesColorEligibilityToPolicy() {
    assertNull(SplashBackgroundDrawable.backgroundColor(null))
    for (color in listOf(Color.WHITE, Color.BLACK, Color.RED, 0xfff5f5f5.toInt())) {
      assertEquals(color, SplashBackgroundDrawable.backgroundColor(ColorDrawable(color)))
      for (gravity in listOf(Gravity.NO_GRAVITY, Gravity.FILL)) {
        val layers = branded(color).apply {
          setLayerGravity(0, gravity)
          setLayerInsetRelative(0, 0, 0, 0, 0)
        }
        assertEquals(color, SplashBackgroundDrawable.backgroundColor(layers))
      }
      assertEquals(color, SplashBackgroundDrawable.backgroundColor(branded(color)))
    }
  }

  @Test fun adaptsSolidBackgroundWithoutChangingSourceOrSharedConstantState() {
    val source = ColorDrawable(Color.WHITE).apply {
      bounds = Rect(2, 3, 30, 22)
      layoutDirection = View.LAYOUT_DIRECTION_RTL
      state = intArrayOf(android.R.attr.state_enabled)
      level = 4321
      isAutoMirrored = true
      changingConfigurations = android.content.pm.ActivityInfo.CONFIG_UI_MODE
      setVisible(false, false)
    }
    val sibling = source.constantState!!.newDrawable(resources).apply { bounds = source.bounds }
    val before = pixels(source)
    val siblingBefore = pixels(sibling)
    val result = SplashBackgroundDrawable.adapt(source, true)

    assertNotSame(source, result)
    assertEquals(Color.BLACK, (result as ColorDrawable).color)
    assertEquals(Color.WHITE, source.color)
    assertArrayEquals(before, pixels(source))
    assertArrayEquals(siblingBefore, pixels(sibling))
    assertEquals(Color.WHITE, (source.constantState!!.newDrawable(resources) as ColorDrawable).color)
    assertPresentation(source, result)
    assertEquals(Color.BLACK, pixels(result)[4 * width + 4])
    assertEquals(Color.TRANSPARENT, pixels(result)[0])
    assertSame(result, SplashBackgroundDrawable.adapt(result, true))
  }

  @Test fun changesOnlyBackgroundPixelsAndRetainsBitmapBrandingAndResourceSiblings() {
    val source = branded(Color.WHITE)
    val overlay = source.getDrawable(1) as BitmapDrawable
    val bitmapBefore = bitmapPixels(overlay.bitmap)
    val sourceBefore = pixels(source)
    val sibling = source.constantState!!.newDrawable(resources).apply { bounds = source.bounds }
    val siblingBefore = pixels(sibling)
    val result = SplashBackgroundDrawable.adapt(source, true) as LayerDrawable
    val after = pixels(result)

    assertNotSame(source, result)
    assertNotSame(source.getDrawable(0), result.getDrawable(0))
    assertNotSame(overlay, result.getDrawable(1))
    for (y in 0 until height) for (x in 0 until width) {
      val expected = when {
        y in 16 until 20 && x in 12 until 16 -> Color.RED
        y in 16 until 20 && x in 16 until 20 -> Color.BLUE
        else -> Color.BLACK
      }
      assertEquals("Pixel ($x, $y)", expected, after[y * width + x])
    }
    assertEquals(Color.WHITE, sourceBefore[0])
    assertEquals(Color.RED, sourceBefore[17 * width + 13])
    assertEquals(Color.BLUE, sourceBefore[17 * width + 18])
    assertArrayEquals(sourceBefore, pixels(source))
    assertArrayEquals(siblingBefore, pixels(sibling))
    assertArrayEquals(bitmapBefore, bitmapPixels(overlay.bitmap))
    assertArrayEquals(bitmapBefore, bitmapPixels((result.getDrawable(1) as BitmapDrawable).bitmap))
    assertEquals(Color.WHITE, (source.getDrawable(0) as ColorDrawable).color)
    assertEquals(Color.WHITE, SplashBackgroundDrawable.backgroundColor(
      source.constantState!!.newDrawable(resources)))
  }

  @Test fun preservesLayerMetadataOverlayTintFilterAlphaAndGeometry() {
    val source = branded(0xfff5f5f5.toInt()).apply {
      setId(0, 100)
      setId(1, 101)
      setLayerSize(1, 8, 4)
      setLayerGravity(1, Gravity.BOTTOM or Gravity.END)
      setLayerInset(1, 1, 2, 3, 4)
      setLayerInsetRelative(1, 5, 2, 6, 4)
      paddingMode = LayerDrawable.PADDING_MODE_STACK
      setPaddingRelative(2, 3, 4, 5)
      setOpacity(PixelFormat.TRANSLUCENT)
      layoutDirection = View.LAYOUT_DIRECTION_RTL
      state = intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)
      level = 7654
      isAutoMirrored = true
      changingConfigurations = android.content.pm.ActivityInfo.CONFIG_UI_MODE
      setVisible(false, false)
      bounds = Rect(1, 1, 31, 23)
    }
    val overlay = source.getDrawable(1) as BitmapDrawable
    overlay.setTintList(ColorStateList(
      arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
      intArrayOf(Color.GREEN, Color.YELLOW)))
    overlay.state = source.state
    overlay.alpha = 192
    overlay.colorFilter = PorterDuffColorFilter(Color.CYAN, PorterDuff.Mode.SRC_IN)
    val before = pixels(source)
    val result = SplashBackgroundDrawable.adapt(source, true) as LayerDrawable
    val adaptedColor = SplashBackgroundPolicy.adapt(0xfff5f5f5.toInt(), true)
    val expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(expected)
      val background = ColorDrawable(adaptedColor).apply { bounds = source.bounds }
      background.draw(canvas)
      overlay.draw(canvas)
      assertArrayEquals(bitmapPixels(expected), pixels(result))
    } finally { expected.recycle() }

    assertLayerMetadata(source, result)
    assertEquals(overlay.alpha, result.getDrawable(1).alpha)
    assertSame(overlay.colorFilter, result.getDrawable(1).colorFilter)
    assertTrue(result.getDrawable(1).isStateful)
    val copiedOverlay = result.getDrawable(1) as BitmapDrawable
    copiedOverlay.clearColorFilter()
    assertNotNull(overlay.colorFilter)
    copiedOverlay.alpha = 255
    val tinted = pixels(copiedOverlay)
    assertTrue(tinted.any { it == Color.GREEN })
    assertArrayEquals(before, pixels(source))
  }

  @Test fun preservesSourceDensityAndGeometryWhenBoundsAreRecomputed() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    for (density in listOf(160, 480)) {
      val densityResources = context.createConfigurationContext(
        Configuration(resources.configuration).apply { densityDpi = density }).resources
      assertEquals(density, densityResources.displayMetrics.densityDpi)
      val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply {
        this.density = 160
        eraseColor(Color.RED)
      }
      val overlay = BitmapDrawable(densityResources, bitmap).apply { gravity = Gravity.FILL }
      val source = (LayerDrawable(arrayOf(ColorDrawable(Color.WHITE), overlay))
        .constantState!!.newDrawable(densityResources).mutate() as LayerDrawable).apply {
        paddingMode = LayerDrawable.PADDING_MODE_STACK
        setPadding(3, 5, 7, 9)
        setLayerSize(1, 8, 4)
        setLayerGravity(1, Gravity.BOTTOM or Gravity.RIGHT)
        setLayerInset(1, 1, 2, 3, 4)
        setBounds(0, 0, width, height)
      }
      val sourceOverlay = source.getDrawable(1) as BitmapDrawable
      val sourceBefore = pixels(source)
      val sibling = source.constantState!!.newDrawable().apply { bounds = source.bounds }
      val siblingBefore = pixels(sibling)
      val result = SplashBackgroundDrawable.adapt(source, true) as LayerDrawable

      assertEquals(3, result.leftPadding)
      assertEquals(5, result.topPadding)
      assertEquals(7, result.rightPadding)
      assertEquals(9, result.bottomPadding)
      assertEquals(8 * density / 160, sourceOverlay.intrinsicWidth)
      assertEquals(4 * density / 160, sourceOverlay.intrinsicHeight)
      assertEquals(sourceOverlay.intrinsicWidth, result.getDrawable(1).intrinsicWidth)
      assertEquals(sourceOverlay.intrinsicHeight, result.getDrawable(1).intrinsicHeight)
      assertLayerMetadata(source, result)
      assertArrayEquals(sourceBefore, pixels(source))
      assertArrayEquals(siblingBefore, pixels(sibling))

      for (bounds in listOf(Rect(0, 0, width, height), Rect(1, 1, 30, 22))) {
        val reference = source.constantState!!.newDrawable().mutate() as LayerDrawable
        reference.bounds = bounds
        (reference.getDrawable(0) as ColorDrawable).color = Color.BLACK
        result.bounds = bounds
        assertLayerMetadata(reference, result)
        assertArrayEquals(pixels(reference), pixels(result))
        assertEquals(Color.BLACK, pixels(result)[2 * width + 2])
        assertTrue(pixels(result).any { it == Color.RED })
      }
      assertArrayEquals(sourceBefore, pixels(source))
      assertArrayEquals(siblingBefore, pixels(sibling))
      assertEquals(Color.WHITE, SplashBackgroundDrawable.backgroundColor(source))
    }
  }

  @Test fun returnsSameObjectForDayDarkBrandedAndTranslucentColors() {
    for ((color, night) in listOf(
      Color.WHITE to false,
      0xfff5f5f5.toInt() to false,
      Color.BLACK to true,
      0xff181818.toInt() to true,
      Color.RED to true,
      0xffb42139.toInt() to true,
      0x80ffffff.toInt() to true,
      Color.TRANSPARENT to true,
    )) {
      assertUnchanged(ColorDrawable(color).apply { setBounds(0, 0, width, height) }, night)
      assertUnchanged(branded(color), night)
    }
    val faded = ColorDrawable(Color.WHITE).apply { alpha = 128 }
    assertNull(SplashBackgroundDrawable.backgroundColor(faded))
    assertUnchanged(faded)
    val fadedLayers = branded(Color.WHITE).apply { alpha = 128 }
    assertNull(SplashBackgroundDrawable.backgroundColor(fadedLayers))
    assertUnchanged(fadedLayers)
  }

  @Test fun rejectsInsetsExplicitBaseSizesAndNonFillGravity() {
    val changes: List<(LayerDrawable) -> Unit> = listOf(
      { it.setLayerInset(0, 1, 0, 0, 0) },
      { it.setLayerInset(0, 0, 1, 0, 0) },
      { it.setLayerInset(0, 0, 0, 1, 0) },
      { it.setLayerInset(0, 0, 0, 0, 1) },
      { it.setLayerInsetRelative(0, 1, 0, 0, 0) },
      { it.setLayerInsetRelative(0, 0, 0, 1, 0) },
      { it.setLayerInset(0, -1, 0, 0, 0) },
      { it.setLayerSize(0, width, -1) },
      { it.setLayerSize(0, -1, height) },
      { it.setLayerGravity(0, Gravity.CENTER) },
      { it.setLayerGravity(0, Gravity.FILL_HORIZONTAL) },
    )
    for (change in changes) {
      val source = branded(Color.WHITE).apply(change)
      assertNull(SplashBackgroundDrawable.backgroundColor(source))
      assertUnchanged(source)
    }
  }

  @Test fun rejectsFilteredComplexAndSubclassedBackgrounds() {
    val filter = PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
    val gradient = GradientDrawable(
      GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.WHITE, Color.LTGRAY))
    val bitmap = (branded(Color.WHITE).getDrawable(1) as BitmapDrawable)
    val selector = StateListDrawable().apply {
      addState(intArrayOf(), ColorDrawable(Color.WHITE))
    }
    val unsupported = listOf(
      ColorDrawable(Color.WHITE).apply { colorFilter = filter },
      branded(Color.WHITE).apply { colorFilter = filter },
      gradient,
      bitmap,
      VectorDrawable(),
      InsetDrawable(ColorDrawable(Color.WHITE), 0),
      selector,
      LayerDrawable(arrayOf(gradient, bitmap)),
      LayerDrawable(arrayOf(bitmap)),
      LayerDrawable(emptyArray<Drawable>()),
      object : LayerDrawable(arrayOf(ColorDrawable(Color.WHITE))) {},
      object : ColorDrawable(Color.WHITE) {},
    )
    for (source in unsupported) {
      source.setBounds(0, 0, width, height)
      assertNull(SplashBackgroundDrawable.backgroundColor(source))
      assertUnchanged(source)
    }
  }

  @Test fun preservesEligibleLayerWhenOverlayCannotBeCloned() {
    val overlay = object : Drawable() {
      override fun draw(canvas: Canvas) {}
      override fun setAlpha(alpha: Int) {}
      override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
      override fun getOpacity(): Int = PixelFormat.TRANSPARENT
    }
    val source = LayerDrawable(arrayOf(ColorDrawable(Color.WHITE), overlay)).apply {
      setBounds(0, 0, width, height)
    }
    assertEquals(Color.WHITE, SplashBackgroundDrawable.backgroundColor(source))
    assertNull(source.constantState)
    assertUnchanged(source)
  }

  private fun branded(color: Int): LayerDrawable {
    val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply {
      density = Bitmap.DENSITY_NONE
      for (y in 0 until height) for (x in 0 until width) {
        setPixel(x, y, if (x < 4) Color.RED else Color.BLUE)
      }
    }
    val overlay = BitmapDrawable(resources, bitmap).apply {
      gravity = Gravity.FILL
      isFilterBitmap = false
      setAntiAlias(false)
    }
    return LayerDrawable(arrayOf(ColorDrawable(color), overlay)).apply {
      setLayerSize(1, 8, 4)
      setLayerGravity(1, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
      setLayerInset(1, 0, 0, 0, 4)
      setBounds(0, 0, width, height)
    }
  }

  private fun assertUnchanged(source: Drawable, night: Boolean = true) {
    val before = pixels(source)
    assertSame(source, SplashBackgroundDrawable.adapt(source, night))
    assertArrayEquals(before, pixels(source))
  }

  private fun assertPresentation(source: Drawable, result: Drawable) {
    assertEquals(source.bounds, result.bounds)
    assertEquals(source.layoutDirection, result.layoutDirection)
    assertArrayEquals(source.state, result.state)
    assertEquals(source.level, result.level)
    assertEquals(source.isVisible, result.isVisible)
    assertEquals(source.isAutoMirrored, result.isAutoMirrored)
    assertEquals(source.changingConfigurations, result.changingConfigurations)
  }

  private fun assertLayerMetadata(source: LayerDrawable, result: LayerDrawable) {
    assertPresentation(source, result)
    assertEquals(source.numberOfLayers, result.numberOfLayers)
    assertEquals(source.paddingMode, result.paddingMode)
    assertEquals(source.opacity, result.opacity)
    assertEquals(source.leftPadding, result.leftPadding)
    assertEquals(source.topPadding, result.topPadding)
    assertEquals(source.rightPadding, result.rightPadding)
    assertEquals(source.bottomPadding, result.bottomPadding)
    assertEquals(source.startPadding, result.startPadding)
    assertEquals(source.endPadding, result.endPadding)
    for (index in 0 until source.numberOfLayers) {
      assertEquals(source.getId(index), result.getId(index))
      assertEquals(source.getLayerWidth(index), result.getLayerWidth(index))
      assertEquals(source.getLayerHeight(index), result.getLayerHeight(index))
      assertEquals(source.getLayerGravity(index), result.getLayerGravity(index))
      assertEquals(source.getLayerInsetLeft(index), result.getLayerInsetLeft(index))
      assertEquals(source.getLayerInsetTop(index), result.getLayerInsetTop(index))
      assertEquals(source.getLayerInsetRight(index), result.getLayerInsetRight(index))
      assertEquals(source.getLayerInsetBottom(index), result.getLayerInsetBottom(index))
      assertEquals(source.getLayerInsetStart(index), result.getLayerInsetStart(index))
      assertEquals(source.getLayerInsetEnd(index), result.getLayerInsetEnd(index))
      assertPresentation(source.getDrawable(index), result.getDrawable(index))
    }
  }

  private fun pixels(drawable: Drawable): IntArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return try {
      drawable.draw(Canvas(bitmap))
      bitmapPixels(bitmap)
    } finally { bitmap.recycle() }
  }

  private fun bitmapPixels(bitmap: Bitmap): IntArray =
    IntArray(bitmap.width * bitmap.height).also {
      bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
