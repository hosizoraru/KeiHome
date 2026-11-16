package os.kei.hyper.home.ui.main

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class LogExportConfirmationTest {
  private val backHost = Os4NavigationTestHost()
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  @Test fun exportWaitsTwentySecondsAndDeliversOnlyOnceAfterDismissal() {
    var open by mutableStateOf(true)
    var exports = 0
    var exportedAt = 0L
    val startedAt = SystemClock.elapsedRealtime()
    rule.setContent {
      backHost.Provide {
        MiuixTheme {
          KeiHomeScaffold {
            if (open) LogExportConfirmationDialog(
              onDismissed = { open = false },
              onExport = { exports++; exportedAt = SystemClock.elapsedRealtime() },
            )
          }
        }
      }
    }
    val confirm = rule.onNodeWithTag("logs.export.confirm")
    confirm.assertIsNotEnabled()
    val click = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    rule.runOnIdle { click(); assertEquals(0, exports) }
    rule.waitUntil(timeoutMillis = 25_000) {
      runCatching { confirm.assertIsEnabled(); true }.getOrDefault(false)
    }
    rule.runOnIdle {
      assertEquals(0, exports)
      click()
      click()
      assertEquals(0, exports)
    }
    rule.waitUntil(timeoutMillis = 5_000) { !open }
    rule.runOnIdle {
      assertEquals(1, exports)
      assertTrue(exportedAt - startedAt >= 20_000L)
    }
  }

  @Test fun cancelAndBackNeverExportAndReopeningRestartsTheWait() {
    var open by mutableStateOf(true)
    var exports = 0
    rule.setContent {
      backHost.Provide {
        MiuixTheme {
          KeiHomeScaffold {
            if (open) LogExportConfirmationDialog(
              onDismissed = { open = false }, onExport = { exports++ },
            )
          }
        }
      }
    }
    rule.onNodeWithTag("logs.export.confirm").assertIsNotEnabled()
    rule.onNodeWithTag("logs.export.cancel").performClick()
    rule.waitUntil(timeoutMillis = 5_000) { !open }
    // Let the old deadline expire; a fresh presentation must still be disabled.
    SystemClock.sleep(20_100)
    rule.runOnIdle { open = true }
    rule.onNodeWithTag("logs.export.confirm").assertIsNotEnabled()
    rule.runOnIdle { backHost.completeBack() }
    rule.waitUntil(timeoutMillis = 5_000) { !open }
    rule.runOnIdle { assertEquals(0, exports) }
  }
}
