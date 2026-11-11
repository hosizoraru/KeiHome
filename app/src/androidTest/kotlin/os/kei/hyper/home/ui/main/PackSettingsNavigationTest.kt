package os.kei.hyper.home.ui.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import java.util.UUID
import org.junit.After
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.MainNavigation
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.data.OnboardingPreferences
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class PackSettingsNavigationTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val id = "pack_settings_nav_${UUID.randomUUID()}"
  private val store by lazy { OnboardingPreferences(context, id, existingConfiguration = false) }

  @After fun clean() {
    AppLocalStorage.open(context, id).close()
    MMKV.removeStorage(id)
  }

  private fun waitForTag(tag: String) {
    rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    rule.waitForIdle()
    rule.mainClock.advanceTimeBy(1_000)
    rule.waitForIdle()
  }

  @Test fun preferencesBelongToPacksAndBackReturnsToPacks() {
    store.complete()
    rule.setContent { KeiHomeTheme { MainNavigation(store) } }
    rule.onNode(hasText(context.getString(R.string.navigation_settings)) and isSelectable()).performClick()
    rule.onNodeWithText(context.getString(R.string.pack_action_settings)).assertDoesNotExist()
    rule.onNode(hasText(context.getString(R.string.navigation_icon_packs)) and isSelectable()).performClick()
    rule.onNodeWithText(context.getString(R.string.pack_action_settings)).performScrollTo().performClick()
    waitForTag("icon-packs.settings")
    rule.onNodeWithText(context.getString(R.string.pack_settings_generation)).assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.pack_auto_apply)).assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.pack_installed_icons_only)).assertIsDisplayed()
    rule.onNodeWithTag("settings.compact-pack-hints").assertExists()
    rule.onNodeWithContentDescription(context.getString(R.string.pack_back)).performClick()
    rule.onNode(hasText(context.getString(R.string.navigation_icon_packs)) and isSelectable()).assertIsSelected()
    rule.onNodeWithText(context.getString(R.string.pack_action_settings)).assertExists()
  }

  @Test fun wizardShortcutReturnsToItsOriginalStep() {
    store.visit(2)
    rule.setContent { KeiHomeTheme { MainNavigation(store) } }
    waitForTag("setup.page.2")
    rule.onNodeWithText(context.getString(R.string.pack_action_settings)).performScrollTo().performClick()
    waitForTag("icon-packs.settings")
    rule.onNodeWithText(context.getString(R.string.pack_auto_apply)).assertIsDisplayed()
    rule.onNodeWithContentDescription(context.getString(R.string.pack_back)).performClick()
    rule.onNodeWithTag("setup.page.2").assertIsDisplayed()
  }
}
