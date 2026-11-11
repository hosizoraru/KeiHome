package os.kei.hyper.home.ui.main

import io.github.libxposed.service.HookedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.FrameworkTarget
import os.kei.hyper.home.status.NativeRuntimeDiagnostic
import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState

class DesktopConnectionCardTest {
  private val version = 123L
  private val now = 100_000L
  private val target = FrameworkTarget(HookedTarget.State.UP_TO_DATE, version)
  private val snapshot = FrameworkSnapshot(name = "LSPosed", apiVersion = 102, systemInScope = true,
    target = target, launcherTarget = null, grantedScope = setOf("system", "com.miui.home"))
  private val framework = FrameworkUiState(connected = true, snapshot = snapshot)
  private val report = RuntimeStatusReport("request", 0, version, now, NativeRuntimeState.READY,
    4200, 37L)
  private val runtime = RuntimeStatusUiState(report = report)

  private fun present(
    framework: FrameworkUiState = this.framework,
    runtime: RuntimeStatusUiState = this.runtime,
    at: Long = now,
  ) = desktopConnectionPresentation(framework, runtime, at, version)

  private fun withSystem(state: HookedTarget.State, loadedVersion: Long = version) =
    framework.copy(snapshot = snapshot.copy(target = target.copy(state = state, loadedVersionCode = loadedVersion)))

  private fun withLauncher(state: HookedTarget.State, loadedVersion: Long = version) =
    framework.copy(snapshot = snapshot.copy(launcherTarget = FrameworkTarget(state, loadedVersion)))

  @Test fun freshNativeConnectionDoesNotRequireAnOptionalJavaTarget() {
    val state = present()
    assertEquals(DesktopConnectionStatus.READY, state.primary)
    assertEquals(StatusTone.READY, state.tone)
    assertEquals(DesktopConnectionStatus.READY, state.native)
    assertNull(state.launcherJava)
  }

  @Test fun disconnectOverridesRetainedSnapshotsRepliesAndAllFailureFlags() {
    val state = present(framework.copy(connected = false, readFailed = true),
      runtime.copy(readFailed = true, timedOut = true))
    assertEquals(DesktopConnectionStatus.DISCONNECTED, state.primary)
    assertEquals(DesktopConnectionStatus.WAITING, state.system)
    assertEquals(DesktopConnectionStatus.WAITING, state.native)
    assertNull(state.launcherJava)
  }

  @Test fun frameworkReadFailureCannotReuseAnyHealthySnapshotBadge() {
    val state = present(framework.copy(readFailed = true))
    assertEquals(DesktopConnectionStatus.READ_FAILED, state.primary)
    assertEquals(DesktopConnectionStatus.WAITING, state.system)
    assertEquals(DesktopConnectionStatus.WAITING, state.native)
  }

  @Test fun absentSnapshotWaitsEvenWithAFreshNativeReply() {
    assertEquals(DesktopConnectionStatus.WAITING, present(framework.copy(snapshot = null)).primary)
  }

  @Test fun eitherMissingScopeBlocksRetainedNativeSuccessAndLoadFailure() {
    for (scope in listOf(setOf("system"), setOf("com.miui.home"), emptySet())) {
      val state = present(framework.copy(snapshot = snapshot.copy(grantedScope = scope,
        systemInScope = "system" in scope, target = target.copy(state = HookedTarget.State.FAILED))))
      assertEquals(DesktopConnectionStatus.SCOPE_MISSING, state.primary)
      assertEquals(DesktopConnectionStatus.WAITING, state.native)
    }
  }

  @Test fun loadFailureWinsOverNativeTimeoutOrReadFailure() {
    for (failed in listOf(withSystem(HookedTarget.State.FAILED), withLauncher(HookedTarget.State.FAILED))) {
      assertEquals(DesktopConnectionStatus.FAILED,
        present(failed, runtime.copy(readFailed = true, timedOut = true)).primary)
    }
  }

  @Test fun nativeReadFailureThenTimeoutOverrideRetainedReadyReplyAndReloadWait() {
    val reloading = withSystem(HookedTarget.State.RELOADING)
    assertEquals(DesktopConnectionStatus.READ_FAILED,
      present(reloading, runtime.copy(readFailed = true, timedOut = true)).primary)
    val timedOut = present(runtime = runtime.copy(timedOut = true))
    assertEquals(DesktopConnectionStatus.TIMED_OUT, timedOut.primary)
    assertEquals(StatusTone.ERROR, timedOut.tone)
    assertEquals(DesktopConnectionStatus.TIMED_OUT, timedOut.native)
  }

  @Test fun everyNonCurrentSystemStatePreventsGreenNativeBadges() {
    for ((targetState, status) in listOf(
      HookedTarget.State.STALE to DesktopConnectionStatus.STALE,
      HookedTarget.State.RELOADING to DesktopConnectionStatus.RELOADING,
      HookedTarget.State.FAILED to DesktopConnectionStatus.FAILED,
    )) {
      val state = present(withSystem(targetState))
      assertEquals(status, state.primary)
      assertEquals(DesktopConnectionStatus.WAITING, state.native)
      assertNotEquals(StatusTone.READY, state.tone)
    }
    assertEquals(DesktopConnectionStatus.WAITING,
      present(framework.copy(snapshot = snapshot.copy(target = null))).primary)
  }

  @Test fun upToDateStillRequiresTheInstalledModuleGeneration() {
    val system = present(withSystem(HookedTarget.State.UP_TO_DATE, version - 1))
    assertEquals(DesktopConnectionStatus.GENERATION_MISMATCH, system.primary)
    assertEquals(DesktopConnectionStatus.WAITING, system.native)
    assertEquals(DesktopConnectionStatus.GENERATION_MISMATCH,
      present(withLauncher(HookedTarget.State.UP_TO_DATE, version - 1)).primary)
  }

  @Test fun explicitOptionalJavaFaultsAreVisibleAndBlockReady() {
    for (state in listOf(HookedTarget.State.STALE, HookedTarget.State.RELOADING, HookedTarget.State.FAILED)) {
      val result = present(withLauncher(state))
      assertNotEquals(StatusTone.READY, result.tone)
      assertEquals(result.primary, result.launcherJava)
      assertEquals(DesktopConnectionStatus.WAITING, result.native)
    }
    assertNull(present(withLauncher(HookedTarget.State.UP_TO_DATE)).launcherJava)
  }

  @Test fun noReplyDistinguishesAnActiveCheckFromAnIdleWait() {
    val waiting = present(runtime = RuntimeStatusUiState())
    assertEquals(DesktopConnectionStatus.NOT_REPORTED, waiting.primary)
    assertNull(waiting.native.tone)
    assertEquals(DesktopConnectionStatus.CHECKING,
      present(runtime = RuntimeStatusUiState(checking = true)).primary)
  }

  @Test fun freshnessBoundaryAndFutureTimestampsCannotStayGreen() {
    assertEquals(DesktopConnectionStatus.READY, present(at = now + RuntimeStatusReport.FRESHNESS_MS).primary)
    assertEquals(DesktopConnectionStatus.EXPIRED, present(at = now + RuntimeStatusReport.FRESHNESS_MS + 1).primary)
    assertEquals(DesktopConnectionStatus.EXPIRED, present(at = now - 1).primary)
    assertEquals(DesktopConnectionStatus.EXPIRED,
      present(runtime = runtime.copy(checking = true), at = now + RuntimeStatusReport.FRESHNESS_MS + 1).primary)
  }

  @Test fun refreshingDoesNotDiscardACurrentVerifiedReply() {
    assertEquals(DesktopConnectionStatus.READY, present(runtime = runtime.copy(checking = true)).primary)
  }

  @Test fun nativeGenerationMismatchTakesPriorityOverUnsupportedCapability() {
    assertEquals(DesktopConnectionStatus.GENERATION_MISMATCH, present(runtime = runtime.copy(report =
      report.copy(moduleVersion = version - 1, nativeState = NativeRuntimeState.UNAVAILABLE,
        detail = NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE))).primary)
  }

  @Test fun unsupportedNativeUnavailableAndInactiveUserStayDistinct() {
    for ((native, reason, expected) in listOf(
      Triple(NativeRuntimeState.UNAVAILABLE, NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE,
        DesktopConnectionStatus.NATIVE_UNSUPPORTED),
      Triple(NativeRuntimeState.UNAVAILABLE, null, DesktopConnectionStatus.NATIVE_UNAVAILABLE),
      Triple(NativeRuntimeState.INACTIVE_USER, null, DesktopConnectionStatus.INACTIVE_USER),
    )) {
      assertEquals(expected, present(runtime = runtime.copy(report =
        report.copy(nativeState = native, detail = reason))).primary)
    }
  }

  @Test fun readyWithoutProcessIdentityCannotVerifyANativeConnection() {
    for (invalid in listOf(report.copy(launcherPid = null), report.copy(launcherPid = 0),
      report.copy(launcherStartTicks = null), report.copy(launcherStartTicks = 0))) {
      assertEquals(DesktopConnectionStatus.NATIVE_UNAVAILABLE,
        present(runtime = runtime.copy(report = invalid)).primary)
    }
  }

  @Test fun categoryConfigurationAndCapabilityBitsDoNotDefineConnectionReadiness() {
    assertEquals(DesktopConnectionStatus.READY, present(runtime = runtime.copy(report = report.copy(
      launcherCapabilities = 0, dockGlassEnabled = false, launcherGlassAvailable = false,
      launcherLabelMode = -1,
    ))).primary)
  }
}
