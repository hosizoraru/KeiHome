package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import os.kei.hyper.home.ui.components.KeiHomeNavigationBar
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.Os4ChromeStowContainer
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import os.kei.hyper.home.ui.components.rememberNavBottomBarVisible
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop

@Composable
internal fun PackTabScaffold(
  model: HomeViewModel,
  pager: PagerState,
  navigation: TabPagerNavigation,
  items: List<NavigationItem>,
  tag: String,
  badge: (Int) -> (@Composable () -> Unit)? = { null },
  floatingActionButton: @Composable () -> Unit = {},
  overlays: @Composable () -> Unit = {},
  content: @Composable (Int, LayerBackdrop?, @Composable () -> Unit) -> Unit,
) {
  val savedTabs = rememberSaveableStateHolder()
  val backdrop = rememberKeiHomeBlurBackdrop()
  val focus = LocalFocusManager.current
  val (styleFlow, initialStyle) = remember(model) {
    model.preferences.state.map { it.navigationBarStyle }.distinctUntilChanged() to
      model.preferences.state.value.navigationBarStyle
  }
  val style by styleFlow.collectAsStateWithLifecycle(initialValue = initialStyle)
  val bottomBarVisible = rememberNavBottomBarVisible()
  KeiHomeScaffold(
    modifier = Modifier.testTag("$tag.destination"), backdrop = backdrop,
    floatingActionButton = {
      Os4ChromeStowContainer(visible = bottomBarVisible) {
        floatingActionButton()
      }
    },
    bottomBar = {
      KeiHomeNavigationBar(
        style = style,
        items = items,
        selectedIndex = navigation.selectedPage,
        onSelect = { index ->
          focus.clearFocus(force = true)
          navigation.selectPage(index)
        },
        modifier = Modifier.testTag("$tag.tabs"),
        backdrop = backdrop,
        badge = badge,
        visible = bottomBarVisible,
      )
    },
  ) { insets ->
    // The stationary bottom bar samples the moving pages. Each top bar uses its own recording.
    // IME clearance stays on inner scrollable insets so the floating bottom bar never rides above the keyboard.
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      KeiHomeTabPager(model, pager, Modifier.fillMaxSize().testTag("$tag.pager")) { index ->
        savedTabs.SaveableStateProvider(index) {
          val pageBackdrop = rememberKeiHomeBlurBackdrop()
          val bottomInset: @Composable () -> Unit = {
            val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
            Spacer(Modifier.height(maxOf(insets.calculateBottomPadding(), imeBottom)))
          }
          content(index, pageBackdrop, bottomInset)
        }
      }
    }
    overlays()
  }
}
