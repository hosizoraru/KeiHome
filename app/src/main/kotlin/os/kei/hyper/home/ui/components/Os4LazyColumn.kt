package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

/** The native thumb shares list state, track insets and existing nested scroll. */
@OptIn(ExperimentalScrollBarApi::class)
@Composable
internal fun Os4LazyColumn(
  state: LazyListState,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  verticalArrangement: Arrangement.Vertical = Arrangement.Top,
  topAppBarScrollBehavior: ScrollBehavior? = null,
  content: LazyListScope.() -> Unit,
) {
  val scrollable by remember(state) {
    derivedStateOf { state.canScrollForward || state.canScrollBackward }
  }
  Box(modifier) {
    LazyColumn(state = state, overscrollEffect = null, modifier = Modifier.fillMaxSize(),
      contentPadding = contentPadding, verticalArrangement = verticalArrangement, content = content)
    if (scrollable) {
      // The narrow native hit strip stays in the outer margin, away from trailing controls.
      VerticalScrollBar(
        adapter = rememberScrollBarAdapter(state),
        modifier = Modifier.align(Alignment.CenterEnd)
          .fillMaxHeight().testTag("os4.list.scrollbar"),
        trackPadding = PaddingValues(top = contentPadding.calculateTopPadding(),
          bottom = contentPadding.calculateBottomPadding()),
        topAppBarScrollBehavior = topAppBarScrollBehavior,
      )
    }
  }
}
