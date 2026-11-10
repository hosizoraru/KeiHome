package os.kei.hyper.home.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.perfect.PerfectIconCatalog
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun PerfectPackScreen(vm: HomeViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
  val state by vm.perfectPacks.state.collectAsStateWithLifecycle()
  val packs by vm.iconPacks.collectAsStateWithLifecycle()
  val prefs by vm.preferences.state.collectAsStateWithLifecycle()
  val guidanceNotes = listOf(
    stringResource(R.string.perfect_icons_layer_rules),
    stringResource(R.string.perfect_icons_static_night_note),
    stringResource(R.string.pack_perfect_community_detail),
    stringResource(R.string.perfect_icons_style_summary),
    stringResource(R.string.perfect_icons_preview_summary),
    stringResource(R.string.perfect_icons_limits_summary),
    stringResource(R.string.icon_pack_preview_note),
    stringResource(R.string.icon_pack_appearance_scope_note),
    stringResource(R.string.icon_pack_native_note),
    stringResource(R.string.icon_pack_effects_note),
  )
  val runtime by vm.runtime.collectAsStateWithLifecycle()
  val framework by vm.framework.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  var actionHeight by remember { mutableIntStateOf(0) }
  val actionBottom = maxOf(104.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp)
  val busy = state.busy || packs.preparing || packs.importing || packs.nativeImporting != null
  var localLoadRequested by remember(vm) { mutableStateOf(false) }
  val localLoading = state.loading || (!localLoadRequested && state.catalog == null)
  val cooling = state.retryAtMillis > System.currentTimeMillis()
  val ready = framework.connected && framework.snapshot?.themesInScope == true &&
    runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong() && !runtime.readFailed && !runtime.timedOut
  LaunchedEffect(vm) {
    vm.perfectPacks.load()
    localLoadRequested = true
  }
  LaunchedEffect(vm, lifecycle, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      coroutineScope { launch { vm.observePage(3, locales) } }
    }
  }
  LaunchedEffect(state.retryAtMillis) {
    val remaining = state.retryAtMillis - System.currentTimeMillis()
    if (remaining > 0) delay(remaining)
    vm.perfectPacks.updateCooldown()
  }
  Scaffold(
    modifier = Modifier.testTag("icon-packs.perfect"),
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(
          title = stringResource(R.string.perfect_icons_title),
          scrollBehavior = behavior,
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          navigationIcon = {
            IconButton(onClick = onBack) {
              Icon(MiuixIcons.Back, stringResource(R.string.pack_back))
            }
          },
          actions = {
            if (prefs.compactPackHints) PackHelpAction(notes = guidanceNotes)
            IconButton(onClick = { vm.perfectPacks.refreshCatalog() }, enabled = !busy && !cooling) {
              Icon(MiuixIcons.Refresh, stringResource(R.string.perfect_icons_refresh))
            }
          },
        )
      }
    },
    floatingActionButton = {
      PageActionFab(stringResource(R.string.perfect_icons_generate), MiuixIcons.Theme,
        onClick = vm.perfectPacks::generate, enabled = !busy && state.catalog != null,
        busy = state.generating, onHeightChanged = { actionHeight = it },
        modifier = Modifier.testTag("icon-packs.perfect.generate"))
    },
  ) { padding ->
    LazyColumn(Modifier.fillMaxSize()
      .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
      .nestedScroll(behavior.nestedScrollConnection), contentPadding = PaddingValues(
      top = padding.calculateTopPadding() + 8.dp,
      bottom = padding.calculateBottomPadding() + actionBottom)) {
      item("status") {
        val catalog = state.catalog
        val style = state.options.style.takeUnless { it.isBlank() || it == "default" }
          ?: stringResource(R.string.perfect_icons_style_default)
        StatusOverviewCard(
          title = stringResource(when {
            state.generating -> R.string.perfect_icons_generating
            state.refreshing -> R.string.perfect_icons_syncing
            state.clearingCache -> R.string.perfect_icons_clearing_cache
            localLoading -> R.string.perfect_icons_loading
            state.error != null -> R.string.perfect_icons_attention
            catalog?.upToDate == true -> R.string.perfect_icons_up_to_date
            state.pack != null -> R.string.perfect_icons_ready
            catalog != null -> R.string.perfect_icons_catalog_ready
            else -> R.string.perfect_icons_catalog_missing
          }),
          summary = if (catalog != null) stringResource(R.string.perfect_icons_preview_style, style)
            else stringResource(if (localLoading) R.string.pack_local_catalog_loading
              else R.string.pack_perfect_community_summary),
          details = emptyList(),
          tone = when {
            state.error != null -> StatusTone.ERROR
            catalog == null || busy -> StatusTone.WARNING
            else -> StatusTone.READY
          },
          onRefresh = { if (!busy && !cooling) vm.perfectPacks.refreshCatalog() },
          onOpen = { onNavigate("perfect-sources") },
          actionLabel = stringResource(R.string.perfect_icons_sources_storage),
          footer = catalog?.let { catalogSummary(it) },
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize()
            .testTag("icon-packs.perfect.status"),
          badgeContent = if (catalog == null) null else { color ->
            MetadataPill(stringResource(R.string.perfect_icons_catalog_count, catalog.iconCount), color)
            val revision = catalog.revision.ifEmpty { catalog.sourceHash }
            val copyLabel = stringResource(if (catalog.revision.matches(Regex("[0-9a-f]{40}")))
              R.string.perfect_icons_revision else R.string.perfect_icons_snapshot_hash)
            MetadataPill(stringResource(R.string.perfect_icons_revision_short, revision.take(7)), color,
              Modifier.testTag("icon-packs.perfect.revision").clickable(onClickLabel = copyLabel) {
                copyPerfectRevision(context, revision, copyLabel)
              })
          },
        )
        if (catalog == null || busy || localLoading || state.error != null || cooling) {
          Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              if (state.catalog == null && !state.busy && !localLoading) Text(stringResource(R.string.perfect_icons_sync_first),
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
              if (state.busy || localLoading) {
                val progress = state.progress
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(),
                  progress = progress?.takeIf { it.total > 0 }?.let {
                    (it.done.toFloat() / it.total).coerceIn(0f, 1f)
                  })
                progress?.let {
                  Text(when (it.label) {
                    "source.download" -> stringResource(R.string.perfect_icons_download_progress,
                      Formatter.formatFileSize(context, it.done.coerceAtLeast(0).toLong()))
                    "source.verify" -> stringResource(R.string.perfect_icons_verifying)
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
      if (!prefs.compactPackHints) item("guidance") {
        PackGuidanceCard(notes = guidanceNotes, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
      }
      item("options") {
        Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
          val styles = listOf("") + state.catalog?.styles.orEmpty()
            .filter { it.isNotBlank() && it != "default" }.distinct()
          val defaultStyle = stringResource(R.string.perfect_icons_style_default)
          OverlaySpinnerPreference(title = stringResource(R.string.perfect_icons_style),
            items = styles.map { DropdownItem(text = it.ifEmpty { defaultStyle }) },
            selectedIndex = styles.indexOf(state.options.style.takeUnless { it == "default" }.orEmpty())
              .coerceAtLeast(0),
            enabled = !busy && state.catalog != null,
            onSelectedIndexChange = { index -> styles.getOrNull(index)?.let {
              vm.perfectPacks.setOptions(state.options.copy(style = it))
            } })
          BasicComponent(title = stringResource(R.string.perfect_icons_preview_title),
            summary = stringResource(R.string.perfect_icons_preview_row_summary),
            enabled = !busy && state.catalog != null,
            onClick = { onNavigate("perfect-preview") },
            modifier = Modifier.testTag("icon-packs.perfect.preview"))
          SwitchPreference(title = stringResource(R.string.perfect_icons_installed),
            summary = stringResource(R.string.perfect_icons_installed_summary),
            checked = state.options.installedOnly, enabled = !busy,
            onCheckedChange = { vm.perfectPacks.setOptions(state.options.copy(installedOnly = it)) })
          SwitchPreference(title = stringResource(R.string.perfect_icons_dynamic),
            summary = stringResource(R.string.perfect_icons_dynamic_summary),
            checked = state.options.dynamic, enabled = !busy,
            onCheckedChange = { vm.perfectPacks.setOptions(state.options.copy(dynamic = it)) })
          SwitchPreference(title = stringResource(R.string.perfect_icons_static_night),
            summary = stringResource(if (state.options.staticNight) R.string.perfect_icons_night_enabled
              else R.string.perfect_icons_night_disabled),
            checked = state.options.staticNight, enabled = !busy,
            onCheckedChange = { vm.perfectPacks.setOptions(state.options.copy(staticNight = it)) },
            modifier = Modifier.testTag("icon-packs.perfect.static-night"))
        }
      }
      item("generated") {
        state.pack?.let { card ->
          Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sourceId = prefs.systemIconFallback.sourceId(card.pack.id)
            val activeId = runtime.report?.iconTheme?.localId
            IconPackCard(card, vm.packPreviews, prefs.iconPackAppearance,
              packs.nativeAssociations[sourceId], card.pack.id == activeCatalogPackId(activeId, packs),
              packs.nativeImporting == sourceId, ready && !busy,
              packs.nativeResult?.takeIf { it.sourceId == sourceId },
              onImport = { vm.importNativeIconPack(card.pack) }, onRecheck = vm::recheckNativeIconImport,
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
      item("sources") {
        Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
          BasicComponent(title = stringResource(R.string.perfect_icons_sources_storage),
            summary = stringResource(R.string.perfect_icons_sources_row_summary),
            onClick = { onNavigate("perfect-sources") },
            modifier = Modifier.testTag("icon-packs.perfect.sources"))
        }
      }
    }
  }
}

@Composable
private fun catalogSummary(catalog: PerfectIconCatalog): String {
  return stringResource(R.string.perfect_icons_catalog_detail, perfectSyncTime(catalog.updatedAt))
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
internal fun PerfectPackSourcesScreen(vm: HomeViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
  val state by vm.perfectPacks.state.collectAsStateWithLifecycle()
  val packs by vm.iconPacks.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val busy = state.busy || packs.preparing || packs.importing || packs.nativeImporting != null
  var localLoadRequested by remember(vm) { mutableStateOf(false) }
  val localLoading = state.loading || (!localLoadRequested && state.catalog == null)
  var confirmDownload by remember { mutableStateOf(false) }
  var showDetails by remember { mutableStateOf(false) }
  var actionHeight by remember { mutableIntStateOf(0) }
  val actionBottom = maxOf(104.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp)
  val cooling = state.retryAtMillis > System.currentTimeMillis()
  val hasSource = state.catalog != null
  val downloadLabel = stringResource(if (hasSource) R.string.perfect_icons_force_download
    else R.string.perfect_icons_download_resources)
  LaunchedEffect(vm) {
    vm.perfectPacks.load()
    localLoadRequested = true
  }
  LaunchedEffect(state.retryAtMillis) {
    val remaining = state.retryAtMillis - System.currentTimeMillis()
    if (remaining > 0) delay(remaining)
    vm.perfectPacks.updateCooldown()
  }
  PerfectSubpage(stringResource(R.string.perfect_icons_sources_storage), onBack,
    bottomSpacing = actionBottom,
    floatingActionButton = {
      PageActionFab(downloadLabel, MiuixIcons.Refresh,
        onClick = { confirmDownload = true }, enabled = !busy && !cooling,
        busy = state.refreshing, onHeightChanged = { actionHeight = it },
        modifier = Modifier.testTag("icon-packs.perfect.force-download"))
    }) {
    item("snapshot") {
      val catalog = state.catalog
      StatusOverviewCard(
        title = stringResource(when {
          state.refreshing -> R.string.perfect_icons_syncing
          localLoading -> R.string.perfect_icons_loading
          state.error != null -> R.string.perfect_icons_attention
          catalog != null -> R.string.perfect_icons_catalog_ready
          else -> R.string.perfect_icons_catalog_missing
        }),
        summary = if (catalog != null) catalogSummary(catalog) else stringResource(
          if (localLoading) R.string.pack_local_catalog_loading else R.string.perfect_icons_storage_empty),
        details = emptyList(),
        tone = when {
          state.error != null -> StatusTone.ERROR
          catalog == null || busy -> StatusTone.WARNING
          else -> StatusTone.READY
        },
        onRefresh = {}, onOpen = {
          if (catalog != null) showDetails = true
          else if (!busy && !cooling && !localLoading) confirmDownload = true
        },
        actionLabel = if (catalog != null) stringResource(R.string.perfect_icons_file_details)
          else downloadLabel,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize()
          .testTag("icon-packs.perfect.storage-status"),
        badgeContent = if (catalog == null) null else { color ->
          MetadataPill(stringResource(R.string.perfect_icons_storage_count, catalog.iconCount,
            Formatter.formatFileSize(context, catalog.archiveBytes)), color)
          val revision = catalog.revision.ifEmpty { catalog.sourceHash }
          val label = stringResource(R.string.perfect_icons_revision)
          MetadataPill(stringResource(R.string.perfect_icons_revision_short, revision.take(7)), color,
            Modifier.clickable(onClickLabel = label) { copyPerfectRevision(context, revision, label) })
        })
    }
    if (localLoading || state.refreshing || state.error != null || cooling) item("sync-progress") {
      Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (localLoading || state.refreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.progress?.let {
              if (it.label == "source.download") stringResource(R.string.perfect_icons_download_progress,
                Formatter.formatFileSize(context, it.done.coerceAtLeast(0).toLong()))
              else stringResource(R.string.perfect_icons_verifying)
            } ?: stringResource(R.string.perfect_icons_loading), style = MiuixTheme.textStyles.footnote1)
          }
          state.error?.let { Text(stringResource(perfectErrorResource(it)),
            color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1) }
          if (cooling) Text(stringResource(R.string.perfect_icons_cooldown_detail,
            DateFormat.getTimeFormat(context).format(Date(state.retryAtMillis))),
            style = MiuixTheme.textStyles.footnote1)
        }
      }
    }
    item("cache") {
      Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        BasicComponent(title = stringResource(R.string.perfect_icons_clear_cache),
          summary = stringResource(R.string.perfect_icons_clear_cache_summary),
          endActions = { MetadataPill(Formatter.formatFileSize(context, state.cacheBytes),
            MiuixTheme.colorScheme.primary) },
          enabled = !busy && state.cacheBytes > 0, onClick = vm.perfectPacks::clearDownloads,
          modifier = Modifier.testTag("icon-packs.perfect.clear-cache"))
        if (state.clearingCache) LinearProgressIndicator(Modifier.fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp))
        state.clearedBytes?.let { bytes ->
          BasicComponent(title = stringResource(R.string.perfect_icons_cache_cleared,
            Formatter.formatFileSize(context, bytes)))
        }
      }
    }
    item("details") {
      Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        BasicComponent(title = stringResource(R.string.perfect_icons_file_details),
          summary = stringResource(R.string.perfect_icons_file_details_summary),
          enabled = state.catalog != null, onClick = { showDetails = true },
          endActions = { Icon(MiuixIcons.Basic.ArrowRight, null) },
          modifier = Modifier.testTag("icon-packs.perfect.file-details"))
        BasicComponent(title = stringResource(R.string.perfect_icons_sources),
          summary = "pzcn / Perfect-Icons-Completion-Project",
          onClick = { onNavigate("perfect-credits") },
          endActions = { Icon(MiuixIcons.Basic.ArrowRight, null) },
          modifier = Modifier.testTag("icon-packs.perfect.credits"))
      }
    }
  }
  val catalog = state.catalog
  val revisionLabel = stringResource(R.string.perfect_icons_revision)
  val hashLabel = stringResource(R.string.perfect_icons_snapshot_hash)
  WindowDialog(show = showDetails, title = stringResource(R.string.perfect_icons_file_details),
    onDismissRequest = { showDetails = false }) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
      catalog?.let {
        Text(stringResource(R.string.perfect_icons_downloaded_at) + "\n" + perfectSyncTime(it.downloadedAt),
          style = MiuixTheme.textStyles.body2)
        Text(revisionLabel + "\n" + it.revision.ifEmpty { it.sourceHash },
          style = MiuixTheme.textStyles.footnote1)
        Text(hashLabel + "\n" + it.sourceHash, style = MiuixTheme.textStyles.footnote1)
      }
      state.result?.let { result ->
        Text(stringResource(R.string.perfect_icons_output) + "\n" + result.file.name,
          style = MiuixTheme.textStyles.footnote1)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(stringResource(R.string.perfect_icons_copy_revision), enabled = catalog != null,
          onClick = { catalog?.let { copyPerfectRevision(context, it.revision.ifEmpty { it.sourceHash }, revisionLabel) } },
          modifier = Modifier.weight(1f))
        TextButton(stringResource(R.string.perfect_icons_copy_checksum), enabled = catalog != null,
          onClick = { catalog?.let { copyPerfectRevision(context, it.sourceHash, hashLabel) } },
          modifier = Modifier.weight(1f))
      }
    }
  }

  WindowDialog(show = confirmDownload,
    title = downloadLabel,
    summary = stringResource(if (hasSource) R.string.perfect_icons_force_download_confirm
      else R.string.perfect_icons_download_confirm),
    onDismissRequest = { confirmDownload = false }) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      TextButton(stringResource(R.string.logs_cancel), onClick = { confirmDownload = false },
        modifier = Modifier.weight(1f))
      TextButton(if (hasSource) stringResource(R.string.perfect_icons_force_download_proceed) else downloadLabel,
        enabled = confirmDownload && !busy && !cooling, onClick = {
          confirmDownload = false
          vm.perfectPacks.refreshCatalog(forceDownload = true)
        }, colors = ButtonDefaults.textButtonColorsPrimary(),
        modifier = Modifier.weight(1f).testTag("icon-packs.perfect.confirm-download"))
    }
  }
}

@Composable
internal fun PerfectPackCreditsScreen(onBack: () -> Unit) {
  val uri = LocalUriHandler.current
  PerfectSubpage(stringResource(R.string.perfect_icons_sources), onBack) {
    item("credits") {
      Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        BasicComponent(title = stringResource(R.string.perfect_icons_sources),
          summary = stringResource(R.string.perfect_icons_attribution))
        BasicComponent(title = stringResource(R.string.pack_perfect_community_title),
          summary = stringResource(R.string.pack_perfect_community_detail))
        BasicComponent(title = stringResource(R.string.perfect_icons_source_icons),
          summary = "pzcn/Perfect-Icons-Completion-Project",
          onClick = { uri.openUri("https://github.com/pzcn/Perfect-Icons-Completion-Project") })
        BasicComponent(title = stringResource(R.string.pack_perfect_author),
          summary = "pzcn", onClick = { uri.openUri("https://github.com/pzcn") })
        BasicComponent(title = stringResource(R.string.perfect_icons_source_packer),
          summary = "YifePlayte/PerfectIconsPacker\n${stringResource(R.string.pack_perfect_packer_reference)}",
          onClick = { uri.openUri("https://github.com/YifePlayte/PerfectIconsPacker") })
        BasicComponent(title = stringResource(R.string.perfect_icons_limits),
          summary = stringResource(R.string.perfect_icons_limits_summary))
      }
    }
  }
}

private fun perfectErrorResource(error: PerfectPackError): Int = when (error) {
  PerfectPackError.LOCAL -> R.string.perfect_icons_error_local
  PerfectPackError.CATALOG -> R.string.perfect_icons_error_catalog
  PerfectPackError.NETWORK -> R.string.perfect_icons_error_network
  PerfectPackError.COOLDOWN -> R.string.perfect_icons_error_cooldown
  PerfectPackError.INSTALLED -> R.string.perfect_icons_error_installed
  PerfectPackError.GENERATE -> R.string.perfect_icons_error_generate
  PerfectPackError.IMPORT -> R.string.perfect_icons_error_import
  PerfectPackError.EMPTY -> R.string.perfect_icons_error_empty
  PerfectPackError.CACHE -> R.string.perfect_icons_error_cache
}
