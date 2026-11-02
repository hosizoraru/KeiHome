package os.kei.hyper.home.ui.main

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.SystemIconFallback
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

internal fun systemFallbackLabel(style: SystemIconFallback): Int = when (style) {
  SystemIconFallback.DEFAULT -> R.string.system_fallback_default
  SystemIconFallback.CLASSIC -> R.string.system_fallback_classic
  SystemIconFallback.BOUNDLESS -> R.string.system_fallback_boundless
}

@Composable
internal fun SystemIconFallbackCard(model: HomeViewModel) {
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val source by model.browserSource.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val apps by model.apps.collectAsStateWithLifecycle()
  val updateNeeded by model.iconPackUpdateNeeded.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val choices = model.preferences.systemIconFallbackChoices
  val pack = model.currentFallbackSource()
  val busy = state.nativeImporting != null
  val ready = framework.connected && framework.snapshot?.themesInScope == true &&
    runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong() && !runtime.readFailed && !runtime.timedOut
  val result = state.nativeResult?.takeIf { pack != null &&
    it.sourceId == preferences.systemIconFallback.sourceId(pack.id) }
  Card(Modifier.fillMaxWidth().animateContentSize(tween(180)), insideMargin = PaddingValues(0.dp)) {
    Column {
      OverlaySpinnerPreference(title = stringResource(R.string.system_fallback_title),
        summary = stringResource(R.string.system_fallback_summary),
        items = choices.map { DropdownItem(text = stringResource(systemFallbackLabel(it))) },
        selectedIndex = choices.indexOf(preferences.systemIconFallback).coerceAtLeast(0),
        onSelectedIndexChange = { model.setSystemIconFallback(choices[it]) },
        enabled = !busy, modifier = Modifier.testTag("icon-packs.system-fallback"))
      SwitchPreference(title = stringResource(R.string.system_name_matching_title),
        summary = stringResource(R.string.system_name_matching_summary),
        checked = preferences.systemAppNameMatching, enabled = !busy,
        onCheckedChange = model.preferences::setSystemAppNameMatching,
        modifier = Modifier.testTag("icon-packs.system-name-matching"))
      Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val applied = source?.systemFallback
        if (applied != null || updateNeeded) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              if (applied != null) MetadataPill(stringResource(R.string.system_fallback_applied,
                stringResource(systemFallbackLabel(applied.style)), applied.names.size), MiuixTheme.colorScheme.primary)
              if (updateNeeded) {
                val description = stringResource(R.string.pack_update_available)
                MetadataPill(stringResource(R.string.pack_update_available_short), MiuixTheme.colorScheme.primary,
                  Modifier.semantics { contentDescription = description })
              }
            }
            if (applied != null && source != null) {
              val current = source!!
              val launcherNames = apps.capabilities.orEmpty().filter { it.hasLauncher }.map { it.packageName }
              val names = launcherNames.filter { it in applied.names }.distinct().take(2)
                .ifEmpty { applied.names.sorted().take(2) }
              Row {
                names.mapNotNull(current.entries::get).distinctBy { it.key }.forEach {
                  PackStyleThumbnail(current.pack, it, model.packPreviews, os.kei.hyper.home.data.IconPackAppearance.SYSTEM)
                }
              }
            }
          }
        }
        Text(stringResource(R.string.system_fallback_update_note),
          style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (pack == null && !state.loading && state.scanned &&
          runtime.report?.iconTheme?.source == os.kei.hyper.home.status.SystemIconThemeReport.Source.THEME) {
          Text(stringResource(R.string.system_fallback_source_needed),
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          if (pack != null) PackInlineAction(stringResource(R.string.pack_action_update),
            onClick = model::updateCurrentSystemFallback, enabled = ready && !busy, icon = MiuixIcons.Refresh)
          if (result?.imported == true) NativeThemeLink(result, preferences.autoApplyIconPack, enabled = !busy)
        }
        if (result != null && !result.imported) {
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
