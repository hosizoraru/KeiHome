package os.kei.hyper.home.ui.main

import android.content.res.Resources
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

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
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      val wide = maxWidth >= 520.dp
      Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemeAppearancePreview(stringResource(R.string.polish_appearance_light), dark = false,
          wide = wide, darkStyle = state.darkStyle,
          selected = !dark, onClick = { preferences.setAppearance(AppAppearance.LIGHT) },
          modifier = Modifier.weight(1f).testTag("settings.theme.light"))
        ThemeAppearancePreview(stringResource(R.string.polish_appearance_dark), dark = true,
          wide = wide, darkStyle = state.darkStyle,
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
    SmallTitle(stringResource(R.string.settings_theme_display),
      insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
      modifier = Modifier.semantics { heading() })
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
private fun ThemeAppearancePreview(title: String, dark: Boolean, wide: Boolean, darkStyle: AppDarkStyle,
  selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val scheme = MiuixTheme.colorScheme
  val accent = scheme.primary
  val borderColor by animateColorAsState(
    targetValue = if (selected) accent else Color.Transparent,
    animationSpec = Os4Motion.colorSpring(),
    label = "theme-preview-border",
  )
  val labelColor by animateColorAsState(
    targetValue = if (selected) accent else scheme.onSurfaceSecondary,
    animationSpec = Os4Motion.colorSpring(),
    label = "theme-preview-label",
  )
  val gradientTop by animateColorAsState(
    targetValue = when {
      !dark -> Color(0xFF406EFF)
      darkStyle == AppDarkStyle.ENHANCED -> Color(0xFF1F387E)
      else -> Color(0xFF2D4EC2)
    },
    animationSpec = Os4Motion.colorSpring(),
    label = "theme-preview-top",
  )
  val gradientBottom by animateColorAsState(
    targetValue = when {
      !dark -> Color(0xFF55B9FA)
      darkStyle == AppDarkStyle.ENHANCED -> Color(0xFF2B609A)
      else -> Color(0xFF3E89C8)
    },
    animationSpec = Os4Motion.colorSpring(),
    label = "theme-preview-bottom",
  )
  val panelColor by animateColorAsState(
    targetValue = if (dark) Color.Black.copy(alpha = if (darkStyle == AppDarkStyle.ENHANCED) 0.34f else 0.24f)
      else Color.White.copy(alpha = 0.24f),
    animationSpec = Os4Motion.colorSpring(),
    label = "theme-preview-panel",
  )
  Card(
    modifier = modifier.semantics(mergeDescendants = true) {
      role = Role.RadioButton
      this.selected = selected
    },
    cornerRadius = 26.dp,
    insideMargin = PaddingValues(vertical = 4.dp),
    colors = CardDefaults.defaultColors(color = Color.Transparent),
    pressFeedbackType = PressFeedbackType.Sink,
    onClick = onClick,
  ) {
    Box(
      Modifier.fillMaxWidth()
        .squircleBorder(width = 3.dp, color = borderColor, cornerRadius = 26.dp)
        .padding(6.dp),
    ) {
      Canvas(Modifier.fillMaxWidth().aspectRatio(if (wide) 1.75f else 1f).squircleClip(20.dp)) {
        drawRect(Brush.verticalGradient(listOf(gradientTop, gradientBottom)))
        val illustrationWidth = size.width * if (wide) 0.72f else 1f
        val start = (size.width - illustrationWidth) / 2f
        val unitX = illustrationWidth / 680f
        val unitY = size.height / 680f
        val cornerUnit = minOf(unitX, unitY)
        val panelStroke = if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.22f)
        val lineColor = if (dark) Color.White.copy(alpha = 0.36f) else Color(0xFF285ABC).copy(alpha = 0.58f)
        val subLineColor = if (dark) Color.White.copy(alpha = 0.22f) else Color(0xFF285ABC).copy(alpha = 0.36f)
        for (top in listOf(148f, 356f)) {
          val panelLeft = start + 48f * unitX
          val panelTop = top * unitY
          val panelSize = Size(584f * unitX, 176f * unitY)
          val panelRadius = CornerRadius(44f * cornerUnit)
          drawRoundRect(panelColor, Offset(panelLeft, panelTop), panelSize, panelRadius)
          drawRoundRect(panelStroke, Offset(panelLeft, panelTop), panelSize, panelRadius,
            style = Stroke(width = 1.dp.toPx()))
          drawRoundRect(lineColor, Offset(start + 108f * unitX, (top + 52f) * unitY),
            Size(456f * unitX, 24f * unitY), CornerRadius(12f * cornerUnit))
          drawRoundRect(subLineColor, Offset(start + 108f * unitX, (top + 102f) * unitY),
            Size(304f * unitX, 22f * unitY), CornerRadius(11f * cornerUnit))
        }
      }
    }
    Text(
      text = title,
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      color = labelColor,
      style = MiuixTheme.textStyles.body2,
      fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
      textAlign = TextAlign.Center,
    )
  }
}
