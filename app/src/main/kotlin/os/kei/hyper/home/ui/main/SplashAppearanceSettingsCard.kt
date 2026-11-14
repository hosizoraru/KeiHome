package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SplashAppearanceSettingsCard(
  state: ThemeEffectsUiState,
  inScope: Boolean,
  onChange: (Boolean) -> Unit,
  showStatus: Boolean = true,
  scopeChecking: Boolean = false,
  scopeReadFailed: Boolean = false,
) {
  val issue = when {
    state.saving -> R.string.theme_effects_saving
    state.saveFailed -> R.string.theme_effects_save_failed
    state.readFailed -> R.string.theme_effects_read_failed
    scopeReadFailed -> R.string.theme_effects_framework_failed
    scopeChecking -> R.string.theme_effects_loading
    !inScope -> R.string.splash_scope
    state.unavailable -> R.string.theme_effects_unavailable
    state.loading || state.savedChoice == null -> R.string.theme_effects_loading
    else -> null
  }
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    SwitchPreference(title = stringResource(R.string.splash_adapt),
      summary = stringResource(if (showStatus) issue ?: R.string.splash_adapt_summary else R.string.splash_adapt_summary),
      checked = state.savedChoice == true,
      enabled = inScope && !scopeChecking && !scopeReadFailed && state.canSaveThemeEffects(state.savedChoice != true),
      onCheckedChange = onChange, modifier = Modifier.testTag("splash-appearance.switch"))
    if (inScope && (state.readFailed || state.saveFailed)) {
      BasicComponent(title = stringResource(R.string.theme_effects_resave_off),
        enabled = !scopeChecking && !scopeReadFailed && state.canSaveThemeEffects(false), onClick = { onChange(false) })
    }
    DesktopSettingProgress(if (state.saving) DesktopFeatureStatus.SAVING else DesktopFeatureStatus.ACCEPTED,
      Modifier.testTag("desktop.feature.splash.progress"))
  }
}
