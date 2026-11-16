package os.kei.hyper.home.xposed

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.NativeDayNightIcon
import os.kei.hyper.home.iconpack.NativeIconPngFixtures
import os.kei.hyper.home.iconpack.NativePaletteIcon
import os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon

class ConvertedIconArchiveTest {
  @Test fun validationCopyCleanupIsIdempotent() {
    val directory = Files.createTempDirectory("converted-cleanup").toFile()
    try {
      val file = File(directory, "proof.icons").apply { writeText("temporary") }
      ConvertedIconArchive.removeValidationCopy(file)
      ConvertedIconArchive.removeValidationCopy(file)
      assertFalse(file.exists())
    } finally { directory.deleteRecursively() }
  }

  @Test fun validationCopyCleanupDoesNotHideActualDeletionFailures() {
    val directory = Files.createTempDirectory("converted-cleanup").toFile()
    try {
      val blocked = File(directory, "proof.icons").apply { mkdir() }
      val child = File(blocked, "keep").apply { writeText("present") }
      assertThrows(java.nio.file.DirectoryNotEmptyException::class.java) {
        ConvertedIconArchive.removeValidationCopy(blocked)
      }
      assertTrue(child.exists())
    } finally { directory.deleteRecursively() }
  }

  @Test fun acceptsBoundedBuildProvenanceAlongsideValidatedArtwork() {
    val content = zip(generatedPair() + ("keihome-build-source.txt" to
      "apk:com.example.icons:system-fallback-v1:DEFAULT\ncom.example.icons:1:100".toByteArray()))
    withArchive(content) { ConvertedIconArchive.inspect(it, "CONVERTED_APK") }
  }

  @Test fun buildProvenanceDoesNotAdmitXmlPathsOrOversizedPayloads() {
    for (value in listOf("<script/>\ncom.example", "file:///data/theme\ncom.example",
        "valid\ninvalid\nextra", "a".repeat(4097))) {
      rejects(zip(generatedPair() + ("keihome-build-source.txt" to value.toByteArray())))
    }
  }
  @Test fun fallbackProvenanceCannotClaimMissingIconsOrUnrelatedMonochromeLayers() {
    val path = os.kei.hyper.home.iconpack.SystemIconFallbackManifest.PATH
    fun metadata(name: String) = os.kei.hyper.home.iconpack.SystemIconFallbackManifest(
      os.kei.hyper.home.data.SystemIconFallback.DEFAULT, setOf(name)).encode()
    val pair = mapOf(day to png(), night to png())
    withArchive(zip(pair + (path to metadata("com.example")))) {
      ConvertedIconArchive.inspect(it, "CONVERTED_CLASSIC_ZIP")
    }
    rejects(zip(pair + (path to metadata("com.missing"))))
    rejects(zip(generatedPalette() + (path to metadata("com.example"))))
  }
  @Test fun acceptsBothAuthenticatedConversionOriginsAndPreservesNativeDigests() {
    val icons = icons()
    withArchive(icons) { file ->
      for (origin in listOf("CONVERTED_APK", "CONVERTED_CLASSIC_ZIP")) {
        val proof = ConvertedIconArchive.inspect(file, origin)
        assertEquals(hash(icons, "SHA-1"), proof.icons.sha1)
        assertEquals(hash(icons, "SHA-256"), proof.icons.sha256)
        assertEquals(hash(file.readBytes(), "SHA-256"), proof.archive.sha256)
      }
      assertEquals(listOf(file.name), requireNotNull(file.parentFile).list()!!.toList()) // No leaked proof copy.
    }
  }

  @Test fun acceptsUnicodeDottedComponents() {
    withArchive(icons("com.示例.活动")) { ConvertedIconArchive.inspect(it, "CONVERTED_APK") }
  }

  @Test fun acceptsQualifiedStaticAndCanonicalDynamicAliasesWithoutWeakeningArchiveProof() {
    val alias = "com.example.release#com.shared.Launcher"
    val pairs = generatedPair(alias)
    val variants = listOf(pairs) + NativeColorOsDynamicIcon.Kind.entries.map { kind ->
      pairs + generatedDynamic(kind).mapKeys { (path, _) ->
        path.replace("com.example", alias)
      }
    }
    for (entries in variants) withArchive(zip(entries)) { file ->
      for (origin in listOf("CONVERTED_APK", "CONVERTED_CLASSIC_ZIP")) {
        ConvertedIconArchive.inspect(file, origin)
      }
    }
    for (invalid in listOf("com.example#com.shared#com.other", "com.example#Launcher",
        "com.example#/com.shared", "com.example#../com.shared", "#com.shared")) {
      rejects(zip(pairs.mapKeys { (path, _) -> path.replace(alias, invalid) }))
    }
    val dynamic = variants.last()
    val manifest = "fancy_icons/$alias/manifest.xml"
    rejects(zip(dynamic + (manifest to (dynamic.getValue(manifest).toString(Charsets.UTF_8) +
      "<Script/>").toByteArray())))
  }

  @Test fun unchangedNativeOrUnrecognizedOriginCannotRegister() {
    for (content in listOf(icons(), zip(generatedPair()))) withArchive(content) { file ->
      listOf("MIUI_MTZ", "NATIVE", "converted_apk", "").forEach { origin ->
        assertThrows(IllegalArgumentException::class.java) { ConvertedIconArchive.inspect(file, origin) }
      }
    }
  }

  @Test fun acceptsOnlyGeneratedStaticPairsAndPreservesWholeArchiveDigests() {
    val entries = generatedPair() + generatedPair("com.示例.活动") +
      generatedPair("com.example.Main\$Nested") + mapOf(
        "res/drawable-xxhdpi/com.static.png" to png(),
        "nightmode/res/drawable-xxhdpi/com.static.png" to png(),
      )
    // A central directory can list the MAML before its ordinary fallback PNGs.
    val content = zip(*entries.toList().reversed().toTypedArray())
    withArchive(content) { file ->
      for (origin in listOf("CONVERTED_APK", "CONVERTED_CLASSIC_ZIP")) {
        val proof = ConvertedIconArchive.inspect(file, origin)
        assertEquals(hash(content, "SHA-1"), proof.icons.sha1)
        assertEquals(hash(content, "SHA-256"), proof.icons.sha256)
        assertEquals(hash(file.readBytes(), "SHA-256"), proof.archive.sha256)
      }
      assertEquals(listOf(file.name), requireNotNull(file.parentFile).list()!!.toList())
    }
  }

  @Test fun rejectsArbitraryXmlNetworkScriptsAndNoncanonicalManifestBytes() {
    val entries = generatedPair()
    val path = "$fancy/manifest.xml"
    val canonical = String(entries.getValue(path), Charsets.UTF_8)
    val manifests = listOf(
      "<Icon><Image src=\"day.png\"/></Icon>",
      canonical.replace("day.png", "https://example.invalid/icon.png"),
      canonical.replace("</Icon>", "<Script>fetch('https://example.invalid/')</Script></Icon>"),
      canonical.replace("</Icon>", "<ExternalCommand command=\"launch\"/></Icon>"),
      canonical.replace("day.png", "../day.png"),
      canonical.replace("day.png", "/data/local/tmp/icon.png"),
      canonical.replace("day.png", "sky.png"),
      canonical.replace("frameRate=\"0\"", "frameRate=\"1\""),
      canonical.replace("</Icon>", "<!-- extra XML --></Icon>"),
      canonical.replace("<Icon ", "<!DOCTYPE Icon SYSTEM \"https://example.invalid/icon.dtd\"><Icon "),
      canonical + "\n",
    )
    for (manifest in manifests) rejects(zip(entries + (path to manifest.toByteArray(Charsets.UTF_8))))
  }

  @Test fun acceptsCanonicalPaletteIconsBesideExistingPairsAndStaticFallbacks() {
    val entries = generatedPalette() + generatedPair("com.pair") + mapOf(
      "res/drawable-xxhdpi/com.static.png" to png(),
      "nightmode/res/drawable-xxhdpi/com.static.png" to png(),
    )
    withArchive(zip(*entries.toList().reversed().toTypedArray())) { file ->
      ConvertedIconArchive.inspect(file, "CONVERTED_CLASSIC_ZIP")
      ConvertedIconArchive.inspect(file, "CONVERTED_APK")
    }
  }

  @Test fun paletteManifestCannotChangeObserverSourceTypesOrTimingOrRunOtherMaml() {
    val entries = generatedPalette()
    val path = "$fancy/manifest.xml"
    val canonical = String(entries.getValue(path), Charsets.UTF_8)
    for (manifest in listOf(
      canonical.replace("settings/system/keihome_maml_palette_state", "settings/secure/other"),
      canonical.replace("content://settings", "content://foreign"),
      canonical.replace("updateInterval=\"-1\"", "updateInterval=\"0\""),
      canonical.replace("queryAtStart=\"true\"", "queryAtStart=\"false\""),
      canonical.replace("column=\"value\"", "column=\"name\""),
      canonical.replace("row=\"0\"", "row=\"1\""),
      canonical.replace("type=\"double\"", "type=\"string\""),
      canonical.replace("tintmode=\"5\"", "tintmode=\"3\""),
      canonical.replace("#palette_state", "#foreign"),
      canonical.replace("bg.png", "../bg.png"),
      canonical.replace("</Icon>", "<Script>anything</Script></Icon>"),
      canonical + "\n",
    )) {
      assertNotEquals(canonical, manifest)
      rejects(zip(entries + (path to manifest.toByteArray())))
    }
  }

  @Test fun acceptsAtomicAndLegacyScalarPaletteArchivesWithCompactOrExpandedFallbacks() {
    val light = NativeIconPngFixtures.rgba()
    val dark = NativeIconPngFixtures.rgba { _, _ -> 128 }
    for (legacyScalar in listOf(false, true)) for (expanded in listOf(false, true)) {
      for (night in listOf(light, dark)) {
        val entries = NativePaletteIcon.entries("com.example", light, night,
          NativeIconPngFixtures.mask(), NativeIconPngFixtures.indexedMask(),
          legacyExpanded = expanded, legacyScalarBinders = legacyScalar)
        withArchive(zip(entries)) { file ->
          ConvertedIconArchive.inspect(file, "CONVERTED_CLASSIC_ZIP")
          ConvertedIconArchive.inspect(file, "CONVERTED_APK")
        }
      }
    }
  }

  @Test fun paletteRequiresBothValidatedMasksAndExactFallbackCopies() {
    val entries = generatedPalette()
    for (missing in listOf("$fancy/manifest.xml", "$fancy/mask.png", "$fancy/bg.png", "$fancy/day.png",
      "$fancy/night.png", day, night)) rejects(zip(entries - missing))
    for (mask in listOf("$fancy/mask.png", "$fancy/bg.png")) {
      for (bad in listOf(png(2), NativeIconPngFixtures.mask(3), "bad".toByteArray(),
        NativeIconPngFixtures.mask().apply { this[29] = 0 })) {
        rejects(zip(entries + (mask to bad)))
      }
    }
    for (path in listOf("$fancy/day.png", "$fancy/night.png", day, night)) {
      rejects(zip(entries + (path to png(2, pixel = 2))))
    }
    rejects(zip(generatedPair() + ("$fancy/bg.png" to NativeIconPngFixtures.mask())))
    rejects(zip(entries + ("$fancy/extra.png" to NativeIconPngFixtures.mask())))
  }

  @Test fun acceptsOnlyTheSixFixedAppearanceVariantsForBothDynamicTemplates() {
    for (kind in NativeColorOsDynamicIcon.Kind.entries) {
      for (fixedDark in listOf(null, false, true)) for (dim in listOf(false, true)) {
        val entries = generatedDynamic(kind, fixedDark, dim) + generatedPair("com.pair") +
          NativePaletteIcon.entries("com.palette", NativeIconPngFixtures.rgba(), NativeIconPngFixtures.rgba(),
            NativeIconPngFixtures.mask(), NativeIconPngFixtures.indexedMask())
        val content = zip(*entries.toList().reversed().toTypedArray())
        withArchive(content) { file ->
          for (origin in listOf("CONVERTED_APK", "CONVERTED_CLASSIC_ZIP")) {
            assertEquals(hash(content, "SHA-256"), ConvertedIconArchive.inspect(file, origin).icons.sha256)
          }
          assertEquals(listOf(file.name), requireNotNull(file.parentFile).list()!!.toList())
        }
      }
    }
  }

  @Test fun dynamicClockAllowsItsMatchedAssetGroupToDifferFromBackgroundDimensions() {
    val entries = generatedDynamic(NativeColorOsDynamicIcon.Kind.CLOCK, clockSize = 240)
    withArchive(zip(entries)) { ConvertedIconArchive.inspect(it, "CONVERTED_CLASSIC_ZIP") }
    rejects(zip(entries + ("$fancy/clock_day_hour_rectangle.png" to png(239))))
    rejects(zip(entries + ("$fancy/clock_day_bg.png" to png(513))))
    rejects(zip(entries + ("$fancy/clock_day_minute_rectangle.png" to "bad".toByteArray())))
    rejects(zip(entries + ("$fancy/night_clock_day_hour_rectangle.png" to png(239))))
    rejects(zip(entries + ("$fancy/night_clock_day_bg.png" to png(513))))
    rejects(zip(entries + ("$fancy/night_clock_day_minute_rectangle.png" to "bad".toByteArray())))
    rejects(zip(entries.filterKeys { !it.startsWith("$fancy/night_clock_") }))
  }

  @Test fun acceptsColorOsClockAndCalendarSnapshotsIndependentOfLiveBackgrounds() {
    for (kind in NativeColorOsDynamicIcon.Kind.entries) {
      val name = if (kind == NativeColorOsDynamicIcon.Kind.CLOCK) "com.android.deskclock"
        else "com.android.calendar"
      for (fixedDark in listOf(null, false, true)) for (dim in listOf(false, true)) {
        val entries = generatedDynamic(kind, fixedDark, dim, nativeName = name, snapshots = true)
        val root = "fancy_icons/$name"
        val lightPath = "res/drawable-xxhdpi/$name.png"
        val darkPath = "nightmode/res/drawable-xxhdpi/$name.png"
        assertFalse(entries.getValue(lightPath).contentEquals(entries.getValue("$root/day.png")))
        assertFalse(entries.getValue(darkPath).contentEquals(entries.getValue("$root/night.png")))
        val content = zip(*entries.toList().reversed().toTypedArray())
        withArchive(content) { file ->
          for (origin in listOf("CONVERTED_APK", "CONVERTED_CLASSIC_ZIP")) {
            assertEquals(hash(content, "SHA-256"), ConvertedIconArchive.inspect(file, origin).icons.sha256)
          }
        }
      }
    }
  }

  @Test fun legacyColorOsFallbacksEqualToBackgroundsRemainCompatible() {
    for (kind in NativeColorOsDynamicIcon.Kind.entries) {
      val entries = generatedDynamic(kind)
      assertTrue(entries.getValue(day).contentEquals(entries.getValue("$fancy/day.png")))
      assertTrue(entries.getValue(night).contentEquals(entries.getValue("$fancy/night.png")))
      withArchive(zip(entries)) { file ->
        for (origin in listOf("CONVERTED_APK", "CONVERTED_CLASSIC_ZIP")) {
          ConvertedIconArchive.inspect(file, origin)
        }
      }
    }
  }

  @Test fun dynamicTemplatesRequireEveryFixedAssetAndBoundedIndependentFallbacks() {
    for (kind in NativeColorOsDynamicIcon.Kind.entries) {
      val entries = generatedDynamic(kind)
      for (missing in entries.keys) rejects(zip(entries - missing))
      for (path in listOf(day, night, "$fancy/day.png", "$fancy/night.png")) {
        for (invalid in listOf("bad".toByteArray(), png(513), png(2, height = 1))) {
          rejects(zip(entries + (path to invalid)))
        }
      }
      for (extra in listOf("fallback.png", "fallback-night.png", "clock_day_second.png",
        "clock_night_bg.png", "clock_day_minute.png", "bg.png")) {
        rejects(zip(entries + ("$fancy/$extra" to png(2))))
      }
    }
    val calendar = generatedDynamic(NativeColorOsDynamicIcon.Kind.CALENDAR)
    val clock = generatedDynamic(NativeColorOsDynamicIcon.Kind.CLOCK)
    rejects(zip(calendar + clock.filterKeys { it.contains("/clock_day_") }))
    rejects(zip(clock + generatedPalette().filterKeys { it.endsWith("/mask.png") || it.endsWith("/bg.png") }))
  }

  @Test fun snapshotFallbacksDoNotAuthorizeForeignMamlPathsOrClockHandSchemas() {
    for (kind in NativeColorOsDynamicIcon.Kind.entries) {
      val entries = generatedDynamic(kind, snapshots = true)
      val manifestPath = "$fancy/manifest.xml"
      val manifest = entries.getValue(manifestPath).toString(Charsets.UTF_8)
      for (changed in listOf(
        manifest.replace("day.png", "../day.png"),
        manifest.replace("</Icon>", "<Script>anything</Script></Icon>"),
        manifest.replace("DateTime.Minute", "DateTime.Second"),
      )) rejects(zip(entries + (manifestPath to changed.toByteArray())))
      for (missing in entries.keys) rejects(zip(entries - missing))
      rejects(zip(entries + ("$fancy/fallback.png" to dynamicSnapshot(kind, false))))
      rejects(zip(entries + (day to ByteArray(4 * 1024 * 1024 + 1))))
      rejects(zip(entries + ("$fancy/day.png" to ByteArray(4 * 1024 * 1024 + 1))))
      if (kind == NativeColorOsDynamicIcon.Kind.CLOCK) {
        rejects(zip(entries + ("$fancy/clock_day_hour_rectangle.png" to png(4))))
        rejects(zip(entries + ("$fancy/night_clock_day_minute_rectangle.png" to png(4))))
        rejects(zip(entries + ("$fancy/clock_day_second.png" to png(3))))
        rejects(zip(entries + (manifestPath to
          manifest.replace("#hour12*30+#minute/2", "#hour12*30").toByteArray())))
      }
    }
  }

  @Test fun dynamicManifestsCannotChangeExpressionsUpdaterViewportOrAddOtherMaml() {
    for (kind in NativeColorOsDynamicIcon.Kind.entries) {
      val entries = generatedDynamic(kind)
      val path = "$fancy/manifest.xml"
      val canonical = String(entries.getValue(path), Charsets.UTF_8)
      val alternatives = mutableListOf(
        canonical.replace("DateTime.Minute", "DateTime.Second"),
        canonical.replace("frameRate=\"0\"", "frameRate=\"1\""),
        canonical.replace("width=\"150\"", "width=\"240\""),
        canonical.replace("day.png", "https://example.invalid/day.png"),
        canonical.replace("</Icon>", "<VariableBinders /></Icon>"),
        canonical.replace("</Icon>", "<Script>anything</Script></Icon>"),
        canonical + "\n",
      )
      if (kind == NativeColorOsDynamicIcon.Kind.CALENDAR) {
        alternatives += canonical.replace("#date", "#year")
        alternatives += canonical.replace("format=\"EEEE\"", "format=\"EEE\"")
        alternatives += canonical.replace("#FF000000", "#FF101010")
      } else {
        alternatives += canonical.replace("#hour12*30+#minute/2", "#hour12*30")
        alternatives += canonical.replace("#minute*6", "#second*6")
        alternatives += canonical.replace("centerX=\"75\"", "centerX=\"120\"")
      }
      for (manifest in alternatives) {
        assertNotEquals("Mutation must change the canonical $kind manifest", canonical, manifest)
        rejects(zip(entries + (path to manifest.toByteArray())))
      }
    }
  }

  @Test fun generatedPairRequiresEveryLocalFileAndBothMatchingOrdinaryImages() {
    val entries = generatedPair() + mapOf(
      "res/drawable-xxhdpi/com.other.png" to png(),
      "nightmode/res/drawable-xxhdpi/com.other.png" to png(pixel = 1),
    )
    for (missing in listOf("$fancy/manifest.xml", "$fancy/day.png", "$fancy/night.png", day, night)) {
      rejects(zip(entries - missing))
    }
  }

  @Test fun generatedArtworkMustMatchTheOrdinaryPairByteForByte() {
    val entries = generatedPair()
    for (path in listOf("$fancy/day.png", "$fancy/night.png", day, night)) {
      rejects(zip(entries + (path to png(pixel = 2))))
    }
    rejects(zip(entries + mapOf("$fancy/day.png" to entries.getValue("$fancy/night.png"),
      "$fancy/night.png" to entries.getValue("$fancy/day.png"))))
  }

  @Test fun rejectsExtraFilesDirectoriesTraversalAndForeignPairPaths() {
    val entries = generatedPair()
    for (extra in listOf("$fancy/script.js", "$fancy/other.png", "$fancy/config.xml", "$fancy/",
      "$fancy/day/../day.png", "$fancy//day.png", "$fancy/Day.png",
      "fancy_icons/com.example/../manifest.xml", "fancy_icons/../com.example/manifest.xml",
      "nightmode/$fancy/manifest.xml")) {
      rejects(zip(entries + (extra to png())))
    }
    val foreign = entries.mapKeys { (path, _) ->
      if (path.startsWith("$fancy/")) path.replace(fancy, "fancy_icons/com.foreign") else path
    }
    rejects(zip(foreign))
  }

  @Test fun generatedPairStillRequiresValidBoundedPngsAndMatchingSquareDimensions() {
    val entries = generatedPair()
    val corrupt = png().also { it[29] = (it[29].toInt() xor 1).toByte() }
    for (invalid in listOf("not a PNG".toByteArray(), corrupt, png(513))) {
      rejects(zip(entries + mapOf(day to invalid, "$fancy/day.png" to invalid)))
    }
    // The manifest for 1px cannot authorize different dimensions, even when both copies agree.
    val larger = png(2)
    rejects(zip(entries + mapOf(day to larger, "$fancy/day.png" to larger)))
    rejects(zip(entries + mapOf(day to larger, night to larger,
      "$fancy/day.png" to larger, "$fancy/night.png" to larger)))
    val rectangular = png(2, height = 1)
    rejects(zip(entries + mapOf(day to rectangular, night to rectangular,
      "$fancy/day.png" to rectangular, "$fancy/night.png" to rectangular)))
    rejects(zip(entries + ("$fancy/manifest.xml" to ByteArray(64 * 1024 + 1))))
    rejects(zip(entries + ("$fancy/day.png" to ByteArray(8 * 1024 * 1024 + 1))))
  }

  @Test fun generatedPairsRejectCentralDirectoryPathChangesAndDuplicateFiles() {
    val entries = generatedPair()
    val path = "$fancy/day.png"
    val mismatch = zip(entries)
    val offset = lastIndexOf(mismatch, path.toByteArray())
    "bad".toByteArray().copyInto(mismatch, offset + path.length - 3)
    rejects(mismatch)
    val alias = "$fancy/dax.png"
    val duplicate = zip(entries + (alias to png()))
    var index = lastIndexOf(duplicate, alias.toByteArray())
    while (index >= 0) {
      path.toByteArray().copyInto(duplicate, index)
      index = lastIndexOf(duplicate, alias.toByteArray())
    }
    rejects(duplicate)
  }

  @Test fun rejectsArbitraryMamlOtherComponentsAndTraversalEvenWhenOuterEnvelopeLooksConverted() {
    for (name in listOf("fancy_icons/com.example/manifest.xml", "../icons.png", "wallpaper/default.png")) {
      rejects(zip(day to png(), night to png(), name to "<Root/>".toByteArray()))
    }
  }

  @Test fun requiresBothNativeDayAndNightTrees() {
    rejects(zip(day to png()))
    rejects(zip("res/drawable-night-xxhdpi/com.example.png" to png(), day to png()))
  }

  @Test fun rejectsStoreMetadataAndXmlDeclarations() {
    for (extra in listOf("<productId>store-id</productId>", "<rights>anything</rights>")) {
      withArchive(icons(), description.replace("</MIUI-Theme>", "$extra</MIUI-Theme>")) { file ->
        assertThrows(IllegalArgumentException::class.java) { ConvertedIconArchive.inspect(file, "CONVERTED_APK") }
      }
    }
    withArchive(icons(), "<!DOCTYPE MIUI-Theme [<!ENTITY x 'untrusted'>]>$description") { file ->
      assertThrows(IllegalArgumentException::class.java) { ConvertedIconArchive.inspect(file, "CONVERTED_APK") }
    }
  }

  @Test fun rejectsNonPngCorruptPngAndOversizedDimensions() {
    rejects(zip(day to "not a PNG".toByteArray(), night to png()))
    val corrupt = png().also { it[29] = (it[29].toInt() xor 1).toByte() }
    rejects(zip(day to corrupt, night to png()))
    rejects(zip(day to png(513), night to png()))
  }

  @Test fun usesCentralDirectoryNamesAndRejectsDuplicates() {
    val centralMismatch = icons()
    val offset = lastIndexOf(centralMismatch, day.toByteArray())
    "xml".toByteArray().copyInto(centralMismatch, offset + day.length - 3)
    rejects(centralMismatch)
    val second = "res/drawable-xxhdpi/com.examp1e.png"
    val duplicate = zip(day to png(), second to png(), night to png())
    var index = lastIndexOf(duplicate, second.toByteArray())
    while (index >= 0) {
      day.toByteArray().copyInto(duplicate, index)
      index = lastIndexOf(duplicate, second.toByteArray())
    }
    rejects(duplicate)
  }

  @Test fun previewChangeKeepsIconIdentityButChangesParentIdentity() {
    var first: ConvertedIconArchive.Proof? = null
    withArchive(icons()) { first = ConvertedIconArchive.inspect(it, "CONVERTED_APK") }
    withArchive(icons(), preview = png(2)) {
      val second = ConvertedIconArchive.inspect(it, "CONVERTED_APK")
      assertEquals(first!!.icons, second.icons)
      assertNotEquals(first.archive, second.archive)
    }
  }

  @Test fun approvalRequiresExactLocalAndParentAssociationsAndNoStoreIdentifiers() {
    val proof = ConvertedIconArchive.Proof("CONVERTED_APK",
      ConvertedIconArchive.Digests("parent-hash", "parent-sha256"),
      ConvertedIconArchive.Digests("icon-hash", "icon-sha256"))
    val grant = ConvertedIconGrant(proof, "icon-local", "parent-local")
    val icon = ConvertedIconResource("icon-local", "icon-hash", null, null, null)
    val parent = ConvertedIconResource("parent-local", "parent-hash", null, null, null)
    assertTrue(grant.matches(icon, parent))
    assertFalse(grant.matches(icon.copy(localId = "other"), parent))
    assertFalse(grant.matches(icon.copy(hash = "other"), parent))
    assertFalse(grant.matches(icon, parent.copy(localId = "other")))
    assertFalse(grant.matches(icon, parent.copy(hash = "other")))
    for (store in listOf(icon.copy(onlineId = "store"), icon.copy(productId = "store"), icon.copy(assemblyId = "store"))) {
      assertFalse(grant.matches(store, parent))
    }
    assertFalse(grant.matches(icon, parent.copy(productId = "store")))
  }

  private fun rejects(icons: ByteArray) = withArchive(icons) { file ->
    assertThrows(Exception::class.java) { ConvertedIconArchive.inspect(file, "CONVERTED_APK") }
  }

  private fun withArchive(icons: ByteArray, metadata: String = description, preview: ByteArray = png(),
    block: (java.io.File) -> Unit) {
    val directory = Files.createTempDirectory("keihome-converted-test-").toFile()
    try {
      val file = java.io.File(directory, "icons.mtz")
      file.writeBytes(zip("description.xml" to metadata.toByteArray(), "icons" to icons,
        "preview/preview_icons_0.png" to preview))
      block(file)
    } finally { directory.deleteRecursively() }
  }

  private fun icons(component: String = "com.example") = zip(
    "res/drawable-xxhdpi/$component.png" to png(), "nightmode/res/drawable-xxhdpi/$component.png" to png())

  private fun generatedPair(component: String = "com.example"): Map<String, ByteArray> {
    val light = png()
    val dark = png(pixel = 1)
    return NativeDayNightIcon.entries(component, light, dark) +
      ("nightmode/res/drawable-xxhdpi/$component.png" to dark)
  }

  private fun generatedPalette(): Map<String, ByteArray> = NativePaletteIcon.entries("com.example",
    NativeIconPngFixtures.rgba(), NativeIconPngFixtures.rgba { _, _ -> 128 },
    NativeIconPngFixtures.mask(), NativeIconPngFixtures.indexedMask())

  private fun generatedDynamic(
    kind: NativeColorOsDynamicIcon.Kind, fixedDark: Boolean? = null, dim: Boolean = false, clockSize: Int = 3,
    nativeName: String = "com.example", snapshots: Boolean = false,
  ): Map<String, ByteArray> {
    val assets = linkedMapOf("day.png" to png(2), "night.png" to png(2, pixel = 1))
    if (snapshots) {
      assets["fallback.png"] = dynamicSnapshot(kind, false)
      assets["fallback-night.png"] = dynamicSnapshot(kind, true)
    }
    if (kind == NativeColorOsDynamicIcon.Kind.CLOCK) {
      for ((index, leaf) in listOf("clock_day_bg.png", "clock_day_hour_rectangle.png",
        "clock_day_minute_rectangle.png", "night_clock_day_bg.png", "night_clock_day_hour_rectangle.png",
        "night_clock_day_minute_rectangle.png").withIndex()) assets[leaf] = png(clockSize, pixel = (index + 2).toByte())
    }
    return NativeColorOsDynamicIcon.entries(nativeName, kind, assets, fixedDark, dim)
  }

  /** A rendered fixed-time/date fallback, with live backgrounds and hands kept separately. */
  private fun dynamicSnapshot(kind: NativeColorOsDynamicIcon.Kind, dark: Boolean): ByteArray {
    val image = BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
      graphics.color = if (dark) Color(32, 32, 32) else Color.WHITE
      graphics.fillRect(0, 0, 96, 96)
      graphics.color = if (dark) Color.WHITE else Color.BLACK
      graphics.stroke = BasicStroke(4f)
      if (kind == NativeColorOsDynamicIcon.Kind.CLOCK) {
        graphics.drawOval(8, 8, 80, 80)
        graphics.drawLine(48, 48, 24, 34)
        graphics.drawLine(48, 48, 74, 32)
      } else {
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 44)
        graphics.drawString("23", 20, 72)
        graphics.color = Color(10, 127, 251)
        graphics.fillRect(8, 8, 80, 16)
      }
    } finally { graphics.dispose() }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
  }

  private fun zip(entries: Map<String, ByteArray>) = zip(*entries.toList().toTypedArray())

  private fun zip(vararg entries: kotlin.Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
    ZipOutputStream(bytes).use { zip -> entries.forEach { (name, content) ->
      zip.putNextEntry(ZipEntry(name).apply { time = 0 }); zip.write(content); zip.closeEntry()
    } }
  }.toByteArray()

  private fun png(width: Int = 1, height: Int = width, pixel: Byte = 0): ByteArray = ByteArrayOutputStream().also { bytes ->
    val output = DataOutputStream(bytes)
    output.writeLong(-8552249625308161526L)
    fun chunk(name: String, data: ByteArray) {
      val type = name.toByteArray(Charsets.US_ASCII)
      output.writeInt(data.size); output.write(type); output.write(data)
      output.writeInt(CRC32().apply { update(type); update(data) }.value.toInt())
    }
    chunk("IHDR", ByteArrayOutputStream().also { header -> DataOutputStream(header).apply {
      writeInt(width); writeInt(height); write(byteArrayOf(8, 6, 0, 0, 0))
    } }.toByteArray())
    chunk("IDAT", ByteArrayOutputStream().also { pixels ->
      DeflaterOutputStream(pixels).use { it.write(ByteArray((1 + width * 4) * height) { index ->
        if (index % (1 + width * 4) == 0) 0 else pixel
      }) }
    }.toByteArray())
    chunk("IEND", byteArrayOf())
  }.toByteArray()

  private fun hash(bytes: ByteArray, algorithm: String) = MessageDigest.getInstance(algorithm).digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

  private fun lastIndexOf(bytes: ByteArray, value: ByteArray): Int =
    (bytes.size - value.size downTo 0).firstOrNull { start -> value.indices.all { bytes[start + it] == value[it] } } ?: -1

  companion object {
    private const val day = "res/drawable-xxhdpi/com.example.png"
    private const val night = "nightmode/res/drawable-xxhdpi/com.example.png"
    private const val fancy = "fancy_icons/com.example"
    private const val description = "<MIUI-Theme><title>Community</title><author>Author</author>" +
      "<designer>Author</designer><version>1.0</version><uiVersion>13</uiVersion>" +
      "<description>Local icon import by KeiHome.</description></MIUI-Theme>"
  }
}
