package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4ExplainedSwitchPreference
import os.kei.hyper.home.ui.components.os4ContentSize
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
  title: Int = R.string.splash_adapt,
  summary: Int = R.string.splash_adapt_summary,
  tag: String = "splash-appearance",
  feature: DesktopFeature? = DesktopFeature.SPLASH,
  scopeMessage: Int = R.string.splash_scope,
  help: Int? = null,
  helpSummary: Int? = null,
  content: @Composable () -> Unit = {},
) {
  val issue = when {
    state.saving -> R.string.theme_effects_saving
    state.saveFailed -> R.string.theme_effects_save_failed
    state.readFailed -> R.string.theme_effects_read_failed
    scopeReadFailed -> R.string.theme_effects_framework_failed
    scopeChecking -> R.string.theme_effects_loading
    !inScope -> scopeMessage
    state.unavailable -> R.string.theme_effects_unavailable
    state.loading || state.savedChoice == null -> R.string.theme_effects_loading
    else -> null
  }
  Card(Modifier.fillMaxWidth().os4ContentSize(), insideMargin = PaddingValues(0.dp)) {
    val allowed = inScope && !scopeChecking && !scopeReadFailed &&
      state.canSaveThemeEffects(state.savedChoice != true)
    val description = stringResource(if (showStatus) issue ?: summary else summary)
    if (help == null) {
      SwitchPreference(title = stringResource(title), summary = description,
        checked = state.savedChoice == true, enabled = allowed,
        onCheckedChange = onChange, modifier = Modifier.testTag("$tag.switch"))
    } else {
      Os4ExplainedSwitchPreference(title = stringResource(title), summary = description,
        explanation = stringResource(help), checked = state.savedChoice == true, enabled = allowed,
        onCheckedChange = onChange, tag = "$tag.switch", helpTag = "$tag.help",
        leadSummary = helpSummary?.let { stringResource(it) } ?: description)
    }
    Os4AnimatedVisibility(visible = inScope && (state.readFailed || state.saveFailed), label = "$tag-retry") {
      BasicComponent(title = stringResource(R.string.theme_effects_resave_off),
        enabled = !scopeChecking && !scopeReadFailed && state.canSaveThemeEffects(false), onClick = { onChange(false) })
    }
    content()
    DesktopSettingProgress(if (state.saving) DesktopFeatureStatus.SAVING else DesktopFeatureStatus.ACCEPTED,
      Modifier.testTag(feature?.let { "desktop.feature.${it.name.lowercase()}.progress" } ?: "$tag.progress"))
  }
}
