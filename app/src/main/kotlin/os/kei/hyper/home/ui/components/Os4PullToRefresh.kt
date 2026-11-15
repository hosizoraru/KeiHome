package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.PullToRefresh

/** The child keeps its list, padding and sole app-bar scroll connection while refreshing. */
@Composable
internal fun Os4PullToRefresh(
  refreshing: Boolean,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  contentPadding: PaddingValues = PaddingValues(),
  refreshLabel: String = stringResource(R.string.refresh),
  content: @Composable () -> Unit,
) {
  var requested by remember { mutableStateOf(false) }
  val busy = refreshing || requested
  val accepting = enabled && !busy
  val canPull by rememberUpdatedState(accepting)
  val request: () -> Boolean = {
    if (!enabled || refreshing || requested) false else {
      requested = true
      onRefresh()
      true
    }
  }
  LaunchedEffect(requested, refreshing) {
    if (requested) {
      if (!refreshing) {
        // Bridge controller/produceState startup through a committed frame, including fast loads.
        withFrameNanos { }
        withFrameNanos { }
      }
      requested = false
    }
  }
  val disabledPull = remember {
    object : NestedScrollConnection {
      override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (!canPull && available.y > 0f) Offset(0f, available.y) else Offset.Zero
    }
  }
  PullToRefresh(
    isRefreshing = busy,
    onRefresh = { request() },
    modifier = modifier.fillMaxSize().semantics {
      customActions = listOf(CustomAccessibilityAction(refreshLabel, request))
    },
    contentPadding = contentPadding,
    refreshTexts = listOf(
      stringResource(R.string.pull_refresh_hint),
      stringResource(R.string.pull_refresh_release),
      stringResource(R.string.refresh_in_progress),
      stringResource(R.string.pull_refresh_finished),
    ),
    // MIUIX docs: do not dispatch the app-bar behavior twice when the list already owns it.
    topAppBarScrollBehavior = null,
  ) {
    // Consume only unhandled downward motion, so a disabled refresh never blocks list scrolling.
    Box(Modifier.fillMaxSize().nestedScroll(disabledPull)) { content() }
  }
}
