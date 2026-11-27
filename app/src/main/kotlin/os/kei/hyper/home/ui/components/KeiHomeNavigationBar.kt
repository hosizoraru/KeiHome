package os.kei.hyper.home.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import os.kei.hyper.home.data.AppNavigationBarStyle
import os.kei.hyper.home.ui.components.liquid.IosLiquidGlassNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.LayerBackdrop

@Composable
internal fun KeiHomeNavigationBar(
  style: AppNavigationBarStyle,
  items: List<NavigationItem>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  backdrop: LayerBackdrop? = null,
  badge: (Int) -> (@Composable () -> Unit)? = { null },
  visible: Boolean = true,
) {
  when (style) {
    AppNavigationBarStyle.IOS_LIKE -> IosLiquidGlassNavigationBar(
      items, selectedIndex, onSelect, backdrop, backdrop != null, modifier, badge, visible)
    AppNavigationBarStyle.OS4_GLASS -> KeiHomeGlassNavigationBar(
      items, selectedIndex, onSelect, modifier, backdrop, badge = { badge(it)?.invoke() }, visible = visible)
  }
}
