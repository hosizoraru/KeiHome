package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.perfect.PerfectStaticNightRenderer

class PerfectLayeredMtzDeviceTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private fun source(): File = File(context.externalCacheDir, "perfect-reporter.mtz").also {
    assumeTrue("Provide the reporter MTZ in the target application's external cache", it.isFile)
  }

  @Test fun reporterMtzImportsAndCopiesItsCompleteNativeModuleUnchanged() {
    val dir = Files.createTempDirectory(context.cacheDir.toPath(), "layered-import-").toFile()
    try {
      val original = source()
      val result = IconPackStore(File(dir, "store")).import(original.inputStream(), original.name)
      assertEquals(IconPackFormat.MIUI_MTZ, result.record.format)
      val output = File(dir, "copied-icons")
      val index = IconPackArchive().copyNativeModule(result.record, output, File(dir, "unused-nested"))
      val expected = ZipFile(original).use { it.getInputStream(it.getEntry("icons")).use { s -> s.readBytes() } }
      assertArrayEquals(expected, output.readBytes())
      assertTrue(index.entries.any { it.nativeName == "com.jingdong.app.mall" })
      index.entries.mapNotNull { it.light as? IconPackArtwork.Archive }
        .filter { it.layers.isNotEmpty() && !it.snapshot }.forEach { assertTrue(it.nativeLayers) }
    } finally { dir.deleteRecursively() }
  }

  @Test fun reporterPreviewMatchesFrameworkLayersAndDetectsTheFormerShrink() = withLayers { zip, artwork ->
    val actual = render(zip, artwork, 256)
    val former = render(zip, artwork.copy(nativeLayers = false), 256)
    val expected = native(zip, artwork, 256)
    try {
      assertPixels(expected, actual)
      var changed = 0
      for (y in 64..191) for (x in 64..191)
        if (former.getPixel(x, y) != expected.getPixel(x, y)) changed++
      assertTrue("The fixture must expose loss of native overscan", changed > 500)
      save("jd-framework.png", expected)
      save("jd-current.png", actual)
      save("jd-former.png", former)
    } finally { actual.recycle(); former.recycle(); expected.recycle() }
  }

  @Test fun optionalDarkConversionKeepsTheSameNativeDayGeometry() = withLayers { zip, artwork ->
    val bytes = artwork.layers.map { group ->
      zip.getInputStream(zip.getEntry(group.single().path)).use { it.readBytes() }
    }
    val pair = PerfectStaticNightRenderer.render(bytes, null, nativeViewport = true, check = {})
    val actual = BitmapFactory.decodeByteArray(pair.day, 0, pair.day.size)
    val expected = native(zip, artwork, 512)
    try {
      save("jd-dark-day-framework.png", expected)
      save("jd-dark-day-current.png", actual)
      assertGeometry(expected, actual)
    } finally { actual.recycle(); expected.recycle() }
  }

  @Test fun reporterGenericColoringAcceptsMetadataAndPreservesItsNumberedLayers() {
    val dir = Files.createTempDirectory(context.cacheDir.toPath(), "layered-monet-").toFile()
    try {
      val native = File(dir, "icons")
      IconPackArchive().copyNativeModule(
        IconPackStore(File(dir, "store")).import(source().inputStream(), "reporter.mtz").record,
        native, File(dir, "nested"))
      val output = File(dir, "published")
      assertTrue(os.kei.hyper.home.status.PublishedGenericMonet.write(native, output, true,
        PreservedIconResources(emptySet(), emptyList())))
      ZipFile(native).use { original -> ZipFile(output).use { result ->
        original.entries().asSequence().filter {
          it.name.matches(Regex("res/drawable[^/]*/[^/]+/[01]\\.png"))
        }.forEach { entry ->
          val before = original.getInputStream(entry).use { it.readBytes() }
          val after = result.getInputStream(requireNotNull(result.getEntry(entry.name))).use { it.readBytes() }
          assertArrayEquals(entry.name, before, after)
        }
        val name = "fancy_icons/com.miui.passwords/day.png"
        assertNotNull(result.getEntry(name))
        val day = result.getInputStream(result.getEntry(name)).use { it.readBytes() }
        assertTrue(GeneratedIconPng.squareSize(day) >= 32)
        val publication = File(dir, "published.mtz")
        java.util.zip.ZipOutputStream(publication.outputStream()).use { zip ->
          val description = "<MIUI-Theme><title>Local fixture</title><author>QA</author>" +
            "<designer>QA</designer><version>1</version><uiVersion>13</uiVersion>" +
            "<description>Local static source fixture</description></MIUI-Theme>"
          for ((path, bytes) in mapOf("description.xml" to description.toByteArray(),
            "preview/preview_icons_0.png" to day)) {
            zip.putNextEntry(java.util.zip.ZipEntry(path)); zip.write(bytes); zip.closeEntry()
          }
          zip.putNextEntry(java.util.zip.ZipEntry("icons"))
          output.inputStream().use { it.copyTo(zip) }
          zip.closeEntry()
        }
        os.kei.hyper.home.xposed.ConvertedIconArchive.inspect(publication,
          os.kei.hyper.home.xposed.ConvertedIconArchive.LOCAL_NATIVE)
      } }
    } finally { dir.deleteRecursively() }
  }

  private fun withLayers(block: (ZipFile, IconPackArtwork.Archive) -> Unit) {
    val dir = Files.createTempDirectory(context.cacheDir.toPath(), "layered-render-").toFile()
    try {
      val nested = File(dir, "icons")
      val index = IconPackArchive().inspect(source(), nested)
      val artwork = index.entries.single { it.nativeName == "com.jingdong.app.mall" }.light
        as IconPackArtwork.Archive
      assertTrue(artwork.nativeLayers)
      ZipFile(nested).use { block(it, artwork) }
    } finally { dir.deleteRecursively() }
  }

  private fun decode(zip: ZipFile, path: String): Bitmap =
    zip.getInputStream(zip.getEntry(path)).use { BitmapFactory.decodeStream(it)!! }

  private fun render(zip: ZipFile, artwork: IconPackArtwork.Archive, size: Int): Bitmap =
    ArchiveLayerRenderer.render(artwork, false, 480, 37, size, {}) { decode(zip, it.path) }!!

  private fun native(zip: ZipFile, artwork: IconPackArtwork.Archive, size: Int): Bitmap {
    val background = decode(zip, artwork.layers[0].single().path)
    val foreground = decode(zip, artwork.layers[1].single().path)
    try {
      val drawable = AdaptiveIconDrawable(
        BitmapDrawable(context.resources, background).apply { isFilterBitmap = true },
        BitmapDrawable(context.resources, foreground).apply { isFilterBitmap = true })
      drawable.setBounds(0, 0, size, size)
      return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { drawable.draw(Canvas(it)) }
    } finally { background.recycle(); foreground.recycle() }
  }

  private fun assertPixels(expected: Bitmap, actual: Bitmap) {
    assertEquals(expected.width, actual.width)
    val from = expected.width / 4
    val end = expected.width * 3 / 4
    var different = 0
    for (y in from until end) for (x in from until end) {
      val a = expected.getPixel(x, y)
      val b = actual.getPixel(x, y)
      if ((0..3).any { shift ->
          kotlin.math.abs(((a ushr (shift * 8)) and 255) - ((b ushr (shift * 8)) and 255)) > 2
        }) different++
    }
    assertEquals("Native interior pixels differ", 0, different)
  }

  private fun assertGeometry(expected: Bitmap, actual: Bitmap) {
    assertEquals(expected.width, actual.width)
    val from = expected.width / 4
    val end = expected.width * 3 / 4
    var error = 0L
    val ink = arrayOf(mutableListOf<Pair<Int, Int>>(), mutableListOf())
    for (y in from until end) for (x in from until end) {
      val pixels = intArrayOf(expected.getPixel(x, y), actual.getPixel(x, y))
      for (shift in 0..3) error += kotlin.math.abs(
        ((pixels[0] ushr (shift * 8)) and 255) - ((pixels[1] ushr (shift * 8)) and 255))
      pixels.forEachIndexed { index, color ->
        if ((color ushr 24) == 255 && (0..2).all { (color ushr (it * 8) and 255) < 100 })
          ink[index] += x to y
      }
    }
    // Skia's Drawable and raster paths differ slightly at antialiased glyph edges.
    assertTrue("Day artwork changed", error.toDouble() / ((end - from) * (end - from) * 4) < .5)
    assertTrue(ink[0].size > 1000)
    val bounds = ink.map { points ->
      listOf(points.minOf { it.first }, points.minOf { it.second },
        points.maxOf { it.first }, points.maxOf { it.second })
    }
    assertEquals("Foreground geometry changed", bounds[0], bounds[1])
    assertTrue("Foreground coverage changed", kotlin.math.abs(ink[0].size - ink[1].size) < ink[0].size / 50)
  }

  private fun save(name: String, bitmap: Bitmap) {
    File(context.externalCacheDir, name).outputStream().use {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
  }
}
