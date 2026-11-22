package os.kei.hyper.home.ui.main

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class ApplePackNavigationTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  @Test fun directEntryAndTabSwitchesRetainIndependentBrowserQueries() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val backHost = Os4NavigationTestHost()
    val restoration = StateRestorationTester(rule)
    val owner = object : LifecycleOwner {
      override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
        currentState = Lifecycle.State.CREATED
      }
    }
    var exits = 0
    restoration.setContent {
      val model: HomeViewModel = viewModel()
      backHost.Provide {
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
          KeiHomeTheme {
            ApplePackDestination(model, ApplePackTab.MATCHED, onBack = { exits++ }, onOpenSearch = {})
          }
        }
      }
    }

    fun tab(value: ApplePackTab) = context.getString(value.label).let { label ->
      rule.onNode(isSelectable() and hasAnyAncestor(hasTestTag("icon-packs.apple.tabs")) and
        (hasContentDescription(label) or hasText(label)))
    }
    fun query(value: ApplePackTab) = rule.onNode(hasTestTag("apple.browser.query") and
      hasAnyAncestor(hasTestTag("icon-packs.${value.section}")))
    fun matchedView(label: Int) = rule.onNode(isSelectable() and hasText(context.getString(label)) and
      hasAnyAncestor(hasTestTag("apple.matched.views")))

    tab(ApplePackTab.MATCHED).assertIsSelected()
    matchedView(R.string.apple_filter_all).assertIsSelected()
    val views = rule.onNodeWithTag("apple.matched.views").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertTrue(views.bottom <= query(ApplePackTab.MATCHED).fetchSemanticsNode().boundsInRoot.top)
    matchedView(R.string.apple_filter_system).performClick().assertIsSelected()
    query(ApplePackTab.MATCHED).performTextInput("calendar")
    tab(ApplePackTab.MAPPINGS).performClick().assertIsSelected()
    query(ApplePackTab.MAPPINGS).performTextInput("settings")
    tab(ApplePackTab.GENERATE).performClick().assertIsSelected()
    val bar = rule.onNodeWithTag("icon-packs.apple.tabs").fetchSemanticsNode().boundsInRoot
    val action = rule.onNodeWithTag("apple.action").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertTrue(action.bottom <= bar.top)
    tab(ApplePackTab.MATCHED).performClick().assertIsSelected()
    query(ApplePackTab.MATCHED).assertTextContains("calendar")
    matchedView(R.string.apple_filter_system).assertIsSelected()
    matchedView(R.string.browser_origin_user).performClick().assertIsSelected()
    query(ApplePackTab.MATCHED).assertTextContains("calendar")
    tab(ApplePackTab.MAPPINGS).performClick().assertIsSelected()
    query(ApplePackTab.MAPPINGS).assertTextContains("settings")
    restoration.emulateSavedInstanceStateRestore()
    tab(ApplePackTab.MAPPINGS).assertIsSelected()
    query(ApplePackTab.MAPPINGS).assertTextContains("settings")
    tab(ApplePackTab.MATCHED).performClick()
    matchedView(R.string.browser_origin_user).assertIsSelected()
    tab(ApplePackTab.MAPPINGS).performClick()
    rule.onNodeWithTag("apple.selection-mode").performClick()
    val toolbar = rule.onNodeWithTag("apple.selection.toolbar").assertIsDisplayed()
    val restoredBar = rule.onNodeWithTag("icon-packs.apple.tabs").fetchSemanticsNode().boundsInRoot
    assertTrue(toolbar.fetchSemanticsNode().boundsInRoot.bottom <= restoredBar.top)
    rule.onNodeWithTag("apple.selection.done").assertIsDisplayed().performClick()
    toolbar.assertDoesNotExist()
    rule.onNodeWithContentDescription(context.getString(R.string.pack_back)).performClick()
    rule.runOnIdle { assertEquals(1, exits) }
  }
}
