package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.status.NativeIconImportReport
import os.kei.hyper.home.ui.components.CenteredLinkAction
import top.yukonga.miuix.kmp.basic.ButtonDefaults

@Composable
internal fun NativeThemeLink(report: NativeIconImportReport, autoApply: Boolean, enabled: Boolean = true) {
  NativeThemeButton(report, autoApply, enabled)
}

@Composable
internal fun NativeThemeButton(report: NativeIconImportReport, autoApply: Boolean, enabled: Boolean = true) {
  val context = LocalContext.current
  val label = stringResource(if (autoApply) R.string.pack_action_view else R.string.pack_apply_native)
  val open = {
    runCatching { context.startActivity(IconPackImportProtocol.nativeDetail(requireNotNull(report.localId))) }
    Unit
  }
  val caption = stringResource(if (autoApply) R.string.pack_action_view_short else R.string.pack_action_apply_short)
  if (autoApply) {
    CenteredLinkAction(caption, onClick = open, enabled = enabled && report.imported,
      description = label, icon = actionIcon(PageActionIcon.OPEN_EXTERNAL))
  } else {
    CenteredLinkAction(caption, onClick = open, modifier = Modifier.padding(12.dp),
      enabled = enabled && report.imported, description = label,
      icon = actionIcon(PageActionIcon.OPEN_EXTERNAL), colors = ButtonDefaults.buttonColorsPrimary())
  }
}

@Composable
internal fun PackInlineAction(label: String, onClick: () -> Unit, enabled: Boolean = true,
  icon: ImageVector = actionIcon(PageActionIcon.OPEN_EXTERNAL), description: String = label) {
  CenteredLinkAction(label, onClick = onClick, enabled = enabled, icon = icon, description = description)
}
