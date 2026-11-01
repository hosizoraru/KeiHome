package os.kei.hyper.home.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libxposed.service.HookedTarget
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.IconPackPreviews
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.status.FrameworkTarget
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.status.NativeIconImportReport
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun IconPacksPage(model: HomeViewModel, listState: LazyListState, padding: PaddingValues, modifier: Modifier) {
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val themeEffects by model.themeEffects.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val activeId = runtime.report?.takeIf { it.isFresh(android.os.SystemClock.elapsedRealtime()) &&
    !runtime.readFailed && !runtime.timedOut }?.iconTheme?.localId
  val importReady = framework.connected && framework.snapshot?.themesInScope == true &&
    runtime.report?.moduleVersion == os.kei.hyper.home.BuildConfig.VERSION_CODE.toLong() &&
    !runtime.readFailed && !runtime.timedOut
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) model.importIconPack(uri)
  }
  LazyColumn(state = listState, modifier = modifier, contentPadding = padding,
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item(key = "native", contentType = "native") { SystemIconThemeCard(runtime) }
    item(key = "theme-effects", contentType = "theme-effects") {
      ThemeEffectsCard(themeEffects, framework, model::setPreserveSystemMaterials)
    }
    item(key = "appearance", contentType = "appearance") {
      IconPackAppearancePreference(preferences.iconPackAppearance, model.preferences::setIconPackAppearance,
        enabled = state.nativeImporting == null)
    }
    item(key = "import", contentType = "import") {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.icon_pack_import_title), style = MiuixTheme.textStyles.body1)
          Text(stringResource(R.string.icon_pack_import_summary), style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
          if (framework.snapshot?.themesInScope != true) Text(stringResource(R.string.icon_pack_scope_needed),
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
          TextButton(stringResource(if (state.importing) R.string.icon_pack_importing else R.string.icon_pack_choose_file),
            onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.importing)
          state.importName?.let { name ->
            Text(stringResource(R.string.icon_pack_imported_name, name), style = MiuixTheme.textStyles.footnote1)
          }
        }
      }
    }
    item(key = "summary", contentType = "summary") {
      Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.icon_pack_library), Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
          if (state.loading) CircularProgressIndicator(Modifier.size(18.dp))
        }
        if (state.scanned && state.packs.isEmpty()) Text(stringResource(R.string.icon_pack_empty),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (state.failedSources > 0) Text(stringResource(R.string.icon_pack_partial, state.failedSources),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        state.error?.let { Text(stringResource(R.string.icon_pack_read_failed, it),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
        RefreshFeedback(model, 3)
      }
    }
    items(state.packs, key = { it.pack.id }, contentType = { "pack" }) { card ->
      IconPackCard(card, model.packPreviews, preferences.iconPackAppearance, state.nativeAssociations[card.pack.id],
        activeId, state.nativeImporting == card.pack.id,
        importReady && state.nativeImporting == null,
        state.nativeResult?.takeIf { it.sourceId == card.pack.id },
        onImport = { model.importNativeIconPack(card.pack) }, onRecheck = model::recheckNativeIconImport)
    }
  }
}

@Composable
private fun IconPackAppearancePreference(
  appearance: IconPackAppearance,
  onChange: (IconPackAppearance) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val system = stringResource(R.string.icon_pack_appearance_system)
  val light = stringResource(R.string.icon_pack_appearance_light)
  val dark = stringResource(R.string.icon_pack_appearance_dark)
  val dim = stringResource(R.string.icon_pack_appearance_dim)
  val items = remember(system, light, dark, dim) {
    listOf(system, light, dark, dim).map { DropdownItem(text = it) }
  }
  Card(modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    Column {
      OverlaySpinnerPreference(
        title = stringResource(R.string.icon_pack_appearance_title),
        summary = stringResource(R.string.icon_pack_appearance_summary),
        items = items,
        selectedIndex = appearance.ordinal,
        onSelectedIndexChange = { onChange(IconPackAppearance.entries[it]) },
        enabled = enabled,
        modifier = Modifier.testTag("icon-packs.appearance"),
      )
      Text(stringResource(R.string.icon_pack_appearance_update_note),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}

@Composable
internal fun ThemeEffectsCard(
  state: ThemeEffectsUiState,
  framework: FrameworkUiState,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val snapshot = framework.snapshot.takeIf { framework.connected && !framework.readFailed }
  val inScope = framework.themeEffectsInScope()
  val available = inScope && !state.unavailable
  val enabled = available && state.savedChoice != null && !state.loading && !state.saving && !state.readFailed
  val targetStatuses = listOfNotNull(
    snapshot?.takeIf { it.systemInScope }?.let {
      stringResource(R.string.theme_effects_system_target_status, stringResource(themeEffectsTargetStatus(it.target)))
    },
    snapshot?.takeIf { it.systemUiInScope }?.let {
      stringResource(R.string.theme_effects_system_ui_target_status, stringResource(themeEffectsTargetStatus(it.systemUiTarget)))
    },
  )
  val scopeGuidance = when {
    snapshot == null || !inScope -> null
    !snapshot.systemUiInScope -> R.string.theme_effects_system_ui_scope_needed
    !snapshot.systemInScope -> R.string.theme_effects_system_scope_needed
    else -> null
  }
  val status = when {
    !framework.connected -> stringResource(R.string.theme_effects_disconnected)
    framework.readFailed -> stringResource(R.string.theme_effects_framework_failed)
    snapshot == null -> stringResource(R.string.theme_effects_loading)
    !inScope -> stringResource(R.string.theme_effects_scope_needed)
    state.unavailable -> stringResource(R.string.theme_effects_unavailable)
    state.saving -> stringResource(R.string.theme_effects_saving)
    state.saveFailed -> stringResource(R.string.theme_effects_save_failed)
    state.readFailed -> stringResource(R.string.theme_effects_read_failed)
    state.loading || state.savedChoice == null -> stringResource(R.string.theme_effects_loading)
    else -> stringResource(R.string.theme_effects_status,
      stringResource(if (state.savedChoice) R.string.theme_effects_saved_on else R.string.theme_effects_saved_off),
      targetStatuses.joinToString(" · "))
  }
  Card(modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    Column {
      SwitchPreference(
        title = stringResource(R.string.theme_effects_title),
        summary = stringResource(R.string.theme_effects_summary),
        checked = state.savedChoice == true,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.testTag("theme-effects.switch"),
      )
      Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(status, style = MiuixTheme.textStyles.footnote2,
          color = if (state.saveFailed || state.readFailed) MiuixTheme.colorScheme.error
            else MiuixTheme.colorScheme.onSurfaceSecondary)
        if (state.readFailed || state.saveFailed) {
          TextButton(stringResource(R.string.theme_effects_resave_off),
            onClick = { onCheckedChange(false) },
            enabled = available && state.canSaveThemeEffects(false),
            modifier = Modifier.testTag("theme-effects.resave-off"))
        }
        if (scopeGuidance != null) Text(stringResource(scopeGuidance),
          style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
  }
}

// Framework target state confirms module loading, not a native hook or preference ACK.
private fun themeEffectsTargetStatus(target: FrameworkTarget?): Int = when {
  target?.state == HookedTarget.State.FAILED -> R.string.theme_effects_target_failed
  target?.state == HookedTarget.State.UP_TO_DATE &&
    target.loadedVersionCode == BuildConfig.VERSION_CODE.toLong() -> R.string.theme_effects_target_loaded
  else -> R.string.theme_effects_target_pending
}

@Composable
private fun IconPackCard(card: IconPackCardData, previews: IconPackPreviewRepository,
  appearance: IconPackAppearance, nativeId: String?, activeId: String?, importing: Boolean, enabled: Boolean,
  result: NativeIconImportReport?, onImport: () -> Unit, onRecheck: () -> Unit) {
  val context = LocalContext.current
  var openFailed by rememberSaveable(card.pack.id) { mutableStateOf(false) }
  var expanded by rememberSaveable(card.pack.id) { mutableStateOf(false) }
  val pack = card.pack
  val native = pack.format == IconPackFormat.MIUI_MTZ || pack.format == IconPackFormat.MIUI_ICONS
  val previewAppearance = if (native) IconPackAppearance.SYSTEM else appearance
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(pack.displayName, style = MiuixTheme.textStyles.title4)
      Text(stringResource(if (pack.source is IconPackSource.Installed) R.string.icon_pack_installed else R.string.icon_pack_imported),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
      Text(stringResource(R.string.icon_pack_coverage, pack.entries.size, card.matchedApps),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      if (pack.colorOsProfile != null) Text(stringResource(R.string.icon_pack_coloros_originals, card.originalApps, card.dynamicApps),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      if (nativeId != null && nativeId == activeId) Text(stringResource(R.string.icon_pack_current),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
      if (card.previewEntries.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          card.previewEntries.forEach { entry ->
            PackIconPair(pack, entry, previews, previewAppearance, Modifier.weight(1f))
          }
        }
      }
      TextButton(stringResource(when {
        importing -> R.string.icon_pack_importing
        nativeId != null -> R.string.icon_pack_native_open
        else -> R.string.icon_pack_native_import
      }), onClick = {
        if (nativeId == null) onImport() else {
          openFailed = runCatching { context.startActivity(IconPackImportProtocol.nativeDetail(nativeId)) }.isFailure
        }
      }, enabled = nativeId != null || (enabled && (card.matchedApps > 0 ||
        pack.format == os.kei.hyper.home.iconpack.IconPackFormat.MIUI_MTZ ||
        pack.format == os.kei.hyper.home.iconpack.IconPackFormat.MIUI_ICONS)))
      if (result != null && !result.imported) {
        Text(stringResource(when (result.status) {
          "UNCERTAIN" -> R.string.icon_pack_native_uncertain
          "UNSUPPORTED" -> R.string.icon_pack_native_unsupported
          else -> R.string.icon_pack_native_failed
        }), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
        if (result.status == "UNCERTAIN") TextButton(stringResource(R.string.icon_pack_recheck), onClick = onRecheck)
      }
      if (result?.imported == true && result.convertedReady == false) {
        Text(stringResource(R.string.icon_pack_native_eligibility_failed), style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.error)
      }
      if (openFailed) Text(stringResource(R.string.icon_pack_native_failed),
        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
      TextButton(stringResource(if (expanded) R.string.icon_pack_hide_details else R.string.icon_pack_details),
        onClick = { expanded = !expanded })
      if (expanded) {
        if (nativeId != null) TextButton(stringResource(R.string.icon_pack_native_update),
          onClick = onImport, enabled = enabled)
        Text(stringResource(if (native) R.string.icon_pack_preview_note else R.string.icon_pack_appearance_preview_note),
          style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Text(stringResource(R.string.icon_pack_appearance_scope_note),
          style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (!native && appearance == IconPackAppearance.SYSTEM) {
          Text(stringResource(R.string.icon_pack_appearance_system_note),
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        if (!native && appearance == IconPackAppearance.DIM_AT_NIGHT) {
          Text(stringResource(R.string.icon_pack_appearance_dim_note),
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        Text(stringResource(if (native) R.string.icon_pack_native_note else R.string.icon_pack_appearance_snapshot_note),
          style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
        pack.description.author?.let { Text(it, style = MiuixTheme.textStyles.footnote1) }
        if (pack.notices.isNotEmpty()) Text(stringResource(R.string.icon_pack_effects_note),
          style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
  }
}

@Composable
private fun PackIconPair(pack: IconPackDescriptor, entry: IconPackEntry, repository: IconPackPreviewRepository,
  appearance: IconPackAppearance, modifier: Modifier) {
  val configuration = LocalConfiguration.current.toString()
  val preview by produceState<IconPackPreviews?>(null, pack.source, entry, configuration) {
    value = repository.load(pack, entry, 144)
  }
  val lightArtwork = if (appearance.sourceDarkMode(false)) preview?.dark else preview?.light
  val darkArtwork = if (appearance.sourceDarkMode(true)) preview?.dark else preview?.light
  val light = remember(lightArtwork) { lightArtwork?.asImageBitmap() }
  val dark = remember(darkArtwork) { darkArtwork?.asImageBitmap() }
  val nightFilter = remember(appearance) {
    if (appearance == IconPackAppearance.DIM_AT_NIGHT) {
      ColorFilter.lighting(Color(IconPackAppearance.DIM_COLOR_MULTIPLY).copy(alpha = 1f), Color.Black)
    } else null
  }
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    listOf(light to false, dark to true).forEach { (bitmap, isDark) ->
      Box(Modifier.size(50.dp).clip(RoundedCornerShape(12.dp))
        .background(if (isDark) Color(0xff232428) else Color(0xfff0f1f4)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, stringResource(if (isDark) R.string.icon_pack_dark_preview else R.string.icon_pack_light_preview),
          Modifier.size(40.dp), colorFilter = if (isDark) nightFilter else null)
        else Text("—", color = if (isDark) Color.White else Color.DarkGray)
      }
    }
  }
}
