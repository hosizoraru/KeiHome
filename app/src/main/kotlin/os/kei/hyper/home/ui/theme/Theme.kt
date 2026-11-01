package os.kei.hyper.home.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import os.kei.hyper.home.data.AppAppearance
import os.kei.hyper.home.data.AppPreferences
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

internal val LocalKeiHomeDarkTheme = staticCompositionLocalOf { false }

@Composable
fun KeiHomeTheme(content: @Composable () -> Unit) {
  val preferences = AppPreferences.get(LocalContext.current)
  val initialAppearance = remember(preferences) { preferences.state.value.appearance }
  val appearance by remember(preferences) { preferences.state.map { it.appearance }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(initialValue = initialAppearance)
  val controller = remember(appearance) {
    ThemeController(when (appearance) {
      AppAppearance.SYSTEM -> ColorSchemeMode.MonetSystem
      AppAppearance.LIGHT -> ColorSchemeMode.MonetLight
      AppAppearance.DARK -> ColorSchemeMode.MonetDark
    })
  }
  val dark = when (appearance) {
    AppAppearance.SYSTEM -> isSystemInDarkTheme()
    AppAppearance.LIGHT -> false
    AppAppearance.DARK -> true
  }
  val activity = LocalActivity.current
  SideEffect {
    activity?.window?.let { window ->
      WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
      }
    }
  }
  CompositionLocalProvider(LocalKeiHomeDarkTheme provides dark) {
    MiuixTheme(controller = controller, content = content)
  }
}

