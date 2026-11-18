package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.LayerGeometryAssertions
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask
import os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon
import os.kei.hyper.home.iconpack.NativeDayNightIcon
import os.kei.hyper.home.iconpack.NativeIconShapeConfig
import os.kei.hyper.home.iconpack.NativePaletteIcon

@RunWith(AndroidJUnit4::class)
class SurfaceIconSnapshotRendererDeviceTest {
  private val name = "os.kei.surface.fixture"
  private val diamond = "M50 5L95 50L50 95L5 50Z"
  private val square = "M0 0H100V100H0Z"
  private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    out.toByteArray()
  }
  private fun png(color: Int, transparentCorner: Boolean = false, size: Int = 150): ByteArray {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    return try {
      bitmap.eraseColor(color)
      if (transparentCorner) bitmap.setPixel(0, 0, Color.TRANSPARENT)
      png(bitmap)
    } finally { bitmap.recycle() }
  }
  private fun mask(): ByteArray {
    val bitmap = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888)
    return try {
      bitmap.eraseColor(Color.TRANSPARENT)
      bitmap.setPixel(75, 75, Color.WHITE)
      ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
      }
    } finally { bitmap.recycle() }
  }
  private fun marker(color: Int, left: Int, top: Int, right: Int, bottom: Int, size: Int = 150): ByteArray {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    return try {
      for (y in top until bottom) for (x in left until right) bitmap.setPixel(x, y, color)
      png(bitmap)
    } finally { bitmap.recycle() }
  }
  private fun <T> archive(entries: Map<String, ByteArray>, block: (ZipFile) -> T): T {
    val file = File.createTempFile("surface-icons-", ".zip",
      InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
    return try {
      ZipOutputStream(file.outputStream()).use { out ->
        entries.forEach { (path, bytes) ->
          out.putNextEntry(ZipEntry(path)); out.write(bytes); out.closeEntry()
        }
      }
      ZipFile(file).use(block)
    } finally { file.delete() }
  }
  private fun pixel(zip: ZipFile, dark: Boolean): Int =
    requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, dark, Locale.US, 0L))
      .let { bitmap -> try { bitmap.getPixel(90, 90) } finally { bitmap.recycle() } }

  private fun configured(entries: Map<String, ByteArray>, path: String = diamond): Map<String, ByteArray> =
    entries + (NativeIconShapeConfig.PATH to NativeIconShapeConfig.generated(path))

  private fun assertDiamond(bitmap: Bitmap, center: Int) {
    assertEquals(192, bitmap.width)
    assertEquals(192, bitmap.height)
    assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
    assertEquals(0, Color.alpha(bitmap.getPixel(30, 30)))
    assertEquals(0, Color.alpha(bitmap.getPixel(0, 96)))
    assertEquals(center, bitmap.getPixel(96, 96))
    assertEquals(center, bitmap.getPixel(96, 40))
  }

  @Test fun publishedCustomMaskFinalizesStaticVerifiedPairAndDisabledPaletteDayNight() {
    val day = png(Color.RED)
    val night = png(Color.BLUE)
    val static = mapOf("res/drawable-xxhdpi/$name.png" to day,
      "nightmode/res/drawable-xxhdpi/$name.png" to night)
    val cases = listOf(static,
      NativeDayNightIcon.entries(name, day, night),
      NativeDayNightIcon.entries(name, day, night, legacyExpanded = true),
      NativePaletteIcon.entries(name, day, night, mask(), mask()))
    for (entries in cases) archive(configured(entries)) { zip ->
      for (dark in listOf(false, true)) {
        val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, dark, Locale.US, 0))
        try { assertDiamond(icon, if (dark) Color.BLUE else Color.RED) }
        finally { icon.recycle() }
      }
    }
  }

  @Test fun publishedStockShapeClipsOpaqueStaticWithoutAddingAMatte() {
    val color = Color.rgb(18, 25, 31)
    archive(configured(mapOf("res/drawable-xxhdpi/$name.png" to png(color)),
      NativeAdaptiveIconMask.HYPER_OS4_PATH_DATA)) { zip ->
      val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, false, Locale.US, 0))
      try {
        assertEquals(0, Color.alpha(icon.getPixel(0, 0)))
        assertEquals(color, icon.getPixel(96, 96))
        assertEquals(color, icon.getPixel(10, 96))
        var fractional = 0
        for (y in 0 until icon.height) for (x in 0 until icon.width) {
          val pixel = icon.getPixel(x, y)
          if (Color.alpha(pixel) in 32..254) {
            fractional++
            assertTrue(Color.red(pixel) <= 22 && Color.green(pixel) <= 30 && Color.blue(pixel) <= 36)
          }
        }
        assertTrue("The final mask must retain antialiased, un-matted edges", fractional > 0)
      } finally { icon.recycle() }
    }
  }

  @Test fun suppliedPublishedMaskIsReusableAndExplicitNullNeverFallsBackToArchiveConfig() {
    archive(configured(mapOf("res/drawable-xxhdpi/$name.png" to png(Color.CYAN)))) { zip ->
      val resolved = requireNotNull(SurfaceIconSnapshotRenderer.resolveMask(zip))
      assertSame(resolved, SurfaceIconSnapshotRenderer.resolveMask(zip))
      val clipped = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, false, Locale.US, 0,
        resolvedMask = resolved))
      try { assertDiamond(clipped, Color.CYAN) } finally { clipped.recycle() }
      val original = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, false, Locale.US, 0,
        resolvedMask = null))
      try {
        assertEquals(Color.CYAN, original.getPixel(0, 0))
        assertEquals(Color.CYAN, original.getPixel(96, 96))
      } finally { original.recycle() }
    }
  }

  @Test fun foreignNamespaceComponentFilenameUsesNativeHashSeparator() {
    val foreign = "$name#foreign.icons.Launcher"
    archive(configured(mapOf("res/drawable-xxhdpi/$foreign.png" to png(Color.CYAN)))) { zip ->
      val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, foreign, false, Locale.US, 0))
      try { assertDiamond(icon, Color.CYAN) } finally { icon.recycle() }
      for (invalid in listOf("$name#", "#foreign.icons.Launcher",
          "$foreign#extra.Name", "$name#../outside", "a".repeat(241))) {
        assertNull(SurfaceIconSnapshotRenderer.render(zip, invalid, false, Locale.US, 0))
      }
    }
  }

  @Test fun packageOnlyArtworkCanSupplyTheSelectedAppearanceForAnActivity() {
    val exact = "$name.Launcher"
    val packageAssets = mapOf(
      "res/drawable-xxhdpi/$name.png" to png(Color.WHITE),
      "nightmode/res/drawable-xxhdpi/$name.png" to png(Color.BLACK),
    )
    archive(packageAssets) { zip ->
      for (dark in listOf(false, true)) {
        val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(
          zip, exact, dark, Locale.US, 0, packageName = name))
        try { assertEquals(if (dark) Color.BLACK else Color.WHITE, icon.getPixel(96, 96)) }
        finally { icon.recycle() }
      }
    }
    archive(packageAssets + ("res/drawable-xxhdpi/$exact.png" to png(Color.RED))) { zip ->
      val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(
        zip, exact, true, Locale.US, 0, packageName = name))
      try { assertEquals(Color.RED, icon.getPixel(96, 96)) } finally { icon.recycle() }
    }
  }

  @Test fun alreadyClippedArtworkKeepsItsAlphaWithoutASecondIntersection() {
    val source = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.argb(128, 18, 25, 31))
      for (y in 80..85) for (x in 80..85) setPixel(x, y, Color.TRANSPARENT)
    }
    val bytes = try {
      val clipped = IconMaskPolicy.clip(source,
        IconMaskPolicy.fromConfig(NativeIconShapeConfig.generated(diamond)))
      try { png(clipped) } finally { clipped.recycle() }
    } finally { source.recycle() }
    val expected = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    try {
      archive(configured(mapOf("res/drawable-xxhdpi/$name.png" to bytes))) { zip ->
        val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, false, Locale.US, 0))
        try {
          assertTrue("Already masked alpha must be byte-for-byte stable", expected.sameAs(icon))
          assertEquals(0, Color.alpha(icon.getPixel(82, 82)))
          assertEquals(128, Color.alpha(icon.getPixel(96, 96)))
        } finally { icon.recycle() }
      }
    } finally { expected.recycle() }
  }

  @Test fun paletteAndLayerCompositesUsePublishedShapeInsteadOfStockClipping() {
    val foreground = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    val foregroundPng = try {
      for (y in 75..115) for (x in 75..115) foreground.setPixel(x, y, Color.BLACK)
      png(foreground)
    } finally { foreground.recycle() }
    val paletteEntries = NativePaletteIcon.entries(name, png(Color.RED, size = 192),
      png(Color.BLUE, size = 192), foregroundPng, png(Color.BLACK, transparentCorner = true, size = 192))
    val layeredEntries = mapOf("res/drawable-xxhdpi/$name/0.png" to png(Color.YELLOW, size = 192),
      "res/drawable-xxhdpi/$name/1.png" to foregroundPng)
    for ((entries, palette) in listOf(
        paletteEntries to NativePaletteIcon.packedState(Color.GREEN, Color.YELLOW),
        layeredEntries to 0L)) {
      for (path in listOf(square, diamond)) archive(configured(entries, path)) { zip ->
        val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, false, Locale.US, 0, palette))
        try {
          assertEquals(if (palette == 0L) Color.BLACK else Color.GREEN, icon.getPixel(96, 96))
          assertEquals(Color.YELLOW, icon.getPixel(96, 40))
          assertEquals(if (path == square) Color.YELLOW else Color.TRANSPARENT, icon.getPixel(191, 0))
          assertEquals(if (path == square) Color.YELLOW else Color.TRANSPARENT, icon.getPixel(30, 30))
        } finally { icon.recycle() }
      }
    }
  }

  @Test fun quietSnapshotsAndTheirLayerFallbackReceiveTheSamePublishedMask() {
    val root = "layer_animating_icons/$name/"
    val config = """<Config><Variables><Variable name="layer0" value="base"/></Variables></Config>"""
      .toByteArray()
    for (quiet in listOf("quiet/quietImage.png", "base/quiet/quietImage.png")) {
      archive(configured(mapOf(root + "config.xml" to config,
        root + quiet to png(Color.CYAN, size = 192)))) { zip ->
        val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, false, Locale.US, 0))
        try { assertDiamond(icon, Color.CYAN) } finally { icon.recycle() }
      }
    }
  }

  @Test fun nativePairsOutrankFlatAliasesAndMatchFrameworkChildGeometry() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    for (dark in listOf(false, true)) {
      val prefix = if (dark) "nightmode/" else ""
      val mark = marker(Color.RED, 80, 80, 112, 112, size = 192)
      val entries = mapOf(
        "${prefix}res/drawable-xxhdpi/$name.png" to png(Color.BLUE, size = 192),
        "${prefix}res/drawable-xxhdpi/$name/0.png" to png(Color.YELLOW, size = 192),
        "${prefix}res/drawable-xxhdpi/$name/1.png" to mark)
      val foreground = requireNotNull(BitmapFactory.decodeByteArray(mark, 0, mark.size))
      val expected = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
      try {
        val native = AdaptiveIconDrawable(ColorDrawable(Color.YELLOW),
          BitmapDrawable(context.resources, foreground).apply { isFilterBitmap = true })
        native.bounds = Rect(0, 0, 192, 192)
        Canvas(expected).also { native.background.draw(it); native.foreground.draw(it) }
        archive(configured(entries, square)) { zip ->
          val actual = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, dark, Locale.US, 0))
          try { LayerGeometryAssertions.assertNative(expected, actual, "surface-native-$dark") }
          finally { actual.recycle() }
        }
      } finally { foreground.recycle(); expected.recycle() }
    }
  }

  @Test fun completedPaletteMasksAndQuietLayersKeepTheirAuthoredPixelPositions() {
    val mark = marker(Color.BLACK, 80, 80, 112, 112, size = 192)
    val root = "layer_animating_icons/$name/"
    val quiet = mapOf(
      root + "config.xml" to """<Config><Variables><Variable name="layer0" value="bg"/><Variable name="layer1" value="fg"/></Variables></Config>""".toByteArray(),
      root + "bg/quiet/quietImage.png" to png(Color.YELLOW, size = 192),
      root + "fg/quiet/quietImage.png" to mark,
      "res/drawable-xxhdpi/$name/0.png" to png(Color.BLUE, size = 192),
      "res/drawable-xxhdpi/$name/1.png" to png(Color.TRANSPARENT, size = 192),
      "res/drawable-xxhdpi/$name.png" to png(Color.CYAN, size = 192))
    val palette = NativePaletteIcon.entries(name, png(Color.RED, size = 192),
      png(Color.BLUE, size = 192), mark, mark)
    for (entries in listOf(quiet, palette)) archive(configured(entries, square)) { zip ->
      val actual = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, false, Locale.US, 0,
        NativePaletteIcon.packedState(Color.GREEN, Color.YELLOW)))
      try {
        val ink = if (entries === quiet) Color.BLACK else Color.GREEN
        val row = (0 until 192).filter { actual.getPixel(it, 96) == ink }
        assertEquals((80 until 112).toList(), row)
      } finally { actual.recycle() }
    }
  }

  @Test fun unsupportedOrMissingPublishedMaskDoesNotInventAStockShape() {
    val entries = mapOf("res/drawable-xxhdpi/$name/0.png" to png(Color.CYAN, size = 192),
      "res/drawable-xxhdpi/$name/1.png" to png(Color.TRANSPARENT, size = 192))
    val disabled = NativeIconShapeConfig.generated(diamond).toString(Charsets.UTF_8)
      .replace("name=\"SupportLayerIcon\" value=\"true\"", "name=\"SupportLayerIcon\" value=\"false\"")
      .toByteArray()
    for (config in listOf(null, disabled, "<IconTransform>".toByteArray(),
        ByteArray(NativeIconShapeConfig.MAX_BYTES + 1))) {
      val withConfig = if (config == null) entries else entries + (NativeIconShapeConfig.PATH to config)
      archive(withConfig) { zip ->
        val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, false, Locale.US, 0))
        try {
          assertEquals(Color.CYAN, icon.getPixel(0, 0))
          assertEquals(Color.CYAN, icon.getPixel(96, 96))
        } finally { icon.recycle() }
      }
    }
  }

  @Test fun dynamicSnapshotsApplyPublishedMaskWithoutLosingDateOrDynamicCallback() {
    val entries = NativeColorOsDynamicIcon.entries(name, NativeColorOsDynamicIcon.Kind.CALENDAR,
      mapOf("day.png" to png(Color.WHITE), "night.png" to png(Color.BLACK)))
    for (dark in listOf(false, true)) for (locale in listOf(Locale.US, Locale.CHINA)) {
      val now = 1_794_182_400_000L
      val original = archive(entries) {
        requireNotNull(SurfaceIconSnapshotRenderer.render(it, name, dark, locale, now))
      }
      val expected = try {
        IconMaskPolicy.clip(original,
          IconMaskPolicy.fromConfig(NativeIconShapeConfig.generated(diamond)))
      } finally { original.recycle() }
      try {
        archive(configured(entries)) { zip ->
          var notifications = 0
          val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, dark, locale, now,
            onDynamic = { notifications++ }))
          try {
            assertEquals(1, notifications)
            assertTrue(expected.sameAs(icon))
            assertEquals(0, Color.alpha(icon.getPixel(0, 0)))
          } finally { icon.recycle() }
        }
      } finally { expected.recycle() }
    }
  }

  @Test fun calendarTwelfthUsesLiveNightBackgroundAndDateWithALightControl() {
    val entries = NativeColorOsDynamicIcon.entries(name, NativeColorOsDynamicIcon.Kind.CALENDAR,
      mapOf("day.png" to png(Color.WHITE), "night.png" to png(Color.BLACK),
        "fallback.png" to png(Color.MAGENTA), "fallback-night.png" to png(Color.CYAN)))
    val path = NativeAdaptiveIconMask.HYPER_OS4_PATH_DATA
    val mask = requireNotNull(IconMaskPolicy.fromConfig(NativeIconShapeConfig.generated(path)))
    archive(configured(entries, path)) { zip ->
      for ((dayOfMonth, weekday) in listOf(12 to "Thursday", 13 to "Friday")) {
        val now = Calendar.getInstance().apply {
          clear()
          set(2026, Calendar.NOVEMBER, dayOfMonth, 12, 0, 0)
        }.timeInMillis
        for (dark in listOf(false, true)) {
          var callbacks = 0
          val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, dark, Locale.US, now,
            onDynamic = { callbacks++ }))
          // Literal expected fields establish Thursday 12 / Friday 13 independently
          // of the renderer's date extraction or the deliberately wrong fallback PNGs.
          val reference = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
          val expected = try {
            reference.eraseColor(if (dark) Color.BLACK else Color.WHITE)
            val canvas = Canvas(reference).apply { scale(192 / 150f, 192 / 150f) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              typeface = Typeface.create("sans-serif", Typeface.BOLD)
              textAlign = Paint.Align.CENTER
              color = Color.rgb(10, 127, 251)
              textSize = 26f
            }
            canvas.drawText(weekday, 75f, 20f - paint.fontMetrics.ascent, paint)
            paint.color = if (dark) Color.WHITE else Color.BLACK
            paint.textSize = 76f
            canvas.drawText(dayOfMonth.toString(), 75f, 43f - paint.fontMetrics.ascent, paint)
            IconMaskPolicy.clip(reference, mask)
          } finally { reference.recycle() }
          try {
            assertEquals(1, callbacks)
            assertEquals(if (dark) Color.BLACK else Color.WHITE, icon.getPixel(96, 175))
            assertTrue("Live $weekday $dayOfMonth must retain its ${if (dark) "night" else "day"} artwork",
              expected.sameAs(icon))
          } finally { expected.recycle(); icon.recycle() }
        }
      }
    }
  }

  @Test fun fixedCalendarAppearanceAndDimmingRetainBackgroundAndLiveForeground() {
    val now = Calendar.getInstance().apply {
      clear()
      set(2026, Calendar.NOVEMBER, 12, 12, 0, 0)
    }.timeInMillis
    for (fixedDark in listOf(false, true)) for (dim in listOf(false, true)) {
      val entries = NativeColorOsDynamicIcon.entries(name, NativeColorOsDynamicIcon.Kind.CALENDAR,
        mapOf("day.png" to png(Color.WHITE), "night.png" to png(Color.BLACK)),
        fixedDark = fixedDark, dim = dim)
      archive(configured(entries)) { zip ->
        val resolved = SurfaceIconSnapshotRenderer.resolveMask(zip)
        for (systemDark in listOf(false, true, false)) {
          var callbacks = 0
          val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, systemDark, Locale.US, now,
            onDynamic = { callbacks++ }))
          try {
            val white = if (dim && systemDark) Color.rgb(214, 214, 214) else Color.WHITE
            assertEquals(if (fixedDark) Color.BLACK else white, icon.getPixel(96, 175))
            val dateColor = if (fixedDark) white else Color.BLACK
            var datePixels = 0
            for (y in 85..145) for (x in 50..140) {
              if (icon.getPixel(x, y) == dateColor) datePixels++
            }
            assertTrue("The date foreground must follow the fixed artwork appearance", datePixels > 100)
            assertEquals(1, callbacks)
            assertSame(resolved, SurfaceIconSnapshotRenderer.resolveMask(zip))
          } finally { icon.recycle() }
        }
      }
    }
  }

  @Test fun layeredResourcesKeepBackgroundAndForegroundInOneAppearanceIncludingQuietSnapshots() {
    val config = """<Config><Variables><Variable name="layer0" value="bg"/><Variable name="layer1" value="fg"/></Variables></Config>"""
      .toByteArray()
    for (quiet in listOf(false, true)) for (completeNight in listOf(false, true)) {
      val entries = linkedMapOf<String, ByteArray>()
      for (dark in listOf(false, true)) {
        val prefix = if (dark) "nightmode/" else ""
        val root = if (quiet) "${prefix}layer_animating_icons/$name/"
          else "${prefix}res/drawable-xxhdpi/$name/"
        if (quiet) entries[root + "config.xml"] = config
        entries[root + if (quiet) "bg/quiet/quietImage.png" else "0.png"] =
          png(if (dark) Color.BLUE else Color.YELLOW, size = 192)
        if (!dark || completeNight) {
          entries[root + if (quiet) "fg/quiet/quietImage.png" else "1.png"] =
            marker(if (dark) Color.GREEN else Color.RED, 75, 75, 115, 115, size = 192)
        }
      }
      archive(configured(entries)) { zip ->
        for (dark in listOf(false, true, false)) {
          val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, dark, Locale.US, 0))
          try {
            val night = dark && completeNight
            assertEquals(if (night) Color.GREEN else Color.RED, icon.getPixel(96, 96))
            assertEquals(if (night) Color.BLUE else Color.YELLOW, icon.getPixel(96, 40))
            assertEquals(0, Color.alpha(icon.getPixel(0, 0)))
          } finally { icon.recycle() }
        }
      }
    }
  }

  @Test fun dynamicClockSnapshotsSelectAllNightLayersAndAdvanceHandsOnTheSameArchive() {
    val assets = linkedMapOf("day.png" to png(Color.WHITE), "night.png" to png(Color.BLACK))
    for (dark in listOf(false, true)) {
      val prefix = if (dark) "night_" else ""
      assets[prefix + "clock_day_bg.png"] =
        marker(if (dark) Color.CYAN else Color.MAGENTA, 12, 70, 25, 80)
      assets[prefix + "clock_day_hour_rectangle.png"] =
        marker(if (dark) Color.GREEN else Color.RED, 65, 40, 72, 70)
      assets[prefix + "clock_day_minute_rectangle.png"] =
        marker(if (dark) Color.YELLOW else Color.BLUE, 74, 15, 78, 70)
    }
    val entries = NativeColorOsDynamicIcon.entries(name, NativeColorOsDynamicIcon.Kind.CLOCK, assets)
    val now = Calendar.getInstance().apply {
      clear()
      set(2026, Calendar.NOVEMBER, 12, 0, 0, 0)
    }.timeInMillis
    archive(configured(entries)) { zip ->
      for (dark in listOf(false, true)) {
        var callbacks = 0
        val first = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, dark, Locale.US, now,
          onDynamic = { callbacks++ }))
        val later = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, dark, Locale.US,
          now + 15 * 60_000L, onDynamic = { callbacks++ }))
        try {
          assertEquals(if (dark) Color.BLACK else Color.WHITE, first.getPixel(96, 175))
          assertEquals(if (dark) Color.CYAN else Color.MAGENTA, first.getPixel(22, 96))
          assertEquals(if (dark) Color.GREEN else Color.RED, first.getPixel(87, 64))
          assertEquals(if (dark) Color.YELLOW else Color.BLUE, first.getPixel(96, 26))
          assertEquals(if (dark) Color.YELLOW else Color.BLUE, later.getPixel(166, 96))
          assertFalse(first.sameAs(later))
          assertEquals(2, callbacks)
          assertEquals(0, Color.alpha(later.getPixel(0, 0)))
        } finally { first.recycle(); later.recycle() }
      }
    }
  }

  @Test fun nativeStaticResourcesSelectNightAndKeepDayFallback() {
    val day = "res/drawable-xxhdpi/$name.png"
    archive(mapOf(day to png(Color.RED), "nightmode/$day" to png(Color.BLUE))) {
      assertEquals(Color.RED, pixel(it, false)); assertEquals(Color.BLUE, pixel(it, true))
      assertNull(SurfaceIconSnapshotRenderer.render(it, "../escape", true, Locale.US, 0))
    }
    archive(mapOf(day to png(Color.GREEN))) { assertEquals(Color.GREEN, pixel(it, true)) }
  }

  @Test fun zeroTickerPairAndClosedCrossfadeSelectFullArtwork() {
    for (legacy in listOf(false, true)) {
      archive(NativeDayNightIcon.entries(name, png(Color.RED), png(Color.BLUE), legacyExpanded = legacy)) {
        assertEquals(Color.RED, pixel(it, false)); assertEquals(Color.BLUE, pixel(it, true))
      }
    }
  }

  @Test fun eventPaletteUsesBothMasksWithoutRunningMaml() {
    val entries = NativePaletteIcon.entries(name, png(Color.RED), png(Color.BLUE),
      mask(), mask())
    archive(entries) { zip ->
      assertNotNull(SurfaceIconSnapshotRenderer.render(zip, name, true, Locale.US, 0))
      val bitmap = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, true, Locale.US,
        0, NativePaletteIcon.packedState(Color.GREEN, Color.YELLOW)))
      try {
        var painted = false
        for (y in 0 until bitmap.height) {
          for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) != 0) painted = true
          }
        }
        assertTrue("Palette snapshot must contain a painted pixel", painted)
      } finally { bitmap.recycle() }
    }
  }

  @Test fun componentLookupAuthenticatesPackageFallbackPaletteAgainstActualResourceRoot() {
    archive(NativePaletteIcon.entries(name, png(Color.RED), png(Color.BLUE), mask(), mask())) { zip ->
      for (dark in listOf(false, true)) {
        val expected = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, dark, Locale.US,
          0, NativePaletteIcon.packedState(Color.GREEN, Color.YELLOW)))
        val actual = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, "$name.ShareActivity",
          dark, Locale.US, 0, NativePaletteIcon.packedState(Color.GREEN, Color.YELLOW), packageName = name))
        try { assertTrue(expected.sameAs(actual)) } finally { expected.recycle(); actual.recycle() }
      }
    }
  }

  @Test fun paletteTintPreservesTransparentForegroundAndIndependentBackgroundColor() {
    val foreground = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888)
    val bytes = try {
      foreground.eraseColor(Color.TRANSPARENT)
      for (y in 60..90) for (x in 60..90) foreground.setPixel(x, y, Color.BLACK)
      ByteArrayOutputStream().use { out ->
        foreground.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
      }
    } finally { foreground.recycle() }
    archive(NativePaletteIcon.entries(name, png(Color.RED), png(Color.BLUE), bytes, png(Color.BLACK, true))) { zip ->
      val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, false, Locale.US,
        0, NativePaletteIcon.packedState(Color.GREEN, Color.YELLOW)))
      try {
        assertEquals(Color.GREEN, icon.getPixel(icon.width / 2, icon.height / 2))
        assertEquals(Color.YELLOW, icon.getPixel(icon.width / 4, icon.height / 2))
      } finally { icon.recycle() }
    }
  }

  @Test fun dynamicCalendarSnapshotsIncludeLocalizedDateAndFollowAppearance() {
    val entries = NativeColorOsDynamicIcon.entries(name, NativeColorOsDynamicIcon.Kind.CALENDAR,
      mapOf("day.png" to png(Color.WHITE), "night.png" to png(Color.BLACK)))
    archive(entries) { zip ->
      val monday = 1_794_182_400_000L
      val day = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, false, Locale.US, monday))
      val night = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, true, Locale.US, monday))
      val chinese = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, name, true, Locale.CHINA, monday))
      try {
        assertEquals(Color.WHITE, day.getPixel(0, 0))
        assertEquals(Color.BLACK, night.getPixel(0, 0))
        assertFalse(day.sameAs(night))
        assertFalse(night.sameAs(chinese))
        var white = 0
        for (y in 65..155) for (x in 35..155) if (night.getPixel(x, y) == Color.WHITE) white++
        assertTrue("Date digits must be present, not just the background", white > 100)
      } finally { day.recycle(); night.recycle(); chinese.recycle() }
    }
  }

  @Test fun foreignProgramsAreNotInterpretedAndRetainStaticFallback() {
    archive(mapOf("fancy_icons/$name/manifest.xml" to
      """<Icon><ContentProviderBinder uri="content://private"/><Text textExp="#date"/></Icon>""".toByteArray(),
      "res/drawable-xxhdpi/$name.png" to png(Color.CYAN))) {
      assertEquals(Color.CYAN, pixel(it, true))
    }
  }

  @Test fun historicalCalendarAccentKeepsDateInsteadOfEmptyBackground() {
    val entries = NativeColorOsDynamicIcon.entries(name, NativeColorOsDynamicIcon.Kind.CALENDAR,
      mapOf("day.png" to png(Color.WHITE), "night.png" to png(Color.BLACK))).toMutableMap()
    val path = "fancy_icons/$name/manifest.xml"
    entries[path] = entries.getValue(path).toString(Charsets.UTF_8)
      .replace("#FF1969FF", "#FF0A7FFB").replace("#FF101010", "#FF000000")
      .toByteArray(Charsets.UTF_8)
    archive(entries) { zip ->
      var dynamic = false
      val icon = requireNotNull(SurfaceIconSnapshotRenderer.render(
        zip, name, true, Locale.CHINA, 1_794_182_400_000L, onDynamic = { dynamic = true },
      ))
      try {
        assertTrue(dynamic)
        assertEquals(Color.BLACK, icon.getPixel(0, 0))
        var digits = 0
        for (y in 65..155) for (x in 35..155) if (icon.getPixel(x, y) == Color.WHITE) digits++
        assertTrue(digits > 100)
      } finally { icon.recycle() }
    }
  }

  @Test fun preservedPackageReplaysOriginalWithoutThePublishedThemeMask() {
    val manifest = os.kei.hyper.home.iconpack.IconGenerationManifest(
      emptySet(), preservedIconPackages = setOf(name))
    archive(configured(mapOf(
      os.kei.hyper.home.iconpack.IconGenerationManifest.PATH to manifest.encode(),
      "res/drawable-xxhdpi/$name.png" to png(Color.RED),
    ))) { zip ->
      val original = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
      try {
        assertNull(SurfaceIconSnapshotRenderer.render(zip, name, true, Locale.US, 0, packageName = name))
        assertSame(original, SurfaceIconSnapshotRenderer.render(zip, name, true, Locale.US, 0,
          packageName = name, originalIcon = { original }))
        assertEquals(Color.BLUE, original.getPixel(0, 0))
      } finally { original.recycle() }
    }
  }

  @Test fun cancellationStopsBeforeDecodingAndDoesNotPublishAnything() {
    archive(mapOf("res/drawable-xxhdpi/$name.png" to png(Color.RED))) {
      try {
        SurfaceIconSnapshotRenderer.render(it, name, false, Locale.US, 0, check = { error("cancelled") })
        fail("Cancelled render must not produce a bitmap")
      } catch (expected: IllegalStateException) { assertEquals("cancelled", expected.message) }
    }
  }
}
