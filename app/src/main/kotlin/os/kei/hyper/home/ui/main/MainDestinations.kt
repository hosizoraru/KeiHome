package os.kei.hyper.home.ui.main

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import os.kei.hyper.home.DesktopRoute
import os.kei.hyper.home.HomeRoute
import os.kei.hyper.home.PackRoute
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.NavigationItem

internal enum class MainTab(val page: Int, @param:StringRes val title: Int, val icon: PageActionIcon) {
  STATUS(0, R.string.navigation_status, PageActionIcon.STATUS),
  APPS(1, R.string.navigation_apps, PageActionIcon.APPS),
  PACKS(3, R.string.navigation_icon_packs, PageActionIcon.PACKS),
  DESKTOP(5, R.string.navigation_desktop, PageActionIcon.DESKTOP),
  SETTINGS(4, R.string.navigation_settings, PageActionIcon.SETTINGS),
}

internal enum class MainShortcut(
  val parent: MainTab,
  val route: HomeRoute,
  @param:StringRes val title: Int,
  val icon: PageActionIcon,
) {
  LIBRARY(MainTab.PACKS, PackRoute("library"), R.string.icon_pack_library, PageActionIcon.PACKS),
  MONET(MainTab.PACKS, PackRoute("monet"), R.string.navigation_monet, PageActionIcon.MONET),
  COMPLETION(MainTab.PACKS, PackRoute("fallback"), R.string.pack_completion_title, PageActionIcon.MATCH),
  CUSTOM(MainTab.PACKS, PackRoute("custom-icons"), R.string.custom_icons_title, PageActionIcon.CUSTOMIZE),
  TOOLS(MainTab.DESKTOP, DesktopRoute(DesktopSection.TOOLS.route), DesktopSection.TOOLS.title, PageActionIcon.TOOLS),
}

@Composable
internal fun mainShortcutItems(): List<NavigationItem> = MainShortcut.entries.map {
  NavigationItem(stringResource(it.title), actionIcon(it.icon))
}
