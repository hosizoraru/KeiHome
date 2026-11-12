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

  /** Opt-in coverage for a local report without committing third-party artwork. */
  @Test fun suppliedNativeArchiveImportsAndCopiesWithoutChangingItsPayload() {
    // Accept either a standalone icons ZIP or an MTZ containing the exact root icons.
    val path = System.getenv("KEIHOME_TEST_ICON_ARCHIVE")?.takeIf { it.isNotBlank() }
    org.junit.Assume.assumeTrue(path != null)
    val source = File(requireNotNull(path))
    assertTrue("KEIHOME_TEST_ICON_ARCHIVE must point to a readable file", source.isFile && source.canRead())
    val nested = temporary.newFile()
    val originalHash = sha256(source)
    val index = IconPackArchive().inspect(source, nested)
    assertTrue(index.format in setOf(IconPackFormat.MIUI_ICONS, IconPackFormat.MIUI_MTZ))
    assertTrue(index.entries.isNotEmpty())
    val nativeHash = sha256(if (index.format == IconPackFormat.MIUI_MTZ) nested else source)
    assertEquals(nativeHash, index.nativeIconsSha256)
    val store = IconPackStore(temporary.newFolder())
    val imported = source.inputStream().use { store.import(it, source.name) }
    val repeated = source.inputStream().use { store.import(it, "renamed.zip") }
    assertFalse(imported.alreadyPresent)
    assertTrue(repeated.alreadyPresent)
    assertEquals(imported.record.id, repeated.record.id)
    assertEquals(index.format, imported.record.format)
    assertEquals(originalHash, sha256(imported.record.originalFile))
    assertEquals(nativeHash, imported.record.nativeIconsSha256)
    if (index.format == IconPackFormat.MIUI_MTZ) {
      assertEquals(nativeHash, sha256(requireNotNull(imported.record.iconsFile)))
    }
    val output = temporary.newFile()
    val copied = IconPackArchive().copyNativeModule(imported.record, output, temporary.newFile())
    assertEquals(IconPackFormat.MIUI_ICONS, copied.format)
    assertEquals(index.entries.map { it.nativeName }, copied.entries.map { it.nativeName })
    assertEquals(nativeHash, sha256(output))
    assertEquals(originalHash, sha256(source))
    println("Native archive format=${index.format} entries=${index.entries.size} notices=${index.notices} " +
      "sourceSha256=$originalHash nativeSha256=$nativeHash")
  }

  @Test fun standaloneModuleKeepsDirectoryEntriesMamlAndOpaqueFancyAssets() {
    val manifest = ("<Icon version='2' useVariableUpdater='DateTime'>" +
      "<VariableBinders><BroadcastBinder action='android.intent.action.DATE_CHANGED'/></VariableBinders>" +
      "<Var name='night'><![CDATA[ifelse(#value,1,0)]]></Var>" +
      "<Image src='common.png'/><Image src='dark.png'/></Icon>").toByteArray()
    // Asset filenames need not reveal their encoding; native MAML owns their decoding.
    val opaqueAsset = "RIFF\u0004\u0000\u0000\u0000WEBP".toByteArray()
    val native = zip(
      "fancy_icons/" to byteArrayOf(), "fancy_icons/org.example.Calendar/" to byteArrayOf(),
      "fancy_icons/org.example.Calendar/manifest.xml" to manifest,
      "fancy_icons/org.example.Calendar/common.png" to png,
      "fancy_icons/org.example.Calendar/dark.png" to opaqueAsset,
      "res/drawable-xxhdpi/org.example.png" to png,
      "transform_config.xml" to "<IconTransform><ScaleX>1.0</ScaleX></IconTransform>".toByteArray())
    val store = IconPackStore(temporary.newFolder())
    val record = store.import(native.inputStream(), "icons.zip").record
    val output = temporary.newFile()
    val index = IconPackArchive().copyNativeModule(record, output, temporary.newFile())
    assertEquals(IconPackFormat.MIUI_ICONS, index.format)
    assertEquals(setOf("org.example", "org.example.Calendar"), index.entries.map { it.nativeName }.toSet())
    assertTrue(IconPackNotice.NATIVE_ANIMATION_PREVIEW_UNSUPPORTED in index.notices)
    assertArrayEquals(native, output.readBytes())
    assertArrayEquals(manifest, IconPackArchive.readEntry(output, "fancy_icons/org.example.Calendar/manifest.xml", 4096))
    assertArrayEquals(opaqueAsset, IconPackArchive.readEntry(output, "fancy_icons/org.example.Calendar/dark.png", 4096))
  }

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

  @Test fun classicConversionKeepsItsSourceTitleAndAuthor() {
    val index = inspect(file(zip("appfilter.xml" to filter("day"),
      "res/drawable/day.png" to png,
      "description.xml" to ("<MIUI-Theme><title>Local style &amp; artwork</title>" +
        "<author>Source authors</author><description>User-supplied conversion</description></MIUI-Theme>").toByteArray())))
    assertEquals(IconPackFormat.CLASSIC_ZIP, index.format)
    assertEquals("Local style & artwork", index.description.title)
    assertEquals("Source authors", index.description.author)
    assertEquals("User-supplied conversion", index.description.description)
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

  @Test fun enforcesEntryExpansionAndSharedNestedEntryBudget() {
    val archive = file(zip("res/drawable/org.example.png" to png, "extra" to ByteArray(2048)))
    assertThrows(IOException::class.java) { inspect(archive, IconPackLimits(maxEntries = 1)) }
    assertThrows(IOException::class.java) { inspect(archive, IconPackLimits(maxEntryBytes = 1024)) }
    assertThrows(IOException::class.java) { inspect(archive, IconPackLimits(maxCompressionRatio = 2)) }
    val inner = zip("res/drawable/org.example.png" to png)
    val mtz = file(zip("icons" to inner))
    assertThrows(IOException::class.java) { inspect(mtz, IconPackLimits(maxEntries = 1)) }
    assertThrows(IOException::class.java) { inspect(mtz, IconPackLimits(maxExpandedBytes = inner.size.toLong() - 1)) }
  }

  @Test fun nestedContainerDoesNotConsumeFinalResourceBudgetAndHandoffStaysLossless() {
    val padding = ByteArray(4096)
    val extra = ByteArray(64)
    val inner = zip("res/drawable/org.example.png" to png, "opaque" to padding)
    val outer = zip("icons" to inner, "extra" to extra)
    val cap = (png.size + padding.size + extra.size).toLong()
    assertTrue(inner.size + extra.size <= cap)
    assertTrue(inner.size + extra.size + png.size + padding.size > cap)
    val limits = IconPackLimits(maxExpandedBytes = cap, maxEntryBytes = padding.size.toLong())
    val store = IconPackStore(temporary.newFolder(), limits)
    val record = store.import(outer.inputStream(), "bounded.mtz").record
    val output = temporary.newFile()
    val index = IconPackArchive(limits).copyNativeModule(record, output, temporary.newFile())
    assertEquals(IconPackFormat.MIUI_MTZ, record.format)
    assertEquals(IconPackFormat.MIUI_ICONS, index.format)
    assertArrayEquals(outer, record.originalFile.readBytes())
    assertArrayEquals(inner, requireNotNull(record.iconsFile).readBytes())
    assertArrayEquals(inner, output.readBytes())
  }

  @Test fun outerPayloadAndInnerResourcesStillShareOneFinalResourceBudget() {
    val padding = ByteArray(4096)
    val extra = ByteArray(64)
    val cap = (png.size + padding.size + extra.size).toLong()
    val limits = IconPackLimits(maxExpandedBytes = cap, maxEntryBytes = padding.size + 1L)
    fun outer(innerPadding: ByteArray, outerExtra: ByteArray) = file(zip(
      "icons" to zip("res/drawable/org.example.png" to png, "opaque" to innerPadding),
      "extra" to outerExtra))
    assertEquals(IconPackFormat.MIUI_MTZ, inspect(outer(padding, extra), limits).format)
    // Each level fits on its own: the combined final resources must still reject one extra byte.
    assertThrows(IOException::class.java) { inspect(outer(padding, ByteArray(extra.size + 1)), limits) }
    assertThrows(IOException::class.java) { inspect(outer(ByteArray(padding.size + 1), extra), limits) }
    assertThrows(IOException::class.java) { inspect(outer(ByteArray(cap.toInt()), byteArrayOf()),
      limits.copy(maxEntryBytes = cap)) }
  }

  @Test fun intermediateExemptionIsExactAndDoesNotApplyToApkOrInnerPayloads() {
    val inner = zip("res/drawable/org.example.png" to png)
    val cap = inner.size.toLong()
    val limits = IconPackLimits(maxExpandedBytes = cap)
    assertEquals(IconPackFormat.MIUI_MTZ, inspect(file(zip("icons" to inner)), limits).format)
    for (name in listOf("icons.zip", "assets/icons")) {
      assertThrows(IOException::class.java) { inspect(file(zip(
        "res/drawable/org.example.png" to png, name to inner)), limits) }
    }
    assertThrows(IOException::class.java) { inspect(file(zip("icons" to inner,
      "AndroidManifest.xml" to byteArrayOf(3), "resources.arsc" to byteArrayOf(2))), limits) }
    // An inner entry named icons is an ordinary opaque resource, never another exempt container.
    val nested = zip("res/drawable/org.example.png" to png, "icons" to ByteArray(4096))
    assertTrue(nested.size < 4096)
    assertThrows(IOException::class.java) { inspect(file(zip("icons" to nested)),
      limits.copy(maxExpandedBytes = 4096)) }
  }

  @Test fun nestedContainersRetainInputRatioCrcAndCancellationChecks() {
    val inner = zip("res/drawable/org.example.png" to png)
    val source = file(zip("icons" to inner))
    val largerInner = zip(*(0..19).map { "res/drawable/org.example$it.png" to png }.toTypedArray())
    val smallerOuter = zip("icons" to largerInner)
    assertTrue(smallerOuter.size < largerInner.size - 1)
    assertThrows(IOException::class.java) { inspect(file(smallerOuter),
      IconPackLimits(maxInputBytes = largerInner.size - 1L)) }
    val outerRatioFailure = assertThrows(IconPackException::class.java) {
      inspect(file(zip("icons" to ByteArray(8192))), IconPackLimits(maxCompressionRatio = 2))
    }
    assertEquals("Archive expansion limit exceeded", outerRatioFailure.message)
    val ratioBomb = zip("res/drawable/org.example.png" to png, "opaque" to ByteArray(8192))
    val innerRatioFailure = assertThrows(IconPackException::class.java) {
      inspect(file(zip("icons" to ratioBomb, stored = true)), IconPackLimits(maxCompressionRatio = 2))
    }
    assertEquals("Archive expansion limit exceeded", innerRatioFailure.message)
    // Mutate only the outer central-directory CRC: its icons bytes remain a valid inner ZIP.
    val corrupt = zip("icons" to inner)
    val central = (0..corrupt.size - 4).first { i ->
      corrupt[i] == 0x50.toByte() && corrupt[i + 1] == 0x4b.toByte() &&
        corrupt[i + 2] == 0x01.toByte() && corrupt[i + 3] == 0x02.toByte()
    }
    corrupt[central + 16] = (corrupt[central + 16].toInt() xor 1).toByte()
    assertThrows(IOException::class.java) { inspect(file(corrupt)) }
    val staging = temporary.newFile()
    var completedCopyChecks = 0
    assertThrows(CancellationException::class.java) {
      IconPackArchive().inspect(source, staging, checkCancelled = {
        // Allow the copy loop's EOF check, then cancel at the first inner entry.
        if (staging.length() == inner.size.toLong() && ++completedCopyChecks == 2) throw CancellationException()
      })
    }
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
  private fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = false) = ByteArrayOutputStream().also { output ->
    ZipOutputStream(output).use { zip ->
      entries.forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name).apply {
          if (stored) {
            method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
            crc = CRC32().also { it.update(bytes) }.value
          }
        })
        zip.write(bytes); zip.closeEntry()
      }
    }
  }.toByteArray()
  private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH2kAAAAASUVORK5CYII=")


  @Test fun indexesQualifiedNativeStemsWithoutTreatingInvalidSeparatorsAsIcons() {
    val qualified = "com.example.debug#com.example.Main"
    val index = inspect(file(zip("res/drawable-xxhdpi/$qualified.png" to png,
      "res/drawable-xxhdpi/com.example#com.other#Main.png" to png)))
    assertEquals(listOf(qualified), index.entries.map { it.nativeName })
    assertThrows(IOException::class.java) {
      inspect(file(zip("res/drawable-xxhdpi/com.example#../Main.png" to png)))
    }
  }
}
