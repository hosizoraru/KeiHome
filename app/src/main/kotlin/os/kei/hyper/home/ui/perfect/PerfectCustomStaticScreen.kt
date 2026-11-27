package os.kei.hyper.home.ui.perfect

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar
import os.kei.hyper.home.ui.components.LocalKeiHomeBackdrop
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4DialogButton
import os.kei.hyper.home.ui.components.Os4InfoPopup
import os.kei.hyper.home.ui.components.Os4LazyColumn
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import os.kei.hyper.home.ui.main.HomeViewModel
import os.kei.hyper.home.ui.main.MetadataPill
import os.kei.hyper.home.ui.main.Os4SearchField
import os.kei.hyper.home.ui.main.PageActionFab
import os.kei.hyper.home.ui.main.PageActionIcon
import os.kei.hyper.home.ui.main.actionIcon
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Layers
import top.yukonga.miuix.kmp.icon.glass.UsageHelp
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PerfectCustomStaticScreen(
  vm: HomeViewModel,
  backdrop: LayerBackdrop?,
  bottomBar: @Composable () -> Unit,
  onBack: () -> Unit,
  active: Boolean,
  onGenerate: () -> Unit,
) {
  val controller = vm.perfectPacks
  val state by controller.state.collectAsStateWithLifecycle()
  val apps by vm.apps.collectAsStateWithLifecycle()
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  val behavior = MiuixScrollBehavior()
  val scope = rememberCoroutineScope()
  val direction = LocalLayoutDirection.current
  var draft by remember { mutableStateOf<StaticDraft?>(null) }
  var showDraft by remember { mutableStateOf(false) }
  var pendingDelete by remember { mutableStateOf<String?>(null) }
  var showDeleteConfirm by remember { mutableStateOf(false) }
  var deleteAfterDraft by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf(false) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var actionHeight by remember { mutableIntStateOf(0) }
  val actionBottom = if (state.customStaticIds.isNotEmpty()) {
    maxOf(100.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp)
  } else 24.dp

  LaunchedEffect(vm, locales) { vm.ensureAppsLoaded(locales) }

  val appLabels = remember(apps.capabilities) { installedAppLabels(apps.capabilities) }

  fun resolveLabel(id: String): String? =
    (appLabels[id] ?: appLabels[id.substringBefore('#')])?.takeIf { it.isNotBlank() && it != id }

  val filteredIds = remember(state.customStaticIds, appLabels, searchQuery) {
    val needle = searchQuery.trim()
    if (needle.isEmpty()) state.customStaticIds
    else state.customStaticIds.filter { id ->
      id.contains(needle, ignoreCase = true) || resolveLabel(id)?.contains(needle, ignoreCase = true) == true
    }
  }

  fun edit(operation: suspend () -> Unit) {
    error = false
    busy = true
    scope.launch {
      try {
        operation()
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        error = true
      } finally {
        busy = false
      }
    }
  }

  KeiHomeScaffold(
    backdrop = backdrop,
    modifier = Modifier.testTag("perfect.custom"),
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        KeiHomeTopAppBar(
          title = stringResource(R.string.perfect_custom_static),
          scrollBehavior = behavior,
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          navigationIcon = {
            KeiHomeIconButton(onClick = onBack) {
              Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back))
            }
          },
          actions = {
            Os4InfoPopup(
              title = stringResource(R.string.perfect_custom_static),
              summary = stringResource(R.string.perfect_custom_pair_help) + "\n" +
                stringResource(R.string.perfect_custom_id_help),
              icon = MiuixIcons.Glass.UsageHelp,
            )
          },
        )
      }
    },
    bottomBar = bottomBar,
    floatingActionButton = {
      Os4AnimatedVisibility(
        visible = state.customStaticIds.isNotEmpty(),
        enter = scaleIn(Os4Motion.stateSpring(), initialScale = 0.86f) + fadeIn(tween(180)),
        exit = scaleOut(Os4Motion.stateSpring(), targetScale = 0.86f) + fadeOut(tween(140)),
        label = "perfect-custom-fab",
      ) {
        PageActionFab(
          label = stringResource(R.string.perfect_custom_add),
          icon = actionIcon(PageActionIcon.CUSTOMIZE),
          enabled = !busy,
          onHeightChanged = { actionHeight = it },
          modifier = Modifier.testTag("perfect.custom.add"),
          onClick = {
            error = false
            draft = StaticDraft()
            showDraft = true
          },
        )
      }
    },
  ) { insets ->
    Os4LazyColumn(
      state = rememberLazyListState(),
      modifier = Modifier.fillMaxSize()
        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
        .keiHomeTopBarScroll(behavior),
      topAppBarScrollBehavior = behavior,
      contentPadding = PaddingValues(
        start = insets.calculateStartPadding(direction) + 16.dp,
        end = insets.calculateEndPadding(direction) + 16.dp,
        top = insets.calculateTopPadding() + 8.dp,
        bottom = insets.calculateBottomPadding() + actionBottom,
      ),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (state.customStaticIds.isEmpty()) {
        item(key = "empty-studio") {
          CustomStaticEmptyStudio(
            busy = busy,
            onAdd = {
              error = false
              draft = StaticDraft()
              showDraft = true
            },
          )
        }
      } else {
        item(key = "studio-summary") {
          Card(Modifier.fillMaxWidth().os4ContentSize(), insideMargin = PaddingValues(16.dp)) {
            Row(
              Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Box(
                Modifier.size(44.dp).squircleBackground(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f), 14.dp),
                contentAlignment = Alignment.Center,
              ) {
                Icon(MiuixIcons.Glass.Layers, contentDescription = null,
                  modifier = Modifier.size(22.dp), tint = MiuixTheme.colorScheme.primary)
              }
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  stringResource(R.string.perfect_custom_count, state.customStaticIds.size),
                  style = MiuixTheme.textStyles.body1,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  stringResource(R.string.perfect_custom_only_help),
                  style = MiuixTheme.textStyles.footnote1,
                  color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
              }
              TextButton(
                text = stringResource(R.string.action_label_generate),
                onClick = onGenerate,
                enabled = !state.busy,
                minHeight = 36.dp,
                cornerRadius = 18.dp,
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                textStyle = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium),
              )
            }
          }
        }
        if (state.customStaticIds.size > 3) {
          item(key = "search") {
            Os4SearchField(
              query = searchQuery,
              onQueryChange = { searchQuery = it },
              label = stringResource(R.string.perfect_custom_search_apps),
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
        items(filteredIds, key = { it }) { id ->
          val artwork = rememberCustomStaticThumbnail(id, state.customStaticRevision, controller.customStaticPreviews)
          val resolvedLabel = resolveLabel(id)
          val pressSource = rememberOs4PressSource()
          Card(Modifier.fillMaxWidth().animateItem(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(
              title = resolvedLabel ?: id,
              summary = if (resolvedLabel != null) id else "0.png + 1.png",
              enabled = !busy,
              interactionSource = pressSource,
              modifier = Modifier.glassPress(pressSource, !busy),
              startAction = {
                StaticArtwork(
                  modifier = Modifier.size(52.dp),
                  layers = artwork,
                  description = resolvedLabel ?: id,
                  backdropMode = StageBackdrop.GRID,
                  cornerRadius = 14.dp,
                )
              },
              endActions = {
                Row(
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  MetadataPill("PNG", MiuixTheme.colorScheme.onSurfaceSecondary)
                  Os4TrailingChevron()
                }
              },
              onClick = { edit { draft = StaticDraft(id, controller.readCustomStatic(id)); showDraft = true } },
            )
          }
        }
        if (filteredIds.isEmpty()) {
          item(key = "search-empty") {
            Text(
              stringResource(R.string.perfect_custom_no_apps),
              style = MiuixTheme.textStyles.body2,
              color = MiuixTheme.colorScheme.onSurfaceSecondary,
              modifier = Modifier.padding(12.dp),
            )
          }
        }
      }
      if (error && draft == null) {
        item(key = "io-error") {
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            StaticError(R.string.perfect_custom_io_error)
          }
        }
      }
    }
  }

  CompositionLocalProvider(LocalKeiHomeBackdrop provides backdrop) {
    draft?.let { current ->
      key(current) {
        PerfectStaticEditorSheet(
          vm = vm,
          draft = current,
          resolvedAppLabel = current.id?.let(::resolveLabel),
          show = active && showDraft,
          busy = busy,
          saveError = error,
          existing = state.customStaticIds,
          onClose = { showDraft = false; error = false },
          onDismissFinished = {
            if (!showDraft) {
              draft = null
              val target = deleteAfterDraft
              deleteAfterDraft = null
              if (target != null) {
                pendingDelete = target
                showDeleteConfirm = true
              }
            }
          },
          onSave = { id, pair ->
            edit {
              controller.saveCustomStatic(id, pair, create = current.id == null)
              showDraft = false
            }
          },
          onDelete = current.id?.let { id -> { deleteAfterDraft = id; showDraft = false } },
        )
      }
    }
    Os4ActionDialog(
      show = active && showDeleteConfirm && pendingDelete != null,
      onDismissRequest = { if (!busy) showDeleteConfirm = false },
      onDismissFinished = { if (!showDeleteConfirm) pendingDelete = null },
      title = stringResource(R.string.perfect_custom_delete),
      summary = pendingDelete.orEmpty(),
      icon = actionIcon(PageActionIcon.CLEAR),
      actions = listOf(
        Os4DialogAction(stringResource(android.R.string.cancel), { showDeleteConfirm = false }, enabled = !busy),
        Os4DialogAction(
          stringResource(R.string.perfect_custom_delete),
          onClick = {
            pendingDelete?.let { id -> edit { controller.deleteCustomStatic(id); showDeleteConfirm = false } }
          },
          role = GlassDialogButtonRole.Destructive,
          enabled = !busy,
        ),
      ),
      footer = if (error) ({ StaticError(R.string.perfect_custom_io_error) }) else null,
    )
  }
}

@Composable
private fun CustomStaticEmptyStudio(busy: Boolean, onAdd: () -> Unit) {
  val scheme = MiuixTheme.colorScheme
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
    Column(
      Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Visual 2-layer depth motif showing back (0.png) + floating foreground (1.png).
      Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Box(
          Modifier.size(72.dp).offset(x = (-10).dp, y = 8.dp)
            .squircleBackground(scheme.primary.copy(alpha = 0.14f), 20.dp)
            .squircleBorder(1.dp, scheme.primary.copy(alpha = 0.28f), 20.dp),
        )
        Box(
          Modifier.size(72.dp).offset(x = 10.dp, y = (-8).dp)
            .squircleBackground(scheme.surfaceContainerHigh, 20.dp)
            .squircleBorder(1.5.dp, scheme.primary.copy(alpha = 0.48f), 20.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(MiuixIcons.Glass.Layers, contentDescription = null, modifier = Modifier.size(32.dp),
            tint = scheme.primary)
        }
      }
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          stringResource(R.string.perfect_custom_static),
          style = MiuixTheme.textStyles.title4,
          fontWeight = FontWeight.SemiBold,
          textAlign = TextAlign.Center,
        )
        Text(
          stringResource(R.string.perfect_custom_pair_help),
          style = MiuixTheme.textStyles.body2,
          color = scheme.onSurfaceSecondary,
          textAlign = TextAlign.Center,
        )
      }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        MetadataPill("1 · ${stringResource(R.string.perfect_custom_target_title)}", scheme.onSurfaceSecondary)
        MetadataPill("2 · 0.png", scheme.onSurfaceSecondary)
        MetadataPill("3 · 1.png", scheme.onSurfaceSecondary)
      }
      Os4DialogButton(
        text = stringResource(R.string.perfect_custom_add),
        onClick = onAdd,
        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("perfect.custom.add"),
        enabled = !busy,
        role = GlassDialogButtonRole.Primary,
      )
    }
  }
}
