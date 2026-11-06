package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PackDestinationScreen(model: HomeViewModel, section: String,
  onBack: () -> Unit, onNavigate: (String) -> Unit) {
  if (section == "monet") {
    MonetDestinationScreen(model, onBack)
    return
  }
  val fallback = section == "fallback"
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(model, lifecycle, locales, section) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      coroutineScope {
        launch { model.observePage(3, locales) }
        if (fallback) model.observeFallbackResources(pageObserved = true)
      }
    }
  }
  val behavior = MiuixScrollBehavior()
  val list = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(title = stringResource(when (section) {
          "library" -> R.string.icon_pack_library
          "builtin" -> R.string.pack_builtin
          "installed" -> R.string.pack_installed
          "imported" -> R.string.pack_imported
          "fallback" -> R.string.pack_completion_title
          else -> R.string.pack_coloros_title
        }), scrollBehavior = behavior,
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          navigationIcon = { IconButton(onClick = onBack) {
            Icon(MiuixIcons.Back, stringResource(R.string.pack_back))
          } }, actions = {
            if (section == "installed" || section == "imported") PackSortMenu(model) {
              scope.launch { list.scrollToItem(0) }
            }
            RefreshAction(model, 3)
          })
      }
    }, floatingActionButton = { PackActionFab(model, 3, section) },
  ) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      val padding = PaddingValues(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 4.dp,
        bottom = insets.calculateBottomPadding() + if (section == "coloros" || fallback) 100.dp else 24.dp)
      val contentModifier = Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection)
      if (fallback) {
        Column(contentModifier.verticalScroll(rememberScrollState()).padding(padding),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
          SystemIconFallbackCard(model)
          IconPackRefreshFeedback(model)
        }
      } else IconPacksPage(model, list, padding, contentModifier, section, onNavigate)
    }
  }
}

@Composable
private fun PackSortMenu(model: HomeViewModel, onOrderChange: () -> Unit) {
  val sort by model.packSort.collectAsStateWithLifecycle()
  val labels = listOf(R.string.icon_pack_sort_name, R.string.icon_pack_sort_identity,
    R.string.icon_pack_sort_updated, R.string.icon_pack_sort_matched)
  val choices = DropdownEntry(items = labels.mapIndexed { index, label ->
    DropdownItem(text = stringResource(label), selected = sort.first == index, onClick = {
      model.updatePackSort(index, model.packSort.value.second)
      onOrderChange()
    })
  })
  val direction = DropdownEntry(items = listOf(
    DropdownItem(text = stringResource(R.string.browser_sort_reverse), selected = sort.second, onClick = {
      model.updatePackSort(model.packSort.value.first, !model.packSort.value.second)
      onOrderChange()
    }),
  ))
  OverlayIconCascadingDropdownMenu(entries = listOf(choices, direction)) {
    Icon(MiuixIcons.Sort, stringResource(R.string.browser_sort),
      tint = if (sort.first != 0 || sort.second) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground)
  }
}
