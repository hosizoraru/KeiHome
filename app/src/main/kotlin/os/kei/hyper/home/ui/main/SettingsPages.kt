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
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.AppData
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.Link
import top.yukonga.miuix.kmp.icon.glass.Log
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class SettingsSection(val route: String, @param:StringRes val title: Int, @param:StringRes val summary: Int) {
  APP("app", R.string.settings_app_section, R.string.settings_app_summary),
  THEME("theme", R.string.settings_app_theme_title, R.string.settings_app_theme_summary),
  PACKS("pack-actions", R.string.pack_action_settings, R.string.pack_action_settings_summary),
  HOOK("hook", R.string.settings_hook_section, R.string.settings_hook_summary),
  CACHE("cache", R.string.settings_cache_section, R.string.settings_cache_summary),
  LOG("log", R.string.settings_log_section, R.string.settings_log_summary),
  RELEASE_NOTES("release-notes", R.string.release_notes_title, R.string.release_notes_summary),
  ABOUT("about", R.string.polish_settings_about, R.string.settings_about_summary),
}

@Composable
internal fun SettingsRootPage(model: HomeViewModel, onNavigate: (String) -> Unit,
  onOpenPerfectIcons: ((String) -> Unit)? = null) {
  var query by rememberSaveable { mutableStateOf("") }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Os4SearchField(query = query, onQueryChange = { query = it },
      modifier = Modifier.fillMaxWidth(), inputModifier = Modifier.testTag("settings.search"),
      label = stringResource(R.string.settings_search))
    if (query.isBlank()) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        SettingsSection.entries.filter { it != SettingsSection.PACKS }.forEach { section ->
          val icon = when (section) {
            SettingsSection.APP -> actionIcon(PageActionIcon.SETTINGS)
            SettingsSection.THEME -> actionIcon(PageActionIcon.MONET)
            SettingsSection.PACKS -> actionIcon(PageActionIcon.PACKS)
            SettingsSection.HOOK -> MiuixIcons.Glass.Link
            SettingsSection.CACHE -> MiuixIcons.Glass.AppData
            SettingsSection.LOG -> MiuixIcons.Glass.Log
            SettingsSection.RELEASE_NOTES -> actionIcon(PageActionIcon.HISTORY)
            SettingsSection.ABOUT -> MiuixIcons.Glass.Info
          }
          BasicComponent(title = stringResource(section.title), summary = stringResource(section.summary),
            onClick = { onNavigate(section.route) }, modifier = Modifier.testTag("settings.entry.${section.route}"),
            startAction = { Icon(icon, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary) },
            endActions = { Icon(MiuixIcons.Glass.ChevronForward, null, tint = MiuixTheme.colorScheme.onSurfaceSecondary) })
        }
      }
    } else {
      val releaseNotes = settingsQueryMatches(query, listOf(
        stringResource(R.string.release_notes_title), stringResource(R.string.release_notes_summary),
        stringResource(R.string.release_notes_search_keywords), stringResource(R.string.setup_title),
        stringResource(R.string.setup_replay_summary), stringResource(R.string.setup_search_keywords),
      ).joinToString(" "))
      val logs = settingsQueryMatches(query, listOf(stringResource(R.string.settings_log_section),
        stringResource(R.string.settings_log_summary)).joinToString(" "))
      if (releaseNotes) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          BasicComponent(title = stringResource(R.string.release_notes_title),
            summary = stringResource(R.string.release_notes_summary),
            modifier = Modifier.testTag("settings.entry.release-notes"),
            onClick = { onNavigate(SettingsSection.RELEASE_NOTES.route) },
            endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) })
        }
      }
      if (logs) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          BasicComponent(title = stringResource(R.string.settings_log_section),
            summary = stringResource(R.string.settings_log_summary), onClick = { onNavigate(SettingsSection.LOG.route) },
            endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) })
        }
      }
      SettingsSectionContent(model, section = null, query = query, onNavigate = onNavigate,
        onOpenPerfectIcons = onOpenPerfectIcons, showEmptySearch = !releaseNotes && !logs)
    }
  }
}

@Composable
internal fun SettingsSectionContent(model: HomeViewModel, section: SettingsSection?, query: String = "",
  onNavigate: (String) -> Unit, cacheRefreshKey: Int = 0, onOpenPerfectIcons: ((String) -> Unit)? = null,
  showEmptySearch: Boolean = true) {
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val splash by model.splashAppearance.collectAsStateWithLifecycle()
  val splashIcons by model.splashIcons.state.collectAsStateWithLifecycle()
  val themeEffects by model.themeEffects.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var cacheRefresh by rememberSaveable { mutableIntStateOf(0) }
  if (section == SettingsSection.CACHE) CacheOverview(model, cacheRefreshKey + cacheRefresh,
    onOpenPerfectIcons ?: {}, onOpenLogs = { onNavigate(SettingsSection.LOG.route) })
  SettingsContent(preferences, model.preferences::setShowIconPreviews, { model.clearPreviewCache(); cacheRefresh++ }, model.preferences::setPageUserScroll,
    model.preferences::setPagerGesture, model.preferences::setAutoApplyIconPack,
    model.preferences::setInstalledIconsOnly, model::setRememberMenuSelections,
    splash, framework.splashAppearanceInScope(), model::setSplashAppearance,
    themeEffects, framework, model::setPreserveSystemMaterials, query, section,
    onOpenNotices = { onNavigate("notices") }, showEmptySearch = showEmptySearch,
    launcherLabelsContent = { LauncherLabelSettingsCard(model) },
    dockGlassContent = { DockGlassSettingsCard(model) },
    launcherGlassContent = { LauncherGlassSettingsCard(model) },
    searchGlassContent = { SearchGlassSettingsCard(model) },
    wallpaperDimmingContent = { WallpaperDimmingSettingsCard(model) },
    wallpaperPresetsContent = { WallpaperPresetSettingsCard(model) },
    splashIconsContent = { SplashIconSettingsCard(splashIcons, framework.splashAppearanceInScope(),
      model.splashIcons::save, scopeChecking = framework.desktopScopeChecking(), scopeReadFailed = framework.readFailed) },
    staticScopeContent = {
      Card(Modifier.fillMaxWidth()) {
        BasicComponent(title = stringResource(R.string.settings_hook_section),
          summary = stringResource(R.string.scope_page_entry_summary),
          endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) },
          onClick = { onNavigate(SettingsSection.HOOK.route) })
      }
    },
    onKeepLatestGeneratedPacksChange = model.preferences::setKeepLatestGeneratedPacks,
    onManualThemeFallbackChange = model.preferences::setManualThemeFallback,
    onOpenAppTheme = { onNavigate(SettingsSection.THEME.route) },
    onOpenRecommendedMappings = onOpenPerfectIcons?.let { open -> { open("recommended-mappings") } },
    desktopStatusObserver = {
      LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeDesktopStatus() }
      }
    }) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
      lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeFallbackResources() }
    }
    onOpenPerfectIcons?.let { IconFallbackSettingsEntry(it) }
  }
}
