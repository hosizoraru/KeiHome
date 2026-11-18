package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Keep one content composition while the window and native rail change shape. */
@Composable
internal fun KeiHomeAdaptiveNavigation(
  items: List<NavigationItem>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  backdrop: Backdrop?,
  modifier: Modifier = Modifier,
  badge: (Int) -> (@Composable () -> Unit)? = { null },
  overlay: @Composable () -> Unit = {},
  secondaryItems: List<NavigationItem> = emptyList(),
  selectedSecondary: Int = -1,
  railEnabled: Boolean = true,
  railBackdrop: Backdrop? = backdrop,
  content: @Composable (wide: Boolean) -> Unit,
) {
  val rail = rememberGlassNavigationRailState()
  val preferredRailWidth = 256.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.25f)
  CompositionLocalProvider(LocalKeiHomeBackdrop provides backdrop) {
    BoxWithConstraints(modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
      val wide = maxWidth >= 720.dp && maxHeight >= 480.dp
      val showRail = wide && railEnabled
      val railWidth = preferredRailWidth.coerceAtMost((maxWidth - 496.dp).coerceAtLeast(0.dp))
      Box(Modifier.fillMaxSize().layout { measurable, constraints ->
        // Read animated progress in measurement, rather than recomposing pages per frame.
        val progress = if (showRail) rail.progress else 0f
        val start = ((railWidth + 24.dp).toPx() * progress).toInt()
        val top = if (showRail) (64.dp.toPx() * (1f - progress)).toInt() else 0
        val child = measurable.measure(constraints.offset(horizontal = -start, vertical = -top))
        layout(constraints.maxWidth, constraints.maxHeight) { child.placeRelative(start, top) }
      }.testTag("adaptive.content")) {
        // Clip transitions to the detail pane so a retained entry cannot show in its gutter.
        Box(Modifier.fillMaxSize().clipToBounds()) { content(wide) }
      }
      val secondary = remember(secondaryItems) {
        secondaryItems.map { GlassNavigationItem(icon = it.icon, label = it.label) }
      }
      if (showRail) GlassNavigationRail(
        items = items.mapIndexed { index, item ->
          GlassNavigationItem(icon = item.icon, label = item.label, badge = badge(index))
        },
        secondaryItems = secondary,
        // The collapsed rail can only select a primary tab. A direct shortcut
        // can change its parent without that tab having been selected before.
        selectedIndex = if (rail.expanded && selectedSecondary in secondary.indices)
          items.size + selectedSecondary else selectedIndex,
        onSelect = onSelect,
        backdrop = railBackdrop,
        state = rail,
        expandedWidth = railWidth,
        style = GlassDefaults.style(LocalKeiHomeDarkTheme.current),
        expandContentDescription = stringResource(R.string.navigation_expand),
        collapseContentDescription = stringResource(R.string.navigation_collapse),
        modifier = Modifier.fillMaxSize().testTag("adaptive.rail"),
      )
      overlay()
    }
  }
}
