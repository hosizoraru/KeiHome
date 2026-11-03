package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PackCompletionCard(model: HomeViewModel, onOpenSettings: () -> Unit) {
  val needsUpdate by model.iconPackUpdateNeeded.collectAsStateWithLifecycle()
  val source by model.browserSource.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val pending = pendingNativeApplication(state, runtime.report?.iconTheme?.localId)
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp),
    onClick = onOpenSettings, showIndication = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pack_completion_title), style = MiuixTheme.textStyles.body1)
        Text(stringResource(if (pending != null) R.string.pack_pending_apply_hint
          else if (needsUpdate) R.string.pack_completion_update_hint else R.string.pack_completion_hint),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (pending != null) MetadataPill(stringResource(R.string.pack_pending_apply), MiuixTheme.colorScheme.primary)
          else if (needsUpdate) MetadataPill(stringResource(R.string.pack_update_available_short), MiuixTheme.colorScheme.primary)
          val applied = source?.systemFallback
          source?.generation?.colorOsPackages?.size?.let { count ->
            if (count > 0) MetadataPill(stringResource(R.string.coloros_fallback_applied, count), MiuixTheme.colorScheme.primary)
          }
          if (applied != null) MetadataPill(stringResource(R.string.system_fallback_applied,
            stringResource(systemFallbackLabel(applied.style)), applied.names.size), MiuixTheme.colorScheme.primary)
          else MetadataPill(stringResource(R.string.pack_completion_selected,
            stringResource(systemFallbackLabel(preferences.systemIconFallback))), MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        if (pending != null) NativeThemeLink(pending, autoApply = false, enabled = state.nativeImporting == null)
      }
      Icon(MiuixIcons.Basic.ArrowRight, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}
