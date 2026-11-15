package os.kei.hyper.home.xposed

import java.io.ByteArrayOutputStream
import java.io.File
import java.awt.image.BufferedImage
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import os.kei.hyper.home.iconpack.NativeIconPngFixtures
import os.kei.hyper.home.iconpack.NativeMamlPair
import os.kei.hyper.home.iconpack.NativePaletteIcon

class LocalNativeIconValidatorTest {
  @get:Rule val temporary = TemporaryFolder()
  private val png = NativeIconPngFixtures.rgba(4)
  private val root = "fancy_icons/com.example/"
  private val selector = "<Icon frameRate='0'><Image src='common.png' visibility='!#__darkmode'/>" +
    "<Image src='dark.png' visibility='#__darkmode'/></Icon>"

  @Test fun preservesNativeAnimationCalendarAndRenamedAssets() {
    val xml = "<Icon useVariableUpdater='DateTime'><VariableBinders>" +
      "<BroadcastBinder action='android.intent.action.DATE_CHANGED'/></VariableBinders>" +
      "<ExternalCommands><Trigger action='back_home_finish'><FrameRateCommand rate='120'/>" +
      "<AnimationCommand target='progress' command='play(0,800)'/>" +
      "<VariableCommand name='progress' expression='0' delay='200'/>" +
      "<FrameRateCommand rate='0' delay='800'/></Trigger></ExternalCommands>" +
      "<Var name='progress'><VariableAnimation><AniFrame time='0' value='0'/></VariableAnimation></Var>" +
      "<Image src='common.png' visibility='!#__darkmode'/><Image src='dark.png' visibility='#__darkmode'/></Icon>"
    val file = archive(module(xml))
    val before = file.readBytes()
    val proof = ConvertedIconArchive.inspect(file, ConvertedIconArchive.LOCAL_NATIVE)
    assertEquals(ConvertedIconArchive.LOCAL_NATIVE, proof.origin)
    assertArrayEquals(before, file.readBytes())
  }

  @Test fun generatedOriginsStillRejectArbitraryNativeMaml() {
    val file = archive(module())
    for (origin in listOf("CONVERTED_APK", "CONVERTED_CLASSIC_ZIP", "UNKNOWN")) {
      assertThrows(Exception::class.java) { ConvertedIconArchive.inspect(file, origin) }
    }
  }

  @Test fun rejectsNonvisualCommandsBindersAndExternalReferences() {
    for (body in listOf("<IntentCommand action='android.intent.action.VIEW'/>",
      "<MethodCommand method='exec'/>", "<FileBinder/>", "<WebServiceBinder/>",
      "<ContentProviderBinder uri='content://settings/system'/>",
      "<BroadcastBinder action='android.intent.action.PHONE_STATE'/>",
      "<Image src='../outside.png'/>", "<Image src='file:/data/secret'/>",
      "<Image src='https://example.com/icon.png'/>", "<Include src='other.xml'/>")) {
      val file = archive(module("<Icon>$body</Icon>"))
      assertThrows("Must reject $body", Exception::class.java) {
        ConvertedIconArchive.inspect(file, ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun rejectsStoreMetadataOrOtherComponentsInsideModule() {
    for (path in listOf("rights.xml", "description.xml", "lockscreen/manifest.xml", "wallpaper/a.png")) {
      assertThrows(Exception::class.java) {
        ConvertedIconArchive.inspect(archive(module() + (path to png)), ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun rejectsIndirectExternalPathsAndUnverifiedImageVariables() {
    for (body in listOf(
      "<Var name='asset' type='string' expression=\"'/data/secret'\"/><Image srcExp='@asset'/>",
      "<Var name='asset' type='string' expression=\"substr('relative/file.png',8)\"/><Image srcExp='@asset'/>",
      "<Var name='asset' type='string' expression='eval(#value)'/><Image srcExp='@asset'/>",
      "<Var name='asset' type='string' expression='@external'/><Image srcExp='@asset'/>",
      "<Var name='asset' type='string' expression=\"'common.png'\" value=\"'/data/secret'\"/><Image srcExp='@asset'/>",
      "<Image srcExp='@unverified'/>",
      "<VariableCommand name='asset' expression=\"'/data/secret'\"/>",
      "<VariableCommand name='asset' expression='0' value=\"'/data/secret'\"/>",
      "<Image src=\"'/data/secret'\"/>",
    )) {
      assertThrows("Must reject $body", Exception::class.java) {
        ConvertedIconArchive.inspect(archive(module("<Icon>$body</Icon>")),
          ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun rejectsMalformedImagesAndOversizedCanvas() {
    for (bad in listOf(byteArrayOf(1, 2, 3), png.copyOf(png.size - 1),
      png.clone().also { it[29] = (it[29].toInt() xor 1).toByte() },
      NativeIconPngFixtures.png(2049, 8, 6, byteArrayOf(0)))) {
      assertThrows(Exception::class.java) {
        ConvertedIconArchive.inspect(archive(module() + ("${root}dark.png" to bad)),
          ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun completePngMayHaveOpaqueTrailingBytesWithoutRewritingThePublication() {
    val tailed = png + ByteArray(4008) { (it * 31).toByte() }
    val file = archive(module() + ("${root}dark.png" to tailed))
    val before = file.readBytes()
    ConvertedIconArchive.inspect(file, ConvertedIconArchive.LOCAL_NATIVE)
    assertArrayEquals(before, file.readBytes())
    val corrupt = tailed.clone().also { it[29] = (it[29].toInt() xor 1).toByte() }
    assertThrows(Exception::class.java) {
      ConvertedIconArchive.inspect(archive(module() + ("${root}dark.png" to corrupt)),
        ConvertedIconArchive.LOCAL_NATIVE)
    }
  }

  @Test fun acceptsRealBaselineAndProgressiveJpegUnderNativePngNamesWithoutChangingBytes() {
    val baseline = jpeg()
    val progressive = jpeg(progressive = true)
    val metadata = byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 6, 0xff.toByte(), 0xd9.toByte(), 0, 0)
    val withMetadata = baseline.copyOf(2) + metadata + baseline.copyOfRange(2, baseline.size)
    val file = archive(module() + mapOf(
      "res/drawable-xxhdpi/com.example.png" to withMetadata,
      "${root}common.png" to baseline, "${root}dark.png" to progressive))
    val original = file.readBytes()
    val proof = ConvertedIconArchive.inspect(file, ConvertedIconArchive.LOCAL_NATIVE)
    assertEquals(ConvertedIconArchive.LOCAL_NATIVE, proof.origin)
    assertArrayEquals(original, file.readBytes())
  }

  @Test fun jpegKeepsLocal2048PixelBoundaryForSequentialAndProgressiveFrames() {
    for (progressive in listOf(false, true)) {
      ConvertedIconArchive.inspect(archive(module() + ("${root}dark.png" to
        jpeg(width = 2048, height = 1, progressive = progressive))), ConvertedIconArchive.LOCAL_NATIVE)
      assertThrows(Exception::class.java) {
        ConvertedIconArchive.inspect(archive(module() + ("${root}dark.png" to
          jpeg(width = 2049, height = 1, progressive = progressive))), ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun jpegRejectsTruncatedHeadersScansMissingEndAndTrailingPayload() {
    val good = jpeg()
    val scan = marker(good, 0xda)
    val scanEnd = scan + 2 + be16(good, scan + 2)
    val malformed = listOf(
      good.copyOf(12),
      good.copyOf(scan + 5),
      good.copyOf(good.size - 1),
      good.copyOf(good.size - 2),
      good + byteArrayOf(0),
      good.copyOf(scan) + byteArrayOf(0xff.toByte(), 0xd9.toByte()), // No SOS.
      good.copyOf(scanEnd) + byteArrayOf(0xff.toByte(), 0xd9.toByte()), // Empty entropy data.
      good.copyOf().also { it[scan + 3] = 2 },
      good.copyOf().also { it[marker(good, 0xc0) + 4] = 12 }, // Baseline precision must be 8.
      good.copyOf().also { it[scan + 5] = 127 }, // Unknown component.
      good.copyOf().also { it[scanEnd - 3] = 1 }, // Baseline scan cannot select an AC spectral band.
      good.copyOf(good.size - 2) + byteArrayOf(0xff.toByte(), 0xd0.toByte(), 0xff.toByte(), 0xd9.toByte()),
    )
    for ((index, bad) in malformed.withIndex()) {
      assertThrows("JPEG fixture $index", Exception::class.java) {
        ConvertedIconArchive.inspect(archive(module() + ("${root}dark.png" to bad)),
          ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun jpegChecksEveryProgressiveScanRatherThanOnlyFirstHeaderAndFinalEoi() {
    val good = jpeg(progressive = true)
    val scan = marker(good, 0xda)
    val scanEnd = scan + 2 + be16(good, scan + 2)
    val invalidSpectral = good.copyOf().also { it[scanEnd - 2] = 63 }
    val duplicateFrame = good.copyOf(good.size - 2) +
      good.copyOfRange(marker(good, 0xc2), marker(good, 0xc2) + 2 + be16(good, marker(good, 0xc2) + 2)) +
      byteArrayOf(0xff.toByte(), 0xd9.toByte())
    for (bad in listOf(invalidSpectral, duplicateFrame)) {
      assertThrows(Exception::class.java) {
        ConvertedIconArchive.inspect(archive(module() + ("${root}dark.png" to bad)),
          ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun jpegDoesNotRelaxEightMiBLocalEntryLimit() {
    val oversized = jpeg().copyOf(8 * 1024 * 1024 + 1)
    val file = temporary.newFile().apply {
      writeBytes(zip(module() + ("${root}dark.png" to oversized), stored = true))
    }
    val failure = assertThrows(IllegalArgumentException::class.java) {
      LocalNativeIconValidator.validate(file)
    }
    assertTrue(failure.message.orEmpty().contains("Local icon entry size limit"))
  }

  @Test fun jpegDoesNotRelaxManifestAndResourcePathAdmission() {
    val payload = module() + ("${root}dark.png" to jpeg())
    for (xml in listOf("<Icon><IntentCommand action='android.intent.action.VIEW'/></Icon>",
      "<Icon><Image src='../secret.png'/></Icon>",
      "<Icon><Image src='content://settings/system'/></Icon>")) {
      assertThrows(Exception::class.java) {
        ConvertedIconArchive.inspect(archive(payload + ("${root}manifest.xml" to xml.toByteArray())),
          ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun acceptsOnlyCanonicalGeneratedSettingsBindersAlongsideNativeContent() {
    val pair = NativePaletteIcon.entries("com.generated", png, png,
      NativeIconPngFixtures.mask(4), NativeIconPngFixtures.mask(4))
    val file = archive(module() + pair)
    ConvertedIconArchive.inspect(file, ConvertedIconArchive.LOCAL_NATIVE)
    val manifestPath = "fancy_icons/com.generated/manifest.xml"
    val altered = pair + (manifestPath to String(pair.getValue(manifestPath))
      .replace("settings/system", "settings/secure").toByteArray())
    assertThrows(Exception::class.java) {
      ConvertedIconArchive.inspect(archive(module() + altered), ConvertedIconArchive.LOCAL_NATIVE)
    }
  }

  @Test fun acceptsVerifiedNativeOriginInReceiptAndRuntimeAssociation() {
    val proof = ConvertedIconArchive.inspect(archive(module()), ConvertedIconArchive.LOCAL_NATIVE)
    assertEquals(proof, ConvertedImportProofReceipt.decode(ConvertedImportProofReceipt.encode(proof)))
    ConvertedIconValidationRegistration("12345678-1234-1234-1234-123456789012", 0, "local-id", proof).validate()
  }

  @Test fun suppliedModulePassesIndependentLocalValidation() {
    val path = System.getenv("KEIHOME_TEST_ICON_ARCHIVE")
    assumeTrue(path != null)
    val file = File(requireNotNull(path))
    val hash = ConvertedIconArchive.digest(file)
    LocalNativeIconValidator.validate(file)
    val outer = archiveBytes(file.readBytes())
    val proof = ConvertedIconArchive.inspect(outer, ConvertedIconArchive.LOCAL_NATIVE)
    assertEquals(hash, proof.icons)
    assertEquals(hash, ConvertedIconArchive.digest(file))
  }

  @Test fun admitsBoundedNativeLayerFlagsAndMaskGeometry() {
    val transform = """<IconTransform>
      <Config name="SupportLayerIcon" value="true"/>
      <Config name="UseDynamicIcon" value="false"/>
      <Config name="ConfigIconMask" value="M 0 10 C 0 0 10 0 10 0 H 90 V 90 L 0 90 Z"/>
      </IconTransform>"""
    ConvertedIconArchive.inspect(archive(module() + ("transform_config.xml" to transform.toByteArray())),
      ConvertedIconArchive.LOCAL_NATIVE)
  }

  @Test fun rejectsUnknownNativeSettingsAndUnboundedOrMalformedMasks() {
    for (setting in listOf(
      """<Config name="Execute" value="true"/>""",
      """<Config name="UseDynamicIcon" value="1"/>""",
      """<Config name="ConfigIconMask" value="content://settings"/>""",
      """<Config name="ConfigIconMask" value="M 0 0 C 1 2 Z"/>""",
      """<Config name="ConfigIconMask" value="M 0 0 L 1e309 2 Z"/>""",
      """<Config name="ConfigIconMask" value="M 0 0 L 999999 2 Z"/>""",
      """<Config name="ConfigIconMask" value="M 0 0 L 1 2"/>""",
      """<Config name="ConfigIconMask" value="M 0 0 ${"L 1 2 ".repeat(257)}Z"/>""",
    )) {
      val transform = "<IconTransform>$setting</IconTransform>"
      assertTrue(runCatching {
        ConvertedIconArchive.inspect(archive(module() + ("transform_config.xml" to transform.toByteArray())),
          ConvertedIconArchive.LOCAL_NATIVE)
      }.isFailure)
    }
  }

  @Test fun admitsOnlyClosedNativeCrossfadeTemplatesWithoutRewritingTheirPublication() {
    for (xml in listOf(closedPair(), closedPair().replace("DateTime.Day", "none"))) {
      val file = archive(closedModule(xml))
      val before = file.readBytes()
      val proof = ConvertedIconArchive.inspect(file, ConvertedIconArchive.LOCAL_NATIVE)
      assertEquals(ConvertedIconArchive.LOCAL_NATIVE, proof.origin)
      assertArrayEquals(before, file.readBytes())
    }
  }

  @Test fun cachedTemplateStillRequiresBothImagesInEachComponent() {
    val first = closedModule()
    val second = closedModule(base = "fancy_icons/com.second/")
    for (missing in listOf("iconBg_0.png", "iconBg_1.png")) {
      val file = archive(first + (second - "fancy_icons/com.second/$missing"))
      assertThrows("Missing image must not inherit another component's proof", Exception::class.java) {
        ConvertedIconArchive.inspect(file, ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun closedPairKeepsExistingImageIntegritySizeAndDimensionBudgets() {
    for (bad in listOf(
      byteArrayOf(1, 2, 3),
      png.clone().also { it[29] = (it[29].toInt() xor 1).toByte() },
      NativeIconPngFixtures.png(2049, 8, 6, byteArrayOf(0)),
    )) {
      assertThrows(Exception::class.java) {
        ConvertedIconArchive.inspect(archive(closedModule() + ("${root}iconBg_1.png" to bad)),
          ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
    val oversized = png.copyOf(8 * 1024 * 1024 + 1)
    val file = temporary.newFile().apply {
      writeBytes(zip(closedModule() + ("${root}iconBg_1.png" to oversized), stored = true))
    }
    assertThrows(IllegalArgumentException::class.java) { LocalNativeIconValidator.validate(file) }
  }

  @Test fun closedPairDoesNotAdmitOtherUpdatersCommandsPathsOrTimingPrograms() {
    for (changed in listOf(
      closedPair().replace("DateTime.Day", "DateTime.Second"),
      closedPair().replace("iconBg.png", "../iconBg.png"),
      closedPair().replace("frameRate=\"60\"", "frameRate=\"120\""),
      closedPair().replace("srcid=\"1\"", "srcid=\"#date\""),
      closedPair().replace("</Icon>", "<ContentProviderBinder uri='content://settings/system'/></Icon>"),
      closedPair().replace("</Icon>", "<IntentCommand action='android.intent.action.VIEW'/></Icon>"),
    )) {
      assertThrows(Exception::class.java) {
        ConvertedIconArchive.inspect(archive(closedModule(changed)), ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun closedPairCannotExtendTheSixtyFourKiBManifestBudgetOrAcceptTransformTypos() {
    val oversized = closedPair().replace("</Icon>", "${" ".repeat(64 * 1024)}</Icon>")
    assertThrows(Exception::class.java) {
      ConvertedIconArchive.inspect(archive(closedModule(oversized)), ConvertedIconArchive.LOCAL_NATIVE)
    }
    val typo = "<IconTransform><Config name='UseDynamicIcon' value='fales'/></IconTransform>"
    assertThrows(Exception::class.java) {
      ConvertedIconArchive.inspect(archive(closedModule() + ("transform_config.xml" to typo.toByteArray())),
        ConvertedIconArchive.LOCAL_NATIVE)
    }
  }

  @Test fun repeatedClosedTemplatesAndNonMatchesAreParsedOnlyOncePerExactByteSequence() {
    var calls = 0
    val cache = LocalNativeIconValidator.PairTemplates { bytes -> calls++; NativeMamlPair.parse(bytes) }
    val one = closedPair().toByteArray()
    val two = closedPair().replace("DateTime.Day", "none").toByteArray()
    val unknown = "<Icon><Image src='image.png'/></Icon>".toByteArray()
    repeat(13_389) { assertNotNull(cache.parse(if (it % 2 == 0) one else two)) }
    repeat(100) { assertNull(cache.parse(unknown)) }
    assertEquals(3, calls)
    // The retained key cannot be changed by a caller reusing its input buffer.
    one[0] = '!'.code.toByte()
    assertNotNull(cache.parse(closedPair().toByteArray()))
    assertEquals(3, calls)
  }

  @Test fun closedTemplateCacheRetainsAtMostThirtyTwoTemplatesAndSkipsOversizedProofs() {
    var calls = 0
    val cache = LocalNativeIconValidator.PairTemplates { bytes -> calls++; NativeMamlPair.parse(bytes) }
    val variants = (32..64).map { closedPair().replace("168", "$it").toByteArray() }
    variants.forEach { assertNotNull(cache.parse(it)) }
    assertEquals(33, calls)
    assertNotNull(cache.parse(variants[2]))
    assertEquals(33, calls)
    assertNotNull(cache.parse(variants[0]))
    assertEquals(34, calls)
    assertNull(cache.parse(ByteArray(64 * 1024 + 1)))
    assertEquals(34, calls)
  }

  @Test fun admitsNativeMonetFancyOnlyPublicationWithoutStaticFallbackTrees() {
    for (night in listOf(png, NativeIconPngFixtures.rgba(4) { _, _ -> 128 })) {
      val generated = NativePaletteIcon.entries("com.generated", png, night,
        NativeIconPngFixtures.mask(4), NativeIconPngFixtures.mask(4))
        .filterKeys { it.startsWith("fancy_icons/com.generated/") }
      val file = archive(module() + generated)
      val before = file.readBytes()
      assertEquals(ConvertedIconArchive.LOCAL_NATIVE,
        ConvertedIconArchive.inspect(file, ConvertedIconArchive.LOCAL_NATIVE).origin)
      assertArrayEquals(before, file.readBytes())
      val manifestPath = "fancy_icons/com.generated/manifest.xml"
      val bad = generated.getValue(manifestPath).toString(Charsets.UTF_8)
        .replace("settings/system/", "settings/secure/").toByteArray()
      assertThrows(Exception::class.java) {
        ConvertedIconArchive.inspect(archive(module() + generated + (manifestPath to bad)),
          ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun admitsCapturedClosedCalendarWithoutRewritingThePublishedManifest() {
    val file = archive(calendarModule())
    val before = file.readBytes()
    assertEquals(ConvertedIconArchive.LOCAL_NATIVE,
      ConvertedIconArchive.inspect(file, ConvertedIconArchive.LOCAL_NATIVE).origin)
    assertArrayEquals(before, file.readBytes())
  }

  @Test fun closedCalendarRequiresEveryDayAndNightImageEvenAfterAnotherComponentWasValidated() {
    val second = "fancy_icons/com.second/"
    for (missing in listOf(
      "calendar_0/themed_icon_calendar_1.png", "calendar_0/themed_icon_calendar_31.png",
      "calendar_1/themed_icon_calendar_1.png", "calendar_1/themed_icon_calendar_31.png",
    )) {
      assertThrows("Missing calendar PNG: $missing", Exception::class.java) {
        ConvertedIconArchive.inspect(archive(calendarModule() + (calendarModule(second) - (second + missing))),
          ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun closedCalendarRetainsExistingImageIntegritySizeAndDimensionBudgets() {
    val path = "${root}calendar_1/themed_icon_calendar_31.png"
    for (bad in listOf(
      byteArrayOf(1, 2, 3),
      png.clone().also { it[29] = (it[29].toInt() xor 1).toByte() },
      NativeIconPngFixtures.png(2049, 8, 6, byteArrayOf(0)),
    )) {
      assertThrows(Exception::class.java) {
        ConvertedIconArchive.inspect(archive(calendarModule() + (path to bad)),
          ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
    val file = temporary.newFile().apply {
      writeBytes(zip(calendarModule() + (path to png.copyOf(8 * 1024 * 1024 + 1)), stored = true))
    }
    assertThrows(IllegalArgumentException::class.java) { LocalNativeIconValidator.validate(file) }
  }

  @Test fun calendarAndStaticProofsShareOneThirtyTwoTemplateCache() {
    var calls = 0
    val cache = LocalNativeIconValidator.PairTemplates { bytes -> calls++; NativeMamlPair.parse(bytes) }
    val calendar = calendarPair().toByteArray()
    repeat(100) {
      assertNull(cache.parse(calendar))
      assertNotNull(cache.calendar(calendar))
    }
    assertEquals(1, calls)
    (32..63).forEach { assertNotNull(cache.parse(closedPair().replace("168", "$it").toByteArray())) }
    assertEquals(33, calls)
    assertNotNull(cache.calendar(calendar))
    assertEquals(34, calls)
    assertNull(cache.calendar(ByteArray(64 * 1024 + 1)))
    assertEquals(34, calls)
  }

  private fun calendarPair() = requireNotNull(javaClass.getResourceAsStream("/native-maml/calendar-pair.xml"))
    .use { it.readBytes().toString(Charsets.UTF_8) }

  private fun calendarModule(base: String = root): Map<String, ByteArray> = linkedMapOf(
    base + "manifest.xml" to calendarPair().toByteArray(),
    "transform_config.xml" to "<IconTransform><Config name='UseDynamicIcon' value='false'/></IconTransform>"
      .toByteArray(),
  ).apply {
    for (mode in 0..1) for (day in 1..31) put(base + "calendar_$mode/themed_icon_calendar_$day.png", png)
  }

  private fun closedPair() = requireNotNull(javaClass.getResourceAsStream("/native-maml/image-pair.xml"))
    .use { it.readBytes().toString(Charsets.UTF_8) }

  private fun closedModule(xml: String = closedPair(), base: String = root) = linkedMapOf(
    base + "manifest.xml" to xml.toByteArray(),
    base + "iconBg_0.png" to png,
    base + "iconBg_1.png" to png,
    "transform_config.xml" to "<IconTransform><Config name='UseDynamicIcon' value='false'/></IconTransform>".toByteArray())

  private fun module(xml: String = selector) = linkedMapOf(
    "res/drawable-xxhdpi/com.example.png" to png,
    "${root}manifest.xml" to xml.toByteArray(), "${root}common.png" to png, "${root}dark.png" to png,
    "transform_config.xml" to "<IconTransform><ScaleX>1</ScaleX></IconTransform>".toByteArray())

  private fun archive(entries: Map<String, ByteArray>) = archiveBytes(zip(entries))
  private fun archiveBytes(module: ByteArray) = temporary.newFile().apply {
    writeBytes(zip(linkedMapOf("description.xml" to
      ("<MIUI-Theme><title>Local icons</title><author>Local author</author><designer>Local author</designer>" +
        "<version>1</version><uiVersion>13</uiVersion><description>Local icon import</description></MIUI-Theme>").toByteArray(),
      "icons" to module, "preview/preview_icons_0.png" to png)))
  }
  private fun jpeg(width: Int = 4, height: Int = 4, progressive: Boolean = false): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("JPEG").next()
    val bytes = ByteArrayOutputStream()
    try {
      ImageIO.createImageOutputStream(bytes).use { output ->
        writer.output = output
        val options = writer.defaultWriteParam.apply {
          progressiveMode = if (progressive) ImageWriteParam.MODE_DEFAULT else ImageWriteParam.MODE_DISABLED
        }
        writer.write(null, IIOImage(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), null, null), options)
      }
      return bytes.toByteArray()
    } finally { writer.dispose() }
  }

  private fun be16(bytes: ByteArray, index: Int) =
    ((bytes[index].toInt() and 255) shl 8) or (bytes[index + 1].toInt() and 255)

  private fun marker(bytes: ByteArray, wanted: Int): Int {
    var offset = 2
    while (offset < bytes.size - 3) {
      require(bytes[offset].toInt() and 255 == 255)
      if (bytes[offset + 1].toInt() and 255 == wanted) return offset
      offset += 2 + be16(bytes, offset + 2)
    }
    error("Missing JPEG marker")
  }

  private fun zip(entries: Map<String, ByteArray>, stored: Boolean = false): ByteArray = ByteArrayOutputStream().also { bytes ->
    ZipOutputStream(bytes).use { zip ->
      entries.forEach { (name, value) ->
        val entry = ZipEntry(name)
        if (stored) {
          entry.method = ZipEntry.STORED
          entry.size = value.size.toLong()
          entry.crc = CRC32().apply { update(value) }.value
        }
        zip.putNextEntry(entry)
        zip.write(value)
        zip.closeEntry()
      }
    }
  }.toByteArray()
}
