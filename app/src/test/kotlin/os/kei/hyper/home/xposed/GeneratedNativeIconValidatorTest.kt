package os.kei.hyper.home.xposed

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.NativeDayNightIcon
import os.kei.hyper.home.iconpack.NativeIconPngFixtures
import os.kei.hyper.home.iconpack.NativePaletteIcon

class GeneratedNativeIconValidatorTest {
  private val name = "org.example.Main"
  private val root = "fancy_icons/$name"
  private val manifestPath = "$root/manifest.xml"
  private val nightFallback = "nightmode/res/drawable-xxhdpi/$name.png"
  private val light = NativeIconPngFixtures.rgba()
  private val dark = NativeIconPngFixtures.rgba { _, _ -> 128 }
  private val mask = NativeIconPngFixtures.mask()
  private val background = NativeIconPngFixtures.indexedMask()

  @Test fun acceptsCompactAndPreviouslyInstalledExpandedIdenticalArtwork() {
    for (palette in listOf(false, true)) {
      validate(entries(palette, light.copyOf()))
      // This manifest is fixed independently of the generator's compatibility flag.
      validate(legacyEntries(palette))
    }
  }

  @Test fun differentArtworkRetainsThePreviousExactManifestAndBothAssets() {
    for (palette in listOf(false, true)) {
      val entries = entries(palette, dark)
      assertArrayEquals(legacyManifest(palette).toByteArray(), entries.getValue(manifestPath))
      assertArrayEquals(light, entries.getValue("$root/day.png"))
      assertArrayEquals(dark, entries.getValue("$root/night.png"))
      validate(entries)
    }
  }

  @Test fun rejectsNoncanonicalXmlInBothCompactAndLegacyTemplates() {
    for (palette in listOf(false, true)) {
      for (entries in listOf(entries(palette, light), legacyEntries(palette))) {
        val canonical = entries.getValue(manifestPath).toString(Charsets.UTF_8)
        val alternatives = listOf(
          "<Icon><Image src=\"day.png\" /></Icon>",
          canonical.replace("day.png", "https://example.invalid/icon.png"),
          canonical.replace("day.png", "../day.png"),
          canonical.replace("frameRate=\"0\"", "frameRate=\"1\""),
          canonical.replace("</Icon>", "<Script>anything</Script></Icon>"),
          canonical.replace("</Icon>", "<!-- extra XML --></Icon>"),
          canonical + "\n",
        ) + if (palette) listOf(
          canonical.replace("settings/system/", "settings/secure/"),
          canonical.replace("updateInterval=\"-1\"", "updateInterval=\"0\""),
          canonical.replace("queryAtStart=\"true\"", "queryAtStart=\"false\""),
          canonical.replace("type=\"int\"", "type=\"string\""),
          canonical.replace("tintmode=\"5\"", "tintmode=\"3\""),
          canonical.replace("#palette_enabled", "0"),
        ) else listOf(canonical.replace("visibility=", "alpha="))
        for (manifest in alternatives) {
          assertNotEquals(canonical, manifest)
          rejects(entries + (manifestPath to manifest.toByteArray()))
        }
      }
    }
  }

  @Test fun rejectsMixedLayoutsExtraAssetsAndArtworkThatDoesNotMatchFallbacks() {
    for (palette in listOf(false, true)) {
      val compact = entries(palette, light)
      val expanded = legacyEntries(palette)
      rejects(compact + ("$root/night.png" to light))
      rejects(expanded - "$root/night.png")
      rejects(compact + (manifestPath to expanded.getValue(manifestPath)))
      rejects(expanded + (manifestPath to compact.getValue(manifestPath)))
      for (entries in listOf(compact, expanded)) {
        rejects(entries + ("$root/extra.png" to light))
        rejects(entries + ("$root/day.png" to dark))
        rejects(entries + (nightFallback to dark))
        rejects(entries - "$root/day.png")
      }
      rejects(expanded + ("$root/night.png" to dark))
    }
  }

  private fun entries(palette: Boolean, night: ByteArray): Map<String, ByteArray> =
    if (palette) NativePaletteIcon.entries(name, light, night, mask, background)
    else NativeDayNightIcon.entries(name, light, night) + (nightFallback to night)

  private fun legacyEntries(palette: Boolean): Map<String, ByteArray> =
    entries(palette, light) + mapOf(
      manifestPath to legacyManifest(palette).toByteArray(),
      "$root/night.png" to light.copyOf(),
    )

  private fun validate(entries: Map<String, ByteArray>) {
    GeneratedNativeIconValidator.validate(name, entries.keys) { path, limit ->
      entries.getValue(path).also { assertTrue(it.size.toLong() <= limit) }
    }
  }

  private fun rejects(entries: Map<String, ByteArray>) {
    assertThrows(RuntimeException::class.java) { validate(entries) }
  }

  private fun legacyManifest(palette: Boolean): String = if (palette) """
    <?xml version="1.0" encoding="utf-8"?>
    <Icon version="1" width="2" height="2" screenWidth="1080" resDensity="480" frameRate="0" useVariableUpdater="none">
      <VariableBinders>
        <ContentProviderBinder name="palette_fg" uri="content://settings/system/keihome_maml_fg_hex" columns="value" updateInterval="-1" queryAtStart="true">
          <Variable name="palette_fg" type="string" column="value" row="0" />
        </ContentProviderBinder>
        <ContentProviderBinder name="palette_bg" uri="content://settings/system/keihome_maml_bg_hex" columns="value" updateInterval="-1" queryAtStart="true">
          <Variable name="palette_bg" type="string" column="value" row="0" />
        </ContentProviderBinder>
        <ContentProviderBinder name="palette_enabled" uri="content://settings/system/keihome_maml_enabled" columns="value" updateInterval="-1" queryAtStart="true">
          <Variable name="palette_enabled" type="int" column="value" row="0" />
        </ContentProviderBinder>
      </VariableBinders>
      <Image x="0" y="0" w="2" h="2" src="day.png" visibility="(1-#palette_enabled)*(1-#__darkmode)" />
      <Image x="0" y="0" w="2" h="2" src="night.png" visibility="(1-#palette_enabled)*#__darkmode" />
      <Image x="0" y="0" w="2" h="2" src="bg.png" tint="@palette_bg" tintmode="5" visibility="#palette_enabled" />
      <Image x="0" y="0" w="2" h="2" src="mask.png" tint="@palette_fg" tintmode="5" visibility="#palette_enabled" />
    </Icon>
  """.trimIndent() + "\n" else """
    <?xml version="1.0" encoding="utf-8"?>
    <Icon version="1" width="2" height="2" screenWidth="1080" resDensity="480" frameRate="0" useVariableUpdater="none">
      <Image x="0" y="0" w="2" h="2" src="day.png" visibility="1-#__darkmode" />
      <Image x="0" y="0" w="2" h="2" src="night.png" visibility="#__darkmode" />
    </Icon>
  """.trimIndent() + "\n"
}
