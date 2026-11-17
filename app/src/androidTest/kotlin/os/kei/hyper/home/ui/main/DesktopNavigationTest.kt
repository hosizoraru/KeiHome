package os.kei.hyper.home.ui.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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

class DesktopNavigationTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val id = "desktop_nav_${UUID.randomUUID()}"
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

  @Test fun categoriesShowOnlyTheirOwnControlsAndReturnToDesktop() {
    store.complete()
    rule.setContent { KeiHomeTheme { MainNavigation(store) } }
    val desktopTab = hasText(context.getString(R.string.navigation_desktop)) and isSelectable()
    rule.onNode(desktopTab).performClick()
    waitForTag("desktop.settings")
    rule.onNodeWithTag("dock-glass.switch").assertDoesNotExist()
    rule.onNodeWithTag("launcher-glass.style").assertDoesNotExist()
    rule.onNodeWithTag("theme-effects.switch").assertDoesNotExist()
    for (section in listOf("dock", "appearance", "icons")) {
      rule.onNodeWithTag("desktop.open.$section").performScrollTo().performClick()
      waitForTag("desktop.page.$section")
      rule.onNode(
        hasTestTag("status.overview.symbol") and
          hasAnyAncestor(hasTestTag("desktop.status.$section")),
        useUnmergedTree = true,
      ).assertWidthIsEqualTo(72.dp).assertHeightIsEqualTo(72.dp)
      when (section) {
        "dock" -> {
          rule.onNodeWithTag("desktop.status.dock").assertExists()
          rule.onNodeWithTag("dock-glass.switch").assertExists()
          rule.onNodeWithTag("launcher-glass.style").assertExists()
          rule.onNodeWithTag("search-glass.style").assertDoesNotExist()
        }
        "appearance" -> {
          rule.onNodeWithTag("desktop.status.appearance").assertExists()
          rule.onNodeWithTag("search-glass.style").assertExists()
          rule.onNodeWithTag("launcher-glass.style").assertDoesNotExist()
          rule.onNodeWithText(context.getString(R.string.launcher_labels_title)).assertExists()
          rule.onNodeWithTag("dock-glass.switch").assertDoesNotExist()
        }
        else -> {
          rule.onNodeWithTag("theme-effects.switch").assertExists()
          rule.onNodeWithTag("splash-appearance.switch").assertExists()
          rule.onNodeWithTag("splash-icons.switch").assertExists()
          rule.onNodeWithTag("splash-icons.help.open").assertExists()
          rule.onNodeWithTag("dock-glass.switch").assertDoesNotExist()
        }
      }
      back()
      waitForTag("desktop.settings")
      rule.onNode(desktopTab).assertIsSelected()
    }
  }

  @Test fun scopePageContainsAuthorizationInformationWithoutFeatureControls() {
    store.complete()
    rule.setContent { KeiHomeTheme { MainNavigation(store) } }
    rule.onNode(hasText(context.getString(R.string.navigation_settings)) and isSelectable()).performClick()
    rule.onNodeWithText(context.getString(R.string.settings_hook_section)).performScrollTo().performClick()
    waitForTag("scope.page.summary")
    rule.onNodeWithTag("theme-effects.switch").assertDoesNotExist()
    rule.onNodeWithTag("splash-appearance.switch").assertDoesNotExist()
    rule.onNodeWithText(context.getString(R.string.scope_request)).assertDoesNotExist()
    rule.onNodeWithTag("scope.page.refresh").assertExists()
    back()
    rule.onNode(hasText(context.getString(R.string.navigation_settings)) and isSelectable()).assertIsSelected()
  }

  @Test fun wizardCanOpenACategoryAndReturnToTheSameStep() {
    store.visit(3)
    rule.setContent { KeiHomeTheme { MainNavigation(store) } }
    waitForTag("setup.page.3")
    rule.onNodeWithText(context.getString(R.string.setup_open_desktop)).performScrollTo().performClick()
    waitForTag("desktop.settings")
    rule.onNodeWithTag("desktop.open.dock").performScrollTo().performClick()
    waitForTag("desktop.page.dock")
    back()
    waitForTag("desktop.settings")
    back()
    rule.onNodeWithTag("setup.page.3").assertIsDisplayed()
  }
}
