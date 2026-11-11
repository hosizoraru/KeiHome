package os.kei.hyper.home.ui.main

import os.kei.hyper.home.iconpack.perfect.PerfectIconStyles

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.delay
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.LocalKeiHomeBackdrop
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4Dialog
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4DialogButton
import os.kei.hyper.home.ui.components.OverlayIconCascadingDropdownMenu
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PerfectResourceScreen(vm: HomeViewModel, onBack: () -> Unit, onPreview: (String) -> Unit = {}) {
  val state by vm.perfectPacks.state.collectAsStateWithLifecycle()
  val packs by vm.iconPacks.collectAsStateWithLifecycle()
  ObservePerfectResources(vm.perfectPacks)
  LaunchedEffect(state.retryAtMillis) {
    val remaining = state.retryAtMillis - System.currentTimeMillis()
    if (remaining > 0) delay(remaining)
    vm.perfectPacks.updateCooldown()
  }
  PerfectResourceContent(state,
    busy = state.busy || packs.preparing || packs.importing || packs.nativeImporting != null,
    onBack = onBack, onCheck = vm.perfectPacks::checkUpdates,
    onDownload = vm.perfectPacks::downloadStyles, onRemove = vm.perfectPacks::removeStyles,
    onRepair = vm.perfectPacks::repairResources,
    onDelete = vm.perfectPacks::deleteResources, onClearCache = vm.perfectPacks::clearDownloads,
    onPreview = onPreview)
}

@Composable
internal fun PerfectResourceContent(state: PerfectPackState, busy: Boolean,
  onBack: () -> Unit, onCheck: () -> Unit, onDownload: (Set<String>) -> Unit,
  onRemove: (Set<String>) -> Unit, onRepair: () -> Unit,
  onDelete: () -> Unit, onClearCache: () -> Unit, onPreview: (String) -> Unit = {}) {
  val context = LocalContext.current
  val uri = LocalUriHandler.current
  val cooling = state.retryAtMillis > System.currentTimeMillis()
  val defaultName = stringResource(R.string.perfect_icons_style_default)
  val downloaded = remember(state.catalog) {
    state.catalog?.downloadedStyles.orEmpty().map { it.ifEmpty { "default" } }.toSet()
  }
  val available = remember(state.catalog, state.remote, downloaded) {
    PerfectIconStyles.ordered((state.remote?.availableStyles?.takeIf { it.isNotEmpty() }
      ?: state.catalog?.styles?.takeIf { it.isNotEmpty() }
      ?: listOf("default", "HyperOS4")).plus(downloaded)
    )
  }
  var selecting by rememberSaveable { mutableStateOf(false) }
  var selection by rememberSaveable { mutableStateOf(listOf<String>()) }
  var downloadedOnly by rememberSaveable { mutableStateOf(false) }
  val visible = remember(available, downloaded, downloadedOnly) {
    if (downloadedOnly) available.filter { it in downloaded } else available
  }
  val selected = selection.filter { it in visible }
  val selectedDownloaded = selected.filter { it in downloaded }
  LaunchedEffect(visible) { selection = selection.filter { it in visible } }
  fun exitSelection() { selecting = false; selection = emptyList() }
  fun toggle(style: String) {
    selection = if (style in selection) selection - style else selection + style
  }
  // A completed back gesture leaves selection before leaving the resource page.
  NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = selecting, onBackCompleted = { exitSelection() })
  var focusedStyle by remember { mutableStateOf<String?>(null) }
  var removalTargets by remember { mutableStateOf(emptySet<String>()) }
  var showInfo by remember { mutableStateOf(false) }
  var showClear by remember { mutableStateOf(false) }
  var showRepair by remember { mutableStateOf(false) }
  var showDelete by remember { mutableStateOf(false) }
  val dialogs: @Composable () -> Unit = {
    PerfectResourceConfirmations(state, busy, showDelete, showClear,
      onDismissDelete = { showDelete = false }, onDismissClearCache = { showClear = false },
      onDelete = onDelete, onClearCache = onClearCache)
    Os4ActionDialog(show = removalTargets.isNotEmpty(),
      title = stringResource(R.string.perfect_git_remove_title, removalTargets.size),
      summary = stringResource(R.string.perfect_git_remove_confirm, removalTargets.size, removalTargets.joinToString { if (it == "default") defaultName else it },
        Formatter.formatFileSize(context, state.resourceBytes)) +
        if (removalTargets.isNotEmpty() && removalTargets.containsAll(downloaded))
          "\n" + stringResource(R.string.perfect_git_remove_all_scope) else "",
      onDismissRequest = { removalTargets = emptySet() }, icon = actionIcon(PageActionIcon.CLEAR),
      actions = listOf(
        Os4DialogAction(stringResource(R.string.logs_cancel), onClick = { removalTargets = emptySet() },
          tag = "perfect.resources.cancel-remove"),
        Os4DialogAction(stringResource(R.string.perfect_git_delete_proceed),
          enabled = !busy && removalTargets.intersect(downloaded).isNotEmpty(), onClick = {
            if (!busy) {
              val requested = removalTargets.intersect(downloaded)
              removalTargets = emptySet()
              if (requested.isNotEmpty()) { exitSelection(); onRemove(requested) }
            }
          }, role = GlassDialogButtonRole.Destructive,
          tag = "perfect.resources.confirm-remove")))
    Os4ActionDialog(show = showRepair, title = stringResource(R.string.perfect_git_repair),
      summary = stringResource(R.string.perfect_git_repair_confirm),
      onDismissRequest = { showRepair = false }, icon = actionIcon(PageActionIcon.FETCH),
      actions = listOf(
        Os4DialogAction(stringResource(R.string.logs_cancel), onClick = { showRepair = false }),
        Os4DialogAction(stringResource(R.string.perfect_git_repair), enabled = !busy && !cooling,
          onClick = {
            if (showRepair && !busy && !cooling) {
              showRepair = false
              onRepair()
            }
          }, role = GlassDialogButtonRole.Primary,
          tag = "icon-packs.perfect.confirm-download")))

    focusedStyle?.let { style ->
      val styleName = if (style == "default") defaultName else style
      val detail = state.catalog?.styleDetails?.get(style)
      val identity = state.catalog?.sourceHash.orEmpty()
      Os4ActionDialog(true, { focusedStyle = null }, title = if (style in downloaded) styleName
        else stringResource(R.string.perfect_resource_download_title, styleName),
        summary = stringResource(if (style in downloaded) R.string.perfect_resource_style_ready
          else R.string.perfect_resource_style_missing),
        artwork = detail?.iconFile?.let { file -> { PerfectStyleArtwork(file, identity, 48.dp) } },
        details = {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetadataPill(stringResource(if (style in downloaded) R.string.perfect_git_downloaded
              else R.string.perfect_git_not_downloaded), MiuixTheme.colorScheme.primary)
            if (detail != null && detail.bytes > 0)
              MetadataPill(stringResource(R.string.perfect_resource_style_bytes, Formatter.formatFileSize(context, detail.bytes)), MiuixTheme.colorScheme.onSurfaceSecondary,
                Modifier.testTag("perfect.resources.style-size"))
            if (detail != null && detail.sharedBytes > 0) {
              MetadataPill(stringResource(R.string.perfect_resource_own_bytes, Formatter.formatFileSize(context, detail.ownBytes)),
                MiuixTheme.colorScheme.onSurfaceSecondary, Modifier.testTag("perfect.resources.style-own-size"))
              MetadataPill(stringResource(R.string.perfect_resource_shared_bytes, Formatter.formatFileSize(context, detail.sharedBytes)),
                MiuixTheme.colorScheme.onSurfaceSecondary, Modifier.testTag("perfect.resources.style-shared-size"))
            }
            (state.catalog?.revision ?: state.remote?.revision)?.takeIf { it.isNotBlank() }?.let {
              MetadataPill(stringResource(R.string.perfect_icons_revision_short, it.take(7)), MiuixTheme.colorScheme.onSurfaceSecondary)
            }
          }
        },
        actions = buildList {
          add(Os4DialogAction(stringResource(if (style in downloaded) R.string.action_label_update
            else R.string.action_label_download), enabled = !busy && !cooling,
            role = GlassDialogButtonRole.Primary, tag = "perfect.resources.style-download",
            onClick = { focusedStyle = null; onDownload(setOf(style)) }))
          if (style in downloaded) add(Os4DialogAction(stringResource(R.string.perfect_resource_remove_short),
            enabled = !busy, role = GlassDialogButtonRole.Destructive, onClick = {
              focusedStyle = null; removalTargets = setOf(style)
            }))
          if (style in downloaded) add(Os4DialogAction(stringResource(R.string.perfect_resource_preview),
            enabled = !busy, tag = "perfect.resources.style-preview",
            onClick = { focusedStyle = null; onPreview(style) }))
          add(Os4DialogAction(stringResource(if (style in downloaded) R.string.perfect_resource_close
            else R.string.logs_cancel), { focusedStyle = null }))
        })
    }
    Os4Dialog(showInfo, { showInfo = false }, title = stringResource(R.string.perfect_resource_info)) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PerfectSyncBadges(state, MiuixTheme.colorScheme.primary)
          }
          state.remote?.let {
            Text(stringResource(R.string.perfect_git_remote_revision, it.revision) + "\n" + it.subject,
              style = MiuixTheme.textStyles.footnote1)
          }
          state.catalog?.let {
            Text(stringResource(R.string.perfect_resource_local_revision, it.revision), style = MiuixTheme.textStyles.footnote1)
          }
          Text(stringResource(R.string.perfect_resource_cache_size, Formatter.formatFileSize(context, state.cacheBytes)),
            style = MiuixTheme.textStyles.footnote1)
          Text(stringResource(R.string.perfect_resource_source_size, Formatter.formatFileSize(context, state.resourceBytes)),
            style = MiuixTheme.textStyles.footnote1)
          Text(stringResource(R.string.perfect_git_last_transfer, Formatter.formatFileSize(context, state.catalog?.transferredBytes ?: 0)),
            style = MiuixTheme.textStyles.footnote1)
          Text(stringResource(R.string.perfect_resource_info_hint), style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
          TextButton(stringResource(R.string.perfect_icons_source_icons),
            onClick = { uri.openUri("https://github.com/pzcn/Perfect-Icons-Completion-Project") },
            modifier = Modifier.fillMaxWidth())
          TextButton(stringResource(R.string.perfect_icons_source_packer),
            onClick = { uri.openUri("https://github.com/YifePlayte/PerfectIconsPacker") },
            modifier = Modifier.fillMaxWidth())
          Text(stringResource(R.string.perfect_icons_attribution), style = MiuixTheme.textStyles.footnote1)
        }
        Os4DialogButton(stringResource(R.string.perfect_resource_close), { showInfo = false }, Modifier.fillMaxWidth())
      }
    }
  }
  var actionHeight by remember { mutableIntStateOf(0) }
  val actionInset = with(LocalDensity.current) { actionHeight.toDp() }
  PerfectSubpage(if (selecting) stringResource(R.string.perfect_git_selection_count, selected.size)
    else stringResource(R.string.perfect_git_resources),
    onBack = { if (selecting) exitSelection() else onBack() },
    actions = {
      OverlayIconCascadingDropdownMenu(entries = listOf(DropdownEntry(items = listOf(
        DropdownItem(text = stringResource(R.string.perfect_git_filter_all), selected = !downloadedOnly,
          onClick = { if (downloadedOnly) { selection = emptyList(); downloadedOnly = false } }),
        DropdownItem(text = stringResource(R.string.perfect_git_filter_downloaded), selected = downloadedOnly,
          onClick = { if (!downloadedOnly) { selection = emptyList(); downloadedOnly = true } })))),
        modifier = Modifier.testTag("perfect.resources.filter")) {
        Icon(ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_list_filter),
          contentDescription = stringResource(R.string.perfect_resource_filter_description,
            stringResource(if (downloadedOnly) R.string.perfect_git_filter_downloaded else R.string.perfect_git_filter_all)),
          modifier = Modifier.size(24.dp),
          tint = if (downloadedOnly) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
      }
      if (!selecting) IconButton(
        enabled = !busy && visible.isNotEmpty(),
        modifier = Modifier.testTag("perfect.resources.selection-mode"),
        onClick = { selection = emptyList(); selecting = true }) {
        Icon(ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_square_check),
          stringResource(R.string.perfect_resource_select), Modifier.size(24.dp))
      }
      if (!selecting) IconButton(onClick = onCheck, enabled = !busy && !cooling,
        modifier = Modifier.testTag("perfect.resources.refresh")) {
        Icon(MiuixIcons.Glass.Refresh, stringResource(R.string.perfect_git_check))
      }
      if (!selecting) OverlayIconCascadingDropdownMenu(entries = listOf(DropdownEntry(items = listOf(
          DropdownItem(text = stringResource(R.string.perfect_resource_info), onClick = { showInfo = true }),
          DropdownItem(text = stringResource(R.string.perfect_git_repair), enabled = !busy && state.catalog != null && !cooling,
            onClick = { showRepair = true }),
          DropdownItem(text = stringResource(R.string.perfect_icons_clear_cache), enabled = !busy && state.cacheBytes > 0,
            onClick = { showClear = true }),
          DropdownItem(text = stringResource(R.string.perfect_git_delete),
            enabled = !busy && (state.catalog != null || state.resourceBytes > 0),
            onClick = { showDelete = true }),

        )))) { Icon(MiuixIcons.Glass.More, stringResource(R.string.perfect_resource_manage),
          Modifier.testTag("perfect.resources.manage")) }
    }, bottomSpacing = if (selecting) actionInset + 24.dp else 24.dp,
    floatingActionButton = { dialogs() },
    floatingToolbar = {
      if (selecting) {
        val shape = GlassShape(32.dp)
        Row(Modifier.widthIn(max = 320.dp).fillMaxWidth()
          .onSizeChanged { actionHeight = it.height }
          .testTag("perfect.resources.toolbar")
          .glassPanel(backdrop = LocalKeiHomeBackdrop.current, shape = shape,
            style = GlassDefaults.style(LocalKeiHomeDarkTheme.current),
            material = GlassTopAppBarDefaults.buttonMaterial(), shading = false,
            fallback = Modifier.clip(shape).background(MiuixTheme.colorScheme.surfaceContainer))
          .padding(horizontal = 8.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
          ResourceToolbarButton("all", stringResource(R.string.perfect_git_select_all),
            ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_list_checks),
            enabled = !busy && selected.size < visible.size, onClick = { selection = visible })
          ResourceToolbarButton("none", stringResource(R.string.perfect_resource_select_none),
            ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_square),
            enabled = !busy && selected.isNotEmpty(), onClick = { selection = emptyList() })
          ResourceToolbarButton("download", stringResource(R.string.action_label_download),
            actionIcon(PageActionIcon.FETCH), enabled = !busy && !cooling && selected.isNotEmpty(),
            onClick = { val targets = selected.toSet(); exitSelection(); onDownload(targets) })
          ResourceToolbarButton("remove", stringResource(R.string.perfect_resource_remove_short),
            actionIcon(PageActionIcon.CLEAR), enabled = !busy && selectedDownloaded.isNotEmpty(),
            tint = MiuixTheme.colorScheme.error, onClick = { removalTargets = selectedDownloaded.toSet() })
          ResourceToolbarButton("done", stringResource(R.string.perfect_resource_done),
            actionIcon(PageActionIcon.APPLY), onClick = { exitSelection() },
            tint = MiuixTheme.colorScheme.primary)
        }
      }
    },
  ) {
    item(key = "resource-status") {
      val tone = when {
        state.error != null || state.syncIssue != null -> StatusTone.WARNING
        downloaded.isNotEmpty() -> StatusTone.READY
        else -> StatusTone.WARNING
      }
      StatusOverviewCard(title = stringResource(when {
        state.checking -> R.string.perfect_git_checking
        state.refreshing -> R.string.perfect_git_resource_syncing
        state.deletingResources -> R.string.perfect_git_deleting
        state.clearingCache -> R.string.perfect_icons_loading
        busy -> R.string.action_label_processing
        downloaded.isNotEmpty() -> R.string.perfect_resource_ready
        else -> R.string.perfect_icons_catalog_missing
      }), summary = stringResource(if (downloaded.isNotEmpty()) R.string.perfect_resource_ready_summary
        else R.string.perfect_resource_empty_summary), tone = tone, details = emptyList(), onRefresh = { if (!busy && !cooling) onCheck() }, onOpen = { showInfo = true }, actionLabel = stringResource(R.string.perfect_resource_info),
        compactSymbol = true, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("perfect.resources.status"), badgeContent = { color ->
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetadataPill(stringResource(R.string.perfect_resource_style_count, downloaded.size, available.size), color)
          MetadataPill(stringResource(R.string.perfect_resource_occupied_bytes, Formatter.formatFileSize(context, state.resourceBytes)), color)
            (state.catalog?.revision ?: state.remote?.revision)?.let {
              MetadataPill(stringResource(R.string.perfect_icons_revision_short, it.take(7)), color)
            }
            PerfectSyncBadges(state, color, showRemote = false, showSuccess = false)
          }
        })
      if (busy && !state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
      if (cooling) Text(stringResource(R.string.perfect_icons_error_cooldown), Modifier.padding(16.dp),
        style = MiuixTheme.textStyles.footnote1)
      state.error?.let { Text(stringResource(perfectErrorResource(it)), Modifier.padding(16.dp), color = MiuixTheme.colorScheme.error) }
      if ((state.syncIssue == PerfectSyncIssue.CORRUPT)) Text(stringResource(R.string.perfect_resource_corrupt),
        Modifier.padding(16.dp), color = MiuixTheme.colorScheme.onSurfaceSecondary,
        style = MiuixTheme.textStyles.footnote1)
    }
    if (state.refreshing) item(key = "transfer") { PerfectDownloadStatusCard(state) }
    item(key = "styles-title") {
      SmallTitle(stringResource(if (downloadedOnly) R.string.perfect_git_filter_downloaded
        else R.string.perfect_resource_styles), Modifier.padding(horizontal = 12.dp))
    }
    if (visible.isEmpty()) item(key = "empty") {
      Text(stringResource(R.string.perfect_resource_filter_empty), Modifier.padding(horizontal = 16.dp),
        color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
    items(visible, key = { "style:$it" }, contentType = { "style" }) { style ->
      Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        val detail = state.catalog?.styleDetails?.get(style)
        BasicComponent(title = if (style == "default") defaultName else style, enabled = !busy,
          startAction = detail?.iconFile?.let { file -> { PerfectStyleArtwork(file, state.catalog?.sourceHash.orEmpty()) } },
          onClick = { if (selecting) toggle(style) else focusedStyle = style }, endActions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
              MetadataPill(stringResource(when {
                style !in downloaded -> R.string.perfect_git_not_downloaded
                (state.syncIssue == PerfectSyncIssue.CORRUPT) -> R.string.perfect_resource_incomplete
                else -> R.string.perfect_git_downloaded
              }), if (style in downloaded && !(state.syncIssue == PerfectSyncIssue.CORRUPT)) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceSecondary)
              if (style in downloaded && detail != null && detail.bytes > 0)
                Text(stringResource(R.string.perfect_resource_style_bytes, Formatter.formatFileSize(context, detail.bytes)), style = MiuixTheme.textStyles.footnote2,
                  color = MiuixTheme.colorScheme.onSurfaceSecondary,
                  modifier = Modifier.testTag("perfect.resources.size.$style"))
              }
              if (selecting) Checkbox(state = if (style in selected) ToggleableState.On else ToggleableState.Off, onClick = { toggle(style) },
                enabled = !busy, modifier = Modifier.testTag("perfect.resources.select.$style"))
              else Icon(MiuixIcons.Glass.ChevronForward, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
          }, modifier = Modifier.testTag("perfect.resources.style.$style"))
      }
    }
  }
}

@Composable
private fun ResourceToolbarButton(tag: String, label: String, icon: ImageVector,
  enabled: Boolean = true, tint: Color = MiuixTheme.colorScheme.onSurface,
  onClick: () -> Unit) {
  IconButton(onClick = onClick, enabled = enabled,
    backgroundColor = Color.Transparent, minWidth = 48.dp, minHeight = 48.dp,
    modifier = Modifier.testTag("perfect.resources.$tag")) {
    Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp),
      tint = if (enabled) tint else MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.38f))
  }
}
