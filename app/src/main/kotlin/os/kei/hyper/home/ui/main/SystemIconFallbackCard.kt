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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.iconpack.perfect.PerfectIconRepository
import os.kei.hyper.home.iconpack.perfect.PerfectSystemFallback
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4DialogAction
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import os.kei.hyper.home.ui.components.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole

@Composable
internal fun systemFallbackName(style: SystemIconFallback, completion: Boolean = false): String = when (style) {
  SystemIconFallback.DEFAULT -> stringResource(R.string.system_fallback_default)
  SystemIconFallback.CLASSIC -> stringResource(R.string.system_fallback_classic)
  SystemIconFallback.BOUNDLESS -> stringResource(R.string.system_fallback_boundless)
  else -> stringResource(if (completion) R.string.perfect_completion_style
    else R.string.system_fallback_perfect, requireNotNull(style.perfectStyle))
}

internal fun nativeSystemFallbackChoices(
  bundled: List<SystemIconFallback>,
  selected: SystemIconFallback,
): List<SystemIconFallback> = (bundled + selected).filterNot { it.isPerfect }.distinct()
  .ifEmpty { listOf(SystemIconFallback.DEFAULT) }

internal fun perfectCompletionStyles(
  downloadedStyles: List<String>,
  selected: SystemIconFallback? = null,
): List<SystemIconFallback> = buildList {
  val downloaded = downloadedStyles.mapNotNull {
    runCatching { SystemIconFallback.perfect(it) }.getOrNull()
  }.distinct()
  val hyperOs4 = SystemIconFallback.perfect(SystemIconFallback.DEFAULT_PERFECT_STYLE)
  if (hyperOs4 in downloaded) add(hyperOs4)
  addAll(downloaded)
  // A deleted resource must not hide the saved intent.
  selected?.takeIf { it.isPerfect }?.let(::add)
}.distinct()

internal fun perfectCompletionChoice(
  selected: SystemIconFallback,
  savedStyle: String,
  downloadedStyles: List<String>,
  requested: SystemIconFallback? = null,
): SystemIconFallback? {
  val downloaded = perfectCompletionStyles(downloadedStyles)
  // An explicit style change must never substitute another style when its resource is missing.
  if (requested != null) return requested.takeIf { it.isPerfect && it in downloaded }
  val saved = runCatching { SystemIconFallback.perfect(savedStyle) }.getOrNull()
  val recommended = SystemIconFallback.perfect(SystemIconFallback.DEFAULT_PERFECT_STYLE)
  return listOfNotNull(selected.takeIf { it.isPerfect }, saved, recommended)
    .firstOrNull { it in downloaded } ?: downloaded.firstOrNull()
}

@Composable
internal fun PerfectCompletionPreferences(
  bundled: List<SystemIconFallback>,
  selected: SystemIconFallback,
  nativeSelected: SystemIconFallback,
  savedStyle: String,
  downloadedStyles: List<String>,
  enabled: Boolean,
  onSelectNative: (SystemIconFallback) -> Unit,
  onSelectPerfect: (String?) -> Unit,
  readDownloadedStyles: suspend () -> List<String>,
  verifyStyle: suspend (String) -> Unit,
  onOpenPerfectIcons: (() -> Unit)?,
) {
  val scope = rememberCoroutineScope()
  val currentSelected by rememberUpdatedState(selected)
  val currentSavedStyle by rememberUpdatedState(savedStyle)
  val currentOnSelectPerfect by rememberUpdatedState(onSelectPerfect)
  var checking by remember { mutableStateOf(false) }
  var showResourcesRequired by remember { mutableStateOf(false) }
  // A fresh event read takes precedence until the controller publishes its next local catalog.
  var checkedStyles by remember(downloadedStyles) { mutableStateOf<List<String>?>(null) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checkedStyles = null }
  val localStyles = checkedStyles ?: downloadedStyles
  val nativeChoices = nativeSystemFallbackChoices(bundled, nativeSelected)
  val perfectChoices = perfectCompletionStyles(localStyles, selected)
  val displayedPerfect = selected.takeIf { it.isPerfect }
    ?: perfectCompletionChoice(selected, savedStyle, localStyles)
  val resourcesAvailable = displayedPerfect?.perfectStyle?.let { it in localStyles } == true
  val selectPerfect: (SystemIconFallback?) -> Unit = { requested ->
    if (enabled && !checking) {
      checking = true
      scope.launch {
        try {
          val freshStyles = withContext(Dispatchers.IO) { readDownloadedStyles() }
          checkedStyles = freshStyles
          val choice = perfectCompletionChoice(currentSelected, currentSavedStyle, freshStyles, requested)
          if (choice != null) {
            val style = requireNotNull(choice.perfectStyle)
            try {
              withContext(Dispatchers.IO) { verifyStyle(style) }
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (failure: Exception) {
              checkedStyles = freshStyles.filterNot { it == style }
              throw failure
            }
            currentOnSelectPerfect(style)
          } else showResourcesRequired = true
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          showResourcesRequired = true
        } finally {
          checking = false
        }
      }
    }
  }
  SwitchPreference(title = stringResource(R.string.perfect_completion_title),
    summary = stringResource(R.string.perfect_completion_summary),
    checked = selected.isPerfect, enabled = enabled && !checking,
    onCheckedChange = { checked ->
      if (checked) selectPerfect(null)
      else if (selected.isPerfect) onSelectPerfect(null)
    },
    modifier = Modifier.testTag("icon-packs.perfect-completion"))
  OverlaySpinnerPreference(title = stringResource(R.string.perfect_completion_choices_title),
    summary = stringResource(R.string.perfect_completion_choices_summary),
    items = perfectChoices.map { DropdownItem(text = requireNotNull(it.perfectStyle)) }
      .ifEmpty { listOf(DropdownItem(text = stringResource(R.string.perfect_completion_no_styles))) },
    selectedIndex = perfectChoices.indexOf(displayedPerfect).coerceAtLeast(0),
    onSelectedIndexChange = { index -> perfectChoices.getOrNull(index)?.let(selectPerfect) },
    enabled = enabled && !checking && perfectChoices.isNotEmpty(),
    modifier = Modifier.testTag("icon-packs.perfect-completion-style"))
  BasicComponent(title = stringResource(if (resourcesAvailable)
      R.string.system_fallback_perfect_source_title else R.string.system_fallback_perfect_download_title),
    summary = stringResource(if (resourcesAvailable)
      R.string.system_fallback_perfect_source_summary else R.string.system_fallback_perfect_download_summary),
    enabled = onOpenPerfectIcons != null && !checking,
    onClick = { onOpenPerfectIcons?.invoke() },
    endActions = { Icon(MiuixIcons.Glass.ChevronForward, null,
      tint = MiuixTheme.colorScheme.onSurfaceSecondary) },
    modifier = Modifier.testTag("icon-packs.perfect-fallback-resources"))
  if (selected.isPerfect && selected.perfectStyle !in localStyles) {
    Text(stringResource(R.string.system_fallback_perfect_unavailable,
      systemFallbackName(selected, completion = true)),
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        .testTag("icon-packs.system-fallback-unavailable"),
      style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
  OverlaySpinnerPreference(title = stringResource(R.string.system_fallback_title),
    summary = if (selected.isPerfect) stringResource(R.string.system_fallback_disabled_summary,
      systemFallbackName(nativeSelected)) else stringResource(R.string.system_fallback_summary),
    items = if (selected.isPerfect) listOf(DropdownItem(text = stringResource(R.string.system_fallback_off)))
      else nativeChoices.map { DropdownItem(text = systemFallbackName(it)) },
    selectedIndex = if (selected.isPerfect) 0 else nativeChoices.indexOf(nativeSelected).coerceAtLeast(0),
    onSelectedIndexChange = { index ->
      if (!currentSelected.isPerfect) nativeChoices.getOrNull(index)?.let(onSelectNative)
    },
    enabled = enabled && !checking && !selected.isPerfect,
    modifier = Modifier.testTag("icon-packs.system-fallback"))
  Os4ActionDialog(show = showResourcesRequired, onDismissRequest = { showResourcesRequired = false },
    title = stringResource(R.string.perfect_completion_resources_required_title),
    summary = stringResource(R.string.perfect_completion_resources_required_summary),
    actions = listOf(
      Os4DialogAction(stringResource(R.string.perfect_completion_resources_later),
        { showResourcesRequired = false }, tag = "perfect.completion.cancel"),
      Os4DialogAction(stringResource(R.string.perfect_completion_manage_resources), {
        showResourcesRequired = false
        onOpenPerfectIcons?.invoke()
      }, role = GlassDialogButtonRole.Primary, enabled = onOpenPerfectIcons != null,
        tag = "perfect.completion.manage-resources"),
    ))
}

@Composable
internal fun SystemIconFallbackCard(model: HomeViewModel, onOpenPerfectIcons: ((String) -> Unit)? = null) {
  val context = LocalContext.current.applicationContext
  val repository = remember(context) { PerfectIconRepository(context) }
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val source by model.browserSource.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val apps by model.apps.collectAsStateWithLifecycle()
  val updateNeeded by model.iconPackUpdateNeeded.collectAsStateWithLifecycle()
  val perfect by model.perfectPacks.state.collectAsStateWithLifecycle()
  ObservePerfectResources(model.perfectPacks)
  LaunchedEffect(model, perfect.clearingCache) {
    if (!perfect.clearingCache) model.perfectPacks.load()
  }
  val downloadedStyles = perfect.catalog?.downloadedStyles.orEmpty()
  val missingResources = !preferences.systemIconFallback.isPerfect &&
    preferences.systemIconFallback !in model.preferences.systemIconFallbackChoices
  val pack = model.currentFallbackSource(runtime.report?.iconTheme, state, preferences.colorOsComponents)
  val busy = state.nativeImporting != null
  val result = state.nativeResult?.takeIf { pack != null &&
    it.sourceId == preferences.systemIconFallback.sourceId(pack.id) }
  Card(Modifier.fillMaxWidth().animateContentSize(tween(180)), insideMargin = PaddingValues(0.dp)) {
    Column {
      SwitchPreference(title = stringResource(R.string.coloros_other_packs_title),
        summary = stringResource(R.string.coloros_other_packs_summary),
        checked = preferences.colorOsForOtherPacks, enabled = !busy,
        onCheckedChange = model.preferences::setColorOsForOtherPacks,
        modifier = Modifier.testTag("icon-packs.coloros-fallback"))
      PerfectCompletionPreferences(
        bundled = model.preferences.systemIconFallbackChoices,
        selected = preferences.systemIconFallback,
        nativeSelected = preferences.nativeSystemIconFallback,
        savedStyle = preferences.perfectIconCompletionStyle,
        downloadedStyles = downloadedStyles,
        enabled = !busy && !perfect.clearingCache && !perfect.deletingResources,
        onSelectNative = model::setSystemIconFallback,
        onSelectPerfect = model::setPerfectIconCompletion,
        readDownloadedStyles = {
          repository.catalog(refresh = false)?.downloadedStyles.orEmpty().also {
            model.perfectPacks.refreshLocal()
          }
        },
        verifyStyle = { style -> PerfectSystemFallback.open(context, style).use { it.validate() } },
        onOpenPerfectIcons = onOpenPerfectIcons?.let { open -> { open("perfect-sources") } })
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
              if (applied != null) {
                val completion = applied.style.isPerfect
                MetadataPill(stringResource(if (completion) R.string.perfect_completion_applied
                    else R.string.system_fallback_applied,
                  if (completion) requireNotNull(applied.style.perfectStyle) else systemFallbackName(applied.style),
                  applied.names.size), MiuixTheme.colorScheme.primary)
              }
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
                  AppliedPackThumbnail(current, it, model.currentPreviews,
                    runtime.report?.iconTheme, apps.previewGeneration)
                }
              }
            }
          }
        }
        Text(stringResource(R.string.system_fallback_update_note),
          style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (missingResources && !perfect.loading) {
          Text(stringResource(R.string.system_fallback_resources_unavailable,
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
