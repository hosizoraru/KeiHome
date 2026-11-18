package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.material.GlassDefaults
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme

@Composable
internal fun KeiHomeGlassNavigationBar(
  items: List<NavigationItem>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  backdrop: Backdrop? = null,
  badge: @Composable (Int) -> Unit = {},
) {
  val glassItems = remember(items, badge) {
    items.mapIndexed { index, item ->
      GlassNavigationItem(icon = item.icon, label = item.label, badge = { badge(index) })
    }
  }
  Box(modifier.fillMaxWidth()
    .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()),
    contentAlignment = Alignment.Center) {
    Box(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth()) {
      GlassNavigationBar(items = glassItems, selectedIndex = selectedIndex, onSelect = onSelect,
        backdrop = backdrop, style = GlassDefaults.style(LocalKeiHomeDarkTheme.current))
    }
  }
}
