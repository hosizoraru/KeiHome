package os.kei.hyper.home.ui.main

import android.app.LocaleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppAppearance
import os.kei.hyper.home.data.AppPreferencesState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsContent(
  preferences: AppPreferencesState,
  onAppearanceChange: (AppAppearance) -> Unit,
  onColorModeChange: (os.kei.hyper.home.data.AppColorMode) -> Unit,
  onPreviewsChange: (Boolean) -> Unit,
  onClearIconCache: () -> Unit,
  onPageUserScrollChange: (Boolean) -> Unit,
  onPagerGestureChange: (os.kei.hyper.home.data.AppPagerGesture) -> Unit,
  onAutoApplyChange: (Boolean) -> Unit,
  onRememberMenusChange: (Boolean) -> Unit,
  onTopBarBlurChange: (os.kei.hyper.home.data.AppTopBarBlur) -> Unit,
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
  desktopStatusObserver: @Composable () -> Unit = {},
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
  val colorMode = matches(SettingsSection.APP, R.string.polish_settings_display, R.string.browser_ui_palette,
    R.string.browser_ui_palette_hint, R.string.polish_appearance_title, extra = "Monet")
  val appearance = preferences.colorMode == os.kei.hyper.home.data.AppColorMode.FOLLOW_ICONS &&
    matches(SettingsSection.APP, R.string.polish_settings_display, R.string.polish_appearance_title, R.string.polish_appearance_summary)
  val language = matches(SettingsSection.APP, R.string.polish_settings_display, R.string.settings_language,
    R.string.language_system, R.string.language_chinese, R.string.language_english, R.string.language_japanese)
  val blur = matches(SettingsSection.APP, R.string.polish_settings_display, R.string.top_bar_blur, R.string.top_bar_blur_summary,
    R.string.top_bar_gaussian, R.string.top_bar_progressive)
  val migratedSearch = section == null && query.isNotBlank()
  val fallback = migratedSearch && matches(SettingsSection.HOOK, R.string.settings_hook_section,
    R.string.system_fallback_title, R.string.system_fallback_summary, R.string.system_fallback_default,
    R.string.system_fallback_classic, R.string.system_fallback_boundless,
    R.string.coloros_other_packs_title, R.string.coloros_other_packs_summary, extra = "ColorOS ART+")
  val effects = matches(SettingsSection.HOOK, R.string.settings_system_appearance, R.string.theme_effects_title, R.string.theme_effects_summary)
  val launcherGlass = migratedSearch && matches(SettingsSection.HOOK, R.string.navigation_desktop, R.string.settings_system_appearance,
    R.string.launcher_glass_title, R.string.launcher_glass_summary,
    R.string.launcher_glass_system, R.string.launcher_glass_light, R.string.launcher_glass_dark)
  val labels = migratedSearch && matches(SettingsSection.HOOK, R.string.navigation_desktop, R.string.launcher_labels_section, R.string.launcher_labels_title,
    R.string.launcher_labels_summary, R.string.launcher_labels_native, R.string.launcher_labels_marquee,
    R.string.launcher_labels_two_lines)
  val dockGlass = migratedSearch && matches(SettingsSection.HOOK, R.string.navigation_desktop, R.string.launcher_labels_section,
    R.string.dock_glass_title, R.string.dock_glass_summary, R.string.dock_glass_corner_title,
    R.string.dock_glass_corner_summary, R.string.dock_glass_corner_recents, R.string.dock_glass_corner_rounded)
  val splashVisible = matches(SettingsSection.HOOK, R.string.splash_section, R.string.splash_adapt, R.string.splash_adapt_summary)
  val autoApply = matches(SettingsSection.APP, R.string.pack_action_settings, R.string.pack_auto_apply, R.string.pack_auto_apply_summary)
  val rememberMenus = matches(SettingsSection.APP, R.string.pack_action_settings, R.string.pack_remember_menus, R.string.pack_remember_menus_summary)
  val pageScroll = matches(SettingsSection.APP, R.string.browser_pager_title, R.string.browser_page_scroll)
  val pagerMode = matches(SettingsSection.APP, R.string.browser_pager_title, R.string.browser_pager_mode, R.string.browser_pager_hint,
    R.string.browser_pager_native, R.string.browser_pager_cross, R.string.browser_pager_ios)
  val previews = matches(SettingsSection.CACHE, R.string.polish_settings_previews, R.string.polish_previews_title, R.string.polish_previews_summary)
  val clear = matches(SettingsSection.CACHE, R.string.polish_settings_previews, R.string.polish_clear_previews, R.string.polish_clear_previews_summary)
  val about = matches(SettingsSection.ABOUT, R.string.polish_settings_about, R.string.app_name, R.string.polish_about_version,
    extra = "${BuildConfig.VERSION_NAME} ${BuildConfig.VERSION_CODE} commit")
  val aboutPackage = matches(SettingsSection.ABOUT, R.string.polish_settings_about, R.string.polish_about_package, extra = BuildConfig.APPLICATION_ID)
  val aboutApi = matches(SettingsSection.ABOUT, R.string.polish_settings_about, R.string.polish_about_api, extra = "API Android")
  val source = matches(SettingsSection.ABOUT, R.string.polish_settings_about, R.string.settings_source, extra = "GitHub")
  val notices = matches(SettingsSection.ABOUT, R.string.polish_settings_about, R.string.settings_notices, extra = "license")
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (listOf(colorMode, appearance, language, blur, fallback, effects, launcherGlass, labels, dockGlass, splashVisible, autoApply, rememberMenus,
        pageScroll, pagerMode, previews, clear, about, aboutPackage, aboutApi, source, notices).none { it }) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Text(stringResource(R.string.settings_search_empty), style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
    if (fallback) {
      SmallTitle(stringResource(R.string.settings_icon_fallback_section), modifier = Modifier.semantics { heading() })
      fallbackContent()
    }
    if (colorMode || appearance || language || blur) {
      SmallTitle(
        stringResource(R.string.polish_settings_display),
        modifier = Modifier.semantics { heading() },
      )
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        val labels = listOf(R.string.language_system, R.string.polish_appearance_light, R.string.polish_appearance_dark,
          R.string.browser_ui_monet_system, R.string.browser_ui_monet_light, R.string.browser_ui_monet_dark,
          R.string.browser_ui_follow)
        val selected = when (preferences.colorMode) {
          os.kei.hyper.home.data.AppColorMode.DEFAULT -> preferences.appearance.ordinal
          os.kei.hyper.home.data.AppColorMode.MONET -> preferences.appearance.ordinal + 3
          os.kei.hyper.home.data.AppColorMode.FOLLOW_ICONS -> 6
        }
        if (colorMode) OverlaySpinnerPreference(title = stringResource(R.string.browser_ui_palette),
          summary = stringResource(R.string.browser_ui_palette_hint),
          items = labels.map { DropdownItem(text = stringResource(it)) }, selectedIndex = selected,
          onSelectedIndexChange = { index ->
            if (index == 6) onColorModeChange(os.kei.hyper.home.data.AppColorMode.FOLLOW_ICONS)
            else {
              onAppearanceChange(AppAppearance.entries[index % 3])
              onColorModeChange(if (index < 3) os.kei.hyper.home.data.AppColorMode.DEFAULT else os.kei.hyper.home.data.AppColorMode.MONET)
            }
          })
        if (appearance)
          AppearancePreference(preferences.appearance, onAppearanceChange)
        if (language) LanguagePreference()
        if (blur) OverlaySpinnerPreference(title = stringResource(R.string.top_bar_blur),
          summary = stringResource(R.string.top_bar_blur_summary),
          items = listOf(R.string.top_bar_gaussian, R.string.top_bar_progressive)
            .map { DropdownItem(text = stringResource(it)) }, selectedIndex = preferences.topBarBlur.ordinal,
          onSelectedIndexChange = { onTopBarBlurChange(os.kei.hyper.home.data.AppTopBarBlur.entries[it]) })
      }
    }
    if (effects || launcherGlass) {
      SmallTitle(stringResource(R.string.settings_system_appearance), modifier = Modifier.semantics { heading() })
      if (effects) ThemeEffectsCard(themeEffects, framework, onThemeEffectsChange)
    if (launcherGlass || labels || dockGlass) desktopStatusObserver()
    if (launcherGlass) launcherGlassContent()
    }
    if (labels || dockGlass) {
      SmallTitle(stringResource(R.string.launcher_labels_section), modifier = Modifier.semantics { heading() })
      if (labels) launcherLabelsContent()
      if (dockGlass) dockGlassContent()
    }
    if (splashVisible) {
      SmallTitle(stringResource(R.string.splash_section))
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        val issue = when {
          !splashInScope -> R.string.splash_scope
          splash.unavailable -> R.string.theme_effects_unavailable
          splash.saving -> R.string.theme_effects_saving
          splash.saveFailed -> R.string.theme_effects_save_failed
          splash.readFailed -> R.string.theme_effects_read_failed
          splash.loading || splash.savedChoice == null -> R.string.theme_effects_loading
          else -> null
        }
        SwitchPreference(title = stringResource(R.string.splash_adapt),
          summary = stringResource(issue ?: R.string.splash_adapt_summary),
          checked = splash.savedChoice == true,
          enabled = splashInScope && splash.canSaveThemeEffects(splash.savedChoice != true),
          onCheckedChange = onSplashChange)
        if (splashInScope && (splash.readFailed || splash.saveFailed)) {
          BasicComponent(title = stringResource(R.string.theme_effects_resave_off),
            enabled = splash.canSaveThemeEffects(false), onClick = { onSplashChange(false) })
        }
      }
    }
    if (autoApply || rememberMenus) {
      SmallTitle(stringResource(R.string.pack_action_settings))
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        if (autoApply) SwitchPreference(title = stringResource(R.string.pack_auto_apply),
          summary = stringResource(R.string.pack_auto_apply_summary), checked = preferences.autoApplyIconPack,
          onCheckedChange = onAutoApplyChange)
        if (rememberMenus) SwitchPreference(title = stringResource(R.string.pack_remember_menus),
          summary = stringResource(R.string.pack_remember_menus_summary), checked = preferences.rememberMenuSelections,
          onCheckedChange = onRememberMenusChange)
      }
    }
    if (pageScroll || pagerMode) {
      SmallTitle(stringResource(R.string.browser_pager_title))
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        if (pageScroll) SwitchPreference(title = stringResource(R.string.browser_page_scroll),
          checked = preferences.pageUserScroll, onCheckedChange = onPageUserScrollChange)
        if (pagerMode) OverlaySpinnerPreference(title = stringResource(R.string.browser_pager_mode),
          summary = stringResource(R.string.browser_pager_hint),
          items = listOf(R.string.browser_pager_native, R.string.browser_pager_cross, R.string.browser_pager_ios)
            .map { DropdownItem(text = stringResource(it)) }, selectedIndex = preferences.pagerGesture.ordinal,
          enabled = preferences.pageUserScroll,
          onSelectedIndexChange = { onPagerGestureChange(os.kei.hyper.home.data.AppPagerGesture.entries[it]) })
      }
    }
    if (previews || clear) {
      SmallTitle(
        stringResource(R.string.polish_settings_previews),
        modifier = Modifier.semantics { heading() },
      )
      PreviewPreferences(preferences.showIconPreviews, onPreviewsChange, onClearIconCache, previews, clear)
    }
    if (about || aboutPackage || aboutApi) {
      if (section != SettingsSection.ABOUT) SmallTitle(
        stringResource(R.string.polish_settings_about),
        modifier = Modifier.semantics { heading() },
      )
      AboutCard(about && section != SettingsSection.ABOUT, aboutPackage, aboutApi)
    }
    if (source || notices) AboutLinks(source, notices, onOpenNotices)
  }
}

private val settingsWhitespace = Regex("\\s+")

internal fun settingsQueryMatches(query: String, text: String): Boolean =
  query.trim().split(settingsWhitespace).filter { it.isNotEmpty() }.all { text.contains(it, ignoreCase = true) }

@Composable
private fun AppearancePreference(appearance: AppAppearance, onChange: (AppAppearance) -> Unit) {
  val system = stringResource(R.string.language_system)
  val light = stringResource(R.string.polish_appearance_light)
  val dark = stringResource(R.string.polish_appearance_dark)
  val items = remember(system, light, dark) {
    listOf(DropdownItem(text = system), DropdownItem(text = light), DropdownItem(text = dark))
  }
  OverlaySpinnerPreference(
    title = stringResource(R.string.polish_appearance_title),
    summary = stringResource(R.string.polish_appearance_summary),
    items = items,
    selectedIndex = appearance.ordinal,
    onSelectedIndexChange = { onChange(AppAppearance.entries[it]) },
  )
}

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
    listOf(system, chinese, english, japanese).map { DropdownItem(text = it) }
  }
  OverlaySpinnerPreference(
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
private fun PreviewPreferences(
  showPreviews: Boolean,
  onPreviewsChange: (Boolean) -> Unit,
  onClearIconCache: () -> Unit,
  showToggle: Boolean,
  showClear: Boolean,
) {
  val context = LocalContext.current
  val clearedMessage = stringResource(R.string.polish_previews_cleared)
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    if (showToggle) SwitchPreference(
      title = stringResource(R.string.polish_previews_title),
      summary = stringResource(R.string.polish_previews_summary),
      checked = showPreviews,
      onCheckedChange = onPreviewsChange,
    )
    if (showClear) BasicComponent(
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
  val versionInfo = "$appName $version\n${BuildConfig.APPLICATION_ID}\n$platform"
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    if (showVersion) BasicComponent(
      title = appName,
      summary = version,
      onClickLabel = stringResource(R.string.polish_copy_version),
      onClick = {
        context.getSystemService(ClipboardManager::class.java)
          .setPrimaryClip(ClipData.newPlainText(appName, versionInfo))
      },
      endActions = {
        Icon(
          imageVector = MiuixIcons.Copy,
          contentDescription = null,
          modifier = Modifier.size(22.dp),
          tint = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
      },
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
