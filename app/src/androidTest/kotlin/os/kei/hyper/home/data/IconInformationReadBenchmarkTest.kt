package os.kei.hyper.home.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.iconpack.IconPackRepository
import os.kei.hyper.home.iconpack.IconPackScanResult
import os.kei.hyper.home.ui.main.iconPackCardData

/** Explicitly selected diagnostic; reads the existing catalog without import/remove/export. */
@RunWith(AndroidJUnit4::class)
class IconInformationReadBenchmarkTest {
  private val runId = SystemClock.elapsedRealtimeNanos().toString()

  @Test fun recordsIconInformationReadStages() = runBlocking {
    val selected = InstrumentationRegistry.getArguments().getString("class").orEmpty()
    assumeTrue("Select this diagnostic class or method explicitly",
      selected.split(',').any { it == IconInformationReadBenchmarkTest::class.java.name ||
        it.startsWith("${IconInformationReadBenchmarkTest::class.java.name}#") })
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    log(JSONObject().put("kind", "environment")
      .put("sdk", Build.VERSION.SDK_INT).put("hardware", Build.HARDWARE).put("model", Build.MODEL)
      .put("timing", "serial stage wall times; not UI latency or a production speedup")
      .put("processColdGuaranteed", false))
    withContext(Dispatchers.IO) {
      val repository = IconPackRepository(context)
      val caller = currentCoroutineContext()
      val check = { caller.ensureActive() }
      for (pass in listOf("new_repository", "same_repository", "installed_cache_invalidated")) {
        check()
        // This is the manual-refresh path's in-memory invalidation, not an on-disk change.
        if (pass == "installed_cache_invalidated") repository.invalidateInstalled()
        val targets = timed(pass, "iconPackTargets", {
          JSONObject().put("targets", it.size).put("packages", it.map { target -> target.packageName }.toSet().size)
        }) { iconPackTargets(context, check) }
        val installed = timed(pass, "listInstalled", ::scanCounts) { repository.listInstalled() }
        val imported = timed(pass, "listImported", ::scanCounts) { repository.listImported() }
        val builtins = timed(pass, "listBuiltins", ::scanCounts) { repository.listBuiltins() }
        val builtinIds = builtins.packs.mapTo(hashSetOf()) { it.id }
        val packs = builtins.packs + installed.packs + imported.packs.filterNot { it.id in builtinIds }
        timed(pass, "cardConversion", {
          JSONObject().put("cards", it.size).put("targets", targets.size)
            .put("entries", packs.sumOf { pack -> pack.entries.size })
            .put("matchedApps", it.sumOf { card -> card.matchedApps })
        }) {
          withContext(Dispatchers.Default) {
            packs.map { pack ->
              currentCoroutineContext().ensureActive()
              iconPackCardData(pack, targets)
            }
          }
        }
      }
      // Deliberately after the normal stages: isolates repeated manifest parsing on warm resources.
      timed("after_stage_passes", "overlayManifestBreakdown", { it }) {
        overlayManifestBreakdown(context, check)
      }
    }
    log(JSONObject().put("kind", "complete"))
  }

  private fun overlayManifestBreakdown(context: Context, check: () -> Unit): JSONObject {
    val started = SystemClock.elapsedRealtimeNanos()
    val launcherPackages = requireNotNull(context.getSystemService(LauncherApps::class.java))
      .getActivityList(null, Process.myUserHandle()).mapTo(hashSetOf()) { it.componentName.packageName }
    check()
    val pm = context.packageManager
    val installed = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    val candidates = installed.filter { app ->
      check()
      app.packageName !in launcherPackages && '.' in app.packageName && app.icon != 0 &&
        app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }
    val discovered = SystemClock.elapsedRealtimeNanos()
    var overlays = 0
    var manifestNanos = 0L
    var maxManifestNanos = 0L
    for (app in candidates) {
      check()
      val before = SystemClock.elapsedRealtimeNanos()
      if (isResourceOverlay(pm, app)) overlays++
      val elapsed = SystemClock.elapsedRealtimeNanos() - before
      manifestNanos += elapsed
      maxManifestNanos = maxOf(maxManifestNanos, elapsed)
    }
    return JSONObject().put("installedApplications", installed.size)
      .put("launcherPackages", launcherPackages.size).put("manifestChecks", candidates.size)
      .put("overlays", overlays).put("discoveryMs", (discovered - started) / 1_000_000.0)
      .put("manifestChecksMs", manifestNanos / 1_000_000.0)
      .put("maxManifestCheckMs", maxManifestNanos / 1_000_000.0)
  }

  private fun scanCounts(scan: IconPackScanResult): JSONObject = JSONObject()
    .put("packs", scan.packs.size).put("entries", scan.packs.sumOf { it.entries.size })
    .put("failures", scan.failures.size).put("visibilityLimited", scan.visibilityLimited)

  private suspend fun <T> timed(
    pass: String, stage: String, counts: (T) -> JSONObject, read: suspend () -> T,
  ): T {
    currentCoroutineContext().ensureActive()
    val started = SystemClock.elapsedRealtimeNanos()
    try {
      val result = read()
      val elapsed = SystemClock.elapsedRealtimeNanos() - started
      log(JSONObject().put("kind", "stage").put("pass", pass).put("stage", stage)
        .put("elapsedMs", elapsed / 1_000_000.0).put("counts", counts(result)))
      return result
    } catch (failure: Throwable) {
      log(JSONObject().put("kind", "stage_failure").put("pass", pass).put("stage", stage)
        .put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
        .put("exception", failure.javaClass.name))
      throw failure
    }
  }

  private fun log(row: JSONObject) {
    Log.i("KeiHomeIconReadBenchmark", row.put("schema", 1).put("runId", runId).toString())
  }
}
