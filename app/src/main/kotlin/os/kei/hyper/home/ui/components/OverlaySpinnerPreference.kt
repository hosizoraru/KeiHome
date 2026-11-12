package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem

/** Compatibility for simple preference selectors; grouped menus use the OS4 cascading adapter. */
@Composable
internal fun OverlaySpinnerPreference(
  items: List<DropdownItem>,
  selectedIndex: Int,
  title: String,
  modifier: Modifier = Modifier,
  summary: String? = null,
  enabled: Boolean = true,
  insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
  onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
  if (items.any { !it.enabled }) {
    Os4ItemDropdownPreference(items, selectedIndex, title, modifier, summary, enabled,
      insideMargin, onSelectedIndexChange)
    return
  }
  Os4DropdownPreference(
    title = title,
    items = items.map { it.text },
    selectedIndex = selectedIndex,
    onSelectedIndexChange = { index ->
      items.getOrNull(index)?.takeIf { it.enabled }?.let { item ->
        onSelectedIndexChange?.invoke(index)
        item.onClick?.invoke()
      }
    },
    modifier = modifier,
    summary = summary,
    enabled = enabled,
    insideMargin = insideMargin,
  )
}
