package os.kei.hyper.home.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.LocalKeiHomeBackdrop
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AppleSelectionEntry(enabled: Boolean, onClick: () -> Unit) {
  KeiHomeIconButton(onClick, enabled = enabled, modifier = Modifier.testTag("apple.selection-mode")) {
    Icon(ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_square_check),
      stringResource(R.string.apple_glass_select), Modifier.size(24.dp))
  }
}

@Composable
internal fun AppleSelectionToolbar(
  selection: AppleMappingSelection, allowMatch: Boolean, enabled: Boolean, matchingEnabled: Boolean,
  onSelectAll: () -> Unit, onClear: () -> Unit, onMatch: () -> Unit, onExport: () -> Unit,
  onDone: () -> Unit, modifier: Modifier = Modifier,
) {
  val shape = GlassShape(32.dp)
  Row(modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("apple.selection.toolbar")
    .glassPanel(backdrop = LocalKeiHomeBackdrop.current, shape = shape,
      style = GlassDefaults.style(LocalKeiHomeDarkTheme.current),
      material = GlassTopAppBarDefaults.buttonMaterial(), shading = false,
      fallback = Modifier.clip(shape).background(MiuixTheme.colorScheme.surfaceContainer))
    .padding(horizontal = 8.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
    AppleToolbarButton("all", stringResource(R.string.apple_glass_select_all),
      ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_list_checks),
      enabled && selection.selected.size < selection.selectable.size, onSelectAll)
    AppleToolbarButton("none", stringResource(R.string.apple_batch_clear_selection),
      ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_square),
      enabled && selection.selected.isNotEmpty(), onClear)
    if (allowMatch) AppleToolbarButton("match",
      stringResource(R.string.apple_glass_match_selected, selection.match.size),
      actionIcon(PageActionIcon.MATCH), enabled && matchingEnabled && selection.match.isNotEmpty(), onMatch)
    AppleToolbarButton("export", stringResource(R.string.apple_glass_export_selected, selection.export.size),
      actionIcon(PageActionIcon.EXPORT), enabled && selection.export.isNotEmpty(), onExport)
    AppleToolbarButton("done", stringResource(R.string.apple_glass_done),
      actionIcon(PageActionIcon.APPLY), true, onDone, tint = MiuixTheme.colorScheme.primary)
  }
}

@Composable
private fun AppleToolbarButton(
  tag: String, label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit,
  tint: Color = MiuixTheme.colorScheme.onSurface,
) {
  val modifier = Modifier.testTag("apple.selection.$tag").semantics { role = Role.Button }
  val content: @Composable () -> Unit = {
    Icon(icon, label, Modifier.size(24.dp),
      tint = if (enabled) tint else MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.38f))
  }
  if (enabled) GlassIconButton(onClick = onClick, modifier = modifier,
    backdrop = LocalKeiHomeBackdrop.current, inheritTopBarMaterial = false,
    style = GlassDefaults.style(LocalKeiHomeDarkTheme.current), size = 48.dp, content = content)
  else KeiHomeIconButton(onClick, modifier, enabled = false, backgroundColor = Color.Transparent,
    minWidth = 48.dp, minHeight = 48.dp, content = content)
}
