package os.kei.hyper.home.iconpack

import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.w3c.dom.Element

class NativeDayNightIconTest {
  private val name = "org.example.Main"
  private val root = "fancy_icons/$name"
  private val day = png("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEElEQVR4nGNgYGD4D8UQBgAd9AP9yOH2qAAAAABJRU5ErkJggg==")
  private val night = png("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAADklEQVR4nGP4DwUMMAYAj4IP8TylVlEAAAAASUVORK5CYII=")

  @Test fun preservesBothPngsAndNormalFallbackAtExactNativePaths() {
    val entries = NativeDayNightIcon.entries(name, day, night)
    assertEquals(setOf("res/drawable-xxhdpi/$name.png", "$root/manifest.xml",
      "$root/day.png", "$root/night.png"), entries.keys)
    assertArrayEquals(day, entries.getValue("res/drawable-xxhdpi/$name.png"))
    assertArrayEquals(day, entries.getValue("$root/day.png"))
    assertArrayEquals(night, entries.getValue("$root/night.png"))
  }

  @Test fun manifestContainsOnlyLocalImagesSelectedByNativeDarkModeWithoutPolling() {
    val xml = NativeDayNightIcon.entries(name, day, night).getValue("$root/manifest.xml")
    val parser = DocumentBuilderFactory.newInstance().apply {
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder()
    val icon = xml.inputStream().use { parser.parse(it).documentElement }
    assertEquals("Icon", icon.tagName)
    assertEquals(mapOf("version" to "1", "width" to "2", "height" to "2",
      "screenWidth" to "1080", "resDensity" to "480", "frameRate" to "0",
      "useVariableUpdater" to "none"), attributes(icon))
    val children = icon.getElementsByTagName("*")
    assertEquals(2, children.length)
    val geometry = mapOf("x" to "0", "y" to "0", "w" to "2", "h" to "2")
    for ((index, selection) in listOf(
      mapOf("src" to "day.png", "visibility" to "1-#__darkmode"),
      mapOf("src" to "night.png", "visibility" to "#__darkmode"),
    ).withIndex()) {
      val image = children.item(index) as Element
      assertEquals("Image", image.tagName)
      assertEquals(geometry + selection, attributes(image))
    }
  }

  @Test fun identicalBytesKeepFallbackAndDescriptorWithOneAlwaysVisibleImage() {
    val entries = NativeDayNightIcon.entries(name, day, day.copyOf())
    assertEquals(setOf("res/drawable-xxhdpi/$name.png", "$root/manifest.xml", "$root/day.png"), entries.keys)
    assertArrayEquals(day, entries.getValue("res/drawable-xxhdpi/$name.png"))
    assertArrayEquals(day, entries.getValue("$root/day.png"))
    val icon = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(entries.getValue("$root/manifest.xml").inputStream()).documentElement
    assertEquals("0", icon.getAttribute("frameRate"))
    assertEquals("none", icon.getAttribute("useVariableUpdater"))
    val children = icon.getElementsByTagName("*")
    assertEquals(1, children.length)
    val image = children.item(0) as Element
    assertEquals("Image", image.tagName)
    assertEquals(mapOf("x" to "0", "y" to "0", "w" to "2", "h" to "2",
      "src" to "day.png", "visibility" to "1"), attributes(image))
  }

  @Test fun rejectsUnsafeNamesAndInvalidOrIncompatiblePngHeaders() {
    assertThrows(IllegalArgumentException::class.java) { NativeDayNightIcon.entries("../escape", day, night) }
    assertThrows(IllegalArgumentException::class.java) { NativeDayNightIcon.entries(name, byteArrayOf(1), night) }
    val nonSquare = day.copyOf().apply { this[23] = 1 }
    assertThrows(IllegalArgumentException::class.java) { NativeDayNightIcon.entries(name, nonSquare, night) }
    val smaller = png("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAX+XDSwAAAABJRU5ErkJggg==")
    assertThrows(IllegalArgumentException::class.java) { NativeDayNightIcon.entries(name, day, smaller) }
  }

  private fun png(encoded: String): ByteArray = Base64.getDecoder().decode(encoded)

  private fun attributes(element: Element): Map<String, String> =
    (0 until element.attributes.length).associate {
      element.attributes.item(it).let { attribute -> attribute.nodeName to attribute.nodeValue }
    }
}
