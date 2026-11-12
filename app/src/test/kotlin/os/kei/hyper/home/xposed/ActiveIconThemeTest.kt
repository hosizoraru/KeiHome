package os.kei.hyper.home.xposed

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import os.kei.hyper.home.iconpack.IconThemeMetadata
import os.kei.hyper.home.status.SystemIconThemeReport

class ActiveIconThemeTest {
  @get:Rule val temporary = TemporaryFolder()
  private val active get() = File(temporary.root, "icons")
  private val stock get() = File(temporary.root, "stock-icons")
  private val mod get() = File(temporary.root, "mod-icons")
  private fun reader(maxEntries: Int = os.kei.hyper.home.iconpack.IconPackLimits().maxEntries) =
    ActiveIconTheme(active, stock, mod, maxEntries)

  @Test fun indexesAnImportedArchiveLargerThanTheOld64MiBLimitWithoutLoadingItsPayload() {
    ZipOutputStream(active.outputStream()).use { zip ->
      zip.setLevel(0)
      zip.putNextEntry(ZipEntry("unused-padding.bin"))
      val block = ByteArray(8192)
      repeat(8193) { zip.write(block) }
      zip.closeEntry()
      zip.putNextEntry(ZipEntry("res/drawable/com.sample.large.png"))
      zip.write(png())
      zip.closeEntry()
    }
    assertTrue(active.length() > 64L * 1024 * 1024)
    val result = reader().snapshot(setOf("com.sample.large"))
    assertTrue(result.complete)
    assertEquals(setOf("com.sample.large"), result.coveredPackages)
    assertFalse(ActiveIconTheme(active, stock, mod, maxArchiveBytes = active.length() - 1)
      .describe().complete)
    assertTrue(ActiveIconTheme(active, stock, mod, maxArchiveBytes = active.length())
      .describe().complete)
  }

  @Test fun inventoriesMoreThanTheOldEntryCountWithoutDroppingLateIcons() {
    archive(active, (0..16_384).associate { "res/drawable/com.sample.icon$it.png" to png() })
    val report = reader().describe()
    assertTrue(report.complete)
    assertEquals(16_385, report.resourceCount)
    assertEquals(setOf("com.sample.icon16384"),
      reader().snapshot(setOf("com.sample.icon16384")).coveredPackages)
  }

  @Test fun manySmallDescriptorsMayExceedOneMiBButStillHaveABoundedAggregate() {
    val xml = ("<Icon><Image/><!--" + " ".repeat(4096) + "--></Icon>").toByteArray()
    archive(active, (0..299).associate { "fancy_icons/com.sample.icon$it/manifest.xml" to xml } +
      mapOf("description.xml" to description("Original source", "2.4")))
    assertTrue(300L * xml.size > 1024 * 1024)
    assertTrue(reader().describe().complete)
    assertEquals(300, reader().describe().resourceCount)
    val bounded = ActiveIconTheme(active, stock, mod, maxDescriptorsTotal = 299L * xml.size)
    val partial = bounded.describe()
    assertFalse(partial.complete)
    assertEquals(SystemIconThemeReport.Source.THEME, partial.source)
    assertEquals("Original source", partial.title)
    assertEquals("2.4", partial.version)
    assertTrue(bounded.snapshot(setOf("com.sample.icon0")).coveredPackages.isEmpty())
  }

  @Test fun descriptorBudgetKeepsStampedLabelsButNeverPublishesPartialCoverage() {
    val xml = descriptor()
    archive(active, linkedMapOf(
      "res/drawable/com.sample.first.png" to png(),
      "nightmode/res/drawable/com.sample.first.png" to png(),
      "fancy_icons/com.sample.first/manifest.xml" to xml,
      "fancy_icons/com.sample.second/manifest.xml" to xml,
      "res/drawable/com.sample.later.png" to png(),
      "description.xml" to description("Generic handoff", "1"),
      // Metadata is deliberately last: labels must be read before the scan stops.
      IconThemeMetadata.PATH to IconThemeMetadata("Actual MTZ title", "2026.11", "Artist").encode(),
    ))
    val index = ActiveIconTheme(active, stock, mod, maxDescriptorsTotal = xml.size.toLong())
    val report = index.describe()
    assertEquals(SystemIconThemeReport.Source.THEME, report.source)
    assertEquals("Actual MTZ title", report.title)
    assertEquals("2026.11", report.version)
    assertEquals(active.absolutePath, report.archivePath)
    assertFalse(report.complete)
    assertEquals(0, report.resourceCount)
    assertEquals(0, report.nightResourceCount)
    assertEquals(report, SystemIconThemeReport.decode(report.encode()))
    val snapshot = index.snapshot(setOf("com.sample.first", "com.sample.second", "com.sample.later"),
      allowStockFallback = true, modEnabled = true)
    assertFalse(snapshot.complete)
    assertTrue(snapshot.coveredPackages.isEmpty())
  }

  @Test fun entryAndIndividualDescriptorBoundsAlsoRetainKnownSourceAndLabels() {
    archive(active, mapOf(
      "res/drawable/com.sample.first.png" to png(),
      "fancy_icons/com.sample.large/manifest.xml" to ByteArray(65_537) { 32 },
      IconThemeMetadata.PATH to IconThemeMetadata("Bounded source", "3").encode(),
    ))
    for (index in listOf(reader(), reader(maxEntries = 1))) {
      val report = index.describe()
      assertEquals(SystemIconThemeReport.Source.THEME, report.source)
      assertEquals("Bounded source", report.title)
      assertEquals("3", report.version)
      assertFalse(report.complete)
      val snapshot = index.snapshot(setOf("com.sample.first"))
      assertFalse(snapshot.complete)
      assertTrue(snapshot.coveredPackages.isEmpty())
    }
    assertFalse(ActiveIconTheme(active, stock, mod, maxArchiveBytes = active.length() - 1).describe().complete)
    assertNull(ActiveIconTheme(active, stock, mod, maxArchiveBytes = active.length() - 1).describe().title)
  }

  @Test fun partialInventoriesRemainCachedUntilInvalidationOrSourceChange() {
    fun entries(title: String, version: String) = mapOf(
      "fancy_icons/com.sample.first/manifest.xml" to descriptor(),
      "fancy_icons/com.sample.second/manifest.xml" to descriptor(),
      IconThemeMetadata.PATH to IconThemeMetadata(title, version).encode(),
    )
    archive(active, entries("One", "1"), noCompression = true)
    val index = ActiveIconTheme(active, stock, mod, maxDescriptorsTotal = descriptor().size.toLong())
    val before = index.describe()
    assertFalse(before.complete)
    val modified = Files.getLastModifiedTime(active.toPath())
    val size = active.length()
    archive(active, entries("Two", "2"), noCompression = true)
    assertEquals(size, active.length())
    Files.setLastModifiedTime(active.toPath(), modified)
    assertEquals(before, index.describe())
    index.invalidate()
    val after = index.describe()
    assertFalse(after.complete)
    assertEquals("Two", after.title)
    assertEquals("2", after.version)
    assertNotEquals(before.revision, after.revision)
    archive(active, entries("New", "3"), noCompression = true)
    Files.setLastModifiedTime(active.toPath(), FileTime.fromMillis(modified.toMillis() + 10_000))
    assertEquals("New", index.describe().title)
  }

  @Test fun partialTimeInventoryDoesNotPublishTargetsSeenBeforeTheBudgetStop() {
    val dynamic = os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon
    val entries = dynamic.entries("sample.calendar.Main", os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon.Kind.CALENDAR,
      mapOf("day.png" to png().copyOf(33), "night.png" to png().copyOf(33)))
    archive(active, entries + mapOf("fancy_icons/sample.later/manifest.xml" to descriptor()))
    val budget = entries.filterKeys { it.endsWith("manifest.xml") }.values.sumOf { it.size.toLong() }
    val index = ActiveIconTheme(active, stock, mod, maxDescriptorsTotal = budget)
    assertFalse(index.describe().complete)
    assertTrue(index.timeIconPackages(setOf("sample.calendar"), mapOf("sample.calendar" to setOf(".Main"))).isEmpty())
  }

  @Test fun displayMetadataFallsBackPerFieldToBoundedOriginalDescription() {
    val original = description("Original title", "2.7")
    val cases = listOf(
      null to ("Original title" to "2.7"),
      "not metadata".toByteArray() to ("Original title" to "2.7"),
      ByteArray(4097) to ("Original title" to "2.7"),
      IconThemeMetadata(null, "4").encode() to ("Original title" to "4"),
      IconThemeMetadata("Stamped", null).encode() to ("Stamped" to "2.7"),
    )
    for ((stamped, expected) in cases) {
      archive(active, mapOf("description.xml" to original) +
        (stamped?.let { mapOf(IconThemeMetadata.PATH to it) } ?: emptyMap()))
      val report = reader().describe()
      assertTrue(report.complete)
      assertEquals(SystemIconThemeReport.Source.THEME, report.source)
      assertEquals(expected.first, report.title)
      assertEquals(expected.second, report.version)
    }
    archive(active, mapOf("description.xml" to description("t".repeat(121), "v".repeat(81))))
    val bounded = reader().describe()
    assertEquals("t".repeat(120), bounded.title)
    assertEquals("v".repeat(80), bounded.version)
    for (badDescription in listOf(ByteArray(65_537), "<MIUI-Theme><title>broken".toByteArray(),
      "<!DOCTYPE MIUI-Theme [<!ENTITY x SYSTEM 'file:///unread'>]><MIUI-Theme><title>&x;</title></MIUI-Theme>".toByteArray())) {
      archive(active, mapOf("description.xml" to badDescription))
      val report = reader().describe()
      assertTrue(report.complete)
      assertNull(report.title)
      assertNull(report.version)
    }
  }

  @Test fun directoryDescriptorBudgetSupportsLargeCollectionsAndStillRejectsOverflow() {
    val xml = ("<Icon><Image/><!--" + " ".repeat(4096) + "--></Icon>").toByteArray()
    archive(active, mapOf(IconThemeMetadata.PATH to IconThemeMetadata("Applied icons", "8").encode()))
    repeat(300) { i ->
      File(mod, "fancy_icons/com.sample.icon$i/manifest.xml").apply {
        parentFile.mkdirs()
        writeBytes(xml)
      }
    }
    assertTrue(reader().describe().complete)
    assertEquals(300, reader().describe().resourceCount)
    val bounded = ActiveIconTheme(active, stock, mod, maxDescriptorsTotal = 299L * xml.size)
    val report = bounded.describe()
    assertFalse(report.complete)
    assertEquals(SystemIconThemeReport.Source.THEME, report.source)
    assertEquals("Applied icons", report.title)
    assertEquals("8", report.version)
    val snapshot = bounded.snapshot(setOf("com.sample.icon0"), modEnabled = true)
    assertFalse(snapshot.complete)
    assertTrue(snapshot.coveredPackages.isEmpty())
  }

  @Test fun timeRefreshTargetsOnlyCanonicalTemplatesAndExactComponentOwners() {
    val dynamic = os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon
    val kind = os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon.Kind.CALENDAR
    val entries = dynamic.entries("sample.calendar.Main", kind,
      mapOf("day.png" to png().copyOf(33), "night.png" to png().copyOf(33)))
    archive(active, entries + mapOf("fancy_icons/sample.foreign/manifest.xml" to descriptor()))
    val index = reader()
    val packages = setOf("sample.calendar", "sample.calendar.extra", "sample.foreign")
    assertEquals(mapOf("sample.calendar" to kind), index.timeIconPackages(packages,
      mapOf("sample.calendar" to setOf(".Main"))))
    assertTrue(index.timeIconPackages(packages, emptyMap()).isEmpty())
    archive(active, mapOf("fancy_icons/sample.calendar.Main/manifest.xml" to descriptor()))
    index.invalidate()
    assertTrue(index.timeIconPackages(packages, mapOf("sample.calendar" to setOf(".Main"))).isEmpty())
  }

  @Test fun reportsNightResourcesFromTheSystemModDirectory() {
    val day = File(mod, "res/drawable/com.sample.mod.png")
    val night = File(mod, "nightmode/res/drawable/com.sample.mod.png")
    requireNotNull(day.parentFile).mkdirs()
    requireNotNull(night.parentFile).mkdirs()
    day.writeBytes(png())
    night.writeBytes(png())
    val report = reader().describe()
    assertTrue(report.complete)
    assertEquals(1, report.resourceCount)
    assertEquals(1, report.nightResourceCount)
  }

  @Test fun reportedSourceAndNightInventoryFollowTheActiveArchive() {
    archive(stock, mapOf("res/drawable/com.sample.stock.png" to png()))
    val index = reader()
    assertEquals(os.kei.hyper.home.status.SystemIconThemeReport.Source.DEFAULT, index.describe().source)
    archive(active, mapOf(
      "description.xml" to "<MIUI-Theme><title>Community icons</title></MIUI-Theme>".toByteArray(),
      "res/drawable/com.sample.app.png" to png(),
      "nightmode/res/drawable/com.sample.app.png" to png(),
    ))
    val report = index.describe()
    assertEquals(os.kei.hyper.home.status.SystemIconThemeReport.Source.THEME, report.source)
    assertEquals("Community icons", report.title)
    assertEquals(1, report.resourceCount)
    assertEquals(1, report.nightResourceCount)
    assertTrue(report.complete)
    active.writeText("broken replacement")
    assertFalse(index.describe().complete)
    assertTrue(active.delete())
    assertEquals(os.kei.hyper.home.status.SystemIconThemeReport.Source.DEFAULT, index.describe().source)
  }

  @Test fun indexesStaticVariantsAndBothDynamicFormatsWithoutPrefixGuessing() {
    archive(active, mapOf(
      "res/drawable/com.sample.day.png" to png(),
      "res/drawable-night-xxhdpi/com.sample.night.png" to png(),
      "fancy_icons/com.sample.clock/manifest.xml" to descriptor(),
      "animating_icons/com.sample.motion/fancy/manifest.xml" to descriptor(),
      "res/drawable-xhdpi/com.sample.unknown.Activity.png" to png(),
      "res/drawable-xhdpi/com.sample.other.extra.png" to png(),
      "res/drawable-xhdpi/icon_mask.png" to png(),
    ))
    val requested = setOf("com.sample.day", "com.sample.night", "com.sample.clock", "com.sample.motion",
      "com.sample.unknown", "com.sample.other", "com.sample.missing")
    val result = reader().snapshot(requested)
    assertTrue(result.complete)
    assertEquals(setOf("com.sample.day", "com.sample.night", "com.sample.clock", "com.sample.motion"),
      result.coveredPackages)
  }

  @Test fun mapsOnlySuppliedComponentsToTheirOwningPackage() {
    archive(active, mapOf(
      "res/drawable-xhdpi/com.sample.app.Main.png" to png(),
      "fancy_icons/com.vendor.external.Clock/manifest.xml" to descriptor(),
    ))
    val index = reader()
    val packages = setOf("com.sample.app", "com.sample.clock", "com.sample")
    assertTrue(index.snapshot(packages).coveredPackages.isEmpty())
    assertEquals(setOf("com.sample.app", "com.sample.clock"), index.snapshot(packages, mapOf(
      "com.sample.app" to setOf(".Main"),
      "com.sample.clock" to setOf("com.vendor.external.Clock"),
    )).coveredPackages)
    assertTrue(index.snapshot(setOf("com.sample.unrelated")).coveredPackages.isEmpty())
  }

  @Test fun classicLayeredIconsRequireADeclaredLayerAndItsDescriptor() {
    val config = "<Config><Variables><Variable name='layer0' value='0' type='string'/></Variables></Config>"
      .toByteArray()
    archive(active, mapOf(
      "layer_animating_icons/com.sample.classic/config.xml" to config,
      "layer_animating_icons/com.sample.classic/0/fancy/manifest.xml" to descriptor(),
      "layer_animating_icons/com.sample.unlisted/config.xml" to config,
      "layer_animating_icons/com.sample.unlisted/1/fancy/manifest.xml" to descriptor(),
      "layer_animating_icons/com.sample.orphan/0/fancy/manifest.xml" to descriptor(),
      "layer_animating_icons/com.sample.empty/config.xml" to "<Config/>".toByteArray(),
      "layer_animating_icons/com.sample.empty/0/fancy/manifest.xml" to descriptor(),
      "layer_animating_icons/com.sample.quiet/config.xml" to config,
      "layer_animating_icons/com.sample.quiet/quiet/quietImage.png" to png(),
    ))
    val result = reader().snapshot(setOf("com.sample.classic", "com.sample.unlisted", "com.sample.orphan",
      "com.sample.empty", "com.sample.quiet"))
    assertTrue(result.complete)
    assertEquals(setOf("com.sample.classic"), result.coveredPackages)
  }

  @Test fun staticLayersRequireBothImagesAndDoNotImplyNightArtwork() {
    archive(active, mapOf(
      "res/drawable-xxhdpi/com.sample.layers/0.png" to png(),
      "res/drawable-xxhdpi/com.sample.layers/1.png" to png(),
      "res/drawable-xxhdpi/com.sample.half/0.png" to png(),
      "res/drawable-xxhdpi/com.sample.bad/0.png" to png(),
      "res/drawable-xxhdpi/com.sample.bad/1.png" to "not an image".toByteArray(),
    ))
    val report = reader().describe()
    assertTrue(report.complete)
    assertEquals(1, report.resourceCount)
    assertEquals(0, report.nightResourceCount)
    assertEquals(setOf("com.sample.layers"),
      reader().snapshot(setOf("com.sample.layers", "com.sample.half", "com.sample.bad")).coveredPackages)
  }

  @Test fun extractedStaticLayersFollowTheSameCompletePairRule() {
    mod.mkdirs()
    File(mod, "res/drawable-xxhdpi/com.sample.layers").apply {
      mkdirs()
      File(this, "0.png").writeBytes(png())
      File(this, "1.png").writeBytes(png())
    }
    File(mod, "res/drawable-xxhdpi/com.sample.half").apply {
      mkdirs()
      File(this, "0.png").writeBytes(png())
    }
    val report = reader().describe()
    assertTrue(report.complete)
    assertEquals(1, report.resourceCount)
    assertEquals(0, report.nightResourceCount)
  }

  @Test fun stockAndModDoNotOverrideNativeSourceGates() {
    archive(active, mapOf("res/drawable/com.sample.active.png" to png()))
    archive(stock, mapOf("res/drawable/com.sample.stock.png" to png()))
    assertTrue(mod.mkdir())
    File(mod, "com.sample.mod.png").writeBytes(png())
    val packages = setOf("com.sample.active", "com.sample.stock", "com.sample.mod")
    val index = reader()
    assertEquals(setOf("com.sample.active"), index.snapshot(packages).coveredPackages)
    assertEquals(packages, index.snapshot(packages, allowStockFallback = true, modEnabled = true).coveredPackages)
    assertTrue(active.delete())
    assertEquals(setOf("com.sample.stock"), index.snapshot(packages).coveredPackages)
  }

  @Test fun missingSourcesAreCompleteButCorruptActivePackCannotBorrowStockCoverage() {
    val index = reader()
    assertTrue(index.snapshot(setOf("com.sample.app")).complete)
    archive(stock, mapOf("res/drawable/com.sample.app.png" to png()))
    active.writeText("not a zip")
    val result = index.snapshot(setOf("com.sample.app"), allowStockFallback = true)
    assertFalse(result.complete)
    assertTrue(result.coveredPackages.isEmpty())
  }

  @Test fun ignoresNonResourcesInvalidDescriptorsAndEmptyOrUnrecognizedImages() {
    archive(active, mapOf(
      "../res/drawable/com.sample.bad.png" to png(),
      "res/drawable/com.sample.nested/unused.png" to png(),
      "previews/com.sample.preview.png" to png(),
      "res/drawable/com.sample.empty.png" to byteArrayOf(),
      "res/drawable/com.sample.notpng.png" to "not an image".toByteArray(),
      "fancy_icons/com.sample.emptyxml/manifest.xml" to "<Icon/>".toByteArray(),
      "fancy_icons/com.sample.broken/manifest.xml" to "<Icon><Image>".toByteArray(),
      "fancy_icons/com.sample.orphan/den480/bg.png" to png(),
      "animating_icons/com.sample.orphan/quiet/quietImage.png" to png(),
      "fancy_icons/com.sample.entity/manifest.xml" to
        "<!DOCTYPE Icon [<!ENTITY x SYSTEM 'file:///unread'>]><Icon><Image>&x;</Image></Icon>".toByteArray(),
    ))
    val result = reader().snapshot(setOf("com.sample.bad", "com.sample.nested", "com.sample.preview",
      "com.sample.empty", "com.sample.notpng", "com.sample.emptyxml", "com.sample.broken",
      "com.sample.orphan", "com.sample.entity"))
    assertTrue(result.complete)
    assertTrue(result.coveredPackages.isEmpty())
  }

  @Test fun entryAndDescriptorLimitsDiscardPartialCoverage() {
    archive(active, mapOf(
      "res/drawable/com.sample.app.png" to png(),
      "res/drawable/com.sample.other.png" to png(),
    ))
    assertFalse(reader(maxEntries = 1).snapshot(setOf("com.sample.app")).complete)
    archive(active, mapOf(
      "res/drawable/com.sample.app.png" to png(),
      "fancy_icons/com.sample.clock/manifest.xml" to ByteArray(65_537) { 32 },
    ))
    val result = reader().snapshot(setOf("com.sample.app"))
    assertFalse(result.complete)
    assertTrue(result.coveredPackages.isEmpty())
  }

  @Test fun sourceStampChangeAndRemovalReplaceOldCoverage() {
    archive(active, mapOf("res/drawable/com.sample.old.png" to png()))
    val index = reader()
    val packages = setOf("com.sample.old", "com.sample.new")
    val before = index.snapshot(packages)
    archive(active, mapOf("res/drawable/com.sample.new.png" to png()))
    Files.setLastModifiedTime(active.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 10_000))
    val after = index.snapshot(packages)
    assertNotEquals(before.signature, after.signature)
    assertEquals(setOf("com.sample.new"), after.coveredPackages)
    assertTrue(active.delete())
    assertTrue(index.snapshot(packages).coveredPackages.isEmpty())
  }

  @Test fun invalidateReindexesEvenWhenArchiveStampIsUnchanged() {
    archive(active, mapOf("res/drawable/com.sample.one.png" to png()))
    val index = reader()
    val packages = setOf("com.sample.one", "com.sample.two")
    val before = index.snapshot(packages)
    val modified = Files.getLastModifiedTime(active.toPath())
    val size = active.length()
    archive(active, mapOf("res/drawable/com.sample.two.png" to png()))
    assertEquals(size, active.length())
    Files.setLastModifiedTime(active.toPath(), modified)
    assertEquals(before, index.snapshot(packages))
    index.invalidate()
    val after = index.snapshot(packages)
    assertNotEquals(before.signature, after.signature)
    assertEquals(setOf("com.sample.two"), after.coveredPackages)
  }

  @Test fun atomicReplacementWithSameSizeAndTimeChangesFileIdentity() {
    archive(active, mapOf("res/drawable/com.sample.one.png" to png()))
    val index = reader()
    val packages = setOf("com.sample.one", "com.sample.two")
    val before = index.snapshot(packages)
    val replacement = temporary.newFile("replacement")
    archive(replacement, mapOf("res/drawable/com.sample.two.png" to png()))
    assertEquals(active.length(), replacement.length())
    Files.setLastModifiedTime(replacement.toPath(), Files.getLastModifiedTime(active.toPath()))
    Files.move(replacement.toPath(), active.toPath(), StandardCopyOption.REPLACE_EXISTING)
    val after = index.snapshot(packages)
    assertNotEquals(before.signature, after.signature)
    assertEquals(setOf("com.sample.two"), after.coveredPackages)
  }

  @Test fun modDirectoryIgnoresSymlinksAndHonorsExplicitNestedInvalidation() {
    assertTrue(mod.mkdir())
    val outside = temporary.newFile("outside.png").apply { writeBytes(png()) }
    Files.createSymbolicLink(File(mod, "com.sample.link.png").toPath(), outside.toPath())
    val nested = File(mod, "res/drawable-night").apply { mkdirs() }
    val icon = File(nested, "com.sample.app.png").apply { writeBytes(png()) }
    val index = reader()
    val packages = setOf("com.sample.app", "com.sample.link")
    assertEquals(setOf("com.sample.app"), index.snapshot(packages, modEnabled = true).coveredPackages)
    icon.writeText("invalid")
    index.invalidate()
    assertTrue(index.snapshot(packages, modEnabled = true).coveredPackages.isEmpty())
  }

  private fun archive(file: File, entries: Map<String, ByteArray>, noCompression: Boolean = false) {
    ZipOutputStream(file.outputStream()).use { zip ->
      if (noCompression) zip.setLevel(0)
      for ((name, bytes) in entries) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0 })
        zip.write(bytes)
        zip.closeEntry()
      }
    }
  }

  private fun descriptor() = "<Icon><Image src='bg.png'/></Icon>".toByteArray()
  private fun description(title: String, version: String) =
    "<MIUI-Theme><title>$title</title><version>$version</version></MIUI-Theme>".toByteArray()

  // Only header recognition is in scope; pixel decoding remains the launcher's responsibility.
  private fun png(): ByteArray = byteArrayOf(
    137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
    0, 0, 0, 1, 0, 0, 0, 1,
  )


  @Test fun qualifiedStaticNamesProtectOnlyTheirExactComponentOwnerAndKeepLegacyCandidates() {
    archive(active, mapOf(
      "res/drawable-xxhdpi/com.sample.debug#com.shared.Main.png" to png(),
      "res/drawable-xxhdpi/com.legacy.Main.png" to png(),
      "res/drawable-xxhdpi/com.bad#com.other#Main.png" to png(),
    ))
    val packages = setOf("com.sample.debug", "com.other.debug", "com.legacy.owner", "com.bad")
    val components = mapOf(
      "com.sample.debug" to setOf("com.shared.Main"),
      "com.other.debug" to setOf("com.shared.Main"),
      "com.legacy.owner" to setOf("com.legacy.Main"),
      "com.bad" to setOf("com.other#Main"),
    )
    val index = reader()
    assertEquals(setOf("com.sample.debug", "com.legacy.owner"), index.snapshot(packages, components).coveredPackages)
    assertEquals(2, index.describe().resourceCount)
  }

  @Test fun samePrefixComponentsRequireBareCanonicalNamesInsteadOfInventedQualifiedNames() {
    archive(active, mapOf("res/drawable-xxhdpi/com.sample#com.sample.Main.png" to png()))
    val index = reader()
    val components = mapOf("com.sample" to setOf(".Main"))
    assertTrue(index.snapshot(setOf("com.sample"), components).coveredPackages.isEmpty())
    archive(active, mapOf("res/drawable-xxhdpi/com.sample.Main.png" to png()))
    index.invalidate()
    assertEquals(setOf("com.sample"), index.snapshot(setOf("com.sample"), components).coveredPackages)
  }
}
