package os.kei.hyper.home.xposed

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.NativeRuntimeDiagnostic
import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.RuntimeStatusProtocol

/** Explicit opt-in: authenticates the real app -> system_server -> native launcher roundtrip. */
class LauncherCapabilityRuntimeDeviceTest {
  @Test fun realResidentCapabilitiesAreReportedWithoutPretendingAnUnsupportedSettingWasApplied() = runBlocking {
    val args = InstrumentationRegistry.getArguments()
    assumeTrue(args.containsKey("expectedLauncherCapabilities"))
    val expected = requireNotNull(args.getString("expectedLauncherCapabilities")).toInt()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val service = withTimeout(15_000L) { FrameworkConnection.service.filterNotNull().first() }
    val report = requireNotNull(RuntimeStatusProtocol.query(service)) { "No live system report" }
    File(context.cacheDir, "launcher-capability-runtime.txt").writeText(report.encode())
    assertEquals(BuildConfig.VERSION_CODE.toLong(), report.moduleVersion)
    assertEquals(expected, report.launcherCapabilities)
    assertTrue(requireNotNull(report.launcherPid) > 0)
    assertTrue(requireNotNull(report.launcherStartTicks) > 0)
    assertTrue(SystemClock.elapsedRealtime() - report.checkedAtElapsed in 0..30_000)
    if (expected == 0) {
      assertEquals(NativeRuntimeState.UNAVAILABLE, report.nativeState)
      assertEquals(NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE, report.detail)
      assertFalse(report.launcherGlassAvailable)
      assertNull(report.dockGlassEnabled)
      assertNull(report.launcherLabelMode)
    }
  }
}
