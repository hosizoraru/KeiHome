package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.data.InitialActivationStatus
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class InitialActivationStatusTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val host = Os4NavigationTestHost()

  @Test fun firstActivationOverridesWaitAdviceAndGuideDoesNotClearIt() {
    var activation by mutableStateOf(InitialActivationStatus.REBOOT_REQUIRED)
    var opened = 0
    rule.setContent { host.Provide { KeiHomeTheme { KeiHomeScaffold {
      Box(Modifier.verticalScroll(rememberScrollState())) {
        StatusBody(true, FrameworkSnapshot("Test", 102, true, null, null), false,
          null, false, {}, {}, activation = activation, onOpenGuide = { opened++ })
      }
    } } } }
    rule.onNodeWithText(context.getString(R.string.activation_reboot_title)).assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.polish_status_waiting)).assertDoesNotExist()
    rule.onNodeWithText(context.getString(R.string.status_hero_load_report_api, 102)).assertDoesNotExist()
    rule.onNodeWithTag("status.activation").assertIsDisplayed()
    capture("activation-pending.png")
    rule.onNodeWithTag("status.activation.guide").performScrollTo().performClick()
    rule.runOnIdle { assertEquals(1, opened) }
    rule.onNodeWithTag("status.activation").assertExists()
    rule.runOnIdle { activation = InitialActivationStatus.COMPLETE }
    rule.onNodeWithTag("status.activation").assertDoesNotExist()
    rule.onNodeWithText(context.getString(R.string.polish_status_waiting)).assertExists()
    rule.onNodeWithText(context.getString(R.string.status_hero_load_report_api, 102)).assertExists()
  }

  @Test fun unknownBootUsesExplicitUncertaintyInsteadOfWaitAdvice() {
    rule.setContent { host.Provide { KeiHomeTheme { KeiHomeScaffold {
      Box(Modifier.verticalScroll(rememberScrollState())) {
        StatusBody(true, FrameworkSnapshot("Test", 102, true, null, null), false,
          null, false, {}, {}, activation = InitialActivationStatus.UNKNOWN)
      }
    } } } }
    rule.onNodeWithText(context.getString(R.string.activation_reboot_unknown)).assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.polish_status_waiting)).assertDoesNotExist()
    capture("activation-unknown.png")
  }

  private fun capture(name: String) {
    rule.waitForIdle()
    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
      File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      bitmap.recycle()
    }
  }
}
