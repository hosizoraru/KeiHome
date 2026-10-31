package os.kei.hyper.home.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiThemeCompatibilityTest {
  @Test fun activeCallbackRejectsReloadWithoutDisablingRuntime() {
    val lifecycle = ThemeCompatibilityLifecycle()
    assertTrue(lifecycle.enter())
    assertFalse(lifecycle.retire { error("Must not retire in-flight work") })
    lifecycle.leave()
    assertTrue(lifecycle.enter())
    lifecycle.leave()
    assertTrue(lifecycle.retire {})
    assertFalse(lifecycle.enter())
  }

  @Test fun finishedActionStillWaitsForItsOwnedThreadToExit() {
    val lifecycle = ThemeCompatibilityLifecycle()
    assertFalse(lifecycle.retire(workersStopped = { false }) {})
    assertTrue(lifecycle.retire(workersStopped = { true }) {})
    assertFalse(lifecycle.enter())
  }

  @Test fun failedStateTransferKeepsOldRuntimeAvailable() {
    val lifecycle = ThemeCompatibilityLifecycle()
    assertFalse(lifecycle.retire { throw IllegalStateException("Framework refused state") })
    assertTrue(lifecycle.enter())
    lifecycle.leave()
    assertTrue(lifecycle.retire {})
  }
}
