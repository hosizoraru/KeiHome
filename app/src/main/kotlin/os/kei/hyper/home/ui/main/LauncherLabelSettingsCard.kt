package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.LauncherLabelMode
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference

@Composable
internal fun LauncherLabelSettingsCard(model: HomeViewModel) {
  val state by model.launcherLabels.state.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.homeScreenRuntime.collectAsStateWithLifecycle()
  val inScope = framework.launcherLabelsInScope()
  val issue = when {
    !inScope -> R.string.launcher_labels_scope
    state.unavailable -> R.string.theme_effects_unavailable
    state.saving -> R.string.theme_effects_saving
    state.failed -> if (state.pending != null) R.string.theme_effects_save_failed else R.string.theme_effects_read_failed
    state.saved == null -> R.string.theme_effects_loading
    state.saved != LauncherLabelMode.NATIVE && runtime.report?.launcherLabelMode == null -> R.string.launcher_labels_pending
    runtime.report?.launcherLabelMode != null && runtime.report?.launcherLabelMode != state.saved?.nativeValue ->
      R.string.launcher_labels_pending
    else -> null
  }
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    OverlaySpinnerPreference(title = stringResource(R.string.launcher_labels_title),
      summary = stringResource(issue ?: R.string.launcher_labels_summary),
      items = listOf(R.string.launcher_labels_native, R.string.launcher_labels_marquee, R.string.launcher_labels_two_lines)
        .map { DropdownItem(text = stringResource(it)) },
      selectedIndex = (state.saved ?: LauncherLabelMode.NATIVE).ordinal,
      enabled = inScope && !state.saving && !state.unavailable && state.saved != null,
      onSelectedIndexChange = { model.launcherLabels.save(LauncherLabelMode.entries[it]) })
    if (state.failed && inScope) BasicComponent(title = stringResource(R.string.launcher_labels_retry),
      enabled = !state.saving, onClick = {
        state.pending?.let(model.launcherLabels::save) ?: run { model.readLauncherLabelSettings() }
      })
  }
}
