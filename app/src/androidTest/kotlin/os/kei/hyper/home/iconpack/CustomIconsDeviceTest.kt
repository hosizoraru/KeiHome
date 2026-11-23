package os.kei.hyper.home.iconpack

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.ui.main.CustomIconsController

/** Uses isolated durable files and preferences. No theme is applied and no user setting is changed. */
@RunWith(AndroidJUnit4::class)
class CustomIconsDeviceTest {
  private val source = IconPackComponent("com.keihome.source")
  private val target = IconPackComponent("com.keihome.target", "com.keihome.target.Main")

  @Test fun builtinColorOsProfilesDistinguishEveryStyleAndArtVariant() = runBlocking {
    Fixture().use { f ->
      val result = f.repository.listBuiltins()
      assertTrue(result.failures.toString(), result.failures.isEmpty())
      for (style in listOf("classic", "daylight", "material", "nightshadow", "pebble")) {
        for (enabled in listOf(false, true)) {
          val key = "coloros-$style-art-${if (enabled) "on" else "off"}"
          val pack = result.packs.single { it.colorOsProfile?.builtin?.key == key }
          assertEquals(key, style, pack.colorOsProfile!!.style)
          assertEquals(key, enabled, pack.colorOsProfile!!.artPlus)
          assertTrue(key, pack.entries.isNotEmpty())
        }
      }
    }
  }

  @Test fun nativeSnapshotSurvivesSourceRemovalAndOverridesEveryBaseResourceTree() = runBlocking {
    Fixture().use { f ->
      val red = png(Color.RED)
      val blue = png(Color.BLUE)
      val sourcePack = f.pack(NativeDayNightIcon.entries(source.packageName, red, blue))
      val captured = CustomIconResources.select(f.resources(sourcePack),
        listOf(source.packageName))
      val selection = CustomIconStore.save(f.context, target.packageName, "Target", sourcePack,
        sourcePack.entries.first(), captured)
      (sourcePack.source as IconPackSource.Imported).record.originalFile.delete()
      assertEquals(selection, CustomIconStore.selections(f.context).single())
      val snapshot = CustomIconStore.read(f.context)
      val stored = snapshot.resources.getValue(target.packageName)
      assertArrayEquals(red, stored.getValue("fancy_icons/custom.icon/day.png"))
      assertArrayEquals(blue, stored.getValue("fancy_icons/custom.icon/night.png"))

      val bases = listOf(
        f.pack(NativeDayNightIcon.entries(target.packageName, png(Color.GREEN), png(Color.CYAN))),
        f.pack(mapOf("appfilter.xml" to """
          <resources><item component="ComponentInfo{${target.flattened}}" drawable="art"/></resources>
        """.trimIndent().toByteArray(), "art.png" to png(Color.YELLOW))),
      )
      for (base in bases) {
        val handoff = IconPackHandoffs.prepare(f.context, f.repository, f.previews, base, listOf(target),
          IconPackAppearance.SYSTEM, includePalette = false, completeSystemIconConfig = false)
        try {
          val resources = f.handoffResources(handoff.file)
          assertArrayEquals(red, resources.getValue("fancy_icons/${target.packageName}/day.png"))
          assertArrayEquals(blue, resources.getValue("fancy_icons/${target.packageName}/night.png"))
          assertArrayEquals(red, resources.getValue("fancy_icons/${target.className}/day.png"))
          assertFalse(resources.keys.any { CustomIconResources.owner(it) == source.packageName })
          val manifest = IconGenerationManifest.decode(resources.getValue(IconGenerationManifest.PATH))
          assertEquals(IconGenerationManifest.CURRENT_SCHEMA, manifest.schemaVersion)
          assertEquals(snapshot.identity, manifest.customIconIdentity)
        } finally { handoff.file.delete() }
      }
    }
  }

  @Test fun classicZipHandoffPreservesTheSavedPerfectIconStaticLayers() = runBlocking {
    Fixture().use { f ->
      val app = IconPackComponent("com.nowcoder.app.florida")
      val prefix = "res/drawable-xxhdpi/${app.packageName}/"
      val layers = mapOf(
        "${prefix}0.png" to png(Color.RED),
        "${prefix}1.png" to png(Color.BLUE),
      )
      val native = f.pack(layers)
      assertEquals(IconPackFormat.MIUI_ICONS, native.format)
      val captured = CustomIconResources.select(f.resources(native), listOf(app.packageName))
      CustomIconStore.save(f.context, app.packageName, "Nowcoder", native, native.entries.single(), captured)

      val classic = f.pack(mapOf(
        "appfilter.xml" to """
          <resources><item component="ComponentInfo{${target.flattened}}" drawable="art"/></resources>
        """.trimIndent().toByteArray(),
        "res/drawable-nodpi/art.png" to png(Color.GREEN),
      ))
      assertEquals(IconPackFormat.CLASSIC_ZIP, classic.format)
      for (targets in listOf(listOf(app, target), listOf(app))) {
        val handoff = IconPackHandoffs.prepare(f.context, f.repository, f.previews, classic, targets,
          IconPackAppearance.SYSTEM, includePalette = false, completeSystemIconConfig = false)
        try {
          assertEquals("CONVERTED_CLASSIC_ZIP", handoff.convertedOrigin)
          val resources = f.handoffResources(handoff.file)
          layers.forEach { (path, bytes) -> assertArrayEquals(path, bytes, resources.getValue(path)) }
          assertFalse("res/drawable-xxhdpi/${app.packageName}.png" in resources)
          if (target in targets) {
            val path = "res/drawable-xxhdpi/${nativeLauncherActivityName(target)}.png"
            assertTrue(path, path in resources)
            assertTrue("nightmode/$path", "nightmode/$path" in resources)
          }
        } finally { handoff.file.delete() }
      }
    }
  }

  @Test fun cancelledReplacementKeepsThePreviousChoiceAndItsResources() {
    Fixture().use { f ->
      val pack = f.pack(NativeDayNightIcon.entries(source.packageName, png(Color.RED), png(Color.BLUE)))
      val resources = CustomIconResources.select(f.resources(pack), listOf(source.packageName))
      val saved = CustomIconStore.save(f.context, target.packageName, "Target", pack, pack.entries.first(), resources)
      try {
        CustomIconStore.save(f.context, target.packageName, "Target", pack, pack.entries.first(),
          mapOf("res/drawable/custom.icon.png" to png(Color.GREEN))) { throw CancellationException("fixture") }
        fail("Expected cancellation")
      } catch (_: CancellationException) {}
      assertEquals(listOf(saved), CustomIconStore.selections(f.context))
      assertEquals(resources.keys, CustomIconStore.read(f.context).resources.getValue(target.packageName).keys)
      assertEquals(1, File(f.context.filesDir, "custom-icons").listFiles().orEmpty().size)
    }
  }

  @Test fun corruptDurableResourceFailsClosedInsteadOfSilentlyChangingTheIcon() {
    Fixture().use { f ->
      val pack = f.pack(NativeDayNightIcon.entries(source.packageName, png(Color.RED), png(Color.BLUE)))
      val saved = CustomIconStore.save(f.context, target.packageName, "Target", pack, pack.entries.first(),
        CustomIconResources.select(f.resources(pack), listOf(source.packageName)))
      File(f.context.filesDir, "custom-icons/${saved.token}.zip").writeBytes(byteArrayOf(1, 2, 3))
      try { CustomIconStore.read(f.context); fail("Expected integrity failure") }
      catch (_: IllegalArgumentException) {}
      assertEquals(listOf(saved), CustomIconStore.selections(f.context))
    }
  }

  @Test fun controllerResolvesBothDirectionsOnlyAfterTheResourceOperationSucceeds() = runBlocking {
    Fixture().use { f ->
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
      var preserved = setOf(target.packageName, "com.keihome.untouched")
      val controller = CustomIconsController(f.context, scope, f.repository, f.previews,
        onCustomSaved = { preserved = preserved - it }, onOriginalPreserved = { preserved = preserved + it })
      try {
        withTimeout(10_000) { controller.state.first { it.ready } }
        val pack = f.pack(NativeDayNightIcon.entries(source.packageName, png(Color.RED), png(Color.BLUE)))
        controller.save(target.packageName, "Target", pack, pack.entries.first())
        val result = withTimeout(30_000) { controller.state.first { it.saved == 1L || it.failed && !it.busy } }
        assertFalse("Saving a source snapshot failed", result.failed)
        assertEquals(setOf("com.keihome.untouched"), preserved)
        assertEquals(target.packageName, CustomIconStore.selections(f.context).single().packageName)
        controller.remove(target.packageName, preserveOriginal = true)
        withTimeout(10_000) { controller.state.first { !it.busy } }
        assertTrue("Removing a saved choice must not be blocked by an inventory refresh",
          CustomIconStore.selections(f.context).isEmpty())
        assertEquals(setOf(target.packageName, "com.keihome.untouched"), preserved)
      } finally { scope.cancel() }
    }
  }

  @Test fun failedSourceCaptureDoesNotDisableAnExistingPreservedChoice() = runBlocking {
    Fixture().use { f ->
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
      var removed = false
      val controller = CustomIconsController(f.context, scope, f.repository, f.previews,
        onCustomSaved = { removed = true }, onOriginalPreserved = {})
      try {
        withTimeout(10_000) { controller.state.first { it.ready } }
        val pack = f.pack(NativeDayNightIcon.entries(source.packageName, png(Color.RED), png(Color.BLUE)))
        (pack.source as IconPackSource.Imported).record.originalFile.delete()
        controller.save(target.packageName, "Target", pack, pack.entries.first())
        withTimeout(10_000) { controller.state.first { it.failed && !it.busy } }
        assertFalse(removed)
        assertTrue(CustomIconStore.selections(f.context).isEmpty())
      } finally { scope.cancel() }
    }
  }

  private class Fixture : AutoCloseable {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "custom-test-${UUID.randomUUID()}"
    private val root = File(base.cacheDir, id).apply { mkdirs() }
    val context = object : ContextWrapper(base) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
      override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        base.getSharedPreferences("$id-$name", mode)
    }
    val repository = IconPackRepository(context)
    val previews = IconPackPreviewRepository(context)
    private val store = IconPackStore(File(root, "imports"))
    fun pack(entries: Map<String, ByteArray>): IconPackDescriptor {
      val file = File(root, "${UUID.randomUUID()}.zip")
      ZipOutputStream(file.outputStream()).use { zip ->
        entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
      }
      val record = file.inputStream().use { store.import(it, file.name, Build.VERSION.SDK_INT).record }
      val index = IconPackArchive().inspect(record.originalFile, File(root, "scratch-${UUID.randomUUID()}.icons"), Build.VERSION.SDK_INT)
      return IconPackDescriptor(record.id, "Fixture pack", index.format, IconPackSource.Imported(record),
        index.entries, directives = index.directives)
    }
    fun resources(pack: IconPackDescriptor) = ZipFile((pack.source as IconPackSource.Imported).record.originalFile).use { zip ->
      zip.entries().asSequence().filterNot { it.isDirectory }
        .associate { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }
    }
    fun handoffResources(theme: File): Map<String, ByteArray> {
      val module = File(root, "handoff.icons")
      ZipFile(theme).use { zip -> zip.getInputStream(zip.getEntry("icons")).use { input ->
        module.outputStream().use { input.copyTo(it) }
      } }
      return ZipFile(module).use { zip -> zip.entries().asSequence().filterNot { it.isDirectory }
        .associate { it.name to zip.getInputStream(it).use { input -> input.readBytes() } } }
    }
    override fun close() {
      base.deleteSharedPreferences("$id-custom_icons_v1")
      root.deleteRecursively()
    }
  }

  private fun png(color: Int): ByteArray {
    val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    return try { ByteArrayOutputStream().use { out ->
      image.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray()
    } } finally { image.recycle() }
  }
}
