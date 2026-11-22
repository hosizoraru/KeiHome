package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppPreferencesState
import os.kei.hyper.home.data.MonetActionStatus
import os.kei.hyper.home.data.MonetCustomization
import os.kei.hyper.home.data.MonetSchemeStyle
import os.kei.hyper.home.status.RuntimeStatusUiState
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.UsageHelp
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val MonetTabs = listOf(MonetSection.OVERVIEW, MonetSection.COLORS, MonetSection.DETAILS, MonetSection.INSTALL)

@Composable
internal fun MonetDestinationScreen(model: HomeViewModel, onBack: () -> Unit, onOpenPacks: () -> Unit) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(model, lifecycle, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observePage(2, locales) }
  }
  val monet by model.monet.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val browser by model.browserSource.collectAsStateWithLifecycle()
  val colorOsComponents = runtime.report?.iconTheme?.localId?.let { preferences.colorOsComponents[it] }
  val ordinary = colorOsComponents == false
  val paletteNames = browser.takeIf { it?.revision == runtime.report?.iconTheme?.previewKey }?.paletteNames
  val locked = colorOsPaletteLocked(ordinary, paletteNames)
  val inspiration = preferences.monetInspiration ?:
    (monet.snapshot?.customization?.style == MonetSchemeStyle.COLOROS_INSPIRATION || colorOsComponents == true)

  val pager = rememberPagerState { MonetTabs.size }
  val scope = rememberCoroutineScope()
  val navigation = remember(pager, scope) { TabPagerNavigation(pager, scope) }
  val items = listOf(
    NavigationItem(label = stringResource(R.string.monet_tab_overview), icon = actionIcon(PageActionIcon.MONET)),
    NavigationItem(label = stringResource(R.string.monet_page_colors), icon = actionIcon(PageActionIcon.CUSTOMIZE)),
    NavigationItem(label = stringResource(R.string.monet_page_details), icon = MiuixIcons.Glass.Info),
    NavigationItem(label = stringResource(R.string.monet_page_install), icon = actionIcon(PageActionIcon.UPDATE)),
  )
  var help by rememberSaveable { mutableStateOf(false) }
  var actionHeight by remember { mutableIntStateOf(0) }
  val actionClearance = with(LocalDensity.current) { actionHeight.toDp() }.coerceAtLeast(76.dp) + 24.dp
  MonetPicker(snapshot = monet.snapshot, readFailed = monet.readFailed,
    preferredStyle = if (inspiration) MonetSchemeStyle.COLOROS_INSPIRATION else null,
    preferredAppearance = preferences.iconPackAppearance,
    familiesExpanded = preferences.monetPalettesExpanded,
    onFamiliesExpandedChange = model.preferences::setMonetPalettesExpanded,
    iconsExpanded = preferences.monetIconColorsExpanded,
    onIconsExpandedChange = model.preferences::setMonetIconColorsExpanded,
    actionAvailable = !locked && framework.connected && !framework.readFailed &&
      framework.snapshot?.target?.loadedVersionCode == BuildConfig.VERSION_CODE.toLong(),
    actionStatus = monet.action, submittedChoice = monet.submittedChoice,
  ) { applyState, renderSection, panels ->
    PackTabScaffold(model, pager, navigation, items, "icon-packs.monet",
      floatingActionButton = {
        MonetApplyAction(applyState, model::applyMonet, Modifier.onSizeChanged { actionHeight = it.height })
      }, overlays = {
        panels()
        MonetHelp(show = help, onDismiss = { help = false })
      },
    ) { index, backdrop, bottomInset ->
      val section = MonetTabs[index]
      MonetTabPage(model, section, backdrop, bottomInset, actionClearance, onBack, onHelp = { help = true }) {
        when (section) {
          MonetSection.OVERVIEW -> {
            MonetOverview(model, monet, framework, ordinary, locked, inspiration)
            renderSection(section)
          }
          MonetSection.INSTALL -> MonetInstall(model, framework, runtime, preferences, inspiration, onOpenPacks)
          else -> renderSection(section)
        }
        RefreshFeedback(model, 2)
      }
    }
  }
}

@Composable
private fun MonetTabPage(model: HomeViewModel, section: MonetSection, backdrop: LayerBackdrop?,
  bottomBar: @Composable () -> Unit, actionClearance: Dp,
  onBack: () -> Unit, onHelp: () -> Unit, content: @Composable () -> Unit) {
  val behavior = MiuixScrollBehavior()
  val scrollState = rememberScrollState()
  Scaffold(backdrop = backdrop, bottomBar = bottomBar,
    modifier = Modifier.testTag("monet.page.${section.name}"),
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(
          title = stringResource(section.title),
          scrollBehavior = behavior,
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          navigationIcon = { IconButton(onClick = onBack) {
            Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
          } },
          actions = {
            IconButton(onClick = onHelp, modifier = Modifier.testTag("monet.help.open")) {
              Icon(MiuixIcons.Glass.UsageHelp, stringResource(R.string.pack_guidance_title))
            }
            RefreshAction(model, 2, Modifier.testTag("monet.page.refresh"))
          },
        )
      }
    },
  ) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      Column(
        modifier = Modifier.fillMaxSize().keiHomeTopBarScroll(behavior)
          .verticalScroll(scrollState, overscrollEffect = null).padding(
            start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 4.dp,
            bottom = insets.calculateBottomPadding() + actionClearance),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) { content() }
    }
  }
}

@Composable
private fun MonetApplyAction(state: MonetApplyUiState, apply: (MonetCustomization) -> Unit,
  modifier: Modifier = Modifier) {
  Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    state.notice?.let { notice ->
      Card(Modifier.widthIn(max = 300.dp).testTag("monet.apply.status")
        .semantics { liveRegion = LiveRegionMode.Polite }, insideMargin = PaddingValues(12.dp)) {
        Text(stringResource(notice), style = MiuixTheme.textStyles.footnote1,
          color = if (state.warning) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
    PageActionFab(stringResource(R.string.monet_apply_scheme), actionIcon(PageActionIcon.APPLY),
      modifier = Modifier.testTag("monet.apply"), enabled = state.enabled, busy = state.busy,
      onClick = { apply(state.choice) })
  }
}

@Composable
private fun MonetHelp(show: Boolean, onDismiss: () -> Unit) {
  Os4DetailsSheet(show = show, onDismissRequest = onDismiss,
    title = stringResource(R.string.monet_help_title), modifier = Modifier.testTag("monet.help"),
  ) {
    for (text in listOf(stringResource(R.string.monet_page_help), stringResource(R.string.monet_help_apply_note),
      stringResource(R.string.monet_help_inspiration_note), stringResource(R.string.monet_scope_hint),
      stringResource(R.string.monet_unadapted_help), stringResource(R.string.monet_details_contrast_note))) {
      Text(text, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}

@Composable
private fun MonetOverview(model: HomeViewModel, monet: MonetUiState, framework: FrameworkUiState,
  ordinary: Boolean, locked: Boolean, inspiration: Boolean) {
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    top.yukonga.miuix.kmp.preference.SwitchPreference(
      title = stringResource(R.string.pack_monet_toggle),
      summary = stringResource(if (locked) R.string.pack_monet_ordinary_locked
        else if (ordinary) R.string.pack_monet_fallback_summary else R.string.pack_monet_toggle_summary),
      checked = monet.snapshot?.monochrome?.enabled == true,
      enabled = !locked && framework.connected && monet.snapshot != null &&
        framework.snapshot?.target?.loadedVersionCode == BuildConfig.VERSION_CODE.toLong() &&
        monet.action != MonetActionStatus.APPLYING,
      onCheckedChange = model::setMonetEnabled)
  }
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    os.kei.hyper.home.ui.components.OverlaySpinnerPreference(
      title = stringResource(R.string.pack_monet_design),
      items = listOf("Pixel / AOSP", stringResource(R.string.pack_style_inspiration)).map { DropdownItem(text = it) },
      selectedIndex = if (inspiration) 1 else 0,
      enabled = monet.action != MonetActionStatus.APPLYING,
      onSelectedIndexChange = { model.selectMonetDesign(it == 1) })
  }
}

@Composable
private fun MonetInstall(model: HomeViewModel, framework: FrameworkUiState, runtime: RuntimeStatusUiState,
  preferences: AppPreferencesState, inspiration: Boolean, onOpenPacks: () -> Unit) {
  val packs by model.iconPacks.collectAsStateWithLifecycle()
  val ready = framework.connected && framework.snapshot?.themesInScope == true &&
    runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong()
  val editable = packs.nativeImporting == null
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    if (inspiration) IconPackAppearanceControl(preferences.iconPackAppearance,
      model.preferences::setIconPackAppearance, editable, compact = true)
    MonetArtworkPreferences(
      enabled = preferences.monetUnadaptedIcons,
      padding = preferences.monetFullBleedIconPadding,
      editable = editable,
      onEnabledChange = model.preferences::setMonetUnadaptedIcons,
      onPaddingChange = model.preferences::setMonetFullBleedIconPadding)
  }
  val theme = runtime.report?.iconTheme
  val source = model.currentFallbackSource(theme, packs, preferences.colorOsComponents)
  val sourceId = source?.let { preferences.systemIconFallback.sourceId(it.id) }
  val pending = pendingNativeApplication(packs, theme?.localId)
    ?.takeIf { it.sourceId == sourceId }
  val updateNeeded by model.iconPackUpdateNeeded.collectAsStateWithLifecycle()
  val busy = packs.importing || packs.preparing || packs.nativeImporting != null || packs.removing != null
  Card(Modifier.fillMaxWidth().testTag("monet.artwork.pack"),
    insideMargin = PaddingValues(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.monet_artwork_pack_title), style = MiuixTheme.textStyles.body1)
      Text(if (source != null) stringResource(R.string.monet_artwork_update_steps, source.displayName)
        else stringResource(R.string.monet_artwork_choose_steps),
        style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      if (source != null && updateNeeded && pending == null) {
        MetadataPill(stringResource(R.string.pack_update_available_short), MiuixTheme.colorScheme.primary)
      }
      if (source != null) {
        PackActionButton(stringResource(R.string.monet_artwork_update_pack),
          onClick = model::updateCurrentSystemFallback,
          modifier = Modifier.fillMaxWidth().testTag("monet.artwork.update"),
          enabled = ready && !runtime.readFailed && !runtime.timedOut && !busy,
          primary = true, operation = PageActionIcon.UPDATE)
        if (framework.snapshot?.themesInScope == false) {
          Text(stringResource(R.string.icon_pack_scope_needed), style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      } else {
        PackActionButton(stringResource(R.string.icon_pack_library), onClick = onOpenPacks,
          modifier = Modifier.fillMaxWidth().testTag("monet.artwork.library"),
          operation = PageActionIcon.OPEN_INTERNAL)
      }
      if (pending != null) {
        Text(stringResource(R.string.pack_pending_apply_hint), style = MiuixTheme.textStyles.footnote1)
        NativeThemeLink(pending, autoApply = false, enabled = !busy)
      }
      packs.nativeResult?.takeIf { it.sourceId == sourceId }?.let { result ->
        if (result.imported && result.convertedReady == false) {
          NativeThemeLink(result, autoApply = false, enabled = !busy,
            manualFallback = preferences.manualThemeFallback)
        } else if (!result.imported) {
          Text(stringResource(result.failureLabel()), style = MiuixTheme.textStyles.footnote1,
            color = if (result.pending) MiuixTheme.colorScheme.onSurfaceSecondary else MiuixTheme.colorScheme.error)
          if (result.pending) PackInlineAction(stringResource(R.string.icon_pack_recheck),
            onClick = model::recheckNativeIconImport)
        }
      }
      packs.error?.takeIf { source != null }?.let { Text(it, style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.error) }
    }
  }
  if (inspiration) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pack_style_inspiration), style = MiuixTheme.textStyles.body1)
        Text(stringResource(R.string.monet_inspiration_install_summary),
          style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        top.yukonga.miuix.kmp.basic.TextButton(
          text = stringResource(R.string.pack_action_inspiration),
          modifier = Modifier.fillMaxWidth().testTag("monet.inspiration.install"),
          enabled = ready && !packs.preparing && packs.nativeImporting == null && framework.snapshot?.themesInScope == true,
          onClick = { model.applyColorOs(inspiration = true) })
      }
    }
    ColorOsSettings(model, ready, inspiration = true)
  }
}
