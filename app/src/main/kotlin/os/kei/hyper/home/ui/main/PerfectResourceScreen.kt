package os.kei.hyper.home.ui.main

import os.kei.hyper.home.iconpack.perfect.PerfectIconStyles

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
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
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4DialogButton
import os.kei.hyper.home.ui.components.OverlayIconCascadingDropdownMenu
import os.kei.hyper.home.ui.components.Os4ViewTabs
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
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Filter
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Date
import java.util.Locale

@Stable
internal class PerfectResourceStyleDialogState {
  enum class Action { DOWNLOAD, REMOVE, PREVIEW }

  data class Session(
    val style: String,
    val id: Long,
    val show: Boolean = true,
    val action: Action? = null,
  )

  var session by mutableStateOf<Session?>(null)
    private set
  private var nextId = 0L

  fun open(style: String) {
    session = Session(style, ++nextId)
  }

  fun dismiss(id: Long, action: Action? = null) {
    val current = session ?: return
    if (current.id != id || !current.show) return
    session = current.copy(show = false, action = action)
  }

  fun finish(id: Long): Session? {
    val current = session ?: return null
    if (current.id != id || current.show) return null
    session = null
    return current
  }
}

@Composable
internal fun PerfectResourceScreen(vm: HomeViewModel, onBack: () -> Unit, onPreview: (String) -> Unit = {}) {
  val state by vm.perfectPacks.state.collectAsStateWithLifecycle()
  val packs by vm.iconPacks.collectAsStateWithLifecycle()
  ObservePerfectResources(vm.perfectPacks, ensureCatalog = true)
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
  var filterName by rememberSaveable { mutableStateOf(PerfectResourceFilter.ALL.name) }
  var sortName by rememberSaveable { mutableStateOf(PerfectResourceSort.RECOMMENDED.name) }
  var reverse by rememberSaveable { mutableStateOf(false) }
  val filter = PerfectResourceFilter.entries.firstOrNull { it.name == filterName } ?: PerfectResourceFilter.ALL
  val sort = PerfectResourceSort.entries.firstOrNull { it.name == sortName } ?: PerfectResourceSort.RECOMMENDED
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  val sizes = remember(state.catalog) { state.catalog?.styleDetails?.mapValues { it.value.bytes }.orEmpty() }
  val visible = remember(available, downloaded, sizes, filter, sort, reverse, defaultName, locales) {
    perfectResourceRows(available, downloaded, sizes, filter, sort, reverse, defaultName,
      Locale.forLanguageTag(locales.substringBefore(',')))
  }
  val selected = selection.filter { it in visible }
  val selectedDownloaded = selected.filter { it in downloaded }
  LaunchedEffect(visible) { selection = retainedPerfectSelection(selection, visible) }
  val filterLabels = listOf(R.string.perfect_git_filter_all, R.string.perfect_git_filter_downloaded,
    R.string.perfect_list_not_downloaded)
  val listState = rememberLazyListState()
  val viewCounts = listOf(available.size, available.count { it in downloaded }, available.count { it !in downloaded })
  fun exitSelection() { selecting = false; selection = emptyList() }
  fun toggle(style: String) {
    selection = if (style in selection) selection - style else selection + style
  }
  // A completed back gesture leaves selection before leaving the resource page.
  NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = selecting, onBackCompleted = { exitSelection() })
  val styleDialog = remember { PerfectResourceStyleDialogState() }
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

    styleDialog.session?.let { session ->
      val style = session.style
      val styleName = if (style == "default") defaultName else style
      val detail = state.catalog?.styleDetails?.get(style)
      val identity = state.catalog?.sourceHash.orEmpty()
      Os4ActionDialog(session.show, { styleDialog.dismiss(session.id) },
        onDismissFinished = {
          // Keep the old panel and scrim until native exit, then hand off exactly once.
          val finished = styleDialog.finish(session.id)
          if (finished != null && !busy) {
            when (finished.action) {
              PerfectResourceStyleDialogState.Action.DOWNLOAD ->
                if (!cooling) onDownload(setOf(finished.style))
              PerfectResourceStyleDialogState.Action.REMOVE ->
                if (finished.style in downloaded) removalTargets = setOf(finished.style)
              PerfectResourceStyleDialogState.Action.PREVIEW ->
                if (finished.style in downloaded) onPreview(finished.style)
              null -> Unit
            }
          }
        }, title = if (style in downloaded) styleName
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
            else R.string.action_label_download), enabled = session.show && !busy && !cooling,
            role = GlassDialogButtonRole.Primary, tag = "perfect.resources.style-download",
            onClick = { styleDialog.dismiss(session.id, PerfectResourceStyleDialogState.Action.DOWNLOAD) }))
          if (style in downloaded) add(Os4DialogAction(stringResource(R.string.perfect_resource_remove_short),
            enabled = session.show && !busy, role = GlassDialogButtonRole.Destructive,
            tag = "perfect.resources.style-remove", onClick = {
              styleDialog.dismiss(session.id, PerfectResourceStyleDialogState.Action.REMOVE)
            }))
          if (style in downloaded) add(Os4DialogAction(stringResource(R.string.perfect_resource_preview),
            enabled = session.show && !busy, tag = "perfect.resources.style-preview",
            onClick = { styleDialog.dismiss(session.id, PerfectResourceStyleDialogState.Action.PREVIEW) }))
          add(Os4DialogAction(stringResource(if (style in downloaded) R.string.perfect_resource_close
            else R.string.logs_cancel), { styleDialog.dismiss(session.id) },
            enabled = session.show, tag = "perfect.resources.style-close"))
        })
    }
    Os4DetailsSheet(showInfo, { showInfo = false }, scrollContent = false, title = stringResource(R.string.perfect_resource_info)) {
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
          Os4DialogButton(stringResource(R.string.perfect_icons_source_icons),
            onClick = { uri.openUri("https://github.com/pzcn/Perfect-Icons-Completion-Project") },
            modifier = Modifier.fillMaxWidth())
          Os4DialogButton(stringResource(R.string.perfect_icons_source_packer),
            onClick = { uri.openUri("https://github.com/YifePlayte/PerfectIconsPacker") },
            modifier = Modifier.fillMaxWidth())
          Text(stringResource(R.string.perfect_icons_attribution), style = MiuixTheme.textStyles.footnote1)
        }
        Os4DialogButton(stringResource(R.string.perfect_resource_close), { showInfo = false }, Modifier.fillMaxWidth())
      }
    }
  }
  val view = LocalView.current
  var actionObstruction by remember { mutableIntStateOf(0) }
  val actionInset = if (selecting) {
    if (actionObstruction > 0) with(LocalDensity.current) { actionObstruction.toDp() } + 12.dp else 96.dp
  } else 0.dp
  PerfectSubpage(if (selecting) stringResource(R.string.perfect_git_selection_count, selected.size)
    else stringResource(R.string.perfect_git_resources),
    onBack = { if (selecting) exitSelection() else onBack() },
    onRefresh = onCheck,
    pullRefresh = true,
    refreshing = state.loading || state.checking,
    refreshEnabled = !busy && !cooling && !selecting,
    refreshLabel = stringResource(R.string.perfect_git_check),
    listState = listState,
    bottomContent = {
      Os4ViewTabs(items = filterLabels.mapIndexed { index, label ->
        stringResource(R.string.browser_view_count, stringResource(label), viewCounts[index])
      }, selectedIndex = filter.ordinal, onSelect = {
        if (it != filter.ordinal) {
          selection = emptyList()
          filterName = PerfectResourceFilter.entries[it].name
        }
        listState.requestScrollToItem(0)
      }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("perfect.resources.views"))
    },
    actions = {
      if (!selecting) PerfectListSortMenu(listOf(R.string.perfect_list_recommended, R.string.perfect_list_name,
        R.string.perfect_list_downloads, R.string.perfect_list_size), sort.ordinal, reverse,
        onSort = { sortName = PerfectResourceSort.entries[it].name },
        onReverse = { reverse = !reverse }, modifier = Modifier.testTag("perfect.resources.sort"))
      if (!selecting) IconButton(
        enabled = !busy && visible.isNotEmpty(),
        modifier = Modifier.testTag("perfect.resources.selection-mode"),
        onClick = { selection = emptyList(); selecting = true }) {
        Icon(ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_square_check),
          stringResource(R.string.perfect_resource_select), Modifier.size(24.dp))
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
    }, viewportBottomInset = actionInset,
    floatingActionButton = { dialogs() },
    floatingToolbar = {
      if (selecting) {
        val shape = GlassShape(32.dp)
        Row(Modifier.widthIn(max = 320.dp).fillMaxWidth()
          .onGloballyPositioned { coordinates ->
            actionObstruction = (view.height - coordinates.boundsInWindow().top.toInt()).coerceAtLeast(0)
          }
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
    }
    if (state.error != null || cooling || state.syncIssue == PerfectSyncIssue.CORRUPT) item(key = "resource-notice") {
      Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize()
        .testTag("perfect.resources.notice")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          state.error?.let {
            Text(stringResource(perfectErrorResource(it)), style = MiuixTheme.textStyles.footnote1,
              color = MiuixTheme.colorScheme.error)
          }
          if (cooling) Text(stringResource(R.string.perfect_icons_cooldown_detail,
            DateFormat.getTimeFormat(context).format(Date(state.retryAtMillis))),
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
          if (state.syncIssue == PerfectSyncIssue.CORRUPT) Text(stringResource(R.string.perfect_resource_corrupt),
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
    }
    item(key = "connection-settings") {
      Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        PerfectConnectionSettings(enabled = !busy, onSaved = onCheck)
      }
    }
    if (state.refreshing) item(key = "transfer") { PerfectDownloadStatusCard(state) }
    item(key = "styles-title") {
      SmallTitle(stringResource(if (filter == PerfectResourceFilter.ALL) R.string.perfect_resource_styles
        else filterLabels[filter.ordinal]), Modifier.padding(horizontal = 12.dp))
    }
    if (visible.isEmpty()) item(key = "empty") {
      Text(stringResource(R.string.perfect_resource_filter_empty), Modifier.padding(horizontal = 16.dp),
        color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
    items(visible, key = { "style:$it" }, contentType = { "style" }) { style ->
      Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
        .animateItem(fadeInSpec = tween(180), fadeOutSpec = tween(120), placementSpec = null)) {
        val detail = state.catalog?.styleDetails?.get(style)
        BasicComponent(title = if (style == "default") defaultName else style, enabled = !busy,
          startAction = detail?.iconFile?.let { file -> { PerfectStyleArtwork(file, state.catalog?.sourceHash.orEmpty()) } },
          onClick = { if (selecting) toggle(style) else styleDialog.open(style) }, endActions = {
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
  val content: @Composable () -> Unit = {
    Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp),
      tint = if (enabled) tint else MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.38f))
  }
  if (enabled) {
    GlassIconButton(onClick = onClick, modifier = Modifier.testTag("perfect.resources.$tag")
      .semantics { role = Role.Button },
      backdrop = LocalKeiHomeBackdrop.current, inheritTopBarMaterial = false,
      style = GlassDefaults.style(LocalKeiHomeDarkTheme.current), size = 48.dp, content = content)
  } else {
    // GlassIconButton has no disabled API; keep the noninteractive accessible fallback.
    IconButton(onClick = onClick, enabled = false,
      backgroundColor = Color.Transparent, minWidth = 48.dp, minHeight = 48.dp,
      modifier = Modifier.testTag("perfect.resources.$tag"), content = content)
  }
}
