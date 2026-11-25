package os.kei.hyper.home.ui.main

import androidx.annotation.StringRes
import os.kei.hyper.home.R

internal enum class DesktopSection(
  val route: String,
  @param:StringRes val title: Int,
  @param:StringRes val summary: Int,
) {
  OVERVIEW("overview", R.string.desktop_home_title, R.string.desktop_overview_summary),
  DOCK("dock", R.string.desktop_dock_title, R.string.desktop_dock_summary),
  APPEARANCE("appearance", R.string.desktop_display_title, R.string.desktop_display_summary),
  DARK_MODE("dark-mode", R.string.desktop_dark_mode_title, R.string.desktop_dark_mode_summary),
  ICONS("icons", R.string.desktop_icons_title, R.string.desktop_icons_summary),
  TOOLS("tools", R.string.desktop_tools_title, R.string.desktop_tools_summary),
  NAVIGATION("navigation", R.string.nav_backdrop_title, R.string.nav_backdrop_summary);

  val features: List<DesktopFeature> get() = when (this) {
    OVERVIEW, TOOLS, NAVIGATION, DARK_MODE -> emptyList()
    DOCK -> listOf(DesktopFeature.DOCK_MATERIAL, DesktopFeature.DOCK_STYLE)
    APPEARANCE -> listOf(DesktopFeature.SEARCH_MATERIAL, DesktopFeature.LABELS)
    ICONS -> listOf(DesktopFeature.ICON_EFFECTS, DesktopFeature.SPLASH_ICONS)
  }
}

internal enum class DesktopFeature { DOCK_MATERIAL, DOCK_STYLE, SEARCH_MATERIAL, LABELS, ICON_EFFECTS, SPLASH, SPLASH_ICONS }
