package os.kei.hyper.home.ui.main

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
}
