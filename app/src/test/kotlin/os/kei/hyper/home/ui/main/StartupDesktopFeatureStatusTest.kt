package os.kei.hyper.home.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.RuntimeStatusUiState

class StartupDesktopFeatureStatusTest {
  private val now = 10_000L
  private val version = 42L
  private val snapshot = FrameworkSnapshot("Test framework", 102, true, null, null)

  @Test fun initialBindingAndFirstSnapshotReadRemainChecking() {
    for (framework in listOf(
      FrameworkUiState(checking = true),
      FrameworkUiState(connected = true, checking = true),
      FrameworkUiState(connected = true),
    )) {
      assertTrue(framework.desktopScopeChecking())
      assertFalse(framework.dockGlassInScope())
      assertFalse(framework.launcherLabelsInScope())
      assertFalse(framework.launcherGlassInScope())
      assertFalse(framework.splashAppearanceInScope())
      assertFalse(framework.themeEffectsInScope())
      assertStatuses(DesktopFeatureStatus.CHECKING, framework)
    }
  }

  @Test fun completedBindingAndSnapshotFailuresDoNotRemainChecking() {
    assertFalse(FrameworkUiState().desktopScopeChecking())
    assertFalse(FrameworkUiState(connected = true, snapshot = snapshot).desktopScopeChecking())
    assertFalse(FrameworkUiState(checking = true, readFailed = true).desktopScopeChecking())
    assertFalse(FrameworkUiState(connected = true, checking = true, readFailed = true).desktopScopeChecking())
    assertStatuses(DesktopFeatureStatus.OUT_OF_SCOPE, FrameworkUiState())
  }

  @Test fun unresolvedScopeDoesNotExposeUnavailablePreferenceResults() {
    assertStatuses(DesktopFeatureStatus.CHECKING, FrameworkUiState(checking = true),
      dock = DockGlassUiState(unavailable = true),
      labels = LauncherLabelUiState(unavailable = true),
      glass = LauncherGlassUiState(unavailable = true),
      stored = ThemeEffectsUiState(unavailable = true))
  }

  @Test fun resolvedScopeRetainsLoadingAndUnavailableResults() {
    assertStatuses(DesktopFeatureStatus.LOADING, FrameworkUiState(connected = true, snapshot = snapshot),
      inScope = true)
    assertStatuses(DesktopFeatureStatus.UNAVAILABLE, FrameworkUiState(connected = true, snapshot = snapshot),
      dock = DockGlassUiState(unavailable = true),
      labels = LauncherLabelUiState(unavailable = true),
      glass = LauncherGlassUiState(unavailable = true),
      stored = ThemeEffectsUiState(unavailable = true), inScope = true)
  }

  @Test fun preferenceFailuresAndSavesRemainVisibleDuringScopeChecks() {
    val framework = FrameworkUiState(checking = true)
    assertStatuses(DesktopFeatureStatus.ERROR, framework,
      dock = DockGlassUiState(failed = true),
      labels = LauncherLabelUiState(failed = true),
      glass = LauncherGlassUiState(failed = true),
      stored = ThemeEffectsUiState(saveFailed = true))
    assertEquals(DesktopFeatureStatus.READ_FAILED,
      desktopStoredStatus(ThemeEffectsUiState(readFailed = true), false, scopeChecking = true))
    assertStatuses(DesktopFeatureStatus.SAVING, framework,
      dock = DockGlassUiState(saving = true),
      labels = LauncherLabelUiState(saving = true),
      glass = LauncherGlassUiState(saving = true),
      stored = ThemeEffectsUiState(saving = true))
  }

  @Test fun runtimeReadFailuresAndTimeoutsRemainVisibleDuringScopeChecks() {
    assertStatuses(DesktopFeatureStatus.READ_FAILED, FrameworkUiState(checking = true),
      runtime = RuntimeStatusUiState(checking = true, readFailed = true),
      stored = ThemeEffectsUiState(readFailed = true))
    assertStatuses(DesktopFeatureStatus.TIMED_OUT, FrameworkUiState(checking = true),
      runtime = RuntimeStatusUiState(checking = true, timedOut = true), includeStored = false)
  }

  @Test fun checkingAndLoadingUseNeutralAggregateToneWithoutHidingErrors() {
    for (pending in listOf(DesktopFeatureStatus.CHECKING, DesktopFeatureStatus.LOADING)) {
      assertEquals(StatusTone.CHECKING, desktopAggregateTone(listOf(pending), ready = false, allUnsupported = false))
      for (error in listOf(DesktopFeatureStatus.ERROR, DesktopFeatureStatus.READ_FAILED, DesktopFeatureStatus.TIMED_OUT)) {
        assertEquals(StatusTone.ERROR,
          desktopAggregateTone(listOf(pending, error), ready = false, allUnsupported = false))
      }
    }
    assertEquals(StatusTone.READY,
      desktopAggregateTone(listOf(DesktopFeatureStatus.ACCEPTED), ready = true, allUnsupported = false))
    assertEquals(StatusTone.READY,
      desktopAggregateTone(listOf(DesktopFeatureStatus.UNAVAILABLE), ready = false, allUnsupported = true))
    assertEquals(StatusTone.WARNING,
      desktopAggregateTone(listOf(DesktopFeatureStatus.OUT_OF_SCOPE), ready = false, allUnsupported = false))
  }

  @Test fun checkingNeverEnablesControlsEvenWithRetainedSettings() {
    for (inScope in listOf(false, true)) {
      assertFalse(desktopControlEnabled(inScope, saving = false, unavailable = false,
        hasSaved = true, supported = true, scopeChecking = true))
    }
    assertFalse(desktopControlEnabled(false, false, false, true, true))
    assertTrue(desktopControlEnabled(true, false, false, true, true))
  }

  private fun assertStatuses(
    expected: DesktopFeatureStatus,
    framework: FrameworkUiState,
    dock: DockGlassUiState = DockGlassUiState(),
    labels: LauncherLabelUiState = LauncherLabelUiState(),
    glass: LauncherGlassUiState = LauncherGlassUiState(),
    stored: ThemeEffectsUiState = ThemeEffectsUiState(),
    runtime: RuntimeStatusUiState = RuntimeStatusUiState(),
    inScope: Boolean = false,
    includeStored: Boolean = true,
  ) {
    val checking = framework.desktopScopeChecking()
    assertEquals(expected, desktopDockStatus(dock, runtime, inScope, now, version, checking))
    assertEquals(expected, desktopLabelStatus(labels, runtime, inScope, now, version, checking))
    assertEquals(expected, desktopGlassStatus(glass, runtime, inScope, now, version, checking))
    assertEquals(expected, desktopSearchGlassStatus(glass, runtime, inScope, now, version, checking))
    if (includeStored) assertEquals(expected, desktopStoredStatus(stored, inScope, checking))
  }
}
