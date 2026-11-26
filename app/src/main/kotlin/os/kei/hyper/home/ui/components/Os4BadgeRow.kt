package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal object Os4BadgePalette {
  val Blue = Color(0xFF3193FB)
  val Pink = Color(0xFFE95C88)
  val Gold = Color(0xFFC8A654)
  val Teal = Color(0xFF2CB5AC)
  val Purple = Color(0xFF8B6CF6)
  val Green = Color(0xFF34C759)
  val Orange = Color(0xFFFF9500)
  val Indigo = Color(0xFF5E7CE2)
}

@Composable
internal fun Os4IconBadge(
  icon: ImageVector,
  tint: Color,
  modifier: Modifier = Modifier,
  size: Dp = 36.dp,
  cornerRadius: Dp = 11.dp,
  iconSize: Dp = 19.dp,
) {
  Box(
    modifier.size(size).squircleBackground(tint.copy(alpha = 0.14f), cornerRadius),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = tint)
  }
}

@Composable
internal fun Os4InsetDivider(
  modifier: Modifier = Modifier,
  start: Dp = 66.dp,
  end: Dp = 16.dp,
) {
  HorizontalDivider(
    modifier = modifier.padding(start = start, end = end),
    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
  )
}

@Composable
internal fun Os4NavigationRow(
  title: String,
  summary: String? = null,
  icon: ImageVector,
  tint: Color,
  modifier: Modifier = Modifier,
  badgeText: String? = null,
  badgeTint: Color = tint,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  val pressSource = rememberOs4PressSource()
  BasicComponent(
    title = title,
    summary = summary,
    enabled = enabled,
    role = Role.Button,
    interactionSource = pressSource,
    modifier = modifier.glassPress(pressSource, enabled),
    startAction = { Os4IconBadge(icon = icon, tint = tint) },
    endActions = {
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (!badgeText.isNullOrBlank()) {
          Text(
            text = badgeText,
            style = MiuixTheme.textStyles.footnote2,
            fontWeight = FontWeight.Medium,
            color = badgeTint,
            modifier = Modifier
              .squircleBackground(badgeTint.copy(alpha = 0.14f), 9.dp)
              .padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
        Os4TrailingChevron()
      }
    },
    onClick = onClick,
  )
}
