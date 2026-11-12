package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
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
  insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
) {
  val actualEnabled = enabled && items.isNotEmpty()
  GlassDropdownPreference(
    title = title,
    items = items,
    selectedIndex = selectedIndex,
    onSelectedIndexChange = { index ->
      if (actualEnabled && index in items.indices) onSelectedIndexChange(index)
    },
    modifier = modifier.semantics(mergeDescendants = true) {
      role = Role.DropdownList
      if (!actualEnabled) disabled()
    },
    summary = summary,
    enabled = actualEnabled,
    insideMargin = insideMargin,
    backdrop = LocalKeiHomeBackdrop.current,
    visuals = GlassPopupDefaults.visuals(style = GlassDefaults.style(LocalKeiHomeDarkTheme.current)),
  )
}
