package os.kei.hyper.home.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {
  @Test
  fun recognizesSystemAndRegionalLanguagePreferences() {
    assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
    assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag("zh-Hans-CN"))
    assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US,ja"))
    assertEquals(AppLanguage.JAPANESE, AppLanguage.fromTag("ja-JP"))
  }

  @Test
  fun doesNotMisrepresentUnsupportedLanguages() {
    assertNull(AppLanguage.fromTag("zh-Hant-TW"))
    assertNull(AppLanguage.fromTag("fr"))
  }
}
