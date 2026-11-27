package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerfectIconSyncHealthTest {
  @get:Rule val temporary = TemporaryFolder()

  private class Fixture(val root: File) {
    val settings = InMemoryPerfectIconSettings()
    val values get() = settings.values
    var time = 100L
    val backend = FakePerfectGitBackend(
      root,
      mapOf(
        "icons/test.app/0.png" to testPng(1),
        "icons/test.app/1.png" to testPng(2),
        "themes/default/icons/test.app.png" to testPng(1),
      ),
    )
    val calls get() = backend.fetches
    var failure: Exception?
      get() = backend.failure
      set(value) { backend.failure = value }
    var beforeDownload: suspend () -> Unit
      get() = backend.beforeFetch ?: {}
      set(value) { backend.beforeFetch = value }
    val repo get() = PerfectIconRepository(root, settings, now = { time }, git = backend)
    fun packFile(): File = backend.storeDir().listFiles()!!.first { it.extension == "pack" }
    fun tipFile(): File = File(backend.storeDir(), "tip")
  }

  @Test fun journalIsDurableBeforeNetworkAndLocalLoadsDoNotInterruptLiveSync() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val entered = CompletableDeferred<Unit>()
    val resume = CompletableDeferred<Unit>()
    f.beforeDownload = {
      val saved = JSONObject(f.values.getValue("syncHistoryV1"))
      assertEquals(100L, saved.getLong("startedAt"))
      assertTrue(saved.getString("owner").isNotEmpty())
      entered.complete(Unit)
      resume.await()
    }
    val task = async { f.repo.catalog(true, styles = setOf("default")) }
    entered.await()
    // Use a second history reader rather than acquiring the busy repository mutex.
    val live = PerfectSyncHistory(f.values::get, { key, value -> f.values[key] = value }, { f.time })
    assertNull(live.current().issue)
    assertEquals(100L, live.current().startedAtMillis)
    resume.complete(Unit)
    task.await()
    assertEquals(100L, f.repo.syncHistory().lastSuccessfulSyncAtMillis)
    assertNull(f.repo.syncHistory().issue)
  }

  @Test fun failedAndCancelledSyncPreserveGoodSourceAcrossRepositoryRecreation() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val catalog = f.repo.catalog(true, styles = setOf("default"))
    val pack = f.packFile()
    val original = pack.readBytes()
    f.time = 200
    f.failure = IOException("offline")
    try { f.repo.catalog(true, forceDownload = true, styles = setOf("default")); fail("Expected failure") } catch (_: IOException) { }
    assertEquals(PerfectSyncFailure.FAILED, f.repo.syncHistory().issue)
    assertEquals(200L, f.repo.syncHistory().issueAtMillis)
    assertEquals(100L, f.repo.syncHistory().lastSuccessfulSyncAtMillis)
    assertEquals(catalog, f.repo.catalog())
    assertEquals(PerfectLocalHealth.READY, f.repo.localStatus().health)
    assertArrayEquals(original, pack.readBytes())
    f.time = 40_000
    f.failure = CancellationException("cancel")
    try { f.repo.catalog(true, forceDownload = true, styles = setOf("default")); fail("Expected cancellation") }
    catch (_: CancellationException) { }
    assertEquals(PerfectSyncFailure.INTERRUPTED, f.repo.syncHistory().issue)
    assertEquals(40_000L, f.repo.syncHistory().issueAtMillis)
    assertArrayEquals(original, pack.readBytes())
    f.failure = null
    f.time = 50_000
    f.repo.catalog(true, forceDownload = true, styles = setOf("default"))
    assertNull(f.repo.syncHistory().issue)
    assertEquals(50_000L, f.repo.syncHistory().lastSuccessfulSyncAtMillis)
  }

  @Test fun generationFailureDoesNotBecomeSyncFailure() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true, styles = setOf("default"))
    val before = f.repo.syncHistory()
    try {
      f.repo.generate(PerfectIconOptions(style = "absent"), emptySet()) {}
      fail("Expected local generation failure")
    } catch (_: IllegalArgumentException) { }
    assertEquals(before, f.repo.syncHistory())
    assertEquals(PerfectLocalHealth.READY, f.repo.localStatus().health)
    assertEquals(1, f.calls)
  }

  @Test fun liveGenerationAndLocalReadsPreserveEarlierFailedSync() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true, styles = setOf("default"))
    val history = PerfectSyncHistory(f.values::get, { key, value -> f.values[key] = value }, { f.time })
    f.time = 200
    history.failed()
    val before = history.current()
    var observed = false
    f.repo.generate(PerfectIconOptions(), setOf("test.app")) {
      observed = true
      assertEquals(before, history.current())
    }
    assertTrue(observed)
    assertEquals(PerfectLocalHealth.READY, f.repo.localStatus().health)
    assertEquals(before, f.repo.syncHistory())
    assertEquals(1, f.calls)
  }

  @Test fun missingFirstInstallIsDistinctFromMalformedOrMissingReferencedSource() = runBlocking {
    val f = Fixture(temporary.newFolder())
    assertEquals(PerfectLocalHealth.MISSING, f.repo.localStatus().health)
    assertEquals(0, f.calls)
    f.repo.catalog(true, styles = setOf("default"))
    val pointer = File(f.root, "catalog.json")
    val original = pointer.readBytes()
    pointer.writeText("{malformed")
    f.time = 200
    assertEquals(PerfectLocalHealth.CORRUPT, f.repo.localStatus().health)
    assertEquals(200L, f.repo.localStatus().corruptAtMillis)
    assertEquals(100L, f.repo.syncHistory().lastSuccessfulSyncAtMillis)
    pointer.writeBytes(original)
    assertEquals(PerfectLocalHealth.READY, f.repo.localStatus().health)
    val pack = f.packFile()
    val packBytes = pack.readBytes()
    pack.writeBytes(ByteArray(packBytes.size + 1))
    assertEquals(PerfectLocalHealth.CORRUPT, f.repo.localStatus().health)
    pack.writeBytes(packBytes)
    assertEquals(PerfectLocalHealth.READY, f.repo.localStatus().health)
    f.tipFile().delete()
    assertEquals(PerfectLocalHealth.CORRUPT, f.repo.localStatus().health)
    assertNull(f.repo.catalog())
    pointer.delete()
    assertEquals(PerfectLocalHealth.CORRUPT, f.repo.localStatus().health)
    assertEquals(1, f.calls)
  }

  @Test fun sourceReadIntegrityFailurePersistsEvenWhenSizeMatches() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true, styles = setOf("default"))
    val pack = f.packFile()
    val bytes = pack.readBytes()
    pack.writeBytes(bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
    // The cheap metadata/size check makes no claim to have rehashed payloads.
    assertEquals(PerfectLocalHealth.READY, f.repo.localStatus().health)
    try {
      f.repo.generate(PerfectIconOptions(), setOf("test.app")) {}
      fail("Expected source read rejection")
    } catch (_: Exception) { }
    assertEquals(PerfectLocalHealth.CORRUPT, f.repo.localStatus().health)
    assertNull(f.repo.syncHistory().issue)
    assertEquals(100L, f.repo.syncHistory().lastSuccessfulSyncAtMillis)
    assertEquals(1, f.calls)
    f.time = 200
    f.repo.catalog(true, forceDownload = true, styles = setOf("default"))
    assertEquals(PerfectLocalHealth.READY, f.repo.localStatus().health)
  }

  @Test fun legacyV2CatalogOnDiskIsTreatedAsMissingAndReplacedCleanlyOnGitSync() = runBlocking {
    val f = Fixture(temporary.newFolder())
    File(f.root, "source").mkdirs()
    val legacyZip = File(f.root, "source/${"a".repeat(64)}.zip").apply { writeBytes(ByteArray(16)) }
    File(f.root, "catalog.json").writeText(JSONObject()
      .put("version", 2)
      .put("revision", "1".repeat(40))
      .put("sourceHash", "a".repeat(64))
      .put("archiveBytes", 16)
      .put("styles", JSONArray(listOf("default")))
      .put("updatedAt", 100)
      .put("downloadedAt", 100)
      .put("count", 1)
      .put("entries", JSONArray())
      .toString())
    f.values["knownSourceHash"] = "a".repeat(64)
    assertNull(f.repo.catalog())
    assertEquals(PerfectLocalHealth.MISSING, f.repo.localStatus().health)
    val synced = requireNotNull(f.repo.catalog(refresh = true, styles = setOf("default")))
    assertTrue(synced.gitBacked)
    assertEquals(PerfectLocalHealth.READY, f.repo.localStatus().health)
    assertFalse("Legacy source ZIP must be reclaimed on Git sync", legacyZip.exists())
  }

  @Test fun healthyLegacyCatalogSeedsTimestampWithoutErasingFailure() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true, styles = setOf("default"))
    f.values.remove("syncHistoryV1")
    val history = PerfectSyncHistory(f.values::get, { key, value -> f.values[key] = value }, { f.time })
    f.time = 200
    history.failed()
    f.repo.localStatus()
    assertEquals(100L, f.repo.syncHistory().lastSuccessfulSyncAtMillis)
    assertEquals(PerfectSyncFailure.FAILED, f.repo.syncHistory().issue)
    assertEquals(200L, f.repo.syncHistory().issueAtMillis)
  }

  @Test fun cachedHealthInvalidatesWhenPackOrCorruptionMarkerChanges() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val repo = f.repo
    repo.catalog(true, styles = setOf("default"))
    repeat(3) { assertEquals(PerfectLocalHealth.READY, repo.localStatus().health) }
    val pack = f.packFile()
    val original = pack.readBytes()
    val originalModified = pack.lastModified()
    pack.writeBytes(ByteArray(original.size + 1))
    assertTrue(pack.setLastModified(originalModified + 2_000))
    assertEquals(PerfectLocalHealth.CORRUPT, repo.localStatus().health)
    pack.writeBytes(original)
    assertTrue(pack.setLastModified(originalModified + 4_000))
    assertEquals(PerfectLocalHealth.READY, repo.localStatus().health)
    f.values["corruptSourceHash"] = repo.catalog()!!.sourceHash
    assertEquals(PerfectLocalHealth.CORRUPT, repo.localStatus().health)
    f.values["corruptSourceHash"] = ""
    assertEquals(PerfectLocalHealth.READY, repo.localStatus().health)
    assertEquals(1, f.calls)
  }

  @Test fun damagedHistoryNeverMarksHealthyResourceCorruptOrRewritesIt() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val repo = f.repo
    repo.catalog(true, styles = setOf("default"))
    val pack = f.packFile()
    val original = pack.readBytes()
    f.values["syncHistoryV1"] = "{broken"
    assertEquals(PerfectLocalHealth.READY, repo.localStatus().health)
    assertEquals(100L, repo.syncHistory().lastSuccessfulSyncAtMillis)
    assertNull(repo.syncHistory().issue)
    f.values["syncHistoryV1"] = """{"issue":"UNKNOWN_FUTURE_VALUE","lastSuccessAt":75}"""
    assertEquals(PerfectLocalHealth.READY, repo.localStatus().health)
    assertEquals(75L, repo.syncHistory().lastSuccessfulSyncAtMillis)
    assertNull(repo.syncHistory().issue)
    assertArrayEquals(original, pack.readBytes())
    assertEquals(1, f.calls)
  }
}
