package os.kei.hyper.home.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test
import os.kei.hyper.home.data.DockGlassChoice
import os.kei.hyper.home.data.DockGlassCornerStyle
import os.kei.hyper.home.data.LauncherGlassStyle
import os.kei.hyper.home.data.LauncherLabelMode
import os.kei.hyper.home.status.NativeRuntimeDiagnostic
import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState

class DesktopFeatureStatusTest {
  private val now = 100_000L
  private val version = 123L
  private val dock = DockGlassUiState(saved = DockGlassChoice(enabled = true))
  private val labels = LauncherLabelUiState(saved = LauncherLabelMode.TWO_LINES)
  private val glass = LauncherGlassUiState(saved = LauncherGlassStyle.DARK)
  private val report = RuntimeStatusReport(
    requestId = "authenticated-request",
    userId = 0,
    moduleVersion = version,
    checkedAtElapsed = now,
    nativeState = NativeRuntimeState.READY,
    launcherPid = 9700,
    launcherStartTicks = 3210,
    launcherLabelMode = LauncherLabelMode.TWO_LINES.nativeValue,
    dockGlassEnabled = true,
    dockGlassPid = 9700,
    dockGlassStartTicks = 3210,
    dockGlassCornerStyle = DockGlassCornerStyle.RECENTS.nativeValue,
    launcherGlassAvailable = true,
    launcherGlassStyle = LauncherGlassStyle.DARK.nativeValue,
  )
  private val runtime = RuntimeStatusUiState(report = report)

  private fun assertStatuses(
    expected: DesktopFeatureStatus,
    runtime: RuntimeStatusUiState = this.runtime,
    inScope: Boolean = true,
    at: Long = now,
    moduleVersion: Long = version,
    dock: DockGlassUiState = this.dock,
    labels: LauncherLabelUiState = this.labels,
    glass: LauncherGlassUiState = this.glass,
  ) {
    assertEquals(listOf(expected, expected, expected), listOf(
      desktopDockStatus(dock, runtime, inScope, at, moduleVersion),
      desktopLabelStatus(labels, runtime, inScope, at, moduleVersion),
      desktopGlassStatus(glass, runtime, inScope, at, moduleVersion),
    ))
  }

  @Test fun matchingNativeConfigurationAcceptsAllFeatures() {
    assertStatuses(DesktopFeatureStatus.ACCEPTED)
    assertStatuses(DesktopFeatureStatus.ACCEPTED, runtime.copy(checking = true))
  }

  @Test fun scopeOverridesEvenAnAcceptedReceiptAndPreferenceFailures() {
    assertStatuses(DesktopFeatureStatus.OUT_OF_SCOPE, inScope = false)
    assertStatuses(DesktopFeatureStatus.OUT_OF_SCOPE, inScope = false,
      dock = dock.copy(saved = null, unavailable = true, saving = true, failed = true),
      labels = labels.copy(saved = null, unavailable = true, saving = true, failed = true),
      glass = glass.copy(saved = null, unavailable = true, saving = true, failed = true))
  }

  @Test fun unavailablePreferencesTakePriorityOverSavedReceiptsAndOtherPreferenceStates() {
    assertStatuses(DesktopFeatureStatus.UNAVAILABLE,
      dock = dock.copy(unavailable = true, saving = true, failed = true),
      labels = labels.copy(unavailable = true, saving = true, failed = true),
      glass = glass.copy(unavailable = true, saving = true, failed = true))
  }

  @Test fun savingDoesNotReuseThePreviouslyAcceptedSavedChoiceOrAnOldFailure() {
    assertStatuses(DesktopFeatureStatus.SAVING,
      dock = dock.copy(saving = true, failed = true, pending = dock.saved!!.copy(enabled = false)),
      labels = labels.copy(saving = true, failed = true, pending = LauncherLabelMode.NATIVE),
      glass = glass.copy(saving = true, failed = true, pending = LauncherGlassStyle.SYSTEM))
  }

  @Test fun failedSavesCannotFallBackToPreviouslyAcceptedSavedChoices() {
    assertStatuses(DesktopFeatureStatus.ERROR,
      dock = dock.copy(failed = true, pending = dock.saved!!.copy(enabled = false)),
      labels = labels.copy(failed = true, pending = LauncherLabelMode.NATIVE),
      glass = glass.copy(failed = true, pending = LauncherGlassStyle.SYSTEM))
  }

  @Test fun failedPreferenceReadsAreErrorsEvenWithoutAnySavedChoice() {
    assertStatuses(DesktopFeatureStatus.ERROR,
      dock = DockGlassUiState(failed = true),
      labels = LauncherLabelUiState(failed = true),
      glass = LauncherGlassUiState(failed = true))
    assertStatuses(DesktopFeatureStatus.ERROR,
      dock = dock.copy(failed = true),
      labels = labels.copy(failed = true),
      glass = glass.copy(failed = true))
  }

  @Test fun unreadPreferencesRemainLoadingEvenWithNativeReceipts() {
    assertStatuses(DesktopFeatureStatus.LOADING,
      dock = DockGlassUiState(), labels = LauncherLabelUiState(), glass = LauncherGlassUiState())
  }

  @Test fun savedPreferencesAndAReceiverGreetingAloneStayPending() {
    for (state in listOf(
      RuntimeStatusUiState(),
      RuntimeStatusUiState(checking = true),
      runtime.copy(report = report.copy(launcherLabelMode = null, dockGlassEnabled = null,
        dockGlassPid = null, dockGlassStartTicks = null, dockGlassCornerStyle = null, launcherGlassStyle = null)),
    )) assertStatuses(DesktopFeatureStatus.PENDING, state)
  }

  @Test fun retainedReceiptsExpireAndCannotSurviveAFutureElapsedTimestamp() {
    assertStatuses(DesktopFeatureStatus.ACCEPTED, at = now + RuntimeStatusReport.FRESHNESS_MS)
    assertStatuses(DesktopFeatureStatus.PENDING, at = now + RuntimeStatusReport.FRESHNESS_MS + 1)
    assertStatuses(DesktopFeatureStatus.PENDING, at = now - 1)
    assertStatuses(DesktopFeatureStatus.PENDING, runtime.copy(checking = true),
      at = now + RuntimeStatusReport.FRESHNESS_MS + 1)
  }

  @Test fun upgradesAndDowngradesBothRequireTheCurrentModuleVersion() {
    for (installedVersion in listOf(version - 1, version + 1))
      assertStatuses(DesktopFeatureStatus.PENDING, moduleVersion = installedVersion)
  }

  @Test fun queryReadFailureOrTimeoutInvalidatesEveryRetainedReceipt() {
    for (state in listOf(
      runtime.copy(readFailed = true),
      runtime.copy(timedOut = true),
      runtime.copy(readFailed = true, checking = true),
      runtime.copy(timedOut = true, checking = true),
    )) assertStatuses(DesktopFeatureStatus.PENDING, state)
  }

  @Test fun inactiveUserCannotAcceptAnyFeatureEvenWithMatchingAcknowledgements() {
    assertStatuses(DesktopFeatureStatus.PENDING,
      runtime.copy(report = report.copy(nativeState = NativeRuntimeState.INACTIVE_USER)))
  }

  @Test fun featureAcknowledgementsRemainValidWhenOnlyMonetIsUnavailable() {
    assertStatuses(DesktopFeatureStatus.ACCEPTED, runtime.copy(report = report.copy(
      nativeState = NativeRuntimeState.UNAVAILABLE,
      detail = NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE)))
  }

  @Test fun labelAcknowledgementsMustMatchEveryModeIncludingNative() {
    for (mode in LauncherLabelMode.entries) {
      val saved = LauncherLabelUiState(saved = mode)
      for (actual in LauncherLabelMode.entries) {
        val state = runtime.copy(report = report.copy(launcherLabelMode = actual.nativeValue))
        assertEquals(if (mode == actual) DesktopFeatureStatus.ACCEPTED else DesktopFeatureStatus.PENDING,
          desktopLabelStatus(saved, state, true, now, version))
      }
      assertEquals(DesktopFeatureStatus.PENDING,
        desktopLabelStatus(saved, runtime.copy(report = report.copy(launcherLabelMode = null)), true, now, version))
    }
  }

  @Test fun nativeLabelModeStillRequiresAFreshCurrentReceiver() {
    val saved = LauncherLabelUiState(saved = LauncherLabelMode.NATIVE)
    val native = report.copy(launcherLabelMode = LauncherLabelMode.NATIVE.nativeValue)
    for (state in listOf(
      RuntimeStatusUiState(),
      runtime.copy(report = native.copy(checkedAtElapsed = now - RuntimeStatusReport.FRESHNESS_MS - 1)),
      runtime.copy(report = native.copy(moduleVersion = version - 1)),
      runtime.copy(report = native.copy(nativeState = NativeRuntimeState.INACTIVE_USER)),
      runtime.copy(report = native, readFailed = true),
      runtime.copy(report = native, timedOut = true),
    )) assertEquals(DesktopFeatureStatus.PENDING, desktopLabelStatus(saved, state, true, now, version))
  }

  @Test fun dockRequiresItsOwnCompleteProcessAndConfigurationAckEvenWhenMonetIsReady() {
    for (observation in listOf(
      report.copy(dockGlassEnabled = false),
      report.copy(dockGlassCornerStyle = DockGlassCornerStyle.ROUNDED.nativeValue),
      report.copy(dockGlassCornerStyle = null),
      report.copy(dockGlassPid = null),
      report.copy(dockGlassStartTicks = null),
    )) assertEquals(DesktopFeatureStatus.PENDING,
      desktopDockStatus(dock, runtime.copy(report = observation), true, now, version))
  }

  @Test fun aConfirmedDisabledDockStillRequiresMatchingStyleAndItsOwnReceiverIdentity() {
    for (cornerStyle in DockGlassCornerStyle.entries) {
      val saved = DockGlassUiState(saved = DockGlassChoice(enabled = false, cornerStyle = cornerStyle))
      val disabled = report.copy(nativeState = NativeRuntimeState.UNAVAILABLE,
        launcherPid = null, launcherStartTicks = null, dockGlassEnabled = false,
        dockGlassCornerStyle = saved.saved!!.options)
      assertEquals(DesktopFeatureStatus.ACCEPTED,
        desktopDockStatus(saved, runtime.copy(report = disabled), true, now, version))
      for (observation in listOf(
        disabled.copy(dockGlassEnabled = null),
        disabled.copy(dockGlassPid = null),
        disabled.copy(dockGlassStartTicks = null),
        disabled.copy(dockGlassCornerStyle = 1 - cornerStyle.nativeValue),
      )) assertEquals(DesktopFeatureStatus.PENDING,
        desktopDockStatus(saved, runtime.copy(report = observation), true, now, version))
    }
  }

  @Test fun launcherGlassRequiresAnExactAckForEveryStyleIncludingSystem() {
    for (style in LauncherGlassStyle.entries) {
      val saved = LauncherGlassUiState(saved = style)
      for (actual in LauncherGlassStyle.entries) {
        val state = runtime.copy(report = report.copy(launcherGlassStyle = actual.nativeValue))
        assertEquals(if (style == actual) DesktopFeatureStatus.ACCEPTED else DesktopFeatureStatus.PENDING,
          desktopGlassStatus(saved, state, true, now, version))
      }
      assertEquals(DesktopFeatureStatus.PENDING,
        desktopGlassStatus(saved, runtime.copy(report = report.copy(launcherGlassStyle = null)), true, now, version))
    }
  }

  @Test fun explicitGlassCapabilityAbsenceDoesNotImplyOtherFeaturesAreUnavailable() {
    val state = runtime.copy(report = report.copy(launcherGlassAvailable = false, launcherGlassStyle = null))
    assertEquals(DesktopFeatureStatus.UNAVAILABLE, desktopGlassStatus(glass, state, true, now, version))
    assertEquals(DesktopFeatureStatus.ACCEPTED, desktopDockStatus(dock, state, true, now, version))
    assertEquals(DesktopFeatureStatus.ACCEPTED, desktopLabelStatus(labels, state, true, now, version))
  }

  @Test fun invalidRuntimeCannotEstablishEvenCapabilityAbsence() {
    val missing = report.copy(launcherGlassAvailable = false, launcherGlassStyle = null)
    for (state in listOf(
      runtime.copy(report = missing, readFailed = true),
      runtime.copy(report = missing, timedOut = true),
      runtime.copy(report = missing.copy(checkedAtElapsed = now - RuntimeStatusReport.FRESHNESS_MS - 1)),
      runtime.copy(report = missing.copy(moduleVersion = version + 1)),
      runtime.copy(report = missing.copy(nativeState = NativeRuntimeState.INACTIVE_USER)),
    )) assertEquals(DesktopFeatureStatus.PENDING, desktopGlassStatus(glass, state, true, now, version))
  }
}
