package os.kei.hyper.home.ui.main

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.util.Date
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import os.kei.hyper.home.ui.perfect.PerfectPackError
import os.kei.hyper.home.ui.perfect.PerfectPackState
import os.kei.hyper.home.ui.perfect.PerfectResourceContent
import os.kei.hyper.home.ui.perfect.PerfectSyncIssue

class PerfectResourceNoticeUiTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val noticeTag = "perfect.resources.notice"

  @Test fun noticesAppearForEachProblemAndLeaveNoEmptyPanelAfterRecoveryOrCooldownExpiry() {
    var state by mutableStateOf(PerfectPackState())
    setContent { state }
    compose.onAllNodesWithTag(noticeTag).assertCountEquals(0)
    val future = System.currentTimeMillis() + 60 * 60 * 1000L
    for (problem in listOf(
      PerfectPackState(error = PerfectPackError.NETWORK),
      PerfectPackState(syncIssue = PerfectSyncIssue.CORRUPT),
      PerfectPackState(retryAtMillis = future),
    )) {
      compose.runOnIdle { state = problem }
      compose.onAllNodesWithTag(noticeTag).assertCountEquals(1)
      compose.runOnIdle { state = PerfectPackState() }
      compose.onAllNodesWithTag(noticeTag).assertCountEquals(0)
    }
    compose.runOnIdle { state = PerfectPackState(retryAtMillis = System.currentTimeMillis() - 1) }
    compose.onAllNodesWithTag(noticeTag).assertCountEquals(0)
  }

  @Test fun cooldownErrorAppearsOnceWithTheLocalizedRetryTimeInTheSameCard() {
    val retry = System.currentTimeMillis() + 60 * 60 * 1000L
    setContent { PerfectPackState(error = PerfectPackError.COOLDOWN, retryAtMillis = retry) }
    val error = context.getString(R.string.perfect_icons_error_cooldown)
    val detail = cooldownDetail(retry)
    compose.onAllNodesWithTag(noticeTag).assertCountEquals(1)
    assertNoticeMessage(error)
    assertNoticeMessage(detail)
  }

  @Test fun longFailureCooldownAndRepairGuidanceWrapWithoutTruncationAtLargeTextSize() {
    val retry = System.currentTimeMillis() + 60 * 60 * 1000L
    setContent(fontScale = 1.6f) {
      PerfectPackState(error = PerfectPackError.NETWORK, retryAtMillis = retry,
        syncIssue = PerfectSyncIssue.CORRUPT)
    }
    compose.onAllNodesWithTag(noticeTag).assertCountEquals(1)
    val messages = listOf(context.getString(R.string.perfect_icons_error_network),
      cooldownDetail(retry), context.getString(R.string.perfect_resource_corrupt))
    for (message in messages) {
      assertNoticeMessage(message)
      val layouts = mutableListOf<TextLayoutResult>()
      compose.onNodeWithText(message, useUnmergedTree = true)
        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayouts ->
          assertTrue(getLayouts(layouts))
        }
      assertTrue("Message must expose its text layout", layouts.isNotEmpty())
      layouts.forEach {
        assertFalse("Notice must wrap instead of overflowing or truncating", it.hasVisualOverflow)
        if (message == messages.first()) assertTrue("Long error must wrap", it.lineCount > 1)
      }
    }
  }

  private fun assertNoticeMessage(text: String) {
    compose.onAllNodesWithText(text, useUnmergedTree = true).assertCountEquals(1)
    compose.onNodeWithText(text, useUnmergedTree = true)
      .assert(hasAnyAncestor(hasTestTag(noticeTag))).performScrollTo().assertIsDisplayed()
  }

  private fun cooldownDetail(retry: Long) = context.getString(R.string.perfect_icons_cooldown_detail,
    DateFormat.getTimeFormat(context).format(Date(retry)))

  private fun setContent(fontScale: Float? = null, state: () -> PerfectPackState) {
    val host = Os4NavigationTestHost()
    compose.setContent {
      host.Provide {
        KeiHomeTheme {
          val density = LocalDensity.current
          CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
            Box(Modifier.widthIn(max = 360.dp).fillMaxSize()) {
              PerfectResourceContent(state(), busy = false, onBack = {},
                onCheck = { error("Notice rendering must not start a network check") },
                onDownload = { error("Notice rendering must not start a download") },
                onRemove = {}, onRepair = {}, onDelete = {}, onClearCache = {})
            }
          }
        }
      }
    }
  }
}
