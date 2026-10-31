package os.kei.hyper.home.iconpack

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IconPackArchiveTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun nativeNightPrefixSelectsItsOwnArtworkAndKeepsOriginalBytes() {
    val index = inspect(file(zip("res/drawable-xxhdpi/org.example.png" to png,
      "nightmode/res/drawable-xxhdpi/org.example.png" to png)))
    val variants = (index.entries.single().dark as IconPackArtwork.Archive).variants
    assertEquals(2, variants.size)
    assertEquals(IconNightMode.DARK, variants.single { it.path.startsWith("nightmode/") }.qualifiers.night)
    assertEquals(IconNightMode.ANY, variants.single { !it.path.startsWith("nightmode/") }.qualifiers.night)
  }

  @Test fun readsNativeMtzWithoutRewritingOriginalOrExtractingEntryPaths() {
    val inner = zip("res/drawable-xxhdpi/org.example.png" to png,
      "res/drawable-night-xxhdpi/org.example.png" to png)
    val outer = zip("description.xml" to "<MIUI-Theme><title>Example &amp; icons</title><author>Creator</author><version>2</version></MIUI-Theme>".toByteArray(),
      "icons" to inner, "preview/preview_icons_0.jpg" to byteArrayOf(1, 2), "wallpaper/default.jpg" to byteArrayOf(3))
    val source = file(outer)
    val copiedIcons = File(temporary.root, "fixed-icons")
    val index = IconPackArchive().inspect(source, copiedIcons)
    assertEquals(IconPackFormat.MIUI_MTZ, index.format)
    assertEquals("Example & icons", index.description.title)
    assertEquals("Creator", index.description.author)
    assertEquals("preview/preview_icons_0.jpg", index.previews.single().path)
    assertEquals("org.example", index.entries.single().nativeName)
    assertEquals(2, (index.entries.single().dark as IconPackArtwork.Archive).variants.size)
    assertEquals(sha256(copiedIcons), index.nativeIconsSha256)
    assertArrayEquals(inner, copiedIcons.readBytes())
    assertArrayEquals(outer, source.readBytes())
    assertFalse(File(temporary.root, "res").exists())
    assertFalse(File(temporary.root, "wallpaper").exists())
  }

  @Test fun classicZipResolvesModeSpecificFiltersAndKeepsMissingArtworkExplicit() {
    val source = file(zip(
      "res/xml/appfilter.xml" to filter("day"), "res/xml-night/appfilter.xml" to filter("night"),
      "res/drawable/day.png" to png, "res/drawable-night-xxhdpi/night.png" to png,
      "res/drawable/icon.xml" to "<vector/>".toByteArray()))
    val index = inspect(source)
    assertEquals(IconPackFormat.CLASSIC_ZIP, index.format)
    val entry = index.entries.single()
    assertEquals(IconPackComponent("org.example", "org.example.Main"), entry.component)
    assertEquals("res/drawable/day.png", (entry.light as IconPackArtwork.Archive).variants.single().path)
    assertEquals("res/drawable-night-xxhdpi/night.png", (entry.dark as IconPackArtwork.Archive).variants.single().path)
    val missing = inspect(file(zip("assets/appfilter.xml" to filter("missing")))).entries.single()
    assertNull(missing.light)
    assertNull(missing.dark)
  }

  @Test fun nativeHandoffPreservesNightVariantsMasksAndAnimationBytes() {
    val native = zip("res/drawable-xxhdpi/org.example.png" to png,
      "res/drawable-night-xxhdpi/org.example.png" to png, "res/drawable/icon_mask.png" to png,
      "fancy_icons/org.clock/manifest.xml" to "<Icon><Image src='face.png'/></Icon>".toByteArray(),
      "fancy_icons/org.clock/face.png" to png, "transform_config.xml" to "<Config/>".toByteArray())
    val mtz = zip("icons" to native, "description.xml" to "<MIUI-Theme><title>Night</title></MIUI-Theme>".toByteArray())
    for (source in listOf(native, mtz)) {
      val store = IconPackStore(temporary.newFolder())
      val record = store.import(source.inputStream(), "native.zip").record
      val output = temporary.newFile()
      val index = IconPackArchive().copyNativeModule(record, output, temporary.newFile())
      assertEquals(IconPackFormat.MIUI_ICONS, index.format)
      assertArrayEquals(native, output.readBytes())
      assertEquals(record.nativeIconsSha256, index.nativeIconsSha256)
      assertTrue(IconPackNotice.NATIVE_ANIMATION_PREVIEW_UNSUPPORTED in index.notices)
      assertThrows(IOException::class.java) { IconPackArchive().copyNativeModule(record, record.iconsFile ?: record.originalFile, temporary.newFile()) }
    }
  }

  @Test fun compiledApkShapeDefersMappingToAndroidInsteadOfGuessingObfuscatedNames() {
    val index = inspect(file(zip("AndroidManifest.xml" to byteArrayOf(3, 0, 8, 0),
      "resources.arsc" to byteArrayOf(2, 0), "res/hAN.xml" to byteArrayOf(3, 0, 8, 0))))
    assertEquals(IconPackFormat.APK, index.format)
    assertTrue(index.entries.isEmpty())
  }

  @Test fun rejectsUnsafePathsInOuterAndNestedArchives() {
    for (path in listOf("../escape.png", "/absolute.png", "res/../outside", "res//bad", "res/./bad", "C:/bad", "res\\bad", "bad\u0000name")) {
      assertThrows(path, IOException::class.java) { inspect(file(zip(path to png))) }
      assertThrows(path, IOException::class.java) { inspect(file(zip("icons" to zip(path to png)))) }
    }
  }

  @Test fun rejectsDuplicatesFileDirectoryCollisionsAndInvalidNestedFormats() {
    val duplicate = zip("aaa" to byteArrayOf(1), "bbb" to byteArrayOf(2))
    // Patch equally sized names in both local and central headers to construct a duplicate ZIP.
    for (i in 0..duplicate.size - 3) if (duplicate[i] == 98.toByte() && duplicate[i + 1] == 98.toByte() && duplicate[i + 2] == 98.toByte()) {
      duplicate[i] = 97; duplicate[i + 1] = 97; duplicate[i + 2] = 97
    }
    assertThrows(IOException::class.java) { inspect(file(duplicate)) }
    assertThrows(IOException::class.java) { inspect(file(zip("res" to png, "res/drawable/org.example.png" to png))) }
    assertThrows(IOException::class.java) { inspect(file(zip("icons" to "not a zip".toByteArray()))) }
    assertThrows(IOException::class.java) { inspect(file(zip("icons.zip" to zip("res/drawable/org.example.png" to png)))) }
    assertThrows(IOException::class.java) { inspect(file(zip("icons" to zip("icons" to zip("res/drawable/org.example.png" to png))))) }
    assertThrows(IOException::class.java) { inspect(file(byteArrayOf(80, 75, 3, 4))) }
    assertThrows(IOException::class.java) { inspect(file(zip("res/drawable/org.fake.png" to "not png".toByteArray()))) }
  }

  @Test fun validatesCrcRatherThanTrustingCentralDirectorySizes() {
    val bytes = ByteArrayOutputStream().also { output ->
      ZipOutputStream(output).use { zip ->
        val content = "integrity-marker".toByteArray()
        zip.putNextEntry(ZipEntry("res/drawable/org.example.png").apply {
          method = ZipEntry.STORED; size = content.size.toLong(); compressedSize = size
          crc = CRC32().also { it.update(content) }.value
        })
        zip.write(content); zip.closeEntry()
      }
    }.toByteArray()
    val marker = "integrity-marker".toByteArray()
    val offset = (0..bytes.size - marker.size).first { bytes.copyOfRange(it, it + marker.size).contentEquals(marker) }
    bytes[offset] = 0
    assertThrows(IOException::class.java) { inspect(file(bytes)) }
  }

  @Test fun enforcesEntryExpansionAndSharedNestedBudgets() {
    val archive = file(zip("res/drawable/org.example.png" to png, "extra" to ByteArray(2048)))
    assertThrows(IOException::class.java) { inspect(archive, IconPackLimits(maxEntries = 1)) }
    assertThrows(IOException::class.java) { inspect(archive, IconPackLimits(maxEntryBytes = 1024)) }
    assertThrows(IOException::class.java) { inspect(archive, IconPackLimits(maxCompressionRatio = 2)) }
    val inner = zip("res/drawable/org.example.png" to png)
    val mtz = file(zip("icons" to inner))
    assertThrows(IOException::class.java) { inspect(mtz, IconPackLimits(maxEntries = 1)) }
    assertThrows(IOException::class.java) { inspect(mtz, IconPackLimits(maxExpandedBytes = inner.size.toLong() + png.size - 1)) }
  }

  @Test fun importIsAtomicDeduplicatedAndDoesNotTrustDisplayNames() {
    val directory = File(temporary.root, "catalog")
    val store = IconPackStore(directory)
    val bytes = zip("res/drawable/org.example.png" to png)
    val first = store.import(bytes.inputStream(), "../../outside.mtz")
    val second = store.import(bytes.inputStream(), "renamed.zip")
    assertFalse(first.alreadyPresent)
    assertTrue(second.alreadyPresent)
    assertEquals(first.record.id, second.record.id)
    assertArrayEquals(bytes, first.record.originalFile.readBytes())
    assertEquals(1, store.list().records.size)
    assertTrue(first.record.originalFile.canonicalPath.startsWith(directory.canonicalPath + File.separator))
    assertThrows(IOException::class.java) { store.read("../outside") }
    assertThrows(IOException::class.java) { store.import(zip("../escape" to png).inputStream(), "bad.zip") }
    assertThrows(CancellationException::class.java) { store.import(bytes.inputStream(), "cancel.zip", checkCancelled = { throw CancellationException() }) }
    assertEquals(1, directory.listFiles()!!.size)
    assertEquals(first.record.id, store.read(first.record.id).nativeIconsSha256)
  }

  @Test fun storeRejectsOversizeInputUnsupportedApkAndQuotaOverflowWithoutPublishing() {
    val directory = File(temporary.root, "catalog")
    val limited = IconPackStore(directory, IconPackLimits(maxInputBytes = 8))
    assertThrows(IOException::class.java) { limited.import(ByteArray(9).inputStream(), "oversize.zip") }
    assertTrue(directory.listFiles()!!.isEmpty())
    val store = IconPackStore(directory, IconPackLimits(maxStoredPacks = 1))
    val apk = zip("AndroidManifest.xml" to byteArrayOf(3), "resources.arsc" to byteArrayOf(2))
    assertThrows(IOException::class.java) { store.import(apk.inputStream(), "invalid.apk") }
    store.import(zip("res/drawable/org.first.png" to png).inputStream(), "one.zip")
    assertThrows(IOException::class.java) { store.import(zip("res/drawable/org.second.png" to png).inputStream(), "two.zip") }
    assertEquals(1, directory.listFiles()!!.size)
  }

  private fun inspect(file: File, limits: IconPackLimits = IconPackLimits()) =
    IconPackArchive(limits).inspect(file, File(temporary.root, "nested-${System.nanoTime()}"))
  private fun file(bytes: ByteArray) = temporary.newFile().also { it.writeBytes(bytes) }
  private fun filter(name: String) = "<resources><item component='ComponentInfo{org.example/.Main}' drawable='$name'/></resources>".toByteArray()
  private fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { output ->
    ZipOutputStream(output).use { zip ->
      entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
    }
  }.toByteArray()
  private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH2kAAAAASUVORK5CYII=")
}
