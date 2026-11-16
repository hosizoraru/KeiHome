package os.kei.hyper.home.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.UsageHelp
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MonetDestinationScreen(model: HomeViewModel, onBack: () -> Unit,
  onNestedChanged: (Boolean) -> Unit = {}) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(model, lifecycle, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observePage(2, locales) }
  }
  var pages by rememberSaveable { mutableStateOf(listOf(MonetSection.OVERVIEW.name)) }
  val section = MonetSection.valueOf(pages.last())
  SideEffect { onNestedChanged(pages.size > 1) }
  DisposableEffect(Unit) { onDispose { onNestedChanged(false) } }
  val applyState = remember { mutableStateOf(MonetApplyUiState()) }
  var actionHeight by remember { mutableIntStateOf(0) }
  val actionClearance = with(LocalDensity.current) { actionHeight.toDp() }.coerceAtLeast(76.dp) + 24.dp
  val navigate: (MonetSection) -> Unit = { if (it != section) pages = pages + it.name }
  val back = { if (pages.size > 1) pages = pages.dropLast(1) else onBack() }
  BackHandler(pages.size > 1) { back() }
  var help by rememberSaveable { mutableStateOf(false) }
  val scrollStates = MonetSection.entries.map { rememberScrollState() }
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(backdrop = backdrop,
    modifier = Modifier.testTag("icon-packs.monet"),
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(
          title = stringResource(section.page.title),
          scrollBehavior = behavior,
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          navigationIcon = { IconButton(onClick = back) {
            Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
          } },
          actions = {
            IconButton(onClick = { help = true }, modifier = Modifier.testTag("monet.help.open")) {
              Icon(MiuixIcons.Glass.UsageHelp, stringResource(R.string.pack_guidance_title))
            }
            RefreshAction(model, 2, Modifier.testTag("monet.page.refresh"))
          },
        )
      }
    },
    floatingActionButton = {
      val state = applyState.value
      Column(Modifier.onSizeChanged { actionHeight = it.height },
        horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.notice?.let { notice ->
          Card(Modifier.widthIn(max = 300.dp).testTag("monet.apply.status")
            .semantics { liveRegion = LiveRegionMode.Polite },
            insideMargin = PaddingValues(12.dp)) {
            Text(stringResource(notice), style = MiuixTheme.textStyles.footnote1,
              color = if (state.warning) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceSecondary)
          }
        }
        PageActionFab(stringResource(R.string.monet_apply_scheme), actionIcon(PageActionIcon.APPLY),
          modifier = Modifier.testTag("monet.apply"), enabled = state.enabled, busy = state.busy,
          onClick = { state.choice?.let(model::applyMonet) })
      }
    }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      Column(
        modifier = Modifier.fillMaxSize().keiHomeTopBarScroll(behavior)
          .verticalScroll(scrollStates[section.page.ordinal], overscrollEffect = null).padding(
            start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 4.dp,
          bottom = insets.calculateBottomPadding() + actionClearance),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        MonetPage(model, section, navigate, back, applyState)
        RefreshFeedback(model, 2)
      }
    }
    Os4DetailsSheet(show = help, onDismissRequest = { help = false },
      title = stringResource(R.string.monet_help_title), modifier = Modifier.testTag("monet.help"),
    ) {
      for (text in listOf(stringResource(R.string.monet_page_help), stringResource(R.string.monet_help_apply_note),
        stringResource(R.string.monet_help_inspiration_note), stringResource(R.string.monet_scope_hint),
        stringResource(R.string.monet_details_contrast_note))) {
        Text(text, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
  }
}

@Composable
private fun MonetPage(model: HomeViewModel, section: MonetSection, onNavigate: (MonetSection) -> Unit,
  onDismissPanel: () -> Unit, applyState: MutableState<MonetApplyUiState>) {
  val monet by model.monet.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val packs by model.iconPacks.collectAsStateWithLifecycle()
  val browser by model.browserSource.collectAsStateWithLifecycle()
  val ordinary = runtime.report?.iconTheme?.localId?.let { preferences.colorOsComponents[it] } == false
  val paletteNames = browser.takeIf { it?.revision == runtime.report?.iconTheme?.previewKey }?.paletteNames
  val locked = colorOsPaletteLocked(ordinary, paletteNames)
  val ready = framework.connected && framework.snapshot?.themesInScope == true &&
    runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong()
  if (section.page == MonetSection.OVERVIEW) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      top.yukonga.miuix.kmp.preference.SwitchPreference(
      title = stringResource(R.string.pack_monet_toggle),
      summary = stringResource(if (locked) R.string.pack_monet_ordinary_locked
        else if (ordinary) R.string.pack_monet_fallback_summary else R.string.pack_monet_toggle_summary),
      checked = monet.snapshot?.monochrome?.enabled == true,
      enabled = !locked && framework.connected && monet.snapshot != null &&
        framework.snapshot?.target?.loadedVersionCode == BuildConfig.VERSION_CODE.toLong() &&
        monet.action != os.kei.hyper.home.data.MonetActionStatus.APPLYING,
      onCheckedChange = model::setMonetEnabled)
    }
  }
  val inspiration = preferences.monetInspiration ?:
    (monet.snapshot?.customization?.style == os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION ||
      runtime.report?.iconTheme?.localId?.let { preferences.colorOsComponents[it] } == true)
  if (section.page == MonetSection.OVERVIEW) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      os.kei.hyper.home.ui.components.OverlaySpinnerPreference(
      title = stringResource(R.string.pack_monet_design),
      items = listOf("Pixel / AOSP", stringResource(R.string.pack_style_inspiration)).map { DropdownItem(text = it) },
      selectedIndex = if (inspiration) 1 else 0,
      onSelectedIndexChange = { model.preferences.setMonetInspiration(it == 1) })
      if (inspiration) IconPackAppearanceControl(preferences.iconPackAppearance,
        model.preferences::setIconPackAppearance, packs.nativeImporting == null, compact = true)
    }
  }
  if (section == MonetSection.DESIGN && inspiration) {
    ColorOsSettings(model, ready, inspiration = true)
    top.yukonga.miuix.kmp.basic.TextButton(
      text = stringResource(R.string.pack_action_inspiration),
      modifier = Modifier.fillMaxWidth().testTag("monet.inspiration.install"),
      enabled = ready && packs.nativeImporting == null && framework.snapshot?.themesInScope == true,
      onClick = { model.applyColorOs(inspiration = true) })
  }
  MonetContent(snapshot = monet.snapshot, readFailed = monet.readFailed,
    preferredStyle = if (inspiration) os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION else null,
    preferredAppearance = preferences.iconPackAppearance,
    actionAvailable = !locked && framework.connected && !framework.readFailed &&
      framework.snapshot?.target?.loadedVersionCode == BuildConfig.VERSION_CODE.toLong(),
    actionStatus = monet.action, submittedChoice = monet.submittedChoice, applyState = applyState,
    section = section, onNavigate = onNavigate, onDismissPanel = onDismissPanel)
}
