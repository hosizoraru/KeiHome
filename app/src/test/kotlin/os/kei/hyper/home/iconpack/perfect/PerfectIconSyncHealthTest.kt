package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerfectIconSyncHealthTest {
  @get:Rule val temporary = TemporaryFolder()

  private class Fixture(val root: File) {
    val values = mutableMapOf<String, String>()
    var time = 100L
    var calls = 0
    var failure: Exception? = null
    var beforeDownload: suspend () -> Unit = {}
    val bytes = PerfectIconRepositoryTest.zip(mapOf(
      "icons/test.app/0.png" to PerfectIconRepositoryTest.png(1),
      "icons/test.app/1.png" to PerfectIconRepositoryTest.png(2),
      "themes/default/icons/test.app.png" to PerfectIconRepositoryTest.png(1)), stored = true)
    private val settings = object : PerfectIconSettings {
      override fun read(key: String) = values[key]
      override fun write(key: String, value: String) { values[key] = value }
    }
    val repo get() = PerfectIconRepository(root, object : PerfectIconTransport {
      override suspend fun download(url: String, destination: File, limit: Long,
        headers: Map<String, String>, progress: (PerfectIconProgress) -> Unit): PerfectIconHttpResponse {
        calls++
        beforeDownload()
        failure?.let { throw it }
        destination.writeBytes(bytes)
        return PerfectIconHttpResponse(200, mapOf("ETag" to "\"test\""))
      }
    }, settings, now = { time })
    fun source(): File = File(root, "source").listFiles()!!.single { it.extension == "zip" }
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
    val task = async { f.repo.catalog(true) }
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
    val catalog = f.repo.catalog(true)
    val source = f.source()
    val original = source.readBytes()
    f.time = 200
    f.failure = IOException("offline")
    try { f.repo.catalog(true, forceDownload = true); fail("Expected failure") } catch (_: IOException) { }
    assertEquals(PerfectSyncFailure.FAILED, f.repo.syncHistory().issue)
    assertEquals(200L, f.repo.syncHistory().issueAtMillis)
    assertEquals(100L, f.repo.syncHistory().lastSuccessfulSyncAtMillis)
    assertEquals(catalog, f.repo.catalog())
    assertEquals(PerfectLocalHealth.READY, f.repo.localStatus().health)
    assertArrayEquals(original, source.readBytes())
    f.time = 40_000
    f.failure = CancellationException("cancel")
    try { f.repo.catalog(true, forceDownload = true); fail("Expected cancellation") }
    catch (_: CancellationException) { }
    assertEquals(PerfectSyncFailure.INTERRUPTED, f.repo.syncHistory().issue)
    assertEquals(40_000L, f.repo.syncHistory().issueAtMillis)
    assertArrayEquals(original, source.readBytes())
    f.failure = null
    f.time = 50_000
    f.repo.catalog(true, forceDownload = true)
    assertNull(f.repo.syncHistory().issue)
    assertEquals(50_000L, f.repo.syncHistory().lastSuccessfulSyncAtMillis)
  }

  @Test fun generationFailureDoesNotBecomeSyncFailure() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true)
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
    f.repo.catalog(true)
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
    f.repo.catalog(true)
    val pointer = File(f.root, "catalog.json")
    val original = pointer.readBytes()
    pointer.writeText("{malformed")
    f.time = 200
    assertEquals(PerfectLocalHealth.CORRUPT, f.repo.localStatus().health)
    assertEquals(200L, f.repo.localStatus().corruptAtMillis)
    assertEquals(100L, f.repo.syncHistory().lastSuccessfulSyncAtMillis)
    pointer.writeBytes(original)
    assertEquals(PerfectLocalHealth.READY, f.repo.localStatus().health)
    val source = f.source()
    val sourceBytes = source.readBytes()
    source.writeBytes(ByteArray(sourceBytes.size))
    assertEquals(PerfectLocalHealth.CORRUPT, f.repo.localStatus().health)
    source.writeBytes(sourceBytes)
    assertEquals(PerfectLocalHealth.READY, f.repo.localStatus().health)
    f.source().delete()
    assertEquals(PerfectLocalHealth.CORRUPT, f.repo.localStatus().health)
    assertNull(f.repo.catalog())
    pointer.delete()
    assertEquals(PerfectLocalHealth.CORRUPT, f.repo.localStatus().health)
    assertEquals(1, f.calls)
  }

  @Test fun sourceReadIntegrityFailurePersistsEvenWhenSizeAndZipDirectoryMatch() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true)
    val source = f.source()
    val bytes = source.readBytes()
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    RandomAccessFile(source, "rw").use { file ->
      (0..bytes.size - 30).filter { header.getInt(it) == 0x04034b50 }.forEach { offset ->
        val payload = offset + 30 + (header.getShort(offset + 26).toInt() and 0xffff) +
          (header.getShort(offset + 28).toInt() and 0xffff)
        file.seek(payload.toLong())
        file.write(bytes[payload].toInt() xor 1)
      }
    }
    // The cheap directory check makes no claim to have rehashed payloads.
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
    f.repo.catalog(true, forceDownload = true)
    assertEquals(PerfectLocalHealth.READY, f.repo.localStatus().health)
  }

  @Test fun healthyLegacyCatalogSeedsTimestampWithoutErasingFailure() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true)
    f.values.remove("syncHistoryV1")
    val history = PerfectSyncHistory(f.values::get, { key, value -> f.values[key] = value }, { f.time })
    f.time = 200
    history.failed()
    f.repo.localStatus()
    assertEquals(100L, f.repo.syncHistory().lastSuccessfulSyncAtMillis)
    assertEquals(PerfectSyncFailure.FAILED, f.repo.syncHistory().issue)
    assertEquals(200L, f.repo.syncHistory().issueAtMillis)
  }

  @Test fun cachedHealthInvalidatesWhenSameLengthZipOrCorruptionMarkerChanges() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val repo = f.repo
    repo.catalog(true)
    repeat(3) { assertEquals(PerfectLocalHealth.READY, repo.localStatus().health) }
    val source = f.source()
    val original = source.readBytes()
    val originalModified = source.lastModified()
    source.writeBytes(ByteArray(original.size))
    assertTrue(source.setLastModified(originalModified + 2_000))
    assertEquals(PerfectLocalHealth.CORRUPT, repo.localStatus().health)
    source.writeBytes(original)
    assertTrue(source.setLastModified(originalModified + 4_000))
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
    repo.catalog(true)
    val source = f.source()
    val original = source.readBytes()
    f.values["syncHistoryV1"] = "{broken"
    assertEquals(PerfectLocalHealth.READY, repo.localStatus().health)
    assertEquals(100L, repo.syncHistory().lastSuccessfulSyncAtMillis)
    assertNull(repo.syncHistory().issue)
    f.values["syncHistoryV1"] = """{"issue":"UNKNOWN_FUTURE_VALUE","lastSuccessAt":75}"""
    assertEquals(PerfectLocalHealth.READY, repo.localStatus().health)
    assertEquals(75L, repo.syncHistory().lastSuccessfulSyncAtMillis)
    assertNull(repo.syncHistory().issue)
    assertArrayEquals(original, source.readBytes())
    assertEquals(1, f.calls)
  }
}
