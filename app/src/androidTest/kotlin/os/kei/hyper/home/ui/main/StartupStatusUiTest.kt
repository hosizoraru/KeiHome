package os.kei.hyper.home.ui.main

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.FrameworkTarget
import io.github.libxposed.service.HookedTarget
import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class StartupStatusUiTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val backHost = Os4NavigationTestHost()

  @Test fun pendingBindAndSnapshotTransitionDirectlyToVerifiedSuccess() {
    var state by mutableStateOf(FrameworkUiState(checking = true))
    val report = RuntimeStatusReport("test", 0, BuildConfig.VERSION_CODE.toLong(),
      SystemClock.elapsedRealtime(), NativeRuntimeState.READY)
    rule.setContent {
      backHost.Provide { KeiHomeTheme { KeiHomeScaffold {
        Box(Modifier.verticalScroll(rememberScrollState())) {
          StatusBody(state.connected, state.snapshot, state.readFailed, null, false, {}, {},
            runtime = RuntimeStatusUiState(report = report), frameworkChecking = state.checking)
        }
      } } }
    }
    title(R.string.runtime_checking).assertExists()
    title(R.string.status_overview_error_title).assertDoesNotExist()
    rule.runOnIdle { state = FrameworkUiState(connected = true, checking = true) }
    title(R.string.runtime_checking).assertExists()
    title(R.string.status_overview_error_title).assertDoesNotExist()
    rule.runOnIdle { state = FrameworkUiState(true, FrameworkSnapshot("Test", 102, true,
      FrameworkTarget(HookedTarget.State.UP_TO_DATE, BuildConfig.VERSION_CODE.toLong()), null)) }
    title(R.string.runtime_module_ready).assertExists()
    title(R.string.runtime_checking).assertDoesNotExist()
  }

  @Test fun completedDetectionStillShowsDisconnectionAndReadFailures() {
    var state by mutableStateOf(FrameworkUiState(checking = true))
    rule.setContent {
      backHost.Provide { KeiHomeTheme { KeiHomeScaffold {
        Box(Modifier.verticalScroll(rememberScrollState())) {
          StatusBody(state.connected, state.snapshot, state.readFailed, null, false, {}, {},
            frameworkChecking = state.checking)
        }
      } } }
    }
    title(R.string.runtime_checking).assertExists()
    rule.runOnIdle { state = FrameworkUiState() }
    title(R.string.status_overview_error_title).assertExists()
    rule.runOnIdle { state = FrameworkUiState(connected = true, readFailed = true) }
    title(R.string.status_overview_error_title).assertExists()
    title(R.string.status_framework_error).assertExists()
  }

  private fun title(resource: Int) = rule.onNode(
    hasText(context.getString(resource)) and hasAnyAncestor(hasTestTag("status.framework.card")),
    useUnmergedTree = true)
}
