package os.kei.hyper.home.ui.main

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** HyperOS can block ActivityScenario's launch after the previous test host closes. */
class ForegroundComposeHostRule : ExternalResource() {
  override fun before() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val target = instrumentation.targetContext.packageName
    val command = "am start -W -a android.intent.action.MAIN " +
      "-c android.intent.category.LAUNCHER -n $target/androidx.activity.ComponentActivity"
    ParcelFileDescriptor.AutoCloseInputStream(
      instrumentation.uiAutomation.executeShellCommand(command)
    ).use { it.readBytes() }
    instrumentation.waitForIdleSync()
  }
}
