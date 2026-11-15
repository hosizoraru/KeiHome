package os.kei.hyper.home.ui.main

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class UpgradeRestartReminderTest {
  private val back = Os4NavigationTestHost()
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  @Test fun countdownGuardsStaleClicksAndBackThenDeliversOnceAfterExit() {
    var version by mutableStateOf("1.11.0")
    var acknowledged = 0
    val start = SystemClock.elapsedRealtime()
    rule.setContent { back.Provide { MiuixTheme { KeiHomeScaffold {
      UpgradeRestartReminder(version) { acknowledged++; version = "" }
    } } } }
    val button = rule.onNodeWithTag("upgrade.restart.confirm")
    button.assertIsNotEnabled()
    val click = button.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    rule.runOnIdle { click(); assertEquals(0, acknowledged) }
    back.completeBack()
    button.assertExists()
    rule.waitUntil(8_000) { runCatching { button.assertIsEnabled(); true }.getOrDefault(false) }
    rule.runOnIdle {
      assertTrue(SystemClock.elapsedRealtime() - start >= 5_000L)
      click(); click()
      assertEquals(0, acknowledged)
    }
    rule.waitUntil(5_000) { acknowledged == 1 }
    rule.runOnIdle { assertEquals(1, acknowledged) }
    button.assertDoesNotExist()
  }

  @Test fun aNewPresentationRestartsTheFullWait() {
    var version by mutableStateOf("1.11.0")
    rule.setContent { back.Provide { MiuixTheme { KeiHomeScaffold {
      UpgradeRestartReminder(version) { fail("Not yet confirmed") }
    } } } }
    val button = rule.onNodeWithTag("upgrade.restart.confirm")
    rule.waitUntil(8_000) { runCatching { button.assertIsEnabled(); true }.getOrDefault(false) }
    rule.runOnIdle { version = "" }
    rule.waitForIdle()
    rule.runOnIdle { version = "1.11.0" }
    button.assertIsNotEnabled()
  }
}
