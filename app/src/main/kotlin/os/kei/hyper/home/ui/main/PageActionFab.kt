package os.kei.hyper.home.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick as onSemanticClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.ui.components.Os4FloatingToolbar
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PageActionFab(label: String, icon: ImageVector, onClick: () -> Unit,
  modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false,
  description: String = label,
  trailingIcon: Boolean = false, onHeightChanged: (Int) -> Unit = {}) {
  val foreground = if (enabled || busy) MiuixTheme.colorScheme.onPrimary
    else MiuixTheme.colorScheme.onSurfaceSecondary
  val processing = if (busy) stringResource(R.string.action_label_processing) else null
  Os4FloatingToolbar(modifier = modifier.wrapContentWidth().heightIn(min = 56.dp)
    .onSizeChanged { onHeightChanged(it.height) }
    .clearAndSetSemantics {
      role = Role.Button
      contentDescription = description
      if (!enabled || busy) disabled()
      if (processing != null) stateDescription = processing
      onSemanticClick {
        if (enabled && !busy) { onClick(); true } else false
      }
    },
    primary = enabled || busy) {
    Row(Modifier.heightIn(min = 56.dp)
      .clickable(enabled = enabled && !busy, onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      val symbol: @Composable () -> Unit = {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
          Crossfade(busy, animationSpec = tween(160), label = "page-action-progress") { loading ->
            if (loading) CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp,
              colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = foreground, backgroundColor = foreground.copy(alpha = 0.2f)))
            else Icon(icon, null, modifier = Modifier.size(22.dp), tint = foreground)
          }
        }
      }
      if (!trailingIcon) symbol()
      Text(label, modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {},
        style = MiuixTheme.textStyles.button, color = foreground,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (trailingIcon) symbol()
    }
  }
}
