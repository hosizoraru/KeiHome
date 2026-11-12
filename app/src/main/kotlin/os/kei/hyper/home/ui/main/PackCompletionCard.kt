package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.status.NativeIconImportReport
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PackCompletionCard(
  needsUpdate: Boolean, pending: NativeIconImportReport?, importing: Boolean, onOpenSettings: () -> Unit,
) {
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp),
    onClick = onOpenSettings, showIndication = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pack_completion_title), style = MiuixTheme.textStyles.body1)
        Text(stringResource(if (pending != null) R.string.pack_pending_apply_hint
          else if (needsUpdate) R.string.pack_completion_update_hint else R.string.pack_completion_hint),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
      Icon(MiuixIcons.Glass.ChevronForward, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
    if (pending != null) NativeThemeLink(pending, autoApply = false, enabled = !importing)
  }
}
