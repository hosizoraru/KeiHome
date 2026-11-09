package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.BuildConfig
import kotlin.math.roundToInt
import os.kei.hyper.home.R
import os.kei.hyper.home.data.DockGlassCornerStyle
import os.kei.hyper.home.status.LauncherRuntimeCapabilities
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.SliderPreference

internal val DockGlassCornerStyle.labelResource: Int get() = when (this) {
  DockGlassCornerStyle.ROUNDED -> R.string.dock_glass_corner_rounded
  DockGlassCornerStyle.RECENTS -> R.string.dock_glass_corner_recents
  DockGlassCornerStyle.PAD -> R.string.dock_glass_corner_pad
  DockGlassCornerStyle.CONCENTRIC -> R.string.dock_glass_corner_concentric
  DockGlassCornerStyle.CUSTOM -> R.string.dock_glass_corner_custom
}

@Composable
internal fun DockGlassSettingsCard(model: HomeViewModel, modifier: Modifier = Modifier) {
  val state by model.dockGlass.state.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.homeScreenRuntime.collectAsStateWithLifecycle()
  val inScope = framework.dockGlassInScope()
  val choice = state.pending ?: state.saved
  val now = android.os.SystemClock.elapsedRealtime()
  val version = BuildConfig.VERSION_CODE.toLong()
  val supported = desktopCapability(runtime, now, version, LauncherRuntimeCapabilities.DOCK)
  val layoutSupported = desktopCapability(runtime, now, version, LauncherRuntimeCapabilities.DOCK_LAYOUT)
  val controlsEnabled = desktopControlEnabled(inScope, state.saving, state.unavailable, state.saved != null, supported)
  val cornerSummary = when (choice?.cornerStyle) {
    DockGlassCornerStyle.RECENTS -> R.string.dock_glass_corner_recents_summary
    DockGlassCornerStyle.ROUNDED -> R.string.dock_glass_corner_rounded_summary
    DockGlassCornerStyle.PAD -> R.string.dock_glass_corner_pad_summary
    DockGlassCornerStyle.CONCENTRIC -> R.string.dock_glass_corner_concentric_summary
    DockGlassCornerStyle.CUSTOM -> R.string.dock_glass_corner_custom_summary
    null -> R.string.dock_glass_corner_summary
  }
  val dockControls = desktopDockControls(choice, controlsEnabled, layoutSupported)
  val styles = listOf(DockGlassCornerStyle.RECENTS, DockGlassCornerStyle.ROUNDED,
    DockGlassCornerStyle.PAD, DockGlassCornerStyle.CONCENTRIC, DockGlassCornerStyle.CUSTOM)
  val issue = desktopStatusMessage(desktopDockStatus(state, runtime, inScope, now, version),
    state.pending != null, state.unavailable && supported != false, R.string.dock_glass_native_retained)
  val layoutHint = if (supported == true && layoutSupported == false)
    "\n" + stringResource(R.string.dock_glass_layout_unavailable) else ""
  Card(modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    SwitchPreference(title = stringResource(R.string.dock_glass_title),
      summary = stringResource(R.string.dock_glass_summary) +
        (issue?.let { "\n" + stringResource(it) } ?: ""),
      checked = state.saved?.enabled == true,
      enabled = controlsEnabled,
      onCheckedChange = model.dockGlass::setEnabled,
      modifier = Modifier.testTag("dock-glass.switch"))
    OverlaySpinnerPreference(title = stringResource(R.string.dock_glass_corner_title),
      summary = stringResource(cornerSummary) + layoutHint,
      items = styles.map { DropdownItem(text = stringResource(it.labelResource),
        enabled = desktopDockCornerStyleSupported(it, layoutSupported)) },
      selectedIndex = styles.indexOf(choice?.cornerStyle ?: DockGlassCornerStyle.RECENTS),
      enabled = dockControls.cornerChooser,
      onSelectedIndexChange = { index ->
        if (dockControls.cornerChooser && choice != null) styles.getOrNull(index)?.let {
          desktopDockCornerSelection(choice, it, layoutSupported)?.let(model.dockGlass::save)
        }
      },
      modifier = Modifier.testTag("dock-glass.corner-style"))
    DockGeometrySlider(value = choice?.verticalPaddingDp ?: 18, range = 0..32,
      title = stringResource(R.string.dock_glass_padding_title),
      summary = stringResource(R.string.dock_glass_padding_summary),
      enabled = dockControls.geometry, onCommit = model.dockGlass::setVerticalPadding,
      modifier = Modifier.testTag("dock-glass.vertical-padding"))
    DockGeometrySlider(value = choice?.verticalOffsetDp ?: 0, range = -48..96,
      title = stringResource(R.string.dock_glass_offset_title),
      summary = stringResource(R.string.dock_glass_offset_summary),
      enabled = dockControls.geometry, onCommit = model.dockGlass::setVerticalOffset,
      modifier = Modifier.testTag("dock-glass.vertical-offset"))
    if (choice?.cornerStyle == DockGlassCornerStyle.CUSTOM) {
      DockGeometrySlider(value = choice.customRadiusDp, range = 0..64,
        title = stringResource(R.string.dock_glass_radius_title),
        summary = stringResource(R.string.dock_glass_radius_summary),
        enabled = dockControls.geometry, onCommit = model.dockGlass::setCustomRadius,
        modifier = Modifier.testTag("dock-glass.custom-radius"))
    }
    BasicComponent(title = stringResource(R.string.dock_glass_reset),
      summary = stringResource(R.string.dock_glass_reset_summary) + layoutHint,
      enabled = dockControls.resetGeometry,
      onClick = model.dockGlass::resetGeometry,
      modifier = Modifier.testTag("dock-glass.reset-geometry"))
    if (state.failed && inScope) BasicComponent(title = stringResource(R.string.launcher_labels_retry),
      enabled = !state.saving && (state.pending?.let {
        desktopDockSaveSupported(it, supported, layoutSupported)
      } ?: true), onClick = {
        state.pending?.let(model.dockGlass::save) ?: model.readDockGlassSettings()
      })
  }
}

@Composable
private fun DockGeometrySlider(
  value: Int,
  range: IntRange,
  title: String,
  summary: String,
  enabled: Boolean,
  onCommit: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Keep drag samples local; one complete preference snapshot is saved on release.
  var draft by remember(value, enabled) { mutableIntStateOf(value) }
  SliderPreference(value = draft.toFloat(),
    onValueChange = { draft = it.roundToInt().coerceIn(range) },
    onValueChangeFinished = { if (enabled && draft != value) onCommit(draft) },
    valueRange = range.first.toFloat()..range.last.toFloat(),
    steps = range.last - range.first - 1,
    title = title, summary = summary,
    valueText = stringResource(R.string.dock_glass_dp, draft),
    enabled = enabled, modifier = modifier)
}
