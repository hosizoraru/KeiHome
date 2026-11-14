package os.kei.hyper.home.ui.main

import os.kei.hyper.home.R
import os.kei.hyper.home.status.RuntimeStatusUiState
import os.kei.hyper.home.status.StaticScopeUiState
import org.junit.Assert.*
import org.junit.Test

class StartupStatusPresentationTest {
  private fun module(connected: Boolean, checking: Boolean, failed: Boolean = false) =
    moduleStatusPresentation(frameworkCardState(connected, failed, null, checking),
      runtimeCardState(RuntimeStatusUiState(), 1L))

  @Test fun pendingBindAndPendingSnapshotAreNeutral() {
    listOf(module(false, true), module(true, true), module(true, false)).forEach {
      assertEquals(StatusTone.CHECKING, it.tone)
      assertEquals(R.string.runtime_checking, it.title)
    }
  }

  @Test fun confirmedDisconnectionAndReadFailureRemainErrors() {
    assertEquals(StatusTone.ERROR, module(false, false).tone)
    assertEquals(StatusTone.ERROR, module(true, true, failed = true).tone)
    assertEquals(StatusTone.ERROR, module(false, true, failed = true).tone)
  }

  @Test fun desktopConnectionDistinguishesPendingFromConfirmedDisconnected() {
    val pending = desktopConnectionPresentation(FrameworkUiState(checking = true), RuntimeStatusUiState(), 1L)
    assertEquals(DesktopConnectionStatus.CHECKING, pending.primary)
    assertEquals(StatusTone.CHECKING, pending.tone)
    val disconnected = desktopConnectionPresentation(FrameworkUiState(), RuntimeStatusUiState(), 1L)
    assertEquals(DesktopConnectionStatus.DISCONNECTED, disconnected.primary)
    assertEquals(StatusTone.ERROR, disconnected.tone)
  }

  @Test fun scopeSummaryWaitsWithoutLosingFailureSeverity() {
    listOf(StaticScopeUiState(checking = true), StaticScopeUiState(connected = true)).forEach {
      assertEquals(StatusTone.CHECKING, scopeStatusPresentation(it).tone)
    }
    assertEquals(StatusTone.ERROR, scopeStatusPresentation(StaticScopeUiState()).tone)
    assertEquals(StatusTone.ERROR, scopeStatusPresentation(
      StaticScopeUiState(connected = true, checking = true, readFailed = true)).tone)
    assertFalse(StaticScopeUiState(checking = true).needsAttention)
  }
}
