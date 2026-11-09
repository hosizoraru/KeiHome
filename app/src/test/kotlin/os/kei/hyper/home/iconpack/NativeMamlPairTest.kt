package os.kei.hyper.home.iconpack

import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeMamlPairTest {
  @get:Rule val temporary = TemporaryFolder()
  private fun source() = javaClass.getResourceAsStream("/native-maml/image-pair.xml")!!.use {
    it.readBytes().toString(Charsets.UTF_8)
  }
  private fun header() = java.nio.ByteBuffer.allocate(24).put(byteArrayOf(
    137.toByte(), 80, 78, 71, 13, 10, 26, 10)).putInt(13)
    .put("IHDR".toByteArray()).putInt(32).putInt(32).array()
  @Test fun recognizesCompleteSourceContractAndOrderIndependentXmlAttributes() {
    assertEquals(NativeMamlPair("iconBg_0.png", "iconBg_1.png"),
      NativeMamlPair.parse(source().toByteArray()))
    assertEquals(NativeMamlPair("glyph_0.png", "glyph_1.png"),
      NativeMamlPair.parse(source().replace("iconBg.png", "glyph.png")
        .replace("x=\"0\" y=\"0\"", "y=\"0\" x=\"0\"").toByteArray()))
  }
  @Test fun unknownProgramsUnsafePathsAlteredModesAndDatesAreNeverReducedToAPair() {
    for (changed in listOf(
      source().replace("iconBg.png", "../iconBg.png"),
      source().replace("srcid=\"1\"", "srcid=\"#date\""),
      source().replace("#__darkmode*255", "#battery_level"),
      source().replace("</Icon>", "<ContentProviderBinder uri=\"content://settings/system\" /></Icon>"),
      source().replace("width=\"168\"", "width=\"8192\""),
      source().replace("<ExternalCommands>", "<ExternalCommands>unexpected"),
      source().replace("action=\"init,resume\"", "action=\"launch\""),
    )) assertNull(NativeMamlPair.parse(changed.toByteArray()))
  }
  @Test fun parsedPairProvidesRealLightAndDarkPathsWithoutAFalseDynamicClaim() {
    val archive = temporary.newFile("icons.zip")
    val root = "fancy_icons/com.example.app/"
    ZipOutputStream(archive.outputStream()).use { out ->
      for ((name, bytes) in mapOf(
        root + "manifest.xml" to source().toByteArray(),
        root + "iconBg_0.png" to header(),
        root + "iconBg_1.png" to header(),
        "fancy_icons/com.example.calendar/manifest.xml" to
          source().replace("srcid=\"1\"", "srcid=\"#date\"").toByteArray(),
      )) {
        out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
      }
    }
    val index = IconPackArchive().inspect(archive, temporary.newFile("nested"))
    val entry = index.entries.single { it.key == "com.example.app" }
    for ((dark, path) in listOf(false to "iconBg_0.png", true to "iconBg_1.png")) {
      val artwork = entry.artwork(dark) as IconPackArtwork.Archive
      assertTrue(artwork.palettePair)
      assertEquals(root + path, artwork.variants.single().path)
    }
    assertNull(index.entries.single { it.key == "com.example.calendar" }.artwork(false))
    val record = StoredIconPack("pair", "icons.zip", 0, index.format, archive, null, archive.length(), index.nativeIconsSha256)
    val copy = temporary.newFile("copied-icons.zip")
    IconPackArchive().copyNativeModule(record, copy, temporary.newFile("copy-nested"))
    ZipFile(copy).use { zip ->
      assertEquals(source(), zip.getInputStream(zip.getEntry(root + "manifest.xml")).use { it.readBytes().toString(Charsets.UTF_8) })
    }
  }
  @Test fun missingDayOrNightRetainsNativeProgramRatherThanInventingArtwork() {
    val archive = temporary.newFile("missing.zip")
    ZipOutputStream(archive.outputStream()).use { out ->
      out.putNextEntry(ZipEntry("fancy_icons/com.example.app/manifest.xml"))
      out.write(source().toByteArray()); out.closeEntry()
    }
    val entry = IconPackArchive().inspect(archive, temporary.newFile("nested")).entries.single()
    assertTrue(IconPackNotice.NATIVE_ANIMATION_PREVIEW_UNSUPPORTED in
      IconPackArchive().inspect(archive, temporary.newFile("nested-again")).notices)
    assertNull(entry.artwork(false))
    assertNull(entry.artwork(true))
  }

  @Test fun explicitNightPairsWinInEitherZipOrderWithoutSupplyingLight() {
    for (tree in listOf("fancy_icons/com.example.app/", "animating_icons/com.example.app/fancy/")) {
      val day = pairResources(tree)
      val night = pairResources("nightmode/$tree")
      for (resources in listOf(day + night, (day + night).reversed())) {
        val entry = inspect(archive(resources)).entries.single()
        assertPairPath(entry.light, tree + "iconBg_0.png")
        assertPairPath(entry.dark, "nightmode/$tree" + "iconBg_1.png")
      }
      val dayOnly = inspect(archive(day)).entries.single()
      assertPairPath(dayOnly.light, tree + "iconBg_0.png")
      assertPairPath(dayOnly.dark, tree + "iconBg_1.png")
      val nightOnly = inspect(archive(night)).entries.single()
      assertNull(nightOnly.light)
      assertPairPath(nightOnly.dark, "nightmode/$tree" + "iconBg_1.png")
      val dayRaster = "res/drawable-xxhdpi/com.example.app.png"
      val mixed = listOf(dayRaster to header()) + night
      for (resources in listOf(mixed, mixed.reversed())) {
        val entry = inspect(archive(resources)).entries.single()
        val light = entry.light as IconPackArtwork.Archive
        assertFalse(light.palettePair)
        assertEquals(dayRaster, light.variants.single().path)
        assertPairPath(entry.dark, "nightmode/$tree" + "iconBg_1.png")
      }
    }
  }

  @Test fun explicitNightRasterLayersAndQuietSnapshotsOverrideDayPairFallbackInEitherOrder() {
    val root = "fancy_icons/com.example.app/"
    val day = pairResources(root)
    val layerRoot = "nightmode/layer_animating_icons/com.example.app/"
    val explicitResources = listOf(
      listOf("nightmode/res/drawable-xxhdpi/com.example.app.png" to header()),
      listOf("res/drawable-night-xxhdpi/com.example.app.png" to header()),
      listOf(
        "nightmode/res/drawable-xxhdpi/com.example.app/0.png" to header(),
        "nightmode/res/drawable-xxhdpi/com.example.app/1.png" to header()),
      listOf(
        layerRoot + "config.xml" to
          "<Config><Variables><Variable name='layer0' value='0'/></Variables></Config>".toByteArray(),
        layerRoot + "quiet/quietImage.png" to header()),
    )
    for (night in explicitResources) {
      val expected = night.filter { it.first.endsWith(".png") }.map { it.first }
      for (resources in listOf(day + night, (day + night).reversed())) {
        val entry = inspect(archive(resources)).entries.single()
        assertPairPath(entry.light, root + "iconBg_0.png")
        val dark = entry.dark as IconPackArtwork.Archive
        assertFalse(dark.palettePair)
        assertEquals(expected, dark.variants.map { it.path })
        assertTrue(dark.variants.all { it.qualifiers.night == IconNightMode.DARK })
      }
    }
  }

  @Test fun unknownProgramsAtAndBeyondProofElementLimitAreNonMatches() {
    for (descendants in listOf(39, 40, 41, 256)) {
      assertNull("descendants=$descendants", NativeMamlPair.parse(unknownProgram(descendants)))
    }
  }

  @Test fun exactlyFortyDescendantsRemainUnsupportedAndNativeCopyIsLossless() {
    val manifest = unknownProgram(40)
    val path = "fancy_icons/com.example.dynamic/manifest.xml"
    val source = archive(listOf(path to manifest))
    val index = inspect(source)
    val entry = index.entries.single()
    assertEquals("com.example.dynamic", entry.nativeName)
    assertNull(entry.light)
    assertNull(entry.dark)
    assertTrue(IconPackNotice.NATIVE_ANIMATION_PREVIEW_UNSUPPORTED in index.notices)
    val record = StoredIconPack("unknown", "icons.zip", 0, index.format, source, null,
      source.length(), index.nativeIconsSha256)
    val copy = temporary.newFile()
    IconPackArchive().copyNativeModule(record, copy, temporary.newFile())
    assertArrayEquals(source.readBytes(), copy.readBytes())
    assertArrayEquals(manifest, IconPackArchive.readEntry(copy, path, 64 * 1024))
    assertThrows(IconPackException::class.java) {
      IconPackArchive(IconPackLimits(maxXmlBytes = 128)).inspect(source, temporary.newFile())
    }
  }

  @Test fun proofOverflowStillValidatesRemainingXmlAndPropagatesCancellation() {
    val valid = unknownProgram(80).toString(Charsets.UTF_8)
    for (invalid in listOf(
      valid.replace("</Icon>", "<Broken></Icon>"),
      valid.replace("</Icon>", "<Var name='${"x".repeat(4097)}'/></Icon>"),
      valid.replace("</Icon>", "<Group>".repeat(17) + "</Group>".repeat(17) + "</Icon>"),
    )) assertThrows(IconPackException::class.java) { NativeMamlPair.parse(invalid.toByteArray()) }
    assertThrows(IconPackException::class.java) { NativeMamlPair.parse(ByteArray(64 * 1024 + 1)) }
    assertThrows(IconPackException::class.java) {
      inspect(archive(listOf("fancy_icons/com.example.dynamic/manifest.xml" to
        valid.replace("</Icon>", "<Broken></Icon>").toByteArray())))
    }
    val cancellation = CancellationException("cancel after proof overflow")
    var checks = 0
    assertSame(cancellation, assertThrows(CancellationException::class.java) {
      NativeMamlPair.parse(valid.toByteArray()) { if (++checks == 45) throw cancellation }
    })
  }

  private fun unknownProgram(descendants: Int) =
    ("<Icon>" + (0 until descendants).joinToString("") { "<Var name='v$it' expression='#date'/>" } +
      "</Icon>").toByteArray()

  private fun pairResources(root: String) = listOf(
    root + "manifest.xml" to source().toByteArray(),
    root + "iconBg_0.png" to header(),
    root + "iconBg_1.png" to header(),
  )

  private fun assertPairPath(artwork: IconPackArtwork?, expected: String) {
    val native = artwork as IconPackArtwork.Archive
    assertTrue(native.palettePair)
    assertEquals(expected, native.variants.single().path)
  }

  private fun inspect(file: File) = IconPackArchive().inspect(file, temporary.newFile())

  private fun archive(resources: List<Pair<String, ByteArray>>) = temporary.newFile().also { file ->
    ZipOutputStream(file.outputStream()).use { out ->
      for ((path, bytes) in resources) {
        out.putNextEntry(ZipEntry(path)); out.write(bytes); out.closeEntry()
      }
    }
  }
}
