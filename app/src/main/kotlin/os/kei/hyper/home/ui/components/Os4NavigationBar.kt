package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.material.GlassDefaults

@Composable
internal fun KeiHomeGlassNavigationBar(
  items: List<NavigationItem>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  backdrop: Backdrop? = null,
  badge: @Composable (Int) -> Unit = {},
  visible: Boolean = true,
) {
  val latestBadge by rememberUpdatedState(badge)
  val glassItems = remember(items) {
    items.mapIndexed { index, item ->
      GlassNavigationItem(icon = item.icon, label = item.label, badge = { latestBadge(index) })
    }
  }
  Box(modifier.fillMaxWidth().navBottomBarGestureDissolve(), contentAlignment = Alignment.Center) {
    GlassNavigationBar(
      items = glassItems,
      selectedIndex = selectedIndex,
      onSelect = onSelect,
      modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp),
      backdrop = backdrop,
      visible = visible,
      style = GlassDefaults.style(LocalKeiHomeDarkTheme.current),
    )
  }
}
