package os.kei.hyper.home.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.springAnimateToPage
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
import androidx.compose.runtime.remember
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
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MainScreen(model: HomeViewModel, onOpenPackSection: (String) -> Unit,
  onOpenSettingsSection: (String) -> Unit, modifier: Modifier = Modifier) {
  val (interactionFlow, initialInteraction) = remember(model) {
    model.preferences.state.map { it.pageUserScroll to it.pagerGesture }.distinctUntilChanged() to
      model.preferences.state.value.let { it.pageUserScroll to it.pagerGesture }
  }
  val interaction by interactionFlow.collectAsStateWithLifecycle(initialValue = initialInteraction)
  val gestureMode = when (interaction.second) {
    os.kei.hyper.home.data.AppPagerGesture.NATIVE -> PagerInterceptionMode.Native
    os.kei.hyper.home.data.AppPagerGesture.CROSS_AXIS -> PagerInterceptionMode.CrossAxis
    os.kei.hyper.home.data.AppPagerGesture.IOS_LIKE -> PagerInterceptionMode.TapToHalt
  }
  val pager = rememberPagerState { 5 }
  val navigationScope = rememberCoroutineScope()
  val selectedTab = pager.currentPage
  fun selectTab(index: Int) { navigationScope.launch { pager.springAnimateToPage(index) } }
  val page = listOf(0, 1, 3, 2, 4)[selectedTab]
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
  val behaviors = List(5) { tab -> androidx.compose.runtime.key("bar-$tab") { MiuixScrollBehavior() } }
  val appList = rememberLazyListState()
  val pageScrolls = List(5) { tab -> androidx.compose.runtime.key("scroll-$tab") { rememberScrollState() } }
  val savedPages = rememberSaveableStateHolder()
  Scaffold(
    modifier = modifier,
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(
          title = if (page == 0) stringResource(R.string.app_name) else labels[selectedTab],
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          scrollBehavior = behaviors[selectedTab],
          actions = {
            if (selectedTab == 1) {
              AppsFilterMenu(model)
              AppsSortMenu(model) { navigationScope.launch { appList.scrollToItem(0) } }
            }
            if (selectedTab != 4) RefreshAction(model, page)
          },
        )
      }
    },
    bottomBar = {
      val packUpdate by model.iconPackUpdateNeeded.collectAsStateWithLifecycle()
      val packState by model.iconPacks.collectAsStateWithLifecycle()
      val activeComponent by remember(model) {
        model.runtime.map { it.report?.iconTheme?.localId }.distinctUntilChanged()
      }.collectAsStateWithLifecycle(initialValue = null)
      val pendingApply = pendingNativeApplication(packState, activeComponent) != null
      val updateLabel = stringResource(R.string.pack_update_available)
      IosLiquidGlassNavigationBar(items = navigation, selectedIndex = selectedTab,
        onItemClick = { selectTab(it) }, backdrop = backdrop, isBlurActive = backdrop != null,
        badge = { index -> if (index == 2 && packUpdate && !pendingApply) ({
          top.yukonga.miuix.kmp.basic.Badge(modifier = Modifier.semantics { contentDescription = updateLabel })
        }) else null })
    },
    floatingActionButton = { PackActionFab(model, page, "home") },
  ) { insets ->
    val padding = PaddingValues(start = 16.dp, end = 16.dp,
      top = insets.calculateTopPadding() + 4.dp,
      bottom = insets.calculateBottomPadding() + if (page == 2 || page == 3) 100.dp else 24.dp)
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      val fling = PagerDefaults.flingBehavior(pager, snapAnimationSpec = PagerNavigationSpringSpec)
      HorizontalPager(state = pager, userScrollEnabled = interaction.first && gestureMode != PagerInterceptionMode.CrossAxis,
        modifier = Modifier.fillMaxSize().pagerGestureOverride(pagerState = pager,
          flingBehavior = fling, mode = gestureMode, enabled = interaction.first),
        flingBehavior = fling, verticalAlignment = Alignment.Top,
        pageNestedScrollConnection = if (gestureMode == PagerInterceptionMode.CrossAxis)
          top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
        else PagerDefaults.pageNestedScrollConnection(pager, Orientation.Horizontal)) { tab ->
        val page = listOf(0, 1, 3, 2, 4)[tab]
        savedPages.SaveableStateProvider(page) {
        val pageModifier = Modifier.fillMaxSize().nestedScroll(behaviors[tab].nestedScrollConnection)
        if (tab == 1) {
          AppsPage(model, appList, padding, pageModifier)
        } else if (page == 3) {
          val sectionList = rememberLazyListState()
          IconPacksPage(model, sectionList, padding, pageModifier, "home", onOpenPackSection,
            onOpenSettings = { onOpenSettingsSection("hook") })
        } else {
          // These pages are a single form. A nested lazy-item saveable owner drops the form's
          // draft/expansion state when the outer page provider saves during tab disposal.
          Column(modifier = pageModifier.verticalScroll(pageScrolls[tab]).padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
              0 -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  StatusPage(model, onOpenApps = { selectTab(1) }, onOpenMonet = { selectTab(3) },
                    onOpenPacks = { selectTab(2) }, onOpenEnvironment = { onOpenSettingsSection("about/environment") })
                  RefreshFeedback(model, 0)
                }
              }
              2 -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  MonetPage(model)
                  RefreshFeedback(model, 2)
                }
              }
              4 -> SettingsRootPage(model, onOpenSettingsSection)
            }
          }
        }
        }
      }
    }
  }
}

@Composable
private fun StatusPage(model: HomeViewModel, onOpenApps: () -> Unit, onOpenMonet: () -> Unit,
  onOpenPacks: () -> Unit, onOpenEnvironment: () -> Unit) {
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val monet by model.monet.collectAsStateWithLifecycle()
  val apps by model.apps.collectAsStateWithLifecycle()
  val source by model.browserSource.collectAsStateWithLifecycle()
  val effective = remember(apps.capabilities, source) { apps.capabilities?.let { browserRows(it, source, IconBrowserOptions(), "") } }
  val counts = remember(effective) { effective?.map { it.app }?.let { list ->
    Triple(list.size, list.count { it.nightMode == IconCapabilityStatus.DETECTED },
      list.count { it.monochrome == IconCapabilityStatus.DETECTED })
  } }
  StatusBody(connected = framework.connected, snapshot = framework.snapshot, readFailed = framework.readFailed,
    monetSnapshot = monet.snapshot, monetReadFailed = monet.readFailed, onRefresh = { model.refresh(0) },
    onOpenMonet = onOpenMonet, onOpenApps = onOpenApps,
    installedApps = counts?.first, nightApps = counts?.second, monetApps = counts?.third,
    runtime = runtime,
    iconSource = runtime.report?.iconTheme, coveredApps = effective?.count { it.covered },
    onOpenPacks = onOpenPacks, onOpenEnvironment = onOpenEnvironment)
}

@Composable
private fun MonetPage(model: HomeViewModel) {
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
  top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
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
  val inspiration = preferences.monetInspiration ?:
    (monet.snapshot?.customization?.style == os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION ||
      runtime.report?.iconTheme?.localId?.let { preferences.colorOsComponents[it] } == true)
  top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference(
      title = stringResource(R.string.pack_monet_design),
      items = listOf("Pixel / AOSP", stringResource(R.string.pack_style_inspiration)).map { DropdownItem(text = it) },
      selectedIndex = if (inspiration) 1 else 0,
      onSelectedIndexChange = { model.preferences.setMonetInspiration(it == 1) })
    if (inspiration) IconPackAppearanceControl(preferences.iconPackAppearance,
      model.preferences::setIconPackAppearance, packs.nativeImporting == null, compact = true)
  }
  if (inspiration) ColorOsSettings(model, ready, inspiration = true)
  MonetContent(snapshot = monet.snapshot, readFailed = monet.readFailed,
    preferredStyle = if (inspiration) os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION else null,
    preferredAppearance = preferences.iconPackAppearance,
    actionAvailable = !locked && framework.connected && !framework.readFailed &&
      framework.snapshot?.target?.loadedVersionCode == BuildConfig.VERSION_CODE.toLong(),
    actionStatus = monet.action, onApply = model::applyMonet)
}

@Composable
private fun AppsFilterMenu(model: HomeViewModel) {
  val options by model.browserOptions.collectAsStateWithLifecycle()
  @Composable
  fun group(title: Int, labels: List<Int>, selected: Int, update: (Int) -> Unit) =
    DropdownItem(text = stringResource(title),
      children = labels.mapIndexed { index, label -> DropdownItem(text = stringResource(label),
        selected = selected == index, onClick = { update(index) }) })
  val filters = DropdownEntry(items = listOf(
    group(R.string.browser_scope, browserScopeLabels(), options.scope) { model.updateBrowserOptions(options.copy(scope = it)) },
    group(R.string.browser_origin, listOf(R.string.browser_origin_all, R.string.browser_origin_user, R.string.browser_origin_system),
      options.origin) { model.updateBrowserOptions(options.copy(origin = it)) },
    group(R.string.browser_coverage, listOf(R.string.browser_all, R.string.browser_covered, R.string.browser_original),
      options.coverage) { model.updateBrowserOptions(options.copy(coverage = it)) },
    group(R.string.browser_support, listOf(R.string.browser_all, R.string.filter_night, R.string.filter_monochrome,
      R.string.browser_both, R.string.filter_unknown), options.support) { model.updateBrowserOptions(options.copy(support = it)) },
  ))
  val controls = DropdownEntry(items = listOf(
    DropdownItem(text = stringResource(R.string.browser_show_overlays), selected = options.showOverlays,
      onClick = { model.updateBrowserOptions(options.copy(showOverlays = !options.showOverlays)) }),
    DropdownItem(text = stringResource(R.string.browser_show_development), selected = options.showDevelopmentPackages,
      onClick = { model.updateBrowserOptions(options.copy(showDevelopmentPackages = !options.showDevelopmentPackages)) }),
    DropdownItem(text = stringResource(R.string.browser_reset), onClick = {
      model.updateBrowserOptions(IconBrowserOptions(sort = options.sort, reverse = options.reverse))
    }),
  ))
  OverlayIconCascadingDropdownMenu(entries = listOf(filters, controls)) {
    Icon(MiuixIcons.Filter, stringResource(R.string.filter_title), tint =
      if (options.copy(sort = 0, reverse = false) != IconBrowserOptions()) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground)
  }
}

@Composable
private fun AppsSortMenu(model: HomeViewModel, onOrderChange: () -> Unit) {
  val options by model.browserOptions.collectAsStateWithLifecycle()
  val labels = listOf(R.string.browser_sort_name, R.string.browser_sort_package,
    R.string.browser_sort_updated, R.string.browser_sort_covered)
  val entry = DropdownEntry(items = labels.mapIndexed { index, label ->
    DropdownItem(text = stringResource(label), selected = options.sort == index,
      onClick = {
        model.updateBrowserOptions(model.browserOptions.value.copy(sort = index))
        onOrderChange()
      })
  })
  val direction = DropdownEntry(items = listOf(
    DropdownItem(text = stringResource(R.string.browser_sort_reverse), selected = options.reverse,
      onClick = {
        model.updateBrowserOptions(model.browserOptions.value.copy(reverse = !options.reverse))
        onOrderChange()
      }),
  ))
  OverlayIconCascadingDropdownMenu(entries = listOf(entry, direction)) {
    Icon(MiuixIcons.Sort, stringResource(R.string.browser_sort),
      tint = if (options.reverse) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground)
  }
}

@Composable
internal fun RefreshAction(model: HomeViewModel, page: Int) {
  val scanning by remember(model) { model.apps.map { it.loading }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(initialValue = false)
  val refreshing by remember(model, page) { model.refreshing.map { page in it }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(initialValue = false)
  val catalogLoading = if (page == 3) {
    val loading by remember(model) { model.iconPacks.map { it.loading }.distinctUntilChanged() }
      .collectAsStateWithLifecycle(initialValue = false)
    loading
  } else false
  val busy = refreshing || (page == 1 && scanning) || catalogLoading
  val label = stringResource(R.string.refresh)
  val progress = stringResource(R.string.refresh_in_progress)
  IconButton(onClick = { model.refresh(page) }, enabled = !busy,
    modifier = Modifier.semantics {
      contentDescription = label
      if (busy) stateDescription = progress
    }) {
    Crossfade(targetState = busy, modifier = Modifier.size(28.dp),
      animationSpec = tween(160), label = "refresh-action") { loading ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) top.yukonga.miuix.kmp.basic.CircularProgressIndicator(size = 24.dp, strokeWidth = 2.dp)
        else Icon(MiuixIcons.Refresh, null, Modifier.size(26.dp), tint = MiuixTheme.colorScheme.onBackground)
      }
    }
  }
}

@Composable
internal fun appFilterLabels() = listOf(
  stringResource(R.string.filter_supported), stringResource(R.string.filter_night),
  stringResource(R.string.filter_monochrome), stringResource(R.string.filter_unknown),
  stringResource(R.string.filter_all),
)

internal fun browserScopeLabels() = listOf(R.string.browser_scope_installed, R.string.browser_scope_launcher,
  R.string.browser_scope_pack)
