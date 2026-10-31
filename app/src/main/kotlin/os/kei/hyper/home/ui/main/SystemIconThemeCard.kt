package os.kei.hyper.home.ui.main

import android.os.SystemClock
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
import os.kei.hyper.home.status.RuntimeStatusUiState
import os.kei.hyper.home.status.SystemIconThemeReport
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SystemIconThemeCard(runtime: RuntimeStatusUiState) {
  val theme = runtime.report?.takeIf { it.isFresh(SystemClock.elapsedRealtime()) &&
    !runtime.readFailed && !runtime.timedOut }?.iconTheme
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(MiuixIcons.Layers, null, Modifier.size(28.dp), tint = MiuixTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(stringResource(R.string.icon_pack_active), style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
          Text(theme?.title ?: stringResource(when (theme?.source) {
            SystemIconThemeReport.Source.DEFAULT -> R.string.icon_pack_system_default
            SystemIconThemeReport.Source.THEME -> R.string.icon_pack_system_theme
            else -> R.string.status_unknown
          }), style = MiuixTheme.textStyles.title3)
        }
      }
      Text(stringResource(when {
        theme == null || theme.source == SystemIconThemeReport.Source.UNAVAILABLE -> R.string.icon_pack_waiting_system
        !theme.complete -> R.string.icon_pack_inventory_incomplete
        else -> R.string.icon_pack_native_priority
      }), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      if (theme?.complete == true) {
        Text(stringResource(R.string.icon_pack_inventory, theme.resourceCount, theme.nightResourceCount),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
  }
}
