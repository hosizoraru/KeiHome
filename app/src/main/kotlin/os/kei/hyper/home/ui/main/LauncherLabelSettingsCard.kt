package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.LauncherLabelMode
import os.kei.hyper.home.status.LauncherRuntimeCapabilities
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
  val now = android.os.SystemClock.elapsedRealtime()
  val version = BuildConfig.VERSION_CODE.toLong()
  val supported = desktopCapability(runtime, now, version, LauncherRuntimeCapabilities.LABELS)
  val issue = desktopStatusMessage(desktopLabelStatus(state, runtime, inScope, now, version),
    state.pending != null, state.unavailable && supported != false, R.string.launcher_labels_unavailable)
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    OverlaySpinnerPreference(title = stringResource(R.string.launcher_labels_title),
      summary = stringResource(R.string.launcher_labels_summary) + (issue?.let { "\n" + stringResource(it) } ?: ""),
      items = listOf(R.string.launcher_labels_native, R.string.launcher_labels_marquee, R.string.launcher_labels_two_lines)
        .map { DropdownItem(text = stringResource(it)) },
      selectedIndex = (state.saved ?: LauncherLabelMode.NATIVE).ordinal,
      enabled = desktopControlEnabled(inScope, state.saving, state.unavailable, state.saved != null, supported),
      onSelectedIndexChange = { model.launcherLabels.save(LauncherLabelMode.entries[it]) })
    if (state.failed && inScope) BasicComponent(title = stringResource(R.string.launcher_labels_retry),
      enabled = !state.saving && (state.pending == null || supported != false), onClick = {
        state.pending?.let(model.launcherLabels::save) ?: run { model.readLauncherLabelSettings() }
      })
  }
}
