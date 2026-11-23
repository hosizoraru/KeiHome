package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DesktopDestinationScreen(model: HomeViewModel, section: DesktopSection, onBack: () -> Unit,
  navigationPage: String? = null, onOpenNavigationPage: (String) -> Unit = {}) {
  if (section == DesktopSection.TOOLS) {
    DesktopToolsScreen(model, onBack)
    return
  }
  if (section == DesktopSection.NAVIGATION) {
    NavigationBackdropScreen(model, onBack,
      page = NavigationBackdropPage.entries.firstOrNull { it.route == navigationPage }
        ?: NavigationBackdropPage.OVERVIEW,
      onOpen = { onOpenNavigationPage(it.route) })
    return
  }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(model, section, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeDesktopStatus(section) }
  }
  val refreshing by model.refreshing.collectAsStateWithLifecycle()
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(modifier = Modifier.testTag("desktop.page.${section.route}"), backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(section.title), scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = onBack) {
          Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
        } },
        actions = { IconButton(enabled = 5 !in refreshing, onClick = { model.refresh(5, section) }) {
          Icon(MiuixIcons.Glass.Refresh, stringResource(R.string.refresh))
        } })
    }
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      Column(Modifier.fillMaxSize().keiHomeTopBarScroll(behavior)
        .verticalScroll(rememberScrollState(), overscrollEffect = null).padding(PaddingValues(
          start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 4.dp,
          bottom = insets.calculateBottomPadding() + 24.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DesktopStatusCard(model, section)
        section.features.forEach { feature ->
          when (feature) {
            DesktopFeature.DOCK_MATERIAL -> LauncherGlassSettingsCard(model)
            DesktopFeature.DOCK_STYLE -> DockGlassSettingsCard(model)
            DesktopFeature.SEARCH_MATERIAL -> SearchGlassSettingsCard(model)
            DesktopFeature.LABELS -> LauncherLabelSettingsCard(model)
            else -> DesktopIconSettings(model, feature)
          }
        }
        if (section == DesktopSection.APPEARANCE) {
          WallpaperPresetSettingsCard(model)
          WallpaperDimmingSettingsCard(model)
        }
      }
    }
  }
}

@Composable
private fun DesktopIconSettings(model: HomeViewModel, feature: DesktopFeature) {
  val framework by model.framework.collectAsStateWithLifecycle()
  val effects by model.themeEffects.collectAsStateWithLifecycle()
  val splash by model.splashAppearance.collectAsStateWithLifecycle()
  val splashIcons by model.splashIcons.state.collectAsStateWithLifecycle()
  when (feature) {
    DesktopFeature.ICON_EFFECTS -> ThemeEffectsCard(effects, framework, model::setPreserveSystemMaterials, showStatus = false)
    DesktopFeature.SPLASH_ICONS -> SplashIconSettingsCard(splashIcons, framework.splashAppearanceInScope(),
      model.splashIcons::save, showStatus = false,
      scopeChecking = framework.desktopScopeChecking(), scopeReadFailed = framework.readFailed)
    DesktopFeature.SPLASH -> SplashAppearanceSettingsCard(splash, framework.splashAppearanceInScope(),
      model::setSplashAppearance, showStatus = false,
      scopeChecking = framework.desktopScopeChecking(), scopeReadFailed = framework.readFailed)
    else -> Unit
  }
}
