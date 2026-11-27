package os.kei.hyper.home

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.status.NativeThemeApplyWindow
import os.kei.hyper.home.ui.components.KeiHomeAdaptiveNavigation
import os.kei.hyper.home.ui.components.ProvideKeiHomeNavChrome
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import os.kei.hyper.home.ui.main.DesktopSection
import os.kei.hyper.home.ui.main.DesktopDestinationScreen
import os.kei.hyper.home.ui.main.HomeViewModel
import os.kei.hyper.home.ui.main.IconPackProgressHost
import os.kei.hyper.home.ui.main.IconPackStep
import os.kei.hyper.home.ui.main.MainScreen
import os.kei.hyper.home.ui.main.MainShortcut
import os.kei.hyper.home.ui.main.MainTab
import os.kei.hyper.home.ui.main.TabPagerNavigation
import os.kei.hyper.home.ui.main.PackNavigationBadge
import os.kei.hyper.home.ui.main.actionIcon
import os.kei.hyper.home.ui.main.mainShortcutItems
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
import top.yukonga.miuix.kmp.basic.NavigationItem

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
  val applyingIcons = NativeIconAutoNavigation(model)
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
  val tabs = MainTab.entries
  val pager = key("main-tabs-v3") { rememberPagerState { tabs.size } }
  val tabScope = rememberCoroutineScope()
  val tabNavigation = remember(pager, tabScope) { TabPagerNavigation(pager, tabScope) }
  val focus = LocalFocusManager.current
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
  val section = when (current) {
    is PackRoute -> MainTab.PACKS
    is DesktopRoute -> MainTab.DESKTOP
    is SettingsRoute -> MainTab.SETTINGS
    else -> tabs[tabNavigation.selectedPage.coerceIn(tabs.indices)]
  }
  val shortcut = MainShortcut.entries.indexOfFirst { it.route == current }
  val navigation = tabs.map { NavigationItem(stringResource(it.title), actionIcon(it.icon)) }
  val backdrop = rememberKeiHomeBlurBackdrop()
  val railEnabled = backStack.firstOrNull() == Main &&
    backStack.all { it is PackRoute || it is DesktopRoute || it is SettingsRoute || it == Main }
  KeiHomeAdaptiveNavigation(
    items = navigation,
    selectedIndex = section.ordinal,
    onSelect = { index ->
      val nextShortcut = MainShortcut.entries.getOrNull(index - tabs.size)
      val tab = tabs.getOrNull(index) ?: nextShortcut?.parent
      if (tab != null && (nextShortcut == null || backStack.lastOrNull() != nextShortcut.route)) {
        focus.clearFocus(force = true)
        if (backStack.lastOrNull() == Main && nextShortcut == null) tabNavigation.selectPage(tab.ordinal)
        else {
          tabNavigation.jumpToPage(tab.ordinal)
          while (backStack.size > 1) back()
          nextShortcut?.let { backStack.add(it.route) }
        }
      }
    },
    backdrop = backdrop,
    railBackdrop = backdrop.takeIf { current == Main },
    badge = { index -> if (index == MainTab.PACKS.ordinal) ({ PackNavigationBadge(model) }) else null },
    secondaryItems = mainShortcutItems(),
    selectedSecondary = shortcut,
    railEnabled = railEnabled,
    overlay = { IconPackProgressHost(model, applyingIcons) },
  ) { wide ->
    val activity = LocalActivity.current
    val cornerRadius = if (wide || activity?.isInMultiWindowMode == true) androidx.compose.ui.unit.Dp(0f)
      else rememberNavSystemCornerRadius()
    NavDisplay(backStack = backStack,
      onBack = ::back,
      transition = NavTransitions.MiuixDefault,
      effects = NavDisplayEffects(cornerClipRadius = cornerRadius,
        dimAmount = 0.26f, blockInputDuringTransition = true,
        backdropColor = MiuixTheme.colorScheme.surface)) {
      entry<Main> {
        val transitionRunning = LocalNavTransitionScope.current.isRunning
        ProvideKeiHomeNavChrome(isCurrentDestination = current == Main) {
          MainScreen(model = model, onOpenPackSection = ::open, onOpenSettingsSection = ::openSettings,
            onOpenDesktopSection = ::openDesktop, pager = pager, tabNavigation = tabNavigation,
            navigation = navigation, backdrop = backdrop, wide = wide) {
            val show = current == Main && pendingVersion.isEmpty() && !transitionRunning
            UpgradeRestartReminder(
              version = if (show) releaseState?.restartReminderVersion.orEmpty() else "",
              onAcknowledged = { releaseNotesPreferences?.acknowledgeRestart(it) },
            )
          }
        }
      }
      entry<DesktopRoute> { route ->
        val section = DesktopSection.entries.firstOrNull { it.route == route.section } ?: DesktopSection.DOCK
        DesktopDestinationScreen(model, section, onBack = ::back, navigationPage = route.page,
          onOpenNavigationPage = { backStack.add(route.copy(page = it)) })
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
      entry<PackRoute>(swipeDismiss = swipe) { route ->
        ProvideKeiHomeNavChrome(isCurrentDestination = current == route) {
          PackDestinationScreen(model, route.section,
            onBack = { if (backStack.lastOrNull() == route && backStack.size > 1) backStack.removeLastOrNull() },
            onNavigate = { section -> if (backStack.lastOrNull() == route) open(section) })
        }
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
}

@Composable
private fun NativeIconAutoNavigation(model: HomeViewModel): IconPackStep? {
  val context = LocalContext.current
  val activity = LocalActivity.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val windowState by model.nativeThemeApplyWindow.state.collectAsStateWithLifecycle()
  var applying by rememberSaveable { mutableStateOf(false) }
  val phase = remember { mutableStateOf<IconPackStep?>(null) }
  val apply = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (applying) {
      applying = false
      phase.value = if (result.resultCode == android.app.Activity.RESULT_OK) IconPackStep.COMPLETE else null
    }
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
            if (intent != null && !model.nativeThemeApplyWindow.launch(context, intent) { model.refresh(3) }) {
              applying = true
              runCatching { apply.launch(intent) }.onFailure { applying = false }
            }
          }
        }
    }
  }
  LaunchedEffect(phase.value) {
    if (phase.value == IconPackStep.COMPLETE) {
      delay(850)
      phase.value = null
    }
  }
  return when {
    applying || windowState == NativeThemeApplyWindow.State.APPLYING -> IconPackStep.APPLYING
    windowState == NativeThemeApplyWindow.State.COMPLETE -> IconPackStep.COMPLETE
    else -> phase.value
  }
}
