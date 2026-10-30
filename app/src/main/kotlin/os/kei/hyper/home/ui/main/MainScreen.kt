package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
  val model: HomeViewModel = viewModel()
  var selectedTab by rememberSaveable { mutableIntStateOf(0) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(model, lifecycle, selectedTab, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observePage(selectedTab, locales) }
  }
  val labels = listOf(stringResource(R.string.navigation_status), stringResource(R.string.navigation_apps),
    stringResource(R.string.navigation_monet), stringResource(R.string.navigation_settings))
  val navigation = remember(labels) { listOf(
    NavigationItem(labels[0], MiuixIcons.Info), NavigationItem(labels[1], MiuixIcons.GridView),
    NavigationItem(labels[2], MiuixIcons.Theme), NavigationItem(labels[3], MiuixIcons.Settings),
  ) }
  val backdrop = rememberKeiHomeBlurBackdrop()
  val behaviors = listOf(MiuixScrollBehavior(), MiuixScrollBehavior(), MiuixScrollBehavior(), MiuixScrollBehavior())
  val appList = rememberLazyListState()
  val pageScrolls = listOf(rememberScrollState(), rememberScrollState(), rememberScrollState(), rememberScrollState())
  val savedPages = rememberSaveableStateHolder()
  Scaffold(
    modifier = modifier,
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(
          title = if (selectedTab == 0) stringResource(R.string.app_name) else labels[selectedTab],
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          scrollBehavior = behaviors[selectedTab],
          actions = {
            if (selectedTab == 1) AppsFilterMenu(model)
            if (selectedTab != 3) RefreshAction(model, selectedTab)
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
      savedPages.SaveableStateProvider(selectedTab) {
        val pageModifier = Modifier.fillMaxSize().nestedScroll(behaviors[selectedTab].nestedScrollConnection)
        if (selectedTab == 1) {
          AppsPage(model, appList, padding, pageModifier)
        } else {
          // These pages are a single form. A nested lazy-item saveable owner drops the form's
          // draft/expansion state when the outer page provider saves during tab disposal.
          Column(modifier = pageModifier.verticalScroll(pageScrolls[selectedTab]).padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (selectedTab) {
              0 -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  StatusPage(model, onOpenApps = { selectedTab = 1 }, onOpenMonet = { selectedTab = 2 })
                  RefreshFeedback(model, 0)
                }
              }
              2 -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  MonetPage(model)
                  RefreshFeedback(model, 2)
                }
              }
              3 -> SettingsPage(model)
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
  val monet by model.monet.collectAsStateWithLifecycle()
  val apps by model.apps.collectAsStateWithLifecycle()
  val counts = remember(apps.capabilities) { apps.capabilities?.let { list ->
    Triple(list.size, list.count { it.nightMode == IconCapabilityStatus.DETECTED },
      list.count { it.monochrome == IconCapabilityStatus.DETECTED })
  } }
  StatusBody(connected = framework.connected, snapshot = framework.snapshot, readFailed = framework.readFailed,
    monetSnapshot = monet.snapshot, monetReadFailed = monet.readFailed, onRefresh = { model.refresh(0) },
    onOpenMonet = onOpenMonet, onOpenApps = onOpenApps,
    installedApps = counts?.first, nightApps = counts?.second, monetApps = counts?.third)
}

@Composable
private fun MonetPage(model: HomeViewModel) {
  val monet by model.monet.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  MonetContent(snapshot = monet.snapshot, readFailed = monet.readFailed,
    actionAvailable = framework.connected && !framework.readFailed &&
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
