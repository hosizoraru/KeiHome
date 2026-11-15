package os.kei.hyper.home.iconpack

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import os.kei.hyper.home.xposed.LocalNativeIconValidator

class PerfectLayeredIconTest {
  @get:Rule val temporary = TemporaryFolder()
  private val png = NativeIconPngFixtures.rgba(4)
  private val static = "res/drawable-xxhdpi/com.example/"
  private val dynamic = "layer_animating_icons/com.example/"

  @Test fun staticPairIsOrderedLayersAndNeverAnIndependentDarkAppearance() {
    val index = inspect(pair())
    val entry = index.entries.single()
    val artwork = entry.light as IconPackArtwork.Archive
    assertEquals(listOf(static + "0.png", static + "1.png"), artwork.layers.map { it.single().path })
    assertSame(entry.light, entry.dark)
    assertFalse(artwork.snapshot)
    assertEquals(artwork.layers.map { it.single() }, artwork.layers.map {
      selectArchiveVariant(it, true, 480, 37)
    })
  }

  @Test fun incompleteStaticPairsAreSkippedWithoutInventingBackgrounds() {
    for (missing in listOf("0.png", "1.png")) {
      val index = inspect(pair().filterKeys { it != static + missing } +
        ("res/drawable-xxhdpi/com.other.png" to png))
      assertEquals(listOf("com.other"), index.entries.map { it.nativeName })
    }
  }

  @Test fun multipleDensitiesStayGroupedByLayerAndChooseMatchingDensity() {
    val entries = pair() + pair("res/drawable-xhdpi/com.example/")
    val artwork = inspect(entries).entries.single().light as IconPackArtwork.Archive
    assertEquals(listOf(2, 2), artwork.layers.map { it.size })
    assertTrue(artwork.layers.all {
      selectArchiveVariant(it, false, 320, 37)!!.path.startsWith("res/drawable-xhdpi/")
    })
  }

  @Test fun partialDensityCannotBorrowTheOtherDensityForeground() {
    val index = inspect(pair() + ("res/drawable-xhdpi/com.example/0.png" to png))
    val artwork = index.entries.single().light as IconPackArtwork.Archive
    assertEquals(listOf(1, 1), artwork.layers.map { it.size })
  }

  @Test fun actualNightmodeDirectoriesSupplySeparateCompleteArtwork() {
    val entry = inspect(pair() + pair("nightmode/$static")).entries.single()
    assertNotEquals(entry.light, entry.dark)
    val dark = entry.dark as IconPackArtwork.Archive
    assertTrue(dark.layers.flatten().all { it.path.startsWith("nightmode/") })
    assertTrue(dark.layers.all { selectArchiveVariant(it, true, 480, 37) != null })
    assertTrue((entry.light as IconPackArtwork.Archive).layers.flatten().none { it.path.startsWith("nightmode/") })
  }

  @Test fun drawableNightQualifiersAlsoSupplySeparateAppearance() {
    val entry = inspect(pair() + pair("res/drawable-night-xxhdpi/com.example/")).entries.single()
    assertNotEquals(entry.light, entry.dark)
    assertTrue((entry.dark as IconPackArtwork.Archive).layers.flatten()
      .all { it.qualifiers.night == IconNightMode.DARK })
  }

  @Test fun dynamicPreviewPrefersRootQuietAndMarksItAsSnapshot() {
    val index = inspect(dynamic() + (dynamic + "quiet/quietImage.png" to png))
    val entry = index.entries.single()
    val artwork = entry.light as IconPackArtwork.Archive
    assertTrue(artwork.snapshot)
    assertTrue(artwork.layers.isEmpty())
    assertEquals(dynamic + "quiet/quietImage.png", artwork.variants.single().path)
    assertSame(entry.light, entry.dark)
    assertTrue(IconPackNotice.NATIVE_ANIMATION_PREVIEW_UNSUPPORTED in index.notices)
  }

  @Test fun absentNightResourcesReuseTheCompleteDaySnapshotRatherThanAnUnrelatedFlatFallback() {
    val entry = inspect(dynamic() + pair() +
      ("res/drawable-xxhdpi/com.example.png" to png)).entries.single()
    assertTrue((entry.light as IconPackArtwork.Archive).snapshot)
    assertSame(entry.light, entry.dark)
  }

  @Test fun dynamicPreviewComposesDeclaredQuietLayersInConfigOrder() {
    val artwork = inspect(dynamic()).entries.single().light as IconPackArtwork.Archive
    assertTrue(artwork.snapshot)
    assertEquals(listOf("0", "1").map { dynamic + "$it/quiet/quietImage.png" },
      artwork.layers.map { it.single().path })
  }

  @Test fun quietPreviewOrderUsesDeclaredDirectoriesRatherThanSortedFilenames() {
    val config = String(fixture("music-config.xml")).replace("value=\"0\"", "value=\"front\"")
      .replace("value=\"1\"", "value=\"back\"").toByteArray()
    val resources = dynamic().mapKeys { (path, _) ->
      path.replace("/0/", "/front/").replace("/1/", "/back/")
    } + (dynamic + "config.xml" to config)
    val artwork = inspect(resources).entries.single().light as IconPackArtwork.Archive
    assertEquals(listOf("front", "back").map { dynamic + "$it/quiet/quietImage.png" },
      artwork.layers.map { it.single().path })
    LocalNativeIconValidator.validate(archive(resources))
  }

  @Test fun missingQuietLayerDoesNotProducePartialPreview() {
    val index = inspect(dynamic().filterKeys { it != dynamic + "1/quiet/quietImage.png" })
    assertNull(index.entries.single().light)
    assertTrue(IconPackNotice.NATIVE_ANIMATION_PREVIEW_UNSUPPORTED in index.notices)
  }

  @Test fun dynamicNightmodeUsesItsOwnRootQuiet() {
    val night = dynamic().mapKeys { "nightmode/" + it.key } +
      ("nightmode/${dynamic}quiet/quietImage.png" to png)
    val entry = inspect(dynamic() + night).entries.single()
    val dark = entry.dark as IconPackArtwork.Archive
    assertTrue(dark.snapshot)
    assertEquals("nightmode/${dynamic}quiet/quietImage.png",
      dark.variants.single().path)
    LocalNativeIconValidator.validate(archive(dynamic() + night))
  }

  @Test fun admitsStaticAndRealMusicVisualManifestWithoutChangingPayload() {
    val resources = pair() + dynamic()
    val file = archive(resources)
    val bytes = file.readBytes()
    LocalNativeIconValidator.validate(file)
    assertArrayEquals(bytes, file.readBytes())
    resources.forEach { (path, expected) ->
      assertArrayEquals(expected, IconPackArchive.readEntry(file, path, 64 * 1024))
    }
  }

  @Test fun nestedMtzIconsPreserveLayersAndWholeModuleByteForByte() {
    val source = archive(pair() + dynamic())
    val mtz = archive(mapOf("icons" to source.readBytes(), "description.xml" to "<MIUI-Theme/>".toByteArray()))
    val nested = temporary.newFile()
    val index = IconPackArchive().inspect(mtz, nested)
    assertEquals(IconPackFormat.MIUI_MTZ, index.format)
    assertArrayEquals(source.readBytes(), nested.readBytes())
    assertEquals(IconArchivePart.ICONS, (index.entries.single().light as IconPackArtwork.Archive).part)
  }

  @Test fun missingLottieJsonStillHasAQuietSnapshotButCannotBeAdmitted() {
    val resources = dynamic() + (dynamic + "1/fancy/manifest.xml" to fixture("browser-lottie-manifest.xml"))
    assertTrue((inspect(resources).entries.single().light as IconPackArtwork.Archive).snapshot)
    assertThrows(Exception::class.java) { LocalNativeIconValidator.validate(archive(resources)) }
  }

  @Test fun rejectsMissingPairsUndeclaredFilesAndNonimagePayloads() {
    val bad = listOf(
      pair().filterKeys { it != static + "1.png" },
      dynamic() + (dynamic + "2/quiet/quietImage.png" to png),
      dynamic() + (dynamic + "1/fancy/animation.json" to "{}".toByteArray()),
      dynamic().filterKeys { it != dynamic + "0/quiet/quietImage.png" },
      dynamic() + (dynamic + "1/fancy/icon.png" to byteArrayOf(1, 2)),
      dynamic() + ("layer_animating_icons/com.other/quiet/quietImage.png" to png),
    )
    bad.forEach { resources ->
      assertThrows(Exception::class.java) { LocalNativeIconValidator.validate(archive(resources)) }
    }
  }

  @Test fun rejectsUnsafeMamlWithoutLooseningLegacyAdmission() {
    for (body in listOf(
      "<VariableBinders><BroadcastBinder action='android.intent.action.BATTERY_CHANGED'/></VariableBinders>",
      "<ExternalCommands><Trigger action='arbitrary'><FrameRateCommand rate='0'/></Trigger></ExternalCommands>",
      "<ExternalCommands><Trigger action='init'><IntentCommand action='anything'/></Trigger></ExternalCommands>",
      "<Image src='../outside.png'/>", "<Image src='file:/data/secret'/>",
      "<Image src='https://example.com/icon.png'/>", "<Image srcExp='@computed'/>",
      "<Var name='bad' expression='eval(#x)'/>", "<Include src='other.xml'/>",
      "<Image src='missing.png'/>", "<Image src='icon.png' onClick='evil'/>",
    )) {
      val xml = "<Icon width='384' height='384'><Image src='icon.png'/>$body</Icon>".toByteArray()
      assertThrows("Must reject $body", Exception::class.java) {
        LocalNativeIconValidator.validate(archive(dynamic() + (dynamic + "1/fancy/manifest.xml" to xml)))
      }
    }
  }

  @Test fun realConfigSchemaIsCaseSensitiveBoundedAndRejectsExternalDeclarations() {
    val limits = IconPackLimits()
    for (name in listOf("music-config.xml", "browser-config.xml")) {
      assertEquals(listOf("0", "1"), NativeLayerConfig.parse(fixture(name), limits).layers)
    }
    val calendar = NativeLayerConfig.parse(fixture("hyperos4-calendar-config.xml"), limits)
    assertEquals(listOf("0", "1", "2"), calendar.layers)
    assertEquals(setOf("1", "2"), calendar.fancyOnly)
    assertEquals(setOf("0"), calendar.quietOnly)
    assertEquals(setOf("1"),
      NativeLayerConfig.parse(fixture("hyperos4-clock-config.xml"), limits).fancyOnly)
    val original = String(fixture("music-config.xml"))
    for (xml in listOf(
      original.replace("Config", "LayerConfig"), original.replace("Variable", "variable"),
      original.replace("value=\"0\"", "value=\"../outside\""),
      original.replace("name=\"layer1\"", "name=\"layer0\""),
      original.replace("name=\"layer1\"", "name=\"layer8\""),
      original.replace("value=\"1\"", "value=\"0\""),
      original.replace("name=\"layer1\"", "name=\"layer2\""),
      original.replace("name=\"fancy_work\"", "name=\"external\""),
      original.replace("</Variables>",
        "<Variable name='layer_only_fancy0' value='true'/></Variables>"),
      original.replace("</Config>", "<Binder/></Config>"),
      "<!DOCTYPE Config [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>$original",
    )) assertThrows(Exception::class.java) { NativeLayerConfig.parse(xml.toByteArray(), limits) }
  }

  @Test fun suppliedHyperOs4FixtureRetainsSnapshotsAndPassesCompleteLocalAdmission() {
    val path = System.getenv("KEIHOME_TEST_PERFECT_HYPEROS4")
    org.junit.Assume.assumeTrue(path != null)
    val file = File(requireNotNull(path))
    val before = file.readBytes()
    val index = IconPackArchive().inspect(file, temporary.newFile())
    assertEquals(setOf("com.android.calendar", "com.android.deskclock",
      "com.android.settings", "com.miui.gallery"), index.entries.map { it.nativeName }.toSet())
    for (entry in index.entries) {
      assertSame(entry.light, entry.dark)
      val artwork = entry.light as IconPackArtwork.Archive
      if (entry.nativeName in setOf("com.android.calendar", "com.android.deskclock")) {
        assertTrue(artwork.snapshot)
        assertEquals("layer_animating_icons/${entry.nativeName}/quiet/quietImage.png",
          artwork.variants.single().path)
      } else assertEquals(2, artwork.layers.size)
    }
    val resources = ZipFile(file).use { zip ->
      zip.entries().asSequence().filterNot { it.isDirectory }.associate {
        it.name to zip.getInputStream(it).use { input -> input.readBytes() }
      }
    }
    val staticOnly = resources.filterKeys { !it.startsWith("layer_animating_icons/") }
    LocalNativeIconValidator.validate(archive(staticOnly))
    for (id in listOf("com.android.calendar", "com.android.deskclock")) {
      val subset = staticOnly + resources.filterKeys { it.startsWith("layer_animating_icons/$id/") }
      LocalNativeIconValidator.validate(archive(subset))
    }
    LocalNativeIconValidator.validate(file)
    assertArrayEquals(before, file.readBytes())
  }

  /** Optional real upstream sample, downloaded read-only outside the shared worktree. */
  @Test fun suppliedSmallUpstreamMusicSamplePassesLocalAdmission() {
    val path = System.getenv("KEIHOME_TEST_PERFECT_SAMPLE")
    org.junit.Assume.assumeTrue(path != null)
    val folder = File(requireNotNull(path))
    val entries = folder.walkTopDown().filter { it.isFile }.associate {
      dynamic + it.relativeTo(folder).invariantSeparatorsPath to it.readBytes()
    }
    val file = archive(entries)
    val before = file.readBytes()
    LocalNativeIconValidator.validate(file)
    assertTrue((inspect(entries).entries.single().light as IconPackArtwork.Archive).snapshot)
    assertArrayEquals(before, file.readBytes())
  }

  private fun fixture(name: String): ByteArray =
    requireNotNull(javaClass.getResourceAsStream("/perfect-icons/$name")).use { it.readBytes() }

  private fun pair(prefix: String = static) = mapOf(prefix + "0.png" to png, prefix + "1.png" to png)
  private fun dynamic() = linkedMapOf(
    dynamic + "config.xml" to fixture("music-config.xml"),
    dynamic + "0/quiet/quietImage.png" to png,
    dynamic + "1/quiet/quietImage.png" to png,
    dynamic + "1/fancy/manifest.xml" to fixture("music-manifest.xml"),
    dynamic + "1/fancy/icon.png" to png,
  )
  private fun inspect(resources: Map<String, ByteArray>) =
    IconPackArchive().inspect(archive(resources), temporary.newFile())
  private fun archive(resources: Map<String, ByteArray>): File = temporary.newFile().apply {
    writeBytes(ByteArrayOutputStream().also { bytes ->
      ZipOutputStream(bytes).use { zip ->
        resources.forEach { (path, value) ->
          zip.putNextEntry(ZipEntry(path)); zip.write(value); zip.closeEntry()
        }
      }
    }.toByteArray())
  }
}
