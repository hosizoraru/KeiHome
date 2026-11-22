package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppNavigationBarStyle
import os.kei.hyper.home.ui.components.KeiHomeNavigationBar
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop

internal enum class PerfectPackTab(val label: Int, val icon: PageActionIcon) {
  GENERATE(R.string.action_label_generate, PageActionIcon.GENERATE),
  RESOURCES(R.string.perfect_resources_tab, PageActionIcon.PACKS),
  PREVIEW(R.string.perfect_icons_preview_title, PageActionIcon.APPS);

  companion object {
    fun forSection(section: String): PerfectPackTab? = when (section) {
      "perfect" -> GENERATE
      "perfect-sources", "perfect-credits" -> RESOURCES
      "perfect-preview" -> PREVIEW
      else -> null
    }
  }
}

@Composable
internal fun PerfectPackDestination(vm: HomeViewModel, initialTab: PerfectPackTab, onBack: () -> Unit) {
  val tabs = PerfectPackTab.entries
  val pager = key(initialTab) { rememberPagerState(initialPage = initialTab.ordinal) { tabs.size } }
  var visitedResources by rememberSaveable(initialTab) { mutableStateOf(initialTab == PerfectPackTab.RESOURCES) }
  val savedTabs = rememberSaveableStateHolder()
  val backdrop = rememberKeiHomeBlurBackdrop()
  val (navigationStyleFlow, initialNavigationStyle) = remember(vm) {
    vm.preferences.state.map { it.navigationBarStyle }.distinctUntilChanged() to
      vm.preferences.state.value.navigationBarStyle
  }
  val navigationStyle by navigationStyleFlow.collectAsStateWithLifecycle(initialValue = initialNavigationStyle)
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  val focus = LocalFocusManager.current
  val scope = rememberCoroutineScope()
  val navigation = remember(pager, scope) { TabPagerNavigation(pager, scope) }
  val tab = tabs[navigation.selectedPage]
  fun selectTab(next: PerfectPackTab) {
    focus.clearFocus(force = true)
    navigation.selectPage(next.ordinal)
  }
  val openResources = { selectTab(PerfectPackTab.RESOURCES) }
  LaunchedEffect(tab) {
    if (tab == PerfectPackTab.RESOURCES) visitedResources = true
  }
  ObservePerfectResources(vm.perfectPacks, ensureCatalog = visitedResources)
  LaunchedEffect(vm, lifecycle, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.observePage(3, locales) }
  }
  LaunchedEffect(vm) {
    vm.perfectPacks.state.map { it.retryAtMillis }.distinctUntilChanged().collectLatest { retryAtMillis ->
      val remaining = retryAtMillis - System.currentTimeMillis()
      if (remaining > 0) delay(remaining)
      vm.perfectPacks.updateCooldown()
    }
  }

  KeiHomeScaffold(
    modifier = Modifier.imePadding().testTag("icon-packs.perfect.destination"),
    backdrop = backdrop,
    bottomBar = { PerfectPackBottomBar(navigationStyle, tab, backdrop, ::selectTab) },
  ) { insets ->
    // Record the moving pages beneath the stationary navigation bar. Each page owns its top bar's layer.
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      KeiHomeTabPager(vm, pager, Modifier.fillMaxSize().testTag("icon-packs.perfect.pager")) { index ->
        val page = tabs[index]
        savedTabs.SaveableStateProvider(page.name) {
          val pageBackdrop = rememberKeiHomeBlurBackdrop()
          val bottomInset: @Composable () -> Unit = { Spacer(Modifier.height(insets.calculateBottomPadding())) }
          when (page) {
            PerfectPackTab.GENERATE -> PerfectPackScreen(vm, onBack, openResources, pageBackdrop, bottomInset)
            PerfectPackTab.RESOURCES -> PerfectResourceScreen(vm, onBack, pageBackdrop, bottomInset,
              active = tab == page) { style ->
              vm.perfectPacks.previewStyle(style) {
                if (scope.isActive && navigation.selectedPage == index) selectTab(PerfectPackTab.PREVIEW)
              }
            }
            PerfectPackTab.PREVIEW -> PerfectPackPreviewScreen(vm, onBack, openResources, pageBackdrop,
              bottomInset, active = tab == page)
          }
        }
      }
    }
  }
}

@Composable
private fun PerfectPackBottomBar(
  style: AppNavigationBarStyle,
  tab: PerfectPackTab,
  backdrop: LayerBackdrop?,
  onSelect: (PerfectPackTab) -> Unit,
) {
  val items = PerfectPackTab.entries.map {
    NavigationItem(label = stringResource(it.label), icon = actionIcon(it.icon))
  }
  KeiHomeNavigationBar(style = style, items = items, selectedIndex = tab.ordinal,
    onSelect = { index -> PerfectPackTab.entries.getOrNull(index)?.let(onSelect) },
    backdrop = backdrop, modifier = Modifier.testTag("icon-packs.perfect.tabs"))
}
