package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.LauncherGlassStyle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference

@Composable
internal fun LauncherGlassSettingsCard(model: HomeViewModel) {
  val state by model.launcherGlass.state.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.homeScreenRuntime.collectAsStateWithLifecycle()
  val inScope = framework.launcherGlassInScope()
  val choices = listOf(R.string.launcher_glass_system, R.string.launcher_glass_light, R.string.launcher_glass_dark)
  val issue = when {
    !inScope -> R.string.launcher_labels_scope
    state.unavailable -> R.string.theme_effects_unavailable
    state.saving -> R.string.theme_effects_saving
    state.failed -> if (state.pending != null) R.string.theme_effects_save_failed else R.string.theme_effects_read_failed
    state.saved == null -> R.string.theme_effects_loading
    else -> null
  }
  val status = issue?.let { stringResource(it) } ?: stringResource(
    R.string.launcher_glass_saved_status, stringResource(choices[requireNotNull(state.saved).ordinal]),
    stringResource(when (launcherGlassRuntimeStatus(state.saved, runtime, android.os.SystemClock.elapsedRealtime(),
      BuildConfig.VERSION_CODE.toLong())) {
      LauncherGlassRuntimeStatus.PENDING -> R.string.launcher_glass_pending
      LauncherGlassRuntimeStatus.UNAVAILABLE -> R.string.launcher_glass_unavailable
      LauncherGlassRuntimeStatus.ACCEPTED -> R.string.launcher_glass_accepted
    }))
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    OverlaySpinnerPreference(title = stringResource(R.string.launcher_glass_title),
      summary = stringResource(R.string.launcher_glass_summary) + "\n" + status,
      items = choices.map { DropdownItem(text = stringResource(it)) },
      selectedIndex = (state.saved ?: LauncherGlassStyle.SYSTEM).ordinal,
      enabled = inScope && !state.saving && !state.unavailable && state.saved != null,
      onSelectedIndexChange = { index -> LauncherGlassStyle.entries.getOrNull(index)?.let(model.launcherGlass::save) },
      modifier = Modifier.testTag("launcher-glass.style"))
    if (state.failed && inScope) BasicComponent(title = stringResource(R.string.launcher_labels_retry),
      enabled = !state.saving, onClick = {
        state.pending?.let(model.launcherGlass::save) ?: model.readLauncherGlassSettings()
      })
  }
}
