package os.kei.hyper.home.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
  @Test fun matchesAllTermsIgnoringCaseAndWhitespace() {
    assertTrue(settingsQueryMatches("  monet  SYSTEM  ", "System Monet palette"))
    assertFalse(settingsQueryMatches("monet dark", "System Monet palette"))
    assertTrue(settingsQueryMatches("  ", "Any setting"))
  }

  @Test fun matchesLocalizedLabelsAndSummaries() {
    assertTrue(settingsQueryMatches("材质", "系统外观 保留系统材质效果"))
    assertTrue(settingsQueryMatches("外観", "システムの外観"))
    assertFalse(settingsQueryMatches("壁纸", "系统外观 保留系统材质效果"))
  }
}
