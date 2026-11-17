package os.kei.hyper.home.status

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import os.kei.hyper.home.iconpack.*

@RunWith(AndroidJUnit4::class)
class PublishedGenericMonetDeviceTest {
  @Test fun nativeLayersSupplyGenericColoringInsteadOfTheirSmallerFlatAlias() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    fun image(background: Int, foreground: Int? = null): ByteArray {
      val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
      return try {
        bitmap.eraseColor(background)
        foreground?.let { Canvas(bitmap).drawRect(24f, 24f, 40f, 40f, Paint().apply { color = it }) }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
      } finally { bitmap.recycle() }
    }
    val name = "os.kei.layered.monet"
    val base = "res/drawable-xxhdpi/$name"
    for (nightMode in listOf("partial", "pair", "flat")) {
      val completeNight = nightMode == "pair"
      val files = linkedMapOf(
        "$base.png" to png(),
        "$base/0.png" to image(-1),
        "$base/1.png" to image(0, 0xff203060.toInt()),
        "nightmode/$base/0.png" to image(0xff222222.toInt()),
        NativeIconShapeConfig.PATH to NativeIconShapeConfig.generated("M0 0H100V100H0Z"))
      if (completeNight) files["nightmode/$base/1.png"] = image(0, -1)
      if (nightMode == "flat") {
        files.remove("nightmode/$base/0.png")
        files["nightmode/$base.png"] = image(0xff111111.toInt(), -1)
      }
      fixture(files) { input, output ->
        assertTrue(PublishedGenericMonet.write(input, output, true, PreservedIconResources(emptySet(), emptyList())))
        ZipFile(output).use { zip ->
          for ((path, bytes) in files.filterKeys { it.endsWith("/0.png") || it.endsWith("/1.png") }) {
            assertArrayEquals("Raw native layers stay unchanged", bytes, zip.getInputStream(zip.getEntry(path)).use { it.readBytes() })
          }
          for (dark in listOf(false, true)) {
            val prefix = if (dark && completeNight) "nightmode/" else ""
            val background = BitmapFactory.decodeByteArray(files.getValue("${prefix}$base/0.png"), 0,
              files.getValue("${prefix}$base/0.png").size)
            val foreground = BitmapFactory.decodeByteArray(files.getValue("${prefix}$base/1.png"), 0,
              files.getValue("${prefix}$base/1.png").size)
            val expected = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val actual = BitmapFactory.decodeStream(zip.getInputStream(zip.getEntry(
              "fancy_icons/$name/${if (dark && nightMode != "partial") "night" else "day"}.png")))
            try {
              val native = android.graphics.drawable.AdaptiveIconDrawable(
                android.graphics.drawable.BitmapDrawable(context.resources, background).apply { isFilterBitmap = true },
                android.graphics.drawable.BitmapDrawable(context.resources, foreground).apply { isFilterBitmap = true })
              native.setBounds(0, 0, 64, 64)
              if (dark && nightMode == "flat") {
                val bytes = files.getValue("nightmode/$base.png")
                val flat = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                try { Canvas(expected).drawBitmap(flat, 0f, 0f, null) } finally { flat.recycle() }
              } else Canvas(expected).also { native.background.draw(it); native.foreground.draw(it) }
              LayerGeometryAssertions.assertNative(expected, actual, "generic-$nightMode-$dark")
            } finally { background.recycle(); foreground.recycle(); expected.recycle(); actual.recycle() }
          }
          val mask = BitmapFactory.decodeStream(zip.getInputStream(zip.getEntry("fancy_icons/$name/mask.png")))
          try {
            assertEquals((20 until 44).toList(), (0 until 64).filter {
              android.graphics.Color.alpha(mask.getPixel(it, 32)) >= 128
            })
          } finally { mask.recycle() }
        }
      }
    }
  }

  @Test fun nativeNightOnlyLayersOutrankTheirFlatNightAlias() {
    val name = "os.kei.night.only"
    val base = "res/drawable-xxhdpi/$name"
    val day = png()
    val night = png(true)
    fixture(mapOf("$base.png" to day, "nightmode/$base.png" to night,
      "nightmode/$base/0.png" to night, "nightmode/$base/1.png" to day)) { input, output ->
      assertTrue(PublishedGenericMonet.write(input, output, true, PreservedIconResources(emptySet(), emptyList())))
      ZipFile(output).use { zip ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val background = BitmapFactory.decodeByteArray(night, 0, night.size)
        val foreground = BitmapFactory.decodeByteArray(day, 0, day.size)
        val expected = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val actual = BitmapFactory.decodeStream(zip.getInputStream(zip.getEntry("fancy_icons/$name/night.png")))
        try {
          val native = android.graphics.drawable.AdaptiveIconDrawable(
            android.graphics.drawable.BitmapDrawable(context.resources, background).apply { isFilterBitmap = true },
            android.graphics.drawable.BitmapDrawable(context.resources, foreground).apply { isFilterBitmap = true })
          native.setBounds(0, 0, 64, 64)
          Canvas(expected).also { native.background.draw(it); native.foreground.draw(it) }
          LayerGeometryAssertions.assertNative(expected, actual, "generic-native-night-only")
        } finally { background.recycle(); foreground.recycle(); expected.recycle(); actual.recycle() }
      }
    }
  }

  @Test fun appliedThemeCopyCanBeGeneratedAndInspectedWithTwoPalettes() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.filesDir, "generic-monet-acceptance").apply { mkdirs() }
    val input = File(root, "source.zip")
    assumeTrue("Optional real-theme copy is supplied by the AVD harness", input.isFile)
    val output = File(root, "generated.zip").apply { delete() }
    assertTrue(PublishedGenericMonet.write(input, output, true,
      PreservedIconResources(emptySet(), emptyList())))
    val inspection = File(context.cacheDir, "generic-monet-inspect-${UUID.randomUUID()}")
    try {
      assertTrue(IconPackArchive().inspect(output, inspection).entries.isNotEmpty())
      ZipFile(input).use { before ->
        ZipFile(output).use { after ->
          val generated = after.entries().asSequence().filter {
            it.name.startsWith("fancy_icons/") && it.name.endsWith("/mask.png") &&
              before.getEntry(it.name) == null
          }.map { it.name.removeSuffix("mask.png") }.sorted().toList()
          assertTrue("The actual theme should have static icons to supplement", generated.isNotEmpty())
          File(root, "result.txt").writeText("New palette layers: ${generated.size}\n" + generated.joinToString("\n"))
          val samples = generated.take(6)
          val preview = Bitmap.createBitmap(900, samples.size * 180 + 50, Bitmap.Config.ARGB_8888)
          try {
            val canvas = Canvas(preview)
            canvas.drawColor(0xfff5f5f5.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; color = 0xff222222.toInt() }
            listOf("Original", "Dark / mint", "Light / green").forEachIndexed { i, title ->
              canvas.drawText(title, i * 300f + 30f, 30f, paint)
            }
            for ((row, path) in samples.withIndex()) {
              fun decode(name: String): Bitmap {
                val bytes = after.getInputStream(after.getEntry(path + name)).use { it.readBytes() }
                return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
              }
              val day = decode("day.png")
              val mask = decode("mask.png")
              val bg = decode("bg.png")
              try {
                for (col in 0..2) {
                  val rect = Rect(col * 300 + 84, row * 180 + 48, col * 300 + 212, row * 180 + 176)
                  if (col == 0) canvas.drawBitmap(day, null, rect, null) else {
                    val plate = if (col == 1) 0xff303734.toInt() else 0xffdcece4.toInt()
                    val ink = if (col == 1) 0xffafeccf.toInt() else 0xff224b3c.toInt()
                    paint.colorFilter = PorterDuffColorFilter(plate, PorterDuff.Mode.SRC_IN)
                    canvas.drawBitmap(bg, null, rect, paint)
                    paint.colorFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN)
                    canvas.drawBitmap(mask, null, rect, paint)
                    paint.colorFilter = null
                  }
                  canvas.drawText(path.removePrefix("fancy_icons/").removeSuffix("/").take(30),
                    col * 300f + 12f, row * 180f + 208f, paint)
                }
              } finally { day.recycle(); mask.recycle(); bg.recycle() }
            }
            File(root, "preview.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
          } finally { preview.recycle() }
        }
      }
    } finally { inspection.deleteRecursively() }
  }

  private fun png(dark: Boolean = false): ByteArray {
    val colors = IntArray(64 * 64) { if (dark) 0xff222222.toInt() else -1 }
    for (y in 16..47) for (x in 16..47) colors[y * 64 + x] =
      if (dark) -1 else 0xff203060.toInt()
    colors[0] = 0
    val bitmap = Bitmap.createBitmap(colors, 64, 64, Bitmap.Config.ARGB_8888)
    return try {
      ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    } finally { bitmap.recycle() }
  }

  private fun fixture(files: Map<String, ByteArray>, block: (File, File) -> Unit) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "generic-monet-test-${UUID.randomUUID()}").apply { mkdirs() }
    try {
      val source = File(root, "source.zip")
      ZipOutputStream(source.outputStream()).use { zip ->
        files.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
      }
      block(source, File(root, "output.zip"))
    } finally { root.deleteRecursively() }
  }

  @Test fun staticDayNightGainsPaletteLayersAndKeepsOriginalArtwork() {
    val day = png(); val night = png(true)
    fixture(NativeDayNightIcon.entries("os.test.static", day, night)) { source, output ->
      assertTrue(PublishedGenericMonet.write(source, output, true, PreservedIconResources(emptySet(), emptyList())))
      ZipFile(output).use { zip ->
        fun read(path: String) = zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
        val root = "fancy_icons/os.test.static/"
        assertArrayEquals(day, read(root + "day.png"))
        assertArrayEquals(night, read(root + "night.png"))
        assertTrue(read(root + "manifest.xml").toString(Charsets.UTF_8).contains(NativePaletteIcon.STATE_KEY))
        val bytes = read(root + "mask.png")
        val mask = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
          assertEquals(0, mask.getPixel(0, 0) ushr 24)
          assertEquals(255, mask.getPixel(32, 32) ushr 24)
        } finally { mask.recycle() }
      }
    }
  }

  @Test fun authoredPaletteDynamicAndPreservedArtworkAreNotReplaced() {
    val png = png()
    val palette = NativePaletteIcon.entries("os.test.palette", png, png, png, png)
    val files = linkedMapOf<String, ByteArray>().apply {
      putAll(palette)
      putAll(NativeDayNightIcon.entries("os.test.preserved", png, png))
      putAll(NativeDayNightIcon.entries("os.test.dynamic", png, png))
      put("fancy_icons/os.test.dynamic/manifest.xml", "<Icon><DateTime/></Icon>".toByteArray())
      put("res/drawable-xxhdpi/os.test.plain.png", png)
    }
    fixture(files) { source, output ->
      assertTrue(PublishedGenericMonet.write(source, output, true,
        PreservedIconResources(setOf("os.test.preserved"), emptyList())))
      ZipFile(output).use { zip ->
        for ((path, bytes) in files) {
          if (path.startsWith("fancy_icons/os.test.plain/")) continue
          assertArrayEquals(path, bytes, zip.getInputStream(zip.getEntry(path)).use { it.readBytes() })
        }
        assertNotNull(zip.getEntry("fancy_icons/os.test.plain/mask.png"))
      }
    }
  }

  @Test fun disabledDoesNotCreateAnArchiveOrModifyTheInput() {
    fixture(mapOf("res/drawable-xxhdpi/os.test.plain.png" to png())) { source, output ->
      val original = source.readBytes()
      assertFalse(PublishedGenericMonet.write(source, output, false, PreservedIconResources(emptySet(), emptyList())))
      assertFalse(output.exists())
      assertArrayEquals(original, source.readBytes())
    }
  }

  @Test fun fancyOnlyImagePairsGainLayersWithoutNeedingAFlatAlias() {
    val files = NativeDayNightIcon.entries("os.test.fancy", png(), png(true))
      .filterKeys { it.startsWith("fancy_icons/") }
    fixture(files) { source, output ->
      assertTrue(PublishedGenericMonet.write(source, output, true,
        PreservedIconResources(emptySet(), emptyList())))
      ZipFile(output).use { assertNotNull(it.getEntry("fancy_icons/os.test.fancy/mask.png")) }
    }
  }

  @Test fun dayOnlyFlatArtworkGetsBothCanonicalAliasesBeforeLocalApproval() {
    val name = "os.test.plain"
    fixture(mapOf("res/drawable-xxhdpi/$name.png" to png())) { source, output ->
      assertTrue(PublishedGenericMonet.write(source, output, true,
        PreservedIconResources(emptySet(), emptyList())))
      ZipFile(output).use { zip ->
        val paths = zip.entries().asSequence().map { it.name }.toSet()
        os.kei.hyper.home.xposed.GeneratedNativeIconValidator.validate(name, paths) { path, _ ->
          zip.getInputStream(requireNotNull(zip.getEntry(path))).use { it.readBytes() }
        }
        fun read(path: String) = zip.getInputStream(requireNotNull(zip.getEntry(path))).use { it.readBytes() }
        assertArrayEquals(read("fancy_icons/$name/day.png"), read("res/drawable-xxhdpi/$name.png"))
        assertArrayEquals(read("fancy_icons/$name/day.png"), read("nightmode/res/drawable-xxhdpi/$name.png"))
      }
    }
  }

  @Test fun importedExifArtworkIsNormalizedOnlyForTheDerivedPalette() {
    val original = withChunk(png(), "eXIf", byteArrayOf(0x49, 0x49, 42, 0, 8, 0, 0, 0, 0, 0))
    val name = "os.test.metadata"
    fixture(mapOf("res/drawable-xxhdpi/$name.png" to original)) { source, output ->
      val before = source.readBytes()
      assertTrue(PublishedGenericMonet.write(source, output, true,
        PreservedIconResources(emptySet(), emptyList())))
      assertArrayEquals(before, source.readBytes())
      ZipFile(output).use { zip ->
        val paths = zip.entries().asSequence().map { it.name }.toSet()
        os.kei.hyper.home.xposed.GeneratedNativeIconValidator.validate(name, paths) { path, _ ->
          zip.getInputStream(requireNotNull(zip.getEntry(path))).use { it.readBytes() }
        }
        val day = zip.getInputStream(zip.getEntry("fancy_icons/$name/day.png")).use { it.readBytes() }
        assertEquals(64, GeneratedIconPng.squareSize(day))
        val expected = BitmapFactory.decodeByteArray(original, 0, original.size)
        val actual = BitmapFactory.decodeByteArray(day, 0, day.size)
        try {
          for (y in 0..63) for (x in 0..63)
            assertEquals(expected.getPixel(x, y), actual.getPixel(x, y))
        } finally { expected.recycle(); actual.recycle() }
      }
    }
  }

  @Test fun animatedPngIsPreservedWhileOtherStaticIconsGainColoring() {
    val animated = withChunk(png(), "acTL", byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0))
    val name = "os.test.animated"
    fixture(mapOf("res/drawable-xxhdpi/$name.png" to animated,
      "res/drawable-xxhdpi/os.test.static.png" to png())) { source, output ->
      assertTrue(PublishedGenericMonet.write(source, output, true,
        PreservedIconResources(emptySet(), emptyList())))
      ZipFile(output).use { zip ->
        assertNull(zip.getEntry("fancy_icons/$name/manifest.xml"))
        val retained = zip.getInputStream(zip.getEntry("res/drawable-xxhdpi/$name.png")).use { it.readBytes() }
        assertArrayEquals(animated, retained)
        assertNotNull(zip.getEntry("fancy_icons/os.test.static/manifest.xml"))
      }
    }
  }

  private fun withChunk(png: ByteArray, name: String, data: ByteArray): ByteArray {
    val type = name.toByteArray(Charsets.US_ASCII)
    val chunk = ByteArrayOutputStream().also { bytes ->
      java.io.DataOutputStream(bytes).apply {
        writeInt(data.size); write(type); write(data)
        writeInt(java.util.zip.CRC32().apply { update(type); update(data) }.value.toInt())
      }
    }.toByteArray()
    return png.copyOfRange(0, 33) + chunk + png.copyOfRange(33, png.size)
  }
}
