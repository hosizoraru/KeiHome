package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.basic.Text

class DesktopOperationProgressTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  @Test fun indicatorStopsAfterAcknowledgementFailureAndUnavailable() {
    val status = mutableStateOf(DesktopFeatureStatus.SAVING)
    rule.setContent { KeiHomeTheme {
      DesktopSettingProgress(status.value, tag = "operation.progress")
    } }
    rule.onNodeWithTag("operation.progress").assertExists()
    for (next in listOf(DesktopFeatureStatus.PENDING, DesktopFeatureStatus.ACCEPTED,
      DesktopFeatureStatus.SAVING, DesktopFeatureStatus.ERROR, DesktopFeatureStatus.UNAVAILABLE)) {
      rule.runOnIdle { status.value = next }
      if (next == DesktopFeatureStatus.PENDING || next == DesktopFeatureStatus.SAVING)
        rule.onNodeWithTag("operation.progress").assertExists()
      else rule.onNodeWithTag("operation.progress").assertDoesNotExist()
    }
  }

  @Test fun missingAcknowledgementTimesOutAndNewChoiceStartsANewWait() {
    val choice = mutableStateOf(0)
    rule.mainClock.autoAdvance = false
    rule.setContent { KeiHomeTheme {
      val status = rememberDesktopOperationStatus(DesktopFeatureStatus.PENDING, choice.value)
      Column {
        Text(status.name)
        DesktopSettingProgress(status, tag = "operation.progress")
      }
    } }
    rule.mainClock.advanceTimeByFrame()
    rule.onNodeWithTag("operation.progress").assertExists()
    rule.mainClock.advanceTimeBy(DESKTOP_OPERATION_TIMEOUT_MS + 100)
    rule.waitForIdle()
    rule.onNodeWithText(DesktopFeatureStatus.TIMED_OUT.name).assertExists()
    rule.onNodeWithTag("operation.progress").assertDoesNotExist()
    rule.runOnIdle { choice.value++ }
    rule.mainClock.advanceTimeByFrame()
    rule.onNodeWithText(DesktopFeatureStatus.PENDING.name).assertExists()
    rule.onNodeWithTag("operation.progress").assertExists()
  }
}
