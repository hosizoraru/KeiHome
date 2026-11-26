package os.kei.hyper.home.ui.main

import android.graphics.Rect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import os.kei.hyper.home.iconpack.CustomIconMask
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.IconPackPreviews
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.components.Os4ShimmerPlaceholder
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.UsageHelp
import top.yukonga.miuix.kmp.preference.SwitchPreference
import os.kei.hyper.home.ui.components.Os4ViewTabs
import os.kei.hyper.home.ui.components.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun IconMaskSettingsScreen(model: HomeViewModel, onBack: () -> Unit) {
  val controller = model.iconMasks
  val state by controller.state.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val packs by model.iconPacks.collectAsStateWithLifecycle()
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  var actionHeight by remember { mutableIntStateOf(0) }
  var picking by rememberSaveable { mutableStateOf(false) }
  var pickerError by rememberSaveable { mutableStateOf(false) }
  val actionPadding = maxOf(100.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp)
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    picking = false
    if (uri != null) controller.importMask(uri)
  }
  LaunchedEffect(controller) { controller.load() }
  Scaffold(modifier = Modifier.testTag("icon-mask.page"), backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(R.string.icon_mask_title), scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back))
          }
        },
        actions = {
          val maskHelp = listOf(
            stringResource(R.string.icon_mask_priority),
            stringResource(R.string.icon_mask_disabled_note),
            stringResource(R.string.icon_mask_xml_note),
            stringResource(R.string.icon_mask_dynamic_note),
            stringResource(R.string.icon_mask_apply_note),
          ).joinToString("\n\n")
          os.kei.hyper.home.ui.components.Os4InfoPopup(
            title = stringResource(R.string.icon_mask_rules),
            summary = maskHelp,
            icon = top.yukonga.miuix.kmp.icon.MiuixIcons.Glass.UsageHelp,
          )
        })
    }
  }, floatingActionButton = {
    PageActionFab(stringResource(R.string.icon_mask_import), actionIcon(PageActionIcon.IMPORT),
      modifier = Modifier.testTag("icon-mask.import"), enabled = !state.busy && !picking,
      busy = state.operation == IconMaskOperation.IMPORT || picking,
      description = stringResource(R.string.icon_mask_import_description),
      onHeightChanged = { actionHeight = it }, onClick = {
        pickerError = false
        picking = true
        try {
          // File providers often expose transform_config.xml as application/octet-stream.
          picker.launch(arrayOf("*/*"))
        } catch (_: Exception) {
          picking = false
          pickerError = true
        }
      })
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      Column(Modifier.fillMaxSize().keiHomeTopBarScroll(behavior)
        .verticalScroll(rememberScrollState(), overscrollEffect = null).padding(
          start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 8.dp,
          bottom = insets.calculateBottomPadding() + actionPadding + 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconMaskSettingsContent(state, preferences.completeSystemIconConfig,
          onCompleteSystemIconConfig = model.preferences::setCompleteSystemIconConfig,
          onReset = controller::reset, pickerError = pickerError,
          comparison = { MaskIconComparison(packs, model.packPreviews, state.custom) })
      }
    }
  }
}

@Composable
internal fun IconMaskSettingsContent(
  state: IconMaskState,
  completeSystemIconConfig: Boolean,
  onCompleteSystemIconConfig: (Boolean) -> Unit,
  onReset: () -> Unit,
  pickerError: Boolean = false,
  comparison: @Composable () -> Unit = {},
) {
  var confirmReset by rememberSaveable { mutableStateOf(false) }
  val colors = MiuixTheme.colorScheme
  val sourceLabel = stringResource(if (state.custom == null) R.string.icon_mask_source_auto
    else R.string.icon_mask_source_custom)
  Card(Modifier.fillMaxWidth().os4ContentSize().testTag("icon-mask.status"), insideMargin = PaddingValues(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MaskShapePreview(state.custom?.pathData ?: state.defaultPathData)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.icon_mask_title), style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetadataPill(stringResource(if (completeSystemIconConfig) R.string.icon_mask_enabled
              else R.string.icon_mask_disabled), if (completeSystemIconConfig) colors.primary else colors.onSurfaceSecondary)
            MetadataPill(sourceLabel, colors.primary, Modifier.testTag("icon-mask.source"))
            if (state.busy) MetadataPill(stringResource(R.string.icon_mask_working), colors.primary)
          }
        }
      }
      Os4AnimatedVisibility(state.custom != null) {
        state.custom?.let {
          Text(it.name, style = MiuixTheme.textStyles.body2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("icon-mask.filename"), color = colors.onSurfaceSecondary)
        }
      }
      Os4AnimatedVisibility(state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("icon-mask.progress"))
      }
    }
  }
  val errorText = when {
    state.error == IconMaskOperation.IMPORT -> R.string.icon_mask_import_failed
    state.error == IconMaskOperation.RESET -> R.string.icon_mask_reset_failed
    state.error == IconMaskOperation.LOAD -> R.string.icon_mask_load_failed
    pickerError -> R.string.icon_mask_picker_failed
    else -> null
  }
  Os4AnimatedVisibility(errorText != null) {
    errorText?.let { res ->
      Card(Modifier.fillMaxWidth().testTag("icon-mask.error"), insideMargin = PaddingValues(16.dp)) {
        Text(stringResource(res), color = colors.error, style = MiuixTheme.textStyles.body2)
      }
    }
  }
  comparison()
  SmallTitle(stringResource(R.string.icon_mask_behavior))
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    SwitchPreference(title = stringResource(R.string.icon_mask_complete),
      summary = stringResource(R.string.icon_mask_complete_summary),
      checked = completeSystemIconConfig, enabled = !state.busy,
      onCheckedChange = onCompleteSystemIconConfig,
      modifier = Modifier.testTag("icon-mask.complete"))
    os.kei.hyper.home.ui.components.Os4InsetDivider(start = 16.dp)
    val restorePress = rememberOs4PressSource()
    val restoreEnabled = state.custom != null && !state.busy
    val restoreTint by animateColorAsState(
      if (restoreEnabled) colors.error else colors.onSurfaceSecondary,
      Os4Motion.colorSpring(),
      label = "icon-mask-restore-tint",
    )
    BasicComponent(title = stringResource(R.string.icon_mask_restore),
      summary = stringResource(R.string.icon_mask_restore_summary),
      enabled = restoreEnabled,
      holdDownState = confirmReset,
      interactionSource = restorePress,
      onClick = { confirmReset = true },
      endActions = {
        Icon(actionIcon(PageActionIcon.CLEAR), null, Modifier.size(22.dp), tint = restoreTint)
      }, modifier = Modifier.glassPress(restorePress, restoreEnabled).testTag("icon-mask.restore"))
  }
  Os4ActionDialog(show = confirmReset,
    onDismissRequest = { confirmReset = false },
    title = stringResource(R.string.icon_mask_restore_question),
    summary = stringResource(R.string.icon_mask_restore_note),
    icon = actionIcon(PageActionIcon.CLEAR),
    actions = listOf(
      Os4DialogAction(stringResource(R.string.icon_mask_cancel), onClick = { confirmReset = false },
        tag = "icon-mask.restore-cancel"),
      Os4DialogAction(stringResource(R.string.icon_mask_restore),
        onClick = { confirmReset = false; onReset() },
        role = GlassDialogButtonRole.Destructive, enabled = state.custom != null && !state.busy,
        tag = "icon-mask.restore-confirm")))
}

@Composable
private fun MaskIconComparison(
  catalog: IconPacksUiState,
  repository: IconPackPreviewRepository,
  custom: CustomIconMask?,
) {
  val packs = remember(SnapshotIdentity(catalog.packs)) { catalog.packs.filter { it.pack.entries.isNotEmpty() } }
  val previewRevision by repository.revision.collectAsStateWithLifecycle()
  var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
  val selectedIndex = packs.indexOfFirst { it.pack.id == selectedId }.coerceAtLeast(0)
  val selected = packs.getOrNull(selectedIndex)
  val darkFromSystem = isSystemInDarkTheme()
  var darkOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
  val dark = darkOverride ?: darkFromSystem
  val originalPolicy = remember { IconMaskPolicy(false) }
  val hyperOs4Policy = remember { IconMaskPolicy.hyperOs4Preview() }
  val customPolicy = remember(custom) { custom?.let { IconMaskPolicy(true, it) } }
  val labels = listOf(stringResource(R.string.icon_mask_compare_original),
    stringResource(R.string.icon_mask_compare_hyperos4), stringResource(R.string.icon_mask_compare_custom))
  SmallTitle(stringResource(R.string.icon_mask_comparison))
  Card(Modifier.fillMaxWidth().os4ContentSize().testTag("icon-mask.comparison"), insideMargin = PaddingValues(0.dp)) {
    Crossfade(targetState = selected == null, animationSpec = tween(200), label = "icon-mask-comparison") { empty ->
      if (empty) {
        Text(stringResource(if (catalog.loading) R.string.icon_mask_catalog_loading else R.string.icon_mask_no_packs),
          modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
      } else {
        val currentSelected = selected ?: return@Crossfade
        Column {
          val items = remember(SnapshotIdentity(packs)) { packs.map { DropdownItem(text = it.pack.displayName) } }
          OverlaySpinnerPreference(title = stringResource(R.string.icon_mask_compare_pack),
            items = items, selectedIndex = selectedIndex, enabled = packs.size > 1,
            modifier = Modifier.testTag("icon-mask.pack"),
            onSelectedIndexChange = { index -> packs.getOrNull(index)?.let { selectedId = it.pack.id } })
          Os4ViewTabs(items = listOf(stringResource(R.string.monet_preview_light),
            stringResource(R.string.monet_preview_dark)), selectedIndex = if (dark) 1 else 0,
            onSelect = { darkOverride = it == 1 },
            modifier = Modifier.padding(16.dp).testTag("icon-mask.compare-dark"))
          val samples = remember(currentSelected) {
            currentSelected.previewEntries.ifEmpty { currentSelected.pack.entries }.asSequence().distinctBy { it.key }.take(3).toList()
          }
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              labels.forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                  Text(label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
              }
            }
            samples.forEach { entry ->
              Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  listOf(originalPolicy, hyperOs4Policy, customPolicy).forEachIndexed { index, policy ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                      if (policy == null) Text(stringResource(R.string.icon_mask_compare_no_custom),
                        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                      else MaskComparisonIcon(currentSelected.pack, entry, repository, policy, dark, labels[index], previewRevision,
                        Modifier.testTag("icon-mask.compare.$index.${entry.key}"))
                    }
                  }
                }
                Text(entry.component?.packageName ?: entry.nativeName ?: entry.key,
                  style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                  maxLines = 1, overflow = TextOverflow.Ellipsis,
                  textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
              }
            }
            Text(stringResource(R.string.icon_mask_compare_note), style = MiuixTheme.textStyles.footnote1,
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Text(stringResource(R.string.icon_mask_compare_legacy), style = MiuixTheme.textStyles.footnote1,
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
          }
        }
      }
    }
  }
}

@Composable
internal fun MaskComparisonIcon(
  pack: IconPackDescriptor,
  entry: IconPackEntry,
  repository: IconPackPreviewRepository,
  policy: IconMaskPolicy,
  dark: Boolean,
  label: String,
  revision: Int,
  modifier: Modifier,
) {
  val configuration = LocalConfiguration.current.toString()
  val sizePx = with(LocalDensity.current) { 50.dp.roundToPx() }.coerceIn(32, 192)
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val preview by produceState<IconPackPreviews?>(null, pack, entry, policy, configuration, sizePx, lifecycle, revision) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      value = try {
        repository.load(pack, entry, sizePx = sizePx, maskPolicy = policy, preservedPackages = emptySet())
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        IconPackPreviews()
      }
      awaitCancellation()
    }
  }
  val artwork = if (dark) preview?.dark else preview?.light
  val bitmap = remember(artwork) { artwork?.asImageBitmap() }
  val tileBg by animateColorAsState(
    if (dark) Color(0xff232428) else Color(0xfff0f1f4),
    Os4Motion.colorSpring(),
    label = "mask-compare-bg",
  )
  // No clip or shape is added by the UI: every column shows the shared renderer's output.
  Box(modifier.size(56.dp).background(tileBg), contentAlignment = Alignment.Center) {
    Crossfade(targetState = bitmap to (preview == null), animationSpec = tween(180), label = "mask-compare-icon") { (image, loading) ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
          image != null -> Image(image, label, Modifier.size(50.dp))
          loading -> Os4ShimmerPlaceholder(Modifier.size(44.dp), cornerRadius = 10.dp)
          else -> Text(stringResource(R.string.icon_mask_compare_unavailable),
            style = MiuixTheme.textStyles.footnote2, color = if (dark) Color.White else Color.DarkGray)
        }
      }
    }
  }
}

@Composable
private fun MaskShapePreview(pathData: String?) {
  val mask = remember(pathData) {
    pathData?.let { runCatching { NativeAdaptiveIconMask.fromPathData("mask-settings-preview", it) }.getOrNull() }
  }
  val color = MiuixTheme.colorScheme.primary
  val description = stringResource(R.string.icon_mask_shape)
  Box(Modifier.size(72.dp).squircleBackground(MiuixTheme.colorScheme.surfaceContainer, 16.dp)
    .testTag("icon-mask.shape").semantics { contentDescription = description },
    contentAlignment = Alignment.Center) {
    Crossfade(targetState = mask, animationSpec = tween(200), label = "mask-shape-preview") { activeMask ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (activeMask != null) Canvas(Modifier.size(56.dp)) {
          val path = activeMask.pathForBounds(Rect(0, 0, size.width.toInt(), size.height.toInt())).asComposePath()
          drawPath(path, Brush.linearGradient(listOf(color, color.copy(alpha = 0.45f))))
        }
        else Text(stringResource(R.string.icon_mask_shape_unavailable),
          style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary,
          modifier = Modifier.padding(8.dp))
      }
    }
  }
}
