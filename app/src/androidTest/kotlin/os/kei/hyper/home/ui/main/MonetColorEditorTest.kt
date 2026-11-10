package os.kei.hyper.home.ui.main

import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
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
    compose.onNodeWithText(context.getString(R.string.monet_seed_source)).performClick()
    compose.onAllNodesWithText(context.getString(R.string.monet_source_basic)).onLast().performClick()
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
    compose.onNodeWithText("#087F8C").assertExists()
    compose.waitUntil(5_000) {
      !compose.onNodeWithTag("monet.page.refresh").fetchSemanticsNode()
        .config.contains(SemanticsProperties.Disabled)
    }
    compose.onNodeWithTag("monet.page.refresh").performClick()
    compose.onNodeWithText("#087F8C").assertExists()
    assertEquals(original, Settings.Secure.getString(context.contentResolver, "keihome_monet_customization"))
  }

  @Test fun incompleteHexCannotCommitAndCancelPreservesTheSeed() {
    val context = compose.activity
    openMonet()
    compose.onNodeWithText(context.getString(R.string.monet_seed_source)).performClick()
    compose.onAllNodesWithText(context.getString(R.string.monet_source_basic)).onLast().performClick()
    compose.onNodeWithTag("monet.custom.seed").performScrollTo().performClick()
    compose.onNodeWithTag("monet.editor.hex").performTextReplacement("ABC")
    compose.onNodeWithTag("monet.editor.confirm").assertIsNotEnabled()
    compose.onNodeWithText(context.getString(R.string.monet_custom_cancel)).performClick()
    compose.onNodeWithTag("monet.custom.seed").performClick()
    // A newly opened editor initializes from the unchanged complete seed, never the cancelled text.
    compose.onNodeWithTag("monet.editor.hex").assertTextContains("#", substring = true)
  }

  private fun openMonet() {
    val context = compose.activity
    compose.onNode(hasText(context.getString(R.string.navigation_status)) and isSelectable()).performClick()
    compose.onNodeWithContentDescription(context.getString(R.string.monet_open_details))
      .performScrollTo().performClick()
  }
}
