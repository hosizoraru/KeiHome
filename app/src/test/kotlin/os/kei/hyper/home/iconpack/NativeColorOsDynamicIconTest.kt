package os.kei.hyper.home.iconpack

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class NativeColorOsDynamicIconTest {
  private val name = "org.example.Clock"
  private val root = "fancy_icons/$name"
  private val day = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEElEQVR4nGNgYGD4D8UQBgAd9AP9yOH2qAAAAABJRU5ErkJggg==")
  private val night = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAADklEQVR4nGP4DwUMMAYAj4IP8TylVlEAAAAASUVORK5CYII=")
  private val backgrounds get() = linkedMapOf("day.png" to day.copyOf(), "night.png" to night.copyOf())
  private val clock get() = backgrounds + linkedMapOf(
    "clock_day_bg.png" to day.copyOf(),
    "clock_day_hour_rectangle.png" to night.copyOf(),
    "clock_day_minute_rectangle.png" to day.copyOf(),
    "night_clock_day_bg.png" to night.copyOf(),
    "night_clock_day_hour_rectangle.png" to day.copyOf(),
    "night_clock_day_minute_rectangle.png" to night.copyOf(),
  )

  @Test fun identifiesAllLocalTimeTemplatesWithoutAcceptingForeignExpressions() {
    for (kind in NativeColorOsDynamicIcon.Kind.entries)
      for (fixed in listOf<Boolean?>(null, false, true))
        for (dim in listOf(false, true)) {
          val manifest = NativeColorOsDynamicIcon.entries(name, kind,
            if (kind == NativeColorOsDynamicIcon.Kind.CLOCK) clock else backgrounds,
            fixed, dim).getValue("$root/manifest.xml")
          assertEquals(kind, NativeColorOsDynamicIcon.kindOfManifest(manifest))
          assertEquals(null, NativeColorOsDynamicIcon.kindOfManifest(
            manifest.toString(Charsets.UTF_8).replace("frameRate=\"0\"", "frameRate=\"1\"").toByteArray()))
        }
  }

  @Test fun calendarUsesOnlyLiveWeekdayAndDateFieldsOverUnchangedBackgrounds() {
    val entries = NativeColorOsDynamicIcon.entries(name, NativeColorOsDynamicIcon.Kind.CALENDAR, backgrounds)
    assertEquals(setOf("res/drawable-xxhdpi/$name.png", "nightmode/res/drawable-xxhdpi/$name.png",
      "$root/manifest.xml", "$root/day.png", "$root/night.png"), entries.keys)
    assertArrayEquals(day, entries.getValue("$root/day.png"))
    assertArrayEquals(night, entries.getValue("$root/night.png"))
    assertArrayEquals(day, entries.getValue("res/drawable-xxhdpi/$name.png"))
    assertArrayEquals(night, entries.getValue("nightmode/res/drawable-xxhdpi/$name.png"))
    val icon = icon(entries)
    assertRoot(icon)
    val children = children(icon)
    assertEquals(listOf("Image", "DateTime", "Text", "Image", "DateTime", "Text"), children.map { it.tagName })
    val geometry = mapOf("x" to "75", "align" to "center", "bold" to "true")
    for ((offset, visibility, color) in listOf(
      Triple(0, "1-#__darkmode", "#FF000000"), Triple(3, "#__darkmode", "#FFFFFFFF"),
    )) {
      assertEquals(mapOf("x" to "0", "y" to "0", "w" to "150", "h" to "150",
        "src" to if (offset == 0) "day.png" else "night.png", "visibility" to visibility),
        attributes(children[offset]))
      val mode = mapOf("visibility" to visibility)
      assertEquals(geometry + mode + mapOf("y" to "20", "size" to "26", "format" to "EEEE", "color" to "#FF0A7FFB"),
        attributes(children[offset + 1]))
      assertEquals(geometry + mode + mapOf("y" to "43", "size" to "76", "textExp" to "#date", "color" to color),
        attributes(children[offset + 2]))
    }
  }

  @Test fun clockRetainsSourceFaceAndFullCanvasHandsWithLiveRotationAtBothAppearances() {
    val entries = NativeColorOsDynamicIcon.entries(name, NativeColorOsDynamicIcon.Kind.CLOCK, clock)
    val children = children(icon(entries).also(::assertRoot))
    assertEquals(11, entries.size)
    assertEquals(List(8) { "Image" }, children.map { it.tagName })
    for ((offset, visibility) in listOf(0 to "1-#__darkmode", 4 to "#__darkmode")) {
      val geometry = mapOf("x" to "0", "y" to "0", "w" to "150", "h" to "150", "visibility" to visibility)
      val prefix = if (offset == 0) "" else "night_"
      assertEquals(geometry + ("src" to "${prefix}clock_day_bg.png"), attributes(children[offset + 1]))
      for ((index, source, angle) in listOf(
        Triple(2, "clock_day_hour_rectangle.png", "#hour12*30+#minute/2"),
        Triple(3, "clock_day_minute_rectangle.png", "#minute*6"),
      )) {
        assertEquals(geometry + mapOf("src" to "$prefix$source", "angle" to angle, "centerX" to "75", "centerY" to "75"),
          attributes(children[offset + index]))
      }
    }
    for (file in clock.keys) assertArrayEquals(clock.getValue(file), entries.getValue("$root/$file"))
  }

  @Test fun allAppearanceVariantsRetainLiveFieldsAndMinuteUpdaterWithoutAdditionalCapabilities() {
    for (kind in NativeColorOsDynamicIcon.Kind.entries) {
      for (fixedDark in listOf(null, false, true)) {
        for (dim in listOf(false, true)) {
          val assets = if (kind == NativeColorOsDynamicIcon.Kind.CLOCK) clock else backgrounds
          val entries = NativeColorOsDynamicIcon.entries(name, kind, assets, fixedDark, dim)
          val children = children(icon(entries).also(::assertRoot))
          val half = children.size / 2
          for (systemDark in listOf(false, true)) {
            val offset = if (systemDark) half else 0
            val artDark = fixedDark ?: systemDark
            val visibility = if (fixedDark == null || dim) {
              if (systemDark) "#__darkmode" else "1-#__darkmode"
            } else if (systemDark == fixedDark) "1" else "0"
            assertEquals(if (artDark) "night.png" else "day.png", children[offset].getAttribute("src"))
            for (element in children.subList(offset, offset + half)) {
              assertEquals(visibility, element.getAttribute("visibility"))
              if (element.tagName == "Image") {
                assertEquals(if (dim && systemDark) "#FFD6D6D6" else "", element.getAttribute("tint"))
                assertEquals(if (dim && systemDark) "13" else "", element.getAttribute("tintmode"))
                if (kind == NativeColorOsDynamicIcon.Kind.CLOCK && element.getAttribute("src").contains("clock_")) {
                  assertEquals(artDark, element.getAttribute("src").startsWith("night_"))
                }
              }
            }
          }
          if (kind == NativeColorOsDynamicIcon.Kind.CALENDAR) {
            assertEquals(listOf("#date", "#date"), children.filter { it.tagName == "Text" }.map { it.getAttribute("textExp") })
            assertEquals(listOf("EEEE", "EEEE"), children.filter { it.tagName == "DateTime" }.map { it.getAttribute("format") })
            assertEquals(setOf("Image", "Text", "DateTime"), children.map { it.tagName }.toSet())
          } else {
            assertEquals(listOf("#hour12*30+#minute/2", "#minute*6", "#hour12*30+#minute/2", "#minute*6"),
              children.mapNotNull { it.getAttribute("angle").ifEmpty { null } })
          }
          assertTrue(entries.getValue("$root/manifest.xml").size < 4096)
          assertFalse(children.any { it.hasChildNodes() })
        }
      }
    }
  }

  @Test fun nightDimmingUsesDayArtworkAndMultipliesAllLiveCalendarAndClockLayers() {
    val calendar = children(icon(NativeColorOsDynamicIcon.entries(
      name, NativeColorOsDynamicIcon.Kind.CALENDAR, backgrounds, fixedDark = false, dim = true)))
    assertEquals(listOf("day.png", "day.png"), calendar.filter { it.tagName == "Image" }.map { it.getAttribute("src") })
    assertEquals("#FF086BD3", calendar[4].getAttribute("color"))
    assertEquals("#FF000000", calendar[5].getAttribute("color"))
    val clock = children(icon(NativeColorOsDynamicIcon.entries(
      name, NativeColorOsDynamicIcon.Kind.CLOCK, clock, fixedDark = false, dim = true)))
    for (image in clock.take(4)) assertFalse(image.hasAttribute("tint"))
    for (image in clock.drop(4)) {
      assertEquals("#FFD6D6D6", image.getAttribute("tint"))
      assertEquals("13", image.getAttribute("tintmode"))
      assertFalse(image.hasAttribute("alpha"))
    }
  }

  @Test fun forcedDarkClockSelectsOriginalNightRastersAndDimOnlyMultipliesTheirColors() {
    val images = children(icon(NativeColorOsDynamicIcon.entries(
      name, NativeColorOsDynamicIcon.Kind.CLOCK, clock, fixedDark = true, dim = true)))
    assertEquals("night.png", images[0].getAttribute("src"))
    assertEquals("night.png", images[4].getAttribute("src"))
    assertFalse(images[0].hasAttribute("tint"))
    assertEquals("#FFD6D6D6", images[4].getAttribute("tint"))
    assertEquals("13", images[4].getAttribute("tintmode"))
    for (image in images.subList(1, 4)) {
      assertTrue(image.getAttribute("src").startsWith("night_clock_"))
      assertFalse(image.hasAttribute("tint"))
      assertFalse(image.hasAttribute("tintmode"))
    }
    for (image in images.subList(5, 8)) {
      assertTrue(image.getAttribute("src").startsWith("night_clock_"))
      assertEquals("#FFD6D6D6", image.getAttribute("tint"))
      assertEquals("13", image.getAttribute("tintmode"))
    }
  }

  @Test fun calendarPreservesIndependentSourceBlueWeekdayAndWhiteNightDateUnderDimming() {
    val fields = children(icon(NativeColorOsDynamicIcon.entries(
      name, NativeColorOsDynamicIcon.Kind.CALENDAR, backgrounds, fixedDark = true, dim = true)))
    assertEquals("#FF0A7FFB", fields[1].getAttribute("color"))
    assertEquals("#FFFFFFFF", fields[2].getAttribute("color"))
    assertEquals("#FF086BD3", fields[4].getAttribute("color"))
    assertEquals("#FFD6D6D6", fields[5].getAttribute("color"))
    assertFalse(fields.any { it.hasAttribute("typeface") })
  }

  @Test fun fallbackSnapshotsArePairedAndNeverAppearInLiveResourcesOrManifest() {
    val inputs = backgrounds + mapOf("fallback.png" to night, "fallback-night.png" to day)
    val entries = NativeColorOsDynamicIcon.entries(name, NativeColorOsDynamicIcon.Kind.CALENDAR, inputs)
    assertArrayEquals(night, entries.getValue("res/drawable-xxhdpi/$name.png"))
    assertArrayEquals(day, entries.getValue("nightmode/res/drawable-xxhdpi/$name.png"))
    assertArrayEquals(day, entries.getValue("$root/day.png"))
    assertArrayEquals(night, entries.getValue("$root/night.png"))
    assertFalse(String(entries.getValue("$root/manifest.xml"), Charsets.UTF_8).contains("fallback"))
    assertFalse(entries.keys.any { it.endsWith("/fallback.png") || it.endsWith("/fallback-night.png") })
  }

  @Test fun outputIsDeterministicAcrossAssetOrderAndAliasesAndOwnsEveryRasterBuffer() {
    val inputs = clock
    val first = NativeColorOsDynamicIcon.entries(name, NativeColorOsDynamicIcon.Kind.CLOCK, inputs)
    val second = NativeColorOsDynamicIcon.entries("org.example.Other", NativeColorOsDynamicIcon.Kind.CLOCK,
      inputs.entries.reversed().associate { it.toPair() })
    assertArrayEquals(first.getValue("$root/manifest.xml"),
      second.getValue("fancy_icons/org.example.Other/manifest.xml"))
    for ((file, png) in inputs) {
      val copied = first.getValue("$root/$file")
      assertNotSame(png, copied)
      assertArrayEquals(png, copied)
    }
    assertNotSame(first.getValue("res/drawable-xxhdpi/$name.png"), first.getValue("$root/day.png"))
    first.getValue("$root/day.png")[0] = 0
    assertEquals(0x89.toByte(), first.getValue("res/drawable-xxhdpi/$name.png")[0])
    for (png in inputs.values) png.fill(0)
    assertEquals(0x89.toByte(), first.getValue("$root/night.png")[0])
  }

  @Test fun rejectsMissingExtraAndUnsafeAssetsAndIncompatiblePngBounds() {
    fun reject(assets: Map<String, ByteArray>, kind: NativeColorOsDynamicIcon.Kind = NativeColorOsDynamicIcon.Kind.CALENDAR) {
      assertThrows(IllegalArgumentException::class.java) { NativeColorOsDynamicIcon.entries(name, kind, assets) }
    }
    reject(backgrounds - "night.png")
    reject(backgrounds + ("manifest.xml" to day))
    reject(backgrounds + ("../day.png" to day))
    reject(backgrounds + ("fallback.png" to day))
    reject(backgrounds + ("fallback-night.png" to night))
    reject(clock)
    reject(clock - "clock_day_bg.png", NativeColorOsDynamicIcon.Kind.CLOCK)
    reject(clock - "night_clock_day_bg.png", NativeColorOsDynamicIcon.Kind.CLOCK)
    reject(clock.filterKeys { !it.startsWith("night_clock_") }, NativeColorOsDynamicIcon.Kind.CLOCK)
    reject(clock + ("clock_day_second.png" to day), NativeColorOsDynamicIcon.Kind.CLOCK)
    reject(backgrounds + ("night.png" to byteArrayOf(1)))
    reject(backgrounds + ("night.png" to day.copyOf().apply { this[0] = 0 }))
    reject(backgrounds + ("night.png" to dimensions(day, 2, 1)))
    reject(backgrounds + ("night.png" to dimensions(day, 1, 1)))
    reject(backgrounds.mapValues { dimensions(it.value, 513, 513) })
    reject(backgrounds.mapValues { dimensions(it.value, 0, 0) })
    reject(backgrounds + ("day.png" to day.copyOf(4 * 1024 * 1024 + 1)))
    reject(clock + ("clock_day_hour_rectangle.png" to dimensions(day, 1, 1)), NativeColorOsDynamicIcon.Kind.CLOCK)
    reject(backgrounds + mapOf("fallback.png" to day, "fallback-night.png" to dimensions(night, 1, 1)))
    for (invalidName in listOf("../bad", "org.example/Clock", "org.example\"Clock", "org.example..Clock", "a".repeat(241))) {
      assertThrows(IllegalArgumentException::class.java) {
        NativeColorOsDynamicIcon.entries(invalidName, NativeColorOsDynamicIcon.Kind.CALENDAR, backgrounds)
      }
    }
  }

  @Test fun clockResourcesAndFallbackSnapshotsCanHaveIndependentSquareRasterResolutions() {
    val smallBackground = backgrounds.mapValues { dimensions(it.value, 1, 1) }
    val assets = clock + smallBackground + mapOf(
      "fallback.png" to dimensions(day, 3, 3), "fallback-night.png" to dimensions(night, 3, 3))
    val entries = NativeColorOsDynamicIcon.entries(name, NativeColorOsDynamicIcon.Kind.CLOCK, assets)
    assertRoot(icon(entries))
    assertArrayEquals(assets.getValue("clock_day_bg.png"), entries.getValue("$root/clock_day_bg.png"))
  }

  private fun assertRoot(icon: Element) {
    assertEquals("Icon", icon.tagName)
    assertEquals(mapOf("version" to "1", "width" to "150", "height" to "150",
      "screenWidth" to "1080", "resDensity" to "480", "frameRate" to "0",
      "useVariableUpdater" to "DateTime.Minute"), attributes(icon))
  }

  private fun icon(entries: Map<String, ByteArray>): Element {
    val parser = DocumentBuilderFactory.newInstance().apply {
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder()
    return entries.getValue("$root/manifest.xml").inputStream().use { parser.parse(it).documentElement }
  }

  private fun children(icon: Element): List<Element> =
    (0 until icon.childNodes.length).mapNotNull { icon.childNodes.item(it) as? Element }

  private fun attributes(element: Element): Map<String, String> =
    (0 until element.attributes.length).associate {
      element.attributes.item(it).let { attribute -> attribute.nodeName to attribute.nodeValue }
    }

  private fun dimensions(png: ByteArray, width: Int, height: Int) = png.copyOf().also {
    ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).apply { putInt(16, width); putInt(20, height) }
  }
}
