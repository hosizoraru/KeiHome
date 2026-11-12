package os.kei.hyper.home.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.material.GlassDefaults

@Composable
internal fun Os4DropdownPreference(
  title: String,
  items: List<String>,
  selectedIndex: Int,
  onSelectedIndexChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  enabled: Boolean = true,
) {
  GlassDropdownPreference(
    title = title,
    items = items,
    selectedIndex = selectedIndex,
    onSelectedIndexChange = onSelectedIndexChange,
    modifier = modifier,
    summary = summary,
    enabled = enabled,
    backdrop = LocalKeiHomeBackdrop.current,
    visuals = GlassPopupDefaults.visuals(style = GlassDefaults.style(LocalKeiHomeDarkTheme.current)),
  )
}
