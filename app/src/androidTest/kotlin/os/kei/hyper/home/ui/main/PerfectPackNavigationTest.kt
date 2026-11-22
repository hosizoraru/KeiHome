package os.kei.hyper.home.ui.main

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

class PerfectPackNavigationTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  @Test fun tabsRetainResourceSelectionAndBackLeavesTheDestination() {
    val backHost = Os4NavigationTestHost()
    val restoration = StateRestorationTester(rule)
    val context = ApplicationProvider.getApplicationContext<Context>()
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
            PerfectPackDestination(model, PerfectPackTab.GENERATE, onBack = { exits++ })
          }
        }
      }
    }

    fun tab(value: PerfectPackTab) = context.getString(value.label).let { label ->
      rule.onNode(isSelectable() and hasAnyAncestor(hasTestTag("icon-packs.perfect.tabs")) and
        (hasContentDescription(label) or hasText(label)))
    }
    fun assertAboveTabs(tag: String) {
      val tabs = rule.onNodeWithTag("icon-packs.perfect.tabs").fetchSemanticsNode().boundsInRoot
      val button = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
      assertTrue(button.bottom <= tabs.top)
    }

    tab(PerfectPackTab.GENERATE).assertIsSelected()
    assertAboveTabs("icon-packs.perfect.generate")
    val bottomBar = rule.onNodeWithTag("icon-packs.perfect.tabs")
    val barBounds = bottomBar.getUnclippedBoundsInRoot()
    val generate = rule.onNodeWithTag("icon-packs.perfect")
    val generateBounds = generate.getUnclippedBoundsInRoot()
    rule.mainClock.autoAdvance = false
    try {
      tab(PerfectPackTab.RESOURCES).performClick()
      rule.mainClock.advanceTimeBy(80)
      tab(PerfectPackTab.RESOURCES).assertIsSelected()
      assertEquals(barBounds, bottomBar.getUnclippedBoundsInRoot())
      val movingBounds = generate.getUnclippedBoundsInRoot()
      assertTrue(movingBounds.left != generateBounds.left)
      assertTrue(movingBounds.left < generateBounds.right && movingBounds.right > generateBounds.left)
    } finally {
      rule.mainClock.autoAdvance = true
    }
    tab(PerfectPackTab.RESOURCES).assertIsSelected()
    rule.onNodeWithTag("perfect.resources.selection-mode").performClick()
    assertAboveTabs("perfect.resources.toolbar")
    rule.onNodeWithTag("perfect.resources.all").performClick()
    rule.onNodeWithTag("perfect.resources.download").assertIsEnabled()
    tab(PerfectPackTab.PREVIEW).performClick().assertIsSelected()
    rule.onNodeWithText(context.getString(R.string.perfect_icons_preview_note)).assertExists()
    restoration.emulateSavedInstanceStateRestore()
    tab(PerfectPackTab.PREVIEW).assertIsSelected()
    tab(PerfectPackTab.RESOURCES).performClick().assertIsSelected()
    assertAboveTabs("perfect.resources.toolbar")
    rule.onNodeWithTag("perfect.resources.download").assertIsEnabled()
    rule.runOnIdle { backHost.completeBack() }
    rule.onNodeWithTag("perfect.resources.toolbar").assertDoesNotExist()
    rule.runOnIdle { assertEquals(0, exits) }
    rule.onNodeWithContentDescription(context.getString(R.string.pack_back)).performClick()
    rule.runOnIdle { assertEquals(1, exits) }
  }
}
