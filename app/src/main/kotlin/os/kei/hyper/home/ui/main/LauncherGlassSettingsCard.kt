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
import os.kei.hyper.home.status.LauncherRuntimeCapabilities
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
  val now = android.os.SystemClock.elapsedRealtime()
  val version = BuildConfig.VERSION_CODE.toLong()
  val supported = desktopCapability(runtime, now, version, LauncherRuntimeCapabilities.GLASS)
  val issue = desktopStatusMessage(desktopGlassStatus(state, runtime, inScope, now, version),
    state.pending != null, state.unavailable && supported != false, R.string.launcher_glass_unavailable)
  val receipt = stringResource(issue ?: R.string.launcher_glass_accepted)
  val status = state.saved?.let {
    stringResource(R.string.launcher_glass_saved_status, stringResource(choices[it.ordinal]), receipt)
  } ?: receipt
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    OverlaySpinnerPreference(title = stringResource(R.string.launcher_glass_title),
      summary = stringResource(R.string.launcher_glass_summary) + "\n" + status,
      items = choices.map { DropdownItem(text = stringResource(it)) },
      selectedIndex = (state.saved ?: LauncherGlassStyle.SYSTEM).ordinal,
      enabled = desktopControlEnabled(inScope, state.saving, state.unavailable, state.saved != null, supported),
      onSelectedIndexChange = { index -> LauncherGlassStyle.entries.getOrNull(index)?.let(model.launcherGlass::save) },
      modifier = Modifier.testTag("launcher-glass.style"))
    if (state.failed && inScope) BasicComponent(title = stringResource(R.string.launcher_labels_retry),
      enabled = !state.saving && (state.pending == null || supported != false), onClick = {
        state.pending?.let(model.launcherGlass::save) ?: model.readLauncherGlassSettings()
      })
  }
}
