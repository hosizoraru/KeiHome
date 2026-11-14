package os.kei.hyper.home.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class Os4DialogAction(
  val text: String,
  val onClick: () -> Unit,
  val role: GlassDialogButtonRole = GlassDialogButtonRole.Normal,
  val enabled: Boolean = true,
  val tag: String? = null,
)

@Composable
internal fun Os4ActionDialog(
  show: Boolean,
  onDismissRequest: () -> Unit,
  title: String,
  summary: String,
  icon: ImageVector? = null,
  actions: List<Os4DialogAction>,
  artwork: (@Composable () -> Unit)? = null,
  onDismissFinished: (() -> Unit)? = null,
  details: @Composable () -> Unit = {},
) {
  val maxHeight = LocalWindowInfo.current.containerDpSize.height * 0.7f
  Os4Dialog(show, onDismissRequest, modifier = Modifier.semantics { paneTitle = title },
    onDismissFinished = onDismissFinished) {
    // The MIUIX host already supplies 24dp insets. Keep only spacing between content here.
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (artwork != null) artwork()
        else if (icon != null) Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
          .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
          contentAlignment = Alignment.Center) {
          Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp),
            tint = MiuixTheme.colorScheme.onSurface)
        }
        Text(title, Modifier.fillMaxWidth(), style = MiuixTheme.textStyles.title4,
          fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Text(summary, Modifier.fillMaxWidth(), style = MiuixTheme.textStyles.body1,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
        details()
      }
      Os4DialogActions(actions, Modifier.padding(top = 8.dp))
    }
  }
}

@Composable
internal fun Os4DialogActions(actions: List<Os4DialogAction>, modifier: Modifier = Modifier) {
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val vertical = actions.size > 2 || maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f
    val button: @Composable (Os4DialogAction, Modifier) -> Unit = { action, modifier ->
      Os4DialogButton(action.text, action.onClick, enabled = action.enabled, role = action.role,
        modifier = modifier.then(action.tag?.let { Modifier.testTag(it) } ?: Modifier))
    }
    if (vertical) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.sortedBy { it.role == GlassDialogButtonRole.Normal }.forEach {
          button(it, Modifier.fillMaxWidth())
        }
      }
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        actions.forEach { button(it, Modifier.weight(1f)) }
      }
    }
  }
}
