package os.kei.hyper.home.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.data.DockGlassChoice
import os.kei.hyper.home.data.DockGlassCornerStyle
import os.kei.hyper.home.data.LauncherGlassStyle
import os.kei.hyper.home.data.LauncherLabelMode
import os.kei.hyper.home.status.NativeRuntimeDiagnostic
import os.kei.hyper.home.status.LauncherRuntimeCapabilities
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
    launcherCapabilities = LauncherRuntimeCapabilities.KNOWN,
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
    assertStatuses(DesktopFeatureStatus.UNKNOWN, RuntimeStatusUiState())
    assertStatuses(DesktopFeatureStatus.CHECKING, RuntimeStatusUiState(checking = true))
    for (state in listOf(
      runtime.copy(report = report.copy(launcherLabelMode = null, dockGlassEnabled = null,
        dockGlassPid = null, dockGlassStartTicks = null, dockGlassCornerStyle = null, launcherGlassStyle = null)),
    )) assertStatuses(DesktopFeatureStatus.PENDING, state)
  }

  @Test fun retainedReceiptsExpireAndCannotSurviveAFutureElapsedTimestamp() {
    assertStatuses(DesktopFeatureStatus.ACCEPTED, at = now + RuntimeStatusReport.FRESHNESS_MS)
    assertStatuses(DesktopFeatureStatus.UNKNOWN, at = now + RuntimeStatusReport.FRESHNESS_MS + 1)
    assertStatuses(DesktopFeatureStatus.UNKNOWN, at = now - 1)
    assertStatuses(DesktopFeatureStatus.CHECKING, runtime.copy(checking = true),
      at = now + RuntimeStatusReport.FRESHNESS_MS + 1)
  }

  @Test fun upgradesAndDowngradesBothRequireTheCurrentModuleVersion() {
    for (installedVersion in listOf(version - 1, version + 1))
      assertStatuses(DesktopFeatureStatus.UNKNOWN, moduleVersion = installedVersion)
  }

  @Test fun queryReadFailureOrTimeoutInvalidatesEveryRetainedReceipt() {
    for (state in listOf(
      runtime.copy(readFailed = true),
      runtime.copy(timedOut = true),
      runtime.copy(readFailed = true, checking = true),
      runtime.copy(timedOut = true, checking = true),
    )) assertStatuses(if (state.readFailed) DesktopFeatureStatus.READ_FAILED else DesktopFeatureStatus.TIMED_OUT, state)
  }

  @Test fun inactiveUserCannotAcceptAnyFeatureEvenWithMatchingAcknowledgements() {
    assertStatuses(DesktopFeatureStatus.UNKNOWN,
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
    )) assertEquals(when {
      state.readFailed -> DesktopFeatureStatus.READ_FAILED
      state.timedOut -> DesktopFeatureStatus.TIMED_OUT
      else -> DesktopFeatureStatus.UNKNOWN
    }, desktopLabelStatus(saved, state, true, now, version))
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
        launcherPid = null, launcherStartTicks = null, launcherCapabilities = null, dockGlassEnabled = false,
        dockGlassCornerStyle = saved.saved!!.options)
      assertEquals(DesktopFeatureStatus.ACCEPTED,
        desktopDockStatus(saved, runtime.copy(report = disabled), true, now, version))
      for (observation in listOf(
        disabled.copy(dockGlassEnabled = null),
        disabled.copy(dockGlassPid = null),
        disabled.copy(dockGlassStartTicks = null),
        disabled.copy(dockGlassCornerStyle = 1 - cornerStyle.nativeValue),
      )) assertEquals(DesktopFeatureStatus.UNKNOWN,
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
    val state = runtime.copy(report = report.copy(launcherGlassAvailable = false, launcherGlassStyle = null,
      launcherCapabilities = LauncherRuntimeCapabilities.KNOWN xor LauncherRuntimeCapabilities.GLASS))
    assertEquals(DesktopFeatureStatus.UNAVAILABLE, desktopGlassStatus(glass, state, true, now, version))
    assertEquals(DesktopFeatureStatus.ACCEPTED, desktopDockStatus(dock, state, true, now, version))
    assertEquals(DesktopFeatureStatus.ACCEPTED, desktopLabelStatus(labels, state, true, now, version))
  }

  @Test fun invalidRuntimeCannotEstablishEvenCapabilityAbsence() {
    val missing = report.copy(launcherGlassAvailable = false, launcherGlassStyle = null,
      launcherCapabilities = LauncherRuntimeCapabilities.MONET)
    for (state in listOf(
      runtime.copy(report = missing, readFailed = true),
      runtime.copy(report = missing, timedOut = true),
      runtime.copy(report = missing.copy(checkedAtElapsed = now - RuntimeStatusReport.FRESHNESS_MS - 1)),
      runtime.copy(report = missing.copy(moduleVersion = version + 1)),
      runtime.copy(report = missing.copy(nativeState = NativeRuntimeState.INACTIVE_USER)),
    )) {
      assertStatuses(when {
        state.readFailed -> DesktopFeatureStatus.READ_FAILED
        state.timedOut -> DesktopFeatureStatus.TIMED_OUT
        else -> DesktopFeatureStatus.UNKNOWN
      }, state)
      for (bit in listOf(8, 4, 16, 32)) assertEquals(null, desktopCapability(state, now, version, bit))
    }
  }

  @Test fun eachMissingCapabilityDisablesOnlyItsOwnFeatureEvenWithAnOldAck() {
    for (bit in listOf(LauncherRuntimeCapabilities.DOCK, LauncherRuntimeCapabilities.LABELS,
      LauncherRuntimeCapabilities.GLASS)) {
      val state = runtime.copy(report = report.copy(launcherCapabilities = LauncherRuntimeCapabilities.KNOWN xor bit))
      val statuses = listOf(
        desktopDockStatus(dock, state, true, now, version),
        desktopLabelStatus(labels, state, true, now, version),
        desktopGlassStatus(glass, state, true, now, version),
      )
      assertEquals(listOf(8, 4, 16).map {
        if (it == bit) DesktopFeatureStatus.UNAVAILABLE else DesktopFeatureStatus.ACCEPTED
      }, statuses)
      assertFalse(desktopControlEnabled(true, false, false, true, desktopCapability(state, now, version, bit)))
    }
    assertStatuses(DesktopFeatureStatus.UNAVAILABLE,
      runtime.copy(report = report.copy(launcherCapabilities = LauncherRuntimeCapabilities.MONET,
        nativeState = NativeRuntimeState.UNAVAILABLE)))
    val unsupported = runtime.copy(report = report.copy(launcherCapabilities = LauncherRuntimeCapabilities.MONET))
    assertEquals(DesktopFeatureStatus.UNAVAILABLE,
      desktopDockStatus(dock.copy(failed = true), unsupported, true, now, version))
    assertEquals(DesktopFeatureStatus.UNAVAILABLE,
      desktopLabelStatus(LauncherLabelUiState(), unsupported, true, now, version))
    assertEquals(DesktopFeatureStatus.UNAVAILABLE,
      desktopGlassStatus(glass.copy(unavailable = true), unsupported, true, now, version))
    assertStatuses(DesktopFeatureStatus.OUT_OF_SCOPE, unsupported, inScope = false)
  }

  @Test fun unknownLegacyAndUnboundCapabilitiesNeverDisableControlsAsUnsupported() {
    for (observation in listOf(
      report.copy(launcherCapabilities = null),
      report.copy(launcherPid = null, launcherStartTicks = null),
      report.copy(launcherCapabilities = 0, launcherPid = null, launcherStartTicks = null),
      report.copy(launcherCapabilities = -2), report.copy(launcherCapabilities = 64),
    )) {
      val state = runtime.copy(report = observation.copy(launcherLabelMode = null,
        dockGlassEnabled = null, launcherGlassAvailable = false, launcherGlassStyle = null))
      assertStatuses(DesktopFeatureStatus.UNKNOWN, state)
      assertEquals(null, desktopCapability(state, now, version, 8))
      assertTrue(desktopControlEnabled(true, false, false, true, desktopCapability(state, now, version, 8)))
    }
  }

  @Test fun authenticatedDiagnosticOnlyZeroDisablesAllFeaturesWithoutASuccessfulOverview() {
    val zero = report.copy(nativeState = NativeRuntimeState.UNAVAILABLE,
      detail = NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE, launcherCapabilities = 0,
      launcherLabelMode = null, dockGlassEnabled = null, dockGlassPid = null,
      dockGlassStartTicks = null, dockGlassCornerStyle = null,
      launcherGlassAvailable = false, launcherGlassStyle = null)
    val state = RuntimeStatusUiState(report = requireNotNull(RuntimeStatusReport.decode(zero.encode())))
    assertStatuses(DesktopFeatureStatus.UNAVAILABLE, state)
    assertStatuses(DesktopFeatureStatus.UNAVAILABLE, state,
      dock = DockGlassUiState(), labels = LauncherLabelUiState(), glass = LauncherGlassUiState())
    val capabilities = listOf(LauncherRuntimeCapabilities.DOCK, LauncherRuntimeCapabilities.LABELS,
      LauncherRuntimeCapabilities.GLASS).map { desktopCapability(state, now, version, it) }
    assertEquals(listOf(false, false, false), capabilities)
    for (supported in capabilities)
      assertFalse(desktopControlEnabled(true, false, false, true, supported))
    assertFalse(desktopDockLayoutEnabled(true, false, desktopCapability(state, now, version, 32)))
    assertFalse(desktopDockSaveSupported(requireNotNull(dock.saved), capabilities[0], false))
    assertEquals(DesktopSyncSummary(0, 0, false),
      desktopSyncSummary(List(3) { DesktopFeatureStatus.UNAVAILABLE }, capabilities))
  }

  @Test fun missingOrUnreadableZeroGreetingCannotEstablishUnsupportedCapabilities() {
    val zero = report.copy(nativeState = NativeRuntimeState.UNAVAILABLE,
      detail = NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE, launcherCapabilities = 0,
      launcherLabelMode = null, dockGlassEnabled = null, dockGlassPid = null,
      dockGlassStartTicks = null, dockGlassCornerStyle = null,
      launcherGlassAvailable = false, launcherGlassStyle = null)
    for (state in listOf(
      RuntimeStatusUiState(),
      RuntimeStatusUiState(report = zero.copy(launcherCapabilities = null)),
      RuntimeStatusUiState(report = zero.copy(checkedAtElapsed = now - RuntimeStatusReport.FRESHNESS_MS - 1)),
      RuntimeStatusUiState(report = zero.copy(moduleVersion = version + 1)),
      RuntimeStatusUiState(report = zero.copy(nativeState = NativeRuntimeState.INACTIVE_USER)),
      RuntimeStatusUiState(report = zero, readFailed = true),
      RuntimeStatusUiState(report = zero, timedOut = true),
    )) {
      assertStatuses(when {
        state.readFailed -> DesktopFeatureStatus.READ_FAILED
        state.timedOut -> DesktopFeatureStatus.TIMED_OUT
        else -> DesktopFeatureStatus.UNKNOWN
      }, state)
      val capabilities = listOf(8, 4, 16).map { desktopCapability(state, now, version, it) }
      assertEquals(listOf(null, null, null), capabilities)
      assertEquals(DesktopSyncSummary(0, 0, false),
        desktopSyncSummary(List(3) { DesktopFeatureStatus.UNKNOWN }, capabilities))
    }
  }

  @Test fun missingLayoutCapabilityPreservesBasicDockControlAndSavedGeometry() {
    val state = runtime.copy(report = report.copy(launcherCapabilities = LauncherRuntimeCapabilities.DOCK))
    val basicEnabled = desktopControlEnabled(true, false, false, true, desktopCapability(state, now, version, 8))
    assertTrue(basicEnabled)
    assertTrue(desktopDockSaveSupported(requireNotNull(dock.saved), true, false))
    assertFalse(desktopDockSaveSupported(requireNotNull(dock.saved), false, false))
    assertFalse(desktopDockLayoutEnabled(true, basicEnabled, desktopCapability(state, now, version, 32)))
    assertEquals(DesktopFeatureStatus.ACCEPTED, desktopDockStatus(dock, state, true, now, version))
    for (choice in listOf(
      DockGlassChoice(enabled = true, cornerStyle = DockGlassCornerStyle.PAD),
      DockGlassChoice(enabled = true, verticalPaddingDp = 24),
      DockGlassChoice(enabled = true, verticalOffsetDp = 8),
      DockGlassChoice(enabled = false, cornerStyle = DockGlassCornerStyle.CUSTOM, customRadiusDp = 30),
    )) {
      val saved = DockGlassUiState(saved = choice)
      assertEquals(DesktopFeatureStatus.LAYOUT_UNAVAILABLE, desktopDockStatus(saved, state, true, now, version))
      assertEquals(choice, saved.saved)
      assertFalse(desktopDockSaveSupported(choice, true, false))
      assertTrue(desktopDockSaveSupported(choice, true, true))
      assertTrue(desktopDockSaveSupported(choice, null, null))
    }
    assertTrue(desktopDockLayoutEnabled(true, basicEnabled, null))
    assertTrue(desktopDockLayoutEnabled(true, basicEnabled, true))
    assertFalse(desktopDockLayoutEnabled(false, basicEnabled, true))
  }

  @Test fun supportedCountsExcludeUnsupportedAndNeverDeclareSuccessWithoutKnownCapabilities() {
    val accepted = DesktopFeatureStatus.ACCEPTED
    val unsupported = DesktopFeatureStatus.UNAVAILABLE
    assertEquals(DesktopSyncSummary(2, 2, true),
      desktopSyncSummary(listOf(accepted, accepted, unsupported), listOf(true, true, false)))
    assertEquals(DesktopSyncSummary(0, 1, false),
      desktopSyncSummary(listOf(DesktopFeatureStatus.PENDING, unsupported, unsupported), listOf(true, false, false)))
    assertEquals(DesktopSyncSummary(0, 0, false),
      desktopSyncSummary(List(3) { unsupported }, List(3) { false }))
    assertEquals(DesktopSyncSummary(0, 0, false),
      desktopSyncSummary(List(3) { accepted }, List(3) { null }))
    assertEquals(DesktopSyncSummary(1, 1, false),
      desktopSyncSummary(listOf(accepted, accepted, unsupported), listOf(true, null, false)))
    assertFalse(desktopSyncSummary(listOf(DesktopFeatureStatus.LAYOUT_UNAVAILABLE, unsupported, unsupported),
      listOf(true, false, false)).ready)
    assertFalse(desktopSyncSummary(listOf(accepted, DesktopFeatureStatus.OUT_OF_SCOPE, unsupported),
      listOf(true, null, false)).ready)
  }

  @Test fun dockWithoutLayoutKeepsLegacyChooserAndResetAvailableButBlocksGeometry() {
    val state = runtime.copy(report = report.copy(launcherCapabilities = LauncherRuntimeCapabilities.DOCK))
    val supported = desktopCapability(state, now, version, LauncherRuntimeCapabilities.DOCK)
    val layoutSupported = desktopCapability(state, now, version, LauncherRuntimeCapabilities.DOCK_LAYOUT)
    val controlsEnabled = desktopControlEnabled(true, false, false, true, supported)
    for (style in DockGlassCornerStyle.entries) {
      val saved = DockGlassChoice(enabled = true, cornerStyle = style,
        verticalPaddingDp = 26, verticalOffsetDp = 37, customRadiusDp = 42)
      assertEquals(DesktopDockControls(true, true, false), desktopDockControls(saved, controlsEnabled, layoutSupported))
      assertEquals(style == DockGlassCornerStyle.RECENTS || style == DockGlassCornerStyle.ROUNDED,
        desktopDockCornerStyleSupported(style, layoutSupported))
      assertEquals(DesktopFeatureStatus.LAYOUT_UNAVAILABLE,
        desktopDockStatus(DockGlassUiState(saved = saved), state, true, now, version))
      assertEquals(style, saved.cornerStyle)
      assertEquals(37, saved.verticalOffsetDp)
      assertEquals(DesktopDockControls(false, true, false),
        desktopDockControls(saved.copy(enabled = false), controlsEnabled, layoutSupported))
      assertEquals(DesktopDockControls(false, false, false), desktopDockControls(saved, false, layoutSupported))
    }
    assertEquals(DesktopDockControls(false, false, false), desktopDockControls(null, controlsEnabled, layoutSupported))
  }

  @Test fun explicitLegacySelectionEscapesPersistedExtraStyleAndOffsetsWithoutLayoutSupport() {
    for (enabled in listOf(false, true)) for (savedStyle in DockGlassCornerStyle.entries) {
      val saved = DockGlassChoice(enabled = enabled, cornerStyle = savedStyle,
        verticalPaddingDp = 26, verticalOffsetDp = 37, customRadiusDp = 42)
      for (target in listOf(DockGlassCornerStyle.RECENTS, DockGlassCornerStyle.ROUNDED)) {
        val selected = requireNotNull(desktopDockCornerSelection(saved, target, false))
        assertEquals(DockGlassChoice(enabled = enabled, cornerStyle = target), selected)
        assertTrue(desktopDockSaveSupported(selected, true, false))
        assertTrue(selected.options in 0..1)
        val pending = runtime.copy(report = report.copy(launcherCapabilities = LauncherRuntimeCapabilities.DOCK,
          dockGlassCornerStyle = null))
        assertEquals(DesktopFeatureStatus.PENDING,
          desktopDockStatus(DockGlassUiState(saved = selected), pending, true, now, version))
        val acknowledged = pending.copy(report = pending.report!!.copy(
          dockGlassEnabled = enabled, dockGlassCornerStyle = selected.options))
        assertEquals(DesktopFeatureStatus.ACCEPTED,
          desktopDockStatus(DockGlassUiState(saved = selected), acknowledged, true, now, version))
      }
      for (target in listOf(DockGlassCornerStyle.PAD, DockGlassCornerStyle.CONCENTRIC, DockGlassCornerStyle.CUSTOM))
        assertNull(desktopDockCornerSelection(saved, target, false))
      assertEquals(savedStyle, saved.cornerStyle)
      assertEquals(26, saved.verticalPaddingDp)
      assertEquals(37, saved.verticalOffsetDp)
      assertEquals(42, saved.customRadiusDp)
    }
  }

  @Test fun supportedOrUnknownLayoutKeepsAllStylesAndPreservesGeometryOnSelection() {
    val saved = DockGlassChoice(enabled = true, cornerStyle = DockGlassCornerStyle.CUSTOM,
      verticalPaddingDp = 26, verticalOffsetDp = 37, customRadiusDp = 42)
    for (layoutSupported in listOf(true, null)) {
      assertEquals(DesktopDockControls(true, true, true), desktopDockControls(saved, true, layoutSupported))
      for (target in DockGlassCornerStyle.entries) {
        assertTrue(desktopDockCornerStyleSupported(target, layoutSupported))
        assertEquals(saved.copy(cornerStyle = target), desktopDockCornerSelection(saved, target, layoutSupported))
      }
    }
  }

  @Test fun preferenceFailureAndScopeStillPreventEditingWhenCapabilityIsSupported() {
    assertFalse(desktopControlEnabled(false, false, false, true, true))
    assertFalse(desktopControlEnabled(true, true, false, true, true))
    assertFalse(desktopControlEnabled(true, false, true, true, true))
    assertFalse(desktopControlEnabled(true, false, false, false, true))
  }
}
