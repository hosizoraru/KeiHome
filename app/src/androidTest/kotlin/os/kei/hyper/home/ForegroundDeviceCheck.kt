package os.kei.hyper.home

import androidx.test.core.app.ActivityScenario

internal inline fun <T> withForegroundDeviceCheck(block: () -> T): T =
  // A test-APK activity does not protect the target UID from HyperOS background freezing.
  ActivityScenario.launch(MainActivity::class.java).use { block() }
