package os.kei.hyper.home.ui.main

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import os.kei.hyper.home.MainActivity
import os.kei.hyper.home.R
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()


  @Test
  fun keepsAppSearchWhenReturningFromSettings() {
    val context = composeTestRule.activity
    composeTestRule.onNode(hasText(context.getString(R.string.navigation_apps)) and isSelectable()).performClick()
    composeTestRule.onNode(hasSetTextAction()).performTextInput("KeiHome")
    composeTestRule.onNode(hasText(context.getString(R.string.navigation_settings)) and isSelectable()).performClick()
    composeTestRule.onNode(hasText(context.getString(R.string.navigation_apps)) and isSelectable()).performClick()
    composeTestRule.onNode(hasSetTextAction() and hasText("KeiHome")).assertExists()
  }

  @Test
  fun separatesModuleStatusFromSettings() {
    val context = composeTestRule.activity
    composeTestRule.onNodeWithText(context.getString(R.string.dashboard_runtime)).assertExists()
    composeTestRule.onNode(hasText(context.getString(R.string.navigation_settings)) and isSelectable()).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.settings_language)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.dashboard_runtime)).assertDoesNotExist()
    composeTestRule.onNode(hasText(context.getString(R.string.navigation_status)) and isSelectable()).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.dashboard_runtime)).assertExists()
  }

  @Test
  fun showsFilterAndTopBarRefresh() {
    val context = composeTestRule.activity
    composeTestRule.onNode(hasText(context.getString(R.string.navigation_apps)) and isSelectable()).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.search_apps)).assertExists()
    composeTestRule.onNodeWithContentDescription(context.getString(R.string.filter_title))
      .assertExists()
    composeTestRule.onNodeWithContentDescription(context.getString(R.string.refresh)).assertExists()
  }

  @Test
  fun settingsOffersAppLanguages() {
    val context = composeTestRule.activity
    composeTestRule.onNode(hasText(context.getString(R.string.navigation_settings)) and isSelectable()).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.settings_language)).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.language_chinese)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.language_english)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.language_japanese)).assertExists()
  }

  @Test
  fun opensIndependentMonetPage() {
    val context = composeTestRule.activity
    composeTestRule.onNode(
      hasText(context.getString(R.string.navigation_monet)) and isSelectable(),
    ).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_current_theme)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_seed_source)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.dashboard_runtime)).assertDoesNotExist()
  }

  @Test
  fun statusMonetSummaryOpensMonetPage() {
    val context = composeTestRule.activity
    composeTestRule.onNodeWithTag("status.monet.card").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("status.monet.settings").performScrollTo().performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_current_theme)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_seed_source)).assertExists()
  }

  @Test
  fun keepsRuntimeSheetTransientAcrossRepeatedStatusAndMonetNavigation() {
    val context = composeTestRule.activity
    repeat(3) {
      composeTestRule.onNodeWithTag("status.runtime.card").performScrollTo().performClick()
      composeTestRule.onNodeWithTag("status.runtime.sheet").assertExists()
      composeTestRule.runOnUiThread { context.onBackPressedDispatcher.onBackPressed() }
      composeTestRule.waitUntil(5_000) {
        runCatching { composeTestRule.onNodeWithTag("status.runtime.sheet").assertDoesNotExist(); true }.getOrDefault(false)
      }
      composeTestRule.onNodeWithTag("status.monet.card").performScrollTo().performClick()
      composeTestRule.onNodeWithTag("status.monet.settings").performScrollTo().performClick()
      composeTestRule.onNodeWithText(context.getString(R.string.monet_current_theme)).assertExists()
      composeTestRule.runOnUiThread { context.onBackPressedDispatcher.onBackPressed() }
      composeTestRule.onNode(hasText(context.getString(R.string.navigation_status)) and isSelectable())
        .performClick()
      composeTestRule.onNodeWithText(context.getString(R.string.dashboard_runtime)).assertExists()
      composeTestRule.onNodeWithTag("status.runtime.sheet").assertDoesNotExist()
      composeTestRule.onNodeWithContentDescription(context.getString(R.string.refresh))
        .assertExists()
    }
  }
}
