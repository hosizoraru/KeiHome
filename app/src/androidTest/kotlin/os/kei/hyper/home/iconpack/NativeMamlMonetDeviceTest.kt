package os.kei.hyper.home.iconpack

import os.kei.hyper.home.withForegroundDeviceCheck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.kei.hyper.home.data.SystemIconFallback

class NativeMamlMonetDeviceTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val testContext get() = InstrumentationRegistry.getInstrumentation().context

  private fun image(background: Int, foreground: Int, multicolor: Boolean = false): ByteArray {
    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { isAntiAlias = false }
    paint.color = background
    canvas.drawRoundRect(RectF(0f, 0f, 96f, 96f), 22f, 22f, paint)
    paint.color = foreground
    canvas.drawRect(30f, 25f, 66f, 71f, paint)
    if (multicolor) { paint.color = Color.RED; canvas.drawRect(40f, 40f, 50f, 50f, paint) }
    return try {
      ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
    } finally { bitmap.recycle() }
  }

  private fun annotated(png: ByteArray): ByteArray {
    val type = "tEXt".toByteArray(Charsets.US_ASCII)
    val text = "Comment\u0000Original metadata".toByteArray(Charsets.US_ASCII)
    val chunk = ByteArrayOutputStream()
    java.io.DataOutputStream(chunk).use {
      it.writeInt(text.size); it.write(type); it.write(text)
      it.writeInt(java.util.zip.CRC32().apply { update(type); update(text) }.value.toInt())
    }
    return png.copyOfRange(0, 33) + chunk.toByteArray() + png.copyOfRange(33, png.size)
  }

  @Test fun nativePairExportRetainsSourcePixelsAndAddsQuietPaletteSelectorsOnlyForUsedTargets() {
    val folder = File(context.cacheDir, "maml-monet-device").apply { deleteRecursively(); mkdirs() }
    try {
      val day = image(0xffffd6f8.toInt(), 0xff643662.toInt())
      val dark = image(0xff643662.toInt(), 0xffefb4e8.toInt())
      val raw = testContext.assets.open("native-maml/image-pair.xml").use { it.readBytes() }
      val archive = File(folder, "source.zip")
      val resources = linkedMapOf<String, ByteArray>()
      for (name in listOf("com.example.present", "com.example.absent", "com.example.color",
        "com.example.metadata", "com.example.identical")) {
        resources["fancy_icons/$name/manifest.xml"] = raw
        resources["fancy_icons/$name/iconBg_0.png"] = when {
          name.endsWith(".color") -> image(0xffffd6f8.toInt(), 0xff643662.toInt(), true)
          name.endsWith(".metadata") -> annotated(day)
          else -> day
        }
        resources["fancy_icons/$name/iconBg_1.png"] = if (name.endsWith(".identical")) day else dark
      }
      resources["fancy_icons/com.example.dynamic/manifest.xml"] =
        "<Icon version=\"1\" width=\"96\" height=\"96\"><Var name=\"date\" expression=\"#date\" /></Icon>".toByteArray()
      resources["fancy_icons/com.example.shadow/manifest.xml"] =
        resources.getValue("fancy_icons/com.example.dynamic/manifest.xml")
      resources["animating_icons/com.example.shadow/fancy/manifest.xml"] = raw
      resources["animating_icons/com.example.shadow/fancy/iconBg_0.png"] = day
      resources["animating_icons/com.example.shadow/fancy/iconBg_1.png"] = dark
      ZipOutputStream(archive.outputStream()).use { out ->
        for ((path, bytes) in resources) {
          out.putNextEntry(ZipEntry(path)); out.write(bytes); out.closeEntry()
        }
      }
      val store = IconPackStore(File(folder, "store"))
      val record = archive.inputStream().use { store.import(it, "native.zip").record }
      val index = IconPackArchive().inspect(record.originalFile, File(folder, "inspect"))
      val pack = IconPackDescriptor(record.id, "Pair fixture", record.format, IconPackSource.Imported(record), index.entries)
      val entry = index.entries.single { it.nativeName == "com.example.present" }
      assertTrue((entry.light as IconPackArtwork.Archive).palettePair)
      assertNull(NativeMamlMonet.read(entry, {}, { if (it.endsWith("_0.png")) ByteArray(4 * 1024 * 1024 + 1) else dark }))
      assertNull(NativeMamlMonet.read(index.entries.single { it.nativeName == "com.example.metadata" }, {}, resources::getValue))
      for (night in listOf(false, true)) IconPackRenderer(context).open(pack, night).use { session ->
        val rendered = requireNotNull(session.render(entry, 96))
        val layers = requireNotNull(session.themedLayers(entry, 96) {})
        try {
          assertEquals(96, rendered.width)
          assertEquals(0, layers.first.getPixel(5, 48) ushr 24)
          assertEquals(255, layers.first.getPixel(48, 48) ushr 24)
          assertEquals(255, layers.second.getPixel(48, 48) ushr 24)
        } finally { rendered.recycle(); layers.first.recycle(); layers.second.recycle() }
      }
      val output = IconPackExporter(context, IconPackLimits()).export(pack,
        listOf(IconPackComponent("com.example.present"), IconPackComponent("com.example.color"),
          IconPackComponent("com.example.metadata"), IconPackComponent("com.example.shadow"),
          IconPackComponent("com.example.identical")), false, 96) {}
      try {
        assertEquals(setOf("com.example.present"), output.fallbackPaletteNames)
        ZipFile(output.archive).use { zip ->
          fun bytes(path: String) = zip.getInputStream(requireNotNull(zip.getEntry(path))).use { it.readBytes() }
          val root = "fancy_icons/com.example.present/"
          assertArrayEquals(day, bytes(root + "day.png"))
          assertArrayEquals(dark, bytes(root + "night.png"))
          val program = bytes(root + "manifest.xml").toString(Charsets.UTF_8)
          assertTrue(program.contains("frameRate=\"0\""))
          assertTrue(program.contains(NativePaletteIcon.STATE_KEY))
          assertFalse(program.contains("FramerateController"))
          assertFalse(program.contains("Folme"))
          for ((path, original) in resources.filterKeys { !it.startsWith(root) })
            assertArrayEquals(path, original, bytes(path))
        }
      } finally { output.archive.delete() }
    } finally { folder.deleteRecursively() }
  }

  /** Explicit diagnostic only: source is copied into private target cache by the developer. */
  @Test fun providedLargeMtzImportsPreviewsAndExportsRealMonetWithoutChangingActiveTheme() = withForegroundDeviceCheck {
    val path = InstrumentationRegistry.getArguments().getString("monetMtzPath")
    assumeTrue(path != null)
    val source = File(requireNotNull(path)).canonicalFile
    require(source.parentFile == context.cacheDir.canonicalFile && source.name == "keihome-monet-source.mtz")
    val folder = File(context.cacheDir, "maml-monet-real").apply { deleteRecursively(); mkdirs() }
    try {
      val record = source.inputStream().use { IconPackStore(File(folder, "store")).import(it, "Monet.mtz").record }
      val index = IconPackArchive().inspect(record.originalFile, File(folder, "nested"))
      assertEquals(IconPackFormat.MIUI_MTZ, index.format)
      assertTrue(index.entries.size > 13_000)
      assertTrue((index.entries.single { it.nativeName == "com.android.settings" }.light as IconPackArtwork.Archive).palettePair)
      val pack = IconPackDescriptor(record.id, "HyperOS Monet Launcher", record.format, IconPackSource.Imported(record),
        index.entries, description = index.description)
      val launcherApps = context.getSystemService(android.content.pm.LauncherApps::class.java)
      val targets = (launcherApps.getActivityList(null, android.os.Process.myUserHandle()).map {
        IconPackComponent(it.componentName.packageName, it.componentName.className)
      } + IconPackComponent("com.android.calendar")).distinct()
      val result = IconPackExporter(context, IconPackLimits()).export(pack, targets, false, 192,
        systemFallback = SystemIconFallback.DEFAULT) {}
      try {
        assertTrue("Settings is an actually proven two-tone icon", "com.android.settings" in result.fallbackPaletteNames)
        assertFalse("Calendar keeps the original date program", "com.android.calendar" in result.fallbackPaletteNames)
        ZipFile(requireNotNull(record.iconsFile)).use { original ->
          ZipFile(result.archive).use { output ->
            for (leaf in listOf("manifest.xml", "calendar_0/themed_icon_calendar_1.png")) {
              val name = "fancy_icons/com.android.calendar/$leaf"
              assertArrayEquals(name, original.getInputStream(requireNotNull(original.getEntry(name))).use { it.readBytes() },
                output.getInputStream(requireNotNull(output.getEntry(name))).use { it.readBytes() })
            }
          }
        }
        File(context.cacheDir, "keihome-monet-device-result.txt").writeText(
          "entries=${index.entries.size}\ntargets=${targets.size}\npalette=${result.fallbackPaletteNames.sorted()}\nbytes=${result.archive.length()}\n")
      } finally { result.archive.delete() }
      val handoff = kotlinx.coroutines.runBlocking {
        os.kei.hyper.home.status.IconPackHandoffs.prepare(context, IconPackRepository(context),
          IconPackPreviewRepository(context), pack, targets, os.kei.hyper.home.data.IconPackAppearance.SYSTEM,
          systemFallback = SystemIconFallback.DEFAULT)
      }
      try {
        assertTrue("Stamped MTZ must fit the system transport", handoff.file.length() <= 256L * 1024 * 1024)
        ZipFile(handoff.file).use { outer ->
          val description = outer.getInputStream(requireNotNull(outer.getEntry("description.xml"))).use { it.readBytes().toString(Charsets.UTF_8) }
          assertTrue(description.contains("<version>${pack.description.version}</version>"))
          val inner = File(folder, "handoff-icons.zip")
          outer.getInputStream(requireNotNull(outer.getEntry("icons"))).use { input ->
            inner.outputStream().use { input.copyTo(it) }
          }
          ZipFile(inner).use { icons ->
            val metadata = requireNotNull(IconThemeMetadata.decode(
              icons.getInputStream(requireNotNull(icons.getEntry(IconThemeMetadata.PATH))).use { it.readBytes() }))
            assertEquals(pack.description.title, metadata.title)
            assertEquals(pack.description.version, metadata.version)
            ZipFile(requireNotNull(record.iconsFile)).use { original ->
              val sourcePath = "fancy_icons/com.android.settings/iconBg_0.png"
              val destinationPath = "fancy_icons/com.android.settings/day.png"
              assertArrayEquals(original.getInputStream(requireNotNull(original.getEntry(sourcePath))).use { it.readBytes() },
                icons.getInputStream(requireNotNull(icons.getEntry(destinationPath))).use { it.readBytes() })
              val calendarPath = "fancy_icons/com.android.calendar/manifest.xml"
              assertArrayEquals(original.getInputStream(requireNotNull(original.getEntry(calendarPath))).use { it.readBytes() },
                icons.getInputStream(requireNotNull(icons.getEntry(calendarPath))).use { it.readBytes() })
            }
          }
        }
      } finally { handoff.file.delete() }
    } finally { folder.deleteRecursively() }
  }
}
