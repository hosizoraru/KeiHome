package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SplashAppearanceSettingsCard(
  state: ThemeEffectsUiState,
  inScope: Boolean,
  onChange: (Boolean) -> Unit,
  showStatus: Boolean = true,
  scopeChecking: Boolean = false,
  scopeReadFailed: Boolean = false,
  title: Int = R.string.splash_adapt,
  summary: Int = R.string.splash_adapt_summary,
  tag: String = "splash-appearance",
  feature: DesktopFeature = DesktopFeature.SPLASH,
  titleAction: (@Composable () -> Unit)? = null,
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
    val allowed = inScope && !scopeChecking && !scopeReadFailed &&
      state.canSaveThemeEffects(state.savedChoice != true)
    val description = stringResource(if (showStatus) issue ?: summary else summary)
    if (titleAction == null) {
      SwitchPreference(title = stringResource(title), summary = description,
        checked = state.savedChoice == true, enabled = allowed,
        onCheckedChange = onChange, modifier = Modifier.testTag("$tag.switch"))
    } else {
      BasicComponent(modifier = Modifier.testTag("$tag.switch"), enabled = allowed,
        role = Role.Switch, onClick = { if (allowed) onChange(state.savedChoice != true) },
        endActions = {
          Switch(checked = state.savedChoice == true, enabled = allowed,
            onCheckedChange = onChange)
        }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(title), modifier = Modifier.weight(1f, fill = false),
            fontSize = MiuixTheme.textStyles.headline1.fontSize, fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface)
          Box(Modifier.width(44.dp).height(24.dp)) {
            Box(Modifier.matchParentSize().wrapContentSize(Alignment.Center, unbounded = true)) {
              titleAction()
            }
          }
        }
        Text(description, style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
    if (inScope && (state.readFailed || state.saveFailed)) {
      BasicComponent(title = stringResource(R.string.theme_effects_resave_off),
        enabled = !scopeChecking && !scopeReadFailed && state.canSaveThemeEffects(false), onClick = { onChange(false) })
    }
    DesktopSettingProgress(if (state.saving) DesktopFeatureStatus.SAVING else DesktopFeatureStatus.ACCEPTED,
      Modifier.testTag("desktop.feature.${feature.name.lowercase()}.progress"))
  }
}
