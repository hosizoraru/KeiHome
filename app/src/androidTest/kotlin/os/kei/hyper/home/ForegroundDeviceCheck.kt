package os.kei.hyper.home

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

internal inline fun <T> withForegroundDeviceCheck(block: () -> T): T {
  // HyperOS can block the instrumented launch and leave startActivitySync waiting.
  val instrumentation = InstrumentationRegistry.getInstrumentation()
  val component = "${instrumentation.targetContext.packageName}/${MainActivity::class.java.name}"
  val launch = ParcelFileDescriptor.AutoCloseInputStream(
    instrumentation.uiAutomation.executeShellCommand("am start -W -n $component")
  ).use { it.bufferedReader().readText() }
  check("Status: ok" in launch) { launch }
  instrumentation.waitForIdleSync()
  var activity: MainActivity? = null
  instrumentation.runOnMainSync {
    activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
      .filterIsInstance<MainActivity>().singleOrNull()
  }
  val host = checkNotNull(activity) { "KeiHome did not resume: $launch" }
  try {
    return block()
  } finally {
    instrumentation.runOnMainSync { host.finish() }
  }
}
