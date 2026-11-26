package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.CenteredLinkAction
import os.kei.hyper.home.ui.components.Os4DisclosureContent
import os.kei.hyper.home.ui.components.Os4DisclosureHeader
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import os.kei.hyper.home.ui.perfect.PerfectPackState
import os.kei.hyper.home.ui.perfect.PerfectSyncBadges
import os.kei.hyper.home.ui.perfect.PerfectSyncIssue

class UiActionPolishDeviceTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  @Test fun compactActionRetainsMeaningAndCannotRunWhileBusy() {
    var busy by mutableStateOf(false)
    var calls = 0
    var height = 0
    var density = 1f
    compose.setContent {
      KeiHomeTheme {
        density = LocalDensity.current.density
        PageActionFab("更新", actionIcon(PageActionIcon.UPDATE), { calls++ },
          description = "更新已导入的图标", busy = busy, onHeightChanged = { height = it })
      }
    }
    val action = compose.onNodeWithContentDescription("更新已导入的图标")
    val initial = action.fetchSemanticsNode().boundsInRoot
    assertTrue("Short captions must not retain the old 164dp width", initial.width / density < 164f)
    assertTrue(initial.height / density >= 56f)
    action.performClick()
    compose.runOnIdle { assertEquals(1, calls); busy = true }
    action.assertIsNotEnabled()
    action.performSemanticsAction(SemanticsActions.OnClick) { it() }
    compose.runOnIdle {
      assertEquals(1, calls)
      assertEquals(initial.height.toInt(), height)
    }
  }

  @Test fun longActionFitsNarrowWidthAtLargeText() {
    var density = 1f
    compose.setContent {
      KeiHomeTheme {
        density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
          Box(Modifier.width(150.dp)) {
            PageActionFab("Download the latest source resources", actionIcon(PageActionIcon.FETCH), {},
              description = "Force download the latest source resources after confirmation")
          }
        }
      }
    }
    val bounds = compose.onNodeWithContentDescription(
      "Force download the latest source resources after confirmation",
    ).fetchSemanticsNode().boundsInRoot
    assertTrue(bounds.width / density <= 150.1f)
    assertTrue(bounds.height / density >= 56f)
  }

  @Test fun disclosureAffordancesShareTrailingPositionAndSingleAction() {
    var firstExpanded by mutableStateOf(false)
    compose.setContent {
      KeiHomeTheme {
        Column(Modifier.width(300.dp)) {
          Os4DisclosureHeader("Runtime", "A summary that wraps to a second line on this narrow card",
            firstExpanded, "Show runtime", if (firstExpanded) "Expanded" else "Collapsed",
            { firstExpanded = !firstExpanded })
          Os4DisclosureContent(firstExpanded) { Text("Runtime details") }
          Os4DisclosureHeader("Monet", "Palette", false, "Show palette", "Collapsed", {})
        }
      }
    }
    val indicators = compose.onAllNodesWithTag("disclosure.indicator", useUnmergedTree = true)
      .fetchSemanticsNodes().map { it.boundsInRoot }
    assertEquals(2, indicators.size)
    assertEquals(indicators[0].right, indicators[1].right, 0.1f)
    assertEquals(indicators[0].width, indicators[1].width, 0.1f)
    compose.onAllNodes(hasClickAction()).assertCountEquals(2)
    compose.onNodeWithText("Runtime").performClick()
    compose.onNodeWithText("Runtime details").assertExists()
    compose.onNode(SemanticsMatcher.expectValue(
      androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Expanded",
    )).assertExists()
    compose.onNodeWithText("Runtime").performClick()
    compose.onNodeWithText("Runtime details").assertDoesNotExist()
  }

  @Test fun navigationContentIsCenteredWithOneAccessibleAction() {
    var calls = 0
    compose.setContent {
      KeiHomeTheme {
        Box(Modifier.width(300.dp)) {
          CenteredLinkAction("查看", { calls++ }, Modifier.fillMaxWidth(),
            icon = actionIcon(PageActionIcon.OPEN_EXTERNAL),
            description = "在系统主题中查看图标包")
        }
      }
    }
    val action = compose.onNodeWithContentDescription("在系统主题中查看图标包")
    val outer = action.fetchSemanticsNode().boundsInRoot
    val group = compose.onNodeWithTag("link.content", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    assertEquals(outer.center.x, group.center.x, 1f)
    compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    action.performClick()
    compose.runOnIdle { assertEquals(1, calls) }
  }

  @Test fun syncIssuePillsKeepTheLastSuccessAndDistinguishFirstUse() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var state by mutableStateOf(PerfectPackState(lastSuccessfulSyncAtMillis = 1_763_000_000_000))
    var density = 1f
    compose.setContent {
      KeiHomeTheme {
        density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
          Column(Modifier.width(260.dp)) {
            PerfectSyncBadges(state, MiuixTheme.colorScheme.primary)
          }
        }
      }
    }
    compose.onNodeWithTag("perfect.sync.success").assertExists()
    compose.onNodeWithTag("perfect.sync.issue").assertDoesNotExist()
    for ((issue, label) in listOf(
      PerfectSyncIssue.FAILED to R.string.sync_badge_failed,
      PerfectSyncIssue.INTERRUPTED to R.string.sync_badge_interrupted,
      PerfectSyncIssue.CORRUPT to R.string.sync_badge_corrupt,
    )) {
      compose.runOnIdle { state = state.copy(syncIssue = issue, syncIssueAtMillis = 1_763_000_100_000) }
      compose.onNodeWithTag("perfect.sync.success").assertExists()
      val pill = compose.onNodeWithTag("perfect.sync.issue")
      pill.assertContentDescriptionContains(context.getString(label), substring = true)
      assertTrue(pill.fetchSemanticsNode().boundsInRoot.width / density <= 260.1f)
    }
    compose.runOnIdle { state = PerfectPackState() }
    compose.onNodeWithTag("perfect.sync.success").assertDoesNotExist()
    compose.onNodeWithTag("perfect.sync.issue").assertDoesNotExist()
  }

  @Test fun pairedActionsStayCenteredOnOneLineWhenTheyFit() {
    compose.setContent {
      KeiHomeTheme {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
          FlowRow(Modifier.width(260.dp).testTag("pair"),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            PackActionButton("更新", {}, Modifier.testTag("pair.update"), operation = PageActionIcon.UPDATE)
            PackActionButton("应用", {}, Modifier.testTag("pair.apply"), operation = PageActionIcon.APPLY)
          }
        }
      }
    }
    val parent = compose.onNodeWithTag("pair").fetchSemanticsNode().boundsInRoot
    val update = compose.onNodeWithTag("pair.update").fetchSemanticsNode().boundsInRoot
    val apply = compose.onNodeWithTag("pair.apply").fetchSemanticsNode().boundsInRoot
    assertEquals(update.center.y, apply.center.y, 1f)
    assertEquals(parent.center.x, (update.left + apply.right) / 2f, 1f)
    assertTrue(update.right < apply.left)
  }
}
