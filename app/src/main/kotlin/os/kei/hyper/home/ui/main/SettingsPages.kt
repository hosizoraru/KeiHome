package os.kei.hyper.home.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class SettingsSection(val route: String, @param:StringRes val title: Int, @param:StringRes val summary: Int) {
  APP("app", R.string.settings_app_section, R.string.settings_app_summary),
  HOOK("hook", R.string.settings_hook_section, R.string.settings_hook_summary),
  CACHE("cache", R.string.settings_cache_section, R.string.settings_cache_summary),
  LOG("log", R.string.settings_log_section, R.string.settings_log_summary),
  ABOUT("about", R.string.polish_settings_about, R.string.settings_about_summary),
}

@Composable
internal fun SettingsRootPage(model: HomeViewModel, onNavigate: (String) -> Unit) {
  var query by rememberSaveable { mutableStateOf("") }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    InputField(query = query, onQueryChange = { query = it }, onSearch = {}, expanded = false,
      onExpandedChange = {}, modifier = Modifier.fillMaxWidth().testTag("settings.search"),
      label = stringResource(R.string.settings_search))
    if (query.isBlank()) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        SettingsSection.entries.forEach { section ->
          val icon = when (section) {
            SettingsSection.APP -> MiuixIcons.Settings
            SettingsSection.HOOK -> MiuixIcons.Layers
            SettingsSection.CACHE -> MiuixIcons.Refresh
            SettingsSection.LOG -> MiuixIcons.Info
            SettingsSection.ABOUT -> MiuixIcons.Info
          }
          BasicComponent(title = stringResource(section.title), summary = stringResource(section.summary),
            onClick = { onNavigate(section.route) },
            startAction = { Icon(icon, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary) },
            endActions = { Icon(MiuixIcons.Basic.ArrowRight, null, tint = MiuixTheme.colorScheme.onSurfaceSecondary) })
        }
      }
    } else {
      if (listOf(stringResource(R.string.settings_log_section), stringResource(R.string.settings_log_summary))
          .any { it.contains(query.trim(), ignoreCase = true) }) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          BasicComponent(title = stringResource(R.string.settings_log_section),
            summary = stringResource(R.string.settings_log_summary), onClick = { onNavigate(SettingsSection.LOG.route) },
            endActions = { Icon(MiuixIcons.Basic.ArrowRight, null) })
        }
      }
      SettingsSectionContent(model, section = null, query = query, onNavigate = onNavigate)
    }
  }
}

@Composable
internal fun SettingsSectionContent(model: HomeViewModel, section: SettingsSection?, query: String = "",
  onNavigate: (String) -> Unit, cacheRefreshKey: Int = 0) {
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val splash by model.splashAppearance.collectAsStateWithLifecycle()
  val themeEffects by model.themeEffects.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var cacheRefresh by rememberSaveable { mutableIntStateOf(0) }
  if (section == SettingsSection.CACHE) CacheOverview(model, cacheRefreshKey + cacheRefresh)
  SettingsContent(preferences, model.preferences::setAppearance, model.preferences::setColorMode,
    model.preferences::setShowIconPreviews, { model.clearPreviewCache(); cacheRefresh++ }, model.preferences::setPageUserScroll,
    model.preferences::setPagerGesture, model.preferences::setAutoApplyIconPack, model::setRememberMenuSelections,
    model.preferences::setTopBarBlur, splash, framework.splashAppearanceInScope(), model::setSplashAppearance,
    themeEffects, framework, model::setPreserveSystemMaterials, query, section,
    onOpenNotices = { onNavigate("notices") }, launcherLabelsContent = { LauncherLabelSettingsCard(model) },
    dockGlassContent = { DockGlassSettingsCard(model) },
    launcherGlassContent = { LauncherGlassSettingsCard(model) },
    staticScopeContent = { StaticScopeCard(model) },
    desktopStatusObserver = {
      LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeDesktopStatus() }
      }
    }) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
      lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeFallbackResources() }
    }
    SystemIconFallbackCard(model)
  }
}
