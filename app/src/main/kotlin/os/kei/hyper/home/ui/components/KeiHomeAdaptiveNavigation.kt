package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassNavigationRail
import top.yukonga.miuix.kmp.glass.rememberGlassNavigationRailState
import top.yukonga.miuix.kmp.material.GlassDefaults

/** Keep one content composition while the window and native rail change shape. */
@Composable
internal fun KeiHomeAdaptiveNavigation(
  items: List<NavigationItem>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  backdrop: Backdrop?,
  modifier: Modifier = Modifier,
  badge: (Int) -> (@Composable () -> Unit)? = { null },
  content: @Composable (wide: Boolean) -> Unit,
) {
  val rail = rememberGlassNavigationRailState()
  val railWidth = 256.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.25f)
  BoxWithConstraints(modifier.fillMaxSize()) {
    val wide = maxWidth >= 840.dp && maxHeight >= 480.dp
    Box(Modifier.fillMaxSize().layout { measurable, constraints ->
      // Read animated progress in measurement, rather than recomposing pages per frame.
      val progress = if (wide) rail.progress else 0f
      val start = if (wide) ((railWidth + 24.dp).toPx() * progress).toInt() else 0
      val top = if (wide) (64.dp.toPx() * (1f - progress)).toInt() else 0
      val child = measurable.measure(constraints.offset(horizontal = -start, vertical = -top))
      layout(constraints.maxWidth, constraints.maxHeight) { child.placeRelative(start, top) }
    }.testTag("adaptive.content")) { content(wide) }
    if (wide) GlassNavigationRail(
      items = items.mapIndexed { index, item ->
        GlassNavigationItem(icon = item.icon, label = item.label, badge = badge(index))
      },
      selectedIndex = selectedIndex,
      onSelect = onSelect,
      backdrop = backdrop,
      state = rail,
      expandedWidth = railWidth,
      style = GlassDefaults.style(LocalKeiHomeDarkTheme.current),
      expandContentDescription = stringResource(R.string.navigation_expand),
      collapseContentDescription = stringResource(R.string.navigation_collapse),
      modifier = Modifier.fillMaxSize().testTag("adaptive.rail"),
    )
  }
}
