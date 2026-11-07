package os.kei.hyper.home.iconpack

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

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

  @Test fun usesOneAtomicDoubleSettingsObserverBeforeFourLocalImages() {
    val manifest = NativePaletteIcon.entries(name, light, dark, mono, shape).getValue("$root/manifest.xml")
    val icon = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(manifest.inputStream()).documentElement
    assertEquals("0", icon.getAttribute("frameRate"))
    assertEquals("none", icon.getAttribute("useVariableUpdater"))
    val children = elements(icon)
    assertEquals(listOf("VariableBinders", "Image", "Image", "Image", "Image"), children.map { it.tagName })
    val binders = elements(children.first())
    assertEquals(1, binders.size)
    for (binder in binders) {
      assertEquals("ContentProviderBinder", binder.tagName)
      assertEquals("content://settings/system/keihome_maml_palette_state", binder.getAttribute("uri"))
      assertEquals("value", binder.getAttribute("columns"))
      assertEquals("-1", binder.getAttribute("updateInterval"))
      assertEquals("true", binder.getAttribute("queryAtStart"))
      val variable = elements(binder).single()
      assertEquals("Variable", variable.tagName)
      assertEquals("palette_state", variable.getAttribute("name"))
      assertEquals("double", variable.getAttribute("type"))
      assertEquals("0", variable.getAttribute("row"))
      assertEquals("value", variable.getAttribute("column"))
    }
    val images = children.drop(1)
    assertEquals(listOf("day.png", "night.png", "bg.png", "mask.png"), images.map { it.getAttribute("src") })
    val enabled = "int(#palette_state/281474976710656)"
    assertEquals(listOf("(1-$enabled)*(1-#__darkmode)", "(1-$enabled)*#__darkmode",
      enabled, enabled), images.map { it.getAttribute("visibility") })
    assertTrue(images.drop(2).all { it.getAttribute("tint").startsWith("argb(255,") })
    assertTrue(images.all { !it.hasAttribute("tintExp") })
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
    val enabled = "int(#palette_state/281474976710656)"
    assertEquals(listOf("(1-$enabled)", enabled, enabled),
      images.map { it.getAttribute("visibility") })
    assertEquals("", images.first().getAttribute("tint"))
    assertTrue(images.drop(1).all { it.getAttribute("tint").startsWith("argb(255,") })
    assertEquals(listOf("5", "5"), images.drop(1).map { it.getAttribute("tintmode") })
  }

  @Test fun packedStateAndActualTintExpressionsRoundTripAllRgbBitsAsDoubles() {
    val icon = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(NativePaletteIcon.entries(name, light, dark, mono, shape).getValue("$root/manifest.xml").inputStream())
      .documentElement
    val tints = elements(icon).filter { it.hasAttribute("tint") }.associate {
      it.getAttribute("src") to it.getAttribute("tint")
    }
    val random = Random(831)
    assertEquals(1L shl 48, NativePaletteIcon.packedState(0, 0))
    assertEquals((1L shl 49) - 1, NativePaletteIcon.packedState(-1, -1))
    val colors = listOf(0, -1, 0xff000000.toInt(), 0x00112233, 0xff804020.toInt(),
      0x00ff00ff, 0x01010101, 0x7fffffff) + List(1024) { random.nextInt() }
    for ((index, fg) in colors.withIndex()) {
      val bg = colors[colors.lastIndex - index]
      val packed = NativePaletteIcon.packedState(fg, bg)
      assertTrue(packed in (1L shl 48) until (1L shl 49))
      assertEquals(packed, packed.toDouble().toLong())
      assertEquals(1, (packed.toDouble() / 281474976710656.0).toInt())
      assertEquals(fg or 0xff000000.toInt(), evaluateTint(tints.getValue("mask.png"), packed.toDouble()))
      assertEquals(bg or 0xff000000.toInt(), evaluateTint(tints.getValue("bg.png"), packed.toDouble()))
    }
    assertEquals(0, (0.0 / 281474976710656.0).toInt())
    for (color in listOf(0, -1)) {
      val state = NativePaletteIcon.packedState(color, color).toDouble()
      assertEquals(color or 0xff000000.toInt(), evaluateTint(tints.getValue("mask.png"), state))
      assertEquals(color or 0xff000000.toInt(), evaluateTint(tints.getValue("bg.png"), state))
    }
  }

  // Evaluate the expressions extracted from the emitted XML using double arithmetic.
  // Native syntax acceptance is established separately from the retained SDK implementation.
  private fun evaluateTint(tint: String, state: Double): Int {
    val components = tint.removePrefix("argb(").removeSuffix(")").split(',')
    assertEquals(4, components.size)
    return components.fold(0) { color, expression ->
      val component = when {
        expression == "255" -> 255
        expression == "#palette_state%256" -> (state % 256.0).toInt()
        else -> {
          val match = requireNotNull(Regex("""int\(\(#palette_state%(\d+)\)/(\d+)\)""").matchEntire(expression))
          ((state % match.groupValues[1].toDouble()) / match.groupValues[2].toDouble()).toInt()
        }
      }
      assertTrue(component in 0..255)
      (color shl 8) or component
    }
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
