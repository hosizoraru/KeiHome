package os.kei.hyper.home.ui.main

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import os.kei.hyper.home.R
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.Os4Dialog
import os.kei.hyper.home.ui.components.Os4DialogButton
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PerfectResourceScreen(vm: HomeViewModel, onBack: () -> Unit) {
  val state by vm.perfectPacks.state.collectAsStateWithLifecycle()
  val packs by vm.iconPacks.collectAsStateWithLifecycle()
  LaunchedEffect(vm) { vm.perfectPacks.load() }
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
    onDelete = vm.perfectPacks::deleteResources, onClearCache = vm.perfectPacks::clearDownloads)
}

@Composable
internal fun PerfectResourceContent(state: PerfectPackState, busy: Boolean,
  onBack: () -> Unit, onCheck: () -> Unit, onDownload: (Set<String>) -> Unit,
  onRemove: (Set<String>) -> Unit, onRepair: () -> Unit,
  onDelete: () -> Unit, onClearCache: () -> Unit) {
  val context = LocalContext.current
  val uri = LocalUriHandler.current
  val downloaded = state.catalog?.downloadedStyles.orEmpty().map { it.ifEmpty { "default" } }.toSet()
  val styles = remember(state.remote?.availableStyles, state.catalog?.styles, downloaded) {
    val available = state.remote?.availableStyles?.takeIf { it.isNotEmpty() }
      ?: state.catalog?.styles?.takeIf { it.isNotEmpty() }
      ?: listOf("default", SystemIconFallback.DEFAULT_PERFECT_STYLE)
    (available.map { it.ifEmpty { "default" } } + downloaded)
      .distinct().sortedWith(compareBy<String> { it != "default" }.thenBy { it })
  }
  var selection by remember { mutableStateOf(emptySet<String>()) }
  var downloadedOnly by remember { mutableStateOf(false) }
  var showStorage by remember { mutableStateOf(false) }
  var removal by remember { mutableStateOf(emptySet<String>()) }
  var confirmDelete by remember { mutableStateOf(false) }
  var confirmCache by remember { mutableStateOf(false) }
  var confirmRepair by remember { mutableStateOf(false) }
  var actionHeight by remember { mutableIntStateOf(0) }
  val selected = selection.intersect(styles.toSet())
  val removable = selected.intersect(downloaded)
  val visible = if (downloadedOnly) styles.filter { it in downloaded } else styles
  val cooling = state.retryAtMillis > System.currentTimeMillis()
  val bottomSpacing = maxOf(104.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp)
  val defaultName = stringResource(R.string.perfect_icons_style_default)
  val names: (Set<String>) -> String = { values ->
    styles.filter { it in values }.joinToString { if (it == "default") defaultName else it }
  }
  fun toggle(style: String) {
    if (!busy) selection = if (style in selection) selection - style else selection + style
  }

  PerfectSubpage(stringResource(R.string.perfect_git_resources), onBack,
    bottomSpacing = bottomSpacing,
    actions = {
      IconButton(onClick = onCheck, enabled = !busy && !cooling,
        modifier = Modifier.testTag("icon-packs.perfect.check-updates")) {
        Icon(MiuixIcons.Glass.Refresh, stringResource(R.string.perfect_git_check))
      }
    },
    floatingActionButton = {
      Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PageActionFab(stringResource(R.string.perfect_git_remove_count, removable.size),
          actionIcon(PageActionIcon.CLEAR), enabled = !busy && removable.isNotEmpty(),
          onClick = { removal = removable.toSet() },
          modifier = Modifier.weight(1f).testTag("perfect.resources.remove"))
        PageActionFab(stringResource(R.string.perfect_git_download_count, selected.size),
          actionIcon(PageActionIcon.FETCH), enabled = !busy && !cooling && selected.isNotEmpty(),
          busy = state.refreshing, onClick = { onDownload(selected.toSet()) },
          onHeightChanged = { actionHeight = it },
          modifier = Modifier.weight(1f).testTag("perfect.resources.download"))
      }
      // GlassDialog registers with this Scaffold host, outside the recorded page content.
      PerfectResourceConfirmations(state, busy, confirmDelete, confirmCache,
        onDismissDelete = { confirmDelete = false }, onDismissClearCache = { confirmCache = false },
        onDelete = onDelete, onClearCache = onClearCache)
      Os4Dialog(show = removal.isNotEmpty(),
        title = stringResource(R.string.perfect_git_remove_title, removal.size),
        summary = stringResource(R.string.perfect_git_remove_confirm, removal.size, names(removal),
          Formatter.formatFileSize(context, state.resourceBytes)) +
          if (removal.isNotEmpty() && removal.containsAll(downloaded))
            "\n" + stringResource(R.string.perfect_git_remove_all_scope) else "",
        onDismissRequest = { removal = emptySet() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Os4DialogButton(stringResource(R.string.logs_cancel), onClick = { removal = emptySet() },
            modifier = Modifier.weight(1f).testTag("perfect.resources.cancel-remove"))
          Os4DialogButton(stringResource(R.string.perfect_git_delete_proceed),
            enabled = !busy && removal.intersect(downloaded).isNotEmpty(), onClick = {
              if (!busy) {
                val requested = removal.intersect(downloaded)
                removal = emptySet()
                if (requested.isNotEmpty()) onRemove(requested)
              }
            }, role = GlassDialogButtonRole.Destructive,
            modifier = Modifier.weight(1f).testTag("perfect.resources.confirm-remove"))
        }
      }
      Os4Dialog(show = confirmRepair, title = stringResource(R.string.perfect_git_repair),
        summary = stringResource(R.string.perfect_git_repair_confirm),
        onDismissRequest = { confirmRepair = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Os4DialogButton(stringResource(R.string.logs_cancel), onClick = { confirmRepair = false },
            modifier = Modifier.weight(1f))
          Os4DialogButton(stringResource(R.string.perfect_git_repair), enabled = !busy && !cooling,
            onClick = {
              if (confirmRepair && !busy && !cooling) {
                confirmRepair = false
                onRepair()
              }
            }, role = GlassDialogButtonRole.Primary,
            modifier = Modifier.weight(1f).testTag("icon-packs.perfect.confirm-download"))
        }
      }
    }) {
    item("summary") {
      Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.perfect_git_resource_summary, downloaded.size, styles.size,
            state.catalog?.iconCount ?: 0), style = MiuixTheme.textStyles.body1)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PerfectSyncBadges(state, MiuixTheme.colorScheme.onSurfaceSecondary)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(if (downloadedOnly) R.string.perfect_git_filter_downloaded
              else R.string.perfect_git_filter_all), enabled = !busy,
              onClick = { downloadedOnly = !downloadedOnly },
              modifier = Modifier.testTag("perfect.resources.filter"))
            TextButton(stringResource(if (visible.isNotEmpty() && selected.containsAll(visible))
              R.string.perfect_git_clear_selection else R.string.perfect_git_select_all), enabled = !busy,
              onClick = {
                selection = if (visible.isNotEmpty() && selected.containsAll(visible)) selection - visible.toSet()
                  else selection + visible
              }, modifier = Modifier.testTag("perfect.resources.select-all"))
          }
          Text(stringResource(R.string.perfect_git_selection_count, selected.size),
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.testTag("perfect.resources.selection-count"))
          if (state.remote == null) Text(stringResource(R.string.perfect_git_check_styles),
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
          if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(when {
              state.checking -> R.string.perfect_git_checking
              state.refreshing -> R.string.perfect_git_resource_syncing
              state.deletingResources -> R.string.perfect_git_deleting
              else -> R.string.perfect_icons_loading
            }), style = MiuixTheme.textStyles.footnote1)
            state.progress?.let {
              if (!state.checking && it.label.startsWith("source.git:")) Text(
                stringResource(R.string.perfect_icons_download_progress, Formatter.formatFileSize(context,
                  it.label.substringAfter("source.git:").toLongOrNull()?.coerceAtLeast(0) ?: 0)),
                style = MiuixTheme.textStyles.footnote1)
            }
          }
          state.error?.let { Text(stringResource(perfectErrorResource(it)),
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
          if (cooling) Text(stringResource(R.string.perfect_icons_error_cooldown),
            style = MiuixTheme.textStyles.footnote1)
          state.deletedBytes?.let { Text(stringResource(R.string.perfect_git_deleted,
            Formatter.formatFileSize(context, it)), style = MiuixTheme.textStyles.footnote1) }
        }
      }
    }
    item("storage") {
      Card(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        BasicComponent(title = stringResource(R.string.perfect_git_storage),
          summary = stringResource(R.string.perfect_git_storage_summary,
            Formatter.formatFileSize(context, state.resourceBytes)),
          onClick = { showStorage = !showStorage },
          modifier = Modifier.testTag("perfect.resources.storage"))
        if (showStorage) {
          Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.remote?.let {
              Text(stringResource(R.string.perfect_git_remote_revision, it.revision) + "\n" +
                perfectSyncTime(it.committedAt), style = MiuixTheme.textStyles.footnote1)
              if (it.subject.isNotBlank()) Text(it.subject, style = MiuixTheme.textStyles.footnote1)
              Text(stringResource(R.string.perfect_git_checked_at, perfectSyncTime(it.checkedAt)),
                style = MiuixTheme.textStyles.footnote1)
            }
            state.catalog?.let {
              Text(stringResource(R.string.perfect_icons_snapshot_hash) + "\n" + it.sourceHash,
                style = MiuixTheme.textStyles.footnote1)
              if (it.gitBacked) Text(stringResource(R.string.perfect_git_last_transfer,
                Formatter.formatFileSize(context, it.transferredBytes)), style = MiuixTheme.textStyles.footnote1)
            }
          }
          BasicComponent(title = stringResource(R.string.perfect_git_repair),
            summary = stringResource(R.string.perfect_git_repair_hint),
            enabled = !busy && !cooling && downloaded.isNotEmpty(), onClick = { confirmRepair = true },
            modifier = Modifier.testTag("icon-packs.perfect.force-download"))
          PerfectResourceStorageCard(state, busy,
            onRequestClearCache = { confirmCache = true }, onRequestDelete = { confirmDelete = true })
          BasicComponent(title = stringResource(R.string.perfect_icons_source_icons),
            summary = "pzcn / Perfect-Icons-Completion-Project",
            onClick = { uri.openUri("https://github.com/pzcn/Perfect-Icons-Completion-Project") },
            modifier = Modifier.testTag("icon-packs.perfect.credits"))
          BasicComponent(title = stringResource(R.string.perfect_icons_source_packer),
            summary = "YifePlayte / PerfectIconsPacker",
            onClick = { uri.openUri("https://github.com/YifePlayte/PerfectIconsPacker") })
          Text(stringResource(R.string.perfect_icons_attribution) + "\n" +
            stringResource(R.string.pack_perfect_packer_reference),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
    }
    items(visible, key = { it }) { style ->
      Card(Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) {
        BasicComponent(title = if (style == "default") defaultName else style, enabled = !busy,
          onClick = { toggle(style) },
          endActions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically) {
              MetadataPill(stringResource(when {
                style in downloaded && state.updateAvailable -> R.string.perfect_git_update_available
                style in downloaded -> R.string.perfect_git_downloaded
                else -> R.string.perfect_git_not_downloaded
              }), if (style in downloaded) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceSecondary, Modifier.widthIn(max = 144.dp))
              Checkbox(state = if (style in selected) ToggleableState.On else ToggleableState.Off,
                onClick = { toggle(style) }, enabled = !busy,
                modifier = Modifier.testTag("perfect.resources.select.$style"))
            }
          }, modifier = Modifier.testTag("perfect.resources.style.$style"))
      }
    }

  }
}
