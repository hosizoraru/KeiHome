package os.kei.hyper.home.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.KeiHomeTheme

/** Read-only device checks use existing saved choices without replacing or applying them. */
class CustomIconsNavigationTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
  private lateinit var model: HomeViewModel
  private val popupNavigation = Os4NavigationTestHost()
  private fun text(id: Int) = rule.activity.getString(id)
  private fun view(id: Int) = rule.onNode(
    hasAnyAncestor(hasTestTag("custom-icons.views")) and hasText(text(id).substringBefore('(').trim(), substring = true))

  @Before fun open() {
    model = ViewModelProvider(rule.activity)[HomeViewModel::class.java]
    rule.setContent { KeiHomeTheme { popupNavigation.Provide { CustomIconsScreen(model, {}, {}) } } }
    rule.waitUntil(30_000) {
      val state = model.customIcons.state.value
      state.ready && !state.loading && state.apps.isNotEmpty()
    }
    // A real first visit is checked separately. This also supports an untouched test device.
    rule.onAllNodesWithText(text(R.string.pack_guidance_done)).fetchSemanticsNodes()
      .takeIf { it.isNotEmpty() }?.let { rule.onNodeWithText(text(R.string.pack_guidance_done)).performClick() }
  }

  @Test fun searchFollowsBothSwitchersAndUnconfiguredAppsCanBeSearched() {
    val views = rule.onNodeWithTag("custom-icons.views").fetchSemanticsNode().boundsInRoot
    val origins = rule.onNodeWithTag("custom-icons.origins").fetchSemanticsNode().boundsInRoot
    val search = rule.onNodeWithTag("custom-icons.search").fetchSemanticsNode().boundsInRoot
    assertTrue(views.bottom <= origins.top)
    assertTrue(origins.bottom <= search.top)
    view(R.string.custom_icons_unset).performTouchInput { click() }
    rule.onNodeWithTag("custom-icons.search").performTextInput("a.package.that.is.not.installed")
    rule.onNodeWithText(text(R.string.custom_icons_no_results)).assertIsDisplayed()
    rule.onNodeWithTag("custom-icons.search").performTextClearance()
    rule.onNode(hasAnyAncestor(hasTestTag("custom-icons.origins")) and
      hasText(text(R.string.browser_badge_system) + " (", substring = true)).performTouchInput { click() }
    rule.onNodeWithTag("custom-icons.search").assertIsDisplayed()
  }

  @Test fun savedChoiceOpensComparisonAndBackDismissesWithoutEditing() {
    val item = model.customIcons.state.value.selections.firstOrNull()
    assumeTrue("Requires one existing saved choice", item != null)
    rule.onNodeWithTag("custom-icons.list").performScrollToNode(hasTestTag("custom-icons.saved.${item!!.packageName}"))
    rule.onNodeWithTag("custom-icons.saved.${item.packageName}").performClick()
    rule.onNodeWithText(text(R.string.custom_icons_original)).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.custom_icons_current)).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.custom_icons_edit)).assertIsDisplayed()
    rule.runOnIdle { popupNavigation.completeBack() }
    rule.onNodeWithTag("custom-icons.views").assertIsDisplayed()
    rule.onNodeWithText(text(R.string.custom_icons_choose_pack)).assertDoesNotExist()
  }

  @Test fun editingStartsAtSourceAndBackReturnsDirectlyToRoot() {
    val item = model.customIcons.state.value.selections.firstOrNull()
    assumeTrue("Requires one existing saved choice", item != null)
    rule.onNodeWithTag("custom-icons.search").performTextInput(item!!.packageName)
    rule.onNodeWithTag("custom-icons.list").performScrollToNode(hasTestTag("custom-icons.saved.${item!!.packageName}"))
    rule.onNodeWithTag("custom-icons.saved.${item.packageName}").performClick()
    rule.onNodeWithTag("custom-icons.search").assertIsNotFocused()
    rule.onNodeWithText(text(R.string.custom_icons_edit)).performClick()
    assertTrue(rule.onAllNodesWithText(text(R.string.custom_icons_choose_pack))
      .fetchSemanticsNodes().indices.any {
        rule.onAllNodesWithText(text(R.string.custom_icons_choose_pack))[it].isDisplayed()
      })
    rule.onNodeWithContentDescription(text(R.string.pack_back)).performClick()
    rule.onNodeWithTag("custom-icons.views").assertIsDisplayed()
    rule.onNodeWithTag("custom-icons.search").assertTextContains(item.packageName).assertIsNotFocused()
    rule.onNodeWithText(text(R.string.custom_icons_choose_app)).assertDoesNotExist()
    assertTrue(model.customIcons.state.value.selections.any { it.token == item.token })
  }
}
