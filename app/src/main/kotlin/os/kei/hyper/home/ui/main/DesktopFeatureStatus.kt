package os.kei.hyper.home.ui.main

import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState

internal enum class DesktopFeatureStatus { OUT_OF_SCOPE, LOADING, SAVING, ERROR, UNAVAILABLE, PENDING, ACCEPTED }

internal fun desktopDockStatus(
  state: DockGlassUiState,
  runtime: RuntimeStatusUiState,
  inScope: Boolean,
  now: Long,
  moduleVersion: Long,
): DesktopFeatureStatus {
  desktopPreferenceStatus(inScope, state.unavailable, state.saving, state.failed, state.saved != null)
    ?.let { return it }
  val report = desktopRuntimeReport(runtime, now, moduleVersion)
  // Dock has its own process-bound ACK, independent of Monet readiness.
  return if (report?.acknowledgesDockGlass(state.saved) == true) DesktopFeatureStatus.ACCEPTED
    else DesktopFeatureStatus.PENDING
}

internal fun desktopLabelStatus(
  state: LauncherLabelUiState,
  runtime: RuntimeStatusUiState,
  inScope: Boolean,
  now: Long,
  moduleVersion: Long,
): DesktopFeatureStatus {
  desktopPreferenceStatus(inScope, state.unavailable, state.saving, state.failed, state.saved != null)
    ?.let { return it }
  val report = desktopRuntimeReport(runtime, now, moduleVersion)
  // The producer emits this field only for a current process-validated ACK, including NATIVE.
  return if (report != null && report.launcherLabelMode == state.saved?.nativeValue)
    DesktopFeatureStatus.ACCEPTED else DesktopFeatureStatus.PENDING
}

internal fun desktopGlassStatus(
  state: LauncherGlassUiState,
  runtime: RuntimeStatusUiState,
  inScope: Boolean,
  now: Long,
  moduleVersion: Long,
): DesktopFeatureStatus {
  desktopPreferenceStatus(inScope, state.unavailable, state.saving, state.failed, state.saved != null)
    ?.let { return it }
  return when (launcherGlassRuntimeStatus(state.saved, runtime, now, moduleVersion)) {
    LauncherGlassRuntimeStatus.UNAVAILABLE -> DesktopFeatureStatus.UNAVAILABLE
    LauncherGlassRuntimeStatus.PENDING -> DesktopFeatureStatus.PENDING
    LauncherGlassRuntimeStatus.ACCEPTED -> DesktopFeatureStatus.ACCEPTED
  }
}

private fun desktopPreferenceStatus(
  inScope: Boolean,
  unavailable: Boolean,
  saving: Boolean,
  failed: Boolean,
  hasSaved: Boolean,
): DesktopFeatureStatus? = when {
  !inScope -> DesktopFeatureStatus.OUT_OF_SCOPE
  unavailable -> DesktopFeatureStatus.UNAVAILABLE
  saving -> DesktopFeatureStatus.SAVING
  failed -> DesktopFeatureStatus.ERROR
  !hasSaved -> DesktopFeatureStatus.LOADING
  else -> null
}

private fun desktopRuntimeReport(
  runtime: RuntimeStatusUiState,
  now: Long,
  moduleVersion: Long,
): RuntimeStatusReport? = runtime.report?.takeIf {
  !runtime.readFailed && !runtime.timedOut && it.isFresh(now) && it.moduleVersion == moduleVersion &&
    it.nativeState != NativeRuntimeState.INACTIVE_USER
}
