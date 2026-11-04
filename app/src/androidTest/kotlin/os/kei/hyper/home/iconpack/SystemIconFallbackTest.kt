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
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.xposed.ConvertedIconArchive
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.status.NativeIconImportReport

/** No system settings writes or theme application; reads the exact target's resources. */
@RunWith(AndroidJUnit4::class)
class SystemIconFallbackTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val camera = IconPackComponent("com.android.camera")

  @Test fun anOlderAppliedComponentKeepsItsSourceAfterRegeneratingTheSamePack() {
    val prefix = "fallback-history-${System.nanoTime()}"
    val local = os.kei.hyper.home.status.IconImportLocalStore(context, prefix, "$prefix.")
    val source = "coloros:classic:SYSTEM:test:system-fallback-v1:DEFAULT"
    val old = java.util.UUID.randomUUID().toString()
    val newer = java.util.UUID.randomUUID().toString()
    try {
      context.getSharedPreferences("$prefix.native_icon_associations", 0).edit().putString(source, old).commit()
      val mutation = local.accept(NativeIconImportReport(
        java.util.UUID.randomUUID().toString(), android.os.Process.myUid() / 100000,
        source, "IMPORTED", newer))
      assertTrue(mutation.publish)
      assertEquals(newer, local.snapshot().associations[source])
      assertEquals(source, local.snapshot().sources[old])
      assertEquals(source, local.snapshot().sources[newer])
    } finally {
      os.kei.hyper.home.data.AppLocalStorage.open(context, prefix).close()
      com.tencent.mmkv.MMKV.removeStorage(prefix)
      for (name in listOf("native_icon_associations", "native_icon_sources", "native_icon_operation", "native_icon_auto_navigation"))
        context.deleteSharedPreferences("$prefix.$name")
    }
  }

  @Test fun allThreeStylesDifferAndNeverReplaceUserAppsOrFreezeLiveIcons() {
    val images = SystemIconFallback.entries.map { style ->
      val renderer = SystemIconFallbackRenderer(context, style)
      assertTrue(renderer.isSystem(camera))
      assertFalse(renderer.isSystem(IconPackComponent(context.packageName)))
      assertNull(renderer.render(IconPackComponent(context.packageName), 192) {})
      assertNull(renderer.render(IconPackComponent("com.android.deskclock"), 192) {})
      assertNull(renderer.render(IconPackComponent("com.android.calendar"), 192) {})
      requireNotNull(renderer.render(camera, 192) {}).also {
        assertEquals("Native icon shape must preserve transparent corners", 0, Color.alpha(it.getPixel(0, 0)))
        val file = File(context.cacheDir, "fallback-qa/${style.name}.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { output -> assertTrue(it.compress(Bitmap.CompressFormat.PNG, 100, output)) }
      }
    }
    try {
      assertFalse(images[0].sameAs(images[1]))
      assertFalse(images[0].sameAs(images[2]))
      assertFalse(images[1].sameAs(images[2]))
    } finally { images.forEach(Bitmap::recycle) }
  }

  @Test fun defaultUsesModernSystemArtworkInsteadOfBoundlessThemeAndSecurityIcons() {
    for (pkg in listOf("com.android.thememanager", "com.miui.securitycenter")) {
      val actual = requireNotNull(SystemIconFallbackRenderer(context, SystemIconFallback.DEFAULT)
        .render(IconPackComponent(pkg), 192) {})
      val flat = requireNotNull(SystemIconFallbackRenderer(context, SystemIconFallback.BOUNDLESS)
        .render(IconPackComponent(pkg), 192) {})
      try {
        var changed = 0
        // Compare the interior, so merely adding a mask cannot satisfy this regression.
        for (y in 24 until 168) for (x in 24 until 168) {
          val modern = actual.getPixel(x, y)
          val legacy = flat.getPixel(x * flat.width / 192, y * flat.height / 192)
          val distance = kotlin.math.abs(Color.red(modern) - Color.red(legacy)) +
            kotlin.math.abs(Color.green(modern) - Color.green(legacy)) +
            kotlin.math.abs(Color.blue(modern) - Color.blue(legacy))
          if (distance > 90) changed++
        }
        assertTrue("$pkg must use modern native artwork, not Boundless",
          changed > 144 * 144 / 10)
        val file = File(context.cacheDir, "fallback-qa/$pkg-default.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(actual.compress(Bitmap.CompressFormat.PNG, 100, it)) }
      } finally { actual.recycle(); flat.recycle() }
    }
  }

  @Test fun nativeArtworkAndUnknownDynamicProgramsRemainByteIdenticalWhileMissingSystemAppsAreAdded() = runBlocking {
    val directory = File(context.cacheDir, "fallback-fixture-${System.nanoTime()}").apply { mkdirs() }
    val source = File(directory, "native-icons.zip")
    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
    val png = try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
      finally { bitmap.recycle() }
    val originals = mapOf(
      "res/drawable-xxhdpi/com.android.camera.png" to png,
      "fancy_icons/com.miui.notes/manifest.xml" to
        "<Icon version=\"1\"><Image src=\"art.png\"/></Icon>".toByteArray(),
      "fancy_icons/com.miui.notes/art.png" to png,
    )
    ZipOutputStream(source.outputStream()).use { zip -> originals.forEach { (path, bytes) ->
      zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
    } }
    val store = IconPackStore(File(directory, "store"))
    try {
      val record = source.inputStream().use { store.import(it, source.name).record }
      val index = IconPackArchive().inspect(record.originalFile, File(directory, "inner.zip"))
      val pack = IconPackDescriptor(record.id, "Fallback fixture", index.format, IconPackSource.Imported(record), index.entries)
      for (style in SystemIconFallback.entries) {
        val exported = IconPackExporter(context, IconPackLimits()).export(pack, listOf(camera,
          IconPackComponent("com.miui.notes"), IconPackComponent("com.android.settings"),
          IconPackComponent(context.packageName)), false, 192, style) {}
        try {
          assertTrue(exported.preservesNativeResources)
          ZipFile(exported.archive).use { zip ->
            for ((path, bytes) in originals) assertArrayEquals(bytes, zip.getInputStream(zip.getEntry(path)).use { it.readBytes() })
            assertNull(zip.getEntry("res/drawable-xxhdpi/com.miui.notes.png"))
            assertNull(zip.getEntry("res/drawable-xxhdpi/${context.packageName}.png"))
            assertNotNull(zip.getEntry("res/drawable-xxhdpi/com.android.settings.png"))
            assertNotNull(zip.getEntry("fancy_icons/com.android.settings/manifest.xml"))
            val manifest = SystemIconFallbackManifest.decode(zip.getInputStream(
              zip.getEntry(SystemIconFallbackManifest.PATH)).use { it.readBytes() })
            assertEquals(style, manifest.style)
            assertEquals(setOf("com.android.settings"), manifest.names)
          }
        } finally { exported.archive.delete() }
      }
    } finally { directory.deleteRecursively() }
  }

  @Test fun colorOsConversionReallyIncludesNativeDefaultFallbacksAndRetainsItsRedrawnCamera() = runBlocking {
    val repository = IconPackRepository(context)
    val pack = repository.listBuiltins().packs.single { it.builtinKey == "coloros-classic-art-on" }
    val targets = os.kei.hyper.home.data.iconPackTargets(context)
    assertTrue("HYOS native system apps have no Android launcher activity",
      IconPackComponent("com.android.thememanager") in targets)
    val baseline = IconPackExporter(context, IconPackLimits()).export(pack, targets, false, 192) {}
    val handoff = IconPackHandoffs.prepare(context, repository, IconPackPreviewRepository(context),
      pack, targets, IconPackAppearance.SYSTEM, false, SystemIconFallback.DEFAULT)
    val inner = File.createTempFile("fallback-pair-", ".zip", context.cacheDir)
    try {
      ConvertedIconArchive.inspect(handoff.file, "CONVERTED_CLASSIC_ZIP", context.cacheDir)
      ZipFile(handoff.file).use { mtz ->
        mtz.getInputStream(mtz.getEntry("icons")).use { input ->
          inner.outputStream().use { input.copyTo(it) }
        }
      }
      ZipFile(inner).use { zip ->
        val manifest = SystemIconFallbackManifest.decode(zip.getInputStream(
          zip.getEntry(SystemIconFallbackManifest.PATH)).use { it.readBytes() })
        assertEquals(SystemIconFallback.DEFAULT, manifest.style)
        assertTrue("Real launcher inventory must include uncovered built-in apps", manifest.names.isNotEmpty())
        assertNotNull("Native system frontends must receive an explicit themed descriptor",
          zip.getEntry("fancy_icons/com.android.thememanager/manifest.xml"))
        assertFalse("ColorOS camera must retain its own redraw", "com.android.camera" in manifest.names)
        ZipFile(baseline.archive).use { old ->
          val name = "res/drawable-xxhdpi/com.android.camera.png"
          assertArrayEquals(old.getInputStream(old.getEntry(name)).use { it.readBytes() },
            zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
        }
        val directory = File(context.cacheDir, "fallback-qa").apply { mkdirs() }
        handoff.file.copyTo(File(directory, "default-coloros.mtz"), overwrite = true)
        File(directory, "manifest.txt").writeBytes(manifest.encode())
      }
    } finally { baseline.archive.delete(); handoff.file.delete(); inner.delete() }
  }
}
