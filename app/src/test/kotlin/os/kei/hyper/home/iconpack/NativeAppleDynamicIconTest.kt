package os.kei.hyper.home.iconpack

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import os.kei.hyper.home.xposed.GeneratedNativeIconValidator

class NativeAppleDynamicIconTest {
  private val name = "com.example.apple"
  private val root = "fancy_icons/$name/"
  private val day = NativeIconPngFixtures.rgba()
  private val night = NativeIconPngFixtures.rgba { _, _ -> 96 }
  private val hands = listOf(
    "hour.png", "minute.png", "second.png",
    "night_hour.png", "night_minute.png", "night_second.png",
  )

  @Test fun calendarUsesNativeLocalizedWeekdayAndDateWithoutContinuousFrames() {
    val files = entries(NativeAppleDynamicIcon.Kind.CALENDAR)
    val xml = files.getValue("${root}manifest.xml")
    val icon = parse(xml)
    assertEquals("DateTime.Minute", icon.getAttribute("useVariableUpdater"))
    assertEquals("0", icon.getAttribute("frameRate"))
    assertEquals("150", icon.getAttribute("width"))
    assertEquals("150", icon.getAttribute("height"))
    val dates = children(icon, "DateTime")
    assertEquals(listOf("EEEE", "d", "EEEE", "d"), dates.map { it.getAttribute("format") })
    for (date in dates) {
      assertEquals("75", date.getAttribute("x"))
      assertEquals("center", date.getAttribute("align"))
      assertEquals("false", date.getAttribute("bold"))
      assertFalse(date.hasAttribute("text"))
      assertFalse(date.hasAttribute("textExp"))
      assertFalse(date.hasAttribute("timeZone"))
      assertFalse(date.hasAttribute("locale"))
      if (date.getAttribute("format") == "EEEE") {
        assertEquals("20", date.getAttribute("y"))
        assertEquals("20", date.getAttribute("size"))
        assertEquals("#FFFF3B30", date.getAttribute("color"))
      } else {
        assertEquals("43", date.getAttribute("y"))
        assertEquals("76", date.getAttribute("size"))
      }
    }
    assertEquals(listOf("#FF000000", "#FFFFFFFF"),
      dates.filter { it.getAttribute("format") == "d" }.map { it.getAttribute("color") })
    assertEquals(0, children(icon, "Text").size)
    assertNoActiveContent(xml)
    assertArrayEquals(day, files.getValue("res/drawable-xxhdpi/$name.png"))
    assertArrayEquals(night, files.getValue("nightmode/res/drawable-xxhdpi/$name.png"))
    assertEquals(5, files.size)
    validate(files)
  }

  @Test fun clockUsesSecondUpdaterAndSecondAwareAnglesOverNativeDials() {
    val files = entries(NativeAppleDynamicIcon.Kind.CLOCK)
    val xml = files.getValue("${root}manifest.xml")
    val icon = parse(xml)
    assertEquals("0", icon.getAttribute("frameRate"))
    assertEquals("DateTime.Second", icon.getAttribute("useVariableUpdater"))
    val images = children(icon, "Image")
    assertEquals(listOf("day.png", "hour.png", "minute.png", "second.png",
      "night.png", "night_hour.png", "night_minute.png", "night_second.png"),
      images.map { it.getAttribute("src") })
    assertEquals(listOf(
      "#hour12*30+#minute/2+#second/120", "#minute*6+#second/10", "#second*6",
      "#hour12*30+#minute/2+#second/120", "#minute*6+#second/10", "#second*6",
    ), images.filter { it.hasAttribute("angle") }.map { it.getAttribute("angle") })
    for (hand in images.filter { it.hasAttribute("angle") }) {
      for (attribute in listOf("x", "y")) assertEquals("0", hand.getAttribute(attribute))
      for (attribute in listOf("w", "h")) assertEquals("150", hand.getAttribute(attribute))
      for (attribute in listOf("centerX", "centerY")) assertEquals("75", hand.getAttribute(attribute))
    }
    assertEquals(11, files.size)
    assertNoActiveContent(xml)
    validate(files)
  }

  @Test fun everyFixedAppearanceAndDimVariantHasExactSourcesAndDarkOnlyMultiplication() {
    for (kind in NativeAppleDynamicIcon.Kind.entries) {
      for (fixed in listOf<Boolean?>(null, false, true)) for (dim in listOf(false, true)) {
        val files = entries(kind, fixed, dim)
        val xml = files.getValue("${root}manifest.xml")
        assertEquals(kind, NativeAppleDynamicIcon.kindOfManifest(xml))
        val icon = parse(xml)
        val images = children(icon, "Image")
        val fields = children(icon, "DateTime")
        val stride = if (kind == NativeAppleDynamicIcon.Kind.CLOCK) 4 else 1
        for (systemDark in listOf(false, true)) {
          val artworkDark = fixed ?: systemDark
          val visibility = if (fixed == null || dim) {
            if (systemDark) "#__darkmode" else "1-#__darkmode"
          } else if (systemDark == fixed) "1" else "0"
          val branch = images.drop(if (systemDark) stride else 0).take(stride)
          assertEquals(if (artworkDark) "night.png" else "day.png", branch.first().getAttribute("src"))
          for (image in branch) {
            assertEquals(visibility, image.getAttribute("visibility"))
            assertEquals(if (dim && systemDark) "#FFD6D6D6" else "", image.getAttribute("tint"))
            assertEquals(if (dim && systemDark) "13" else "", image.getAttribute("tintmode"))
            assertFalse(image.hasAttribute("alpha"))
          }
          if (kind == NativeAppleDynamicIcon.Kind.CLOCK) {
            assertEquals(listOf("hour.png", "minute.png", "second.png").map {
              if (artworkDark) "night_$it" else it
            }, branch.drop(1).map { it.getAttribute("src") })
          } else {
            val text = fields.drop(if (systemDark) 2 else 0).take(2)
            assertTrue(text.all { it.getAttribute("visibility") == visibility })
            assertEquals(if (dim && systemDark) "#FFD63228" else "#FFFF3B30",
              text.first().getAttribute("color"))
            val dateColor = if (!artworkDark) "#FF000000" else
              if (dim && systemDark) "#FFD6D6D6" else "#FFFFFFFF"
            assertEquals(dateColor, text.last().getAttribute("color"))
          }
        }
        assertNoActiveContent(xml)
        validate(files)
      }
    }
  }

  @Test fun optionalSnapshotsReplaceStaticFallbacksWithoutReplacingLiveAssets() {
    for (kind in NativeAppleDynamicIcon.Kind.entries) {
      val light = NativeIconPngFixtures.rgba(3)
      val dark = NativeIconPngFixtures.rgba(4) { _, _ -> 64 }
      val files = NativeAppleDynamicIcon.entries(name, kind,
        assets(kind) + mapOf("fallback.png" to light, "fallback-night.png" to dark))
      assertArrayEquals(light, files.getValue("res/drawable-xxhdpi/$name.png"))
      assertArrayEquals(dark, files.getValue("nightmode/res/drawable-xxhdpi/$name.png"))
      assertArrayEquals(day, files.getValue("${root}day.png"))
      assertArrayEquals(night, files.getValue("${root}night.png"))
      assertFalse(files.keys.any { it.endsWith("fallback.png") || it.endsWith("fallback-night.png") })
      validate(files)
    }
  }

  @Test fun returnedResourcesOwnTheirBytes() {
    val source = assets(NativeAppleDynamicIcon.Kind.CLOCK)
    val files = NativeAppleDynamicIcon.entries(name, NativeAppleDynamicIcon.Kind.CLOCK, source)
    val original = source.getValue("day.png").copyOf()
    source.getValue("day.png")[0] = 0
    assertArrayEquals(original, files.getValue("${root}day.png"))
    assertArrayEquals(original, files.getValue("res/drawable-xxhdpi/$name.png"))
    files.getValue("${root}day.png")[1] = 0
    assertArrayEquals(original, files.getValue("res/drawable-xxhdpi/$name.png"))
  }

  @Test fun manifestsOnlyRecognizeTheFiniteCanonicalTemplates() {
    val all = NativeAppleDynamicIcon.Kind.entries.flatMap { kind ->
      listOf<Boolean?>(null, false, true).flatMap { fixed ->
        listOf(false, true).map { dim ->
          entries(kind, fixed, dim).getValue("${root}manifest.xml").toString(Charsets.UTF_8)
        }
      }
    }
    assertEquals(12, all.distinct().size)
    for (xml in all) for (changed in listOf(
      xml.trimEnd(), xml.replace("frameRate=\"0\"", "frameRate=\"1\""),
      xml.replace("src=\"", "src=\"../"), xml.replace("src=\"", "src=\"https://example.org/"),
      xml.replace("</Icon>", "<ContentProviderBinder uri=\"content://settings/system\" /></Icon>"),
    )) assertNull(NativeAppleDynamicIcon.kindOfManifest(changed.toByteArray()))
    assertNull(NativeAppleDynamicIcon.kindOfManifest(ByteArray(8193)))
    assertNull(NativeAppleDynamicIcon.kindOfManifest(byteArrayOf(0xc3.toByte(), 0x28)))
  }

  @Test fun rejectsUnexpectedMissingOrMismatchedAssetsAndUnsafeNames() {
    for (kind in NativeAppleDynamicIcon.Kind.entries) {
      val valid = assets(kind)
      for (badName in listOf("", "../escape", "a/b", "a\\b", ".hidden", "a..b",
        "1invalid", "a\"b", "a.png/", "a".repeat(241), "例".repeat(81))) {
        rejects { NativeAppleDynamicIcon.entries(badName, kind, valid) }
      }
      for (missing in valid.keys) rejects {
        NativeAppleDynamicIcon.entries(name, kind, valid - missing)
      }
      for (extra in listOf("../extra.png", "res/day.png", "mask.png", "manifest.xml")) {
        rejects { NativeAppleDynamicIcon.entries(name, kind, valid + (extra to day)) }
      }
      if (kind == NativeAppleDynamicIcon.Kind.CALENDAR) rejects {
        NativeAppleDynamicIcon.entries(name, kind, assets(NativeAppleDynamicIcon.Kind.CLOCK))
      }
      rejects { NativeAppleDynamicIcon.entries(name, kind, valid + ("fallback.png" to day)) }
      rejects { NativeAppleDynamicIcon.entries(name, kind, valid + ("fallback-night.png" to day)) }
      for (leaf in valid.keys) {
        rejects { NativeAppleDynamicIcon.entries(name, kind, valid + (leaf to NativeIconPngFixtures.rgba(3))) }
      }
    }
  }

  @Test fun pngBoundsApplyToEveryLiveAndFallbackAsset() {
    val kind = NativeAppleDynamicIcon.Kind.CLOCK
    val valid = assets(kind) + mapOf("fallback.png" to day, "fallback-night.png" to night)
    val oversized = day.copyOf(8 * 1024 * 1024 + 1)
    val badHeader = day.copyOf().also { it[0] = 0 }
    val badIhdr = day.copyOf().also { it[15] = 0 }
    fun dimensions(width: Int, height: Int) = day.copyOf().also {
      ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(16, width).putInt(20, height)
    }
    val nonsquare = nonsquarePng()
    GeneratedIconPng.validate(nonsquare, 1024)
    val invalid = listOf(ByteArray(32), badHeader, badIhdr, oversized,
      dimensions(0, 0), dimensions(-1, -1), dimensions(1025, 1025), nonsquare)
    for (leaf in valid.keys) for (png in invalid) rejects {
      NativeAppleDynamicIcon.entries(name, kind, valid + (leaf to png))
    }
    val tooLargeForLive = NativeIconPngFixtures.rgba(513)
    for (leaf in assets(kind).keys) rejects {
      NativeAppleDynamicIcon.entries(name, kind, valid + (leaf to tooLargeForLive))
    }
    val maxLive = NativeIconPngFixtures.rgba(512)
    val maxFallback = NativeIconPngFixtures.rgba(1024)
    val files = NativeAppleDynamicIcon.entries(name, kind, valid.mapValues { (leaf, _) ->
      if (leaf.startsWith("fallback")) maxFallback else maxLive
    })
    validate(files)
  }

  @Test fun cappedRuntimeAssetsAcceptIndependentUserSelectedSnapshotResolutions() {
    val live = NativeIconPngFixtures.rgba(512)
    val light = NativeIconPngFixtures.rgba(256)
    val dark = NativeIconPngFixtures.rgba(1024)
    for (kind in NativeAppleDynamicIcon.Kind.entries) {
      val source = assets(kind).mapValues { live } + mapOf(
        "fallback.png" to light, "fallback-night.png" to dark)
      val files = NativeAppleDynamicIcon.entries(name, kind, source)
      assertArrayEquals(live, files.getValue("${root}day.png"))
      assertArrayEquals(live, files.getValue("${root}night.png"))
      assertArrayEquals(light, files.getValue("res/drawable-xxhdpi/$name.png"))
      assertArrayEquals(dark, files.getValue("nightmode/res/drawable-xxhdpi/$name.png"))
      validate(files)
    }
  }

  @Test fun validatorRejectsChangedManifestsPathsMissingHandsAndInvalidPngs() {
    for (kind in NativeAppleDynamicIcon.Kind.entries) {
      val files = entries(kind)
      val manifestPath = "${root}manifest.xml"
      for (path in files.keys) rejects { validate(files - path) }
      for (extra in listOf("helper.xml", "fallback.png", "../extra.png")) {
        rejects { validate(files + ("$root$extra" to day)) }
      }
      for (path in files.keys.filter { it.endsWith(".png") }) {
        rejects { validate(files + (path to ByteArray(32))) }
      }
      val xml = files.getValue(manifestPath).toString(Charsets.UTF_8)
      for (changed in listOf(
        xml.replace("frameRate=\"0\"", "frameRate=\"60\""),
        xml.replace("day.png", "night.png"),
        xml.replace("day.png", "../day.png"),
        xml.replace("</Icon>", "<Image src=\"external.png\" /></Icon>"),
        xml.replace("DateTime.", "DateTime.Other"),
      )) rejects { validate(files + (manifestPath to changed.toByteArray())) }
    }
  }

  @Test fun validatorEnforcesReadBoundsEvenIfReaderIgnoresRequestedLimit() {
    val files = entries(NativeAppleDynamicIcon.Kind.CLOCK)
    val manifest = "${root}manifest.xml"
    rejects { validate(files + (manifest to ByteArray(64 * 1024 + 1))) }
    rejects { validate(files + ("${root}hour.png" to day.copyOf(8 * 1024 * 1024 + 1))) }
    val limits = mutableMapOf<String, Long>()
    GeneratedNativeIconValidator.validate(name, files.keys) { path, limit ->
      limits[path] = limit
      files.getValue(path)
    }
    assertEquals(64L * 1024, limits.getValue(manifest))
    assertTrue(limits.filterKeys { it.endsWith(".png") }.values.all { it == 8L * 1024 * 1024 })
  }

  @Test fun legacyStaticPaletteAndColorOsTemplatesRemainAccepted() {
    val mask = NativeIconPngFixtures.mask()
    for (same in listOf(false, true)) for (expanded in listOf(false, true)) {
      val dark = if (same) day else night
      val ordinary = NativeDayNightIcon.entries(name, day, dark, legacyExpanded = expanded)
      assertNull(NativeAppleDynamicIcon.kindOfManifest(ordinary.getValue("${root}manifest.xml")))
      validate(ordinary + ("nightmode/res/drawable-xxhdpi/$name.png" to dark))
      for (scalar in listOf(false, true)) {
        val palette = NativePaletteIcon.entries(name, day, dark, mask, mask,
          legacyExpanded = expanded, legacyScalarBinders = scalar)
        assertNull(NativeAppleDynamicIcon.kindOfManifest(palette.getValue("${root}manifest.xml")))
        validate(palette + ("nightmode/res/drawable-xxhdpi/$name.png" to dark))
      }
    }
    for (fixed in listOf<Boolean?>(null, false, true)) for (dim in listOf(false, true)) {
      for (kind in NativeColorOsDynamicIcon.Kind.entries) {
        val source = if (kind == NativeColorOsDynamicIcon.Kind.CALENDAR) {
          mapOf("day.png" to day, "night.png" to night)
        } else mapOf("day.png" to day, "night.png" to night,
          "clock_day_bg.png" to day, "clock_day_hour_rectangle.png" to day,
          "clock_day_minute_rectangle.png" to day, "night_clock_day_bg.png" to night,
          "night_clock_day_hour_rectangle.png" to night, "night_clock_day_minute_rectangle.png" to night)
        val colorOs = NativeColorOsDynamicIcon.entries(name, kind, source, fixed, dim)
        assertNull(NativeAppleDynamicIcon.kindOfManifest(colorOs.getValue("${root}manifest.xml")))
        validate(colorOs)
      }
    }
    val plain = NativeDayNightIcon.entries(name, day, night)
    val tooLarge = NativeIconPngFixtures.rgba(513)
    rejects { validate(plain + mapOf(
      "res/drawable-xxhdpi/$name.png" to tooLarge,
      "nightmode/res/drawable-xxhdpi/$name.png" to tooLarge)) }
  }

  private fun assets(kind: NativeAppleDynamicIcon.Kind) = linkedMapOf(
    "day.png" to day.copyOf(), "night.png" to night.copyOf(),
  ).apply {
    if (kind == NativeAppleDynamicIcon.Kind.CLOCK) for (hand in hands) {
      put(hand, NativeIconPngFixtures.mask())
    }
  }

  private fun entries(kind: NativeAppleDynamicIcon.Kind, fixed: Boolean? = null, dim: Boolean = false) =
    NativeAppleDynamicIcon.entries(name, kind, assets(kind), fixed, dim)

  private fun nonsquarePng(): ByteArray {
    // Three complete RGBA scanlines, with a matching 2x3 IHDR and its CRC.
    val png = NativeIconPngFixtures.png(2, 8, 6, ByteArray(3 * (1 + 2 * 4)))
    ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN)
      .putInt(20, 3)
      .putInt(29, CRC32().apply { update(png, 12, 17) }.value.toInt())
    return png
  }

  private fun parse(xml: ByteArray): Element =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream()).documentElement

  private fun children(root: Element, tag: String): List<Element> =
    root.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

  private fun assertNoActiveContent(xml: ByteArray) {
    val text = xml.toString(Charsets.UTF_8)
    for (forbidden in listOf("ContentProviderBinder", "VariableBinder", "<Var ", "Animation",
      "keihome_maml_", "content://", "http://", "https://", "typeface=", "fontPath=")) {
      assertFalse(forbidden, text.contains(forbidden))
    }
    assertNotEquals("", text)
  }

  private fun validate(files: Map<String, ByteArray>) {
    GeneratedNativeIconValidator.validate(name, files.keys) { path, _ -> files.getValue(path) }
  }

  private fun rejects(action: () -> Unit) {
    try {
      action()
      throw AssertionError("Expected malformed generated resources to be rejected")
    } catch (_: IllegalArgumentException) {
      // Asset and bound checks.
    } catch (_: IllegalStateException) {
      // Exact-template rejection.
    } catch (_: NoSuchElementException) {
      // Missing archive resource.
    }
  }


  @Test fun qualifiedNativeStemRetainsItsOwnerAndRejectsTraversalOrMultipleSeparators() {
    val qualified = "com.example.debug#com.example.Clock"
    val kind = NativeAppleDynamicIcon.Kind.CALENDAR
    val files = NativeAppleDynamicIcon.entries(qualified, kind, assets(kind))
    assertArrayEquals(day, files.getValue("res/drawable-xxhdpi/$qualified.png"))
    assertTrue("fancy_icons/$qualified/manifest.xml" in files)
    GeneratedNativeIconValidator.validate(qualified, files.keys) { path, _ -> files.getValue(path) }
    for (invalid in listOf("com.example#../Clock", "com.example#com.other#Clock", "#com.example.Clock", "com.example#")) {
      org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
        NativeAppleDynamicIcon.entries(invalid, kind, assets(kind))
      }
    }
  }
}
