package os.kei.hyper.home.xposed

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
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
}
