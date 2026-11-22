package os.kei.hyper.home.ui.main

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import os.kei.hyper.home.data.AppPagerGesture
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

@Composable
internal fun KeiHomeTabPager(
  model: HomeViewModel,
  pager: PagerState,
  modifier: Modifier = Modifier,
  pageContent: @Composable PagerScope.(Int) -> Unit,
) {
  val (interactionFlow, initialInteraction) = remember(model) {
    model.preferences.state.map { it.pageUserScroll to it.pagerGesture }.distinctUntilChanged() to
      model.preferences.state.value.let { it.pageUserScroll to it.pagerGesture }
  }
  val interaction by interactionFlow.collectAsStateWithLifecycle(initialValue = initialInteraction)
  val mode = when (interaction.second) {
    AppPagerGesture.NATIVE -> PagerInterceptionMode.Native
    AppPagerGesture.CROSS_AXIS -> PagerInterceptionMode.CrossAxis
  }
  val fling = PagerDefaults.flingBehavior(pager, snapAnimationSpec = PagerNavigationSpringSpec)
  HorizontalPager(state = pager, userScrollEnabled = interaction.first && mode != PagerInterceptionMode.CrossAxis,
    modifier = modifier.pagerGestureOverride(pagerState = pager, flingBehavior = fling,
      mode = mode, enabled = interaction.first),
    flingBehavior = fling, verticalAlignment = Alignment.Top,
    pageNestedScrollConnection = if (mode == PagerInterceptionMode.CrossAxis) PagerGestureNestedScrollConnection
      else PagerDefaults.pageNestedScrollConnection(pager, Orientation.Horizontal),
    pageContent = pageContent)
}
