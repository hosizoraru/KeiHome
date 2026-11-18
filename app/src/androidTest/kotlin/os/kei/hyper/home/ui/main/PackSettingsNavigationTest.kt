package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.MainNavigation
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.data.AppPreferences
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

  private fun back() {
    val buttons = rule.onAllNodesWithContentDescription(context.getString(R.string.pack_back))
    rule.waitUntil(10_000) {
      buttons.fetchSemanticsNodes().indices.count { buttons[it].isDisplayed() } == 1
    }
    val visible = buttons.fetchSemanticsNodes().indices.single { buttons[it].isDisplayed() }
    buttons[visible].performClick()
  }

  @Test fun preferencesBelongToPacksAndBackReturnsToPacks() {
    val preferences = AppPreferences.get(context)
    val original = preferences.state.value
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
    rule.onNodeWithTag("settings.compact-pack-hints").assertDoesNotExist()
    rule.onNodeWithTag("icon-packs.complete-system-icon-config").assertDoesNotExist()
    rule.onNodeWithTag("icon-packs.open-mask").performScrollTo().performClick()
    waitForTag("icon-mask.page")
    rule.onNodeWithTag("icon-mask.import").assertIsDisplayed()
    rule.onNodeWithTag("icon-mask.complete").performScrollTo().assertIsDisplayed()
    rule.waitUntil(15_000) {
      rule.onAllNodesWithTag("icon-mask.pack").fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNodeWithText(context.getString(R.string.icon_mask_compare_original)).performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.icon_mask_compare_hyperos4)).assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.icon_mask_compare_custom)).assertIsDisplayed()
    rule.waitUntil(20_000) {
      listOf(R.string.icon_mask_compare_original, R.string.icon_mask_compare_hyperos4).all {
        rule.onAllNodesWithContentDescription(context.getString(it)).fetchSemanticsNodes().isNotEmpty()
      }
    }
    rule.runOnIdle { assertEquals("Opening mask comparisons must not edit preferences", original, preferences.state.value) }
    back()
    waitForTag("icon-packs.settings")
    rule.onNodeWithTag("settings.icon-previews").performScrollTo().assertIsDisplayed()
    back()
    rule.onNode(hasText(context.getString(R.string.navigation_icon_packs)) and isSelectable()).assertIsSelected()
    rule.onNodeWithText(context.getString(R.string.pack_action_settings)).assertExists()
    rule.runOnIdle { assertEquals("Navigation must not change the active configuration", original, preferences.state.value) }
  }

  @Test fun wizardShortcutReturnsToItsOriginalStep() {
    val preferences = AppPreferences.get(context)
    val original = preferences.state.value
    store.visit(2)
    rule.setContent { KeiHomeTheme { MainNavigation(store) } }
    waitForTag("setup.page.2")
    rule.onNodeWithText(context.getString(R.string.setup_os4_icons_more)).performScrollTo().performClick()
    waitForTag("icon-packs.settings")
    rule.onNodeWithText(context.getString(R.string.pack_auto_apply)).assertIsDisplayed()
    rule.onNodeWithTag("icon-packs.open-mask").performScrollTo().performClick()
    waitForTag("icon-mask.page")
    rule.onNodeWithTag("icon-mask.complete").performScrollTo().assertIsDisplayed()
    back()
    waitForTag("icon-packs.settings")
    back()
    rule.onNodeWithTag("setup.page.2").assertIsDisplayed()
    rule.runOnIdle { assertEquals("Wizard shortcuts must not enable or apply mask settings", original, preferences.state.value) }
  }

  @Test fun wideShortcutsSwitchDetailsAndBackReturnsToTheOwningTabAfterResize() {
    var width by mutableStateOf(900.dp)
    store.complete()
    rule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        KeiHomeTheme {
          Box(Modifier.size(width, 700.dp)) { MainNavigation(store) }
        }
      }
    }
    fun selectShortcut(shortcut: MainShortcut) {
      rule.onNode(hasText(context.getString(shortcut.title)) and
        hasAnyAncestor(hasTestTag("adaptive.rail")), useUnmergedTree = true)
        .performTouchInput { click() }
    }

    selectShortcut(MainShortcut.LIBRARY)
    waitForTag("icon-packs.library")
    selectShortcut(MainShortcut.MONET)
    waitForTag("icon-packs.monet")

    rule.runOnIdle { width = 744.dp }
    rule.onNodeWithTag("adaptive.rail").assertIsDisplayed()
    rule.runOnIdle { width = 520.dp }
    rule.onNodeWithTag("adaptive.rail").assertDoesNotExist()
    rule.onNodeWithTag("icon-packs.monet").assertIsDisplayed()
    back()
    rule.onNode(hasText(context.getString(R.string.navigation_icon_packs)) and isSelectable())
      .assertIsSelected()

    rule.runOnIdle { width = 900.dp }
    selectShortcut(MainShortcut.TOOLS)
    waitForTag("desktop.page.tools")
    back()
    rule.onNode(hasText(context.getString(R.string.navigation_desktop)) and isSelectable())
      .assertIsSelected()
  }
}
