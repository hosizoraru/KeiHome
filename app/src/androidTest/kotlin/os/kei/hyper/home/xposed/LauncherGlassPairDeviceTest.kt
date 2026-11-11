package os.kei.hyper.home.xposed

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.kei.hyper.home.data.LauncherGlassChoice
import os.kei.hyper.home.data.LauncherGlassPreferences
import os.kei.hyper.home.data.LauncherGlassStyle
import os.kei.hyper.home.data.SearchGlassStyle
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.LauncherRuntimeCapabilities
import os.kei.hyper.home.status.RuntimeStatusProtocol
import os.kei.hyper.home.status.RuntimeStatusReport

/** Opt-in: exercises and restores real remote preferences without restarting the launcher. */
class LauncherGlassPairDeviceTest {
  @Test fun independentMaterialsAndFollowDockReceiveCurrentProcessAcknowledgements(): Unit = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("verifyGlassPair") == "true")
    val service = withTimeout(15_000) { FrameworkConnection.service.filterNotNull().first() }
    val original = LauncherGlassPreferences.readChoice(service)
    val initial = requireNotNull(RuntimeStatusProtocol.query(service))
    assertEquals(true, initial.supportsLauncherCapability(LauncherRuntimeCapabilities.GLASS_PAIR))
    val receipts = mutableListOf(initial.encode())
    suspend fun awaitChoice(choice: LauncherGlassChoice): RuntimeStatusReport = withTimeout(20_000) {
      while (true) {
        val report = RuntimeStatusProtocol.query(service)
        if (report != null && report.acknowledgesLauncherGlass(choice.dock) &&
          report.acknowledgesSearchGlass(choice)) {
          assertEquals(initial.launcherPid, report.launcherPid)
          assertEquals(initial.launcherStartTicks, report.launcherStartTicks)
          assertTrue(SystemClock.elapsedRealtime() - report.checkedAtElapsed in 0..30_000)
          receipts += report.encode()
          return@withTimeout report
        }
        delay(250)
      }
      @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    try {
      LauncherGlassPreferences.save(service, LauncherGlassStyle.DARK)
      LauncherGlassPreferences.saveSearch(service, SearchGlassStyle.LIGHT)
      awaitChoice(LauncherGlassChoice(LauncherGlassStyle.DARK, SearchGlassStyle.LIGHT))
      LauncherGlassPreferences.saveSearch(service, SearchGlassStyle.FOLLOW_DOCK)
      awaitChoice(LauncherGlassChoice(LauncherGlassStyle.DARK))
      LauncherGlassPreferences.save(service, LauncherGlassStyle.LIGHT)
      awaitChoice(LauncherGlassChoice(LauncherGlassStyle.LIGHT))
      LauncherGlassPreferences.saveSearch(service, SearchGlassStyle.SYSTEM)
      awaitChoice(LauncherGlassChoice(LauncherGlassStyle.LIGHT, SearchGlassStyle.SYSTEM))
    } finally {
      withContext(NonCancellable) {
        LauncherGlassPreferences.save(service, original.dock)
        LauncherGlassPreferences.saveSearch(service, original.search)
        awaitChoice(original)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "launcher-glass-pair-acceptance.txt").writeText(receipts.joinToString("\n"))
      }
    }
    Unit
  }
}
