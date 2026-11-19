package os.kei.hyper.home.xposed

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.BILIBILI_PACKAGE
import os.kei.hyper.home.data.NavigationBackdropChoice
import os.kei.hyper.home.data.NavigationBackdropPreferences

class NavigationRuleOverlayTest {
  @Test fun defaultsNeverSelectAnApplication() {
    assertFalse(NavigationBackdropPreferences.read(null).includes("sample.app", 10001))
    assertFalse(NavigationBackdropChoice(packages = setOf("sample.app")).includes("sample.app", 10001))
    assertFalse(NavigationBackdropChoice(enabled = true).includes("sample.app", 10001))
  }

  @Test fun selectionIsBoundedToTheOwningUserAndApplicationUid() {
    val choice = NavigationBackdropChoice(true, setOf("sample.app"), 10)
    assertTrue(choice.includes("sample.app", 1010001))
    assertFalse(choice.includes("other.app", 1010001))
    assertFalse(choice.includes("sample.app", 10001))
    assertFalse(choice.includes("sample.app", 1001000))
    assertFalse(choice.includes("sample.app", -1))
  }

  @Test fun artworkBindsOnlyBilibiliAndPreservesManualSelection() {
    val ordinary = NavigationBackdropChoice(packages = setOf("sample.app"))
    val artwork = ordinary.copy(bilibiliArtwork = true)
    assertEquals(setOf("sample.app", BILIBILI_PACKAGE), artwork.selectedPackages)
    assertTrue(artwork.includesBilibiliArtwork(10001))
    assertFalse(artwork.includesBilibiliArtwork(1010001))
    assertFalse(artwork.includes("sample.app", 10001))
    assertNotEquals(ordinary.fingerprint(), artwork.fingerprint())
    assertEquals(ordinary, artwork.copy(bilibiliArtwork = false))
    val manuallySelected = ordinary.copy(enabled = true, packages = setOf(BILIBILI_PACKAGE))
    assertTrue(manuallySelected.includes(BILIBILI_PACKAGE, 10001))
    assertFalse(manuallySelected.includesBilibiliArtwork(10001))
    assertEquals(manuallySelected.packages,
      manuallySelected.copy(bilibiliArtwork = true).selectedPackages)
  }

  @Test fun footerPlacementSupportsEitherShiftDirectionAndRejectsMissingCoverage() {
    val avdHeight = 249f * 1120 / 1242
    val physicalHeight = 249f * 1220 / 1242
    val avdTop = bottomAlignedFooterTop(2436, 2376, 2203, avdHeight)!!
    val physicalTop = bottomAlignedFooterTop(2656, 2591, 2375, physicalHeight)!!
    assertEquals(-8.54f, avdTop - 2220, 0.02f)
    assertEquals(64.41f, physicalTop - 2347, 0.02f)
    assertNull(bottomAlignedFooterTop(2436, 2376, 2203, 60f))
    assertNull(bottomAlignedFooterTop(2436, 2376, 2203, 234f))
    assertNull(bottomAlignedFooterTop(2436, 2376, 2203, Float.NaN))
    assertNull(bottomAlignedFooterTop(2436, 2436, 2203, avdHeight))
  }

  @Test fun onlyDisabledActivitiesChangeAndAllOemFieldsSurvive() {
    val raw = """{"Main":{"mode":0,"sf_sampling_mode":0,"popupMode":0,"dialogMode":0,"viewRules":[{"future":true}],"future":{"value":42}},"Player":{"mode":2,"color":"#ffffff"},"Authored":{"mode":1},"Default":{"mode":-1}}"""
    val result = JSONObject(NavigationRuleOverlay.rewriteActivityRules(raw)!!)
    assertEquals(-1, result.getJSONObject("Main").getInt("mode"))
    assertEquals(0, result.getJSONObject("Main").getInt("dialogMode"))
    assertEquals(0, result.getJSONObject("Main").getInt("popupMode"))
    assertEquals(0, result.getJSONObject("Main").getInt("sf_sampling_mode"))
    assertTrue(result.getJSONObject("Main").getJSONArray("viewRules").getJSONObject(0).getBoolean("future"))
    assertEquals(42, result.getJSONObject("Main").getJSONObject("future").getInt("value"))
    assertEquals(2, result.getJSONObject("Player").getInt("mode"))
    assertEquals("#ffffff", result.getJSONObject("Player").getString("color"))
    assertEquals(1, result.getJSONObject("Authored").getInt("mode"))
    assertEquals(-1, result.getJSONObject("Default").getInt("mode"))
    assertEquals(0, JSONObject(raw).getJSONObject("Main").getInt("mode"))
  }

  @Test fun missingActivityFieldsAreNotSynthesized() {
    val result = JSONObject(NavigationRuleOverlay.rewriteActivityRules("""{"Unknown":{"future":true}}""")!!)
    assertFalse(result.getJSONObject("Unknown").has("mode"))
    assertEquals("", NavigationRuleOverlay.rewriteActivityRules(""))
  }

  @Test fun disabledBilibiliHomeUsesOemPixelSamplingWithoutChangingOtherPolicies() {
    val home = "tv.danmaku.bili.NewHomeActivity"
    val raw = """{"$home":{"mode":0,"sf_sampling_mode":0},"Settings":{"mode":2,"sf_sampling_mode":0},"Other":{"mode":0,"sf_sampling_mode":0}}"""
    val rewritten = NavigationRuleOverlay.rewriteActivityRules(raw, home)!!
    val result = JSONObject(rewritten)
    assertEquals(1, result.getJSONObject(home).getInt("sf_sampling_mode"))
    assertEquals(2, result.getJSONObject("Settings").getInt("mode"))
    assertEquals(0, result.getJSONObject("Other").getInt("sf_sampling_mode"))
    assertEquals(rewritten, NavigationRuleOverlay.rewriteActivityRules(rewritten, home))
    val unrelated = JSONObject(NavigationRuleOverlay.rewriteActivityRules(raw, "other.app")!!)
    assertEquals(0, unrelated.getJSONObject(home).getInt("sf_sampling_mode"))
    for ((mode, sampling) in listOf(2 to 0, -1 to 0, 0 to -1, 0 to 1)) {
      val input = """{"$home":{"mode":$mode,"sf_sampling_mode":$sampling}}"""
      val preserved = JSONObject(NavigationRuleOverlay.rewriteActivityRules(input, home)!!)
      assertEquals(sampling, preserved.getJSONObject(home).getInt("sf_sampling_mode"))
    }
  }

  @Test fun renderedSamplingOnlyFillsMissingLaunchPolicy() {
    for (tree in listOf("", "{}", """{"Other":{"mode":1}}""")) {
      val home = JSONObject(NavigationRuleOverlay.rewriteActivityRules(tree, "Home")!!).getJSONObject("Home")
      assertEquals(-1, home.getInt("mode"))
      assertEquals(1, home.getInt("sf_sampling_mode"))
    }
    for (field in listOf("\"color\":123", "\"viewRules\":[{}]")) {
      val tree = """{"Home":{"mode":0,"sf_sampling_mode":0,$field}}"""
      val home = JSONObject(NavigationRuleOverlay.rewriteActivityRules(tree, "Home")!!).getJSONObject("Home")
      assertEquals(0, home.getInt("sf_sampling_mode"))
    }
  }

  @Test fun footerColorsDistinguishArtworkTransparencyAndHandleContrast() {
    fun strip(rgb: Int) = IntArray(33) { 0xff000000.toInt() or rgb }
    fun paint(rgb: Int, previous: Boolean? = null) = FooterPalette.handlePaint(strip(rgb), previous)!!
    assertEquals(0x80ffffff.toInt(), paint(0x17181a))
    assertEquals(0, paint(0xf0e2d4) and 0xffffff)
    assertEquals(0, paint(0x757575, true) and 0xffffff)
    assertEquals(0xffffff, paint(0x757575, false) and 0xffffff)
    assertTrue(paint(0x757575, true) ushr 24 > paint(0xffffff) ushr 24)
    assertTrue(paint(0xffc8d8) ushr 24 in 102..145)
    // The white majority favors dark ink even if the preceding tab needed white.
    val split = IntArray(33) { if (it < 20) -1 else 0xff000000.toInt() }
    assertEquals(paint(0xffffff), FooterPalette.handlePaint(split, false))
    val solid = strip(0x222222)
    assertEquals(solid.first(), FooterPalette.solid(solid))
    solid[2] = -1
    assertNull(FooterPalette.solid(solid))
    assertEquals(0xffffff, FooterPalette.handlePaint(solid, null)!! and 0xffffff)
    solid[3] = 0
    assertNull(FooterPalette.handlePaint(solid, null))
    assertNull(FooterPalette.handlePaint(intArrayOf(), null))
  }

  @Test fun malformedOrUnexpectedActivityTreesStayUnchanged() {
    for (raw in listOf("not-json", "[]", "{\"Main\":null}", "{\"Main\":0}", "{\"Main\":[]}"))
      assertNull(raw, NavigationRuleOverlay.rewriteActivityRules(raw))
  }

  @Test fun ruleLimitsFailClosedIncludingUtf8ByteLength() {
    assertNull(NavigationRuleOverlay.rewriteActivityRules("x".repeat(NavigationRuleOverlay.MAX_RULE_BYTES + 1)))
    val unicode = JSONObject().put("Main", JSONObject().put("future", "\u4e00".repeat(45000))).toString()
    assertTrue(unicode.length < NavigationRuleOverlay.MAX_RULE_BYTES)
    assertNull(NavigationRuleOverlay.rewriteActivityRules(unicode))
    val tooMany = JSONObject()
    repeat(NavigationRuleOverlay.MAX_ACTIVITIES + 1) { tooMany.put("A$it", JSONObject().put("mode", 0)) }
    assertNull(NavigationRuleOverlay.rewriteActivityRules(tooMany.toString()))
  }

  @Test fun applyingTheOverlayAgainDoesNotChangePolicy() {
    val first = NavigationRuleOverlay.rewriteActivityRules("""{"Main":{"mode":0},"Other":{"mode":2}}""")!!
    assertEquals(first, NavigationRuleOverlay.rewriteActivityRules(first))
  }

  @Test fun settingsFingerprintIsOrderIndependentAndIncludesActivationAndUser() {
    val choice = NavigationBackdropChoice(true, setOf("one.app", "two.app"))
    assertEquals(choice.fingerprint(), choice.copy(packages = setOf("two.app", "one.app")).fingerprint())
    assertEquals(64, choice.fingerprint().length)
    assertNotEquals(choice.fingerprint(), choice.copy(enabled = false).fingerprint())
    assertNotEquals(choice.fingerprint(), choice.copy(userId = 10).fingerprint())
  }

  @Test fun deepAndTrailingJsonIsNotNormalizedIntoANewPolicy() {
    val raw = "{\"Main\":{\"future\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}}"
    assertNull(NavigationRuleOverlay.rewriteActivityRules(raw))
    assertNull(NavigationRuleOverlay.rewriteActivityRules("{} {}"))
    assertNull(NavigationRuleOverlay.rewriteActivityRules("{} trailing"))
    val quoted = JSONObject().put("Main", JSONObject().put("mode", 0).put("future", "[\"\\\\{}]"))
    assertNotNull(NavigationRuleOverlay.rewriteActivityRules(quoted.toString()))
  }

  @Test fun plainHandleStillShowsWhileAwaitingTheNextNativeSample() {
    fun over(color: Int, background: Int): Int {
      val alpha = color ushr 24
      return ((color and 255) * alpha + background * (255 - alpha) + 127) / 255
    }
    val light = plainGesturePaint(0x80ffffff.toInt())
    val dark = plainGesturePaint(0x66000000)
    assertEquals(128, over(light, 0))
    assertEquals(153, over(dark, 255))
    assertEquals(207, over(light, 255))
    assertEquals(48, over(dark, 0))
    for (color in listOf(0x00ffffff, 0xff888888.toInt(), 0x80654321.toInt())) {
      assertEquals(color, plainGesturePaint(color))
    }
  }
}
