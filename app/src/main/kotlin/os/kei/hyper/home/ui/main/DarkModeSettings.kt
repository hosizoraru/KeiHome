package os.kei.hyper.home.ui.main

import android.os.Process
import android.os.UserHandle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import os.kei.hyper.home.R
import os.kei.hyper.home.data.SystemDarkStyle
import os.kei.hyper.home.data.supportsLowBrightnessContrast
import os.kei.hyper.home.data.supportsSystemDarkStyle
import os.kei.hyper.home.status.SystemDarkStyleProtocol
import os.kei.hyper.home.ui.components.Os4DropdownPreference
import top.yukonga.miuix.kmp.basic.Card

internal val darkStyleLabels = listOf(R.string.dark_mode_style_enhanced, R.string.dark_mode_style_medium,
  R.string.dark_mode_style_gentle)

@Composable
internal fun SystemDarkStyleSettingsCard(model: HomeViewModel) {
  if (!supportsSystemDarkStyle() || Process.myUserHandle() != UserHandle.getUserHandleForUid(0)) return
  val context = LocalContext.current.applicationContext
  val framework by model.framework.collectAsStateWithLifecycle()
  val inScope = framework.wallpaperDimmingInScope()
  val scope = rememberCoroutineScope()
  var selected by remember(inScope) { mutableStateOf<SystemDarkStyle?>(null) }
  var busy by remember(inScope) { mutableStateOf(inScope) }
  var failed by remember(inScope) { mutableStateOf(false) }

  suspend fun refresh(requested: SystemDarkStyle? = null) {
    busy = true
    val result = SystemDarkStyleProtocol.request(context, requested)
    if (result != null) selected = result
    failed = result == null
    busy = false
  }

  LaunchedEffect(inScope) { if (inScope) refresh() }

  val summary = when {
    framework.desktopScopeChecking() -> R.string.scope_loading
    !inScope -> R.string.system_dark_style_scope
    busy -> R.string.system_dark_style_busy
    failed -> R.string.system_dark_style_failed
    else -> R.string.system_dark_style_summary
  }
  Card(insideMargin = PaddingValues(0.dp)) {
    Os4DropdownPreference(
      title = stringResource(R.string.system_dark_style_title),
      summary = stringResource(summary),
      items = darkStyleLabels.map { stringResource(it) },
      selectedIndex = selected?.ordinal ?: -1,
      enabled = inScope && !busy,
      onSelectedIndexChange = { index ->
        val style = SystemDarkStyle.entries[index]
        if (!busy && (failed || style != selected)) {
          busy = true
          scope.launch { refresh(style) }
        }
      },
      modifier = Modifier.testTag("desktop.dark-mode.system-style"),
    )
  }
}

@Composable
internal fun LowBrightnessContrastSettingsCard(model: HomeViewModel) {
  if (!supportsLowBrightnessContrast()) return
  val settings by model.lowBrightnessContrast.state.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  SplashAppearanceSettingsCard(settings, framework.wallpaperDimmingInScope(), model.lowBrightnessContrast::save,
    scopeChecking = framework.desktopScopeChecking(), scopeReadFailed = framework.readFailed,
    title = R.string.dark_mode_low_contrast, summary = R.string.dark_mode_low_contrast_summary,
    tag = "dark-mode.low-contrast", feature = null, scopeMessage = R.string.dark_mode_low_contrast_scope)
}
