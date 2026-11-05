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

  @Test fun launcherGlassSearchIncludesLocalizedWallpaperSchemesAndScope() {
    assertTrue(settingsQueryMatches("柔光 浅色壁纸", "系统外观 柔光玻璃质感 跟随壁纸 浅色壁纸方案 深色壁纸方案 Dock 文件夹 搜索"))
    assertTrue(settingsQueryMatches("glass dark wallpaper", "System appearance Soft glass material Follow wallpaper Light wallpaper scheme Dark wallpaper scheme"))
    assertTrue(settingsQueryMatches("ガラス 明るい壁紙", "システムの外観 ソフトガラスの質感 壁紙に合わせる 明るい壁紙用の配色 暗い壁紙用の配色"))
    assertFalse(settingsQueryMatches("玻璃 性能", "系统外观 柔光玻璃质感 跟随壁纸 浅色壁纸方案 深色壁纸方案"))
  }
}
