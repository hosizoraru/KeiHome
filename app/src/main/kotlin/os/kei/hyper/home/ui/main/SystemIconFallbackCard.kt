package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.status.IconPackImportProtocol
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun systemFallbackLabel(style: SystemIconFallback): Int = when (style) {
  SystemIconFallback.DEFAULT -> R.string.system_fallback_default
  SystemIconFallback.CLASSIC -> R.string.system_fallback_classic
  SystemIconFallback.BOUNDLESS -> R.string.system_fallback_boundless
}

@Composable
internal fun SystemIconFallbackCard(model: HomeViewModel, ready: Boolean) {
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val source by model.browserSource.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val choices = model.preferences.systemIconFallbackChoices
  val pack = model.currentFallbackSource()
  val busy = state.nativeImporting != null
  val result = state.nativeResult?.takeIf { pack != null &&
    it.sourceId == preferences.systemIconFallback.sourceId(pack.id) }
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    Column {
      OverlaySpinnerPreference(title = stringResource(R.string.system_fallback_title),
        summary = stringResource(R.string.system_fallback_summary),
        items = choices.map { DropdownItem(text = stringResource(systemFallbackLabel(it))) },
        selectedIndex = choices.indexOf(preferences.systemIconFallback).coerceAtLeast(0),
        onSelectedIndexChange = { model.setSystemIconFallback(choices[it]) },
        enabled = !busy, modifier = Modifier.testTag("icon-packs.system-fallback"))
      Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.system_fallback_update_note),
          style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        val applied = source?.systemFallback
        if (applied != null) Text(stringResource(R.string.system_fallback_applied,
          stringResource(systemFallbackLabel(applied.style)), applied.names.size),
          style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.primary)
        if (pack != null) {
          TextButton(stringResource(if (busy) R.string.icon_pack_importing else R.string.system_fallback_update),
            onClick = model::updateCurrentSystemFallback, enabled = ready && !busy)
        } else if (!state.loading && state.scanned &&
          runtime.report?.iconTheme?.source == os.kei.hyper.home.status.SystemIconThemeReport.Source.THEME) {
          Text(stringResource(R.string.system_fallback_source_needed),
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        if (result?.imported == true) TextButton(stringResource(R.string.pack_apply_native), onClick = {
          context.startActivity(IconPackImportProtocol.nativeDetail(requireNotNull(result.localId)))
        }) else if (result != null) {
          Text(stringResource(if (result.status == "UNCERTAIN") R.string.icon_pack_native_uncertain
            else R.string.icon_pack_native_failed), style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.error)
          if (result.status == "UNCERTAIN") TextButton(stringResource(R.string.icon_pack_recheck),
            onClick = model::recheckNativeIconImport)
        }
      }
    }
  }
}
