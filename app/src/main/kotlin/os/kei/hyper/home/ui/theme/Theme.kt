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
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.channels.awaitClose
import os.kei.hyper.home.data.AppAppearance
import os.kei.hyper.home.data.AppPreferences
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

internal val LocalKeiHomeDarkTheme = staticCompositionLocalOf { false }

@Composable
fun KeiHomeTheme(content: @Composable () -> Unit) {
  val preferences = AppPreferences.get(LocalContext.current)
  val (choiceFlow, initialChoice) = remember(preferences) {
    preferences.state.map { it.appearance to it.colorMode }.distinctUntilChanged() to
      preferences.state.value.let { it.appearance to it.colorMode }
  }
  val choice by choiceFlow.collectAsStateWithLifecycle(initialValue = initialChoice)
  val (appearance, colorMode) = choice
  val context = LocalContext.current
  val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
  val palette by androidx.compose.runtime.produceState<os.kei.hyper.home.data.MonetPaletteSnapshot?>(null, context, colorMode, lifecycle) {
    if (colorMode != os.kei.hyper.home.data.AppColorMode.FOLLOW_ICONS) return@produceState
    lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
    kotlinx.coroutines.flow.callbackFlow {
      val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { trySend(Unit) }
      }
      val resolver = context.contentResolver
      resolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, observer)
      resolver.registerContentObserver(android.provider.Settings.Secure.CONTENT_URI, true, observer)
      trySend(Unit)
      awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate().collect {
      value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        os.kei.hyper.home.data.MonetPaletteReader(context).read()
      }
    }
    }
  }
  val useMonet = when (colorMode) {
    os.kei.hyper.home.data.AppColorMode.DEFAULT -> false
    os.kei.hyper.home.data.AppColorMode.MONET -> true
    os.kei.hyper.home.data.AppColorMode.FOLLOW_ICONS -> palette?.customizationActive == true
  }
  val keyColor = palette?.takeIf { colorMode == os.kei.hyper.home.data.AppColorMode.FOLLOW_ICONS && useMonet }
    ?.families?.firstOrNull { it.name == "primary" }?.colors?.firstOrNull { it.tone == 50 }?.value
  val controller = remember(appearance, useMonet, keyColor) {
    ThemeController(colorSchemeMode = when (appearance) {
      AppAppearance.SYSTEM -> if (useMonet) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
      AppAppearance.LIGHT -> if (useMonet) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
      AppAppearance.DARK -> if (useMonet) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
    }, keyColor = keyColor?.let { androidx.compose.ui.graphics.Color(it) })
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

