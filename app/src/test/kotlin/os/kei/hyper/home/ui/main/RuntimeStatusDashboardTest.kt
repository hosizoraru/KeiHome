package os.kei.hyper.home.ui.main

import io.github.libxposed.service.HookedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.FrameworkTarget
import os.kei.hyper.home.status.IconRefreshReceipt
import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.NativeRuntimeDiagnostic
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState

class RuntimeStatusDashboardTest {
  private val installedVersion = BuildConfig.VERSION_CODE.toLong()
  private val checkedAt = 100_000L

  private fun report() = RuntimeStatusReport(
    requestId = "authenticated-request",
    userId = 0,
    moduleVersion = installedVersion,
    checkedAtElapsed = checkedAt,
    nativeState = NativeRuntimeState.READY,
    launcherPid = 1234,
    launcherStartTicks = 9876,
  )

  private fun snapshot() = FrameworkSnapshot(
    name = "LSPosed",
    apiVersion = 102,
    systemInScope = true,
    target = FrameworkTarget(HookedTarget.State.UP_TO_DATE, installedVersion),
    launcherTarget = null,
    frameworkVersion = "2.1.4",
    frameworkVersionCode = 7350,
  )

  private fun native(
    state: RuntimeStatusUiState = RuntimeStatusUiState(report = report()),
    now: Long = checkedAt,
  ) = runtimeCardState(state, now, installedVersion)

  private fun presentation(
    framework: FrameworkSnapshot? = snapshot(),
    runtime: RuntimeCardState = native(),
    connected: Boolean = true,
    readFailed: Boolean = false,
  ) = moduleStatusPresentation(frameworkCardState(connected, readFailed, framework), runtime)

  @Test
  fun nativeReadyDoesNotRequireALauncherJavaLoadReportOrARefreshReceipt() {
    val result = presentation()
    assertEquals(R.string.runtime_module_ready, result.title)
    assertEquals(StatusTone.READY, result.tone)
    assertNull(native().report?.refresh)
  }

  @Test
  fun javaLoadingAloneCannotEstablishNativeReadiness() {
    val framework = snapshot().copy(
      launcherTarget = FrameworkTarget(HookedTarget.State.UP_TO_DATE, installedVersion),
    )
    val missing = native(RuntimeStatusUiState())
    assertEquals(NativeStatusKind.NOT_REPORTED, missing.kind)
    assertEquals(R.string.runtime_not_reported, presentation(framework, missing).title)
    assertEquals(StatusTone.WARNING, presentation(framework, missing).tone)
  }

  @Test
  fun aFreshSuccessIsRetainedWhileChecking() {
    val checking = native(RuntimeStatusUiState(report = report(), checking = true), checkedAt + 29_999)
    assertEquals(NativeStatusKind.READY, checking.kind)
    assertEquals(report(), checking.report)
    assertEquals(StatusTone.READY, presentation(runtime = checking).tone)
  }

  @Test
  fun aRetainedReportExpiresAtTheFirstMillisecondPastItsLifetime() {
    val state = RuntimeStatusUiState(report = report())
    assertEquals(NativeStatusKind.READY,
      native(state, checkedAt + RuntimeStatusReport.FRESHNESS_MS).kind)
    val expired = native(state, checkedAt + RuntimeStatusReport.FRESHNESS_MS + 1)
    assertEquals(NativeStatusKind.EXPIRED, expired.kind)
    assertNull(expired.report)
    assertEquals(R.string.runtime_expired, presentation(runtime = expired).title)
    assertEquals(StatusTone.WARNING, presentation(runtime = expired).tone)
  }

  @Test
  fun resumingAQueryCannotKeepAnExpiredOrClearedSuccess() {
    val resumed = native(RuntimeStatusUiState(report = report(), checking = true), checkedAt + 60_000)
    val cleared = native(RuntimeStatusUiState(checking = true), checkedAt + 60_000)
    listOf(resumed, cleared).forEach {
      assertEquals(NativeStatusKind.CHECKING, it.kind)
      assertNull(it.report)
      assertEquals(StatusTone.CHECKING, presentation(runtime = it).tone)
    }
  }

  @Test
  fun aFutureElapsedTimestampCannotProveReadiness() {
    val future = native(now = checkedAt - 1)
    assertEquals(NativeStatusKind.EXPIRED, future.kind)
    assertNull(future.report)
    assertEquals(StatusTone.WARNING, presentation(runtime = future).tone)
  }

  @Test
  fun upgradeAndDowngradeBothRequireMatchingReportingGenerations() {
    listOf(installedVersion - 1, installedVersion + 1).forEach { version ->
      val mismatch = native(RuntimeStatusUiState(report = report().copy(moduleVersion = version)))
      assertEquals(NativeStatusKind.GENERATION_MISMATCH, mismatch.kind)
      assertEquals(version, mismatch.report?.moduleVersion)
      assertEquals(R.string.runtime_generation_mismatch, presentation(runtime = mismatch).title)
      assertEquals(StatusTone.WARNING, presentation(runtime = mismatch).tone)
    }
  }

  @Test
  fun nativeMissingTimeoutReadFailureAndInactiveUserHaveDistinctPresentations() {
    val cases = listOf(
      RuntimeStatusUiState() to NativeStatusKind.NOT_REPORTED,
      RuntimeStatusUiState(checking = true) to NativeStatusKind.CHECKING,
      RuntimeStatusUiState(timedOut = true) to NativeStatusKind.TIMED_OUT,
      RuntimeStatusUiState(readFailed = true) to NativeStatusKind.READ_FAILED,
      RuntimeStatusUiState(report = report().copy(nativeState = NativeRuntimeState.UNAVAILABLE)) to
        NativeStatusKind.UNAVAILABLE,
      RuntimeStatusUiState(report = report().copy(nativeState = NativeRuntimeState.INACTIVE_USER)) to
        NativeStatusKind.INACTIVE_USER,
    )
    cases.forEach { (state, kind) ->
      val result = native(state)
      assertEquals(kind, result.kind)
      assertEquals(kind.label, presentation(runtime = result).title)
      assertEquals(when (kind) {
        NativeStatusKind.READ_FAILED -> StatusTone.ERROR
        NativeStatusKind.CHECKING -> StatusTone.CHECKING
        else -> StatusTone.WARNING
      },
        presentation(runtime = result).tone)
    }
    assertEquals(cases.size, cases.map { presentation(runtime = native(it.first)).title }.toSet().size)
  }

  @Test
  fun queryFailuresInvalidateEvenARecentSuccessAndItsProcessDetails() {
    listOf(
      RuntimeStatusUiState(report = report(), readFailed = true) to NativeStatusKind.READ_FAILED,
      RuntimeStatusUiState(report = report(), timedOut = true) to NativeStatusKind.TIMED_OUT,
    ).forEach { (state, expected) ->
      val result = native(state)
      assertEquals(expected, result.kind)
      assertNull(result.report)
      assertNotEquals(StatusTone.READY, presentation(runtime = result).tone)
    }
  }

  @Test
  fun readinessRequiresAConnectedFrameworkWithReadableStatusAndSystemScope() {
    assertEquals(StatusTone.ERROR, presentation(connected = false).tone)
    assertEquals(StatusTone.ERROR, presentation(readFailed = true).tone)
    assertEquals(StatusTone.CHECKING, presentation(framework = null).tone)
    assertEquals(StatusTone.ERROR, presentation(snapshot().copy(systemInScope = false)).tone)
    assertEquals(StatusTone.WARNING,
      moduleStatusPresentation(frameworkCardState(true, false, snapshot()).copy(systemInScope = null),
        native()).tone)
  }

  @Test
  fun nativeSuccessDoesNotHideSystemReloadFailureOrVersionMismatch() {
    val cases = listOf(
      HookedTarget.State.RELOADING to R.string.status_target_reloading,
      HookedTarget.State.STALE to R.string.polish_status_update_pending,
      HookedTarget.State.FAILED to R.string.status_target_failed,
    )
    cases.forEach { (state, title) ->
      val result = presentation(snapshot().copy(target = FrameworkTarget(state, installedVersion)))
      assertEquals(title, result.title)
      assertEquals(if (state == HookedTarget.State.FAILED) StatusTone.ERROR else StatusTone.WARNING,
        result.tone)
    }
    listOf(installedVersion - 1, installedVersion + 1).forEach { version ->
      val result = presentation(snapshot().copy(target = FrameworkTarget(HookedTarget.State.UP_TO_DATE, version)))
      assertEquals(R.string.polish_status_update_pending, result.title)
      assertEquals(StatusTone.WARNING, result.tone)
    }
    assertEquals(StatusTone.WARNING, presentation(snapshot().copy(target = null)).tone)
  }

  @Test
  fun explicitLauncherJavaFailureRemainsAnErrorEvenWithNativeReady() {
    val result = presentation(snapshot().copy(
      launcherTarget = FrameworkTarget(HookedTarget.State.FAILED, installedVersion),
    ))
    assertEquals(R.string.polish_status_launcher_failed, result.title)
    assertEquals(StatusTone.ERROR, result.tone)
  }

  @Test
  fun optionalJavaReloadAndStaleReportsRemainSeparateFromVerifiedNativeReadiness() {
    listOf(HookedTarget.State.RELOADING, HookedTarget.State.STALE).forEach { javaState ->
      val framework = frameworkCardState(true, false, snapshot().copy(
        launcherTarget = FrameworkTarget(javaState, installedVersion - 1),
      ))
      assertEquals(javaState, framework.launcherState)
      assertEquals(installedVersion - 1, framework.launcherVersion)
      assertEquals(StatusTone.READY, moduleStatusPresentation(framework, native()).tone)
    }
  }

  @Test
  fun callbackReceiptsDoNotPromoteAnUnavailableEndpointOrSurviveExpiry() {
    val receipt = IconRefreshReceipt(callbacks = 0, completedAtMillis = 1_000_000)
    val state = RuntimeStatusUiState(report = report().copy(
      nativeState = NativeRuntimeState.UNAVAILABLE,
      refresh = receipt,
      detail = "Endpoint unavailable",
    ))
    val current = native(state)
    assertEquals(NativeStatusKind.UNAVAILABLE, current.kind)
    assertEquals(StatusTone.WARNING, presentation(runtime = current).tone)
    assertEquals(receipt, current.report?.refresh)
    assertEquals("Endpoint unavailable", current.report?.detail)
    assertNull(native(state, checkedAt + 30_001).report)
  }

  @Test
  fun anAuthenticatedMissingCapabilityIsNotPresentedAsAConnectionWait() {
    val receipt = IconRefreshReceipt(261, 1_000_000)
    val state = RuntimeStatusUiState(report = report().copy(
      nativeState = NativeRuntimeState.UNAVAILABLE,
      launcherPid = null,
      launcherStartTicks = null,
      refresh = receipt,
      detail = NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE,
    ))
    val current = native(state)
    assertEquals(NativeStatusKind.CAPABILITY_MISSING, current.kind)
    assertEquals(R.string.runtime_capability_missing, presentation(runtime = current).title)
    assertEquals(StatusTone.WARNING, presentation(runtime = current).tone)
    assertEquals(receipt, current.report?.refresh)
    assertNull(current.report?.launcherPid)
    assertNull(native(state, checkedAt + 30_001).report)
    assertEquals(NativeStatusKind.TIMED_OUT, native(state.copy(timedOut = true)).kind)
    assertEquals(NativeStatusKind.READ_FAILED, native(state.copy(readFailed = true)).kind)
  }

  @Test
  fun unrelatedFailuresAndOldDiagnosticsCannotMaskARecoveredNativeCapability() {
    listOf(null, "endpoint: deadline exceeded", "native greeting capabilities invalid").forEach { detail ->
      assertEquals(NativeStatusKind.UNAVAILABLE, native(RuntimeStatusUiState(report = report().copy(
        nativeState = NativeRuntimeState.UNAVAILABLE, detail = detail,
      ))).kind)
    }
    assertEquals(NativeStatusKind.READY, native(RuntimeStatusUiState(report = report().copy(
      detail = NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE,
    ))).kind)
  }
}
