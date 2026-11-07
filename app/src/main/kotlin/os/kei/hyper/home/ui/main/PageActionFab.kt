package os.kei.hyper.home.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PageActionFab(label: String, icon: ImageVector, onClick: () -> Unit,
  modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false,
  onHeightChanged: (Int) -> Unit = {}) {
  val foreground = if (enabled || busy) MiuixTheme.colorScheme.onPrimary
    else MiuixTheme.colorScheme.onSurfaceSecondary
  FloatingActionButton(modifier = modifier.widthIn(min = 164.dp).onSizeChanged { onHeightChanged(it.height) }
    .semantics { if (!enabled || busy) disabled() },
    onClick = { if (enabled && !busy) onClick() }, shape = RoundedCornerShape(28.dp),
    containerColor = if (enabled || busy) MiuixTheme.colorScheme.primary
      else MiuixTheme.colorScheme.surfaceVariant) {
    Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Crossfade(busy, animationSpec = tween(160), label = "page-action-progress") { loading ->
          if (loading) CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp,
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
              foregroundColor = foreground, backgroundColor = foreground.copy(alpha = 0.2f)))
          else Icon(icon, null, tint = foreground)
        }
      }
      Text(label, style = MiuixTheme.textStyles.button, color = foreground)
    }
  }
}
