package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.basic.Text

class LocalSearchInteractionTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val navigation = Os4NavigationTestHost()
  private lateinit var latest: LocalSearchProjection<List<String>>
  private lateinit var list: LazyListState
  private var gate: CompletableDeferred<Unit>? = null
  private var fail = false
  private var progressSeen = false

  @Test fun slowBroadMatchTypingKeepsFocusViewportAndDoesNotFlashProgress() {
    start()
    val input = rule.onNodeWithTag("local-search.input").performClick()
    rule.mainClock.advanceTimeBy(1_000)
    rule.onNodeWithTag("local-search.rows").performScrollToIndex(20)
    val viewport = rule.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
    for (query in listOf("i", "ic", "ico", "icon")) {
      input.performTextReplacement(query)
      awaitResult(query)
      input.assertIsFocused()
      rule.runOnIdle {
        assertEquals(100, list.layoutInfo.totalItemsCount)
        assertEquals(viewport, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        assertFalse(progressSeen)
      }
      rule.mainClock.advanceTimeBy(300)
    }
  }

  @Test fun genuinelySlowFilteringRetainsRowsAndShowsDelayedInlineProgress() {
    start()
    val old = rule.runOnIdle { latest.value }
    rule.runOnIdle { gate = CompletableDeferred() }
    rule.onNodeWithTag("local-search.input").performClick().performTextReplacement("icon")
    rule.mainClock.advanceTimeBy(LOCAL_SEARCH_PROGRESS_MS + 100)
    rule.waitUntil(5_000) { rule.runOnIdle { latest.status.showProgress } }
    rule.runOnIdle {
      assertSame(old, latest.value)
      assertTrue(latest.status.pending)
      gate!!.complete(Unit)
    }
    awaitResult("icon")
    rule.onNodeWithTag("local-search.input").assertIsFocused()
    rule.runOnIdle { assertFalse(latest.status.showProgress) }
  }

  @Test fun failedFilterKeepsRowsAndShowsAnExplicitWorkingRetry() {
    start()
    val old = rule.runOnIdle { latest.value }
    rule.runOnIdle { fail = true }
    rule.onNodeWithTag("local-search.input").performClick().performTextReplacement("icon")
    rule.waitUntil(5_000) { rule.runOnIdle { latest.status.error != null } }
    rule.runOnIdle {
      assertSame(old, latest.value)
      assertFalse(latest.status.pending)
      assertFalse(latest.status.showProgress)
      fail = false
    }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    rule.onNodeWithText(context.getString(R.string.local_search_failed)).assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.local_search_retry)).performClick()
    awaitResult("icon")
    rule.runOnIdle { assertEquals(null, latest.status.error) }
  }

  private fun awaitResult(query: String) {
    rule.waitUntil(5_000) {
      rule.runOnIdle { latest.request.query == query && !latest.status.pending && latest.status.error == null }
    }
  }

  private fun start() {
    rule.setContent {
      KeiHomeTheme {
        navigation.Provide {
          var query by remember { mutableStateOf("") }
          val scope = remember { Any() }
          val rows = remember { List(100) { "Icon $it" } }
          val projection = rememberLocalSearch(scope, query,
            workerDispatcher = Dispatchers.Main.immediate) { request ->
            if (request.query.isNotEmpty()) gate?.await()
            check(!fail)
            rows.filter { it.contains(request.query, ignoreCase = true) }
          }
          val listState = rememberLazyListState()
          SideEffect {
            latest = projection
            list = listState
            progressSeen = progressSeen || projection.status.showProgress
          }
          Column(Modifier.fillMaxSize()) {
            Os4SearchField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = "Search",
              inputModifier = Modifier.testTag("local-search.input"),
              searching = projection.status.showProgress)
            LocalSearchError(projection.status)
            LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("local-search.rows")) {
              items(projection.value.orEmpty(), key = { it }) { Text(it) }
            }
          }
        }
      }
    }
    awaitResult("")
  }
}
