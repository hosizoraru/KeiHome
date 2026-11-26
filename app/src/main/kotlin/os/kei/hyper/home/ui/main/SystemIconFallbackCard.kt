package os.kei.hyper.home.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.OverlaySpinnerPreference
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import os.kei.hyper.home.ui.perfect.ObservePerfectResources
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Theme
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

  val styleRowAlpha by animateFloatAsState(
    if (selected.isPerfect) 1f else 0.78f,
    Os4Motion.stateSpring(),
    label = "perfect-style-row-alpha",
  )
  val nativeCardAlpha by animateFloatAsState(
    if (selected.isPerfect) 0.72f else 1f,
    Os4Motion.stateSpring(),
    label = "native-fallback-card-alpha",
  )

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    SmallTitle(
      stringResource(R.string.icon_fallback_section_completion),
      insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
      modifier = Modifier.semantics { heading() },
    )
    Card(Modifier.fillMaxWidth().os4ContentSize(), insideMargin = PaddingValues(0.dp)) {
      SwitchPreference(
        title = stringResource(R.string.perfect_completion_title),
        summary = stringResource(R.string.perfect_completion_summary),
        checked = selected.isPerfect,
        enabled = enabled && !checking,
        onCheckedChange = { checked ->
          if (checked) selectPerfect(null)
          else if (selected.isPerfect) onSelectPerfect(null)
        },
        modifier = Modifier.testTag("icon-packs.perfect-completion"),
      )
      HorizontalDivider(Modifier.padding(horizontal = 16.dp))
      OverlaySpinnerPreference(
        title = stringResource(R.string.perfect_completion_choices_title),
        summary = stringResource(R.string.perfect_completion_choices_summary),
        items = perfectChoices.map { DropdownItem(text = requireNotNull(it.perfectStyle)) }
          .ifEmpty { listOf(DropdownItem(text = stringResource(R.string.perfect_completion_no_styles))) },
        selectedIndex = perfectChoices.indexOf(displayedPerfect).coerceAtLeast(0),
        onSelectedIndexChange = { index -> perfectChoices.getOrNull(index)?.let(selectPerfect) },
        enabled = enabled && !checking && perfectChoices.isNotEmpty(),
        modifier = Modifier.graphicsLayer { alpha = styleRowAlpha }
          .testTag("icon-packs.perfect-completion-style"),
      )
      HorizontalDivider(Modifier.padding(horizontal = 16.dp))
      val resourcesPress = rememberOs4PressSource()
      val perfectAccent = Color(0xFFE95C88)
      BasicComponent(
        title = stringResource(
          if (resourcesAvailable) R.string.system_fallback_perfect_source_title
          else R.string.system_fallback_perfect_download_title,
        ),
        summary = stringResource(
          if (resourcesAvailable) R.string.system_fallback_perfect_source_summary
          else R.string.system_fallback_perfect_download_summary,
        ),
        enabled = onOpenPerfectIcons != null && !checking,
        interactionSource = resourcesPress,
        onClick = { onOpenPerfectIcons?.invoke() },
        startAction = {
          Box(
            Modifier.size(34.dp).squircleBackground(perfectAccent.copy(alpha = 0.14f), 10.dp),
            contentAlignment = Alignment.Center,
          ) {
            Icon(MiuixIcons.Glass.Theme, contentDescription = null, modifier = Modifier.size(18.dp),
              tint = perfectAccent)
          }
        },
        endActions = {
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (localStyles.isNotEmpty()) {
              MetadataPill("${localStyles.size}", perfectAccent)
            }
            Os4TrailingChevron()
          }
        },
        modifier = Modifier.glassPress(resourcesPress, onOpenPerfectIcons != null && !checking)
          .testTag("icon-packs.perfect-fallback-resources"),
      )
      Os4AnimatedVisibility(selected.isPerfect && selected.perfectStyle !in localStyles) {
        Text(
          stringResource(
            R.string.system_fallback_perfect_unavailable,
            systemFallbackName(selected, completion = true),
          ),
          modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
            .testTag("icon-packs.system-fallback-unavailable"),
          style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.error,
        )
      }
    }

    SmallTitle(
      stringResource(R.string.icon_fallback_section_system),
      insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
      modifier = Modifier.semantics { heading() },
    )
    Card(
      Modifier.fillMaxWidth().os4ContentSize().graphicsLayer { alpha = nativeCardAlpha },
      insideMargin = PaddingValues(0.dp),
    ) {
      OverlaySpinnerPreference(
        title = stringResource(R.string.system_fallback_title),
        summary = if (selected.isPerfect) stringResource(
          R.string.system_fallback_disabled_summary,
          systemFallbackName(nativeSelected),
        ) else stringResource(R.string.system_fallback_summary),
        items = if (selected.isPerfect) listOf(DropdownItem(text = stringResource(R.string.system_fallback_off)))
        else nativeChoices.map { DropdownItem(text = systemFallbackName(it)) },
        selectedIndex = if (selected.isPerfect) 0 else nativeChoices.indexOf(nativeSelected).coerceAtLeast(0),
        onSelectedIndexChange = { index ->
          if (!currentSelected.isPerfect) nativeChoices.getOrNull(index)?.let(onSelectNative)
        },
        enabled = enabled && !checking && !selected.isPerfect,
        modifier = Modifier.testTag("icon-packs.system-fallback"),
      )
    }
  }

  Os4ActionDialog(
    show = showResourcesRequired,
    onDismissRequest = { showResourcesRequired = false },
    title = stringResource(R.string.perfect_completion_resources_required_title),
    summary = stringResource(R.string.perfect_completion_resources_required_summary),
    actions = listOf(
      Os4DialogAction(
        stringResource(R.string.perfect_completion_resources_later),
        { showResourcesRequired = false },
        tag = "perfect.completion.cancel",
      ),
      Os4DialogAction(
        stringResource(R.string.perfect_completion_manage_resources),
        {
          showResourcesRequired = false
          onOpenPerfectIcons?.invoke()
        },
        role = GlassDialogButtonRole.Primary,
        enabled = onOpenPerfectIcons != null,
        tag = "perfect.completion.manage-resources",
      ),
    ),
  )
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
  val applied = source?.systemFallback
  val hasAppliedBanner = applied != null || updateNeeded

  Column(Modifier.fillMaxWidth().os4ContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Os4AnimatedVisibility(hasAppliedBanner) {
      Card(Modifier.fillMaxWidth().os4ContentSize(), insideMargin = PaddingValues(16.dp)) {
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              stringResource(R.string.icon_fallback_applied_status),
              style = MiuixTheme.textStyles.footnote1,
              fontWeight = FontWeight.Medium,
              color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              if (applied != null) {
                val completion = applied.style.isPerfect
                MetadataPill(
                  stringResource(
                    if (completion) R.string.perfect_completion_applied
                    else R.string.system_fallback_applied,
                    if (completion) requireNotNull(applied.style.perfectStyle)
                    else systemFallbackName(applied.style),
                    applied.names.size,
                  ),
                  MiuixTheme.colorScheme.primary,
                )
              }
              source?.generation?.colorOsPackages?.size?.let { count ->
                if (count > 0) MetadataPill(
                  stringResource(R.string.coloros_fallback_applied, count),
                  Color(0xFFFF963A),
                )
              }
              if (pendingNativeApplication(state, runtime.report?.iconTheme?.localId, preferences.systemIconFallback) != null) {
                MetadataPill(stringResource(R.string.pack_pending_apply), MiuixTheme.colorScheme.primary)
              } else if (updateNeeded) {
                val description = stringResource(R.string.pack_update_available)
                MetadataPill(
                  stringResource(R.string.pack_update_available_short),
                  MiuixTheme.colorScheme.primary,
                  Modifier.semantics { contentDescription = description },
                )
              }
            }
          }
          if (applied != null && source != null) {
            val current = source!!
            val launcherNames = apps.capabilities.orEmpty().filter { it.hasLauncher }.map { it.packageName }
            val names = launcherNames.filter { it in applied.names }.distinct().take(2)
              .ifEmpty { applied.names.sorted().take(2) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              names.mapNotNull(current.entries::get).distinctBy { it.key }.forEach {
                AppliedPackThumbnail(
                  current, it, model.currentPreviews,
                  runtime.report?.iconTheme, apps.previewGeneration,
                )
              }
            }
          }
        }
      }
    }

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
      onOpenPerfectIcons = onOpenPerfectIcons?.let { open -> { open("perfect-sources") } },
    )

    SmallTitle(
      stringResource(R.string.icon_fallback_section_coloros),
      insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
      modifier = Modifier.semantics { heading() },
    )
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      SwitchPreference(
        title = stringResource(R.string.coloros_other_packs_title),
        summary = stringResource(R.string.coloros_other_packs_summary),
        checked = preferences.colorOsForOtherPacks,
        enabled = !busy,
        onCheckedChange = model.preferences::setColorOsForOtherPacks,
        modifier = Modifier.testTag("icon-packs.coloros-fallback"),
      )
    }

    val hasFeedbackCard = (missingResources && !perfect.loading) ||
      (pack == null && !state.loading && state.scanned &&
        runtime.report?.iconTheme?.source == os.kei.hyper.home.status.SystemIconThemeReport.Source.THEME) ||
      result != null
    Os4AnimatedVisibility(hasFeedbackCard) {
      Card(Modifier.fillMaxWidth().os4ContentSize(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Os4AnimatedVisibility(missingResources && !perfect.loading) {
            Text(
              stringResource(
                R.string.system_fallback_resources_unavailable,
                systemFallbackName(preferences.systemIconFallback),
              ),
              modifier = Modifier.testTag("icon-packs.system-fallback-unavailable"),
              style = MiuixTheme.textStyles.footnote2,
              color = MiuixTheme.colorScheme.error,
            )
          }
          Os4AnimatedVisibility(
            pack == null && !state.loading && state.scanned &&
              runtime.report?.iconTheme?.source == os.kei.hyper.home.status.SystemIconThemeReport.Source.THEME,
          ) {
            Text(
              stringResource(R.string.system_fallback_source_needed),
              style = MiuixTheme.textStyles.footnote2,
              color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
          }
          Os4AnimatedVisibility(result?.imported == true) {
            result?.takeIf { it.imported }?.let { imported ->
              NativeThemeLink(
                imported, preferences.autoApplyIconPack, enabled = !busy,
                manualFallback = preferences.manualThemeFallback,
              )
            }
          }
          Os4AnimatedVisibility(result != null && !result.imported) {
            result?.takeIf { !it.imported }?.let { failed ->
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                  stringResource(failed.failureLabel()),
                  style = MiuixTheme.textStyles.footnote2,
                  color = if (failed.pending) MiuixTheme.colorScheme.onSurfaceSecondary
                  else MiuixTheme.colorScheme.error,
                )
                if (failed.pending) TextButton(
                  stringResource(R.string.icon_pack_recheck),
                  onClick = model::recheckNativeIconImport,
                )
              }
            }
          }
        }
      }
    }

    Text(
      stringResource(R.string.system_fallback_update_note),
      modifier = Modifier.padding(horizontal = 8.dp),
      style = MiuixTheme.textStyles.footnote2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary,
    )
  }
}
