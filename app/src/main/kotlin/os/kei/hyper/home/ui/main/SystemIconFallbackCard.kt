package os.kei.hyper.home.ui.main

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.SystemIconFallback
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun systemFallbackName(style: SystemIconFallback): String = when (style) {
  SystemIconFallback.DEFAULT -> stringResource(R.string.system_fallback_default)
  SystemIconFallback.CLASSIC -> stringResource(R.string.system_fallback_classic)
  SystemIconFallback.BOUNDLESS -> stringResource(R.string.system_fallback_boundless)
  else -> stringResource(R.string.system_fallback_perfect, requireNotNull(style.perfectStyle))
}

internal fun systemFallbackChoices(
  bundled: List<SystemIconFallback>,
  downloadedStyles: List<String>,
  expanded: Boolean,
  selected: SystemIconFallback,
): List<SystemIconFallback> = buildList {
  addAll(bundled)
  val downloaded = downloadedStyles.mapNotNull {
    runCatching { SystemIconFallback.perfect(it) }.getOrNull()
  }.distinct()
  val hyperOs4 = SystemIconFallback.perfect(SystemIconFallback.DEFAULT_PERFECT_STYLE)
  if (hyperOs4 in downloaded) add(hyperOs4)
  if (expanded) addAll(downloaded)
  // Keep the saved choice visible even after collapsing or removing its local resources.
  add(selected)
}.distinct()

internal fun perfectFallbackResourceRoute(selected: SystemIconFallback, downloadedStyles: List<String>): String =
  if ((selected.perfectStyle ?: SystemIconFallback.DEFAULT_PERFECT_STYLE) in downloadedStyles)
    "perfect-sources" else "perfect"

@Composable
internal fun SystemIconFallbackCard(model: HomeViewModel, onOpenPerfectIcons: ((String) -> Unit)? = null) {
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val source by model.browserSource.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val apps by model.apps.collectAsStateWithLifecycle()
  val updateNeeded by model.iconPackUpdateNeeded.collectAsStateWithLifecycle()
  val perfect by model.perfectPacks.state.collectAsStateWithLifecycle()
  LaunchedEffect(model, perfect.clearingCache) {
    if (!perfect.clearingCache) model.perfectPacks.load()
  }
  val downloadedStyles = perfect.catalog?.styles.orEmpty()
  val choices = systemFallbackChoices(model.preferences.systemIconFallbackChoices, downloadedStyles,
    preferences.perfectFallbackExpanded, preferences.systemIconFallback)
  val resourceRoute = perfectFallbackResourceRoute(preferences.systemIconFallback, downloadedStyles)
  val resourcesAvailable = resourceRoute == "perfect-sources"
  val missingResources = preferences.systemIconFallback.let {
    if (it.isPerfect) it.perfectStyle !in downloadedStyles
    else it !in model.preferences.systemIconFallbackChoices
  }
  val pack = model.currentFallbackSource(runtime.report?.iconTheme, state, preferences.colorOsComponents)
  val busy = state.nativeImporting != null
  val result = state.nativeResult?.takeIf { pack != null &&
    it.sourceId == preferences.systemIconFallback.sourceId(pack.id) }
  Card(Modifier.fillMaxWidth().animateContentSize(tween(180)), insideMargin = PaddingValues(0.dp)) {
    Column {
      OverlaySpinnerPreference(title = stringResource(R.string.system_fallback_title),
        summary = stringResource(R.string.system_fallback_summary),
        items = choices.map { DropdownItem(text = systemFallbackName(it)) },
        selectedIndex = choices.indexOf(preferences.systemIconFallback).coerceAtLeast(0),
        onSelectedIndexChange = { model.setSystemIconFallback(choices[it]) },
        enabled = !busy, modifier = Modifier.testTag("icon-packs.system-fallback"))
      SwitchPreference(title = stringResource(R.string.system_fallback_perfect_expanded_title),
        summary = stringResource(R.string.system_fallback_perfect_expanded_summary),
        checked = preferences.perfectFallbackExpanded, enabled = !busy,
        onCheckedChange = model.preferences::setPerfectFallbackExpanded,
        modifier = Modifier.testTag("icon-packs.perfect-fallback-expanded"))
      BasicComponent(title = stringResource(if (resourcesAvailable)
        R.string.system_fallback_perfect_source_title else R.string.system_fallback_perfect_download_title),
        summary = stringResource(if (resourcesAvailable)
          R.string.system_fallback_perfect_source_summary else R.string.system_fallback_perfect_download_summary),
        enabled = onOpenPerfectIcons != null,
        onClick = { onOpenPerfectIcons?.invoke(resourceRoute) },
        endActions = { Icon(MiuixIcons.Basic.ArrowRight, null,
          tint = MiuixTheme.colorScheme.onSurfaceSecondary) },
        modifier = Modifier.testTag("icon-packs.perfect-fallback-resources"))
      SwitchPreference(title = stringResource(R.string.system_name_matching_title),
        summary = stringResource(R.string.system_name_matching_summary),
        checked = preferences.systemAppNameMatching, enabled = !busy,
        onCheckedChange = model.preferences::setSystemAppNameMatching,
        modifier = Modifier.testTag("icon-packs.system-name-matching"))
      SwitchPreference(title = stringResource(R.string.coloros_other_packs_title),
        summary = stringResource(R.string.coloros_other_packs_summary),
        checked = preferences.colorOsForOtherPacks, enabled = !busy,
        onCheckedChange = model.preferences::setColorOsForOtherPacks,
        modifier = Modifier.testTag("icon-packs.coloros-fallback"))
      Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val applied = source?.systemFallback
        if (applied != null || updateNeeded) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              if (applied != null) MetadataPill(stringResource(R.string.system_fallback_applied,
                systemFallbackName(applied.style), applied.names.size), MiuixTheme.colorScheme.primary)
              source?.generation?.colorOsPackages?.size?.let { count ->
                if (count > 0) MetadataPill(stringResource(R.string.coloros_fallback_applied, count), MiuixTheme.colorScheme.primary)
              }
              if (pendingNativeApplication(state, runtime.report?.iconTheme?.localId, preferences.systemIconFallback) != null) {
                MetadataPill(stringResource(R.string.pack_pending_apply), MiuixTheme.colorScheme.primary)
              } else if (updateNeeded) {
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
        if (missingResources && !perfect.loading) {
          Text(stringResource(if (preferences.systemIconFallback.isPerfect)
            R.string.system_fallback_perfect_unavailable else R.string.system_fallback_resources_unavailable,
            systemFallbackName(preferences.systemIconFallback)),
            modifier = Modifier.testTag("icon-packs.system-fallback-unavailable"),
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        if (pack == null && !state.loading && state.scanned &&
          runtime.report?.iconTheme?.source == os.kei.hyper.home.status.SystemIconThemeReport.Source.THEME) {
          Text(stringResource(R.string.system_fallback_source_needed),
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        if (result?.imported == true) NativeThemeLink(result, preferences.autoApplyIconPack, enabled = !busy)
        if (result != null && !result.imported) {
          Text(stringResource(if (result.pending) R.string.icon_pack_native_uncertain
            else R.string.icon_pack_native_failed), style = MiuixTheme.textStyles.footnote2,
            color = if (result.pending) MiuixTheme.colorScheme.onSurfaceSecondary else MiuixTheme.colorScheme.error)
          if (result.pending) TextButton(stringResource(R.string.icon_pack_recheck),
            onClick = model::recheckNativeIconImport)
        }
      }
    }
  }
}
