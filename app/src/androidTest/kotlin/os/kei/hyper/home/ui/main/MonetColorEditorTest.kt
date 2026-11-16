package os.kei.hyper.home.ui.main

import android.provider.Settings
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.MainActivity
import os.kei.hyper.home.R

class MonetColorEditorTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test fun exactHexDraftSurvivesRefreshAndRecreationWithoutApplying() {
    val context = compose.activity
    val original = Settings.Secure.getString(context.contentResolver, "keihome_monet_customization")
    openMonet()
    selectBasicColor()
    compose.onNodeWithTag("monet.custom.seed").performScrollTo().performClick()
    val field = compose.onNodeWithTag("monet.editor.hex")
    field.performClick().performTextReplacement("087F8C")
    field.assertTextContains("087F8C")
    compose.onNodeWithTag("monet.editor.confirm").assertIsDisplayed().performClick()
    compose.waitUntil(5_000) {
      compose.onAllNodesWithText("#087F8C").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("#087F8C").assertExists()
    compose.activityRule.scenario.recreate()
    compose.waitUntil(10_000) {
      compose.onAllNodesWithText("#087F8C").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("#087F8C").assertExists()
    closeSource()
    compose.waitUntil(5_000) {
      !compose.onNodeWithTag("monet.page.refresh").fetchSemanticsNode()
        .config.contains(SemanticsProperties.Disabled)
    }
    compose.onNodeWithTag("monet.page.refresh").performClick()
    compose.onNodeWithTag("monet.open.SOURCE").performScrollTo().performClick()
    compose.onNodeWithText("#087F8C").assertExists()
    assertEquals(original, Settings.Secure.getString(context.contentResolver, "keihome_monet_customization"))
  }

  @Test fun incompleteHexCannotCommitAndCancelPreservesTheSeed() {
    val context = compose.activity
    openMonet()
    selectBasicColor()
    compose.onNodeWithTag("monet.custom.seed").performScrollTo().performClick()
    val field = compose.onNodeWithTag("monet.editor.hex")
    field.performClick().performTextReplacement("ABC")
    field.assertTextContains("ABC")
    compose.onNodeWithTag("monet.editor.confirm").assertIsNotEnabled()
    compose.onNodeWithText(context.getString(R.string.monet_custom_cancel)).performClick()
    compose.onNodeWithTag("monet.custom.seed").performClick()
    // A newly opened editor initializes from the unchanged complete seed, never the cancelled text.
    compose.onNodeWithTag("monet.editor.hex").assertTextContains("#", substring = true)
  }

  @Test fun overviewKeepsDetailsSeparateAndRetainsDraftAcrossCategories() {
    val context = compose.activity
    val original = Settings.Secure.getString(context.contentResolver, "keihome_monet_customization")
    openMonet()
    compose.onNodeWithTag("monet.style.TONAL_SPOT").assertDoesNotExist()
    selectBasicColor()
    compose.onNodeWithTag("monet.custom.seed").performScrollTo().performClick()
    compose.onNodeWithTag("monet.editor.hex").performClick().performTextReplacement("087F8C")
    compose.onNodeWithTag("monet.editor.confirm").performClick()
    closeSource()
    compose.onNodeWithTag("monet.open.DETAILS").performScrollTo().performClick()
    compose.onNodeWithTag("monet.custom.seed").assertDoesNotExist()
    compose.onNodeWithContentDescription(context.getString(R.string.pack_back)).performClick()
    compose.onNodeWithTag("monet.open.SOURCE").performScrollTo().performClick()
    compose.onNodeWithText("#087F8C").assertExists()
    assertEquals(original, Settings.Secure.getString(context.contentResolver, "keihome_monet_customization"))
  }

  @Test fun helpOpensDismissesAndReopensWithoutChangingSettings() {
    val context = compose.activity
    val original = Settings.System.getString(context.contentResolver, "key_monochrome")
    openMonet()
    compose.onAllNodesWithTag("monet.apply").assertCountEquals(1)
    repeat(2) {
      compose.onNodeWithTag("monet.help.open").performClick()
      compose.onNodeWithText(context.getString(R.string.monet_help_apply_note)).assertExists()
      compose.onNodeWithTag("monet.help").performSemanticsAction(SemanticsActions.Dismiss) { it() }
      compose.waitUntil(5_000) {
        compose.onAllNodesWithTag("monet.help").fetchSemanticsNodes().isEmpty()
      }
    }
    assertEquals(original, Settings.System.getString(context.contentResolver, "key_monochrome"))
  }

  private fun closeSource() {
    compose.onNodeWithTag("monet.panel.SOURCE").performSemanticsAction(SemanticsActions.Dismiss) { it() }
    compose.waitUntil(5_000) {
      compose.onAllNodesWithTag("monet.panel.SOURCE").fetchSemanticsNodes().isEmpty()
    }
  }

  private fun selectBasicColor() {
    compose.onNodeWithTag("monet.open.SOURCE").performScrollTo().performClick()
    compose.onNode(hasText(compose.activity.getString(R.string.monet_seed_source)) and
      SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
      .performScrollTo().performClick()
    val options = compose.onAllNodesWithText(compose.activity.getString(R.string.monet_source_basic))
    compose.waitUntil(5_000) {
      options.fetchSemanticsNodes().indices.any { options[it].isDisplayed() }
    }
    val visible = options.fetchSemanticsNodes().indices.last { options[it].isDisplayed() }
    options[visible].performClick()
  }

  private fun openMonet() {
    val context = compose.activity
    compose.onNode(hasText(context.getString(R.string.navigation_icon_packs)) and isSelectable()).performClick()
    compose.onNodeWithText(context.getString(R.string.navigation_monet)).performScrollTo().performClick()
    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag("monet.open.SOURCE").fetchSemanticsNodes().isNotEmpty()
    }
  }
}
