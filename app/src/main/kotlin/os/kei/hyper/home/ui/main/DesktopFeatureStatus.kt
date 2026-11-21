package os.kei.hyper.home.ui.main

import os.kei.hyper.home.R
import os.kei.hyper.home.data.DockGlassChoice
import os.kei.hyper.home.data.DockGlassCornerStyle
import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.LauncherRuntimeCapabilities
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState

internal enum class DesktopFeatureStatus {
  OUT_OF_SCOPE, LOADING, SAVING, ERROR, UNAVAILABLE, UNKNOWN, CHECKING, READ_FAILED, TIMED_OUT,
  LAYOUT_UNAVAILABLE, HORIZONTAL_PADDING_UNAVAILABLE, PENDING, ACCEPTED,
}

internal fun FrameworkUiState.desktopScopeChecking(): Boolean =
  !readFailed && (checking || (connected && snapshot == null))

internal fun desktopAggregateTone(
  statuses: List<DesktopFeatureStatus>,
  ready: Boolean,
  allUnsupported: Boolean,
): StatusTone = when {
  statuses.any { it == DesktopFeatureStatus.ERROR || it == DesktopFeatureStatus.READ_FAILED ||
    it == DesktopFeatureStatus.TIMED_OUT } -> StatusTone.ERROR
  statuses.any { it == DesktopFeatureStatus.CHECKING || it == DesktopFeatureStatus.LOADING } -> StatusTone.CHECKING
  ready || allUnsupported -> StatusTone.READY
  else -> StatusTone.WARNING
}

internal fun desktopCapability(
  runtime: RuntimeStatusUiState, now: Long, moduleVersion: Long, capability: Int,
): Boolean? = desktopRuntimeReport(runtime, now, moduleVersion)?.supportsLauncherCapability(capability)

internal fun desktopControlEnabled(inScope: Boolean, saving: Boolean, unavailable: Boolean,
  hasSaved: Boolean, supported: Boolean?, scopeChecking: Boolean = false): Boolean =
  inScope && !scopeChecking && !saving && !unavailable && hasSaved && supported != false

internal fun desktopDockLayoutEnabled(dockEnabled: Boolean, controlsEnabled: Boolean,
  layoutSupported: Boolean?): Boolean = dockEnabled && controlsEnabled && layoutSupported != false

internal data class DesktopDockControls(val cornerChooser: Boolean, val resetGeometry: Boolean, val geometry: Boolean)

internal fun desktopDockControls(choice: DockGlassChoice?, controlsEnabled: Boolean,
  layoutSupported: Boolean?): DesktopDockControls = DesktopDockControls(
  cornerChooser = controlsEnabled && choice?.enabled == true,
  resetGeometry = controlsEnabled && choice != null,
  geometry = desktopDockLayoutEnabled(choice?.enabled == true, controlsEnabled, layoutSupported),
)

internal fun desktopDockCornerStyleSupported(style: DockGlassCornerStyle, layoutSupported: Boolean?): Boolean =
  layoutSupported != false || style == DockGlassCornerStyle.RECENTS || style == DockGlassCornerStyle.ROUNDED

internal fun desktopDockCornerSelection(choice: DockGlassChoice, style: DockGlassCornerStyle,
  layoutSupported: Boolean?): DockGlassChoice? {
  if (!desktopDockCornerStyleSupported(style, layoutSupported)) return null
  // Only an explicit legacy-style selection clears geometry that this receiver cannot accept.
  return if (layoutSupported == false) DockGlassChoice(enabled = choice.enabled, cornerStyle = style)
    else choice.copy(cornerStyle = style)
}

internal fun desktopDockSaveSupported(choice: DockGlassChoice, dockSupported: Boolean?,
  layoutSupported: Boolean?, horizontalPaddingSupported: Boolean? = null): Boolean =
  dockSupported != false && (layoutSupported != false || choice.options in 0..1) &&
    (horizontalPaddingSupported != false || choice.horizontalPaddingDeltaDp == 0)

internal data class DesktopSyncSummary(val accepted: Int, val supported: Int, val ready: Boolean)

internal fun desktopSyncSummary(
  statuses: List<DesktopFeatureStatus>, capabilities: List<Boolean?>,
): DesktopSyncSummary {
  require(statuses.size == capabilities.size)
  val supported = capabilities.count { it == true }
  val accepted = statuses.indices.count { capabilities[it] == true && statuses[it] == DesktopFeatureStatus.ACCEPTED }
  return DesktopSyncSummary(accepted, supported, supported > 0 && accepted == supported &&
    capabilities.none { it == null } && statuses.all {
      it == DesktopFeatureStatus.ACCEPTED || it == DesktopFeatureStatus.UNAVAILABLE
    })
}

internal fun desktopDockStatus(
  state: DockGlassUiState,
  runtime: RuntimeStatusUiState,
  inScope: Boolean,
  now: Long,
  moduleVersion: Long,
  scopeChecking: Boolean = false,
): DesktopFeatureStatus {
  desktopUnresolvedScopeStatus(scopeChecking, state.saving, state.failed, runtime)?.let { return it }
  val report = desktopRuntimeReport(runtime, now, moduleVersion)
  val supported = report?.supportsLauncherCapability(LauncherRuntimeCapabilities.DOCK)
  desktopPreferenceStatus(inScope, state.unavailable, state.saving, state.failed, state.saved != null, supported)
    ?.let { return it }
  // Dock has its own process-bound ACK, independent of Monet readiness.
  if (supported == true && state.saved != null && state.saved.options !in 0..1 &&
    report.supportsLauncherCapability(LauncherRuntimeCapabilities.DOCK_LAYOUT) == false)
    return DesktopFeatureStatus.LAYOUT_UNAVAILABLE
  if (supported == true && state.saved != null && state.saved.horizontalPaddingDeltaDp != 0 &&
    report.supportsLauncherCapability(LauncherRuntimeCapabilities.DOCK_HORIZONTAL_PADDING) == false)
    return DesktopFeatureStatus.HORIZONTAL_PADDING_UNAVAILABLE
  return desktopReceiverStatus(runtime, report, supported, report?.acknowledgesDockGlass(state.saved) == true)
}

internal fun desktopLabelStatus(
  state: LauncherLabelUiState,
  runtime: RuntimeStatusUiState,
  inScope: Boolean,
  now: Long,
  moduleVersion: Long,
  scopeChecking: Boolean = false,
): DesktopFeatureStatus {
  desktopUnresolvedScopeStatus(scopeChecking, state.saving, state.failed, runtime)?.let { return it }
  val report = desktopRuntimeReport(runtime, now, moduleVersion)
  val supported = report?.supportsLauncherCapability(LauncherRuntimeCapabilities.LABELS)
  desktopPreferenceStatus(inScope, state.unavailable, state.saving, state.failed, state.saved != null, supported)
    ?.let { return it }
  // The producer emits this field only for a current process-validated ACK, including NATIVE.
  return desktopReceiverStatus(runtime, report, supported,
    report != null && report.launcherLabelMode == state.saved?.nativeValue)
}

internal fun desktopGlassStatus(
  state: LauncherGlassUiState,
  runtime: RuntimeStatusUiState,
  inScope: Boolean,
  now: Long,
  moduleVersion: Long,
  scopeChecking: Boolean = false,
): DesktopFeatureStatus {
  desktopUnresolvedScopeStatus(scopeChecking, state.saving, state.failed, runtime)?.let { return it }
  val report = desktopRuntimeReport(runtime, now, moduleVersion)
  val supported = report?.supportsLauncherCapability(LauncherRuntimeCapabilities.GLASS)
  desktopPreferenceStatus(inScope, state.unavailable, state.saving, state.failed, state.saved != null, supported)
    ?.let { return it }
  return desktopReceiverStatus(runtime, report, supported,
    report?.acknowledgesLauncherGlass(state.saved) == true)
}

internal fun desktopSearchGlassStatus(
  state: LauncherGlassUiState,
  runtime: RuntimeStatusUiState,
  inScope: Boolean,
  now: Long,
  moduleVersion: Long,
  scopeChecking: Boolean = false,
): DesktopFeatureStatus {
  desktopUnresolvedScopeStatus(scopeChecking, state.saving, state.failed, runtime)?.let { return it }
  val report = desktopRuntimeReport(runtime, now, moduleVersion)
  val supported = report?.supportsLauncherCapability(LauncherRuntimeCapabilities.GLASS_PAIR)
  desktopPreferenceStatus(inScope, state.unavailable, state.saving, state.failed, state.savedSearch != null, supported)
    ?.let { return it }
  val choice = state.saved?.let { dock -> state.savedSearch?.let { search ->
    os.kei.hyper.home.data.LauncherGlassChoice(dock, search)
  } }
  return desktopReceiverStatus(runtime, report, supported, report?.acknowledgesSearchGlass(choice) == true)
}

internal fun desktopStoredStatus(state: ThemeEffectsUiState, inScope: Boolean,
  scopeChecking: Boolean = false): DesktopFeatureStatus = when {
  scopeChecking && state.saving -> DesktopFeatureStatus.SAVING
  scopeChecking && state.saveFailed -> DesktopFeatureStatus.ERROR
  scopeChecking && state.readFailed -> DesktopFeatureStatus.READ_FAILED
  scopeChecking -> DesktopFeatureStatus.CHECKING
  !inScope -> DesktopFeatureStatus.OUT_OF_SCOPE
  state.unavailable -> DesktopFeatureStatus.UNAVAILABLE
  state.saving -> DesktopFeatureStatus.SAVING
  state.saveFailed -> DesktopFeatureStatus.ERROR
  state.readFailed -> DesktopFeatureStatus.READ_FAILED
  state.loading || state.savedChoice == null -> DesktopFeatureStatus.LOADING
  else -> DesktopFeatureStatus.ACCEPTED
}

private fun desktopUnresolvedScopeStatus(scopeChecking: Boolean, saving: Boolean, failed: Boolean,
  runtime: RuntimeStatusUiState): DesktopFeatureStatus? = when {
  !scopeChecking -> null
  saving -> DesktopFeatureStatus.SAVING
  failed -> DesktopFeatureStatus.ERROR
  runtime.readFailed -> DesktopFeatureStatus.READ_FAILED
  runtime.timedOut -> DesktopFeatureStatus.TIMED_OUT
  else -> DesktopFeatureStatus.CHECKING
}

private fun desktopReceiverStatus(runtime: RuntimeStatusUiState, report: RuntimeStatusReport?,
  supported: Boolean?, acknowledged: Boolean): DesktopFeatureStatus = when {
  report == null && runtime.readFailed -> DesktopFeatureStatus.READ_FAILED
  report == null && runtime.timedOut -> DesktopFeatureStatus.TIMED_OUT
  report == null && runtime.checking -> DesktopFeatureStatus.CHECKING
  report == null -> DesktopFeatureStatus.UNKNOWN
  supported == false -> DesktopFeatureStatus.UNAVAILABLE
  acknowledged -> DesktopFeatureStatus.ACCEPTED
  supported == null -> DesktopFeatureStatus.UNKNOWN
  else -> DesktopFeatureStatus.PENDING
}

private fun desktopPreferenceStatus(
  inScope: Boolean,
  unavailable: Boolean,
  saving: Boolean,
  failed: Boolean,
  hasSaved: Boolean,
  supported: Boolean?,
): DesktopFeatureStatus? = when {
  !inScope -> DesktopFeatureStatus.OUT_OF_SCOPE
  supported == false -> DesktopFeatureStatus.UNAVAILABLE
  unavailable -> DesktopFeatureStatus.UNAVAILABLE
  saving -> DesktopFeatureStatus.SAVING
  failed -> DesktopFeatureStatus.ERROR
  !hasSaved -> DesktopFeatureStatus.LOADING
  else -> null
}

internal fun desktopRuntimeReport(
  runtime: RuntimeStatusUiState,
  now: Long,
  moduleVersion: Long,
): RuntimeStatusReport? = runtime.report?.takeIf {
  !runtime.readFailed && !runtime.timedOut && it.isFresh(now) && it.moduleVersion == moduleVersion &&
    it.nativeState != NativeRuntimeState.INACTIVE_USER
}

internal fun desktopStatusMessage(status: DesktopFeatureStatus, hasPending: Boolean = false,
  preferenceUnavailable: Boolean = false, unsupportedMessage: Int = R.string.desktop_status_unavailable): Int? =
  when (status) {
    DesktopFeatureStatus.OUT_OF_SCOPE -> R.string.launcher_labels_scope
    DesktopFeatureStatus.LOADING -> R.string.theme_effects_loading
    DesktopFeatureStatus.SAVING -> R.string.theme_effects_saving
    DesktopFeatureStatus.ERROR -> if (hasPending) R.string.theme_effects_save_failed else R.string.theme_effects_read_failed
    DesktopFeatureStatus.UNAVAILABLE -> if (preferenceUnavailable) R.string.theme_effects_unavailable else unsupportedMessage
    DesktopFeatureStatus.UNKNOWN -> R.string.desktop_status_unknown
    DesktopFeatureStatus.CHECKING -> R.string.desktop_status_checking
    DesktopFeatureStatus.READ_FAILED -> R.string.desktop_status_read_failed
    DesktopFeatureStatus.TIMED_OUT -> R.string.desktop_status_timeout
    DesktopFeatureStatus.LAYOUT_UNAVAILABLE -> R.string.dock_glass_layout_unavailable
    DesktopFeatureStatus.HORIZONTAL_PADDING_UNAVAILABLE -> R.string.dock_glass_horizontal_padding_unavailable
    DesktopFeatureStatus.PENDING -> R.string.launcher_glass_pending
    DesktopFeatureStatus.ACCEPTED -> null
  }
