package os.kei.hyper.home.ui.main

import androidx.compose.runtime.Composable
import os.kei.hyper.home.R

@Composable
internal fun SplashIconSettingsCard(
  state: ThemeEffectsUiState,
  inScope: Boolean,
  onChange: (Boolean) -> Unit,
  showStatus: Boolean = true,
  scopeChecking: Boolean = false,
  scopeReadFailed: Boolean = false,
) {
  SplashAppearanceSettingsCard(state, inScope, onChange, showStatus,
    scopeChecking, scopeReadFailed, R.string.splash_icons_title, R.string.splash_icons_summary,
    "splash-icons", DesktopFeature.SPLASH_ICONS, help = R.string.splash_icons_help)
}
