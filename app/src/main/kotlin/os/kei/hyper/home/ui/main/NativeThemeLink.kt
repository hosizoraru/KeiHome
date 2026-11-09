package os.kei.hyper.home.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import os.kei.hyper.home.R
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.status.NativeIconImportReport
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun NativeThemeLink(report: NativeIconImportReport, autoApply: Boolean, enabled: Boolean = true) {
  val context = LocalContext.current
  val label = stringResource(if (autoApply) R.string.pack_action_view else R.string.pack_apply_native)
  val open = {
    runCatching { context.startActivity(IconPackImportProtocol.nativeDetail(requireNotNull(report.localId))) }
    Unit
  }
  if (autoApply) BasicComponent(title = stringResource(R.string.pack_action_view_short),
    modifier = Modifier.semantics { contentDescription = label }, onClick = open, enabled = enabled, role = Role.Button,
    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    endActions = { Icon(MiuixIcons.Basic.ArrowRight, null, Modifier.size(18.dp)) })
  else TextButton(stringResource(R.string.pack_action_apply_short), onClick = open, enabled = enabled,
    modifier = Modifier.fillMaxWidth().padding(12.dp).semantics { contentDescription = label }, colors = ButtonDefaults.textButtonColorsPrimary())
}

@Composable
internal fun PackInlineAction(label: String, onClick: () -> Unit, enabled: Boolean = true,
  icon: ImageVector = MiuixIcons.Theme) {
  val color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary
  Row(Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(icon, null, Modifier.size(20.dp), tint = color)
    Text(label, style = MiuixTheme.textStyles.footnote1, color = color)
  }
}
