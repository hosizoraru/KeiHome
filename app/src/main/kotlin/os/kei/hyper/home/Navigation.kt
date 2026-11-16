package os.kei.hyper.home

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.ui.main.DesktopSection
import os.kei.hyper.home.ui.main.DesktopDestinationScreen
import os.kei.hyper.home.ui.main.HomeViewModel
import os.kei.hyper.home.ui.main.MainScreen
import os.kei.hyper.home.ui.main.PackDestinationScreen
import os.kei.hyper.home.ui.main.SettingsDestinationScreen
import os.kei.hyper.home.ui.main.SettingsSection
import os.kei.hyper.home.ui.main.ReleaseNotesScreen
import os.kei.hyper.home.ui.main.UpgradeRestartReminder
import os.kei.hyper.home.ui.main.OnboardingScreen
import os.kei.hyper.home.ui.main.OnboardingDesktopScreen
import os.kei.hyper.home.data.OnboardingPreferences
import os.kei.hyper.home.data.ReleaseNotesPreferences
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MainNavigation(
  setup: OnboardingPreferences = OnboardingPreferences.get(LocalContext.current),
  releaseNotesPreferences: ReleaseNotesPreferences? = null,
) {
  val model: HomeViewModel = viewModel()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(model, lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) model.scopeRequests.onResumed()
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  NativeIconAutoNavigation(model)
  val releaseState = releaseNotesPreferences?.state?.collectAsStateWithLifecycle()?.value
  val pending = releaseState?.pendingVersions.orEmpty()
  val pendingVersion = releaseState?.pendingVersion.orEmpty()
  val initialRoutes = remember(setup) {
    if (setup.state.value == OnboardingPreferences.COMPLETE)
      listOfNotNull(Main, ReleaseNotesUpgradeRoute.takeIf { pendingVersion.isNotEmpty() })
    else (0..setup.state.value).map { OnboardingRoute(it) }
  }
  val backStack = rememberNavBackStack<HomeRoute>(*initialRoutes.toTypedArray())
  val progress by setup.state.collectAsStateWithLifecycle()
  val current = backStack.lastOrNull()
  LaunchedEffect(current) {
    if (current is OnboardingRoute && !current.replay) setup.visit(current.page)
  }
  LaunchedEffect(progress) {
    // A process can die between saving completion and replacing its navigation stack.
    if (progress == OnboardingPreferences.COMPLETE &&
      backStack.any { it is OnboardingRoute && !it.replay }) {
      backStack.clear()
      backStack.add(Main)
    }
  }
  LaunchedEffect(progress, pendingVersion, current) {
    if (pendingVersion.isEmpty()) backStack.removeAll { it == ReleaseNotesUpgradeRoute }
    if (progress == OnboardingPreferences.COMPLETE && pendingVersion.isNotEmpty() &&
      backStack.size == 1 && current == Main) backStack.add(ReleaseNotesUpgradeRoute)
  }
  var monetNested by remember { mutableStateOf(false) }
  val swipe = if (LocalLayoutDirection.current == LayoutDirection.Rtl)
    NavSwipeDirection.RightToLeft else NavSwipeDirection.LeftToRight
  fun open(section: String) {
    val route = PackRoute(section)
    if (backStack.lastOrNull() != route) backStack.add(route)
  }
  fun openSettings(section: String) {
    val route: HomeRoute = if (section == "setup") OnboardingRoute(0, replay = true) else SettingsRoute(section)
    if (backStack.lastOrNull() != route) backStack.add(route)
  }
  fun openDesktop(section: String) {
    if (DesktopSection.entries.none { it != DesktopSection.OVERVIEW && it.route == section }) return
    backStack.add(DesktopRoute(section))
  }
  val releaseReadSession = remember { mutableSetOf<String>() }
  fun recordReleaseViews(versions: Set<String>) {
    releaseReadSession.clear()
    releaseReadSession.addAll(versions)
  }
  fun back() {
    val route = backStack.lastOrNull()
    if (route == ReleaseNotesUpgradeRoute || route == SettingsRoute(SettingsSection.RELEASE_NOTES.route))
      releaseNotesPreferences?.markRead(releaseReadSession)
    if (backStack.lastOrNull() == ReleaseNotesUpgradeRoute) releaseNotesPreferences?.acknowledge()
    if (backStack.size > 1) backStack.removeLastOrNull()
  }
  fun finishSetup(route: OnboardingRoute) {
    setup.complete()
    if (route.replay) {
      while (backStack.lastOrNull() is OnboardingRoute) backStack.removeLastOrNull()
    } else {
      backStack.clear()
      backStack.add(Main)
    }
  }
  NavDisplay(backStack = backStack,
    onBack = ::back,
    transition = NavTransitions.MiuixDefault,
    effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius(),
      dimAmount = 0.26f, blockInputDuringTransition = true,
      backdropColor = MiuixTheme.colorScheme.surface)) {
    entry<Main> {
      val transitionRunning = LocalNavTransitionScope.current.isRunning
      MainScreen(model = model, onOpenPackSection = ::open, onOpenSettingsSection = ::openSettings,
        onOpenDesktopSection = ::openDesktop, overlay = {
          UpgradeRestartReminder(
            version = releaseState?.restartReminderVersion.orEmpty().takeIf {
              current == Main && pendingVersion.isEmpty() && !transitionRunning
            }.orEmpty(),
            onAcknowledged = { releaseNotesPreferences?.acknowledgeRestart(it) },
          )
        })
    }
    entry<DesktopRoute> { route ->
      val section = DesktopSection.entries.firstOrNull { it.route == route.section } ?: DesktopSection.DOCK
      DesktopDestinationScreen(model, section, onBack = ::back)
    }
    entry<OnboardingRoute>(swipeDismiss = swipe) { route ->
      OnboardingScreen(model, page = route.page, replay = route.replay,
        onBack = { if (backStack.lastOrNull() == route) back() }, onExit = { if (backStack.lastOrNull() == route) finishSetup(route) },
        onNext = {
          if (backStack.lastOrNull() == route) {
            if (route.page == OnboardingPreferences.PAGE_COUNT - 1) finishSetup(route)
            else backStack.add(route.copy(page = route.page + 1))
          }
        }, onOpenPack = { if (backStack.lastOrNull() == route) open(it) },
        onOpenDesktop = { if (backStack.lastOrNull() == route) backStack.add(OnboardingDesktop) },
        onOpenSettings = { if (backStack.lastOrNull() == route) openSettings(it) },
        onOpenDesktopSection = { if (backStack.lastOrNull() == route) openDesktop(it) })
    }
    entry<OnboardingDesktop>(swipeDismiss = swipe) {
      OnboardingDesktopScreen(model, onBack = ::back, onOpenSection = ::openDesktop)
    }
    entry<PackRoute>(swipeDismiss = if (monetNested && current == PackRoute("monet")) NavSwipeDirection.None else swipe) { route ->
      PackDestinationScreen(model, route.section,
        onBack = { if (backStack.lastOrNull() == route && backStack.size > 1) backStack.removeLastOrNull() },
        onNavigate = { section -> if (backStack.lastOrNull() == route) open(section) },
        onMonetNestedChanged = { monetNested = it })
    }
    entry<SettingsRoute>(swipeDismiss = swipe) { route ->
      SettingsDestinationScreen(model, route.section,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() }, onNavigate = ::openSettings,
        onOpenPackSection = ::open, releaseNotesPreferences = releaseNotesPreferences,
        onReleaseNotesViewed = ::recordReleaseViews)
    }
    entry<ReleaseNotesUpgradeRoute> {
      ReleaseNotesScreen(onBack = ::back, currentVersion = releaseState?.observedVersion ?: BuildConfig.VERSION_NAME,
        highlightedVersions = pending.toSet(),
        unreadVersions = releaseState?.unreadVersions.orEmpty().toSet(),
        onViewedVersionsChanged = ::recordReleaseViews,
        onReadVersions = { releaseNotesPreferences?.markRead(it) }, onContinue = {
        if (backStack.lastOrNull() == ReleaseNotesUpgradeRoute) back()
      })
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
