package os.kei.hyper.home.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.nav.runtime.NavChange
import top.yukonga.miuix.kmp.nav.transition.NavRole

class Os4NavigationChromeTest {
  @Test fun coldStartAndIdleStandaloneNeverSeedHidden() {
    assertFalse(shouldSeedNavChromeEntry(isCurrentDestination = true, isRunning = false, change = NavChange.None))
    assertFalse(shouldSeedNavChromeEntry(isCurrentDestination = true, isRunning = false, change = null))
    assertFalse(shouldSeedNavChromeEntry(isCurrentDestination = true, isRunning = true, change = NavChange.None))
    assertFalse(shouldSeedNavChromeEntry(isCurrentDestination = false, isRunning = true, change = NavChange.Push))
  }

  @Test fun enteringOrRestoredDestinationDuringTransitionSeedsHiddenForBloom() {
    assertTrue(shouldSeedNavChromeEntry(isCurrentDestination = true, isRunning = true, change = NavChange.Push))
    assertTrue(shouldSeedNavChromeEntry(isCurrentDestination = true, isRunning = true, change = NavChange.Pop))
    assertTrue(shouldSeedNavChromeEntry(isCurrentDestination = true, isRunning = true, change = NavChange.Replace))
  }

  @Test fun gestureDissolveAppliesOnlyToOutgoingSwipeProgress() {
    assertEquals(0f, navBottomBarGestureDissolveFraction(NavRole.Top, 0.5f), 0.0001f)
    assertEquals(0f, navBottomBarGestureDissolveFraction(NavRole.Covered, 0.5f), 0.0001f)
    assertEquals(0f, navBottomBarGestureDissolveFraction(NavRole.Incoming, 0.5f), 0.0001f)
    assertEquals(0f, navBottomBarGestureDissolveFraction(NavRole.Outgoing, null), 0.0001f)
    assertEquals(0f, navBottomBarGestureDissolveFraction(NavRole.Outgoing, 0f), 0.0001f)
    assertEquals(0.5f, navBottomBarGestureDissolveFraction(NavRole.Outgoing, 0.275f), 0.001f)
    assertEquals(1f, navBottomBarGestureDissolveFraction(NavRole.Outgoing, 0.8f), 0.0001f)
  }
}
