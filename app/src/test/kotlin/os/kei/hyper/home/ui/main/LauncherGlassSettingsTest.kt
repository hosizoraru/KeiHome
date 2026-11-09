package os.kei.hyper.home.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test
import os.kei.hyper.home.data.LauncherGlassStyle
import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.NativeRuntimeDiagnostic
import os.kei.hyper.home.status.LauncherRuntimeCapabilities
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState

class LauncherGlassSettingsTest {
  private val now = 100_000L
  private val version = 123L
  private val report = RuntimeStatusReport("request", 0, version, now, NativeRuntimeState.UNAVAILABLE,
    launcherGlassAvailable = true, launcherPid = 9700, launcherStartTicks = 3210,
    launcherCapabilities = LauncherRuntimeCapabilities.KNOWN)

  private fun status(style: LauncherGlassStyle? = LauncherGlassStyle.DARK,
    state: RuntimeStatusUiState = RuntimeStatusUiState(report = report), at: Long = now) =
    launcherGlassRuntimeStatus(style, state, at, version)

  @Test fun savedChoiceAndCapabilityWithoutAnAckRemainPending() {
    assertEquals(LauncherGlassRuntimeStatus.PENDING, status())
    assertEquals(LauncherGlassRuntimeStatus.PENDING, status(LauncherGlassStyle.SYSTEM))
    assertEquals(LauncherGlassRuntimeStatus.PENDING, status(null))
    assertEquals(LauncherGlassRuntimeStatus.PENDING, status(state = RuntimeStatusUiState()))
    assertEquals(LauncherGlassRuntimeStatus.UNAVAILABLE,
      status(state = RuntimeStatusUiState(report = report.copy(launcherGlassAvailable = false,
        launcherCapabilities = LauncherRuntimeCapabilities.LABELS))))
    assertEquals(LauncherGlassRuntimeStatus.PENDING,
      status(state = RuntimeStatusUiState(report = report.copy(launcherGlassAvailable = false,
        launcherCapabilities = null))))
  }

  @Test fun onlyTheExactAcknowledgedStyleIsAcceptedEvenWhenMonetIsUnavailable() {
    for (style in LauncherGlassStyle.entries) {
      val state = RuntimeStatusUiState(report = report.copy(launcherGlassStyle = style.nativeValue))
      assertEquals(LauncherGlassRuntimeStatus.ACCEPTED, status(style, state))
      assertEquals(LauncherGlassRuntimeStatus.PENDING, status(null, state))
      assertEquals(LauncherGlassRuntimeStatus.PENDING,
        status(LauncherGlassStyle.entries[(style.ordinal + 1) % 3], state))
    }
  }

  @Test fun authenticatedDiagnosticOnlyZeroIsUnavailableWhileANullGreetingRemainsPending() {
    val zero = report.copy(launcherCapabilities = 0, launcherGlassAvailable = false,
      detail = NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE)
    val state = RuntimeStatusUiState(report = requireNotNull(RuntimeStatusReport.decode(zero.encode())))
    for (style in LauncherGlassStyle.entries)
      assertEquals(LauncherGlassRuntimeStatus.UNAVAILABLE, status(style, state))
    assertEquals(LauncherGlassRuntimeStatus.PENDING,
      status(state = RuntimeStatusUiState(report = zero.copy(launcherCapabilities = null))))
    assertEquals(LauncherGlassRuntimeStatus.PENDING, status(state = state.copy(readFailed = true)))
    assertEquals(LauncherGlassRuntimeStatus.PENDING, status(state = state.copy(timedOut = true)))
  }

  @Test fun freshnessGenerationAndQueryFailuresCannotReuseAnAcceptedReceipt() {
    val accepted = report.copy(launcherGlassStyle = 2)
    assertEquals(LauncherGlassRuntimeStatus.ACCEPTED,
      status(state = RuntimeStatusUiState(report = accepted, checking = true)))
    for (state in listOf(
      RuntimeStatusUiState(report = accepted, readFailed = true),
      RuntimeStatusUiState(report = accepted, timedOut = true),
      RuntimeStatusUiState(report = accepted.copy(moduleVersion = version - 1)),
      RuntimeStatusUiState(report = accepted.copy(moduleVersion = version + 1)),
      RuntimeStatusUiState(report = accepted.copy(nativeState = NativeRuntimeState.INACTIVE_USER)),
    )) assertEquals(LauncherGlassRuntimeStatus.PENDING, status(state = state))
    assertEquals(LauncherGlassRuntimeStatus.PENDING,
      status(state = RuntimeStatusUiState(report = accepted), at = now - 1))
    assertEquals(LauncherGlassRuntimeStatus.PENDING,
      status(state = RuntimeStatusUiState(report = accepted), at = now + RuntimeStatusReport.FRESHNESS_MS + 1))
  }
}
