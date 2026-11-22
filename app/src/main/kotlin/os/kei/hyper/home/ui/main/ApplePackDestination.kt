package os.kei.hyper.home.ui.main

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.NavigationItem

internal enum class ApplePackTab(val section: String, val label: Int, val icon: PageActionIcon) {
  GENERATE("apple", R.string.action_label_generate, PageActionIcon.GENERATE),
  MATCHED("apple-matched", R.string.apple_matched_title, PageActionIcon.APPS),
  MAPPINGS("apple-mappings", R.string.apple_mappings, PageActionIcon.CUSTOMIZE);

  companion object {
    fun forSection(section: String): ApplePackTab? = entries.firstOrNull { it.section == section }
  }
}

@Composable
internal fun ApplePackDestination(model: HomeViewModel, initialTab: ApplePackTab, onBack: () -> Unit,
  onOpenSearch: () -> Unit) {
  val tabs = ApplePackTab.entries
  val pager = key(initialTab) { rememberPagerState(initialPage = initialTab.ordinal) { tabs.size } }
  val scope = rememberCoroutineScope()
  val navigation = remember(pager, scope) { TabPagerNavigation(pager, scope) }
  val loadRequested = observeApplePack(model)
  val items = tabs.map { NavigationItem(label = stringResource(it.label), icon = actionIcon(it.icon)) }
  PackTabScaffold(model, pager, navigation, items, "icon-packs.apple") { index, backdrop, bottomInset ->
    ApplePackScreen(model, tabs[index].section, onBack, onOpenSearch, loadRequested, backdrop,
      bottomBar = bottomInset, active = navigation.selectedPage == index)
  }
}

@Composable
internal fun ApplePackSearchDestination(model: HomeViewModel, onBack: () -> Unit) {
  val loadRequested = observeApplePack(model)
  ApplePackScreen(model, "apple-search", onBack, {}, loadRequested, rememberKeiHomeBlurBackdrop())
}

@Composable
private fun observeApplePack(model: HomeViewModel): Boolean {
  val controller = model.applePacks
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  var loadRequested by remember(controller, locales) { mutableStateOf(false) }
  LaunchedEffect(controller, locales) {
    controller.ensureLoaded(locales)
    loadRequested = true
  }
  LaunchedEffect(controller) {
    controller.state.map { it.retryAtMillis }.distinctUntilChanged().collectLatest { retryAtMillis ->
      if (retryAtMillis > 0) {
        delay((retryAtMillis - System.currentTimeMillis()).coerceAtLeast(0))
        controller.refreshCooldown()
      }
    }
  }
  LaunchedEffect(model, lifecycle, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observePage(3, locales) }
  }
  return loadRequested
}
