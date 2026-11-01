package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.liquid.IosLiquidGlassNavigationBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
  val model: HomeViewModel = viewModel()
  var selectedTab by rememberSaveable { mutableIntStateOf(0) }
  val page = listOf(0, 1, 3, 2, 4)[selectedTab]
  var packSection by rememberSaveable { mutableStateOf("home") }
  val backState = androidx.navigationevent.compose.rememberNavigationEventState(
    currentInfo = androidx.navigationevent.NavigationEventInfo.None)
  androidx.navigationevent.compose.NavigationBackHandler(backState,
    isBackEnabled = page == 3 && packSection != "home", onBackCompleted = {
      packSection = when (packSection) { "coloros" -> "builtin"; "builtin", "installed", "imported" -> "library"; else -> "home" }
    })
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(model, lifecycle, selectedTab, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observePage(page, locales) }
  }
  val labels = listOf(stringResource(R.string.navigation_status), stringResource(R.string.navigation_apps),
    stringResource(R.string.navigation_icon_packs), stringResource(R.string.navigation_monet),
    stringResource(R.string.navigation_settings))
  val navigation = remember(labels) { listOf(
    NavigationItem(labels[0], MiuixIcons.Info), NavigationItem(labels[1], MiuixIcons.GridView),
    NavigationItem(labels[2], MiuixIcons.Layers), NavigationItem(labels[3], MiuixIcons.Theme),
    NavigationItem(labels[4], MiuixIcons.Settings),
  ) }
  val backdrop = rememberKeiHomeBlurBackdrop()
  val behaviors = List(5) { MiuixScrollBehavior() }
  val appList = rememberLazyListState()
  val pageScrolls = List(5) { rememberScrollState() }
  val savedPages = rememberSaveableStateHolder()
  Scaffold(
    modifier = modifier,
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(
          title = if (page == 3 && packSection != "home") stringResource(when (packSection) {
            "library" -> R.string.icon_pack_library; "builtin" -> R.string.pack_builtin
            "installed" -> R.string.pack_installed; "imported" -> R.string.pack_imported
            else -> R.string.pack_coloros_title
          }) else if (page == 0) stringResource(R.string.app_name) else labels[selectedTab],
          navigationIcon = {
            if (page == 3 && packSection != "home") IconButton(onClick = {
              packSection = when (packSection) { "coloros" -> "builtin"; "builtin", "installed", "imported" -> "library"; else -> "home" }
            }) { Icon(MiuixIcons.Back, stringResource(R.string.pack_back)) }
          },
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          scrollBehavior = behaviors[selectedTab],
          actions = {
            if (selectedTab == 1) AppsFilterMenu(model)
            if (selectedTab != 4) RefreshAction(model, page)
          },
        )
      }
    },
    bottomBar = {
      IosLiquidGlassNavigationBar(items = navigation, selectedIndex = selectedTab,
        onItemClick = { selectedTab = it }, backdrop = backdrop, isBlurActive = backdrop != null)
    },
  ) { insets ->
    val padding = PaddingValues(start = 16.dp, end = 16.dp,
      top = insets.calculateTopPadding() + 4.dp, bottom = insets.calculateBottomPadding() + 24.dp)
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      savedPages.SaveableStateProvider(page) {
        val pageModifier = Modifier.fillMaxSize().nestedScroll(behaviors[selectedTab].nestedScrollConnection)
        if (selectedTab == 1) {
          AppsPage(model, appList, padding, pageModifier)
        } else if (page == 3) {
          androidx.compose.runtime.key(packSection) {
            val sectionList = rememberLazyListState()
            IconPacksPage(model, sectionList, padding, pageModifier, packSection, { packSection = it })
          }
        } else {
          // These pages are a single form. A nested lazy-item saveable owner drops the form's
          // draft/expansion state when the outer page provider saves during tab disposal.
          Column(modifier = pageModifier.verticalScroll(pageScrolls[selectedTab]).padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
              0 -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  StatusPage(model, onOpenApps = { selectedTab = 1 }, onOpenMonet = { selectedTab = 3 })
                  RefreshFeedback(model, 0)
                }
              }
              2 -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  MonetPage(model)
                  RefreshFeedback(model, 2)
                }
              }
              4 -> SettingsPage(model)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StatusPage(model: HomeViewModel, onOpenApps: () -> Unit, onOpenMonet: () -> Unit) {
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val monet by model.monet.collectAsStateWithLifecycle()
  val apps by model.apps.collectAsStateWithLifecycle()
  val counts = remember(apps.capabilities) { apps.capabilities?.let { list ->
    Triple(list.size, list.count { it.nightMode == IconCapabilityStatus.DETECTED },
      list.count { it.monochrome == IconCapabilityStatus.DETECTED })
  } }
  StatusBody(connected = framework.connected, snapshot = framework.snapshot, readFailed = framework.readFailed,
    monetSnapshot = monet.snapshot, monetReadFailed = monet.readFailed, onRefresh = { model.refresh(0) },
    onOpenMonet = onOpenMonet, onOpenApps = onOpenApps,
    installedApps = counts?.first, nightApps = counts?.second, monetApps = counts?.third,
    runtime = runtime)
}

@Composable
private fun MonetPage(model: HomeViewModel) {
  val monet by model.monet.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val ordinary = runtime.report?.iconTheme?.localId?.let { preferences.colorOsComponents[it] } == false
  val ready = framework.connected && framework.snapshot?.themesInScope == true &&
    runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong()
  top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    top.yukonga.miuix.kmp.preference.SwitchPreference(
      title = stringResource(R.string.pack_monet_toggle),
      summary = stringResource(if (ordinary) R.string.pack_monet_ordinary_locked else R.string.pack_monet_toggle_summary),
      checked = monet.snapshot?.monochrome?.enabled == true,
      enabled = !ordinary && framework.connected && monet.snapshot != null &&
        framework.snapshot?.target?.loadedVersionCode == BuildConfig.VERSION_CODE.toLong() &&
        monet.action != os.kei.hyper.home.data.MonetActionStatus.APPLYING,
      onCheckedChange = model::setMonetEnabled)
  }
  val inspiration = preferences.monetInspiration ?:
    (monet.snapshot?.customization?.style == os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION ||
      runtime.report?.iconTheme?.localId?.let { preferences.colorOsComponents[it] } == true)
  top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference(
      title = stringResource(R.string.pack_monet_design),
      items = listOf("Pixel / AOSP", stringResource(R.string.pack_style_inspiration)).map { DropdownItem(text = it) },
      selectedIndex = if (inspiration) 1 else 0,
      onSelectedIndexChange = { model.preferences.setMonetInspiration(it == 1) })
  }
  if (inspiration) ColorOsSettings(model, ready, inspiration = true)
  MonetContent(snapshot = monet.snapshot, readFailed = monet.readFailed,
    preferredStyle = if (inspiration) os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION else null,
    preferredAppearance = preferences.iconPackAppearance,
    actionAvailable = !ordinary && framework.connected && !framework.readFailed &&
      framework.snapshot?.target?.loadedVersionCode == BuildConfig.VERSION_CODE.toLong(),
    actionStatus = monet.action, onApply = model::applyMonet)
}

@Composable
private fun SettingsPage(model: HomeViewModel) {
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  SettingsContent(preferences, model.preferences::setAppearance, model.preferences::setShowIconPreviews,
    model::clearPreviewCache)
}

@Composable
private fun AppsFilterMenu(model: HomeViewModel) {
  val selected by model.filter.collectAsStateWithLifecycle()
  val labels = appFilterLabels()
  val entry = remember(labels, selected) { DropdownEntry(items = labels.mapIndexed { index, label ->
    DropdownItem(text = label, selected = selected == index, onClick = { model.setFilter(index) })
  }) }
  OverlayIconDropdownMenu(entry = entry) {
    Icon(MiuixIcons.Filter, stringResource(R.string.filter_title), tint = MiuixTheme.colorScheme.onBackground)
  }
}

@Composable
private fun RefreshAction(model: HomeViewModel, page: Int) {
  val scanning by remember(model) { model.apps.map { it.loading }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(initialValue = false)
  val refreshing by remember(model, page) { model.refreshing.map { page in it }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(initialValue = false)
  val busy = refreshing || (page == 1 && scanning)
  val label = stringResource(R.string.refresh)
  val progress = stringResource(R.string.refresh_in_progress)
  IconButton(onClick = { model.refresh(page) }, enabled = !busy,
    modifier = Modifier.semantics {
      contentDescription = label
      if (busy) stateDescription = progress
    }) {
    if (busy) {
      top.yukonga.miuix.kmp.basic.CircularProgressIndicator(modifier = Modifier.size(24.dp))
    } else Icon(MiuixIcons.Refresh, null, tint = MiuixTheme.colorScheme.onBackground)
  }
}

@Composable
internal fun appFilterLabels() = listOf(
  stringResource(R.string.filter_supported), stringResource(R.string.filter_night),
  stringResource(R.string.filter_monochrome), stringResource(R.string.filter_unknown),
  stringResource(R.string.filter_all),
)
