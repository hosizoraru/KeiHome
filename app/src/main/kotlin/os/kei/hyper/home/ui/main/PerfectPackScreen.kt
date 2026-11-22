package os.kei.hyper.home.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Date
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.iconpack.perfect.PerfectIconOptions
import os.kei.hyper.home.iconpack.perfect.PerfectIconStyles
import os.kei.hyper.home.ui.components.BlurredBar
import top.yukonga.miuix.kmp.basic.BasicComponent
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.os4Clickable
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.Refresh
import os.kei.hyper.home.ui.components.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PerfectPackScreen(vm: HomeViewModel, onBack: () -> Unit, onOpenResources: () -> Unit,
  backdrop: LayerBackdrop?, bottomBar: @Composable () -> Unit) {
  val state by vm.perfectPacks.state.collectAsStateWithLifecycle()
  val packs by vm.iconPacks.collectAsStateWithLifecycle()
  val prefs by vm.preferences.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val behavior = MiuixScrollBehavior()
  var actionHeight by remember { mutableIntStateOf(0) }
  val actionBottom = maxOf(104.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp)
  val busy = state.busy || packs.preparing || packs.importing || packs.nativeImporting != null
  var localLoadRequested by rememberSaveable { mutableStateOf(false) }
  val localLoading = state.loading || (!localLoadRequested && state.catalog == null)
  val cooling = state.retryAtMillis > System.currentTimeMillis()
  LaunchedEffect(vm) { localLoadRequested = true }
  Scaffold(backdrop = backdrop,
    modifier = Modifier.testTag("icon-packs.perfect"),
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(
          title = stringResource(R.string.perfect_icons_title),
          scrollBehavior = behavior,
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          navigationIcon = {
            IconButton(onClick = onBack) {
              Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
            }
          },
          actions = {
            PackHelpAction(listOf(
              stringResource(R.string.perfect_git_overview_summary),
              stringResource(R.string.perfect_icons_limits_summary),
              stringResource(R.string.generated_pack_retention_note),
            ), page = os.kei.hyper.home.data.PackGuidancePage.PERFECT)
            IconButton(onClick = { vm.perfectPacks.checkUpdates() }, enabled = !busy && !cooling,
              modifier = Modifier.testTag("icon-packs.perfect.check-updates")) {
              Icon(MiuixIcons.Glass.Refresh, stringResource(R.string.perfect_git_check))
            }
          },
        )
      }
    },
    bottomBar = bottomBar,
    floatingActionButton = {
      PageActionFab(stringResource(if (state.generating) R.string.action_label_processing
        else R.string.action_label_generate), actionIcon(PageActionIcon.GENERATE),
        description = stringResource(R.string.perfect_icons_generate),
        onClick = vm.perfectPacks::generate, enabled = !busy && state.ready,
        busy = state.generating, onHeightChanged = { actionHeight = it },
        modifier = Modifier.testTag("icon-packs.perfect.generate"))
    },
  ) { padding ->
    LazyColumn(overscrollEffect = null, modifier = Modifier.fillMaxSize()
      .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
      .keiHomeTopBarScroll(behavior), contentPadding = PaddingValues(
      top = padding.calculateTopPadding() + 8.dp,
      bottom = padding.calculateBottomPadding() + actionBottom)) {
      item("status") {
        val catalog = state.catalog
        val downloadedCount = catalog?.downloadedStyles?.size ?: 0
        val style = state.options.style.takeUnless { it.isBlank() || it == "default" }
          ?: stringResource(R.string.perfect_icons_style_default)
        StatusOverviewCard(
          title = stringResource(when {
            state.generating -> R.string.perfect_icons_generating
            state.refreshing -> R.string.perfect_git_syncing
            state.checking -> R.string.perfect_git_checking
            state.clearingCache -> R.string.perfect_icons_clearing_cache
            state.deletingResources -> R.string.perfect_git_deleting
            localLoading -> R.string.perfect_icons_loading
            state.error != null || state.syncIssue == PerfectSyncIssue.CORRUPT -> R.string.perfect_icons_attention
            state.updateAvailable -> R.string.perfect_git_update_available
            downloadedCount > 0 && !state.ready -> R.string.perfect_local_available
            catalog != null && !state.ready -> R.string.perfect_git_style_missing
            state.syncIssue != null -> if (state.ready) R.string.perfect_icons_catalog_ready
              else R.string.perfect_icons_catalog_missing
            state.remote != null && state.ready -> R.string.perfect_icons_catalog_ready
            state.pack != null -> R.string.perfect_icons_ready
            catalog != null -> R.string.perfect_icons_catalog_ready
            else -> R.string.perfect_icons_catalog_missing
          }),
          summary = when {
            localLoading -> stringResource(R.string.pack_local_catalog_loading)
            downloadedCount > 0 && !state.ready -> stringResource(R.string.perfect_local_selection_missing, downloadedCount, style)
            !state.ready -> stringResource(R.string.perfect_git_overview_summary)
            else -> stringResource(R.string.perfect_icons_preview_style, style)
          },
          details = emptyList(),
          tone = when {
            state.error != null || state.syncIssue == PerfectSyncIssue.CORRUPT -> StatusTone.ERROR
            state.syncIssue != null -> StatusTone.WARNING
            !state.ready || state.updateAvailable || busy -> StatusTone.WARNING
            else -> StatusTone.READY
          },
          onRefresh = { if (!busy && !cooling) vm.perfectPacks.checkUpdates() },
          onOpen = onOpenResources.takeIf { !state.ready && !localLoading },
          actionLabel = stringResource(R.string.perfect_git_resources),
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize()
            .testTag("icon-packs.perfect.status"),
          badgeContent = if (catalog == null && state.syncIssue == null) null else { color ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp)) {
              PerfectSyncBadges(state, color, showRemote = false, showSuccess = false)
              if (catalog != null) {
                MetadataPill(stringResource(R.string.perfect_resource_downloaded_count, downloadedCount), MiuixTheme.colorScheme.primary)
                if (!state.ready) MetadataPill(stringResource(R.string.perfect_git_not_downloaded), color,
                  Modifier.testTag("icon-packs.perfect.style-readiness"))
                MetadataPill(stringResource(R.string.perfect_icons_catalog_count, catalog.iconCount), color)
                val revision = catalog.revision.ifEmpty { catalog.sourceHash }
                val copyLabel = stringResource(if (catalog.revision.matches(Regex("[0-9a-f]{40}")))
                  R.string.perfect_icons_revision else R.string.perfect_icons_snapshot_hash)
                MetadataPill(stringResource(R.string.perfect_icons_revision_short, revision.take(7)), color,
                  Modifier.testTag("icon-packs.perfect.revision").os4Clickable(onClickLabel = copyLabel) {
                    copyPerfectRevision(context, revision, copyLabel)
                  })
              }
            }
          },
        )
        PerfectDownloadStatusCard(state)
        if ((state.busy && !state.refreshing) || localLoading || state.error != null || cooling) {
          Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              if (state.busy || localLoading) {
                val progress = state.progress
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(),
                  progress = progress?.takeIf { it.total > 0 }?.let {
                    (it.done.toFloat() / it.total).coerceIn(0f, 1f)
                  })
                progress?.let {
                  Text(if (state.checking) stringResource(R.string.perfect_git_checking)
                  else if (it.label.startsWith("source.git:")) stringResource(
                    R.string.perfect_icons_download_progress, Formatter.formatFileSize(context,
                      it.label.substringAfter("source.git:").toLongOrNull()?.coerceAtLeast(0) ?: 0))
                  else when (it.label) {
                    "source.download" -> stringResource(R.string.perfect_icons_download_progress,
                      Formatter.formatFileSize(context, it.done.coerceAtLeast(0).toLong()))
                    "source.verify" -> stringResource(R.string.perfect_icons_verifying)
                    "source.check", "source.tree" -> stringResource(R.string.perfect_git_checking)
                    else -> stringResource(R.string.perfect_icons_progress,
                      it.done.coerceAtLeast(0), it.total.coerceAtLeast(0))
                  }, style = MiuixTheme.textStyles.footnote2)
                  if (!it.label.startsWith("source.") && it.label.isNotBlank()) Text(it.label.take(160),
                    style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
              }
              state.error?.let { Text(stringResource(perfectErrorResource(it)),
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
              if (cooling) Text(stringResource(R.string.perfect_icons_cooldown_detail,
                DateFormat.getTimeFormat(context).format(Date(state.retryAtMillis))),
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
          }
        }
      }
      item("connection") {
        Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
          PerfectConnectionSettings(enabled = !busy, onSaved = { vm.perfectPacks.checkUpdates() })
        }
      }
      item("options") {
        val availableStyles = ((state.remote?.availableStyles?.takeIf { it.isNotEmpty() }
          ?: state.catalog?.styles?.takeIf { it.isNotEmpty() }
          ?: listOf("default", SystemIconFallback.DEFAULT_PERFECT_STYLE)) +
          state.catalog?.downloadedStyles.orEmpty()).map { it.ifEmpty { "default" } }.distinct()
        PerfectPackOptions(state.options, availableStyles, state.catalog?.downloadedStyles.orEmpty(),
          !busy, vm.perfectPacks::setOptions)
      }
      item("generated") {
        state.pack?.let { card ->
          val runtime by vm.runtime.collectAsStateWithLifecycle()
          val framework by vm.framework.collectAsStateWithLifecycle()
          val ready = framework.connected && framework.snapshot?.themesInScope == true &&
            runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong() &&
            !runtime.readFailed && !runtime.timedOut
          Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sourceId = prefs.systemIconFallback.sourceId(card.pack.id)
            val activeId = runtime.report?.iconTheme?.localId
            IconPackCard(card, vm.packPreviews, prefs.iconPackAppearance,
              packs.nativeAssociations[sourceId], card.pack.id == activeCatalogPackId(activeId, packs),
              packs.nativeImporting == sourceId, ready && !busy,
              packs.nativeResult?.takeIf { it.sourceId == sourceId },
              onImport = { vm.importNativeIconPack(card.pack) }, onRecheck = vm::recheckNativeIconImport,
              manualThemeFallback = prefs.manualThemeFallback,
              metadataContent = {
                state.generatedOptions?.let { generated ->
                  MetadataPill(stringResource(if (generated.staticNight) R.string.perfect_icons_night_mode_derived
                    else R.string.perfect_icons_night_mode_original), MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                state.result?.let { result ->
                  listOf(
                    R.string.perfect_icons_result_static to result.staticCount,
                    R.string.perfect_icons_result_dynamic to result.dynamicCount,
                    R.string.perfect_icons_result_extracted to result.downloaded,
                    R.string.perfect_icons_result_reused to result.reused,
                  ).forEach { (label, count) ->
                    MetadataPill(stringResource(label, count), MiuixTheme.colorScheme.onSurfaceSecondary)
                  }
                  if (result.rejectedDynamic > 0) MetadataPill(
                    stringResource(R.string.perfect_icons_layer_fallback, result.rejectedDynamic),
                    MiuixTheme.colorScheme.primary)
                  if (result.derivedDynamicCount > 0) MetadataPill(
                    stringResource(R.string.perfect_icons_result_dynamic_night, result.derivedDynamicCount),
                    MiuixTheme.colorScheme.onSurfaceSecondary)
                  if (result.needsContractUpdate(state.generatedOptions ?: state.options))
                    MetadataPill(stringResource(R.string.perfect_icons_layer_regenerate), MiuixTheme.colorScheme.primary)
                }
                if (state.generatedOptions != null && state.generatedOptions != state.options) {
                  Column(Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetadataPill(stringResource(R.string.perfect_icons_options_changed), MiuixTheme.colorScheme.primary)
                    Text(stringResource(R.string.perfect_icons_options_changed_summary),
                      style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                  }
                }
              })
          }
        }
      }
    }
  }
}

@Composable
internal fun PerfectPackOptions(options: PerfectIconOptions, catalogStyles: List<String>?,
  downloadedStyles: List<String>, enabled: Boolean, onOptionsChange: (PerfectIconOptions) -> Unit) {
  Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
    val styles = remember(catalogStyles, options.style, downloadedStyles) {
      PerfectIconStyles.ordered(listOf("") + catalogStyles.orEmpty() + options.style, downloadedStyles)
        .map { it.takeUnless { style -> style == "default" }.orEmpty() }
    }
    val defaultStyle = stringResource(R.string.perfect_icons_style_default)
    val downloaded = stringResource(R.string.perfect_git_downloaded)
    val notDownloaded = stringResource(R.string.perfect_git_not_downloaded)
    val styleItems = remember(styles, downloadedStyles, defaultStyle, downloaded, notDownloaded) {
      styles.map { style ->
        val available = downloadedStyles.any { it.ifEmpty { "default" } == style.ifEmpty { "default" } }
        DropdownItem(text = "${style.ifEmpty { defaultStyle }} · ${if (available) downloaded else notDownloaded}")
      }
    }
    OverlaySpinnerPreference(title = stringResource(R.string.perfect_icons_style),
      items = styleItems,
      selectedIndex = styles.indexOf(options.style.takeUnless { it == "default" }.orEmpty())
        .coerceAtLeast(0),
      enabled = enabled && styles.size > 1,
      onSelectedIndexChange = { index -> styles.getOrNull(index)?.let {
        onOptionsChange(options.copy(style = it))
      } })
    SwitchPreference(title = stringResource(R.string.perfect_icons_dynamic),
      summary = stringResource(R.string.perfect_icons_dynamic_summary),
      checked = options.dynamic, enabled = enabled,
      modifier = Modifier.testTag("perfect.options.dynamic"),
      onCheckedChange = { onOptionsChange(options.copy(dynamic = it)) })
    SwitchPreference(title = stringResource(R.string.perfect_icons_static_night),
      summary = stringResource(if (options.staticNight) R.string.perfect_icons_night_enabled else R.string.perfect_icons_night_disabled),
      checked = options.staticNight, enabled = enabled,
      modifier = Modifier.testTag("perfect.options.night"),
      onCheckedChange = { onOptionsChange(options.copy(staticNight = it)) })
    SwitchPreference(title = stringResource(R.string.perfect_icons_installed),
      summary = stringResource(R.string.perfect_icons_installed_summary),
      checked = options.installedOnly, enabled = enabled,
      onCheckedChange = { onOptionsChange(options.copy(installedOnly = it)) })
  }
}

@Composable
internal fun perfectSyncTime(millis: Long): String {
  val context = LocalContext.current
  return if (millis > 0) {
    val date = Date(millis)
    "${DateFormat.getMediumDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
  } else stringResource(R.string.perfect_icons_updated_unknown)
}

private fun copyPerfectRevision(context: android.content.Context, revision: String, label: String) {
  context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, revision))
}

@Composable
internal fun PerfectResourceConfirmations(state: PerfectPackState, busy: Boolean,
  confirmDelete: Boolean, confirmClearCache: Boolean,
  onDismissDelete: () -> Unit, onDismissClearCache: () -> Unit,
  onDelete: () -> Unit, onClearCache: () -> Unit) {
  val context = LocalContext.current
  Os4ActionDialog(show = confirmDelete,
    title = stringResource(R.string.perfect_git_delete),
    summary = stringResource(R.string.perfect_git_delete_confirm,
      Formatter.formatFileSize(context, state.resourceBytes)),
    onDismissRequest = onDismissDelete, icon = actionIcon(PageActionIcon.CLEAR),
    actions = listOf(
      Os4DialogAction(stringResource(R.string.logs_cancel), onClick = onDismissDelete,
        tag = "icon-packs.perfect.cancel-delete"),
      Os4DialogAction(stringResource(R.string.perfect_git_delete_proceed),
        enabled = confirmDelete && !busy, onClick = {
          if (confirmDelete && !busy) { onDismissDelete(); onDelete() }
        }, role = GlassDialogButtonRole.Destructive, tag = "icon-packs.perfect.confirm-delete")))
  Os4ActionDialog(show = confirmClearCache,
    title = stringResource(R.string.perfect_icons_clear_cache),
    summary = stringResource(R.string.perfect_git_clear_cache_confirm,
      Formatter.formatFileSize(context, state.cacheBytes)),
    onDismissRequest = onDismissClearCache, icon = actionIcon(PageActionIcon.CLEAR),
    actions = listOf(
      Os4DialogAction(stringResource(R.string.logs_cancel), onClick = onDismissClearCache,
        tag = "icon-packs.perfect.cancel-clear-cache"),
      Os4DialogAction(stringResource(R.string.perfect_icons_clear_cache),
        enabled = confirmClearCache && !busy, onClick = {
          if (confirmClearCache && !busy) { onDismissClearCache(); onClearCache() }
        }, role = GlassDialogButtonRole.Destructive, tag = "icon-packs.perfect.confirm-clear-cache")))
}

@Composable
internal fun PerfectResourceStorageCard(state: PerfectPackState, busy: Boolean,
  onRequestClearCache: () -> Unit, onRequestDelete: () -> Unit) {
  val context = LocalContext.current
  Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
    BasicComponent(title = stringResource(R.string.perfect_icons_clear_cache),
      summary = stringResource(R.string.perfect_icons_clear_cache_summary),
      endActions = { MetadataPill(Formatter.formatFileSize(context, state.cacheBytes),
        MiuixTheme.colorScheme.primary) },
      enabled = !busy && state.cacheBytes > 0, onClick = onRequestClearCache,
      modifier = Modifier.testTag("icon-packs.perfect.clear-cache"))
    BasicComponent(title = stringResource(R.string.perfect_git_delete),
      summary = stringResource(R.string.perfect_git_delete_size,
        Formatter.formatFileSize(context, state.resourceBytes)),
      enabled = !busy && (state.resourceBytes > 0 || state.catalog != null || state.remote != null ||
        state.syncIssue != null),
      onClick = onRequestDelete,
      modifier = Modifier.testTag("icon-packs.perfect.delete-resources"))
    if (state.clearingCache) LinearProgressIndicator(Modifier.fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp))
    state.clearedBytes?.let { bytes ->
      BasicComponent(title = stringResource(R.string.perfect_icons_cache_cleared,
        Formatter.formatFileSize(context, bytes)))
    }
    state.deletedBytes?.let { bytes ->
      BasicComponent(title = stringResource(R.string.perfect_git_deleted,
        Formatter.formatFileSize(context, bytes)))
    }
  }
}

internal fun perfectErrorResource(error: PerfectPackError): Int = when (error) {
  PerfectPackError.LOCAL -> R.string.perfect_icons_error_local
  PerfectPackError.CATALOG -> R.string.perfect_icons_error_catalog
  PerfectPackError.NETWORK -> R.string.perfect_icons_error_network
  PerfectPackError.CHECK -> R.string.perfect_git_check_failed
  PerfectPackError.STYLE_NEEDED -> R.string.perfect_git_style_needed
  PerfectPackError.COOLDOWN -> R.string.perfect_icons_error_cooldown
  PerfectPackError.INSTALLED -> R.string.perfect_icons_error_installed
  PerfectPackError.GENERATE -> R.string.perfect_icons_error_generate
  PerfectPackError.IMPORT -> R.string.perfect_icons_error_import
  PerfectPackError.EMPTY -> R.string.perfect_icons_error_empty
  PerfectPackError.CACHE -> R.string.perfect_icons_error_cache
  PerfectPackError.DELETE -> R.string.perfect_git_delete_failed
}
