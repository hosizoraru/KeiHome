package os.kei.hyper.home.iconpack.apple

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.AppLocalStorage

class AppleBatchMatchingDeviceTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val options = AppleIconOptions(country = "cn")
  private fun target(index: Int) = AppleAppTarget("test.batch.app$index", "BatchFixture$index", false)

  private class SearchServer : AppleIconTransport {
    val requests = mutableListOf<String>()
    var empty = false
    var ambiguous = false
    var limited = false
    var malformed = false
    var allowLookup = false
    lateinit var cacheRoot: File
    var suspendTerm: String? = null
    var resume: CompletableDeferred<Unit>? = null
    var cancellationAsIo = false
    val suspended = CompletableDeferred<Unit>()
    override suspend fun get(url: String): ByteArray {
      requests += url
      check("/search?" in url || allowLookup && "/lookup?" in url) { "Unexpected endpoint: $url" }
      val query = URI(url).rawQuery.split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
      }
      if (limited) throw AppleRequestException(429, System.currentTimeMillis() + 60_000)
      if (malformed) return """{"unexpected":"schema"}""".toByteArray()
      val term = query["term"] ?: "LookupById"
      if (term == suspendTerm) {
        suspended.complete(Unit)
        try { resume?.await() ?: awaitCancellation() }
        catch (cancelled: kotlinx.coroutines.CancellationException) {
          if (cancellationAsIo) throw IOException("Socket closed during cancellation")
          throw cancelled
        }
      }
      val results = JSONArray()
      if (!empty) repeat(if (ambiguous) 2 else 1) { index ->
        results.put(JSONObject().put("trackId", (123456700 + index).toString())
          .put("trackName", term).put("artistName", "Fixture $index")
          .put("bundleId", "test.apple.fixture$index").put("kind", "software")
          .put("supportedDevices", JSONArray().put("iPhone").put("iPad")))
      }
      return JSONObject().put("results", results).toString().toByteArray()
    }
  }

  private fun fixture(block: suspend (AppleIconRepository, SearchServer, () -> AppleIconRepository) -> Unit) = runBlocking {
    val namespace = "apple_batch_${UUID.randomUUID()}"
    val directory = File(context.cacheDir, namespace)
    val server = SearchServer()
    server.cacheRoot = directory
    fun open() = AppleIconRepository(context,
      AppleIconClient(server, metadataIntervalMillis = 0), directory, namespace)
    try { block(open(), server, ::open) }
    finally { directory.deleteRecursively(); AppLocalStorage.open(context, namespace).clearAll() }
  }

  @Test fun tenThenRemainingThenNoRequestsAndMappingsSurviveRecreation() = fixture { repo, server, open ->
    val targets = List(12, ::target)
    val first = repo.autoMatch(targets, options) { _, _ -> }
    assertEquals(10, first.attempted); assertEquals(10, first.matched)
    assertEquals(10, server.requests.size)
    val reopened = open()
    val second = reopened.autoMatch(targets, options) { _, _ -> }
    assertEquals(2, second.matched); assertEquals(12, server.requests.size)
    assertEquals(0, reopened.autoMatch(targets, options) { _, _ -> }.attempted)
    assertEquals(12, server.requests.size)
    assertTrue(targets.all { reopened.match(it, options)?.country == "cn" })
    assertTrue(targets.none { reopened.custom(it, options) })
    assertTrue(server.requests.all { "country=cn" in it })
  }

  @Test fun recentMissesAreSkippedWhileUntouchedAppsAdvanceAndRegionIsIndependent() = fixture { repo, server, open ->
    server.empty = true
    val targets = List(11, ::target)
    assertEquals(10, repo.autoMatch(targets, options) { _, _ -> }.unmatched)
    assertEquals(1, open().autoMatch(targets, options) { _, _ -> }.unmatched)
    assertEquals(0, repo.autoMatch(targets, options) { _, _ -> }.attempted)
    assertEquals(11, server.requests.size)
    val us = options.copy(country = "us")
    assertNull(repo.matchAttempt(target(0), us))
    assertEquals(1, repo.autoMatch(targets, us, setOf(target(0).packageName)) { _, _ -> }.unmatched)
    assertEquals(12, server.requests.size)
    assertEquals(AppleMatchAttemptStatus.NO_MATCH, open().matchAttempt(target(0), options)?.status)
  }

  @Test fun duplicateTitlesRequireReviewAndKeepCandidatesLocally() = fixture { repo, server, open ->
    server.ambiguous = true
    val app = target(0)
    val result = repo.autoMatch(listOf(app), options) { _, _ -> }
    assertEquals(1, result.review); assertEquals(0, result.matched)
    assertNull(repo.match(app, options))
    val record = open().matchAttempt(app, options)!!
    assertEquals(AppleMatchAttemptStatus.REVIEW, record.status)
    assertEquals(2, record.candidates.size)
    assertEquals(0, repo.autoMatch(listOf(app), options) { _, _ -> }.attempted)
    assertEquals(1, server.requests.size)
    repo.setMatch(app, options, record.candidates.last())
    assertEquals(record.candidates.last(), repo.match(app, options))
    assertTrue(repo.custom(app, options))
  }

  @Test fun rateLimitStopsBatchAndPersistsCooldownWithoutClassifyingRemainingAsMisses() = fixture { repo, server, open ->
    server.limited = true
    val targets = List(4, ::target)
    val result = repo.autoMatch(targets, options) { _, _ -> }
    assertEquals(1, result.failed); assertEquals(0, result.unmatched)
    assertEquals(1, server.requests.size)
    assertEquals(AppleMatchAttemptStatus.FAILED, repo.matchAttempt(targets[0], options)?.status)
    assertNull(repo.matchAttempt(targets[1], options))
    val reopened = open()
    assertTrue(reopened.retryAtMillis() > System.currentTimeMillis())
    assertEquals(0, reopened.autoMatch(targets, options) { _, _ -> }.attempted)
    assertEquals(1, server.requests.size)
  }

  @Test fun manualChoicesExclusionsAndPresetsAreNeverOverwritten() = fixture { repo, server, _ ->
    val excluded = target(0)
    val manual = target(1)
    val automatic = target(2)
    val preset = AppleAppTarget("com.android.settings", "设置", true)
    val choice = AppleAppMatch("123456789", "Manual", "Publisher", country = "jp")
    repo.setMatch(excluded, options, null)
    repo.setMatch(manual, options, choice)
    val presetChoice = repo.match(preset, options)
    assertNotNull(presetChoice)
    val result = repo.autoMatch(listOf(excluded, manual, preset, automatic), options) { _, _ -> }
    assertEquals(1, result.matched); assertEquals(1, server.requests.size)
    assertNull(repo.match(excluded, options)); assertTrue(repo.custom(excluded, options))
    assertEquals(choice, repo.match(manual, options))
    assertEquals(presetChoice, repo.match(preset, options))
    assertFalse(repo.custom(automatic, options))
    val exported = AppleMappingTransfer.decode(repo.exportMappings())
    assertEquals(3, exported.size)
    assertEquals("cn", exported.single { it.packageName == automatic.packageName }.country)
    repo.resetMatch(automatic, options)
    assertNull(repo.match(automatic, options))
    repo.importMappings(AppleMappingTransfer.encode(exported))
    assertNotNull(repo.match(automatic, options))
    assertTrue(repo.custom(automatic, options))
  }

  @Test fun cancellationKeepsCompletedMappingsAndDoesNotMarkInflightSearchAsMissing() = fixture { repo, server, open ->
    val targets = List(3, ::target)
    server.suspendTerm = targets[1].label
    server.cancellationAsIo = true
    kotlinx.coroutines.coroutineScope {
      val job = launch { repo.autoMatch(targets, options) { _, _ -> } }
      server.suspended.await()
      job.cancelAndJoin()
    }
    val reopened = open()
    assertNotNull(reopened.match(targets[0], options))
    assertNull(reopened.matchAttempt(targets[1], options))
    assertNull(reopened.matchAttempt(targets[2], options))
    assertEquals(2, server.requests.size)
    server.suspendTerm = null
    assertEquals(2, reopened.autoMatch(targets, options) { _, _ -> }.matched)
  }

  @Test fun manualChangeDuringSearchWinsOverAutomaticResult() = fixture { repo, server, _ ->
    val app = target(0)
    server.suspendTerm = app.label
    server.resume = CompletableDeferred()
    kotlinx.coroutines.coroutineScope {
      val job = launch { repo.autoMatch(listOf(app), options) { _, _ -> } }
      server.suspended.await()
      repo.setMatch(app, options, null)
      server.resume!!.complete(Unit)
      job.join()
    }
    assertTrue(repo.custom(app, options))
    assertNull(repo.match(app, options))
  }

  @Test fun thirdPartyNameDoesNotGetShortCircuitedToASystemPreset() = fixture { repo, server, _ ->
    val weather = AppleAppTarget("test.thirdparty.weather", "天气", false)
    assertNull(repo.match(weather, options))
    assertEquals(1, repo.autoMatch(listOf(weather), options) { _, _ -> }.matched)
    assertEquals("天气", repo.match(weather, options)?.name)
    assertEquals(1, server.requests.size)
  }

  @Test fun renamedLabelsAndPlatformsHaveIndependentHistory() = fixture { repo, server, _ ->
    server.empty = true
    val original = target(0)
    assertEquals(1, repo.autoMatch(listOf(original), options) { _, _ -> }.unmatched)
    val renamed = original.copy(label = "RenamedFixture")
    assertNull(repo.matchAttempt(renamed, options))
    assertEquals(1, repo.autoMatch(listOf(renamed), options) { _, _ -> }.unmatched)
    assertNull(repo.matchAttempt(original, options.copy(platform = ApplePlatform.MACOS)))
    assertEquals(1, repo.autoMatch(listOf(original), options.copy(platform = ApplePlatform.MACOS)) { _, _ -> }.unmatched)
    assertEquals(3, server.requests.size)
  }

  @Test fun largeInventoryStillUsesOnlyTenSearchesAndNoImageDownloads() = fixture { repo, server, _ ->
    val targets = List(320, ::target)
    val result = repo.autoMatch(targets, options) { _, _ -> }
    assertEquals(10, result.attempted)
    assertEquals(310, result.skipped)
    assertEquals(10, server.requests.size)
    assertNull(repo.matchAttempt(targets.last(), options))
  }

  @Test fun invalidSuccessfulResponseUsesFailureCooldownInsteadOfASevenDayMiss() = fixture { repo, server, open ->
    server.malformed = true
    val targets = List(3, ::target)
    val result = repo.autoMatch(targets, options) { _, _ -> }
    assertEquals(1, result.failed); assertEquals(0, result.unmatched)
    assertEquals(1, server.requests.size)
    assertEquals(AppleMatchAttemptStatus.FAILED, open().matchAttempt(targets[0], options)?.status)
    assertNull(repo.matchAttempt(targets[1], options))
    // Failed app is cooling down; untouched applications can still be attempted.
    server.malformed = false
    assertEquals(2, open().autoMatch(targets, options) { _, _ -> }.matched)
    assertEquals(3, server.requests.size)
  }

  @Test fun malformedCachedSearchIsDiscardedBeforeRefetch() = fixture { repo, server, _ ->
    val app = target(0)
    repo.search(app.label, options)
    val key = AppleIconPolicy.key("search:IOS:cn:${app.label.lowercase(java.util.Locale.ROOT)}")
    val cached = File(server.cacheRoot, "downloads/metadata/$key.json")
    assertTrue(cached.isFile)
    cached.writeText(JSONObject().put("time", System.currentTimeMillis()).toString())
    assertEquals(1, repo.autoMatch(listOf(app), options) { _, _ -> }.matched)
    assertEquals(2, server.requests.size)
    assertTrue(JSONObject(cached.readText()).has("apps"))
  }

  @Test fun numericTitleSearchIsSeparateFromManualStoreIdLookup() = fixture { repo, server, _ ->
    server.allowLookup = true
    assertEquals("LookupById", repo.search("123456", options).single().name)
    val app = AppleAppTarget("test.numeric.app", "123456", false)
    assertEquals(1, repo.autoMatch(listOf(app), options) { _, _ -> }.matched)
    assertEquals("123456", repo.match(app, options)?.name)
    assertEquals(2, server.requests.size)
    assertTrue("/lookup?" in server.requests[0])
    assertTrue("/search?" in server.requests[1])
    assertTrue("term=123456" in server.requests[1])
  }
}
