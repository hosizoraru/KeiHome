package os.kei.hyper.home.ui.main

import io.github.libxposed.service.HookedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.FrameworkTarget

class StatusDashboardTest {
  private val installedVersion = BuildConfig.VERSION_CODE.toLong()

  private fun snapshot() = FrameworkSnapshot(
    name = "Test framework",
    apiVersion = 102,
    systemInScope = true,
    target = FrameworkTarget(HookedTarget.State.UP_TO_DATE, installedVersion),
    launcherTarget = FrameworkTarget(HookedTarget.State.UP_TO_DATE, installedVersion),
    frameworkVersion = "2.1.4-test",
    frameworkVersionCode = 7350L,
  )

  @Test
  fun frameworkIdentityKeepsFrameworkReleaseSeparateFromApiAndModuleBuild() {
    val state = frameworkCardState(true, false, snapshot())
    assertEquals("2.1.4-test", state.frameworkVersion)
    assertEquals(7350L, state.frameworkVersionCode)
    assertEquals(102, state.apiVersion)
    assertEquals(installedVersion, state.systemVersion)
    assertTrue(state.reported)
  }

  @Test
  fun unavailableVersionIsNotReplacedByTheApiOrModuleVersion() {
    val legacy = FrameworkSnapshot("Test framework", 102, true, null, null)
    val state = frameworkCardState(true, false, legacy)
    assertTrue(state.reported)
    assertNull(state.frameworkVersion)
    assertNull(state.frameworkVersionCode)
    val invalid = frameworkCardState(true, false,
      legacy.copy(frameworkVersion = "  ", frameworkVersionCode = -1))
    assertNull(invalid.frameworkVersion)
    assertNull(invalid.frameworkVersionCode)
  }

  @Test
  fun disconnectOrReadFailureCannotDisplayPreviousSuccessfulReports() {
    listOf(false to false, false to true, true to true).forEach { (connected, failed) ->
      val state = frameworkCardState(connected, failed, snapshot())
      assertFalse(state.reported)
      assertNull(state.name)
      assertNull(state.apiVersion)
      assertNull(state.frameworkVersion)
      assertNull(state.frameworkVersionCode)
      assertNull(state.systemInScope)
      assertNull(state.systemState)
      assertNull(state.systemVersion)
      assertNull(state.launcherState)
      assertNull(state.launcherVersion)
      assertEquals(StatusTone.ERROR,
        statusTone(connected, failed, state.systemInScope, state.systemState, state.launcherState))
    }
    assertEquals(snapshot().frameworkVersion, frameworkCardState(true, false, snapshot()).frameworkVersion)
  }

  @Test
  fun waitingForReadAndSuccessfulReadWithNoTargetsRemainDistinct() {
    val waiting = frameworkCardState(true, false, null)
    val unreported = frameworkCardState(true, false, snapshot().copy(target = null, launcherTarget = null))
    assertFalse(waiting.reported)
    assertNull(waiting.systemInScope)
    assertTrue(unreported.reported)
    assertEquals(true, unreported.systemInScope)
    assertNull(unreported.systemState)
    assertNull(unreported.launcherState)
    assertEquals(StatusTone.WARNING,
      statusTone(true, false, unreported.systemInScope, unreported.systemState, unreported.launcherState))
  }

  @Test
  fun installedGenerationMismatchCannotAppearCurrentAfterUpgradeOrDowngrade() {
    listOf(installedVersion - 1, installedVersion + 1).forEach { loaded ->
      val target = FrameworkTarget(HookedTarget.State.UP_TO_DATE, loaded)
      listOf(snapshot().copy(target = target), snapshot().copy(launcherTarget = target)).forEach {
        val state = frameworkCardState(true, false, it)
        assertEquals(StatusTone.WARNING,
          statusTone(true, false, true, state.systemState, state.launcherState))
      }
      assertEquals(HookedTarget.State.STALE, loadedState(HookedTarget.State.UP_TO_DATE, loaded))
    }
  }

  @Test
  fun reloadAndFailureReportsAreNotOverriddenByVersionComparison() {
    listOf(HookedTarget.State.RELOADING, HookedTarget.State.FAILED, HookedTarget.State.STALE).forEach {
      assertEquals(it, loadedState(it, installedVersion))
      assertEquals(it, loadedState(it, installedVersion - 1))
    }
    assertNull(loadedState(HookedTarget.State.UP_TO_DATE, null))
    assertNull(loadedState(null, installedVersion))
  }

  @Test
  fun launcherFailureHasSameErrorSeverityAsSystemFailure() {
    val loaded = HookedTarget.State.UP_TO_DATE
    val failed = HookedTarget.State.FAILED
    assertEquals(StatusTone.ERROR, statusTone(true, false, true, loaded, failed))
    assertEquals(StatusTone.ERROR, statusTone(true, false, true, failed, loaded))
    assertEquals(StatusTone.WARNING, statusTone(true, false, true, loaded, null))
  }

  @Test
  fun appliedPaletteAgeChangesAtTheMinuteBoundary() {
    val appliedAt = 1_000_000L
    assertEquals(0L, dashboardPaletteAgeMinutes(appliedAt, appliedAt + 59_999))
    assertEquals(1L, dashboardPaletteAgeMinutes(appliedAt, appliedAt + 60_000))
    assertEquals(60L, dashboardPaletteAgeMinutes(appliedAt, appliedAt + 3_600_000))
    assertEquals(1_440L, dashboardPaletteAgeMinutes(appliedAt, appliedAt + 86_400_000))
  }

  @Test
  fun clockSkewAndInvalidPaletteTimestampsDoNotInventARecentApplication() {
    assertNull(dashboardPaletteAgeMinutes(1_001, 1_000))
    assertNull(dashboardPaletteAgeMinutes(0, 1_000))
    assertNull(dashboardPaletteAgeMinutes(-1, 1_000))
  }
}
