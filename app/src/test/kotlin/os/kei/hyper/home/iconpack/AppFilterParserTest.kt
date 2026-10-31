package os.kei.hyper.home.iconpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFilterParserTest {
  @Test fun canonicalizesRelativeComponentsAndKeepsExternalActivityOwnership() {
    val parsed = parse("""
      <item component="ComponentInfo{org.example.app/.Main}" drawable="primary" />
      <item component="ComponentInfo{org.example.other/com.vendor.Launcher}" drawable="@mipmap/other" />
      <item component="org.example.app/org.example.app.Main${'$'}Alias" drawable="@drawable/alias" />
      <item component="ComponentInfo{org.example.fallback/}" drawable="fallback" />
    """)
    assertEquals(4, parsed.mappings.size)
    assertEquals(IconDrawableName("primary"), parsed.mappings[IconPackComponent("org.example.app", "org.example.app.Main")])
    assertEquals(IconDrawableName("other", "mipmap"), parsed.mappings[IconPackComponent("org.example.other", "com.vendor.Launcher")])
    assertTrue(IconPackComponent("org.example.fallback") in parsed.mappings)
  }

  @Test fun ignoresInvalidMappingsButReportsThemAndRetainsLauncherDirectives() {
    val result = parse("""
      <item component="ComponentInfo{org.example/.Main}" drawable="first" />
      <item component="org.example/org.example.Main" drawable="second" />
      <item component="ComponentInfo{org.bad/../Main}" drawable="bad" />
      <item component="org.example" drawable="../../outside" />
      <item component=":BROWSER" drawable="browser" />
      <calendar component="ComponentInfo{org.calendar/.Main}" prefix="calendar_" />
      <iconmask img1="mask" /><scale factor="0.8" />
    """)
    assertEquals(mapOf(IconPackComponent("org.example", "org.example.Main") to IconDrawableName("second")), result.mappings)
    assertTrue(result.notices.containsAll(setOf(IconPackNotice.INVALID_FILTER_ITEMS, IconPackNotice.CONFLICTING_MAPPINGS,
      IconPackNotice.LAUNCHER_SHORTCUTS, IconPackNotice.DYNAMIC_FILTER_DIRECTIVES, IconPackNotice.FALLBACK_MASK_DIRECTIVES)))
    assertEquals(listOf("item", "calendar", "iconmask", "scale"), result.directives.map { it.tag })
  }

  @Test fun componentParserRejectsMalformedWrappersPathsAndInjectedWhitespace() {
    listOf("ComponentInfo{org.app/.Main", "org.app/.Main}", "org.app/a/b", "org..app/.Main",
      "/org.app.Main", "../outside", "org.app/\\outside", "org.app/.Main\nOther", "C:/outside",
      "org.app/org.app..Main", ":CLOCK").forEach { assertNull(it, IconPackComponent.parse(it)) }
    assertNull(IconDrawableName.parse("@other.pack:drawable/icon"))
    assertNull(IconDrawableName.parse("drawable/icon.xml"))
  }

  @Test fun rejectsDoctypesAlsoWhenEncodedAsUtf16() {
    val xml = "<!DOCTYPE resources [<!ENTITY probe SYSTEM 'file:///no-such-keihome-test-file'>]><resources>&probe;</resources>"
    for (bytes in listOf(xml.toByteArray(), ("\uFEFF" + xml).toByteArray(Charsets.UTF_16LE))) {
      assertThrows(IconPackException::class.java) { AppFilterParser().parse(bytes) }
    }
  }

  @Test fun rejectsWrongRootTruncationDeepNestingAndSizeOrItemOverflows() {
    listOf("<theme/>", "<resources><item", "<resources>" + "<a>".repeat(20) + "</a>".repeat(20) + "</resources>")
      .forEach { value -> assertThrows(IconPackException::class.java) { AppFilterParser().parse(value.toByteArray()) } }
    assertThrows(IconPackException::class.java) { AppFilterParser(IconPackLimits(maxXmlBytes = 8)).parse("<resources/>".toByteArray()) }
    assertThrows(IconPackException::class.java) {
      AppFilterParser(IconPackLimits(maxMappings = 1)).parse("<resources><item/><item/></resources>".toByteArray())
    }
  }

  @Test fun nightDensityAndApiQualifiersAreIndependentAndUnknownQualifiersStayVisible() {
    val night = IconResourceQualifiers.parse("drawable-night-xxhdpi-v31")!!
    assertEquals(IconNightMode.DARK, night.night)
    assertEquals(480, night.densityDpi)
    assertEquals(31, night.minSdk)
    val day = variant("drawable-notnight-xxhdpi", "light")
    val shared = variant("drawable-xhdpi", "shared")
    val dark = variant("drawable-night-xxhdpi-v31", "dark")
    val future = variant("drawable-night-xxhdpi-v99", "future")
    val unknown = variant("drawable-land-night-xxhdpi", "landscape")
    assertEquals(listOf("land"), unknown.qualifiers.unsupported)
    val variants = listOf(day, shared, dark, future, unknown)
    assertEquals(day, selectArchiveVariant(variants, dark = false, densityDpi = 480, sdk = 37))
    assertEquals(dark, selectArchiveVariant(variants, dark = true, densityDpi = 480, sdk = 37))
    assertEquals(shared, selectArchiveVariant(variants, dark = true, densityDpi = 480, sdk = 30))
    assertNull(selectArchiveVariant(listOf(dark), dark = false, densityDpi = 480, sdk = 37))
    assertFalse(IconResourceQualifiers.parse("mipmap-night-anydpi-v26")!!.unsupported.isNotEmpty())
  }

  private fun parse(items: String) = AppFilterParser().parse("<resources>$items</resources>".toByteArray())
  private fun variant(directory: String, name: String) = IconArchiveVariant("res/$directory/$name.png", IconResourceQualifiers.parse(directory)!!)
}
