package os.kei.hyper.home.ui.perfect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.data.iconPackTargets
import os.kei.hyper.home.iconpack.nativeLauncherActivityName
import os.kei.hyper.home.iconpack.perfect.PerfectCustomStatic
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar
import os.kei.hyper.home.ui.components.LocalKeiHomeBackdrop
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4DialogButton
import os.kei.hyper.home.ui.components.Os4InfoPopup
import os.kei.hyper.home.ui.components.Os4LazyColumn
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.components.Os4ShimmerPlaceholder
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import os.kei.hyper.home.ui.components.os4Clickable
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import os.kei.hyper.home.ui.main.MetadataPill
import os.kei.hyper.home.ui.main.Os4SearchField
import os.kei.hyper.home.ui.main.PageActionFab
import os.kei.hyper.home.ui.main.PageActionIcon
import os.kei.hyper.home.ui.main.actionIcon
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.GridView
import top.yukonga.miuix.kmp.icon.glass.ImageOutline
import top.yukonga.miuix.kmp.icon.glass.Layers
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.icon.glass.UsageHelp
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class StaticDraft(val id: String? = null, val pair: PerfectCustomStatic? = null)

private enum class StageBackdrop(val labelRes: Int) {
  GRID(R.string.perfect_custom_backdrop_grid),
  LIGHT(R.string.perfect_custom_backdrop_light),
  DARK(R.string.perfect_custom_backdrop_dark),
}

private data class InstalledTargetChoice(
  val packageName: String,
  val label: String,
  val targetId: String,
)

@Composable
internal fun PerfectCustomStaticScreen(
  controller: PerfectPackController,
  backdrop: LayerBackdrop?,
  bottomBar: @Composable () -> Unit,
  onBack: () -> Unit,
  active: Boolean,
  onGenerate: () -> Unit,
) {
  val state by controller.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
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

  val appLabels by produceState<Map<String, String>>(emptyMap(), context, state.customStaticIds) {
    value = withContext(Dispatchers.IO) {
      val pm = context.packageManager
      buildMap {
        state.customStaticIds.forEach { id ->
          val pkg = id.substringBefore('#')
          val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
          }.getOrNull()
          if (!label.isNullOrBlank() && label != id) put(id, label)
        }
      }
    }
  }

  val filteredIds = remember(state.customStaticIds, appLabels, searchQuery) {
    val needle = searchQuery.trim()
    if (needle.isEmpty()) state.customStaticIds
    else state.customStaticIds.filter { id ->
      id.contains(needle, ignoreCase = true) || appLabels[id]?.contains(needle, ignoreCase = true) == true
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
          val artwork = rememberStaticThumbnail(id, state.customStaticRevision, controller)
          val resolvedLabel = appLabels[id]
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
          draft = current,
          resolvedAppLabel = current.id?.let(appLabels::get),
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

@Composable
private fun PerfectStaticEditorSheet(
  draft: StaticDraft,
  resolvedAppLabel: String?,
  show: Boolean,
  busy: Boolean,
  saveError: Boolean,
  existing: List<String>,
  onClose: () -> Unit,
  onDismissFinished: () -> Unit,
  onSave: (String, PerfectCustomStatic) -> Unit,
  onDelete: (() -> Unit)?,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val name = rememberTextFieldState(draft.id.orEmpty())
  val id = name.text.toString().trim()
  val valid = PerfectCustomStatic.validId(id)
  val duplicate = draft.id == null && id in existing
  var background by remember { mutableStateOf(draft.pair?.background) }
  var foreground by remember { mutableStateOf(draft.pair?.foreground) }
  var reading by remember { mutableStateOf(false) }
  var invalidPng by remember { mutableStateOf(false) }
  var pickForeground by rememberSaveable { mutableStateOf(false) }
  var pickingApp by remember { mutableStateOf(false) }
  var stageBackdrop by rememberSaveable { mutableStateOf(StageBackdrop.GRID) }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      val replaceForeground = pickForeground
      reading = true
      invalidPng = false
      scope.launch {
        try {
          val bytes = withContext(Dispatchers.IO) {
            PerfectCustomStatic.readLayer(requireNotNull(context.contentResolver.openInputStream(uri))).also {
              requireNotNull(decodePreview(it))
            }
          }
          if (replaceForeground) foreground = bytes else background = bytes
        } catch (failure: Exception) {
          if (failure is CancellationException) throw failure
          invalidPng = true
        } finally {
          reading = false
        }
      }
    }
  }

  val available = !busy && !reading
  val canSave = available && valid && !duplicate && background != null && foreground != null

  Os4DetailsSheet(
    show = show,
    onDismissRequest = {
      if (pickingApp) pickingApp = false
      else if (available) onClose()
    },
    onDismissFinished = { if (!show) onDismissFinished() },
    title = stringResource(
      when {
        pickingApp -> R.string.perfect_custom_choose_app
        draft.id == null -> R.string.perfect_custom_add
        else -> R.string.perfect_custom_edit
      },
    ),
    startAction = {
      GlassIconButton(
        onClick = {
          if (pickingApp) pickingApp = false
          else if (available) onClose()
        },
        backdrop = LocalKeiHomeBackdrop.current,
      ) {
        Icon(
          if (pickingApp) MiuixIcons.Glass.ChevronBackward else MiuixIcons.Glass.Close,
          contentDescription = stringResource(if (pickingApp) R.string.pack_back else android.R.string.cancel),
        )
      }
    },
    endAction = if (!pickingApp) {
      {
        GlassIconButton(
          onClick = {
            if (canSave) onSave(id, PerfectCustomStatic(requireNotNull(background), requireNotNull(foreground)))
          },
          backdrop = LocalKeiHomeBackdrop.current,
        ) {
          Icon(
            MiuixIcons.Glass.Ok,
            contentDescription = stringResource(R.string.perfect_custom_save),
            tint = if (canSave) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.35f),
          )
        }
      }
    } else null,
  ) {
    AnimatedContent(
      targetState = pickingApp,
      transitionSpec = {
        val enterOffset: (Int) -> Int = { if (targetState) it / 4 else -it / 4 }
        val exitOffset: (Int) -> Int = { if (targetState) -it / 5 else it / 5 }
        (slideInHorizontally(tween(220), enterOffset) + fadeIn(tween(180))) togetherWith
          (slideOutHorizontally(tween(180), exitOffset) + fadeOut(tween(140)))
      },
      label = "perfect-custom-sheet-step",
    ) { inAppPicker ->
      if (inAppPicker) {
        InSheetInstalledAppPicker(
          onPick = { pickedId ->
            name.setTextAndPlaceCursorAtEnd(pickedId)
            pickingApp = false
          },
        )
      } else {
        val backgroundPreview by rememberLayerPreview(background)
        val foregroundPreview by rememberLayerPreview(foreground)
        Column(Modifier.fillMaxWidth().os4ContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          // 1. Live Interactive Parallax Composite Stage
          InteractiveLayeredStage(
            background = backgroundPreview,
            foreground = foregroundPreview,
            backdropMode = stageBackdrop,
            onBackdropChange = { stageBackdrop = it },
            idReady = valid && !duplicate,
            bgReady = background != null,
            fgReady = foreground != null,
          )

          // 2. Target App / Identifier Section
          SmallTitle(
            stringResource(R.string.perfect_custom_target_title),
            insideMargin = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
          )
          GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            if (draft.id == null) {
              Column(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                GlassTextField(
                  state = name,
                  label = stringResource(R.string.perfect_custom_id),
                  lineLimits = TextFieldLineLimits.SingleLine,
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                  modifier = Modifier.fillMaxWidth().testTag("perfect.custom.id"),
                  enabled = available,
                )
                Text(
                  stringResource(R.string.perfect_custom_id_help),
                  style = MiuixTheme.textStyles.footnote2,
                  color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
              }
              HorizontalDivider(Modifier.padding(horizontal = 14.dp))
              val pickAppPress = rememberOs4PressSource()
              BasicComponent(
                title = stringResource(R.string.perfect_custom_choose_app),
                enabled = available,
                interactionSource = pickAppPress,
                startAction = {
                  Icon(MiuixIcons.Glass.GridView, contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.primary)
                },
                endActions = { Os4TrailingChevron() },
                onClick = { pickingApp = true },
                modifier = Modifier.fillMaxWidth().glassPress(pickAppPress, available),
              )
            } else {
              BasicComponent(
                title = resolvedAppLabel ?: id,
                summary = if (resolvedAppLabel != null) id else stringResource(R.string.perfect_custom_id),
                startAction = {
                  Icon(MiuixIcons.Glass.GridView, contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.primary)
                },
              )
            }
          }

          // 3. Side-by-Side Tactile Layer Pickers (0.png & 1.png)
          SmallTitle(
            stringResource(R.string.perfect_custom_layers_title),
            insideMargin = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
          )
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StaticLayerCard(
              modifier = Modifier.weight(1f),
              title = stringResource(R.string.perfect_custom_background),
              file = "0.png",
              bitmap = backgroundPreview,
              loaded = background != null,
              enabled = available,
              tag = "perfect.custom.background",
              onPick = {
                pickForeground = false
                picker.launch(arrayOf("image/png"))
              },
            )
            StaticLayerCard(
              modifier = Modifier.weight(1f),
              title = stringResource(R.string.perfect_custom_foreground),
              file = "1.png",
              bitmap = foregroundPreview,
              loaded = foreground != null,
              enabled = available,
              tag = "perfect.custom.foreground",
              onPick = {
                pickForeground = true
                picker.launch(arrayOf("image/png"))
              },
            )
          }

          val validationError = when {
            invalidPng -> R.string.perfect_custom_png_error
            duplicate -> R.string.perfect_custom_duplicate
            id.isNotEmpty() && !valid -> R.string.perfect_custom_id_error
            saveError -> R.string.perfect_custom_io_error
            else -> null
          }
          Os4AnimatedVisibility(validationError != null) {
            validationError?.let { res ->
              GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
                StaticError(res)
              }
            }
          }

          // 4. Primary Save + Optional Destructive Delete
          Os4DialogButton(
            text = stringResource(R.string.perfect_custom_save),
            onClick = {
              onSave(id, PerfectCustomStatic(requireNotNull(background), requireNotNull(foreground)))
            },
            modifier = Modifier.fillMaxWidth().testTag("perfect.custom.save"),
            enabled = canSave,
            role = GlassDialogButtonRole.Primary,
          )
          if (onDelete != null) {
            Os4DialogButton(
              text = stringResource(R.string.perfect_custom_delete),
              onClick = onDelete,
              modifier = Modifier.fillMaxWidth(),
              enabled = available,
              role = GlassDialogButtonRole.Destructive,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun InteractiveLayeredStage(
  background: ImageBitmap?,
  foreground: ImageBitmap?,
  backdropMode: StageBackdrop,
  onBackdropChange: (StageBackdrop) -> Unit,
  idReady: Boolean,
  bgReady: Boolean,
  fgReady: Boolean,
) {
  val scheme = MiuixTheme.colorScheme
  var rawDrag by remember { mutableStateOf(Offset.Zero) }
  val parallax by animateOffsetAsState(
    targetValue = rawDrag,
    animationSpec = folmeSpring(damping = 0.72f, response = 0.36f),
    label = "custom-layer-parallax",
  )
  val bothLayers = background != null && foreground != null

  GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
    Column(
      Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          stringResource(R.string.perfect_custom_composite),
          style = MiuixTheme.textStyles.footnote1,
          fontWeight = FontWeight.Medium,
          color = scheme.onSurfaceSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          StageBackdrop.entries.forEach { option ->
            val selected = option == backdropMode
            val chipBg by animateColorAsState(
              if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.onSurface.copy(alpha = 0.06f),
              Os4Motion.colorSpring(),
              label = "stage-bg-chip",
            )
            val chipText by animateColorAsState(
              if (selected) scheme.primary else scheme.onSurfaceSecondary,
              Os4Motion.colorSpring(),
              label = "stage-bg-text",
            )
            Box(
              Modifier.squircleBackground(chipBg, 10.dp)
                .os4Clickable(cornerRadius = 10.dp) { onBackdropChange(option) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
              Text(stringResource(option.labelRes), style = MiuixTheme.textStyles.footnote2, color = chipText)
            }
          }
        }
      }

      Box(
        Modifier.size(116.dp)
          .pointerInput(bothLayers) {
            if (!bothLayers) return@pointerInput
            detectDragGestures(
              onDragEnd = { rawDrag = Offset.Zero },
              onDragCancel = { rawDrag = Offset.Zero },
            ) { change, dragAmount ->
              change.consume()
              val maxPx = 12.dp.toPx()
              rawDrag = Offset(
                x = (rawDrag.x + dragAmount.x * 0.45f).coerceIn(-maxPx, maxPx),
                y = (rawDrag.y + dragAmount.y * 0.45f).coerceIn(-maxPx, maxPx),
              )
            }
          },
        contentAlignment = Alignment.Center,
      ) {
        StaticArtwork(
          modifier = Modifier.fillMaxSize(),
          layers = listOfNotNull(background, foreground),
          description = stringResource(R.string.perfect_custom_composite),
          backdropMode = backdropMode,
          cornerRadius = 24.dp,
          layerOffsets = if (bothLayers) {
            listOf(
              Offset(parallax.x * 0.25f, parallax.y * 0.25f),
              parallax,
            )
          } else emptyList(),
        )
      }

      Os4AnimatedVisibility(bothLayers) {
        Text(
          stringResource(R.string.perfect_custom_parallax_hint),
          style = MiuixTheme.textStyles.footnote2,
          color = scheme.primary,
          textAlign = TextAlign.Center,
        )
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        ReadinessChip(stringResource(R.string.perfect_custom_id), idReady)
        ReadinessChip("0.png", bgReady)
        ReadinessChip("1.png", fgReady)
      }
    }
  }
}

@Composable
private fun ReadinessChip(label: String, ready: Boolean) {
  val scheme = MiuixTheme.colorScheme
  val color by animateColorAsState(
    if (ready) scheme.primary else scheme.onSurfaceSecondary,
    Os4Motion.colorSpring(),
    label = "readiness-chip-color",
  )
  Row(
    Modifier.squircleBackground(color.copy(alpha = 0.12f), 10.dp)
      .padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (ready) Icon(MiuixIcons.Glass.Ok, contentDescription = null, modifier = Modifier.size(12.dp), tint = color)
    Text(label, style = MiuixTheme.textStyles.footnote2, color = color, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun StaticLayerCard(
  modifier: Modifier,
  title: String,
  file: String,
  bitmap: ImageBitmap?,
  loaded: Boolean,
  enabled: Boolean,
  tag: String,
  onPick: () -> Unit,
) {
  val scheme = MiuixTheme.colorScheme
  val accent by animateColorAsState(
    if (loaded) scheme.primary else scheme.onSurfaceSecondary,
    Os4Motion.colorSpring(),
    label = "layer-card-accent",
  )
  val border by animateColorAsState(
    if (loaded) scheme.primary.copy(alpha = 0.45f) else scheme.onSurface.copy(alpha = 0.12f),
    Os4Motion.colorSpring(),
    label = "layer-card-border",
  )
  GlassCard(
    modifier = modifier
      .squircleBorder(1.dp, border, 18.dp)
      .os4Clickable(enabled = enabled, role = Role.Button, cornerRadius = 18.dp, onClick = onPick)
      .testTag(tag),
    insideMargin = PaddingValues(12.dp),
  ) {
    Column(
      Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold, maxLines = 1,
          overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(file, style = MiuixTheme.textStyles.footnote2, color = accent, fontWeight = FontWeight.Medium)
          if (loaded) Icon(MiuixIcons.Glass.Ok, contentDescription = null, modifier = Modifier.size(13.dp), tint = accent)
        }
      }
      StaticArtwork(
        modifier = Modifier.size(84.dp),
        layers = listOfNotNull(bitmap),
        description = title,
        backdropMode = StageBackdrop.GRID,
      )
      Text(
        stringResource(if (loaded) R.string.perfect_custom_replace_layer else R.string.perfect_custom_tap_to_pick),
        style = MiuixTheme.textStyles.footnote2,
        color = accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun InSheetInstalledAppPicker(onPick: (String) -> Unit) {
  val context = LocalContext.current
  val query = rememberTextFieldState()
  var choices by remember { mutableStateOf<List<InstalledTargetChoice>?>(null) }
  var failed by remember { mutableStateOf(false) }

  LaunchedEffect(context) {
    try {
      choices = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        iconPackTargets(context).groupBy { it.packageName }.flatMap { (pkg, entries) ->
          val label = try {
            manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
          } catch (_: android.content.pm.PackageManager.NameNotFoundException) { pkg }
          (listOf(pkg) + entries.mapNotNull(::nativeLauncherActivityName)).distinct()
            .filter(PerfectCustomStatic::validId)
            .map { InstalledTargetChoice(pkg, label, it) }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
      }
    } catch (failure: Exception) {
      if (failure is CancellationException) throw failure
      failed = true
    }
  }

  val search = query.text.toString().trim()
  val matches = remember(choices, search) {
    choices.orEmpty().filter {
      it.label.contains(search, true) || it.targetId.contains(search, true)
    }
  }

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    GlassTextField(
      state = query,
      label = stringResource(R.string.perfect_custom_search_apps),
      lineLimits = TextFieldLineLimits.SingleLine,
      modifier = Modifier.fillMaxWidth(),
    )
    when {
      failed -> StaticError(R.string.perfect_custom_apps_error)
      choices == null -> Row(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
        Text(
          stringResource(R.string.perfect_custom_loading_apps),
          style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
      }
      matches.isEmpty() -> Text(
        stringResource(R.string.perfect_custom_no_apps),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
        modifier = Modifier.padding(16.dp),
      )
      else -> GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
          items(matches, key = { it.targetId }) { item ->
            val pressSource = rememberOs4PressSource()
            BasicComponent(
              title = item.label,
              summary = item.targetId,
              interactionSource = pressSource,
              startAction = { AppPickerThumbnail(context, item.packageName) },
              endActions = { Os4TrailingChevron() },
              onClick = { onPick(item.targetId) },
              modifier = Modifier.glassPress(pressSource),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AppPickerThumbnail(context: Context, packageName: String) {
  val icon by produceState<ImageBitmap?>(null, context, packageName) {
    value = withContext(Dispatchers.IO) {
      runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
      }.getOrNull()
    }
  }
  Crossfade(targetState = icon, modifier = Modifier.size(36.dp), animationSpec = tween(160),
    label = "picker-app-icon") { img ->
    if (img != null) Image(img, contentDescription = null, modifier = Modifier.fillMaxSize())
    else Os4ShimmerPlaceholder(Modifier.fillMaxSize(), cornerRadius = 9.dp)
  }
}

@Composable
private fun StaticArtwork(
  modifier: Modifier,
  layers: List<ImageBitmap>,
  description: String?,
  backdropMode: StageBackdrop = StageBackdrop.GRID,
  cornerRadius: Dp = 18.dp,
  layerOffsets: List<Offset> = emptyList(),
) {
  val scheme = MiuixTheme.colorScheme
  val bg by animateColorAsState(
    when (backdropMode) {
      StageBackdrop.GRID -> scheme.surfaceContainerHigh
      StageBackdrop.LIGHT -> Color(0xFFF2F3F7)
      StageBackdrop.DARK -> Color(0xFF1E2026)
    },
    Os4Motion.colorSpring(),
    label = "static-artwork-bg",
  )
  Box(
    modifier.squircleClip(cornerRadius)
      .squircleBackground(bg, cornerRadius)
      .squircleBorder(1.dp, scheme.onSurface.copy(alpha = 0.08f), cornerRadius),
    contentAlignment = Alignment.Center,
  ) {
    if (backdropMode == StageBackdrop.GRID) {
      Canvas(Modifier.matchParentSize()) {
        val tile = 8.dp.toPx()
        val tint = scheme.onSurface.copy(alpha = 0.065f)
        for (row in 0..(size.height / tile).toInt()) {
          val y = row * tile
          val h = (size.height - y).coerceIn(0f, tile)
          if (h <= 0f) continue
          for (column in (row % 2)..(size.width / tile).toInt() step 2) {
            val x = column * tile
            val w = (size.width - x).coerceIn(0f, tile)
            if (w <= 0f) continue
            drawRect(tint, Offset(x, y), Size(w, h))
          }
        }
      }
    }
    Crossfade(targetState = layers, animationSpec = tween(180), label = "static-artwork") { activeLayers ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (activeLayers.isEmpty()) {
          Icon(
            MiuixIcons.Glass.ImageOutline,
            contentDescription = description,
            modifier = Modifier.size(28.dp),
            tint = if (backdropMode == StageBackdrop.LIGHT) Color(0xFF666B75) else scheme.onSurfaceSecondary,
          )
        } else {
          activeLayers.forEachIndexed { index, layer ->
            val shift = layerOffsets.getOrNull(index) ?: Offset.Zero
            Image(
              layer,
              contentDescription = description.takeIf { index == 0 },
              modifier = Modifier.fillMaxSize().padding(6.dp).offset {
                IntOffset(shift.x.roundToInt(), shift.y.roundToInt())
              }.graphicsLayer {
                if (layerOffsets.isNotEmpty() && index == activeLayers.lastIndex) {
                  scaleX = 1.02f
                  scaleY = 1.02f
                }
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun rememberLayerPreview(bytes: ByteArray?): State<ImageBitmap?> {
  val bitmap = remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
  LaunchedEffect(bytes) {
    bitmap.value = withContext(Dispatchers.Default) { bytes?.let(::decodePreview)?.asImageBitmap() }
  }
  return bitmap
}

@Composable
private fun rememberStaticThumbnail(id: String, revision: Long, controller: PerfectPackController): List<ImageBitmap> {
  var layers by remember(id) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
  LaunchedEffect(id, revision, controller) {
    try {
      val pair = controller.readCustomStatic(id)
      layers = withContext(Dispatchers.Default) { pair.layers().mapNotNull { decodePreview(it)?.asImageBitmap() } }
    } catch (failure: Exception) {
      if (failure is CancellationException) throw failure
    }
  }
  return layers
}

private fun decodePreview(bytes: ByteArray): Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  val sample = Integer.highestOneBit((maxOf(bounds.outWidth, bounds.outHeight) / 192).coerceAtLeast(1))
  return BitmapFactory.decodeByteArray(
    bytes,
    0,
    bytes.size,
    BitmapFactory.Options().apply { inSampleSize = sample },
  )
}

@Composable
private fun StaticError(text: Int) {
  Text(stringResource(text), color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
}
