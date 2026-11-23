package os.kei.hyper.home.iconpack

import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import os.kei.hyper.home.withForegroundDeviceCheck
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedServiceHelper
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.iconPackTargets
import os.kei.hyper.home.iconpack.apple.AppleIconRepository
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.IconPackHandoff
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.status.readFrameworkSnapshot

/**
 * Explicitly imports real packs into Themes; never applies them or changes user settings.
 * Enable only with instrumentation argument realImportMatrix=1. Ordinary suites skip
 * before inventory, service discovery, preferences access, or report creation.
 */
@RunWith(AndroidJUnit4::class)
class NativeIconPackImportMatrixDeviceTest {
  private data class Candidate(
    val pack: IconPackDescriptor,
    val origins: MutableSet<String>,
  )

  @Test fun importsAllAvailablePacksWithoutApplying() {
    assumeTrue("Real native imports require explicit opt-in",
      InstrumentationRegistry.getArguments().getString("realImportMatrix") == "1")
    withForegroundDeviceCheck {
    runBlocking(Dispatchers.IO) {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val started = SystemClock.elapsedRealtime()
      val destination = File(context.cacheDir, "large-pack-matrix.json")
      val inventories = JSONArray()
      val issues = JSONArray()
      val rows = JSONArray()
      val report = JSONObject()
        .put("schema", 1)
        .put("startedAt", System.currentTimeMillis())
        .put("userId", Process.myUid() / 100_000)
        .put("autoApply", false)
        .put("applicationVerified", false)
        .put("coverage", "All repository-discovered packs and existing local publications")
        .put("inventories", inventories).put("inventoryIssues", issues).put("packs", rows)
        .put("status", "inventory")

      fun save() {
        report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
        val atomic = AtomicFile(destination)
        val stream = atomic.startWrite()
        try {
          stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
          atomic.finishWrite(stream)
        } catch (failure: Throwable) {
          atomic.failWrite(stream)
          throw failure
        }
      }

      val repository = IconPackRepository(context)
      val arguments = InstrumentationRegistry.getArguments()
      val sourceIds = arguments.getString("sourceIds")?.split(',')?.toSet()
      val originPrefix = arguments.getString("originPrefix")
      val candidates = linkedMapOf<String, Candidate>()
      fun add(pack: IconPackDescriptor, origin: String) {
        if (sourceIds != null && pack.id !in sourceIds) return
        val previous = candidates[pack.id]
        if (previous == null) candidates[pack.id] = Candidate(pack, linkedSetOf(origin))
        else previous.origins += origin
      }

      suspend fun scan(origin: String, load: suspend () -> IconPackScanResult) {
        if (originPrefix != null && !origin.startsWith(originPrefix)) return
        val began = SystemClock.elapsedRealtime()
        try {
          val result = load()
          result.packs.forEach { add(it, origin) }
          result.failures.forEach {
            issues.put(JSONObject().put("origin", origin).put("sourceId", it.sourceId)
              .put("error", it.message))
          }
          inventories.put(JSONObject().put("origin", origin).put("count", result.packs.size)
            .put("visibilityLimited", result.visibilityLimited)
            .put("elapsedMs", SystemClock.elapsedRealtime() - began))
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: Exception) {
          issues.put(JSONObject().put("origin", origin).put("error", failure.toString()))
        }
        save()
      }

      save()
      try {
        val requestedStyle = arguments.getString("perfectStyle")
        val generatedDigest = if (requestedStyle != null) {
          report.put("coverage", "Explicit Perfect Icons style only; other inventories are context")
          check(requestedStyle == "iOS") { "Unsupported explicit style acceptance request" }
          val perfect = os.kei.hyper.home.iconpack.perfect.PerfectIconRepository(context)
          check(requestedStyle in requireNotNull(perfect.catalog()).downloadedStyles) { "The requested style is not downloaded" }
          val options = os.kei.hyper.home.iconpack.perfect.PerfectIconOptions(
            style = requestedStyle, installedOnly = arguments.getString("perfectInstalledOnly") == "1",
            dynamic = arguments.getString("perfectDynamic") != "0",
            staticNight = arguments.getString("perfectStaticNight") != "0",
            fixedDark = arguments.getString("perfectFixedDark") == "1")
          val began = SystemClock.elapsedRealtime()
          val existing = InstrumentationRegistry.getArguments().getString("existingPerfectArchive")?.let { name ->
            check(name.matches(Regex("perfect-[a-f0-9]{12}-[a-f0-9]{24}\\.zip"))) { "Expected a publication filename" }
            File(context.filesDir, "perfect-icons/generated/$name").also {
              check(Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS))
            }
          }
          val generated = if (existing == null) perfect.generate(options,
            if (options.installedOnly) iconPackTargets(context) {}.mapTo(hashSetOf()) { it.packageName }
            else emptySet()) {} else null
          report.put("generatedStyle", requestedStyle).put("generationMs", SystemClock.elapsedRealtime() - began)
            .put("generatedArchive", (generated?.file ?: existing!!).absolutePath)
            .put("reusedExistingPublication", existing != null)
          generated?.let { report.put("generatedMappings", it.mapped) }
          val caller = currentCoroutineContext()
          sha256(generated?.file ?: existing!!) { caller.ensureActive() }
        } else null
        scan("installed", repository::listInstalled)
        scan("imported", repository::listImported)
        scan("builtin", repository::listBuiltins)
        scan("GeneratedApple") {
          val apple = AppleIconRepository(context).descriptor()
          check(apple != null ||
            !File(context.noBackupFilesDir, "apple-icons/current.json").exists()) {
            "An Apple publication exists but its descriptor could not be read"
          }
          IconPackScanResult(listOfNotNull(apple))
        }

        // Read existing Perfect publications without importing/pruning the local
        // catalog, regenerating artwork, downloading, or changing producer settings.
        val generated = File(context.filesDir, "perfect-icons/generated")
        val publications = if (!generated.exists()) emptyList() else {
          check(generated.isDirectory) { "Perfect publication path is not a directory" }
          requireNotNull(generated.listFiles()) { "Cannot enumerate Perfect publications" }
            .filter { it.extension.equals("zip", ignoreCase = true) }.sortedBy { it.name }
        }
        report.put("existingPerfectPublications", publications.size)
        for (file in publications) {
          currentCoroutineContext().ensureActive()
          scan("GeneratedPerfect:${file.name}") {
            check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
              "Perfect publication is not a regular immutable file"
            }
            val caller = currentCoroutineContext()
            val digest = sha256(file) { caller.ensureActive() }
            val existing = candidates[digest]?.pack
            if (existing != null) IconPackScanResult(listOf(existing))
            else {
              val nested = File.createTempFile("matrix-nested-", ".icons", context.cacheDir)
              try {
                val index = IconPackArchive().inspect(file, nested, checkCancelled = { caller.ensureActive() })
                val record = StoredIconPack(digest, file.name, file.lastModified(), index.format,
                  file, null, file.length(), index.nativeIconsSha256, GeneratedPackProducer.PERFECT)
                IconPackScanResult(listOf(IconPackDescriptor(digest, file.name, index.format,
                  IconPackSource.Imported(record), index.entries, index.description, index.previews,
                  index.notices, index.directives)))
              } finally {
                nested.delete()
              }
            }
          }
        }
        if (generatedDigest != null) candidates.keys.retainAll(setOf(generatedDigest))
        check(sourceIds == null || candidates.keys == sourceIds) { "Requested sources are missing from the inventory" }
        if (sourceIds != null || originPrefix != null) {
          report.put("coverage", "Explicit import subset")
        }
        val minimumMappings = arguments.getString("minimumMappings")?.toIntOrNull() ?: 0
        if (minimumMappings > 0) {
          candidates.values.removeAll { it.pack.entries.size < minimumMappings }
          report.put("minimumMappings", minimumMappings)
        }
        val testGenericMonet = arguments.getString("testGenericMonet") == "1"
        report.put("genericMonet", testGenericMonet)
        check(candidates.isNotEmpty()) { "No import candidates were discovered" }
        candidates.values.forEach {
          rows.put(JSONObject().put("sourceId", it.pack.id).put("displayName", it.pack.displayName)
            .put("origins", JSONArray(it.origins.toList())).put("format", it.pack.format.name)
            .put("mappingCount", it.pack.entries.size).put("status", "queued")
            .put("localId", JSONObject.NULL).put("title", JSONObject.NULL)
            .put("convertedReady", JSONObject.NULL))
        }
        report.put("inventoryComplete", true).put("candidateCount", candidates.size)
          .put("status", "awaiting_service")
        save()

        // Same live-service entry point as the existing runtime Device tests.
        // Never call reloadMismatchedSystemTargets or attempt scope/activation changes.
        XposedServiceHelper.registerListener(FrameworkConnection)
        val service = withTimeoutOrNull(10_000) { FrameworkConnection.service.filterNotNull().first() }
        checkNotNull(service) { "No live Xposed service; inventory only, no import was attempted" }
        val framework = service.readFrameworkSnapshot()
        val installedVersion = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        report.put("service", JSONObject().put("framework", framework.name)
          .put("apiVersion", framework.apiVersion).put("systemInScope", framework.systemInScope)
          .put("themesInScope", framework.themesInScope).put("installedVersion", installedVersion)
          .put("systemVersion", framework.target?.loadedVersionCode ?: JSONObject.NULL)
          .put("systemState", framework.target?.state?.name ?: JSONObject.NULL))
        save()
        check(framework.apiVersion >= 102 && framework.systemInScope && framework.themesInScope) {
          "Live API-102 service with system and Themes scope is required"
        }
        check(framework.target?.state == HookedTarget.State.UP_TO_DATE &&
          framework.target.loadedVersionCode == installedVersion) {
          "system_server must already be running the installed module; no reload is attempted"
        }
        check(IconPackImportProtocol.pendingSource(context) == null) {
          "An existing import is pending; do not replace its request"
        }

        val selected = AppPreferences.get(context).state.value
        val caller = currentCoroutineContext()
        val targets = iconPackTargets(context) { caller.ensureActive() }
        val previews = IconPackPreviewRepository(context)
        report.put("targetCount", targets.size).put("status", "importing")
        var blocked: String? = null
        for ((index, candidate) in candidates.values.withIndex()) {
          currentCoroutineContext().ensureActive()
          val row = rows.getJSONObject(index)
          if (blocked != null) {
            row.put("status", "blocked").put("error", blocked)
            save()
            continue
          }
          val began = SystemClock.elapsedRealtime()
          var handoff: IconPackHandoff? = null
          var submitted = false
          try {
            check(FrameworkConnection.service.value === service) { "Live service changed" }
            check(IconPackImportProtocol.pendingSource(context) == null) {
              "A different import is pending"
            }
            val pack = candidate.pack.copy(id = selected.systemIconFallback.sourceId(candidate.pack.id))
            row.put("status", "preparing").put("preparedSourceId", pack.id)
            save()
            val prepared = IconPackHandoffs.prepare(context, repository, previews, pack, targets,
              selected.iconPackAppearance, includePalette = true,
              systemFallback = selected.systemIconFallback,
              systemAppNameMatching = selected.systemAppNameMatching,
              colorOsForOtherPacks = selected.colorOsForOtherPacks,
              installedOnly = selected.installedIconsOnly,
              completeSystemIconConfig = selected.completeSystemIconConfig,
              recommendedMappings = selected.recommendedIconMappings,
              monetUnadaptedIcons = testGenericMonet,
              onProgress = ColorOsProgressReporter({ progress ->
                row.put("stage", progress.stage.name).put("stageDone", progress.done)
                  .put("stageTotal", progress.total)
              })::update)
            handoff = prepared
            row.put("prepareMs", SystemClock.elapsedRealtime() - began)
              .put("convertedOrigin", prepared.convertedOrigin ?: JSONObject.NULL)
              .put("handoffBytes", prepared.file.length()).put("token", prepared.token)
              .put("status", "submitting")
            save()
            check(FrameworkConnection.service.value === service) { "Service changed during preparation" }
            check(IconPackImportProtocol.pendingSource(context) == null) {
              "Another import started during preparation"
            }
            val importStarted = SystemClock.elapsedRealtime()
            submitted = true
            var result = IconPackImportProtocol.submit(context, service, prepared, autoApply = false)
            // A large pack can outlive submit's normal receipt timeout. Recheck only
            // this request, retaining its handoff and never replacing a pending owner.
            val deadline = SystemClock.elapsedRealtime() + 180_000L
            while ((result == null || result.pending) && SystemClock.elapsedRealtime() < deadline) {
              val completed = IconPackImportProtocol.state(context).result?.takeIf {
                it.token == prepared.token && it.sourceId == prepared.sourceId && !it.pending
              }
              if (completed != null) {
                result = completed
                break
              }
              check(FrameworkConnection.service.value === service) { "Service changed while importing" }
              check(IconPackImportProtocol.state(context).pendingToken == prepared.token) {
                "Pending receipt ownership changed"
              }
              IconPackImportProtocol.recheck(context, service, canPublish = {
                val state = IconPackImportProtocol.state(context)
                state.pendingToken == prepared.token && state.pendingSource == prepared.sourceId
              })
              result = withTimeoutOrNull(120_000) {
                IconPackImportProtocol.states(context).filterNotNull().first {
                  it.result?.let { report ->
                    report.token == prepared.token && report.sourceId == prepared.sourceId && !report.pending
                  } == true
                }.result
              } ?: result
              if (result == null || result.pending) delay(500)
            }
            row.put("importMs", SystemClock.elapsedRealtime() - importStarted)
              .put("nativeStatus", result?.status ?: JSONObject.NULL)
              .put("localId", result?.localId ?: JSONObject.NULL)
              .put("title", result?.title ?: JSONObject.NULL)
              .put("convertedReady", result?.convertedReady ?: JSONObject.NULL)
              .put("nativeMessage", result?.message ?: JSONObject.NULL)
            check(result != null && result.token == prepared.token &&
              result.sourceId == prepared.sourceId && result.userId == Process.myUid() / 100_000 &&
              result.imported && (prepared.convertedOrigin == null || result.convertedReady == true) &&
              result.status != "APPLIED" && result.autoApplyToken == null) {
              "Import/conversion was not confirmed: ${result?.status}, ${result?.message}"
            }
            row.put("status", "passed")
          } catch (cancelled: CancellationException) {
            row.put("status", "cancelled")
            throw cancelled
          } catch (failure: Exception) {
            row.put("status", "failed").put("error", failure.toString())
          } finally {
            // A published/uncertain handoff belongs to the production receipt lifecycle.
            if (!submitted) handoff?.file?.delete()
            row.put("elapsedMs", SystemClock.elapsedRealtime() - began)
            save()
          }
          if (IconPackImportProtocol.pendingSource(context) != null) {
            blocked = "An import remains pending; its request and handoff were retained"
          } else if (FrameworkConnection.service.value !== service) {
            blocked = "Live service changed; no reconnect/reload was attempted"
          }
        }
        val passed = (0 until rows.length()).count { rows.getJSONObject(it).optString("status") == "passed" }
        val unchanged = AppPreferences.get(context).state.value == selected
        report.put("passed", passed).put("userPreferencesUnchanged", unchanged)
          .put("status", if (passed == candidates.size && issues.length() == 0 && unchanged)
            "imports_passed_application_not_tested" else "failed")
        save()
        assertTrue("Import matrix incomplete; inspect ${destination.absolutePath}",
          passed == candidates.size && issues.length() == 0 && unchanged)
      } catch (cancelled: CancellationException) {
        report.put("status", "cancelled")
        throw cancelled
      } catch (failure: Throwable) {
        report.put("status", "failed").put("error", failure.toString())
        throw failure
      } finally {
        report.put("finishedAt", System.currentTimeMillis())
        save()
      }
    }
    }
  }
}
