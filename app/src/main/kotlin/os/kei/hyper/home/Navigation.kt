package os.kei.hyper.home

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.ui.main.HomeViewModel
import os.kei.hyper.home.ui.main.MainScreen
import os.kei.hyper.home.ui.main.PackDestinationScreen
import os.kei.hyper.home.ui.main.SettingsDestinationScreen
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainNavigation() {
  val model: HomeViewModel = viewModel()
  NativeIconAutoNavigation(model)
  val backStack = rememberNavBackStack<HomeRoute>(Main)
  val swipe = if (LocalLayoutDirection.current == LayoutDirection.Rtl)
    NavSwipeDirection.RightToLeft else NavSwipeDirection.LeftToRight
  fun open(section: String) {
    val route = PackRoute(section)
    if (backStack.lastOrNull() != route) backStack.add(route)
  }
  fun openSettings(section: String) {
    val route = SettingsRoute(section)
    if (backStack.lastOrNull() != route) backStack.add(route)
  }
  NavDisplay(backStack = backStack,
    onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
    transition = NavTransitions.MiuixDefault,
    effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius(),
      dimAmount = 0.26f, blockInputDuringTransition = true,
      backdropColor = MiuixTheme.colorScheme.surface)) {
    entry<Main> { MainScreen(model = model, onOpenPackSection = ::open, onOpenSettingsSection = ::openSettings) }
    entry<PackRoute>(swipeDismiss = swipe) { route ->
      PackDestinationScreen(model, route.section,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() }, onNavigate = ::open)
    }
    entry<SettingsRoute>(swipeDismiss = swipe) { route ->
      SettingsDestinationScreen(model, route.section,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() }, onNavigate = ::openSettings)
    }
  }
}

@Composable
private fun NativeIconAutoNavigation(model: HomeViewModel) {
  val context = LocalContext.current
  val activity = LocalActivity.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val apply = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == android.app.Activity.RESULT_OK) {
      model.refresh(3)
      activity?.window?.decorView?.apply {
        requestLayout()
        postInvalidateOnAnimation()
      }
    }
  }
  LaunchedEffect(model, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      model.iconPacks.map { if (it.nativeImporting == null) it.nativeResult else null }
        .distinctUntilChanged().collect { report ->
          if (report != null && model.preferences.state.value.autoApplyIconPack) {
            val intent = withContext(Dispatchers.IO) { IconPackImportProtocol.takeAutoApply(context, report) }
            if (intent != null) runCatching { apply.launch(intent) }
          }
        }
    }
  }
}
