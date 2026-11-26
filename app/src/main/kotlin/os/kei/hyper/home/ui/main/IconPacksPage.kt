package os.kei.hyper.home.ui.main

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libxposed.service.HookedTarget
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.Os4ExplainedSwitchPreference
import os.kei.hyper.home.ui.components.os4Clickable
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.IconPackPreviews
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.status.FrameworkTarget
import os.kei.hyper.home.status.openNativeThemeDetail
import os.kei.hyper.home.status.NativeIconImportReport
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import os.kei.hyper.home.ui.components.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.glass.ChevronForward

@Composable
internal fun IconPacksPage(model: HomeViewModel, listState: LazyListState, padding: PaddingValues,
  modifier: Modifier, section: String = "home", onNavigate: (String) -> Unit = {},
  filter: IconPackCatalogFilter = IconPackCatalogFilter(), onFilterReset: () -> Unit = {}) {
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val sort by model.packSort.collectAsStateWithLifecycle()
  val source by model.browserSource.collectAsStateWithLifecycle()
  val needsUpdate by model.iconPackUpdateNeeded.collectAsStateWithLifecycle()
  val pending = pendingNativeApplication(state, runtime.report?.iconTheme?.localId, preferences.systemIconFallback)
  var query by rememberSaveable(section) { mutableStateOf("") }
  val activeTheme = runtime.report?.takeIf { it.isFresh(android.os.SystemClock.elapsedRealtime()) &&
    !runtime.readFailed && !runtime.timedOut }?.iconTheme
  val activeId = activeTheme?.localId
  val importReady = framework.connected && framework.snapshot?.themesInScope == true &&
    runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong() && !runtime.readFailed && !runtime.timedOut
  val activePackId = remember(activeId, state.nativeSources, state.nativeAssociations) {
    activeCatalogPackId(activeId, state)
  }
  val projection = rememberIconPackCatalogProjection(state, section, activePackId, query, sort, filter)
  val cards = projection.cards
  val total = projection.total
  var removingPack by remember { mutableStateOf<IconPackDescriptor?>(null) }
  var confirmRemoval by remember { mutableStateOf(false) }
  val busy = state.importing || state.nativeImporting != null || state.removing != null
  val sectionLoading = state.isSectionLoading(section)
  LazyColumn(overscrollEffect = null, state = listState, modifier = modifier, contentPadding = padding,
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
            add(if (applied != null) stringResource(
              if (applied.style.isPerfect) R.string.perfect_completion_applied else R.string.system_fallback_applied,
              systemFallbackName(applied.style), applied.names.size)
            else stringResource(R.string.pack_completion_selected,
              systemFallbackName(preferences.systemIconFallback)))
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
          PackNavigationCard(stringResource(R.string.pack_completion_title),
            stringResource(if (pending != null) R.string.pack_pending_apply_hint
              else if (needsUpdate) R.string.pack_completion_update_hint else R.string.pack_completion_hint),
            { onNavigate("fallback") })
        }
        item("settings") { PackNavigationCard(stringResource(R.string.pack_action_settings),
          stringResource(R.string.pack_action_settings_summary), { onNavigate("settings") }) }
      }
      "library" -> {
        item("builtin") { PackNavigationCard(stringResource(R.string.pack_builtin),
          stringResource(R.string.pack_builtin_summary), { onNavigate("builtin") }) }
        item("perfect") { PackNavigationCard(stringResource(R.string.perfect_icons_title),
          stringResource(R.string.perfect_icons_library_summary), { onNavigate("perfect") }) }
        item("apple") { PackNavigationCard(stringResource(R.string.apple_pack_title),
          stringResource(R.string.apple_pack_summary), { onNavigate("apple") }) }
        item("installed") { PackNavigationCard(stringResource(R.string.pack_installed),
          stringResource(R.string.pack_installed_summary), { onNavigate("installed") }) }
        item("imported") { PackNavigationCard(stringResource(R.string.pack_imported),
          stringResource(R.string.pack_imported_summary), { onNavigate("imported") }) }
      }
      "builtin" -> {
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
          Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Os4SearchField(query = query, onQueryChange = { query = it },
              modifier = Modifier.weight(1f), inputModifier = Modifier.testTag("packs.search"),
              label = stringResource(R.string.icon_pack_search))
            val description = stringResource(R.string.pack_list_result_count, cards.size, total)
            MetadataPill(if (cards.size == total) "$total" else "${cards.size}/$total",
              MiuixTheme.colorScheme.primary, Modifier.testTag("packs.count").clearAndSetSemantics {
                contentDescription = description
              })
          }
        }
        if (filter.isActive) item("filter-summary") {
          Row(Modifier.fillMaxWidth().testTag("icon-packs.filter-summary"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              if (filter.isActive) Text(packListFilterSummary(filter),
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
            }
            if (filter.isActive) TextButton(text = stringResource(R.string.pack_list_filter_reset),
              onClick = onFilterReset, modifier = Modifier.testTag("icon-packs.reset-filter"))
          }
        }
        item("appearance") { IconPackAppearancePreference(preferences.iconPackAppearance,
          model.preferences::setIconPackAppearance, state.nativeImporting == null, compact = true,
          staticAppearance = preferences.staticAppearanceIcons,
          onStaticAppearanceChange = model.preferences::setStaticAppearanceIcons) }
        if (!state.importing && state.importName != null) item("import-status") {
          PackInfoCard(stringResource(R.string.icon_pack_imported_name, state.importName.orEmpty()),
            Modifier.testTag("packs.file-import.status"))
        }
        items(cards, key = { it.pack.id }, contentType = { "pack" }) { card ->
          IconPackCard(card, model.packPreviews, preferences.iconPackAppearance,
            state.nativeAssociations[preferences.systemIconFallback.sourceId(card.pack.id)],
            card.pack.id == activePackId, state.nativeImporting == preferences.systemIconFallback.sourceId(card.pack.id),
            importReady && !busy,
            state.nativeResult?.takeIf { it.sourceId == preferences.systemIconFallback.sourceId(card.pack.id) },
            onImport = { model.importNativeIconPack(card.pack) }, onRecheck = model::recheckNativeIconImport,
            manualThemeFallback = preferences.manualThemeFallback,
            onRemove = if (card.pack.source is IconPackSource.Imported) ({
              removingPack = card.pack
              confirmRemoval = true
            }) else null, removeEnabled = !busy && !sectionLoading,
            removing = state.removing == card.pack.id)
        }
        if (!sectionLoading && (state.scanned || state.loading) && cards.isEmpty()) item("empty") {
          PackInfoCard(stringResource(if (filter.isActive) R.string.pack_list_filter_empty
            else if (query.isBlank()) R.string.icon_pack_empty else R.string.icon_pack_search_empty),
            Modifier.testTag("icon-packs.empty"))
        }
      }
    }
    item("feedback") {
      IconPackRefreshFeedback(model, section)
    }
  }
  Os4ActionDialog(confirmRemoval, { confirmRemoval = false },
    title = stringResource(R.string.pack_remove_title),
    summary = removingPack?.displayName.orEmpty(),
    icon = actionIcon(PageActionIcon.CLEAR),
    details = { Text(stringResource(R.string.pack_remove_message),
      style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary) },
    actions = listOf(
      Os4DialogAction(stringResource(R.string.logs_cancel), onClick = { confirmRemoval = false }),
      Os4DialogAction(stringResource(R.string.pack_remove_action), onClick = {
        confirmRemoval = false
        removingPack?.let(model::removeImportedIconPack)
      }, role = GlassDialogButtonRole.Destructive, enabled = confirmRemoval && !busy,
        tag = "packs.remove.confirm"),
    ))
}

@Composable
internal fun PackNavigationCard(title: String, summary: String, onClick: () -> Unit) {
  Card(Modifier.fillMaxWidth().os4Clickable(role = Role.Button, cornerRadius = CardDefaults.CornerRadius,
    onClick = onClick), insideMargin = PaddingValues(18.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MiuixTheme.textStyles.body1)
        Text(summary, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
      top.yukonga.miuix.kmp.basic.Icon(top.yukonga.miuix.kmp.icon.MiuixIcons.Glass.ChevronForward, null,
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
  staticAppearance: Boolean = false,
  onStaticAppearanceChange: ((Boolean) -> Unit)? = null,
) {
  Card(modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    Column {
      IconPackAppearanceControl(appearance, onChange, enabled, compact, compactNote = compact)
      if (appearance == IconPackAppearance.SYSTEM && onStaticAppearanceChange != null) {
        Os4ExplainedSwitchPreference(title = stringResource(R.string.icon_pack_static_appearance),
          summary = stringResource(R.string.icon_pack_static_appearance_summary),
          explanation = stringResource(R.string.icon_pack_static_appearance_help),
          checked = staticAppearance, enabled = enabled,
          tag = "icon-packs.static-appearance",
          onCheckedChange = onStaticAppearanceChange)
      }
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
  showStatus: Boolean = true,
) {
  val scopeChecking = framework.desktopScopeChecking()
  val snapshot = framework.snapshot.takeIf { framework.connected && !framework.readFailed && !scopeChecking }
  val inScope = framework.themeEffectsInScope()
  val available = inScope && !scopeChecking && !state.unavailable
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
    framework.readFailed -> stringResource(R.string.theme_effects_framework_failed)
    state.saving -> stringResource(R.string.theme_effects_saving)
    state.saveFailed -> stringResource(R.string.theme_effects_save_failed)
    state.readFailed -> stringResource(R.string.theme_effects_read_failed)
    scopeChecking -> stringResource(R.string.theme_effects_loading)
    !framework.connected -> stringResource(R.string.theme_effects_disconnected)
    snapshot == null -> stringResource(R.string.theme_effects_loading)
    !inScope -> stringResource(R.string.theme_effects_scope_needed)
    state.unavailable -> stringResource(R.string.theme_effects_unavailable)
    state.loading || state.savedChoice == null -> stringResource(R.string.theme_effects_loading)
    else -> stringResource(R.string.theme_effects_status,
      stringResource(if (state.savedChoice) R.string.theme_effects_saved_on else R.string.theme_effects_saved_off),
      targetStatuses.joinToString(" · "))
  }
  Card(modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    Column {
      Os4ExplainedSwitchPreference(
        title = stringResource(R.string.theme_effects_title),
        summary = stringResource(R.string.theme_effects_summary),
        explanation = stringResource(R.string.theme_effects_help),
        checked = state.savedChoice == true,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
        tag = "theme-effects.switch",
      )
      if (showStatus || state.readFailed || state.saveFailed || scopeGuidance != null) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)) {
          if (showStatus) Text(status, style = MiuixTheme.textStyles.footnote2,
            color = if (framework.readFailed || state.saveFailed || state.readFailed) MiuixTheme.colorScheme.error
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
      DesktopSettingProgress(if (state.saving) DesktopFeatureStatus.SAVING else DesktopFeatureStatus.ACCEPTED,
        Modifier.testTag("desktop.feature.icon-effects.progress"))
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
internal fun IconPackCard(card: IconPackCardData, previews: IconPackPreviewRepository,
  appearance: IconPackAppearance, nativeId: String?, current: Boolean, importing: Boolean, enabled: Boolean,
  result: NativeIconImportReport?, onImport: () -> Unit, onRecheck: () -> Unit,
  metadataContent: @Composable (() -> Unit)? = null,
  onRemove: (() -> Unit)? = null, removeEnabled: Boolean = true, removing: Boolean = false,
  manualThemeFallback: Boolean = false) {
  val context = LocalContext.current
  val pack = card.pack
  val generated = pack.source is IconPackSource.Generated || result?.convertedReady == false
  val native = pack.format == IconPackFormat.MIUI_MTZ || pack.format == IconPackFormat.MIUI_ICONS
  val previewAppearance = if (native) IconPackAppearance.SYSTEM else appearance
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val removingLabel = stringResource(R.string.pack_removing)
        Text(pack.displayName, style = MiuixTheme.textStyles.body1, maxLines = 2,
          overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onRemove != null) KeiHomeIconButton(onClick = onRemove, enabled = removeEnabled,
          modifier = Modifier.testTag("pack.remove.${pack.id}").semantics {
            if (removing) contentDescription = removingLabel
          }) {
          if (removing) CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp)
          else Icon(actionIcon(PageActionIcon.CLEAR), stringResource(R.string.pack_remove_named, pack.displayName),
            Modifier.size(22.dp), tint = MiuixTheme.colorScheme.error)
        }
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val installed = pack.source as? IconPackSource.Installed
        val source = stringResource(if (installed != null) R.string.icon_pack_installed else R.string.icon_pack_imported)
        val version = (installed?.versionName ?: pack.description.version)?.trim()?.takeIf { it.isNotEmpty() }?.take(40)
        MetadataPill(if (version == null) source else "$source · $version", MiuixTheme.colorScheme.onSurfaceSecondary)
        if (version == null && pack.source is IconPackSource.Imported && pack.format != IconPackFormat.APK) {
          MetadataPill(stringResource(R.string.icon_pack_version_unspecified), MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        (pack.source as? IconPackSource.Imported)?.record?.takeIf { pack.format != IconPackFormat.APK }?.let {
          MetadataPill(stringResource(R.string.icon_pack_local_storage_size,
            Formatter.formatFileSize(LocalContext.current, it.storedBytes)),
            MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        pack.description.author?.trim()?.takeIf { it.isNotEmpty() }?.let {
          MetadataPill(stringResource(R.string.pack_guidance_author, it), MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        pack.description.designer?.trim()?.takeIf { it.isNotEmpty() && it != pack.description.author?.trim() }?.let {
          MetadataPill(stringResource(R.string.pack_guidance_designer, it), MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        if (current) MetadataPill(stringResource(R.string.icon_pack_current),
          MiuixTheme.colorScheme.primary)
        metadataContent?.invoke()
      }
      Text(stringResource(R.string.icon_pack_coverage, pack.entries.size, card.matchedApps),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      if (pack.colorOsProfile != null) Text(stringResource(R.string.icon_pack_coloros_originals, card.originalApps, card.dynamicApps),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      val actionDescription = stringResource(when {
        nativeId != null && generated -> R.string.icon_pack_native_update
        nativeId != null -> R.string.icon_pack_native_open
        else -> R.string.icon_pack_native_import
      })
      val updateDescription = stringResource(R.string.icon_pack_native_update)
      Row(Modifier.fillMaxWidth().testTag("pack.preview.actions"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.weight(1f).testTag("pack.preview.strip")) {
          val count = ((maxWidth.value + 6f) / 38f).toInt().coerceIn(0, 6)
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            card.previewEntries.take(count).forEach { entry ->
              PackIconPair(pack, entry, previews, previewAppearance, Modifier.width(32.dp), compact = true)
            }
          }
        }
        Column(Modifier.widthIn(max = 192.dp).testTag("pack.actions"),
          horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
          PackActionButton(stringResource(when {
            importing -> R.string.pack_action_importing_short
            nativeId != null && generated -> R.string.pack_action_update_short
            nativeId != null -> R.string.pack_action_apply_short
            else -> R.string.pack_action_import_short
          }), onClick = {
            // Generated packs retain their source ID while their content and native theme record change.
            if (nativeId == null || generated) onImport() else {
              openNativeThemeDetail(context, nativeId)
            }
          }, modifier = Modifier.semantics { contentDescription = "${pack.displayName}: $actionDescription" },
            enabled = !importing && if (generated) enabled && card.matchedApps > 0
            else nativeId != null || (enabled && (card.matchedApps > 0 ||
              pack.format == IconPackFormat.MIUI_MTZ || pack.format == IconPackFormat.MIUI_ICONS)),
            primary = true, operation = when {
              nativeId == null -> PageActionIcon.IMPORT
              generated -> PageActionIcon.UPDATE
              else -> PageActionIcon.APPLY
            })
          if (!generated && nativeId != null) PackActionButton(stringResource(R.string.pack_action_update_short),
            modifier = Modifier.semantics { contentDescription = "${pack.displayName}: $updateDescription" },
            onClick = onImport, enabled = enabled && !importing)
        }
      }
      if (result != null && !result.imported) {
        Text(stringResource(result.failureLabel()), style = MiuixTheme.textStyles.footnote2,
          color = if (result.pending) MiuixTheme.colorScheme.onSurfaceSecondary else MiuixTheme.colorScheme.error)
        if (result.pending) TextButton(stringResource(R.string.icon_pack_recheck), onClick = onRecheck)
      }
      if (result?.imported == true && result.convertedReady == false) {
        NativeThemeLink(result, autoApply = false, enabled = !importing, manualFallback = manualThemeFallback)
      } else if (manualThemeFallback && generated && nativeId != null && (result == null || result.imported)) {
        NativeThemeDetailLink(nativeId, enabled = !importing)
      }
    }
  }
}

internal fun NativeIconImportReport.failureLabel(): Int = when (status) {
  "IMPORTING", "UNCERTAIN" -> R.string.icon_pack_native_uncertain
  "UNSUPPORTED" -> R.string.icon_pack_native_unsupported
  "PREPARATION_FAILED" -> R.string.icon_pack_native_preparation_failed
  else -> R.string.icon_pack_native_failed
}

@Composable
internal fun PackActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
  enabled: Boolean = true, primary: Boolean = false, compact: Boolean = true,
  operation: PageActionIcon = PageActionIcon.UPDATE) {
  val colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors()
  Button(onClick = onClick, modifier = modifier, enabled = enabled,
    minWidth = if (compact) 0.dp else ButtonDefaults.MinWidth,
    minHeight = if (compact) 48.dp else ButtonDefaults.MinHeight,
    insideMargin = if (compact) PaddingValues(12.dp) else ButtonDefaults.InsideMargin,
    colors = ButtonColors(color = colors.color, disabledColor = colors.disabledColor,
      contentColor = colors.textColor, disabledContentColor = colors.disabledTextColor)) {
    Icon(actionIcon(operation),
      null, Modifier.size(20.dp))
    Text(label, Modifier.padding(start = 8.dp),
      style = if (compact) MiuixTheme.textStyles.footnote1 else MiuixTheme.textStyles.button)
  }
}

@Composable
internal fun PackIconPair(pack: IconPackDescriptor, entry: IconPackEntry, repository: IconPackPreviewRepository,
  appearance: IconPackAppearance, modifier: Modifier, compact: Boolean = false, distinctOnly: Boolean = false) {
  val configuration = LocalConfiguration.current.toString()
  val maskRevision by repository.revision.collectAsStateWithLifecycle()
  val preview by androidx.compose.runtime.key(pack.id, pack.source, entry, configuration, compact, maskRevision) {
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
  Column(modifier.testTag("pack.preview.pair"), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp)) {
    val appearances = if (distinctOnly && entry.light == entry.dark) listOf(light to false)
      else listOf(light to false, dark to true)
    appearances.forEach { (bitmap, isDark) ->
      Box(Modifier.size(if (compact) 32.dp else 50.dp), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, stringResource(if (isDark) R.string.icon_pack_dark_preview else R.string.icon_pack_light_preview),
          Modifier.fillMaxSize(), colorFilter = if (isDark) nightFilter else null)
        else if (preview == null) CircularProgressIndicator(size = if (compact) 14.dp else 18.dp, strokeWidth = 2.dp)
        else Text("—", color = MiuixTheme.colorScheme.onSurfaceSecondary)
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
  val maskRevision by repository.revision.collectAsStateWithLifecycle()
  val preview by androidx.compose.runtime.key(pack.id, pack.source, entry, configuration.toString(), maskRevision) {
    produceState<IconPackPreviews?>(null) { value = repository.load(pack, entry, 144) }
  }
  val artwork = if (dark) preview?.dark else preview?.light
  val bitmap = remember(artwork) { artwork?.asImageBitmap() }
  Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
    if (bitmap != null) Image(bitmap, null, Modifier.size(50.dp))
    else if (preview == null) CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
  }
}
