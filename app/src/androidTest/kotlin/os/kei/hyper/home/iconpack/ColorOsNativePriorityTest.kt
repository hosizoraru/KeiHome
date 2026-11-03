package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.xposed.ConvertedIconArchive

/** Explicit AVD diagnostic against the installed Via and Apple Music resources; no theme apply. */
@RunWith(AndroidJUnit4::class)
class ColorOsNativePriorityTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val apple = IconPackComponent("com.apple.android.music")
  private val via = IconPackComponent("mark.via")

  @Before fun requireExplicitDiagnostic() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith(javaClass.name) == true)
    assumeTrue(runCatching { context.packageManager.getPackageInfo(apple.packageName, 0).versionName == "7.0.0" }.getOrDefault(false))
  }

  private fun entry(pack: IconPackDescriptor, target: IconPackComponent) =
    pack.entries.single { it.component == target }

  @Test fun actualOfficialNightPairWinsOverDownloadedArtButNotExplicitPackArt() = runBlocking {
    val pack = requireNotNull(IconPackRepository(context).runtimeFallback())
    val original = ColorOsFallbackRenderer(context)
    assertTrue("The installed Apple Music has official day/night resources", original.hasNativeDayNight(apple))
    assertFalse("Plain night dimming must not count as Via night artwork", original.hasNativeDayNight(via))
    val profile = requireNotNull(pack.colorOsProfile)
    val bytes = profile.read(profile.maskPath)
    val shape = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    try {
      for (dark in listOf(false, true)) {
        IconPackRenderer(context).open(pack, dark).use { session ->
          assertTrue(session.prefersAppIcon(apple, entry(pack, apple)) {})
          assertFalse(session.prefersAppIcon(via, entry(pack, via)) {})
          val actual = requireNotNull(session.render(entry(pack, apple), 96))
          val expected = requireNotNull(original.render(apple, dark, 96, shape))
          try { assertTrue("Official artwork must survive", actual.sameAs(expected)) }
          finally { actual.recycle(); expected.recycle() }
        }
        val explicit = pack.copy(colorOsProfile = null)
        IconPackRenderer(context).open(explicit, dark, pack).use { session ->
          assertFalse(session.prefersAppIcon(apple, entry(pack, apple)) {})
          val actual = requireNotNull(session.render(entry(pack, apple), 96))
          val supplied = IconPackRenderer(context).open(explicit, dark).use { requireNotNull(it.render(entry(pack, apple), 96)) }
          try { assertTrue("Explicit pack art must outrank the official icon", actual.sameAs(supplied)) }
          finally { actual.recycle(); supplied.recycle() }
        }
      }
    } finally { shape.recycle() }
  }

  @Test fun officialMonochromeIsIndependentOfTheColoredFallback() = runBlocking {
    val pack = requireNotNull(IconPackRepository(context).runtimeFallback())
    val profile = requireNotNull(pack.colorOsProfile)
    val bytes = profile.read(profile.maskPath)
    val shape = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    try {
      val expected = requireNotNull(ColorOsFallbackRenderer(context).monochrome(apple, false, 96, shape))
      try {
        IconPackRenderer(context).open(pack, false).use { session ->
          val actual = requireNotNull(session.colorOsMonochrome(apple, 96) {})
          try { assertTrue("Official monochrome must precede the downloaded mask", actual.sameAs(expected)) }
          finally { actual.recycle() }
        }
      } finally { expected.recycle() }
    } finally { shape.recycle() }
  }

  @Test fun externalOptInIsScopedAndNativeSuppliedEntriesRemainByteIdentical() = runBlocking {
    val bundled = requireNotNull(IconPackRepository(context).runtimeFallback())
    val fallback = bundled.copy(entries = bundled.entries.filter { it.component in setOf(apple, via) })
    val empty = fallback.copy(colorOsProfile = null, entries = emptyList())
    IconPackRenderer(context).open(empty, true).use { assertNull(it.renderTarget(null, via, 96) {}) }
    IconPackRenderer(context).open(empty, true, fallback).use { session ->
      for (target in listOf(apple, via)) {
        val actual = requireNotNull(session.renderTarget(null, target, 96) {})
        try { assertEquals(96, actual.width) } finally { actual.recycle() }
      }
    }
    val green = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    val png = try {
      green.eraseColor(Color.GREEN)
      ByteArrayOutputStream().also { assertTrue(green.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
    } finally { green.recycle() }
    val supplied = NativeDayNightIcon.entries(via.packageName, png, png)
    val folder = File(context.cacheDir, "native-fallback-priority-test").apply { mkdirs() }
    try {
      val archive = File(folder, "source.zip")
      ZipOutputStream(archive.outputStream()).use { output ->
        for ((path, data) in supplied) {
          output.putNextEntry(ZipEntry(path)); output.write(data); output.closeEntry()
        }
      }
      val record = archive.inputStream().use { IconPackStore(File(folder, "store")).import(it, "source.zip").record }
      val source = IconPackDescriptor(record.id, "Native fixture", record.format, IconPackSource.Imported(record), emptyList())
      val result = IconPackExporter(context, IconPackLimits()).export(source, listOf(apple, via), false, 96,
        SystemIconFallback.DEFAULT, colorOsFallback = fallback) {}
      try {
        ZipFile(result.archive).use { zip ->
          for ((path, data) in supplied) assertArrayEquals(path, data, zip.getInputStream(requireNotNull(zip.getEntry(path))).use { it.readBytes() })
          assertNotNull(zip.getEntry("fancy_icons/${apple.packageName}/night.png"))
          val manifest = SystemIconFallbackManifest.decode(zip.getInputStream(requireNotNull(zip.getEntry(SystemIconFallbackManifest.PATH))).use { it.readBytes() })
          assertTrue(manifest.colorOsFallback)
        }
      } finally { result.archive.delete() }
    } finally { folder.deleteRecursively() }
  }

  @Test fun normalColorOsHandoffStillCarriesTheOfficialFallbackMonetMask() = runBlocking {
    val repository = IconPackRepository(context)
    val bundled = requireNotNull(repository.runtimeFallback())
    val pack = bundled.copy(entries = bundled.entries.filter { it.component == apple })
    val handoff = IconPackHandoffs.prepare(context, repository, IconPackPreviewRepository(context), pack,
      listOf(apple), IconPackAppearance.SYSTEM, includePalette = false, systemFallback = SystemIconFallback.DEFAULT)
    val nested = File(context.cacheDir, "priority-monet-icons.zip")
    try {
      ConvertedIconArchive.inspect(handoff.file, "CONVERTED_CLASSIC_ZIP", context.cacheDir)
      ZipFile(handoff.file).use { mtz ->
        mtz.getInputStream(requireNotNull(mtz.getEntry("icons"))).use { input -> nested.outputStream().use { input.copyTo(it) } }
      }
      ZipFile(nested).use { icons ->
        val root = "fancy_icons/${apple.packageName}"
        assertNotNull("Official fallback mask must survive the normal ColorOS style", icons.getEntry("$root/mask.png"))
        val manifest = icons.getInputStream(requireNotNull(icons.getEntry("$root/manifest.xml"))).bufferedReader().use { it.readText() }
        assertTrue(manifest.contains(NativePaletteIcon.ENABLED_KEY))
      }
    } finally { handoff.file.delete(); nested.delete() }
  }

  @Test fun completeCloudCatalogProducesAnAdmissibleClassicHandoff(): Unit = runBlocking {
    val repository = IconPackRepository(context)
    val pack = repository.listBuiltins().packs.single { it.builtinKey == "coloros-classic-art-on" }
    val handoff = IconPackHandoffs.prepare(context, repository, IconPackPreviewRepository(context), pack,
      listOf(apple, via), IconPackAppearance.SYSTEM, includePalette = false, systemFallback = SystemIconFallback.DEFAULT)
    try { ConvertedIconArchive.inspect(handoff.file, "CONVERTED_CLASSIC_ZIP", context.cacheDir) }
    finally { handoff.file.delete() }
  }
}
