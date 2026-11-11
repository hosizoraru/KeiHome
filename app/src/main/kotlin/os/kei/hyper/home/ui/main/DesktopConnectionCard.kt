package os.kei.hyper.home.ui.main

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.libxposed.service.HookedTarget
import kotlinx.coroutines.delay
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.status.FrameworkTarget
import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class DesktopConnectionStatus(
  @param:StringRes val label: Int,
  @param:StringRes val summary: Int,
  val tone: StatusTone?,
) {
  CONNECTED(R.string.desktop_connection_connected, R.string.desktop_connection_ready_summary, StatusTone.READY),
  READY(R.string.desktop_connection_ready, R.string.desktop_connection_ready_summary, StatusTone.READY),
  DISCONNECTED(R.string.desktop_connection_disconnected, R.string.desktop_connection_disconnected_summary, StatusTone.ERROR),
  READ_FAILED(R.string.desktop_connection_read_failed, R.string.desktop_connection_read_failed_summary, StatusTone.ERROR),
  WAITING(R.string.desktop_connection_waiting, R.string.desktop_connection_waiting_summary, null),
  SCOPE_MISSING(R.string.desktop_connection_scope_missing, R.string.desktop_connection_scope_missing_summary, StatusTone.WARNING),
  FAILED(R.string.desktop_connection_failed, R.string.desktop_connection_failed_summary, StatusTone.ERROR),
  RELOADING(R.string.desktop_connection_reloading, R.string.desktop_connection_reloading_summary, StatusTone.WARNING),
  STALE(R.string.desktop_connection_stale, R.string.desktop_connection_stale_summary, StatusTone.WARNING),
  GENERATION_MISMATCH(R.string.desktop_connection_generation_mismatch, R.string.desktop_connection_generation_summary, StatusTone.WARNING),
  CHECKING(R.string.desktop_connection_checking, R.string.desktop_connection_checking_summary, null),
  NOT_REPORTED(R.string.desktop_connection_not_reported, R.string.desktop_connection_not_reported_summary, null),
  EXPIRED(R.string.desktop_connection_expired, R.string.desktop_connection_expired_summary, StatusTone.WARNING),
  TIMED_OUT(R.string.desktop_connection_timed_out, R.string.desktop_connection_timed_out_summary, StatusTone.ERROR),
  NATIVE_UNSUPPORTED(R.string.desktop_connection_native_unsupported, R.string.desktop_connection_native_unsupported_summary, StatusTone.WARNING),
  NATIVE_UNAVAILABLE(R.string.desktop_connection_native_unavailable, R.string.desktop_connection_native_unavailable_summary, StatusTone.WARNING),
  INACTIVE_USER(R.string.desktop_connection_inactive_user, R.string.desktop_connection_inactive_user_summary, StatusTone.WARNING),
}

internal data class DesktopConnectionPresentation(
  val primary: DesktopConnectionStatus,
  val framework: DesktopConnectionStatus,
  val system: DesktopConnectionStatus,
  val native: DesktopConnectionStatus,
  val launcherJava: DesktopConnectionStatus? = null,
) {
  val tone: StatusTone get() = primary.tone ?: StatusTone.WARNING
}

/** Connection evidence only: no saved preferences, feature ACKs or category capabilities. */
internal fun desktopConnectionPresentation(
  framework: FrameworkUiState,
  runtime: RuntimeStatusUiState,
  now: Long,
  installedVersion: Long = BuildConfig.VERSION_CODE.toLong(),
): DesktopConnectionPresentation {
  val frameworkStatus = when {
    !framework.connected -> DesktopConnectionStatus.DISCONNECTED
    framework.readFailed -> DesktopConnectionStatus.READ_FAILED
    framework.snapshot == null -> DesktopConnectionStatus.WAITING
    else -> DesktopConnectionStatus.CONNECTED
  }
  // A retained snapshot/report cannot supply healthy badges after the service disappears.
  val snapshot = framework.snapshot.takeIf { frameworkStatus == DesktopConnectionStatus.CONNECTED }
    ?: return DesktopConnectionPresentation(frameworkStatus, frameworkStatus,
      DesktopConnectionStatus.WAITING, DesktopConnectionStatus.WAITING)
  val inScope = snapshot.systemInScope && "com.miui.home" in snapshot.grantedScope
  val system = if (inScope) desktopTargetConnectionStatus(snapshot.target, installedVersion)
    else DesktopConnectionStatus.SCOPE_MISSING
  // The native Launcher may legitimately have no Java target. Explicit faults still matter.
  val launcher = snapshot.launcherTarget?.let { desktopTargetConnectionStatus(it, installedVersion) }
    ?.takeUnless { it == DesktopConnectionStatus.READY }
  val native = if (inScope) desktopNativeConnectionStatus(runtime, now, installedVersion)
    else DesktopConnectionStatus.WAITING
  val primary = when {
    !inScope -> DesktopConnectionStatus.SCOPE_MISSING
    system == DesktopConnectionStatus.FAILED || launcher == DesktopConnectionStatus.FAILED -> DesktopConnectionStatus.FAILED
    native == DesktopConnectionStatus.READ_FAILED || native == DesktopConnectionStatus.TIMED_OUT -> native
    system != DesktopConnectionStatus.READY -> system
    launcher != null -> launcher
    else -> native
  }
  return DesktopConnectionPresentation(primary, frameworkStatus, system,
    native.takeUnless { it == DesktopConnectionStatus.READY && primary != DesktopConnectionStatus.READY }
      ?: DesktopConnectionStatus.WAITING, launcher)
}

private fun desktopTargetConnectionStatus(target: FrameworkTarget?, installedVersion: Long): DesktopConnectionStatus =
  when (target?.state) {
    null -> DesktopConnectionStatus.WAITING
    HookedTarget.State.FAILED -> DesktopConnectionStatus.FAILED
    HookedTarget.State.RELOADING -> DesktopConnectionStatus.RELOADING
    HookedTarget.State.STALE -> DesktopConnectionStatus.STALE
    HookedTarget.State.UP_TO_DATE -> if (target.loadedVersionCode == installedVersion) DesktopConnectionStatus.READY
      else DesktopConnectionStatus.GENERATION_MISMATCH
  }

private fun desktopNativeConnectionStatus(
  runtime: RuntimeStatusUiState,
  now: Long,
  installedVersion: Long,
): DesktopConnectionStatus {
  if (runtime.readFailed) return DesktopConnectionStatus.READ_FAILED
  if (runtime.timedOut) return DesktopConnectionStatus.TIMED_OUT
  val report = runtime.report ?: return if (runtime.checking) DesktopConnectionStatus.CHECKING
    else DesktopConnectionStatus.NOT_REPORTED
  if (!report.isFresh(now)) return DesktopConnectionStatus.EXPIRED
  if (report.moduleVersion != installedVersion) return DesktopConnectionStatus.GENERATION_MISMATCH
  return when (report.nativeState) {
    NativeRuntimeState.INACTIVE_USER -> DesktopConnectionStatus.INACTIVE_USER
    NativeRuntimeState.UNAVAILABLE -> if (report.hasMissingMonetCapability) DesktopConnectionStatus.NATIVE_UNSUPPORTED
      else DesktopConnectionStatus.NATIVE_UNAVAILABLE
    NativeRuntimeState.READY -> if ((report.launcherPid ?: 0) > 0 && (report.launcherStartTicks ?: 0) > 0)
      DesktopConnectionStatus.READY else DesktopConnectionStatus.NATIVE_UNAVAILABLE
  }
}

@Composable
internal fun DesktopConnectionCard(model: HomeViewModel) {
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.homeScreenRuntime.collectAsStateWithLifecycle()
  val refreshResults by model.refreshResults.collectAsStateWithLifecycle()
  val launcherVersion by rememberLauncherVersionLabel(refreshResults[5]?.completedAt)
  val checkedAt = runtime.report?.checkedAtElapsed
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var elapsed by remember(checkedAt) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
  // One expiry wakeup per report, repeated on resume; this never queries PackageManager.
  LaunchedEffect(checkedAt, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      val now = SystemClock.elapsedRealtime()
      elapsed = now
      if (checkedAt != null && now >= checkedAt && now - checkedAt <= RuntimeStatusReport.FRESHNESS_MS) {
        delay(RuntimeStatusReport.FRESHNESS_MS - (now - checkedAt) + 1)
        elapsed = SystemClock.elapsedRealtime()
      }
    }
  }
  val state = desktopConnectionPresentation(framework, runtime, maxOf(elapsed, SystemClock.elapsedRealtime()))
  StatusOverviewCard(
    title = stringResource(R.string.desktop_connection_title),
    summary = stringResource(state.primary.summary),
    details = emptyList(),
    tone = state.tone,
    onRefresh = { model.refresh(5) },
    modifier = Modifier.testTag("desktop.status"), compactSymbol = true,
    badgeContent = {
      DesktopConnectionPill(R.string.navigation_desktop, state.primary, "status")
      MetadataPill(launcherVersion ?: stringResource(R.string.desktop_connection_version_unknown),
        MiuixTheme.colorScheme.onSurfaceSecondary, Modifier.testTag("desktop.connection.version"))
    },
  )
}

@Composable
private fun DesktopConnectionPill(@StringRes label: Int, status: DesktopConnectionStatus, tag: String) {
  MetadataPill(stringResource(R.string.desktop_connection_badge, stringResource(label), stringResource(status.label)),
    desktopStatusColor(status.tone), Modifier.testTag("desktop.connection.$tag"))
}

@Composable
internal fun desktopStatusColor(tone: StatusTone?): Color = when (tone) {
  StatusTone.READY -> colorResource(if (LocalKeiHomeDarkTheme.current)
    R.color.status_hero_loaded_accent_dark else R.color.status_hero_loaded_accent)
  StatusTone.WARNING -> colorResource(if (LocalKeiHomeDarkTheme.current)
    R.color.status_hero_warning_accent_dark else R.color.status_hero_warning_accent)
  StatusTone.ERROR -> MiuixTheme.colorScheme.error
  null -> MiuixTheme.colorScheme.onSurfaceSecondary
}
