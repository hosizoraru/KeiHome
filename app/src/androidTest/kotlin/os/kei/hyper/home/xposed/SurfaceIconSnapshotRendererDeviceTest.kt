package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon
import os.kei.hyper.home.iconpack.NativeDayNightIcon
import os.kei.hyper.home.iconpack.NativePaletteIcon

@RunWith(AndroidJUnit4::class)
class SurfaceIconSnapshotRendererDeviceTest {
  private val name = "os.kei.surface.fixture"
  private fun png(color: Int, transparentCorner: Boolean = false): ByteArray {
    val bitmap = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888)
    return try {
      bitmap.eraseColor(color)
    if (transparentCorner) bitmap.setPixel(0, 0, Color.TRANSPARENT)
      ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
      }
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

  @Test fun cancellationStopsBeforeDecodingAndDoesNotPublishAnything() {
    archive(mapOf("res/drawable-xxhdpi/$name.png" to png(Color.RED))) {
      try {
        SurfaceIconSnapshotRenderer.render(it, name, false, Locale.US, 0, check = { error("cancelled") })
        fail("Cancelled render must not produce a bitmap")
      } catch (expected: IllegalStateException) { assertEquals("cancelled", expected.message) }
    }
  }
}
