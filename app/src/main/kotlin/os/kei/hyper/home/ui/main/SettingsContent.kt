package os.kei.hyper.home.ui.main

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppPreferencesState
import os.kei.hyper.home.ui.components.Os4DropdownPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsContent(
  preferences: AppPreferencesState,
  onPreviewsChange: (Boolean) -> Unit,
  onClearIconCache: () -> Unit,
  onPageUserScrollChange: (Boolean) -> Unit,
  onPagerGestureChange: (os.kei.hyper.home.data.AppPagerGesture) -> Unit,
  onAutoApplyChange: (Boolean) -> Unit,
  onInstalledIconsOnlyChange: (Boolean) -> Unit,
  onRememberMenusChange: (Boolean) -> Unit,
  splash: ThemeEffectsUiState,
  splashInScope: Boolean,
  onSplashChange: (Boolean) -> Unit,
  themeEffects: ThemeEffectsUiState,
  framework: FrameworkUiState,
  onThemeEffectsChange: (Boolean) -> Unit,
  query: String,
  section: SettingsSection?,
  onOpenNotices: () -> Unit,
  launcherLabelsContent: @Composable () -> Unit,
  dockGlassContent: @Composable () -> Unit,
  launcherGlassContent: @Composable () -> Unit = {},
  splashIconsContent: @Composable () -> Unit = {},
  wallpaperDimmingContent: @Composable () -> Unit = {},
  searchGlassContent: @Composable () -> Unit = {},
  desktopStatusObserver: @Composable () -> Unit = {},
  staticScopeContent: @Composable () -> Unit = {},
  onKeepLatestGeneratedPacksChange: (Boolean) -> Unit = {},
  onManualThemeFallbackChange: ((Boolean) -> Unit)? = null,
  onOpenAppTheme: (() -> Unit)? = null,
  onOpenRecommendedMappings: (() -> Unit)? = null,
  fallbackContent: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val resources = remember(context, configuration) { context.resources }
  fun matches(group: SettingsSection, title: Int, vararg labels: Int, extra: String = ""): Boolean {
    if (section != null && section != group) return false
    if (query.isBlank()) return true
    val text = (listOf(title, group.title) + labels.asList()).joinToString(" ") { resources.getString(it) } + " " + extra
    return settingsQueryMatches(query, text)
  }
  val appTheme = section == null && query.isNotBlank() && onOpenAppTheme != null &&
    appThemeSearchMatches(query, resources)
  val language = matches(SettingsSection.APP, R.string.settings_language,
    R.string.language_system, R.string.language_chinese, R.string.language_english, R.string.language_japanese)
  val hideIcon = matches(SettingsSection.APP, R.string.app_entry_settings,
    R.string.hide_launcher_icon, R.string.hide_launcher_icon_summary, extra = "launcher LSPosed")
  val migratedSearch = section == null && query.isNotBlank()
  val wallpaperDimming = migratedSearch && matches(SettingsSection.HOOK, R.string.wallpaper_dimming_title,
    R.string.wallpaper_dimming_summary, R.string.desktop_display_title)
  val recommendedMappings = migratedSearch && onOpenRecommendedMappings != null &&
    matches(SettingsSection.PACKS, R.string.recommended_mappings_title,
      R.string.recommended_mappings_entry, R.string.recommended_mappings_search_keywords,
      R.string.system_name_matching_title, R.string.system_name_matching_summary,
      R.string.recommended_mappings_telegram_title, R.string.recommended_mappings_telegram_summary,
      R.string.recommended_mappings_phone_companions_title, R.string.recommended_mappings_phone_companions_summary,
      R.string.recommended_mappings_weibo_lite_title, R.string.recommended_mappings_weibo_lite_summary,
      R.string.recommended_mappings_coloros_title, R.string.recommended_mappings_coloros_summary)
  val fallback = migratedSearch && matches(SettingsSection.HOOK, R.string.settings_hook_section,
    R.string.system_fallback_title, R.string.system_fallback_summary, R.string.system_fallback_default,
    R.string.system_fallback_classic, R.string.system_fallback_boundless,
    R.string.coloros_other_packs_title, R.string.coloros_other_packs_summary, extra = "ColorOS ART+")
  val effects = migratedSearch && matches(SettingsSection.HOOK, R.string.settings_system_appearance, R.string.theme_effects_title, R.string.theme_effects_summary)
  val staticScope = matches(SettingsSection.HOOK, R.string.scope_title, R.string.scope_search,
    R.string.scope_surfaces_note,
    extra = "scope libxposed")
  val launcherGlass = migratedSearch && matches(SettingsSection.HOOK, R.string.navigation_desktop, R.string.settings_system_appearance,
    R.string.launcher_glass_title, R.string.launcher_glass_summary,
    R.string.launcher_glass_system, R.string.launcher_glass_light, R.string.launcher_glass_dark)
  val searchGlass = migratedSearch && matches(SettingsSection.HOOK,
    R.string.search_glass_title, R.string.search_glass_summary, R.string.search_glass_follow_dock)
  val labels = migratedSearch && matches(SettingsSection.HOOK, R.string.navigation_desktop, R.string.launcher_labels_section, R.string.launcher_labels_title,
    R.string.launcher_labels_summary, R.string.launcher_labels_native, R.string.launcher_labels_marquee,
    R.string.launcher_labels_two_lines)
  val dockGlass = migratedSearch && matches(SettingsSection.HOOK, R.string.navigation_desktop, R.string.launcher_labels_section,
    R.string.dock_glass_title, R.string.dock_glass_summary, R.string.dock_glass_corner_title,
    R.string.dock_glass_corner_summary, R.string.dock_glass_corner_recents, R.string.dock_glass_corner_rounded,
    R.string.dock_glass_corner_pad, R.string.dock_glass_corner_concentric, R.string.dock_glass_corner_custom,
    R.string.dock_glass_padding_title, R.string.dock_glass_padding_summary,
    R.string.dock_glass_horizontal_padding_title, R.string.dock_glass_horizontal_padding_summary,
    R.string.dock_glass_offset_title, R.string.dock_glass_offset_summary,
    R.string.dock_glass_radius_title, R.string.dock_glass_radius_summary,
    R.string.dock_glass_reset, R.string.dock_glass_reset_summary)
  val splashIconsVisible = migratedSearch && matches(SettingsSection.HOOK, R.string.splash_section, R.string.splash_icons_title, R.string.splash_icons_summary, R.string.splash_icons_help)
  val splashVisible = migratedSearch && matches(SettingsSection.HOOK, R.string.splash_section, R.string.splash_adapt, R.string.splash_adapt_summary)
  val autoApply = matches(SettingsSection.PACKS, R.string.pack_action_settings, R.string.pack_auto_apply, R.string.pack_auto_apply_summary)
  val manualThemeFallback = onManualThemeFallbackChange != null &&
    matches(SettingsSection.PACKS, R.string.pack_action_settings,
      R.string.pack_manual_theme_fallback, R.string.pack_manual_theme_fallback_summary)
  val installedIconsOnly = matches(SettingsSection.PACKS, R.string.pack_action_settings,
    R.string.pack_installed_icons_only, R.string.pack_installed_icons_only_summary)
  val rememberMenus = matches(SettingsSection.PACKS, R.string.pack_action_settings, R.string.pack_remember_menus, R.string.pack_remember_menus_summary)
  val keepLatestGeneratedPacks = matches(SettingsSection.PACKS, R.string.pack_action_settings,
    R.string.pack_settings_experience, R.string.generated_pack_retention_title,
    R.string.generated_pack_retention_summary)
  val pageScroll = matches(SettingsSection.APP, R.string.app_interaction_title,
    R.string.browser_pager_title, R.string.browser_page_scroll)
  val pagerMode = matches(SettingsSection.APP, R.string.app_interaction_title,
    R.string.browser_pager_title, R.string.browser_pager_mode, R.string.browser_pager_hint,
    R.string.browser_pager_native, R.string.browser_pager_cross)
  val previews = matches(SettingsSection.PACKS, R.string.pack_action_settings, R.string.pack_settings_experience,
    R.string.polish_previews_title, R.string.polish_previews_summary)
  val clear = matches(SettingsSection.CACHE, R.string.polish_settings_previews, R.string.polish_clear_previews, R.string.polish_clear_previews_summary)
  val about = matches(SettingsSection.ABOUT, R.string.polish_settings_about, R.string.app_name, R.string.polish_about_version,
    extra = "${BuildConfig.VERSION_NAME} ${BuildConfig.VERSION_CODE} commit")
  val aboutPackage = matches(SettingsSection.ABOUT, R.string.polish_settings_about, R.string.polish_about_package, extra = BuildConfig.APPLICATION_ID)
  val aboutApi = matches(SettingsSection.ABOUT, R.string.polish_settings_about, R.string.polish_about_api, extra = "API Android")
  val source = matches(SettingsSection.ABOUT, R.string.polish_settings_about, R.string.settings_source, extra = "GitHub")
  val notices = matches(SettingsSection.ABOUT, R.string.polish_settings_about, R.string.settings_notices, extra = "license")
  val channel = matches(SettingsSection.ABOUT, R.string.polish_settings_about,
    R.string.update_channel, R.string.update_channel_summary, extra = "Telegram TG @${UpdateChannel.USERNAME}")
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (listOf(appTheme, language, fallback, recommendedMappings, effects, staticScope, launcherGlass, labels, dockGlass, splashVisible, splashIconsVisible, wallpaperDimming, autoApply, manualThemeFallback, installedIconsOnly, rememberMenus, keepLatestGeneratedPacks,
        pageScroll, pagerMode, previews, clear, about, aboutPackage, aboutApi, source, notices, hideIcon, channel).none { it }) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Text(stringResource(R.string.settings_search_empty), style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
    if (staticScope) staticScopeContent()
    if (hideIcon) {
      SmallTitle(stringResource(R.string.app_entry_settings), modifier = Modifier.semantics { heading() })
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) { LauncherIconSetting() }
    }
    if (fallback) {
      SmallTitle(stringResource(R.string.settings_icon_fallback_section), modifier = Modifier.semantics { heading() })
      fallbackContent()
    }
    if (recommendedMappings) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        BasicComponent(title = stringResource(R.string.recommended_mappings_title),
          summary = stringResource(R.string.recommended_mappings_entry),
          modifier = Modifier.testTag("settings.entry.recommended-mappings"),
          endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) },
          onClick = onOpenRecommendedMappings)
      }
    }
    if (appTheme) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        BasicComponent(title = stringResource(SettingsSection.THEME.title),
          summary = stringResource(SettingsSection.THEME.summary), onClick = onOpenAppTheme,
          modifier = Modifier.testTag("settings.entry.theme"),
          endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) })
      }
    }
    if (language) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) { LanguagePreference() }
    }
    if (launcherGlass || labels || dockGlass || wallpaperDimming) desktopStatusObserver()
    if (effects || launcherGlass) {
      SmallTitle(stringResource(R.string.settings_system_appearance), modifier = Modifier.semantics { heading() })
      if (effects) ThemeEffectsCard(themeEffects, framework, onThemeEffectsChange)
      if (launcherGlass) launcherGlassContent()
    }
    if (searchGlass) searchGlassContent()
    if (labels || dockGlass) {
      SmallTitle(stringResource(R.string.launcher_labels_section), modifier = Modifier.semantics { heading() })
      if (labels) launcherLabelsContent()
      if (dockGlass) dockGlassContent()
    }
    if (splashIconsVisible) splashIconsContent()
    if (wallpaperDimming) wallpaperDimmingContent()
    if (splashVisible) SplashAppearanceSettingsCard(splash, splashInScope, onSplashChange,
      scopeChecking = framework.desktopScopeChecking(), scopeReadFailed = framework.readFailed)
    if (autoApply || manualThemeFallback || installedIconsOnly || rememberMenus || keepLatestGeneratedPacks || previews) {
      PackSettingsContent(preferences, onAutoApplyChange, onInstalledIconsOnlyChange,
        onRememberMenusChange, onPreviewsChange,
        showAutoApply = autoApply, showInstalledOnly = installedIconsOnly,
        showRememberMenus = rememberMenus, showPreviews = previews,
        onManualThemeFallback = onManualThemeFallbackChange.takeIf { manualThemeFallback },
        onKeepLatestGeneratedPacks = onKeepLatestGeneratedPacksChange.takeIf { keepLatestGeneratedPacks })
    }
    if (pageScroll || pagerMode) {
      SmallTitle(stringResource(R.string.app_interaction_title), modifier = Modifier.semantics { heading() })
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        if (pageScroll) SwitchPreference(title = stringResource(R.string.browser_page_scroll),
          checked = preferences.pageUserScroll, onCheckedChange = onPageUserScrollChange)
        if (pagerMode) Os4DropdownPreference(title = stringResource(R.string.browser_pager_mode),
          summary = stringResource(R.string.browser_pager_hint),
          items = listOf(R.string.browser_pager_native, R.string.browser_pager_cross)
            .map { stringResource(it) }, selectedIndex = preferences.pagerGesture.ordinal,
          enabled = preferences.pageUserScroll,
          onSelectedIndexChange = { onPagerGestureChange(os.kei.hyper.home.data.AppPagerGesture.entries[it]) })
      }
    }
    if (clear && section != SettingsSection.CACHE) {
      SmallTitle(
        stringResource(R.string.polish_settings_previews),
        modifier = Modifier.semantics { heading() },
      )
      PreviewCachePreference(onClearIconCache)
    }
    if (about || aboutPackage || aboutApi) {
      if (section != SettingsSection.ABOUT) SmallTitle(
        stringResource(R.string.polish_settings_about),
        modifier = Modifier.semantics { heading() },
      )
      AboutCard(about && section != SettingsSection.ABOUT, aboutPackage, aboutApi)
    }
    if (source || notices || channel) AboutLinks(source, notices, onOpenNotices, showChannel = channel)
  }
}

private val settingsWhitespace = Regex("\\s+")

internal fun settingsQueryMatches(query: String, text: String): Boolean =
  query.trim().split(settingsWhitespace).filter { it.isNotEmpty() }.all { text.contains(it, ignoreCase = true) }

@Composable
private fun LanguagePreference() {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val localeManager = remember(context) { context.getSystemService(LocaleManager::class.java) }
  var language by remember(localeManager, configuration) {
    mutableStateOf(AppLanguage.fromTag(localeManager.applicationLocales.toLanguageTags()))
  }
  val system = stringResource(R.string.language_system)
  val chinese = stringResource(R.string.language_chinese)
  val english = stringResource(R.string.language_english)
  val japanese = stringResource(R.string.language_japanese)
  val items = remember(system, chinese, english, japanese) {
    listOf(system, chinese, english, japanese)
  }
  Os4DropdownPreference(
    title = stringResource(R.string.settings_language),
    items = items,
    selectedIndex = AppLanguage.entries.indexOf(language),
    onSelectedIndexChange = { index ->
      val selected = AppLanguage.entries[index]
      language = selected
      localeManager.applicationLocales = LocaleList.forLanguageTags(selected.tag)
    },
  )
}

@Composable
private fun PreviewCachePreference(
  onClearIconCache: () -> Unit,
) {
  val context = LocalContext.current
  val clearedMessage = stringResource(R.string.polish_previews_cleared)
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    BasicComponent(
      title = stringResource(R.string.polish_clear_previews),
      summary = stringResource(R.string.polish_clear_previews_summary),
      onClick = {
        onClearIconCache()
        android.widget.Toast.makeText(context, clearedMessage, android.widget.Toast.LENGTH_SHORT).show()
      },
      onClickLabel = stringResource(R.string.polish_clear_previews),
    )
  }
}

@Composable
private fun AboutCard(showVersion: Boolean, showPackage: Boolean, showApi: Boolean) {
  val context = LocalContext.current
  val appName = stringResource(R.string.app_name)
  val version = stringResource(
    R.string.polish_about_version,
    BuildConfig.VERSION_NAME,
    BuildConfig.VERSION_CODE,
  )
  val platform = stringResource(
    R.string.polish_about_api_value,
    context.applicationInfo.minSdkVersion,
    context.applicationInfo.targetSdkVersion,
  )
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    if (showVersion) BasicComponent(
      modifier = Modifier.testTag("settings.about-version"),
      title = appName,
      summary = version,
    )
    if (showPackage) BasicComponent(
      title = stringResource(R.string.polish_about_package),
      summary = BuildConfig.APPLICATION_ID,
    )
    if (showApi) BasicComponent(
      title = stringResource(R.string.polish_about_api),
      summary = platform,
    )
  }
}
