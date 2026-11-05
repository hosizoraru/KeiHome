package os.kei.hyper.home.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.status.FrameworkSnapshot

class ThemeEffectsScopeTest {
  @Test
  fun eitherScopeAllowsConfigurationBeforeModuleLoad() {
    assertTrue(framework(system = true, systemUi = false).themeEffectsInScope())
    assertTrue(framework(system = false, systemUi = true).themeEffectsInScope())
    assertTrue(framework(system = true, systemUi = true).themeEffectsInScope())
    assertFalse(framework(system = false, systemUi = false).themeEffectsInScope())
  }

  @Test
  fun unavailableSnapshotCannotReuseScope() {
    val connected = framework(system = true, systemUi = true)
    assertFalse(connected.copy(connected = false).themeEffectsInScope())
    assertFalse(connected.copy(readFailed = true).themeEffectsInScope())
    assertFalse(connected.copy(snapshot = null).themeEffectsInScope())
  }

  @Test
  fun launcherEffectsRequireBothScopeGrantsBeforeTargetLoad() {
    assertLauncherAccess(framework(system = true, systemUi = true), false)
    assertLauncherAccess(framework(system = false, systemUi = true, launcher = true), false)
    assertLauncherAccess(framework(system = true, systemUi = false, launcher = true), true)
  }

  @Test
  fun launcherEffectsCannotReuseDisconnectedOrFailedGrants() {
    val connected = framework(system = true, systemUi = true, launcher = true)
    assertLauncherAccess(connected.copy(connected = false), false)
    assertLauncherAccess(connected.copy(readFailed = true), false)
    assertLauncherAccess(connected.copy(snapshot = null), false)
  }

  private fun assertLauncherAccess(state: FrameworkUiState, expected: Boolean) {
    org.junit.Assert.assertEquals(expected, state.dockGlassInScope())
    org.junit.Assert.assertEquals(expected, state.launcherGlassInScope())
    org.junit.Assert.assertEquals(expected, state.launcherLabelsInScope())
  }

  private fun framework(system: Boolean, systemUi: Boolean, launcher: Boolean = false) = FrameworkUiState(
    connected = true,
    snapshot = FrameworkSnapshot(
      name = "Test framework", apiVersion = 102, systemInScope = system,
      target = null, launcherTarget = null, systemUiInScope = systemUi,
      grantedScope = if (launcher) setOf("com.miui.home") else emptySet(),
    ),
  )
}
