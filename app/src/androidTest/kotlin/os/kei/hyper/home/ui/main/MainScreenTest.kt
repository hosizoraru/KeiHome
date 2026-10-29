package os.kei.hyper.home.ui.main

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import os.kei.hyper.home.MainActivity
import os.kei.hyper.home.R
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun separatesModuleStatusFromSettings() {
    val context = composeTestRule.activity
    composeTestRule.onNodeWithText(context.getString(R.string.status_framework)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.navigation_settings)).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.settings_language)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.status_framework)).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.navigation_status)).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.status_framework)).assertExists()
  }

  @Test
  fun showsFilterAndTopBarRefresh() {
    val context = composeTestRule.activity
    composeTestRule.onNodeWithText(context.getString(R.string.navigation_apps)).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.search_apps)).assertExists()
    composeTestRule.onNodeWithContentDescription(context.getString(R.string.filter_title))
      .assertExists()
    composeTestRule.onNodeWithContentDescription(context.getString(R.string.refresh)).assertExists()
  }

  @Test
  fun settingsOffersAppLanguages() {
    val context = composeTestRule.activity
    composeTestRule.onNodeWithText(context.getString(R.string.navigation_settings)).performClick()
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
    composeTestRule.onNodeWithText(context.getString(R.string.monet_overview_title)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_experimental_note)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.status_framework)).assertDoesNotExist()
  }

  @Test
  fun statusMonetSummaryOpensMonetPage() {
    val context = composeTestRule.activity
    composeTestRule.onNode(
      hasText(context.getString(R.string.settings_monet_title)) and
        hasClickAction() and !isSelectable(),
    ).performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_overview_title)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_experimental_note)).assertExists()
  }

  @Test
  fun retainsChromeAcrossRepeatedStatusAndMonetNavigation() {
    val context = composeTestRule.activity
    repeat(3) {
      composeTestRule.onNode(
        hasText(context.getString(R.string.navigation_monet)) and isSelectable(),
      ).performClick()
      composeTestRule.onNodeWithText(context.getString(R.string.monet_overview_title)).assertExists()
      composeTestRule.onNodeWithContentDescription(context.getString(R.string.refresh))
        .assertExists()
      composeTestRule.onNode(
        hasText(context.getString(R.string.navigation_status)) and isSelectable(),
      ).performClick()
      composeTestRule.onNodeWithText(context.getString(R.string.status_framework)).assertExists()
      composeTestRule.onNodeWithContentDescription(context.getString(R.string.refresh))
        .assertExists()
    }
  }
}
