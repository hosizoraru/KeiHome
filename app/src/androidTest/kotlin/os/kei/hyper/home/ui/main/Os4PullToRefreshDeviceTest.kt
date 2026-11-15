package os.kei.hyper.home.ui.main

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import os.kei.hyper.home.R
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.ui.components.Os4PullToRefresh
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme

class Os4PullToRefreshDeviceTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()
  private val refreshing = mutableStateOf(false)
  private val scanning = mutableStateOf(false)
  private val enabled = mutableStateOf(true)
  private lateinit var list: LazyListState
  private lateinit var behavior: ScrollBehavior
  private lateinit var scope: CoroutineScope
  private var itemHeight = 0f
  private var requests = 0
  private var mounts = 0
  private var disposals = 0

  @Test fun pullKeepsListMountedAcrossRefreshScanAndCompletionWithoutDuplicateRequests() {
    showList()
    compose.mainClock.autoAdvance = false
    pull()
    compose.runOnIdle {
      assertEquals(1, requests)
      assertTrue(refreshing.value)
      assertEquals(0, list.firstVisibleItemIndex)
    }
    compose.onNodeWithTag("row:0").assertIsDisplayed()
    pull()
    compose.runOnIdle {
      assertEquals(1, requests)
      scanning.value = true
      refreshing.value = false
    }
    pull()
    compose.runOnIdle {
      assertEquals(1, requests)
      assertEquals(1, mounts)
      assertEquals(0, disposals)
      scanning.value = false
    }
    compose.mainClock.advanceTimeBy(3_000)
    pull()
    compose.runOnIdle {
      assertEquals(2, requests)
      assertEquals(1, mounts)
      assertEquals(0, disposals)
    }
  }

  @Test fun shortPullAndScrollingAwayFromTopDoNotRefreshAndDisabledPullStillAllowsScrolling() {
    showList()
    compose.mainClock.autoAdvance = false
    compose.onNodeWithTag("pull").performTouchInput {
      val start = Offset(centerX, height * 0.2f)
      down(start)
      moveTo(start + Offset(0f, 12f), delayMillis = 200)
      up()
    }
    compose.mainClock.advanceTimeBy(3_000)
    compose.runOnIdle {
      assertEquals(0, requests)
      scope.launch { list.scrollToItem(80) }
    }
    compose.waitForIdle()
    pull()
    compose.runOnIdle {
      assertEquals(0, requests)
      assertTrue(list.firstVisibleItemIndex > 0)
      enabled.value = false
      scope.launch { list.scrollToItem(0) }
    }
    compose.waitForIdle()
    pull()
    val disabledAction = compose.onNodeWithTag("pull").fetchSemanticsNode()
      .config[SemanticsActions.CustomActions].single()
    compose.runOnIdle { assertFalse(disabledAction.action()) }
    compose.onNodeWithTag("pull").performTouchInput { swipeUp() }
    compose.mainClock.advanceTimeBy(1_000)
    compose.runOnIdle {
      assertEquals(0, requests)
      assertTrue(list.canScrollBackward)
    }
  }

  @Test fun listOwnedAppBarReceivesConsumedScrollExactlyOnce() {
    showList()
    compose.onNodeWithTag("pull").performTouchInput {
      down(Offset(centerX, height * 0.75f))
      moveTo(Offset(centerX, height * 0.35f), delayMillis = 300)
    }
    compose.runOnIdle {
      val consumed = list.firstVisibleItemIndex * itemHeight + list.firstVisibleItemScrollOffset
      assertTrue(consumed > 0)
      assertEquals(-consumed, behavior.state.contentOffset, 2f)
      assertEquals(0, requests)
    }
    compose.onNodeWithTag("pull").performTouchInput { up() }
  }

  @Test fun backgroundScanDisablesPullWithoutShowingRefreshOrBlockingListScroll() {
    scanning.value = true
    showList()
    compose.mainClock.autoAdvance = false
    pull()
    val context = ApplicationProvider.getApplicationContext<Context>()
    compose.onNodeWithText(context.getString(R.string.refresh_in_progress)).assertDoesNotExist()
    compose.onNodeWithText(context.getString(R.string.pull_refresh_finished)).assertDoesNotExist()
    compose.onNodeWithTag("pull").performTouchInput { swipeUp() }
    compose.mainClock.advanceTimeBy(1_000)
    compose.runOnIdle {
      assertEquals(0, requests)
      assertTrue(list.canScrollBackward)
      assertEquals(1, mounts)
      assertEquals(0, disposals)
    }
  }

  @Test fun requestLatchRejectsSecondActionBeforeCoroutineStartedBusyIsObserved() {
    showList(deferredStart = true)
    val refresh = compose.onNodeWithTag("pull").fetchSemanticsNode()
      .config[SemanticsActions.CustomActions].single()
    compose.runOnIdle {
      assertTrue(refresh.action())
      assertFalse(refresh.action())
    }
    compose.runOnIdle {
      assertEquals(1, requests)
      assertTrue(refreshing.value)
      assertEquals(1, mounts)
      assertEquals(0, disposals)
    }
  }

  @Test fun appsRetainCommittedRowsUntilRefreshProjectionFinishesButNeverBorrowAnotherQuery() {
    val browser = mutableStateOf(pending())
    val busy = mutableStateOf(false)
    lateinit var displayed: AppsBrowserUi
    compose.setContent {
      MiuixTheme {
        val current = rememberAppsRefreshContent(browser.value, busy.value)
        SideEffect { displayed = current }
        Text(if (current.display.loading) "Loading" else "Ready")
      }
    }
    compose.runOnIdle {
      assertTrue(displayed.display.loading)
      assertFalse(displayed.display.showEmpty)
      assertTrue(displayed.visibleRows.isEmpty())
      browser.value = ready("old")
    }
    compose.runOnIdle {
      assertEquals("old", displayed.visibleRows.single().app.packageName)
      busy.value = true
      browser.value = pending()
    }
    compose.runOnIdle {
      assertEquals("old", displayed.visibleRows.single().app.packageName)
      busy.value = false
    }
    compose.runOnIdle {
      assertEquals("old", displayed.visibleRows.single().app.packageName)
      browser.value = ready("new")
    }
    compose.runOnIdle {
      assertEquals("new", displayed.visibleRows.single().app.packageName)
      busy.value = true
      browser.value = pending("other query")
    }
    compose.runOnIdle {
      assertTrue(displayed.display.loading)
      assertTrue(displayed.visibleRows.isEmpty())
      busy.value = false
      browser.value = ready()
    }
    compose.runOnIdle {
      assertTrue(displayed.display.showEmpty)
      assertTrue(displayed.visibleRows.isEmpty())
    }
  }

  private fun showList(deferredStart: Boolean = false) {
    compose.setContent {
      MiuixTheme {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
          val barState = remember { TopAppBarState(0f, 0f, 0f) }
          behavior = MiuixScrollBehavior(barState)
          list = rememberLazyListState()
          scope = rememberCoroutineScope()
          itemHeight = with(LocalDensity.current) { 48.dp.toPx() }
          Os4PullToRefresh(
            refreshing = refreshing.value,
            enabled = enabled.value && !scanning.value,
            onRefresh = {
              requests++
              if (deferredStart) scope.launch { yield(); refreshing.value = true }
              else refreshing.value = true
            },
            modifier = Modifier.testTag("pull"),
          ) {
            DisposableEffect(Unit) {
              mounts++
              onDispose { disposals++ }
            }
            LazyColumn(state = list, overscrollEffect = null,
              modifier = Modifier.fillMaxSize().keiHomeTopBarScroll(behavior)) {
              items((0 until 200).toList(), key = { it }) {
                Text("App $it", Modifier.height(48.dp).testTag("row:$it"))
              }
            }
          }
        }
      }
    }
  }

  private fun pull() {
    compose.onNodeWithTag("pull").performTouchInput { swipeDown(durationMillis = 700) }
    compose.mainClock.advanceTimeBy(1_500)
  }

  private fun pending(query: String = "") = AppsBrowserUi(query, {}, 0, AppsBrowserDisplay(Any(), null))

  private fun ready(vararg packages: String): AppsBrowserUi {
    val request = Any()
    val rows = packages.map {
      IconBrowserRow(InstalledIconCapability(it, it, 0, true,
        IconCapabilityStatus.UNKNOWN, IconCapabilityStatus.UNKNOWN), null)
    }
    return AppsBrowserUi("", {}, 0, AppsBrowserDisplay(request, AppsBrowserResult(request, rows, rows.size)))
  }
}
