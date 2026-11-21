package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.status.NativeIconImportReport
import os.kei.hyper.home.status.openNativeThemeDetail
import os.kei.hyper.home.ui.components.CenteredLinkAction
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun NativeThemeLink(report: NativeIconImportReport, autoApply: Boolean, enabled: Boolean = true,
  manualFallback: Boolean = false) {
  val localId = report.localId?.takeIf { report.imported && it.isNotBlank() } ?: return
  if (report.convertedReady == false) {
    Text(stringResource(if (manualFallback) R.string.pack_manual_theme_validation_incomplete
      else R.string.pack_local_apply_unavailable),
      style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    if (manualFallback) NativeThemeDetailLink(localId, enabled = enabled)
  } else {
    NativeThemeDetailLink(localId, showApply = !autoApply, enabled = enabled)
  }
}

@Composable
internal fun NativeThemeDetailLink(localId: String, showApply: Boolean = false, enabled: Boolean = true) {
  val context = LocalContext.current
  val label = stringResource(if (showApply) R.string.pack_apply_native else R.string.pack_action_view)
  val open = { openNativeThemeDetail(context, localId) }
  if (showApply) {
    CenteredLinkAction(stringResource(R.string.pack_action_apply_short), onClick = open,
      modifier = Modifier.padding(12.dp), enabled = enabled, description = label,
      icon = actionIcon(PageActionIcon.OPEN_EXTERNAL), colors = ButtonDefaults.buttonColorsPrimary())
  } else {
    PackInlineAction(label, onClick = open,
      enabled = enabled, description = label)
  }
}

@Composable
internal fun PackInlineAction(label: String, onClick: () -> Unit, enabled: Boolean = true,
  icon: ImageVector = actionIcon(PageActionIcon.OPEN_EXTERNAL), description: String = label) {
  CenteredLinkAction(label, onClick = onClick, enabled = enabled, icon = icon, description = description)
}
