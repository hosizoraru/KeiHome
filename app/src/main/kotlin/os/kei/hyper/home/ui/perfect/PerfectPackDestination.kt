package os.kei.hyper.home.ui.perfect

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.NavigationItem
import os.kei.hyper.home.ui.main.HomeViewModel
import os.kei.hyper.home.ui.main.PackTabScaffold
import os.kei.hyper.home.ui.main.PageActionIcon
import os.kei.hyper.home.ui.main.TabPagerNavigation
import os.kei.hyper.home.ui.main.actionIcon

internal enum class PerfectPackTab(val label: Int, val icon: PageActionIcon) {
  GENERATE(R.string.action_label_generate, PageActionIcon.GENERATE),
  RESOURCES(R.string.perfect_resources_tab, PageActionIcon.PACKS),
  PREVIEW(R.string.perfect_icons_preview_title, PageActionIcon.APPS),
  CUSTOM(R.string.perfect_custom_tab, PageActionIcon.CUSTOMIZE);

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

  val items = tabs.map { NavigationItem(label = stringResource(it.label), icon = actionIcon(it.icon)) }
  PackTabScaffold(vm, pager, navigation, items, "icon-packs.perfect") { index, pageBackdrop, bottomInset ->
    val page = tabs[index]
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
      PerfectPackTab.CUSTOM -> PerfectCustomStaticScreen(vm.perfectPacks, pageBackdrop, bottomInset,
        onBack, active = tab == page, onGenerate = { selectTab(PerfectPackTab.GENERATE) })
    }
  }
}
