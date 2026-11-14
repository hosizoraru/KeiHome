package os.kei.hyper.home.iconpack.perfect

import org.junit.Assert.*
import org.junit.Test

class PerfectNativeTextNightColorTest {
  @Test fun blackUsesColorParserArgbWithExactDayAndNeutral216Night() {
    assertEquals("argb(255,ifelse(#__darkmode,216,0),ifelse(#__darkmode,216,0),ifelse(#__darkmode,216,0))",
      PerfectDynamicIconValidator.nightTextColor("#000000"))
  }

  @Test fun everyEligibleGrayPreservesOriginalAlphaAndDayChannels() {
    // Decode the emitted channels independently of the admission regex.
    val parser = Regex("""argb\((\d+),ifelse\(#__darkmode,(\d+),(\d+)\),ifelse\(#__darkmode,(\d+),(\d+)\),ifelse\(#__darkmode,(\d+),(\d+)\)\)""")
    for (alpha in listOf(0, 1, 32, 127, 254, 255)) for (gray in 0..32) {
      val literal = "#%02x%02x%02x%02x".format(alpha, gray, gray, gray)
      val expression = requireNotNull(PerfectDynamicIconValidator.nightTextColor(literal))
      val channels = requireNotNull(parser.matchEntire(expression)).groupValues.drop(1).map(String::toInt)
      assertEquals(listOf(alpha, 216, gray, 216, gray, 216, gray), channels)
      assertTrue(PerfectDynamicIconValidator.validLiveTextColor(expression, true))
    }
  }

  @Test fun coloredAndBrighterLiteralsAreNeverLifted() {
    for (literal in listOf("#3482FF", "#000001", "#010000", "#000100", "#20201f",
      "#212121", "#D8D8D8", "#FFFFFF", "#803482ff")) {
      assertNull(literal, PerfectDynamicIconValidator.nightTextColor(literal))
      assertTrue(PerfectDynamicIconValidator.validLiveTextColor(literal, false))
    }
  }

  @Test fun generatedExpressionRequiresDarkModeRegistration() {
    val expression = requireNotNull(PerfectDynamicIconValidator.nightTextColor("#181818"))
    assertFalse(PerfectDynamicIconValidator.validLiveTextColor(expression, false))
    assertTrue(PerfectDynamicIconValidator.validLiveTextColor(expression, true))
  }

  @Test fun generatedWhitelistRejectsArbitraryOrAlmostMatchingExpressions() {
    val expression = requireNotNull(PerfectDynamicIconValidator.nightTextColor("#101010"))
    val invalid = listOf(
      expression.replace("255,", "256,"),
      expression.replace("255,", "-1,"),
      expression.replace("255,", "0255,"),
      expression.replace(",216,", ",217,"),
      expression.replace(",16)", ",33)"),
      expression.replaceFirst(",16)", ",15)"),
      expression.replace("#__darkmode", "#dark"),
      expression.replace("#__darkmode", "#__darkmode+1"),
      expression.replace("argb(", "argb( "),
      expression + " ",
      "argb(255,1,2,3)",
      "ifelse(#__darkmode,'#D8D8D8','#000000')",
      "@theme_color", "#000", "#000000000", "",
    )
    for (value in invalid) {
      assertFalse(value, PerfectDynamicIconValidator.validLiveTextColor(value, true))
      assertNull(PerfectDynamicIconValidator.nightTextColor(value))
    }
  }
}
