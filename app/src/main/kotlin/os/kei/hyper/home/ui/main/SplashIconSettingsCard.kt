package os.kei.hyper.home.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SplashIconSettingsCard(
  state: ThemeEffectsUiState,
  inScope: Boolean,
  onChange: (Boolean) -> Unit,
  showStatus: Boolean = true,
  scopeChecking: Boolean = false,
  scopeReadFailed: Boolean = false,
) {
  var help by rememberSaveable { mutableStateOf(false) }
  SplashAppearanceSettingsCard(state, inScope, onChange, showStatus,
    scopeChecking, scopeReadFailed, R.string.splash_icons_title, R.string.splash_icons_summary,
    "splash-icons", DesktopFeature.SPLASH_ICONS, titleAction = {
      IconButton(onClick = { help = true }, minWidth = 44.dp, minHeight = 44.dp,
        modifier = Modifier.testTag("splash-icons.help.open")) {
        Icon(MiuixIcons.Glass.Info, stringResource(R.string.splash_icons_help_title),
          Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    })
  Os4DetailsSheet(show = help, onDismissRequest = { help = false },
    title = stringResource(R.string.splash_icons_title), modifier = Modifier.testTag("splash-icons.help")) {
    Text(stringResource(R.string.splash_icons_help), style = MiuixTheme.textStyles.body2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
}
