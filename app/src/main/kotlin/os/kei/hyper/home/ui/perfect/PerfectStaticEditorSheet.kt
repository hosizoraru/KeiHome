package os.kei.hyper.home.ui.perfect

import android.content.pm.LauncherApps
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.nativeLauncherActivityName
import os.kei.hyper.home.iconpack.perfect.PerfectCustomStatic
import os.kei.hyper.home.ui.components.LocalKeiHomeBackdrop
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4DialogButton
import os.kei.hyper.home.ui.components.Os4ShimmerPlaceholder
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import os.kei.hyper.home.ui.main.HomeViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.GridView
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class StaticDraft(val id: String? = null, val pair: PerfectCustomStatic? = null)

private class LayerDraft(val bytes: ByteArray, val preview: ImageBitmap?)

private data class InstalledTargetChoice(
  val packageName: String,
  val label: String,
  val targetId: String,
  val capability: InstalledIconCapability,
)

@Composable
internal fun PerfectStaticEditorSheet(
  vm: HomeViewModel,
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
  var background by remember { mutableStateOf(draft.pair?.background?.let { LayerDraft(it, null) }) }
  var foreground by remember { mutableStateOf(draft.pair?.foreground?.let { LayerDraft(it, null) }) }
  var reading by remember { mutableStateOf(false) }
  var invalidPng by remember { mutableStateOf(false) }
  var pickForeground by rememberSaveable { mutableStateOf(false) }
  var pickingApp by remember { mutableStateOf(false) }
  var stageBackdrop by rememberSaveable { mutableStateOf(StageBackdrop.GRID) }

  // Decode pre-populated disk layers once on entry without blocking sheet open.
  LaunchedEffect(draft.pair) {
    val initial = draft.pair ?: return@LaunchedEffect
    val (bgImg, fgImg) = withContext(Dispatchers.Default) {
      decodeCustomStaticPreview(initial.background)?.asImageBitmap() to
        decodeCustomStaticPreview(initial.foreground)?.asImageBitmap()
    }
    if (background?.bytes === initial.background) background = LayerDraft(initial.background, bgImg)
    if (foreground?.bytes === initial.foreground) foreground = LayerDraft(initial.foreground, fgImg)
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      val replaceForeground = pickForeground
      reading = true
      invalidPng = false
      scope.launch {
        try {
          val decoded = withContext(Dispatchers.IO) {
            val bytes = PerfectCustomStatic.readLayer(requireNotNull(context.contentResolver.openInputStream(uri)))
            val preview = requireNotNull(decodeCustomStaticPreview(bytes)).asImageBitmap()
            LayerDraft(bytes, preview)
          }
          if (replaceForeground) foreground = decoded else background = decoded
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
            if (canSave) {
              onSave(id, PerfectCustomStatic(requireNotNull(background).bytes, requireNotNull(foreground).bytes))
            }
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
          vm = vm,
          onPick = { pickedId ->
            name.setTextAndPlaceCursorAtEnd(pickedId)
            pickingApp = false
          },
        )
      } else {
        val backgroundPreview = background?.preview
        val foregroundPreview = foreground?.preview
        Column(Modifier.fillMaxWidth().os4ContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          InteractiveLayeredStage(
            background = backgroundPreview,
            foreground = foregroundPreview,
            backdropMode = stageBackdrop,
            onBackdropChange = { stageBackdrop = it },
            idReady = valid && !duplicate,
            bgReady = background != null,
            fgReady = foreground != null,
          )

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

          Os4DialogButton(
            text = stringResource(R.string.perfect_custom_save),
            onClick = {
              onSave(id, PerfectCustomStatic(requireNotNull(background).bytes, requireNotNull(foreground).bytes))
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
private fun InSheetInstalledAppPicker(vm: HomeViewModel, onPick: (String) -> Unit) {
  val context = LocalContext.current
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  val apps by vm.apps.collectAsStateWithLifecycle()
  val query = rememberTextFieldState()
  var choices by remember { mutableStateOf<List<InstalledTargetChoice>?>(null) }
  var failed by remember { mutableStateOf(false) }

  LaunchedEffect(vm, locales) { vm.ensureAppsLoaded(locales) }

  val capabilities = apps.capabilities
  LaunchedEffect(context, capabilities) {
    if (capabilities == null) {
      choices = null
      return@LaunchedEffect
    }
    failed = false
    try {
      choices = withContext(Dispatchers.IO) {
        val launcherActivitiesByPkg = runCatching {
          context.getSystemService(LauncherApps::class.java)
            ?.getActivityList(null, Process.myUserHandle())
            .orEmpty()
            .groupBy({ it.componentName.packageName }) {
              IconPackComponent(it.componentName.packageName, it.componentName.className)
            }
        }.getOrDefault(emptyMap())
        capabilities.asSequence()
          .filterNot { it.overlayApp }
          .filter { it.hasLauncher || it.systemApp }
          .groupBy { it.packageName }
          .flatMap { (pkg, entries) ->
            val preferred = entries.firstOrNull { it.personalProfile } ?: entries.first()
            val components = launcherActivitiesByPkg[pkg].orEmpty() +
              entries.mapNotNull { it.componentName?.let { cls -> IconPackComponent(pkg, cls) } }
            (listOf(pkg) + components.mapNotNull(::nativeLauncherActivityName))
              .distinct()
              .filter(PerfectCustomStatic::validId)
              .map { targetId -> InstalledTargetChoice(pkg, preferred.label, targetId, preferred) }
          }
          .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
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
      failed || (capabilities == null && apps.error != null) -> StaticError(R.string.perfect_custom_apps_error)
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
              startAction = { AppPickerThumbnail(vm, item.capability) },
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
private fun AppPickerThumbnail(vm: HomeViewModel, capability: InstalledIconCapability) {
  val darkTheme = isSystemInDarkTheme()
  val icon by produceState<ImageBitmap?>(null, vm, capability, darkTheme) {
    val previews = vm.previews.load(capability, sizePx = 96)
    val bitmap = if (darkTheme) previews.dark ?: previews.light else previews.light ?: previews.dark
    value = bitmap?.asImageBitmap()
  }
  Crossfade(targetState = icon, modifier = Modifier.size(36.dp), animationSpec = tween(160),
    label = "picker-app-icon") { img ->
    if (img != null) Image(img, contentDescription = null, modifier = Modifier.fillMaxSize())
    else Os4ShimmerPlaceholder(Modifier.fillMaxSize(), cornerRadius = 9.dp)
  }
}
