package os.kei.hyper.home.iconpack.perfect

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerfectGitRepositoryTest {
  @get:Rule val temporary = TemporaryFolder()

  private class Settings : PerfectIconSettings {
    val values = mutableMapOf<String, String>()
    override fun read(key: String) = values[key]
    override fun write(key: String, value: String) { values[key] = value }
  }

  private class Backend(val root: File) : PerfectIconGitBackend {
    var revision = "a".repeat(40)
    var available = listOf("HyperOS4", "default")
    var checks = 0
    var fetches = 0
    var failure: Exception? = null
    var requests = emptySet<String>()
    var compact = false
    val files = linkedMapOf("icons/test.app/0.png" to png(10), "icons/test.app/1.png" to png(20))
    val objects = mutableMapOf<String, ByteArray>()
    private val store get() = File(root, "git/test-store")
    override suspend fun check(progress: (PerfectIconProgress) -> Unit): PerfectIconRemote {
      checks++
      failure?.let { throw it }
      return PerfectIconRemote(revision, 1_800_000_000_000, "Resource update", 1_800_000_001_000)
    }
    override suspend fun fetch(currentStore: String?, repair: Boolean, styles: Set<String>,
      progress: (PerfectIconProgress) -> Unit): PerfectGitSnapshot {
      fetches++
      requests = styles
      failure?.let { throw it }
      store.mkdirs()
      val entries = files.map { (path, bytes) ->
        val hash = PerfectIconFormat.sha(bytes)
        objects[hash] = bytes
        File(store, hash).writeBytes(bytes)
        PerfectIconSourceEntry(path, hash, bytes.size.toLong(), 0)
      }
      return PerfectGitSnapshot("test-store", PerfectIconRemote(revision, 1_800_000_000_000,
        "Resource update", 1_800_000_001_000), entries,
        store.listFiles().orEmpty().sumOf { it.length() }, 100, available, styles.toList())
    }
    override fun open(store: String): PerfectGitReader = object : PerfectGitReader {
      override fun read(entry: PerfectIconSourceEntry, check: () -> Unit): ByteArray {
        check()
        return requireNotNull(objects[entry.sha]).also {
          require(PerfectIconFormat.sha(it) == entry.sha && it.size.toLong() == entry.size)
        }
      }
      override fun healthy(entries: List<PerfectIconSourceEntry>, check: () -> Unit) = entries.all {
        check()
        File(root, "git/$store/${it.sha}").isFile
      }
      override fun close() = Unit
    }
    override fun exists(store: String) = File(root, "git/$store").isDirectory
    override fun stamp(store: String) = files.keys.joinToString()
    override fun maintenanceNeeded(store: String, liveBytes: Long) = compact
    override suspend fun retain(store: String, entries: List<PerfectIconSourceEntry>,
      progress: (PerfectIconProgress) -> Unit): PerfectGitRetained {
      failure?.let { throw it }
      val destination = File(root, "git/retained-store").apply { mkdirs() }
      entries.forEach { File(destination, it.sha).writeBytes(requireNotNull(objects[it.sha])) }
      return PerfectGitRetained(destination.name, destination.listFiles().orEmpty().sumOf { it.length() })
    }
    override fun published(store: String, revision: String) {
      File(root, "git").listFiles().orEmpty().filter { it.isDirectory && it.name != store }
        .forEach { it.deleteRecursively() }
    }
  }

  private class Fixture(val root: File) {
    val settings = Settings()
    val backend = Backend(root)
    var time = 1_800_000_002_000L
    val repository = PerfectIconRepository(root, settings, now = { time }, git = backend)
  }

  @Test fun checkOnlyStoresRemoteMetadataWithoutDownloadingOrMarkingSyncSuccessful() = runBlocking {
    val f = Fixture(temporary.newFolder())
    assertNull(f.repository.catalog())
    val remote = f.repository.checkUpdates()
    assertEquals(remote, f.repository.remoteStatus())
    assertEquals(0, f.backend.fetches)
    assertNull(f.repository.catalog())
    assertEquals(0, f.repository.syncHistory().lastSuccessfulSyncAtMillis)
  }

  @Test fun acquisitionDistinguishesAvailableAndDownloadedStylesAndKeepsExistingStyles() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val first = requireNotNull(f.repository.catalog(refresh = true, styles = setOf("HyperOS4")))
    assertEquals(listOf("HyperOS4", "default"), first.styles)
    assertEquals(listOf("HyperOS4"), first.downloadedStyles)
    assertTrue(first.gitBacked)
    assertThrows(IllegalArgumentException::class.java) { runBlocking {
      f.repository.generate(PerfectIconOptions(style = "default", installedOnly = false), emptySet()) {}
    } }
    val second = requireNotNull(f.repository.catalog(refresh = true, styles = setOf("default")))
    assertEquals(setOf("HyperOS4", "default"), f.backend.requests)
    assertEquals(first.styles, second.downloadedStyles)
    assertEquals(PerfectLocalHealth.READY, f.repository.localStatus().health)
  }

  @Test fun offlineGenerationAndPreviewDoNotCheckOrFetch() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repository.catalog(refresh = true, styles = setOf("default"))
    f.backend.failure = IOException("Offline")
    val options = PerfectIconOptions(installedOnly = false, dynamic = false)
    val generated = f.repository.generate(options, emptySet()) {}
    assertTrue(generated.file.isFile)
    assertTrue(f.repository.previewArchive(options).isFile)
    f.repository.clearDownloadCache()
    assertTrue(f.repository.previewArchive(options).isFile)
    assertEquals(0, f.backend.checks)
    assertEquals(1, f.backend.fetches)
  }

  @Test fun failedAndCancelledFetchPreservePublishedSourceAndLastSuccess() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val original = f.repository.catalog(refresh = true)
    val success = f.repository.syncHistory().lastSuccessfulSyncAtMillis
    for (failure in listOf(IOException("Network"), CancellationException("Cancel"))) {
      f.time += 5000
      f.backend.failure = failure
      assertThrows(failure.javaClass) { runBlocking { f.repository.catalog(refresh = true) } }
      assertEquals(original, f.repository.catalog())
      assertEquals(success, f.repository.syncHistory().lastSuccessfulSyncAtMillis)
      assertEquals(PerfectLocalHealth.READY, f.repository.localStatus().health)
    }
  }

  @Test fun remoteCheckFailureDoesNotBecomeResourceSyncFailure() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val original = f.repository.catalog(refresh = true)
    val history = f.repository.syncHistory()
    f.time += 5000
    f.backend.failure = IOException("Check offline")
    assertThrows(IOException::class.java) { runBlocking { f.repository.checkUpdates() } }
    assertEquals(original, f.repository.catalog())
    assertEquals(history, f.repository.syncHistory())
  }

  @Test fun replacementIndexDropsDeletedAndRenamedResourcesFromGeneration() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repository.setOptions(PerfectIconOptions())
    f.repository.catalog(refresh = true)
    val oldHash = f.repository.catalog()?.sourceHash
    f.backend.revision = "b".repeat(40)
    f.backend.files.clear()
    f.backend.files["icons/new.app/0.png"] = png(30)
    f.backend.files["icons/new.app/1.png"] = png(40)
    val catalog = requireNotNull(f.repository.catalog(refresh = true))
    assertNotEquals(oldHash, catalog.sourceHash)
    val result = f.repository.generate(PerfectIconOptions(installedOnly = false, dynamic = false), emptySet()) {}
    ZipFile(result.file).use { zip ->
      val names = zip.entries().asSequence().map { it.name }.toList()
      assertTrue(names.any { "new.app" in it })
      assertFalse(names.any { "test.app" in it })
    }
  }

  @Test fun deleteRemovesSourcesAndDerivedCachesButPreservesExportsAndOptions() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val options = PerfectIconOptions(installedOnly = false, dynamic = false)
    f.repository.setOptions(options)
    f.repository.catalog(refresh = true)
    f.repository.checkUpdates()
    val export = f.repository.generate(options, emptySet()) {}.file
    f.repository.previewArchive(options)
    val before = f.repository.resourceBytes()
    assertTrue(before > 0)
    assertEquals(before, f.repository.deleteResources())
    assertEquals(0, f.repository.resourceBytes())
    assertNull(f.repository.catalog())
    assertNull(f.repository.remoteStatus())
    assertEquals(PerfectLocalHealth.MISSING, f.repository.localStatus().health)
    assertEquals(0, f.repository.syncHistory().lastSuccessfulSyncAtMillis)
    assertEquals(options, f.repository.options())
    assertTrue(export.isFile)
    assertEquals(0, f.repository.deleteResources())
  }

  @Test fun corruptObjectIsReportedAndNeverPublishedAsGeneratedArchive() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repository.setOptions(PerfectIconOptions())
    f.repository.catalog(refresh = true)
    val sha = f.backend.objects.keys.first()
    f.backend.objects[sha] = byteArrayOf(0)
    assertThrows(IllegalArgumentException::class.java) { runBlocking {
      f.repository.generate(PerfectIconOptions(installedOnly = false), emptySet()) {}
    } }
    assertEquals(PerfectLocalHealth.CORRUPT, f.repository.localStatus().health)
    assertTrue(File(f.root, "generated").listFiles().orEmpty().isEmpty())
  }

  @Test fun removingOneStyleRetainsSharedObjectsAndLastSyncWithoutNetwork() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val exclusivePath = "themes/HyperOS4/icons/special.app.png"
    val exclusive = png(50)
    f.backend.files[exclusivePath] = exclusive
    f.repository.catalog(refresh = true, styles = setOf("default", "HyperOS4"))
    val footprints = f.repository.catalog()!!.styleDetails
    assertEquals(footprints.getValue("default").sharedBytes, footprints.getValue("HyperOS4").sharedBytes)
    footprints.values.forEach { assertEquals(it.bytes, it.ownBytes + it.sharedBytes) }
    val history = f.repository.syncHistory()
    f.time += 5000
    val before = f.repository.resourceBytes()
    val fetched = f.backend.fetches
    val released = f.repository.removeStyles(setOf("HyperOS4"))
    assertTrue(released > 0)
    assertEquals(before - released, f.repository.resourceBytes())
    assertEquals(listOf("default"), f.repository.catalog()?.downloadedStyles)
    assertEquals(footprints.getValue("default"), f.repository.catalog()!!.styleDetails.getValue("default"))
    assertFalse(f.repository.catalog()!!.styleDetails.containsKey("HyperOS4"))
    assertEquals(history, f.repository.syncHistory())
    assertEquals(fetched, f.backend.fetches)
    assertEquals(0, f.backend.checks)
    assertFalse(File(f.root, "git/retained-store/${PerfectIconFormat.sha(exclusive)}").exists())
    assertEquals(PerfectLocalHealth.READY, f.repository.localStatus().health)
    assertTrue(f.repository.generate(PerfectIconOptions(installedOnly = false, dynamic = false), emptySet()) {}.file.isFile)
    assertTrue(f.repository.removeStyles(setOf("default")) > 0)
    assertNull(f.repository.catalog())
  }

  @Test fun failedLocalStyleRemovalRetainsOriginalGraphAndReadiness() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val original = f.repository.catalog(refresh = true, styles = setOf("default", "HyperOS4"))
    f.backend.failure = IOException("No storage space")
    assertThrows(IOException::class.java) { runBlocking { f.repository.removeStyles(setOf("HyperOS4")) } }
    assertEquals(original, f.repository.catalog())
    assertEquals(PerfectLocalHealth.READY, f.repository.localStatus().health)
  }

  @Test fun boundedMaintenanceOnNewRevisionKeepsSameStyleOfflineReady() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repository.setOptions(PerfectIconOptions())
    f.repository.catalog(refresh = true)
    f.backend.revision = "b".repeat(40)
    f.backend.compact = true
    val catalog = f.repository.catalog(refresh = true)
    assertEquals(f.backend.revision, catalog?.revision)
    assertFalse(File(f.root, "git/test-store").exists())
    assertTrue(File(f.root, "git/retained-store").exists())
    assertEquals(PerfectLocalHealth.READY, f.repository.localStatus().health)
    assertTrue(f.repository.generate(PerfectIconOptions(installedOnly = false, dynamic = false), emptySet()) {}.file.isFile)
  }

  @Test fun wrappedGitThrottlePausesChecksWithoutChangingSyncHistory() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val until = f.time + 60_000
    f.backend.failure = IOException("Transport", PerfectIconCooldownException(until))
    assertThrows(PerfectIconCooldownException::class.java) { runBlocking { f.repository.checkUpdates() } }
    assertEquals(until, f.repository.retryAtMillis())
    assertThrows(PerfectIconCooldownException::class.java) { runBlocking { f.repository.checkUpdates() } }
    assertEquals(1, f.backend.checks)
    assertEquals(PerfectSyncHistoryState(), f.repository.syncHistory())
    f.time = until + 1
    f.backend.failure = null
    assertNotNull(f.repository.checkUpdates())
    assertEquals(2, f.backend.checks)
  }

  companion object {
    private fun png(color: Int): ByteArray = ByteArrayOutputStream().also { output ->
      output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
      fun chunk(type: String, bytes: ByteArray) {
        val name = type.toByteArray()
        DataOutputStream(output).apply {
          writeInt(bytes.size); write(name); write(bytes)
          writeInt(CRC32().apply { update(name); update(bytes) }.value.toInt())
        }
      }
      chunk("IHDR", ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply { writeInt(1); writeInt(1); write(byteArrayOf(8, 6, 0, 0, 0)) }
      }.toByteArray())
      chunk("IDAT", ByteArrayOutputStream().also { bytes ->
        DeflaterOutputStream(bytes).use { it.write(byteArrayOf(0, color.toByte(), 0, 0, -1)) }
      }.toByteArray())
      chunk("IEND", byteArrayOf())
    }.toByteArray()
  }
}
