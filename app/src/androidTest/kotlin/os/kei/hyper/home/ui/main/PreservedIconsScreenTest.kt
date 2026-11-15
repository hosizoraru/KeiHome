package os.kei.hyper.home.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import os.kei.hyper.home.R
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class PreservedIconsScreenTest {
  private val navigation = Os4NavigationTestHost()
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<ComponentActivity>()

  private val first = PreservedIconApp("os.test.first", "First app", 1, 1, "", 0)
  private val second = PreservedIconApp("os.test.second", "Second app", 1, 1, "", 0)
  private val removed = "os.test.uninstalled"

  @Test fun savedEntriesRemainRemovableAndSearchSupportsMultipleSelections() {
    setContent {
      var selected by remember { mutableStateOf(setOf(first.packageName, removed)) }
      KeiHomeTheme {
        PreservedIconsPage(listOf(first, second), selected, loading = false, failed = false,
          onToggle = { selected = if (it in selected) selected - it else selected + it },
          onRetry = {}, onBack = {})
      }
    }
    row(first.packageName).assertIsOn()
    scrollTo(removed)
    row(removed).assertIsOn().performClick().assertDoesNotExist()
    rule.onNodeWithTag("preserved-icons.list").performScrollToIndex(0)
    selectMode(all = true)
    rule.onNodeWithTag("preserved-icons.search").performTextInput(second.packageName)
    scrollTo(second.packageName)
    row(second.packageName).assertIsOff().performClick().assertIsOn()
    rule.onNodeWithTag("preserved-icons.list").performScrollToIndex(0)
    rule.onNodeWithTag("preserved-icons.search").performTextClearance()
    rule.onNodeWithTag("preserved-icons.search").performTextInput("First app")
    scrollTo(first.packageName)
    row(first.packageName).assertIsOn().performClick().assertIsOff()
    rule.onNodeWithTag("preserved-icons.list").performScrollToIndex(0)
    rule.onNodeWithTag("preserved-icons.search").performTextClearance()
    selectMode(all = false)
    scrollTo(second.packageName)
    row(second.packageName).assertIsOn()
    row(first.packageName).assertDoesNotExist()
  }

  @Test fun failedInventoryStillAllowsRemovingSavedPackagesAndRetrying() {
    setContent {
      var selected by remember { mutableStateOf(setOf(removed)) }
      var failed by remember { mutableStateOf(true) }
      KeiHomeTheme {
        PreservedIconsPage(emptyList(), selected, loading = false, failed = failed,
          onToggle = { selected = selected - it }, onRetry = { failed = false }, onBack = {})
      }
    }
    scrollTo(removed)
    row(removed).assertIsOn().performClick().assertDoesNotExist()
    rule.onNodeWithTag("preserved-icons.list").performScrollToIndex(0)
    rule.onNodeWithTag("preserved-icons.status").assertIsDisplayed()
    rule.onNodeWithTag("preserved-icons.list").performScrollToNode(hasTestTag("preserved-icons.retry"))
    rule.onNodeWithTag("preserved-icons.retry").performClick()
    rule.onNodeWithTag("preserved-icons.empty").assertIsDisplayed()
  }

  @Test fun viewTabsStayAboveTheListAfterScrollingAndKeepTheSavedCountLive() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val apps = List(60) { PreservedIconApp("os.test.app.$it", "App ${it.toString().padStart(2, '0')}", 1, 1, "", 0) }
    setContent {
      var selected by remember { mutableStateOf(setOf(apps.first().packageName)) }
      KeiHomeTheme {
        PreservedIconsPage(apps, selected, loading = false, failed = false,
          onToggle = { selected = if (it in selected) selected - it else selected + it },
          onRetry = {}, onBack = {})
      }
    }
    val tabs = rule.onNodeWithTag("preserved-icons.views")
    val list = rule.onNodeWithTag("preserved-icons.list")
    assertTrue(tabs.fetchSemanticsNode().boundsInRoot.bottom <=
      rule.onNodeWithTag("preserved-icons.search").fetchSemanticsNode().boundsInRoot.top)
    selectMode(all = true)
    list.performScrollToIndex(35)
    rule.onNodeWithText(context.getString(R.string.preserved_icons_all)).assertIsDisplayed().assertIsSelected()
    assertTrue(tabs.fetchSemanticsNode().boundsInRoot.top >= 0f)
    selectMode(all = false)
    assertTrue(tabs.fetchSemanticsNode().boundsInRoot.bottom <=
      rule.onNodeWithTag("preserved-icons.search").fetchSemanticsNode().boundsInRoot.top)
    scrollTo(apps.first().packageName)
    row(apps.first().packageName).performClick()
    rule.onNodeWithText(context.getString(R.string.preserved_icons_saved, 0)).assertIsSelected()
    rule.onNodeWithTag("preserved-icons.empty").assertIsDisplayed()
  }

  @Test fun backgroundInventoryLoadingKeepsNativeRefreshHiddenAndTheListScrollable() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val apps = List(100) { first.copy(packageName = "example.app$it", label = "App ${it.toString().padStart(3, '0')}") }
    var requests = 0
    setContent {
      KeiHomeTheme {
        PreservedIconsPage(apps, apps.mapTo(linkedSetOf()) { it.packageName },
          loading = true, failed = false, onToggle = {}, onRetry = { requests++ }, onBack = {})
      }
    }
    rule.onNodeWithText(context.getString(R.string.refresh_in_progress)).assertDoesNotExist()
    val refresh = rule.onNodeWithTag("preserved-icons.pull-to-refresh")
      .fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
    rule.runOnIdle { assertFalse(refresh.action()) }
    val list = rule.onNodeWithTag("preserved-icons.list")
    val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    list.performTouchInput { swipeUp(durationMillis = 700) }
    val after = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    assertTrue(after > before)
    assertEquals(0, requests)
  }

  @Test fun explicitManualRefreshShowsNativeIndicatorAndRetainsSavedRows() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    setContent {
      KeiHomeTheme {
        PreservedIconsPage(listOf(first, second), setOf(first.packageName, second.packageName),
          loading = true, failed = false, refreshing = true,
          onToggle = {}, onRetry = {}, onBack = {})
      }
    }
    rule.onNodeWithText(context.getString(R.string.refresh_in_progress)).assertExists()
    row(first.packageName).assertExists()
    row(second.packageName).assertExists()
  }

  private fun row(pkg: String) = rule.onNodeWithTag("preserved-icons.app:$pkg")

  private fun scrollTo(pkg: String) {
    rule.onNodeWithTag("preserved-icons.list")
      .performScrollToNode(hasTestTag("preserved-icons.app:$pkg"))
  }
  private fun setContent(content: @Composable () -> Unit) {
    rule.setContent { navigation.Provide(content) }
  }

  private fun selectMode(all: Boolean) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val label = if (all) context.getString(R.string.preserved_icons_all)
      else context.getString(R.string.preserved_icons_saved, 1)
    val item = hasText(label) and hasClickAction()
    rule.waitUntil(5_000) { rule.onAllNodes(item).fetchSemanticsNodes().size == 1 }
    rule.onNode(item).performClick()
    rule.onNode(item).assertIsSelected()
  }

}
