package os.kei.hyper.home.iconpack

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import os.kei.hyper.home.data.SystemIconFallback

class NativeAppearanceVariantsTest {
  @get:Rule val folder = TemporaryFolder()

  private val light = NativeIconPngFixtures.rgba()
  private val dark = NativeIconPngFixtures.rgba { _, _ -> 128 }
  private val mono = NativeIconPngFixtures.mask()
  private val shape = NativeIconPngFixtures.rgba { x, _ -> if (x == 0) 0 else 255 }
  private val plain = "org.example.Plain"
  private val palette = "org.example.Palette"
  private val system = "com.android.settings"
  private val clock = "com.android.deskclock"

  private fun archive(entries: Map<String, ByteArray>, name: String = "source.zip"): File = folder.newFile(name).also { file ->
    ZipOutputStream(file.outputStream()).use { output ->
      for ((name, bytes) in entries) {
        output.putNextEntry(ZipEntry(name))
        output.write(bytes)
        output.closeEntry()
      }
    }
  }

  private fun read(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
    zip.entries().asSequence().associate { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }
  }

  private fun generated(): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
    putAll(NativeDayNightIcon.entries(plain, light, dark))
    putAll(NativePaletteIcon.entries(palette, light, dark, mono, shape))
    putAll(NativeDayNightIcon.entries(system, light, dark))
    put(SystemIconFallbackManifest.PATH, SystemIconFallbackManifest(SystemIconFallback.DEFAULT, setOf(system)).encode())
    // An authored program with extra layers is not a generated selector.
    put("res/drawable-xxhdpi/$clock.png", light)
    put("nightmode/res/drawable-xxhdpi/$clock.png", dark)
    put("fancy_icons/$clock/manifest.xml", "<Icon/>".toByteArray())
    put("fancy_icons/$clock/hour.png", light)
    put("transform_config.xml", "<IconTransform/>".toByteArray())
  }

  @Test fun flattensOnlyReproducibleSelectorsAndPutsNightArtworkAtTheDarkDefaultPath() {
    val source = archive(generated())
    val lightOut = File(folder.root, "light.icons")
    val darkOut = File(folder.root, "dark.icons")
    val result = NativeAppearanceVariants.write(source, lightOut, darkOut)
    assertEquals(NativeAppearanceVariants.Result(flattened = 2, paletteFlattened = 1, retained = 2), result)

    val lightEntries = read(lightOut)
    val darkEntries = read(darkOut)
    for (entries in listOf(lightEntries, darkEntries)) {
      assertFalse(entries.keys.any { it.startsWith("fancy_icons/$plain/") })
      assertFalse(entries.keys.any { it.startsWith("fancy_icons/$palette/") })
      // System-frontend descriptors and authored programs remain intact.
      assertTrue("fancy_icons/$system/manifest.xml" in entries)
      assertTrue("fancy_icons/$clock/hour.png" in entries)
      assertArrayEquals(dark, entries.getValue("nightmode/res/drawable-xxhdpi/$plain.png"))
      assertTrue("transform_config.xml" in entries)
    }
    assertArrayEquals(light, lightEntries.getValue("res/drawable-xxhdpi/$plain.png"))
    assertArrayEquals(light, lightEntries.getValue("res/drawable-xxhdpi/$palette.png"))
    assertArrayEquals(dark, darkEntries.getValue("res/drawable-xxhdpi/$plain.png"))
    assertArrayEquals(dark, darkEntries.getValue("res/drawable-xxhdpi/$palette.png"))
    // Retained selectors keep their own day fallback in both copies.
    assertArrayEquals(light, darkEntries.getValue("res/drawable-xxhdpi/$system.png"))
    assertArrayEquals(light, darkEntries.getValue("res/drawable-xxhdpi/$clock.png"))
  }

  @Test fun keepsSelectorsWhoseImagesNoLongerMatchTheFlatArtwork() {
    val entries = generated().toMutableMap()
    // A later stage rewrote the flat PNG only, so the selector shows different pixels.
    entries["res/drawable-xxhdpi/$plain.png"] = NativeIconPngFixtures.rgba { _, _ -> 64 }
    val result = NativeAppearanceVariants.write(archive(entries),
      File(folder.root, "light.icons"), File(folder.root, "dark.icons"))
    assertEquals(1, result.flattened)
    assertTrue("fancy_icons/$plain/manifest.xml" in read(File(folder.root, "dark.icons")))
  }

  @Test fun acceptsLegacyExpandedSelectorsForIdenticalArtwork() {
    val entries = NativeDayNightIcon.entries(plain, light, light, legacyExpanded = true)
    val result = NativeAppearanceVariants.write(archive(entries),
      File(folder.root, "light.icons"), File(folder.root, "dark.icons"))
    assertEquals(1, result.flattened)
  }

  @Test fun refusesArchivesWithoutGeneratedSelectors() {
    val entries = mapOf("res/drawable-xxhdpi/$plain.png" to light, "nightmode/res/drawable-xxhdpi/$plain.png" to dark)
    assertThrows(NativeAppearanceVariants.NothingToFlatten::class.java) {
      NativeAppearanceVariants.write(archive(entries), File(folder.root, "light.icons"), File(folder.root, "dark.icons"))
    }
  }

  @Test fun keepsDescriptorsForPhoneCompanions() {
    val entries = generated().toMutableMap()
    // Recommended phone icons carry a descriptor even when both appearances are equal.
    entries.putAll(NativeDayNightIcon.entries("com.android.phone", light, light))
    entries.putAll(NativeDayNightIcon.entries("com.android.server.telecom.InCall", light, dark))
    val darkOut = File(folder.root, "dark.icons")
    val result = NativeAppearanceVariants.write(archive(entries), File(folder.root, "light.icons"), darkOut)
    assertEquals(2, result.flattened)
    val darkEntries = read(darkOut)
    assertTrue("fancy_icons/com.android.phone/manifest.xml" in darkEntries)
    assertTrue("fancy_icons/com.android.server.telecom.InCall/manifest.xml" in darkEntries)
  }

  @Test fun keepsSelectorsWhoseIconAnotherResourceCanServe() {
    val competing = listOf(
      "res/drawable-xxhdpi/$plain/0.png",            // Static layer pair.
      "nightmode/res/drawable-xxhdpi/$plain/1.png",
      "res/drawable-xxxhdpi/$plain.png",             // Another density.
      "$plain.png",                                  // Legacy root icon.
      "animating_icons/$plain/fancy/manifest.xml",
      "layer_animating_icons/$plain/manifest.xml",
      "nightmode/fancy_icons/$plain/manifest.xml",
    )
    competing.forEachIndexed { index, extra ->
      val entries = generated().toMutableMap().apply { put(extra, light) }
      val darkOut = File(folder.root, "dark-$index.icons")
      val result = NativeAppearanceVariants.write(archive(entries, "source-$index.zip"),
        File(folder.root, "light-$index.icons"), darkOut)
      assertEquals(extra, 1, result.flattened)
      assertTrue(extra, "fancy_icons/$plain/manifest.xml" in read(darkOut))
      assertArrayEquals(extra, light, read(darkOut).getValue("res/drawable-xxhdpi/$plain.png"))
    }
  }

  @Test fun selectsTheOriginalOnlyWhileAFlattenedPaletteIsLive() {
    assertEquals(NativeAppearanceVariants.Role.DARK, NativeAppearanceVariants.target(true, false, true))
    assertEquals(NativeAppearanceVariants.Role.LIGHT, NativeAppearanceVariants.target(false, false, true))
    assertEquals(NativeAppearanceVariants.Role.PRIMARY, NativeAppearanceVariants.target(true, true, true))
    assertEquals(NativeAppearanceVariants.Role.LIGHT, NativeAppearanceVariants.target(false, true, false))
  }
}
