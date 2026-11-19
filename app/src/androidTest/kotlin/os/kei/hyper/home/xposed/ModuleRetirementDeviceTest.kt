package os.kei.hyper.home.xposed

import androidx.test.platform.app.InstrumentationRegistry
import io.github.libxposed.service.HotReloadResult
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.kei.hyper.home.status.FrameworkConnection

/** Opt-in: exercises normal API 102 handoffs, never process restart or native activation. */
class ModuleRetirementDeviceTest {
  /** Read the framework's receipt after the autonomous retry, without dispatching another reload. */
  @Test fun recoveredPidRunsInstalledVersion() {
    val args = InstrumentationRegistry.getArguments()
    val pid = args.getString("recoveredPid")?.toIntOrNull()
    assumeTrue("Provide the original PID from the FAILED receipt", pid != null)
    val process = requireNotNull(args.getString("recoveredProcess"))
    val service = runBlocking {
      withTimeout(10_000) { FrameworkConnection.service.filterNotNull().first() }
    }
    val target = service.runningTargets.single { it.pid == pid && it.processName == process }
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val installed = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
    android.util.Log.i("KeiHomeReloadTest", "$process/$pid ${target.state} loaded=${target.loadedVersionCode} installed=$installed")
    assertEquals(io.github.libxposed.service.HookedTarget.State.UP_TO_DATE, target.state)
    assertEquals(installed, target.loadedVersionCode)
  }

  @Test fun twoSameProcessHandoffsForEachLiveJavaHost() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("retirementReload") == "true")
    val service = runBlocking {
      withTimeout(10_000) { FrameworkConnection.service.filterNotNull().first() }
    }
    assertTrue(service.apiVersion >= 102)
    val targets = service.runningTargets.filter {
      it.processName in setOf("system_server", "system", "com.android.systemui", "com.android.thememanager")
    }
    assertTrue("No SystemUI target", targets.any { it.processName == "com.android.systemui" })
    assertTrue("No system_server target", targets.any { it.processName in setOf("system_server", "system") })
    val results = JSONArray()
    val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
      "module-retirement-reloads.json")
    for (round in 1..2) {
      for (target in targets) {
        val future = CompletableFuture<HotReloadResult>()
        service.hotReloadModule(target, null) { _, result -> future.complete(result) }
        val result = future.get(15, TimeUnit.SECONDS)
        results.put(JSONObject().put("round", round).put("process", target.processName)
          .put("status", result.status.name).put("message", result.message))
        output.writeText(results.toString(2))
        assertEquals("${target.processName}: ${result.message}", HotReloadResult.Status.SUCCEEDED,
          result.status)
      }
    }
  }
}
