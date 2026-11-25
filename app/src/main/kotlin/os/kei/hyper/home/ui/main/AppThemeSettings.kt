package os.kei.hyper.home.ui.main

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppAppearance
import os.kei.hyper.home.data.AppColorMode
import os.kei.hyper.home.data.AppDarkStyle
import os.kei.hyper.home.data.AppNavigationBarStyle
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.AppTopBarBlur
import os.kei.hyper.home.ui.components.Os4DropdownPreference
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val appPaletteLabels = listOf(R.string.browser_ui_default, R.string.browser_ui_monet,
  R.string.browser_ui_follow)

private val appThemeSearchLabels = listOf(
  R.string.settings_app_theme_title, R.string.settings_app_theme_summary, R.string.polish_appearance_title,
  R.string.polish_appearance_light, R.string.polish_appearance_dark, R.string.language_system,
  R.string.polish_appearance_summary, R.string.dark_mode_style_title, R.string.dark_mode_style_summary,
  R.string.polish_settings_display, R.string.settings_theme_display,
  R.string.browser_ui_palette, R.string.browser_ui_palette_hint, R.string.top_bar_blur,
  R.string.top_bar_blur_summary, R.string.top_bar_off, R.string.top_bar_gaussian, R.string.top_bar_progressive,
  R.string.app_squircle_title, R.string.app_squircle_summary, R.string.app_scale_title, R.string.app_scale_summary,
  R.string.app_scroll_haptic_title, R.string.app_scroll_haptic_summary, R.string.navigation_bar_style_title,
  R.string.navigation_bar_style_summary, R.string.navigation_bar_style_ios_like,
  R.string.navigation_bar_style_os4_glass, R.string.navigation_bar_style_search_keywords,
) + appPaletteLabels + darkStyleLabels

internal fun appThemeSearchMatches(query: String, resources: Resources): Boolean =
  settingsQueryMatches(query, appThemeSearchLabels.joinToString(" ") { resources.getString(it) } +
    " Monet Squircle Global Scale Scroll End Haptic")

@Composable
internal fun AppThemeSettingsContent(preferences: AppPreferences) {
  val state by preferences.state.collectAsStateWithLifecycle()
  val dark = LocalKeiHomeDarkTheme.current
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Row(Modifier.widthIn(max = 500.dp).fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ThemeAppearancePreview(stringResource(R.string.polish_appearance_light), dark = false,
          selected = !dark, onClick = { preferences.setAppearance(AppAppearance.LIGHT) },
          modifier = Modifier.weight(1f).testTag("settings.theme.light"))
        ThemeAppearancePreview(stringResource(R.string.polish_appearance_dark), dark = true,
          selected = dark, onClick = { preferences.setAppearance(AppAppearance.DARK) },
          modifier = Modifier.weight(1f).testTag("settings.theme.dark"))
      }
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      SwitchPreference(title = stringResource(R.string.language_system),
        checked = state.appearance == AppAppearance.SYSTEM,
        onCheckedChange = { follow ->
          preferences.setAppearance(when {
            follow -> AppAppearance.SYSTEM
            dark -> AppAppearance.DARK
            else -> AppAppearance.LIGHT
          })
        }, modifier = Modifier.testTag("settings.theme.follow-system"))
      Os4DropdownPreference(title = stringResource(R.string.dark_mode_style_title),
        summary = stringResource(R.string.dark_mode_style_summary),
        items = darkStyleLabels.map { stringResource(it) }, selectedIndex = state.darkStyle.ordinal,
        onSelectedIndexChange = { preferences.setDarkStyle(AppDarkStyle.entries[it]) },
        modifier = Modifier.testTag("settings.theme.dark-style"))
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      Os4DropdownPreference(title = stringResource(R.string.browser_ui_palette),
        summary = stringResource(R.string.browser_ui_palette_hint),
        items = appPaletteLabels.map { stringResource(it) }, selectedIndex = state.colorMode.ordinal,
        onSelectedIndexChange = { preferences.setColorMode(AppColorMode.entries[it]) },
        modifier = Modifier.testTag("settings.theme.palette"))
    }
    SmallTitle(stringResource(R.string.settings_theme_display), modifier = Modifier.semantics { heading() })
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      val squircleSupported = isRuntimeShaderSupported()
      SwitchPreference(title = stringResource(R.string.app_squircle_title),
        summary = stringResource(if (squircleSupported) R.string.app_squircle_summary else R.string.app_squircle_unsupported),
        checked = squircleSupported && state.squircleShapes,
        enabled = squircleSupported, onCheckedChange = preferences::setSquircleShapes,
        modifier = Modifier.testTag("settings.squircle-shapes"))
      Os4DropdownPreference(title = stringResource(R.string.top_bar_blur),
        summary = stringResource(R.string.top_bar_blur_summary),
        items = listOf(R.string.top_bar_off, R.string.top_bar_gaussian, R.string.top_bar_progressive)
          .map { stringResource(it) }, selectedIndex = state.topBarBlur.ordinal,
        onSelectedIndexChange = { preferences.setTopBarBlur(AppTopBarBlur.entries[it]) })
      AppScalePreference(state.appScalePercent, preferences::setAppScalePercent)
      SwitchPreference(title = stringResource(R.string.app_scroll_haptic_title),
        summary = stringResource(R.string.app_scroll_haptic_summary),
        checked = state.scrollEndHaptic, onCheckedChange = preferences::setScrollEndHaptic,
        modifier = Modifier.testTag("settings.scroll-end-haptic"))
      Os4DropdownPreference(title = stringResource(R.string.navigation_bar_style_title),
        summary = stringResource(R.string.navigation_bar_style_summary),
        items = listOf(R.string.navigation_bar_style_ios_like, R.string.navigation_bar_style_os4_glass)
          .map { stringResource(it) }, selectedIndex = state.navigationBarStyle.ordinal,
        onSelectedIndexChange = { preferences.setNavigationBarStyle(AppNavigationBarStyle.entries[it]) },
        modifier = Modifier.testTag("settings.navigation-bar-style"))
    }
  }
}

@Composable
private fun ThemeAppearancePreview(title: String, dark: Boolean, selected: Boolean,
  onClick: () -> Unit, modifier: Modifier = Modifier) {
  val scheme = MiuixTheme.colorScheme
  val accent = scheme.primary
  val outline = if (selected) accent else scheme.onSurfaceSecondary.copy(alpha = 0.3f)
  Column(modifier.squircleClip(20.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.fillMaxWidth().squircleBorder(if (selected) 3.dp else 1.dp, outline, 20.dp).padding(5.dp)) {
      Canvas(Modifier.fillMaxWidth().aspectRatio(1.55f).squircleClip(15.dp)) {
        val rounded = CornerRadius(7.dp.toPx())
        val surface = if (dark) Color(0xff1b212b) else Color(0xffecf0f6)
        val card = if (dark) Color(0xff2e3745) else Color.White
        val line = if (dark) Color(0xff8998aa) else Color(0xff9daaba)
        val control = if (dark) Color(0xff8ac1ff) else Color(0xff2878cc)
        drawRect(surface)
        fun block(color: Color, x: Float, y: Float, width: Float, height: Float) {
          drawRoundRect(color, Offset(size.width * x, size.height * y),
            Size(size.width * width, size.height * height), rounded)
        }
        block(line, 0.12f, 0.13f, 0.31f, 0.06f)
        block(card, 0.12f, 0.28f, 0.76f, 0.27f)
        block(card, 0.12f, 0.61f, 0.76f, 0.27f)
        block(line, 0.19f, 0.39f, 0.29f, 0.045f)
        block(line, 0.19f, 0.72f, 0.36f, 0.045f)
        block(control, 0.66f, 0.36f, 0.15f, 0.12f)
      }
    }
    Text(title, color = if (selected) accent else scheme.onSurface, style = MiuixTheme.textStyles.body2)
  }
}
