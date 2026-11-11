package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.xposed.ConvertedIconArchive

/** Exercises final shared handoffs only; never applies a theme or touches the user's library. */
@RunWith(AndroidJUnit4::class)
class NativeIconShapeCompletionDeviceTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val repository = IconPackRepository(context)
  private val previews = IconPackPreviewRepository(context)

  private fun icons(file: File): Map<String, ByteArray> {
    val temporary = File.createTempFile("shape-inspect-", ".zip", context.cacheDir)
    return try {
      ZipFile(file).use { mtz -> temporary.writeBytes(mtz.getInputStream(mtz.getEntry("icons")).use { it.readBytes() }) }
      ZipFile(temporary).use { zip ->
        zip.entries().asSequence().associate { it.name to zip.getInputStream(it).use { stream -> stream.readBytes() } }
      }
    } finally { temporary.delete() }
  }

  @Test fun convertedColorOsPairCompletesMissingConfigAndSwitchOffRestoresOriginalPolicy() = runBlocking {
    val pack = repository.listBuiltins().packs.first { it.colorOsProfile != null }
    for (enabled in listOf(true, false)) {
      val handoff = IconPackHandoffs.prepare(context, repository, previews, pack,
        listOf(IconPackComponent("com.android.settings")), IconPackAppearance.SYSTEM,
        includePalette = false, installedOnly = true, completeSystemIconConfig = enabled)
      try {
        val entries = icons(handoff.file)
        val generation = IconGenerationManifest.decode(entries.getValue(IconGenerationManifest.PATH))
        assertEquals(enabled, generation.completeSystemIconConfig)
        assertEquals(3, generation.schemaVersion)
        if (enabled) NativeIconShapeConfig.validateGenerated(entries.getValue(NativeIconShapeConfig.PATH))
        else assertFalse(entries.containsKey(NativeIconShapeConfig.PATH))
        ConvertedIconArchive.inspect(handoff.file, requireNotNull(handoff.convertedOrigin))
      } finally { handoff.file.delete() }
    }
  }

  @Test fun nativeArchivesPreserveAuthoredAndLegacyShapesWhileFillingMissingContracts() = runBlocking {
    val folder = File(context.cacheDir, "shape-native-${System.nanoTime()}").apply { mkdirs() }
    val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
    val png = try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
      finally { bitmap.recycle() }
    val authored = """<IconTransform><Config name="SupportLayerIcon" value="false"/><Config name="UseDynamicIcon" value="false"/><Config name="ConfigIconMask" value="M50 0A50 50 0 1 0 50 100Z"/></IconTransform>""".toByteArray()
    val partial = """<IconTransform><Config name="SupportLayerIcon" value="false"/></IconTransform>""".toByteArray()
    val filters = """<IconTransform><IconFilters><Filter name="Tint"/></IconFilters></IconTransform>""".toByteArray()
    val supplied = NativeDayNightIcon.entries("os.test.shape", png, png)
    try {
      for ((index, extra) in listOf(emptyMap(), mapOf(NativeIconShapeConfig.PATH to authored),
        mapOf(NativeIconShapeConfig.PATH to partial), mapOf("icon_mask.png" to png),
        mapOf("nightmode/icon_mask.png" to png), mapOf(NativeIconShapeConfig.PATH to filters)).withIndex()) {
        val input = File(folder, "source-$index.zip")
        ZipOutputStream(input.outputStream()).use { zip ->
          for ((name, bytes) in supplied + extra) {
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
          }
        }
        val record = input.inputStream().use {
          IconPackStore(File(folder, "store-$index")).import(it, "source.zip").record
        }
        val pack = IconPackDescriptor(record.id, "Shape fixture", record.format, IconPackSource.Imported(record), emptyList())
        for (enabled in listOf(true, false)) {
          val handoff = IconPackHandoffs.prepare(context, repository, previews, pack, emptyList(),
            IconPackAppearance.SYSTEM, includePalette = false, completeSystemIconConfig = enabled)
          try {
            val entries = icons(handoff.file)
            for ((name, bytes) in supplied) assertArrayEquals(name, bytes, entries.getValue(name))
            when (index) {
              0 -> assertEquals(enabled, entries.containsKey(NativeIconShapeConfig.PATH))
              1 -> assertArrayEquals(authored, entries.getValue(NativeIconShapeConfig.PATH))
              2 -> if (!enabled) assertArrayEquals(partial, entries.getValue(NativeIconShapeConfig.PATH))
                else {
                  val config = entries.getValue(NativeIconShapeConfig.PATH).decodeToString()
                  assertTrue(config.contains("name=\"SupportLayerIcon\" value=\"false\""))
                  assertTrue(config.contains("ConfigIconMask"))
                }
              3, 4 -> {
                assertFalse(entries.containsKey(NativeIconShapeConfig.PATH))
                assertArrayEquals(png, entries.getValue(if (index == 3) "icon_mask.png" else "nightmode/icon_mask.png"))
              }
              5 -> assertArrayEquals(filters, entries.getValue(NativeIconShapeConfig.PATH))
            }
          } finally { handoff.file.delete() }
        }
      }
    } finally { folder.deleteRecursively() }
  }
}
