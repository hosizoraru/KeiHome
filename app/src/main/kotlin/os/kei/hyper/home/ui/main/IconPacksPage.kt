package os.kei.hyper.home.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.style.TextOverflow
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
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.basic.ArrowRight

@Composable
internal fun IconPacksPage(model: HomeViewModel, listState: LazyListState, padding: PaddingValues,
  modifier: Modifier, section: String = "home", onNavigate: (String) -> Unit = {}) {
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val sort by model.packSort.collectAsStateWithLifecycle()
  val source by model.browserSource.collectAsStateWithLifecycle()
  val needsUpdate by model.iconPackUpdateNeeded.collectAsStateWithLifecycle()
  val pending = pendingNativeApplication(state, runtime.report?.iconTheme?.localId)
  var query by rememberSaveable(section) { mutableStateOf("") }
  val activeTheme = runtime.report?.takeIf { it.isFresh(android.os.SystemClock.elapsedRealtime()) &&
    !runtime.readFailed && !runtime.timedOut }?.iconTheme
  val activeId = activeTheme?.localId
  val importReady = framework.connected && framework.snapshot?.themesInScope == true &&
    runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong() && !runtime.readFailed && !runtime.timedOut
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) model.importIconPack(uri)
  }
  val activePackId = remember(activeId, state.nativeSources, state.nativeAssociations) {
    activeCatalogPackId(activeId, state)
  }
  val cards = remember(state.packs, section, activePackId, query, sort) {
    catalogPackRows(state, section, activePackId, query, sort.first, sort.second)
  }
  LazyColumn(state = listState, modifier = modifier, contentPadding = padding,
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    when (section) {
      "home" -> {
        item("native") {
          val badges = buildList {
            if (pending != null) add(stringResource(R.string.pack_pending_apply))
            else if (needsUpdate) add(stringResource(R.string.pack_update_available_short))
            source?.generation?.colorOsPackages?.size?.let { count ->
              if (count > 0) add(stringResource(R.string.coloros_fallback_applied, count))
            }
            val applied = source?.systemFallback
            add(if (applied != null) stringResource(R.string.system_fallback_applied,
              stringResource(systemFallbackLabel(applied.style)), applied.names.size)
            else stringResource(R.string.pack_completion_selected,
              stringResource(systemFallbackLabel(preferences.systemIconFallback))))
          }
          SystemIconThemeCard(runtime, badges) { model.refresh(3) }
        }
        if (activeTheme?.source == os.kei.hyper.home.status.SystemIconThemeReport.Source.DEFAULT) {
          item("system-appearance") {
            PackInfoCard(stringResource(R.string.pack_system_appearance_note),
              Modifier.testTag("icon-packs.system-appearance-note"))
          }
        }
        item("library") { PackNavigationCard(stringResource(R.string.icon_pack_library),
          stringResource(R.string.pack_library_summary), { onNavigate("library") }) }
        item("monet") { PackNavigationCard(stringResource(R.string.navigation_monet),
          stringResource(R.string.pack_monet_summary), { onNavigate("monet") }) }
        item("completion") {
          PackCompletionCard(needsUpdate, pending, state.nativeImporting != null) { onNavigate("fallback") }
        }
      }
      "library" -> {
        item("builtin") { PackNavigationCard(stringResource(R.string.pack_builtin),
          stringResource(R.string.pack_builtin_summary), { onNavigate("builtin") }) }
        item("installed") { PackNavigationCard(stringResource(R.string.pack_installed),
          stringResource(R.string.pack_installed_summary), { onNavigate("installed") }) }
        item("imported") { PackNavigationCard(stringResource(R.string.pack_imported),
          stringResource(R.string.pack_imported_summary), { onNavigate("imported") }) }
      }
      "builtin" -> {
        item("coloros") { PackNavigationCard("ColorOS", stringResource(R.string.pack_coloros_summary),
          { onNavigate("coloros") }) }
        item("hyperos") { HyperOsPackCard() }
        item("system-appearance") {
          PackInfoCard(stringResource(R.string.pack_system_appearance_note),
            Modifier.testTag("icon-packs.builtin-system-appearance-note"))
        }
      }
      "coloros" -> {
        item("settings") { ColorOsSettings(model, importReady) }
      }
      else -> {
        item("search") {
          InputField(query = query, onQueryChange = { query = it }, onSearch = {}, expanded = false,
            onExpandedChange = {}, modifier = Modifier.fillMaxWidth(), label = stringResource(R.string.icon_pack_search))
        }
        item("appearance") { IconPackAppearancePreference(preferences.iconPackAppearance,
          model.preferences::setIconPackAppearance, state.nativeImporting == null, compact = true) }
        items(cards, key = { it.pack.id }, contentType = { "pack" }) { card ->
          IconPackCard(card, model.packPreviews, preferences.iconPackAppearance,
            state.nativeAssociations[preferences.systemIconFallback.sourceId(card.pack.id)],
            card.pack.id == activePackId, state.nativeImporting == preferences.systemIconFallback.sourceId(card.pack.id),
            importReady && state.nativeImporting == null,
            state.nativeResult?.takeIf { it.sourceId == preferences.systemIconFallback.sourceId(card.pack.id) },
            onImport = { model.importNativeIconPack(card.pack) }, onRecheck = model::recheckNativeIconImport)
        }
        if (state.scanned && cards.isEmpty()) item("empty") {
          PackInfoCard(stringResource(if (query.isBlank()) R.string.icon_pack_empty else R.string.icon_pack_search_empty),
            Modifier.testTag("icon-packs.empty"))
        }
        item("import") {
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              if (section == "installed") Text(stringResource(R.string.icon_pack_apk_file_guidance),
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
              TextButton(stringResource(if (state.importing) R.string.icon_pack_importing else
                if (section == "installed") R.string.icon_pack_choose_apk_file else R.string.icon_pack_choose_file),
                onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.importing)
              state.importName?.let { Text(stringResource(R.string.icon_pack_imported_name, it)) }
              if (framework.snapshot?.themesInScope != true) Text(stringResource(R.string.icon_pack_scope_needed),
                style = MiuixTheme.textStyles.footnote2)
            }
          }
        }
      }
    }
    item("feedback") {
      IconPackRefreshFeedback(model)
    }
  }
}

@Composable
internal fun PackNavigationCard(title: String, summary: String, onClick: () -> Unit) {
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp), onClick = onClick, showIndication = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MiuixTheme.textStyles.body1)
        Text(summary, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
      top.yukonga.miuix.kmp.basic.Icon(top.yukonga.miuix.kmp.icon.MiuixIcons.Basic.ArrowRight, null,
        tint = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}

@Composable
internal fun IconPackAppearancePreference(
  appearance: IconPackAppearance,
  onChange: (IconPackAppearance) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
) {
  Card(modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    Column {
      IconPackAppearanceControl(appearance, onChange, enabled, compact, compactNote = compact)
      if (!compact) Text(stringResource(R.string.icon_pack_appearance_update_note),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}

@Composable
internal fun IconPackAppearanceControl(
  appearance: IconPackAppearance,
  onChange: (IconPackAppearance) -> Unit,
  enabled: Boolean,
  compact: Boolean = false,
  compactNote: Boolean = false,
) {
  val system = stringResource(R.string.icon_pack_appearance_system)
  val light = stringResource(R.string.icon_pack_appearance_light)
  val dark = stringResource(R.string.icon_pack_appearance_dark)
  val dim = stringResource(R.string.icon_pack_appearance_dim)
  val items = remember(system, light, dark, dim) {
    listOf(system, light, dark, dim).map { DropdownItem(text = it) }
  }
  OverlaySpinnerPreference(
    title = stringResource(R.string.icon_pack_appearance_title),
    summary = when {
      compactNote -> stringResource(R.string.icon_pack_appearance_short_note)
      compact -> null
      else -> stringResource(R.string.icon_pack_appearance_summary)
    },
    items = items,
    selectedIndex = appearance.ordinal,
    onSelectedIndexChange = { onChange(IconPackAppearance.entries[it]) },
    enabled = enabled,
    modifier = Modifier.testTag("icon-packs.appearance"),
  )
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
  appearance: IconPackAppearance, nativeId: String?, current: Boolean, importing: Boolean, enabled: Boolean,
  result: NativeIconImportReport?, onImport: () -> Unit, onRecheck: () -> Unit) {
  val context = LocalContext.current
  var openFailed by rememberSaveable(card.pack.id) { mutableStateOf(false) }
  var expanded by rememberSaveable(card.pack.id) { mutableStateOf(false) }
  val pack = card.pack
  val native = pack.format == IconPackFormat.MIUI_MTZ || pack.format == IconPackFormat.MIUI_ICONS
  val previewAppearance = if (native) IconPackAppearance.SYSTEM else appearance
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(pack.displayName, style = MiuixTheme.textStyles.body1, maxLines = 2, overflow = TextOverflow.Ellipsis)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val installed = pack.source as? IconPackSource.Installed
        val source = stringResource(if (installed != null) R.string.icon_pack_installed else R.string.icon_pack_imported)
        val version = installed?.versionName?.trim()?.takeIf { it.isNotEmpty() }?.take(24)
        MetadataPill(if (version == null) source else "$source · $version", MiuixTheme.colorScheme.onSurfaceSecondary)
        if (current) MetadataPill(stringResource(R.string.icon_pack_current),
          MiuixTheme.colorScheme.primary)
      }
      Text(stringResource(R.string.icon_pack_coverage, pack.entries.size, card.matchedApps),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      if (pack.colorOsProfile != null) Text(stringResource(R.string.icon_pack_coloros_originals, card.originalApps, card.dynamicApps),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      if (card.previewEntries.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          card.previewEntries.forEach { entry ->
            PackIconPair(pack, entry, previews, previewAppearance, Modifier.weight(1f), compact = true)
          }
        }
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PackInlineAction(stringResource(when {
          importing -> R.string.icon_pack_importing
          nativeId != null -> R.string.icon_pack_native_open
          else -> R.string.icon_pack_native_import
        }), onClick = {
          if (nativeId == null) onImport() else {
            openFailed = runCatching { context.startActivity(IconPackImportProtocol.nativeDetail(nativeId)) }.isFailure
          }
        }, enabled = nativeId != null || (enabled && (card.matchedApps > 0 ||
          pack.format == IconPackFormat.MIUI_MTZ || pack.format == IconPackFormat.MIUI_ICONS)))
        TextButton(stringResource(if (expanded) R.string.icon_pack_hide_details else R.string.icon_pack_details),
          onClick = { expanded = !expanded })
      }
      if (result != null && !result.imported) {
        Text(stringResource(when (result.status) {
          "IMPORTING", "UNCERTAIN" -> R.string.icon_pack_native_uncertain
          "UNSUPPORTED" -> R.string.icon_pack_native_unsupported
          else -> R.string.icon_pack_native_failed
        }), style = MiuixTheme.textStyles.footnote2,
          color = if (result.pending) MiuixTheme.colorScheme.onSurfaceSecondary else MiuixTheme.colorScheme.error)
        if (result.pending) TextButton(stringResource(R.string.icon_pack_recheck), onClick = onRecheck)
      }
      if (result?.imported == true && result.convertedReady == false) {
        Text(stringResource(R.string.icon_pack_native_eligibility_failed), style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.error)
      }
      if (openFailed) Text(stringResource(R.string.icon_pack_native_failed),
        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
      if (expanded) {
        if (nativeId != null) PackInlineAction(stringResource(R.string.icon_pack_native_update),
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
internal fun PackIconPair(pack: IconPackDescriptor, entry: IconPackEntry, repository: IconPackPreviewRepository,
  appearance: IconPackAppearance, modifier: Modifier, compact: Boolean = false) {
  val configuration = LocalConfiguration.current.toString()
  val preview by androidx.compose.runtime.key(pack.id, pack.source, entry, configuration, compact) {
    produceState<IconPackPreviews?>(null) { value = repository.load(pack, entry, if (compact) 96 else 144) }
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
      Box(Modifier.size(if (compact) 32.dp else 50.dp).clip(RoundedCornerShape(if (compact) 8.dp else 12.dp))
        .background(if (isDark) Color(0xff232428) else Color(0xfff0f1f4)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, stringResource(if (isDark) R.string.icon_pack_dark_preview else R.string.icon_pack_light_preview),
          Modifier.size(if (compact) 27.dp else 40.dp), colorFilter = if (isDark) nightFilter else null)
        else if (preview == null) CircularProgressIndicator(size = if (compact) 14.dp else 18.dp, strokeWidth = 2.dp)
        else Text("—", color = if (isDark) Color.White else Color.DarkGray)
      }
    }
  }
}

@Composable
internal fun PackStyleThumbnail(pack: IconPackDescriptor, entry: IconPackEntry,
  repository: IconPackPreviewRepository, appearance: IconPackAppearance) {
  val configuration = LocalConfiguration.current
  val dark = appearance.sourceDarkMode(configuration.uiMode and
    android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
  val preview by androidx.compose.runtime.key(pack.id, pack.source, entry, configuration.toString()) {
    produceState<IconPackPreviews?>(null) { value = repository.load(pack, entry, 144) }
  }
  val artwork = if (dark) preview?.dark else preview?.light
  val bitmap = remember(artwork) { artwork?.asImageBitmap() }
  Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
    if (bitmap != null) Image(bitmap, null, Modifier.size(50.dp))
    else if (preview == null) CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
  }
}
