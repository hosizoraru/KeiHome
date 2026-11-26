package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R

internal enum class IconPreviewMode { LIGHT, DARK, COMPARE }

@Composable
internal fun IconPreviewArtworkFrame(
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  // Render the worker's masked artwork without another backplate, inset or corner shape.
  Box(modifier, contentAlignment = Alignment.Center, content = content)
}

@Composable
internal fun IconPreviewModeTabs(
  mode: IconPreviewMode,
  onSelect: (IconPreviewMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  Os4ViewTabs(
    items = listOf(stringResource(R.string.preview_glass_light), stringResource(R.string.preview_glass_dark),
      stringResource(R.string.preview_glass_compare)),
    selectedIndex = mode.ordinal,
    onSelect = { IconPreviewMode.entries.getOrNull(it)?.let(onSelect) },
    modifier = modifier,
  )
}

/** Both themes remain visible by default; choosing a theme only changes the inspection view. */
@Composable
internal fun IconPreviewComparison(
  mode: IconPreviewMode,
  modifier: Modifier = Modifier,
  content: @Composable (dark: Boolean, modifier: Modifier) -> Unit,
) {
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    if (mode != IconPreviewMode.DARK) content(false, Modifier.weight(1f))
    if (mode != IconPreviewMode.LIGHT) content(true, Modifier.weight(1f))
  }
}

/** The caller retains its focused item while hidden so the native sheet can finish dismissing. */
@Composable
internal fun IconPreviewInspectionSheet(
  show: Boolean,
  onDismissRequest: () -> Unit,
  title: String,
  modifier: Modifier = Modifier,
  onDismissFinished: (() -> Unit)? = null,
  details: @Composable ColumnScope.() -> Unit = {},
  preview: @Composable (IconPreviewMode) -> Unit,
) {
  var selectedIndex by rememberSaveable { mutableIntStateOf(IconPreviewMode.COMPARE.ordinal) }
  val mode = IconPreviewMode.entries.getOrElse(selectedIndex) { IconPreviewMode.COMPARE }
  Os4DetailsSheet(show = show, onDismissRequest = onDismissRequest, title = title,
    modifier = modifier, onDismissFinished = onDismissFinished,
    headerBottomContent = {
      IconPreviewModeTabs(mode, onSelect = { selectedIndex = it.ordinal },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
    }) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      preview(mode)
      details()
    }
  }
}
