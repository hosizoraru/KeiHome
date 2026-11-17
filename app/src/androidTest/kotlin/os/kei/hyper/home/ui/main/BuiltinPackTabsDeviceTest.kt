package os.kei.hyper.home.ui.main

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class BuiltinPackTabsDeviceTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<ComponentActivity>()

  private fun model(): HomeViewModel {
    lateinit var model: HomeViewModel
    rule.runOnUiThread {
      model = ViewModelProvider(rule.activity,
        ViewModelProvider.AndroidViewModelFactory.getInstance(
          rule.activity.applicationContext as Application))[HomeViewModel::class.java]
    }
    return model
  }

  private fun tab(label: String) = rule.onNode(
    isSelectable() and (hasText(label) or hasContentDescription(label)) and
      hasAnyAncestor(hasTestTag("icon-packs.builtin.tabs")))

  @Test fun switchingPlatformsStaysOnOnePageAndBackLeavesItOnce() {
    val model = model()
    var back = 0
    var navigation = 0
    rule.setContent { KeiHomeTheme {
      PackDestinationScreen(model, "builtin", onBack = { back++ }, onNavigate = { navigation++ })
    } }
    tab("HyperOS").assertIsSelected()
    rule.onNodeWithText(rule.activity.getString(R.string.pack_hyperos)).assertIsDisplayed()
    rule.onNodeWithTag("icon-packs.coloros.action").assertDoesNotExist()
    tab("ColorOS").performClick().assertIsSelected()
    rule.onNodeWithTag("icon-packs.builtin").assertExists()
    rule.onNodeWithTag("icon-packs.appearance").assertExists()
    rule.onNodeWithTag("pack-guidance.help.coloros").assertIsDisplayed()
    rule.onNodeWithTag("icon-packs.coloros.action").assertIsDisplayed()
    tab("HyperOS").performClick().assertIsSelected()
    rule.onNodeWithTag("pack-guidance.help.coloros").assertDoesNotExist()
    rule.onNodeWithTag("icon-packs.coloros.action").assertDoesNotExist()
    rule.onNodeWithText(rule.activity.getString(R.string.pack_hyperos)).assertIsDisplayed()
    rule.onNodeWithContentDescription(rule.activity.getString(R.string.pack_back)).performClick()
    rule.runOnIdle {
      assertEquals(0, navigation)
      assertEquals(1, back)
    }
  }

  @Test fun existingColorOsLinksOpenTheMatchingTabAndSelectionSurvivesRestoration() {
    val model = model()
    val restoration = StateRestorationTester(rule)
    restoration.setContent { KeiHomeTheme {
      PackDestinationScreen(model, "coloros", onBack = {}, onNavigate = {})
    } }
    tab("ColorOS").assertIsSelected()
    rule.onAllNodesWithText(rule.activity.getString(R.string.pack_builtin)).onFirst().assertExists()
    tab("HyperOS").performClick().assertIsSelected()
    restoration.emulateSavedInstanceStateRestore()
    tab("HyperOS").assertIsSelected()
    rule.onNodeWithTag("icon-packs.coloros.action").assertDoesNotExist()
    tab("ColorOS").performClick().assertIsSelected()
    rule.onNodeWithTag("icon-packs.appearance").assertExists()
  }
}
