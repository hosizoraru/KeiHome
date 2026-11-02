package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PackDestinationScreen(model: HomeViewModel, section: String,
  onBack: () -> Unit, onNavigate: (String) -> Unit) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(model, lifecycle, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observePage(3, locales) }
  }
  val behavior = MiuixScrollBehavior()
  val list = rememberLazyListState()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(title = stringResource(when (section) {
          "library" -> R.string.icon_pack_library
          "builtin" -> R.string.pack_builtin
          "installed" -> R.string.pack_installed
          "imported" -> R.string.pack_imported
          else -> R.string.pack_coloros_title
        }), scrollBehavior = behavior,
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          navigationIcon = { IconButton(onClick = onBack) {
            Icon(MiuixIcons.Back, stringResource(R.string.pack_back))
          } }, actions = { RefreshAction(model, 3) })
      }
    }, floatingActionButton = { PackActionFab(model, 3, section) },
  ) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      IconPacksPage(model, list,
        PaddingValues(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 4.dp,
          bottom = insets.calculateBottomPadding() + if (section == "coloros") 100.dp else 24.dp),
        Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection), section, onNavigate)
    }
  }
}
