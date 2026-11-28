package os.kei.hyper.home.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import os.kei.hyper.home.ui.components.Os4ViewTabs
import androidx.compose.ui.platform.LocalFocusManager
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
import os.kei.hyper.home.R
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.Os4ChromeStowContainer
import os.kei.hyper.home.ui.components.Os4PullToRefresh
import os.kei.hyper.home.ui.components.KeiHomeNavigationBar
import os.kei.hyper.home.ui.components.rememberNavBottomBarVisible
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationItem
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Filter
import top.yukonga.miuix.kmp.icon.glass.Sort
import os.kei.hyper.home.ui.components.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MainScreen(model: HomeViewModel, onOpenPackSection: (String) -> Unit,
  onOpenSettingsSection: (String) -> Unit, onOpenDesktopSection: (String) -> Unit,
  pager: PagerState, tabNavigation: TabPagerNavigation, navigation: List<NavigationItem>,
  backdrop: LayerBackdrop?, wide: Boolean, overlay: @Composable () -> Unit) {
  val navigationScope = rememberCoroutineScope()
  var statusPackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
  val focus = LocalFocusManager.current
  val tabs = MainTab.entries
  val selectedTab = tabNavigation.selectedPage.coerceIn(tabs.indices)
  fun selectTab(index: Int) {
    focus.clearFocus(force = true)
    tabNavigation.selectPage(index)
  }
  val page = tabs[selectedTab].page
  val appsBrowser = rememberAppsBrowserState(model, enabled = selectedTab == 1)
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(model, lifecycle, selectedTab, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observePage(page, locales) }
  }
  val (navigationStyleFlow, initialNavigationStyle) = remember(model) {
    model.preferences.state.map { it.navigationBarStyle }.distinctUntilChanged() to
      model.preferences.state.value.navigationBarStyle
  }
  val navigationStyle by navigationStyleFlow.collectAsStateWithLifecycle(initialValue = initialNavigationStyle)
  val behaviors = tabs.map { androidx.compose.runtime.key("bar-${it.page}") { MiuixScrollBehavior() } }
  val appList = rememberLazyListState()
  val pageScrolls = tabs.map { androidx.compose.runtime.key("scroll-${it.page}") { rememberScrollState() } }
  val savedPages = rememberSaveableStateHolder()
  val navigationBadge: (Int) -> (@Composable () -> Unit)? = { index ->
    if (index == 2) ({ PackNavigationBadge(model) }) else null
  }
  val bottomBarVisible = rememberNavBottomBarVisible(enabled = !wide)
  Scaffold(backdrop = backdrop,
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(
          title = if (page == 0) stringResource(R.string.app_name) else stringResource(tabs[selectedTab].title),
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          scrollBehavior = behaviors[selectedTab],
          actions = {
            if (selectedTab == 1) {
              AppsPreviewHelp(model)
              AppsFilterMenu(model)
              AppsSortMenu(model) { navigationScope.launch { appList.scrollToItem(0) } }
            }
            if (page != 1 && page != 3 && page != 4) RefreshAction(model, page)
          },
          bottomContent = {
            if (selectedTab == 1) AppsOriginTabs(model, appsBrowser)
          },
        )
      }
    },
    bottomBar = {
      if (!wide) KeiHomeNavigationBar(style = navigationStyle, items = navigation, selectedIndex = selectedTab,
        onSelect = { selectTab(it) }, backdrop = backdrop,
        badge = navigationBadge, visible = bottomBarVisible)
    },
    floatingActionButton = {
      Os4ChromeStowContainer(visible = bottomBarVisible || wide) {
        PackActionFab(model, page, "home")
      }
    },
  ) { insets ->
    // Glass dialogs register with the MIUIX popup host supplied by this scaffold.
    overlay()
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      KeiHomeTabPager(model, pager, Modifier.fillMaxSize()) { tab ->
        val page = tabs[tab].page
        val padding = PaddingValues(start = 16.dp, end = 16.dp,
          top = insets.calculateTopPadding() + 4.dp,
          bottom = insets.calculateBottomPadding() + if (page == 3) 100.dp else 24.dp)
        savedPages.SaveableStateProvider(page) {
        val pageModifier = Modifier.fillMaxSize().keiHomeTopBarScroll(behaviors[tab])
        if (tab == 1) {
          PagePullToRefresh(model, 1, padding, Modifier.testTag("apps.pull-to-refresh")) { busy ->
            AppsPage(model, appList, padding,
              Modifier.fillMaxSize().keiHomeTopBarScroll(behaviors[tab], overScrollEnabled = !busy),
              rememberAppsRefreshContent(appsBrowser, busy), scrollBehavior = behaviors[tab])
          }
        } else if (page == 3) {
          val sectionList = rememberLazyListState()
          PagePullToRefresh(model, 3, padding) { busy ->
            IconPacksPage(model, sectionList, padding,
              Modifier.fillMaxSize().keiHomeTopBarScroll(behaviors[tab], overScrollEnabled = !busy),
              "home", onOpenPackSection)
          }
        } else {
          // These pages are a single form. A nested lazy-item saveable owner drops the form's
          // draft/expansion state when the outer page provider saves during tab disposal.
          Column(modifier = pageModifier.verticalScroll(pageScrolls[tab], overscrollEffect = null).padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
              0 -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  StatusPage(model, onOpenApps = { selectTab(1) }, onOpenMonet = {
                    focus.clearFocus(force = true)
                    tabNavigation.selectPage(2, animate = false) { onOpenPackSection("monet") }
                  },
                    onOpenPacks = {
                      if (!model.preferences.state.value.openCurrentIconPack) selectTab(2)
                      else if (statusPackJob?.isActive != true) {
                        val theme = runtimeCardState(model.runtime.value,
                          android.os.SystemClock.elapsedRealtime()).report?.iconTheme
                        statusPackJob = navigationScope.launch {
                          val destination = loadStatusIconPackDestination(model.getApplication(), theme)
                          if (tabNavigation.selectedPage == 0 &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            runtimeCardState(model.runtime.value,
                              android.os.SystemClock.elapsedRealtime()).report?.iconTheme == theme) {
                            if (destination == null) selectTab(2) else {
                              focus.clearFocus(force = true)
                              onOpenPackSection(destination)
                            }
                          }
                        }
                      }
                    }, onOpenEnvironment = { onOpenSettingsSection("about/environment") },
                    onOpenGuide = { onOpenSettingsSection("setup") })
                  RefreshFeedback(model, 0)
                }
              }
              5 -> {
                DesktopPage(model, onOpenDesktopSection)
                RefreshFeedback(model, 5)
              }
              4 -> SettingsRootPage(model, onOpenSettingsSection, onOpenPerfectIcons = onOpenPackSection)
            }
          }
        }
        }
      }
    }
  }
}

@Composable
internal fun PackNavigationBadge(model: HomeViewModel) {
  val packUpdate by model.iconPackUpdateNeeded.collectAsStateWithLifecycle()
  val packState by model.iconPacks.collectAsStateWithLifecycle()
  val activeComponent by remember(model) {
    model.runtime.map { it.report?.iconTheme?.localId }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = null)
  val fallback by remember(model) {
    model.preferences.state.map { it.systemIconFallback }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = os.kei.hyper.home.data.SystemIconFallback.DEFAULT)
  if (packUpdate && pendingNativeApplication(packState, activeComponent, fallback) == null) {
    val updateLabel = stringResource(R.string.pack_update_available)
    top.yukonga.miuix.kmp.basic.Badge(modifier = Modifier.semantics { contentDescription = updateLabel })
  }
}

@Composable
private fun StatusPage(model: HomeViewModel, onOpenApps: () -> Unit, onOpenMonet: () -> Unit,
  onOpenPacks: () -> Unit, onOpenEnvironment: () -> Unit, onOpenGuide: () -> Unit) {
  val activation by model.initialActivation.state.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val monet by model.monet.collectAsStateWithLifecycle()
  val apps by model.apps.collectAsStateWithLifecycle()
  val source by model.browserSource.collectAsStateWithLifecycle()
  val packs by model.iconPacks.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val packUpdateNeeded by model.iconPackUpdateNeeded.collectAsStateWithLifecycle()
  val packSourceUpdated by model.activeInstalledPackUpdated.collectAsStateWithLifecycle()
  val activePack = rememberActiveIconPackSummary(
    theme = runtime.report?.iconTheme,
    packs = packs,
    fallback = preferences.systemIconFallback,
    updateNeeded = packUpdateNeeded,
    sourceUpdated = packSourceUpdated,
  )
  val effective = remember(apps.capabilities, source) { apps.capabilities?.let { browserRows(it, source, IconBrowserOptions(), "") } }
  val counts = remember(effective) { effective?.map { it.app }?.let { list ->
    Triple(list.size, list.count { it.nightMode == IconCapabilityStatus.DETECTED },
      list.count { it.monochrome == IconCapabilityStatus.DETECTED })
  } }
  val covered = remember(effective) { effective?.count { it.covered } }
  StatusBody(connected = framework.connected, snapshot = framework.snapshot, readFailed = framework.readFailed,
    frameworkChecking = framework.checking,
    monetSnapshot = monet.snapshot, monetReadFailed = monet.readFailed, onRefresh = { model.refresh(0) },
    onOpenMonet = onOpenMonet, onOpenApps = onOpenApps,
    installedApps = counts?.first, nightApps = counts?.second, monetApps = counts?.third,
    runtime = runtime,
    iconSource = runtime.report?.iconTheme, coveredApps = covered,
    activePack = activePack,
    onOpenPacks = onOpenPacks, onOpenEnvironment = onOpenEnvironment,
    scopeContent = { StaticScopeCard(model, attentionOnly = true) },
    activation = activation ?: os.kei.hyper.home.data.InitialActivationStatus.COMPLETE,
    onOpenGuide = onOpenGuide)
}

@Composable
private fun AppsOriginTabs(model: HomeViewModel, browser: AppsBrowserUi) {
  val options by model.browserOptions.collectAsStateWithLifecycle()
  Os4ViewTabs(
    items = listOf(R.string.browser_all, R.string.browser_origin_user, R.string.browser_origin_system)
      .mapIndexed { index, label ->
        val title = stringResource(label)
        browser.display.counts?.get(index)?.let { stringResource(R.string.browser_view_count, title, it) } ?: title
      },
    selectedIndex = options.origin,
    onSelect = {
      model.updateBrowserOptions(model.browserOptions.value.copy(origin = it))
    },
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
      .testTag("apps.origins"),
  )
}

@Composable
private fun AppsPreviewHelp(model: HomeViewModel) {
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val source by model.browserSource.collectAsStateWithLifecycle()
  val options by model.browserOptions.collectAsStateWithLifecycle()
  os.kei.hyper.home.ui.components.Os4InfoPopup(
    title = stringResource(R.string.browser_preview_help_title),
    summary = listOf(runtime.report?.iconTheme?.title ?: stringResource(R.string.status_unknown),
      stringResource(browserScopeLabels()[options.scope.coerceIn(0, 2)]),
      stringResource(if (source == null) R.string.browser_source_pending else R.string.browser_preview_note))
      .joinToString("\n\n"),
    modifier = Modifier.testTag("apps.preview-help"),
  )
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
    Icon(MiuixIcons.Glass.Filter, stringResource(R.string.filter_title), tint =
      if (options.copy(origin = 0, sort = 0, reverse = false) != IconBrowserOptions()) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground)
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
    Icon(MiuixIcons.Glass.Sort, stringResource(R.string.browser_sort),
      tint = if (options.reverse) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground)
  }
}

@Composable
internal fun PagePullToRefresh(
  model: HomeViewModel,
  page: Int,
  padding: PaddingValues,
  modifier: Modifier = Modifier,
  content: @Composable (busy: Boolean) -> Unit,
) {
  val refreshing by remember(model, page) { model.refreshing.map { page in it }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(initialValue = false)
  val loadingFlow = remember(model, page) {
    if (page == 1) model.apps.map { it.loading }.distinctUntilChanged()
    else model.iconPacks.map { it.loading }.distinctUntilChanged()
  }
  val loading by loadingFlow.collectAsStateWithLifecycle(initialValue = false)
  val busy = refreshing || loading
  Os4PullToRefresh(refreshing = refreshing, enabled = !loading, onRefresh = { model.refresh(page) },
    contentPadding = padding, modifier = modifier) { content(busy) }
}

private class AppsRefreshContent {
  var display: AppsBrowserDisplay? = null
  var pending = false
}

@Composable
internal fun rememberAppsRefreshContent(browser: AppsBrowserUi, busy: Boolean): AppsBrowserUi {
  val retained = remember(browser.query) { AppsRefreshContent() }
  val display = if ((busy || retained.pending) && browser.display.loading) {
    retained.display ?: browser.display
  } else browser.display
  // A committed holder avoids snapshot writes for the display object recreated each composition.
  SideEffect {
    if (!browser.display.loading) retained.display = browser.display
    retained.pending = busy || (retained.pending && browser.display.loading)
  }
  return AppsBrowserUi(browser.query, browser.onQueryChange, browser.origin, display, browser.search)
}

@Composable
internal fun RefreshAction(model: HomeViewModel, page: Int, modifier: Modifier = Modifier) {
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
    modifier = modifier.semantics {
      contentDescription = label
      if (busy) stateDescription = progress
    }) {
    Crossfade(targetState = busy, modifier = Modifier.size(28.dp),
      animationSpec = tween(160), label = "refresh-action") { loading ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) top.yukonga.miuix.kmp.basic.CircularProgressIndicator(size = 24.dp, strokeWidth = 2.dp)
        else Icon(MiuixIcons.Glass.Refresh, null, Modifier.size(26.dp), tint = MiuixTheme.colorScheme.onBackground)
      }
    }
  }
}

internal fun browserScopeLabels() = listOf(R.string.browser_scope_installed, R.string.browser_scope_launcher,
  R.string.browser_scope_pack)
