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
import os.kei.hyper.home.R
import os.kei.hyper.home.data.DockGlassCornerStyle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun DockGlassSettingsCard(model: HomeViewModel, modifier: Modifier = Modifier) {
  val state by model.dockGlass.state.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.homeScreenRuntime.collectAsStateWithLifecycle()
  val inScope = framework.dockGlassInScope()
  val report = runtime.report?.takeIf { it.isFresh(android.os.SystemClock.elapsedRealtime()) }
  val issue = when {
    !inScope -> R.string.launcher_labels_scope
    state.unavailable -> R.string.theme_effects_unavailable
    state.saving -> R.string.theme_effects_saving
    state.failed -> if (state.pending != null) R.string.theme_effects_save_failed else R.string.theme_effects_read_failed
    state.saved == null -> R.string.theme_effects_loading
    runtime.readFailed || runtime.timedOut || report?.acknowledgesDockGlass(state.saved) != true -> R.string.dock_glass_pending
    else -> null
  }
  Card(modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    SwitchPreference(title = stringResource(R.string.dock_glass_title),
      summary = stringResource(R.string.dock_glass_summary) +
        (issue?.let { "\n" + stringResource(it) } ?: ""),
      checked = state.saved?.enabled == true,
      enabled = inScope && !state.saving && !state.unavailable && state.saved != null,
      onCheckedChange = model.dockGlass::setEnabled,
      modifier = Modifier.testTag("dock-glass.switch"))
    OverlaySpinnerPreference(title = stringResource(R.string.dock_glass_corner_title),
      summary = stringResource(R.string.dock_glass_corner_summary),
      items = listOf(R.string.dock_glass_corner_recents, R.string.dock_glass_corner_rounded)
        .map { DropdownItem(text = stringResource(it)) },
      selectedIndex = if (state.saved?.cornerStyle == DockGlassCornerStyle.ROUNDED) 1 else 0,
      enabled = inScope && !state.saving && !state.unavailable && state.saved?.enabled == true,
      onSelectedIndexChange = { model.dockGlass.setCornerStyle(
        if (it == 0) DockGlassCornerStyle.RECENTS else DockGlassCornerStyle.ROUNDED) },
      modifier = Modifier.testTag("dock-glass.corner-style"))
    if (state.failed && inScope) BasicComponent(title = stringResource(R.string.launcher_labels_retry),
      enabled = !state.saving, onClick = {
        state.pending?.let(model.dockGlass::save) ?: model.readDockGlassSettings()
      })
  }
}
