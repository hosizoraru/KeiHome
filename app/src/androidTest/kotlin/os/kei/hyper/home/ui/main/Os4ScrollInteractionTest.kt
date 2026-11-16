package os.kei.hyper.home.ui.main

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.ui.components.KeiHomeGlassNavigationBar
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

class Os4ScrollInteractionTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  private lateinit var behavior: ScrollBehavior
  private lateinit var listState: LazyListState
  private val selected = mutableIntStateOf(0)
  private val selections = mutableListOf<Int>()

  @After fun restoreClock() { rule.mainClock.autoAdvance = true }

  @Test fun shortContentTopPullSeparatesElasticOffsetAndRebounds() {
    assertTopPullAndRebound(itemCount = 1)
  }

  @Test fun longContentTopPullSeparatesElasticOffsetAndRebounds() {
    assertTopPullAndRebound(itemCount = 30)
  }

  @Test fun scrollingContentOffsetMatchesListTravelRatherThanElasticDisplacement() {
    startScrollContent(itemCount = 4)
    // Reach the bottom through real input, then continue into elastic overscroll.
    rule.onNodeWithTag(LIST).performTouchInput {
      down(Offset(centerX, height * 0.85f))
      repeat(7) { moveBy(Offset(0f, -height * 0.1f), delayMillis = 40) }
    }
    var actualScroll = 0f
    var heldElasticOffset = 0f
    rule.runOnIdle {
      assertTrue("The gesture must consume actual list scroll", listState.canScrollBackward)
      assertTrue("The gesture must reach the bottom", !listState.canScrollForward)
      val rowHeight = listState.layoutInfo.visibleItemsInfo.first().size
      actualScroll = -(listState.firstVisibleItemIndex * rowHeight +
        listState.firstVisibleItemScrollOffset).toFloat()
      assertTrue(actualScroll < 0f)
      assertEquals("Only list travel belongs in contentOffset",
        actualScroll, behavior.state.contentOffset, 1f)
      heldElasticOffset = behavior.state.overscrollOffset
      assertTrue("The remaining upward drag must be elastic", heldElasticOffset < 0f)
      assertVisualOffset()
    }
    releaseAndAssertRebound(actualScroll, heldElasticOffset)
    rule.runOnIdle {
      assertTrue("Rebound must not reset the list to its top", listState.canScrollBackward)
    }
  }

  @Test fun navigationPointerDownPreviewsUntilReleaseCommitsExactlyOnce() {
    startNavigation()
    val target = tabPosition(2)
    rule.onNodeWithTag(NAVIGATION).performTouchInput { down(target) }
    assertNavigationUncommitted(previewIndex = 2)

    rule.onNodeWithTag(NAVIGATION).performTouchInput { up() }
    assertNavigationCommitted(index = 2)
  }

  @Test fun navigationDragAcrossTabsCommitsOnlyItsFinalDestinationOnRelease() {
    startNavigation()
    val start = tabPosition(1)
    rule.onNodeWithTag(NAVIGATION).performTouchInput { down(start) }
    assertNavigationUncommitted(previewIndex = 1)
    for (index in listOf(2, 4, 3)) {
      val target = tabPosition(index)
      rule.onNodeWithTag(NAVIGATION).performTouchInput {
        moveTo(target, delayMillis = 80)
      }
      assertNavigationUncommitted(previewIndex = index)
    }

    rule.onNodeWithTag(NAVIGATION).performTouchInput { up() }
    assertNavigationCommitted(index = 3)
  }

  @Test fun navigationCancelledDragNeverCommitsItsPreview() {
    startNavigation()
    val start = tabPosition(1)
    val destination = tabPosition(4)
    rule.onNodeWithTag(NAVIGATION).performTouchInput {
      down(start)
      moveTo(destination, delayMillis = 120)
    }
    assertNavigationUncommitted(previewIndex = 4)
    rule.onNodeWithTag(NAVIGATION).performTouchInput { cancel() }
    rule.mainClock.advanceTimeBy(2_000)
    assertNavigationUncommitted(previewIndex = 4)
  }

  private fun startScrollContent(itemCount: Int) {
    rule.setContent {
      MiuixTheme {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
          // A fixed app-bar height isolates consumed list travel from title collapse.
          val state = remember { TopAppBarState(0f, 0f, 0f) }
          behavior = MiuixScrollBehavior(state)
          listState = rememberLazyListState()
          LazyColumn(
            modifier = Modifier.size(280.dp, 300.dp).testTag(LIST)
              .keiHomeTopBarScroll(behavior),
            state = listState,
            // The app helper owns explicit overscroll, including short content.
            overscrollEffect = null,
          ) {
            items(itemCount) { index ->
              Box(Modifier.fillMaxWidth().height(120.dp)) { Text("Row $index") }
            }
          }
        }
      }
    }
    rule.onNodeWithTag(LIST).assertIsDisplayed()
    rule.runOnIdle {
      assertEquals(0, listState.firstVisibleItemIndex)
      assertEquals(0, listState.firstVisibleItemScrollOffset)
      assertEquals(0f, behavior.state.contentOffset, 0f)
      assertEquals(0f, behavior.state.overscrollOffset, 0f)
      assertEquals(itemCount > 1, listState.canScrollForward)
    }
    rule.mainClock.autoAdvance = false
  }

  private fun assertTopPullAndRebound(itemCount: Int) {
    startScrollContent(itemCount)
    rule.onNodeWithTag(LIST).performTouchInput {
      down(Offset(centerX, height * 0.2f))
      repeat(5) { moveBy(Offset(0f, height * 0.1f), delayMillis = 40) }
    }
    var heldElasticOffset = 0f
    rule.runOnIdle {
      assertEquals(0, listState.firstVisibleItemIndex)
      assertEquals(0, listState.firstVisibleItemScrollOffset)
      assertEquals("A top pull must not invent consumed scroll",
        0f, behavior.state.contentOffset, 0.01f)
      heldElasticOffset = behavior.state.overscrollOffset
      assertTrue("Actual pointer input must produce positive top overscroll",
        heldElasticOffset > 1f)
      assertVisualOffset()
    }
    releaseAndAssertRebound(actualScroll = 0f, heldElasticOffset = heldElasticOffset)
  }

  private fun releaseAndAssertRebound(actualScroll: Float, heldElasticOffset: Float) {
    rule.onNodeWithTag(LIST).performTouchInput {
      advanceEventTime(200)
      up()
    }
    rule.mainClock.advanceTimeBy(96)
    rule.runOnIdle {
      assertTrue("Spring frames must report displacement before final reset",
        kotlin.math.abs(behavior.state.overscrollOffset) < kotlin.math.abs(heldElasticOffset))
      assertEquals(actualScroll, behavior.state.contentOffset, 1f)
      assertVisualOffset()
    }
    rule.mainClock.advanceTimeBy(2_000)
    rule.runOnIdle {
      assertEquals("Rebound must report exact zero", 0f, behavior.state.overscrollOffset, 0f)
      assertEquals("Rebound must preserve actual consumed scroll",
        actualScroll, behavior.state.contentOffset, 1f)
      assertEquals(actualScroll, behavior.state.visualContentOffset, 1f)
    }
  }

  private fun assertVisualOffset() {
    assertEquals(behavior.state.contentOffset + behavior.state.overscrollOffset,
      behavior.state.visualContentOffset, 0.001f)
  }

  private fun startNavigation() {
    rule.setContent {
      MiuixTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            KeiHomeGlassNavigationBar(
              items = remember { List(5) { NavigationItem("Tab $it", MiuixIcons.Glass.Info) } },
              selectedIndex = selected.intValue,
              onSelect = { index ->
                selections += index
                selected.intValue = index
              },
              modifier = Modifier.testTag(NAVIGATION),
            )
          }
        }
      }
    }
    rule.onNodeWithTag(NAVIGATION).assertIsDisplayed()
    rule.onNodeWithContentDescription("Tab 0").assertIsSelected()
    rule.runOnIdle { assertTrue(selections.isEmpty()) }
    rule.mainClock.autoAdvance = false
  }

  private fun tabPosition(index: Int): Offset {
    val bar = rule.onNodeWithTag(NAVIGATION).fetchSemanticsNode().boundsInRoot
    val tab = rule.onNodeWithContentDescription("Tab $index").fetchSemanticsNode().boundsInRoot
    return tab.center - bar.topLeft
  }

  private fun assertNavigationUncommitted(previewIndex: Int) {
    rule.runOnIdle {
      assertEquals("Pointer previews must not dispatch navigation", emptyList<Int>(), selections)
      assertEquals(0, selected.intValue)
    }
    rule.onNodeWithContentDescription("Tab 0").assertIsSelected()
    rule.onNodeWithContentDescription("Tab $previewIndex").assertIsNotSelected()
  }

  private fun assertNavigationCommitted(index: Int) {
    rule.mainClock.advanceTimeBy(2_000)
    rule.runOnIdle {
      assertEquals("Release must dispatch exactly the final destination", listOf(index), selections)
      assertEquals(index, selected.intValue)
    }
    rule.onNodeWithContentDescription("Tab $index").assertIsSelected()
    rule.onNodeWithContentDescription("Tab 0").assertIsNotSelected()
  }

  private companion object {
    const val LIST = "os4.scroll.interaction"
    const val NAVIGATION = "os4.navigation.interaction"
  }
}
