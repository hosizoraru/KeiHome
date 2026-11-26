package os.kei.hyper.home.ui.perfect

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.data.iconPackTargets
import os.kei.hyper.home.iconpack.nativeLauncherActivityName
import os.kei.hyper.home.iconpack.perfect.PerfectCustomStatic
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar
import os.kei.hyper.home.ui.components.LocalKeiHomeBackdrop
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4DialogButton
import os.kei.hyper.home.ui.components.Os4LazyColumn
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.ImageOutline
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import os.kei.hyper.home.ui.main.PageActionIcon
import os.kei.hyper.home.ui.main.actionIcon

private data class StaticDraft(val id: String? = null, val pair: PerfectCustomStatic? = null)

@Composable
internal fun PerfectCustomStaticScreen(controller: PerfectPackController, backdrop: LayerBackdrop?,
  bottomBar: @Composable () -> Unit, onBack: () -> Unit, active: Boolean, onGenerate: () -> Unit) {
  val state by controller.state.collectAsStateWithLifecycle()
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
  KeiHomeScaffold(backdrop = backdrop, modifier = Modifier.testTag("perfect.custom"), topBar = {
    BlurredBar(backdrop, backdrop != null) {
      KeiHomeTopAppBar(stringResource(R.string.perfect_custom_static), scrollBehavior = behavior,
        navigationIcon = {
          KeiHomeIconButton(onClick = onBack) { Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back)) }
        }, actions = {
          if (state.customStaticIds.isNotEmpty()) {
            KeiHomeIconButton(enabled = !busy, onClick = {
              error = false
              draft = StaticDraft()
              showDraft = true
            }) {
              Icon(actionIcon(PageActionIcon.CUSTOMIZE), stringResource(R.string.perfect_custom_add))
            }
          }
        })
    }
  }) { insets ->
    Os4LazyColumn(
      state = rememberLazyListState(),
      modifier = Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
        .keiHomeTopBarScroll(behavior),
      topAppBarScrollBehavior = behavior,
      contentPadding = PaddingValues(start = insets.calculateStartPadding(direction) + 16.dp,
        end = insets.calculateEndPadding(direction) + 16.dp, top = insets.calculateTopPadding() + 12.dp,
        bottom = insets.calculateBottomPadding() + 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (state.customStaticIds.isEmpty()) item {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
          Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StaticArtwork(Modifier.size(88.dp), emptyList(), null)
            Text(stringResource(R.string.perfect_custom_pair_help), textAlign = TextAlign.Center,
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Os4DialogButton(stringResource(R.string.perfect_custom_add),
              onClick = { error = false; draft = StaticDraft(); showDraft = true },
              modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("perfect.custom.add"),
              enabled = !busy, role = GlassDialogButtonRole.Primary)
          }
        }
      }
      items(state.customStaticIds, key = { it }) { id ->
        val artwork = rememberStaticThumbnail(id, state.customStaticRevision, controller)
        val pressSource = rememberOs4PressSource()
        Card(Modifier.fillMaxWidth().animateItem(), insideMargin = PaddingValues(0.dp)) {
          BasicComponent(title = id, enabled = !busy,
            interactionSource = pressSource,
            modifier = Modifier.glassPress(pressSource, !busy),
            startAction = {
              StaticArtwork(Modifier.size(48.dp), artwork, null)
            },
            endActions = { Os4TrailingChevron() },
            onClick = { edit { draft = StaticDraft(id, controller.readCustomStatic(id)); showDraft = true } })
        }
      }
      if (state.customStaticIds.isNotEmpty()) item {
        Os4DialogButton(stringResource(R.string.action_label_generate), onGenerate,
          modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
          role = GlassDialogButtonRole.Primary)
      }
      if (error && draft == null) item { StaticError(R.string.perfect_custom_io_error) }
      item { bottomBar() }
    }
  }
  CompositionLocalProvider(LocalKeiHomeBackdrop provides backdrop) {
    draft?.let { current ->
      key(current) {
        PerfectStaticEditor(current, show = active && showDraft, busy = busy, saveError = error,
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
          onSave = { id, pair -> edit {
            controller.saveCustomStatic(id, pair, create = current.id == null)
            showDraft = false
          } },
          onDelete = current.id?.let { id -> { deleteAfterDraft = id; showDraft = false } })
      }
    }
    Os4ActionDialog(show = active && showDeleteConfirm && pendingDelete != null,
      onDismissRequest = { if (!busy) showDeleteConfirm = false },
      onDismissFinished = { if (!showDeleteConfirm) pendingDelete = null },
      title = stringResource(R.string.perfect_custom_delete), summary = pendingDelete.orEmpty(),
      icon = actionIcon(PageActionIcon.CLEAR),
      actions = listOf(
        Os4DialogAction(stringResource(android.R.string.cancel), { showDeleteConfirm = false }, enabled = !busy),
        Os4DialogAction(stringResource(R.string.perfect_custom_delete), {
          pendingDelete?.let { id -> edit { controller.deleteCustomStatic(id); showDeleteConfirm = false } }
        }, role = GlassDialogButtonRole.Destructive, enabled = !busy),
      ), footer = if (error) ({ StaticError(R.string.perfect_custom_io_error) }) else null)
  }
}

@Composable
private fun PerfectStaticEditor(draft: StaticDraft, show: Boolean, busy: Boolean, saveError: Boolean, existing: List<String>,
  onClose: () -> Unit, onDismissFinished: () -> Unit,
  onSave: (String, PerfectCustomStatic) -> Unit, onDelete: (() -> Unit)?) {
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
  var chooseApp by remember { mutableStateOf(false) }
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
  Os4ActionDialog(show = show && !chooseApp, onDismissRequest = { if (available) onClose() },
    onDismissFinished = { if (!show && !chooseApp) onDismissFinished() },
    title = stringResource(if (draft.id == null) R.string.perfect_custom_add else R.string.perfect_custom_edit),
    actions = listOf(
      Os4DialogAction(stringResource(android.R.string.cancel), onClose, enabled = available),
      Os4DialogAction(stringResource(R.string.perfect_custom_save), {
        onSave(id, PerfectCustomStatic(requireNotNull(background), requireNotNull(foreground)))
      }, role = GlassDialogButtonRole.Primary,
        enabled = available && valid && !duplicate && background != null && foreground != null,
        tag = "perfect.custom.save"),
    ),
    details = {
      Column(Modifier.fillMaxWidth().os4ContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (draft.id == null) {
          GlassTextField(state = name, label = stringResource(R.string.perfect_custom_id),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("perfect.custom.id"), enabled = available)
          Text(stringResource(R.string.perfect_custom_id_help), style = MiuixTheme.textStyles.footnote1)
          BasicComponent(title = stringResource(R.string.perfect_custom_choose_app),
            enabled = available, onClick = { chooseApp = true }, endActions = { Os4TrailingChevron() },
            modifier = Modifier.fillMaxWidth())
        } else {
          Text(id, style = MiuixTheme.textStyles.body1)
        }
        val backgroundPreview by rememberLayerPreview(background)
        val foregroundPreview by rememberLayerPreview(foreground)
        GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
          Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StaticArtwork(Modifier.size(108.dp), listOfNotNull(backgroundPreview, foregroundPreview),
              stringResource(R.string.perfect_custom_composite))
            Text(stringResource(R.string.perfect_custom_composite), style = MiuixTheme.textStyles.footnote1,
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
          }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          StaticLayerTile(Modifier.weight(1f), stringResource(R.string.perfect_custom_background), "0.png",
            backgroundPreview, available, "perfect.custom.background", onPick = {
              pickForeground = false
              picker.launch(arrayOf("image/png"))
            })
          StaticLayerTile(Modifier.weight(1f), stringResource(R.string.perfect_custom_foreground), "1.png",
            foregroundPreview, available, "perfect.custom.foreground", onPick = {
              pickForeground = true
              picker.launch(arrayOf("image/png"))
            })
        }
        if (onDelete != null) {
          BasicComponent(title = stringResource(R.string.perfect_custom_delete), enabled = available, onClick = onDelete)
        }
        when {
          invalidPng -> StaticError(R.string.perfect_custom_png_error)
          duplicate -> StaticError(R.string.perfect_custom_duplicate)
          id.isNotEmpty() && !valid -> StaticError(R.string.perfect_custom_id_error)
          saveError -> StaticError(R.string.perfect_custom_io_error)
        }
      }
    })
  PerfectStaticTargetPicker(show = show && chooseApp, onClose = { chooseApp = false }, onPick = {
    name.setTextAndPlaceCursorAtEnd(it)
    chooseApp = false
  })
}

@Composable
private fun PerfectStaticTargetPicker(show: Boolean, onClose: () -> Unit, onPick: (String) -> Unit) {
  val context = LocalContext.current
  val query = rememberTextFieldState()
  var choices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
  var failed by remember { mutableStateOf(false) }
  LaunchedEffect(context, show) {
    if (!show || choices != null) return@LaunchedEffect
    try {
      choices = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        iconPackTargets(context).groupBy { it.packageName }.flatMap { (pkg, entries) ->
          val label = try {
            manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
          } catch (_: android.content.pm.PackageManager.NameNotFoundException) { pkg }
          (listOf(pkg) + entries.mapNotNull(::nativeLauncherActivityName)).distinct()
            .filter(PerfectCustomStatic::validId).map { label to it }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
      }
    } catch (failure: Exception) {
      if (failure is CancellationException) throw failure
      failed = true
    }
  }
  val search = query.text.toString().trim()
  val matches = remember(choices, search) {
    choices.orEmpty().filter { (label, id) -> label.contains(search, true) || id.contains(search, true) }
  }
  Os4ActionDialog(show = show, onDismissRequest = onClose,
    title = stringResource(R.string.perfect_custom_choose_app),
    actions = listOf(Os4DialogAction(stringResource(android.R.string.cancel), onClose)),
    details = {
      GlassTextField(state = query, label = stringResource(R.string.perfect_custom_search_apps),
        lineLimits = TextFieldLineLimits.SingleLine, modifier = Modifier.fillMaxWidth())
      when {
        failed -> StaticError(R.string.perfect_custom_apps_error)
        choices == null -> Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
          Text(stringResource(R.string.perfect_custom_loading_apps))
        }
        matches.isEmpty() -> Text(stringResource(R.string.perfect_custom_no_apps))
        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 264.dp)) {
          items(matches) { (label, id) -> BasicComponent(title = label, summary = id, onClick = { onPick(id) }) }
        }
      }
    })
}

@Composable
private fun StaticLayerTile(modifier: Modifier, title: String, file: String, bitmap: ImageBitmap?,
  enabled: Boolean, tag: String, onPick: () -> Unit) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    StaticArtwork(Modifier.fillMaxWidth().aspectRatio(1.65f), listOfNotNull(bitmap), title)
    Os4DialogButton(title, onPick, Modifier.fillMaxWidth().testTag(tag), enabled)
    Text(file, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1,
      overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
}

@Composable
private fun StaticArtwork(modifier: Modifier, layers: List<ImageBitmap>, description: String?) {
  val scheme = MiuixTheme.colorScheme
  Box(modifier.squircleBackground(scheme.surfaceContainerHigh, 16.dp),
    contentAlignment = Alignment.Center) {
    Crossfade(targetState = layers, animationSpec = tween(180), label = "static-artwork") { activeLayers ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (activeLayers.isEmpty()) {
          Icon(MiuixIcons.Glass.ImageOutline, description, Modifier.size(30.dp), tint = scheme.onSurfaceSecondary)
        } else {
          Canvas(Modifier.matchParentSize()) {
            val tile = 8.dp.toPx()
            val tint = scheme.onSurface.copy(alpha = 0.07f)
            for (row in 0..(size.height / tile).toInt()) {
              for (column in (row % 2)..(size.width / tile).toInt() step 2) {
                drawRect(tint, Offset(column * tile, row * tile), Size(tile, tile))
              }
            }
          }
          activeLayers.forEachIndexed { index, layer ->
            Image(layer, description.takeIf { index == 0 }, Modifier.fillMaxSize().padding(6.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun rememberLayerPreview(bytes: ByteArray?): androidx.compose.runtime.State<ImageBitmap?> {
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

private fun decodePreview(bytes: ByteArray): android.graphics.Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  val sample = Integer.highestOneBit((maxOf(bounds.outWidth, bounds.outHeight) / 192).coerceAtLeast(1))
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
    BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun StaticError(text: Int) {
  Text(stringResource(text), color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
}
