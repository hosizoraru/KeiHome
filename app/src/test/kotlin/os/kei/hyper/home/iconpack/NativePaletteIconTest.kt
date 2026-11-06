package os.kei.hyper.home.iconpack

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class NativePaletteIconTest {
  private val name = "org.example.Main"
  private val root = "fancy_icons/$name"
  private val light = NativeIconPngFixtures.rgba()
  private val dark = NativeIconPngFixtures.rgba { _, _ -> 128 }
  private val mono = NativeIconPngFixtures.mask()
  private val shape = NativeIconPngFixtures.rgba { x, _ -> if (x == 0) 0 else 255 }

  @Test fun preservesFallbackArtworkAndBothSourceMasksAtExactNativePaths() {
    val entries = NativePaletteIcon.entries(name, light, dark, mono, shape)
    assertEquals(setOf("res/drawable-xxhdpi/$name.png", "nightmode/res/drawable-xxhdpi/$name.png",
      "$root/manifest.xml", "$root/day.png", "$root/night.png", "$root/mask.png", "$root/bg.png"), entries.keys)
    assertArrayEquals(light, entries.getValue("res/drawable-xxhdpi/$name.png"))
    assertArrayEquals(dark, entries.getValue("nightmode/res/drawable-xxhdpi/$name.png"))
    assertArrayEquals(light, entries.getValue("$root/day.png"))
    assertArrayEquals(dark, entries.getValue("$root/night.png"))
    assertArrayEquals(mono, entries.getValue("$root/mask.png"))
    assertArrayEquals(shape, entries.getValue("$root/bg.png"))
    val before = entries.getValue("$root/bg.png").copyOf()
    shape.fill(0)
    assertArrayEquals(before, entries.getValue("$root/bg.png"))
  }

  @Test fun usesOnlyTheThreeFixedSettingsObserversBeforeFourLocalImages() {
    val manifest = NativePaletteIcon.entries(name, light, dark, mono, shape).getValue("$root/manifest.xml")
    val icon = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(manifest.inputStream()).documentElement
    assertEquals("0", icon.getAttribute("frameRate"))
    assertEquals("none", icon.getAttribute("useVariableUpdater"))
    val children = elements(icon)
    assertEquals(listOf("VariableBinders", "Image", "Image", "Image", "Image"), children.map { it.tagName })
    val binders = elements(children.first())
    assertEquals(3, binders.size)
    for ((index, key) in listOf("keihome_maml_fg_hex", "keihome_maml_bg_hex", "keihome_maml_enabled").withIndex()) {
      val binder = binders[index]
      assertEquals("ContentProviderBinder", binder.tagName)
      assertEquals("content://settings/system/$key", binder.getAttribute("uri"))
      assertEquals("value", binder.getAttribute("columns"))
      assertEquals("-1", binder.getAttribute("updateInterval"))
      assertEquals("true", binder.getAttribute("queryAtStart"))
      val variable = elements(binder).single()
      assertEquals("Variable", variable.tagName)
      assertEquals(if (index == 2) "int" else "string", variable.getAttribute("type"))
      assertEquals("0", variable.getAttribute("row"))
      assertEquals("value", variable.getAttribute("column"))
    }
    val images = children.drop(1)
    assertEquals(listOf("day.png", "night.png", "bg.png", "mask.png"), images.map { it.getAttribute("src") })
    assertEquals(listOf("(1-#palette_enabled)*(1-#__darkmode)", "(1-#palette_enabled)*#__darkmode",
      "#palette_enabled", "#palette_enabled"), images.map { it.getAttribute("visibility") })
    assertEquals(listOf("@palette_bg", "@palette_fg"), images.drop(2).map { it.getAttribute("tint") })
    assertEquals(listOf("5", "5"), images.drop(2).map { it.getAttribute("tintmode") })
  }

  @Test fun identicalBytesKeepFallbacksMasksAndObserversWithOneUntintedImage() {
    val indexed = NativeIconPngFixtures.indexedMask()
    val entries = NativePaletteIcon.entries(name, light, light.copyOf(), indexed, shape)
    assertEquals(setOf("res/drawable-xxhdpi/$name.png", "nightmode/res/drawable-xxhdpi/$name.png",
      "$root/manifest.xml", "$root/day.png", "$root/mask.png", "$root/bg.png"), entries.keys)
    assertArrayEquals(light, entries.getValue("res/drawable-xxhdpi/$name.png"))
    assertArrayEquals(light, entries.getValue("nightmode/res/drawable-xxhdpi/$name.png"))
    assertArrayEquals(light, entries.getValue("$root/day.png"))
    assertArrayEquals(indexed, entries.getValue("$root/mask.png"))
    assertArrayEquals(shape, entries.getValue("$root/bg.png"))
    val icon = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(entries.getValue("$root/manifest.xml").inputStream()).documentElement
    assertEquals("0", icon.getAttribute("frameRate"))
    assertEquals("none", icon.getAttribute("useVariableUpdater"))
    val children = elements(icon)
    assertEquals(listOf("VariableBinders", "Image", "Image", "Image"), children.map { it.tagName })
    val expanded = NativePaletteIcon.entries(name, light, dark, indexed, shape)
    val expandedIcon = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(expanded.getValue("$root/manifest.xml").inputStream()).documentElement
    assertTrue(elements(expandedIcon).first().isEqualNode(children.first()))
    val images = children.drop(1)
    assertEquals(listOf("day.png", "bg.png", "mask.png"), images.map { it.getAttribute("src") })
    assertEquals(listOf("(1-#palette_enabled)", "#palette_enabled", "#palette_enabled"),
      images.map { it.getAttribute("visibility") })
    assertEquals("", images.first().getAttribute("tint"))
    assertEquals(listOf("@palette_bg", "@palette_fg"), images.drop(1).map { it.getAttribute("tint") })
    assertEquals(listOf("5", "5"), images.drop(1).map { it.getAttribute("tintmode") })
  }

  @Test fun rejectsInvalidNamesMismatchedDimensionsAndMissingOrOpaqueSilhouettes() {
    assertThrows(Exception::class.java) { NativePaletteIcon.entries("../escape", light, dark, mono, shape) }
    val invalid = listOf(byteArrayOf(), NativeIconPngFixtures.mask(3), NativeIconPngFixtures.rgba(),
      NativeIconPngFixtures.rgba { _, _ -> 0 }, mono.copyOf().apply { this[29] = 0 })
    for (mask in invalid) {
      assertThrows(Exception::class.java) { NativePaletteIcon.entries(name, light, dark, mask, shape) }
      assertThrows(Exception::class.java) { NativePaletteIcon.entries(name, light, dark, mono, mask) }
    }
    assertThrows(Exception::class.java) { NativePaletteIcon.entries(name, light, NativeIconPngFixtures.rgba(3), mono, shape) }
  }

  @Test fun rejectsTruncatedExcessOrInvalidExpandedPixelRowsEvenWithValidChunkCrcs() {
    for (raw in listOf(ByteArray(1), ByteArray(19), ByteArray(18).apply { this[0] = 5 })) {
      val mask = NativeIconPngFixtures.png(2, 8, 6, raw)
      assertThrows(Exception::class.java) { NativePaletteIcon.entries(name, light, dark, mask, shape) }
    }
  }

  private fun elements(parent: Element): List<Element> = (0 until parent.childNodes.length)
    .mapNotNull { parent.childNodes.item(it) as? Element }
}
