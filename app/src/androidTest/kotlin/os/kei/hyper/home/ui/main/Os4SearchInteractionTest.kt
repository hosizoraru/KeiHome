package os.kei.hyper.home.ui.main

import androidx.compose.ui.test.click
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

class Os4SearchInteractionTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val navigation = Os4NavigationTestHost()
  private val searches = mutableListOf<String>()
  private lateinit var resultsState: LazyListState
  private var imeBottomPx = 0

  @Test fun cancelAndBackCollapseEditingWithoutClearingExistingQuery() {
    start(initialQuery = "Icon 1")
    val input = rule.onNodeWithTag(INPUT)
    input.assertTextEquals("Icon 1").assertIsNotFocused().performClick()
    rule.onNodeWithTag(CANCEL).assertIsDisplayed().performClick()
    rule.mainClock.advanceTimeBy(1_000)
    input.assertTextEquals("Icon 1").assertIsNotFocused()
    rule.onNodeWithTag(CANCEL).assertDoesNotExist()
    rule.onNodeWithTag("result-10").assertIsDisplayed()

    input.performClick().assertIsFocused()
    rule.runOnIdle { navigation.completeBack() }
    rule.mainClock.advanceTimeBy(1_000)
    input.assertTextEquals("Icon 1").assertIsNotFocused()
    rule.onNodeWithTag(CANCEL).assertDoesNotExist()
    rule.onNodeWithTag(RESULTS).assertIsDisplayed()
    rule.runOnIdle { assertEquals(emptyList<String>(), searches) }
  }

  @Test fun imeSubmitsOnceAndLeavesFilteredResultsOperable() {
    start()
    val input = rule.onNodeWithTag(INPUT)
    input.performClick().performTextReplacement("Icon 1")
    rule.onNodeWithTag(CANCEL).assertIsDisplayed()
    input.performImeAction()
    rule.mainClock.advanceTimeBy(1_000)
    input.assertTextEquals("Icon 1").assertIsNotFocused()
    rule.onNodeWithTag(CANCEL).assertDoesNotExist()
    rule.runOnIdle { assertEquals(listOf("Icon 1"), searches) }
    rule.onNodeWithTag("result-10").assertIsDisplayed().performClick()
    rule.onNodeWithTag(CHILD).assertIsDisplayed()
  }

  @Test fun expandedSearchDoesNotCoverOrInterceptItsFilteredResults() {
    start()
    rule.onNodeWithTag(INPUT).performClick().performTextReplacement("Icon 2")
    awaitVisibleIme()
    rule.onNodeWithTag(CANCEL).assertIsDisplayed()
    // Use a real touch with the IME visible: semantics clicks bypass hit testing.
    rule.onNodeWithTag("result-20").assertIsDisplayed().performTouchInput { click(center) }
    rule.onNodeWithTag(CHILD).assertIsDisplayed()
    rule.onNodeWithTag(RETURN).performClick()
    rule.onNodeWithTag(INPUT).assertTextEquals("Icon 2").assertIsNotFocused()
    rule.onNodeWithTag("result-20").assertIsDisplayed()
  }

  @Test fun explicitClearKeepsSearchExpandedAndRestoresUnfilteredResults() {
    start()
    val input = rule.onNodeWithTag(INPUT)
    input.performClick().performTextReplacement("Icon 2")
    awaitVisibleIme()
    rule.onNodeWithTag(RESULTS).performScrollToIndex(6)
    rule.runOnIdle {
      assertEquals(11, resultsState.layoutInfo.totalItemsCount)
      assertTrue("Start scrolled to verify the whole data set becomes available again",
        resultsState.canScrollBackward)
    }
    val clear = InstrumentationRegistry.getInstrumentation().targetContext
      .getString(R.string.search_clear_text)
    rule.onNodeWithContentDescription(clear).performClick()
    input.assertTextEquals("").assertIsFocused()
    rule.onNodeWithTag(CANCEL).assertIsDisplayed()
    rule.runOnIdle {
      assertEquals("Clear must restore the complete data set", 101,
        resultsState.layoutInfo.totalItemsCount)
      assertEquals(emptyList<String>(), searches)
    }
    // The field owns text, not the page's scroll policy. All rows must remain operable.
    rule.onNodeWithTag(RESULTS).performScrollToIndex(0)
    rule.onNodeWithTag("result-0").assertIsDisplayed().performTouchInput { click(center) }
    rule.onNodeWithTag(CHILD).assertIsDisplayed()
  }

  @Test fun childReturnRestoresQueryFilterAndScrolledResults() {
    start()
    rule.onNodeWithTag(INPUT).performClick().performTextReplacement("Icon")
    rule.onNodeWithTag(FILTER).performClick()
    rule.onNodeWithTag(RESULTS).performScrollToIndex(18)
    var savedIndex = -1
    var savedOffset = -1
    rule.runOnIdle {
      savedIndex = resultsState.firstVisibleItemIndex
      savedOffset = resultsState.firstVisibleItemScrollOffset
    }
    rule.onNodeWithTag("result-36").assertIsDisplayed().performClick()
    rule.onNodeWithTag(CHILD).assertIsDisplayed()
    rule.onNodeWithTag(RETURN).performClick()
    rule.onNodeWithTag(INPUT).assertTextEquals("Icon")
    rule.onNodeWithTag(FILTER).assertTextEquals("Even icons")
    rule.onNodeWithTag("result-36").assertIsDisplayed()
    rule.runOnIdle {
      assertEquals(savedIndex, resultsState.firstVisibleItemIndex)
      assertEquals(savedOffset, resultsState.firstVisibleItemScrollOffset)
    }
  }

  private fun start(initialQuery: String = "") {
    rule.setContent {
      KeiHomeTheme {
        navigation.Provide { SearchResultsHost(initialQuery) }
      }
    }
  }

  private fun awaitVisibleIme() {
    rule.waitUntil(timeoutMillis = 5_000) { rule.runOnIdle { imeBottomPx > 0 } }
  }

  @Composable
  private fun SearchResultsHost(initialQuery: String) {
    imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    var child by remember { mutableStateOf(false) }
    val savedPages = rememberSaveableStateHolder()
    if (child) {
      Column(Modifier.testTag(CHILD)) {
        Text("Icon details")
        TextButton("Return", onClick = { child = false }, modifier = Modifier.testTag(RETURN))
      }
    } else savedPages.SaveableStateProvider("results") {
      var query by rememberSaveable { mutableStateOf(initialQuery) }
      var evenOnly by rememberSaveable { mutableStateOf(false) }
      val list = rememberLazyListState()
      resultsState = list
      val rows = (0..100).filter { (!evenOnly || it % 2 == 0) && "Icon $it".contains(query) }
      Column(Modifier.fillMaxSize().imePadding()) {
        Os4SearchField(query = query, onQueryChange = { query = it },
          label = "Search icons", inputModifier = Modifier.testTag(INPUT),
          onSearch = { searches += it })
        TextButton(if (evenOnly) "Even icons" else "All icons",
          onClick = { evenOnly = !evenOnly }, modifier = Modifier.testTag(FILTER))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag(RESULTS),
          state = list, overscrollEffect = null) {
          items(rows, key = { it }) { index ->
            TextButton("Icon $index", onClick = { child = true },
              modifier = Modifier.fillMaxWidth().testTag("result-$index"))
          }
        }
      }
    }
  }

  private companion object {
    const val INPUT = "os4.search.input"
    const val CANCEL = "os4.search.cancel"
    const val RESULTS = "os4.search.results"
    const val FILTER = "os4.search.filter"
    const val CHILD = "os4.search.child"
    const val RETURN = "os4.search.return"
  }
}
