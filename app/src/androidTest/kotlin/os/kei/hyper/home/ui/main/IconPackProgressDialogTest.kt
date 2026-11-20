package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class IconPackProgressDialogTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  @Test fun compactDialogIsCenteredAndCanCancelPreparation() {
    verifyDialog(Density(3f), waiting = false)
  }

  @Test fun wideDialogIsCenteredAndCanContinueImportInBackground() {
    verifyDialog(Density(1f), waiting = true)
  }

  private fun verifyDialog(density: Density, waiting: Boolean) {
    var actions = 0
    lateinit var countLabel: String
    rule.setContent {
      countLabel = stringResource(R.string.coloros_generation_count, 25, 100)
      CompositionLocalProvider(LocalDensity provides density) {
        KeiHomeTheme {
          Box(Modifier.fillMaxSize().testTag("window")) {
            IconPackProgressDialog(IconPackOperationProgress(IconPackOperation.SYSTEM_IMPORT,
              if (waiting) IconPackStep.SYSTEM_IMPORT else IconPackStep.MONET,
              if (waiting) 94 else 81, done = if (waiting) 0 else 25,
              total = if (waiting) 0 else 100, cancellable = !waiting),
              onCancel = { actions++ }, onBackground = if (waiting) ({ actions++ }) else null)
          }
        }
      }
    }
    val window = rule.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
    val dialog = rule.onNodeWithTag("icon-pack.progress").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertTrue(abs(window.center.x - dialog.center.x) < window.width * .04f)
    assertTrue(abs(window.center.y - dialog.center.y) < window.height * .09f)
    assertTrue(dialog.width < window.width)
    rule.onNodeWithTag("icon-pack.progress.percent").assertIsDisplayed()
    rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
      ProgressBarRangeInfo(if (waiting) .94f else .81f, 0f..1f))).assertIsDisplayed()
    if (waiting) rule.onNodeWithTag("icon-pack.progress.count").assertDoesNotExist()
    else rule.onNodeWithTag("icon-pack.progress.count").assertIsDisplayed().assertTextEquals(countLabel)
    val action = if (waiting) "background" else "cancel"
    rule.onNodeWithTag("icon-pack.progress.$action").assertIsDisplayed().performClick()
    rule.runOnIdle { assertEquals(1, actions) }
  }
}
