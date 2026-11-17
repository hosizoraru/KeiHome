package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerfectFallbackSessionTest {
  @get:Rule val temporary = TemporaryFolder()

  private class Settings : PerfectIconSettings {
    val values = mutableMapOf<String, String>()
    override fun read(key: String) = values[key]
    override fun write(key: String, value: String) { values[key] = value }
  }

  private class Fixture(val root: File,
    equivalentRenderer: ((ByteArray, List<ByteArray>, String?, () -> Unit) -> PerfectStaticPair)? = null,
    renderer: ((List<ByteArray>, String?, () -> Unit) -> PerfectStaticPair)? = null) {
    val settings = Settings()
    val files = linkedMapOf(
      "icons/dev.thirdparty.client/0.png" to png(10),
      "icons/dev.thirdparty.client/1.png" to png(20),
      "icons/dev.thirdparty.client.MainActivity/0.png" to png(30),
      "icons/dev.thirdparty.client.MainActivity/1.png" to png(40),
      "themes/default/icons/com.android.settings.png" to png(50),
    )
    var downloads = 0
    val repo = PerfectIconRepository(root, PerfectIconTransport { _, destination, _, _, _ ->
      downloads++
      check(downloads == 1) { "Local completion must not download" }
      destination.writeBytes(PerfectIconRepositoryTest.zip(files))
      PerfectIconHttpResponse(200, emptyMap())
    }, settings, staticRenderer = renderer, staticEquivalentRenderer = equivalentRenderer)
    suspend fun acquire() { repo.catalog(refresh = true) }
    val archive get() = File(root, "source").listFiles()!!.single()
    val catalog get() = File(root, "catalog.json")
  }

  /** A disk-backed source that counts Git work separately from constant-time file stats. */
  private class Backend(val root: File) : PerfectIconGitBackend {
    val files = linkedMapOf(
      "icons/dev.thirdparty.client/0.png" to png(10),
      "icons/dev.thirdparty.client/1.png" to png(20),
    )
    val directory get() = File(root, "git/test-store")
    var fetches = 0
    var opens = 0
    var closes = 0
    var stamps = 0
    override suspend fun check(progress: (PerfectIconProgress) -> Unit): PerfectIconRemote =
      error("Local completion must not check the remote")
    override suspend fun fetch(currentStore: String?, repair: Boolean, styles: Set<String>,
      progress: (PerfectIconProgress) -> Unit): PerfectGitSnapshot {
      check(++fetches == 1)
      directory.mkdirs()
      File(directory, "tip").writeText("a".repeat(40))
      val entries = files.map { (path, bytes) ->
        val sha = PerfectIconFormat.sha(bytes)
        File(directory, "$sha.pack").writeBytes(bytes)
        PerfectIconSourceEntry(path, sha, bytes.size.toLong(), 0)
      }
      return PerfectGitSnapshot("test-store", PerfectIconRemote("a".repeat(40), 0, "", 1), entries,
        directory.listFiles()!!.sumOf { it.length() }, 100, listOf("default", "missing"), listOf("default"))
    }
    override fun open(store: String): PerfectGitReader {
      opens++
      return object : PerfectGitReader {
        override fun read(entry: PerfectIconSourceEntry, check: () -> Unit): ByteArray {
          check()
          return File(directory, "${entry.sha}.pack").readBytes().also {
            require(it.size.toLong() == entry.size && PerfectIconFormat.sha(it) == entry.sha)
          }
        }
        override fun healthy(entries: List<PerfectIconSourceEntry>, check: () -> Unit): Boolean =
          entries.all { check(); File(directory, "${it.sha}.pack").isFile }
        override fun close() { closes++ }
      }
    }
    override fun exists(store: String) = directory.isDirectory && File(directory, "tip").isFile
    override fun stamp(store: String): String {
      stamps++
      return directory.listFiles().orEmpty().sortedBy { it.name }
        .joinToString { "${it.name}:${it.length()}:${it.lastModified()}" }
    }
    override suspend fun retain(store: String, entries: List<PerfectIconSourceEntry>,
      progress: (PerfectIconProgress) -> Unit): PerfectGitRetained = error("Completion must not compact Git")
    override fun published(store: String, revision: String) = Unit
  }

  @Test fun missingSourceAndUndownloadedStyleThrowWithoutNetwork() = runBlocking {
    val f = Fixture(temporary.newFolder())
    rejected { f.repo.openFallbackSession("default") }
    assertEquals(0, f.downloads)
    f.acquire()
    rejected { f.repo.openFallbackSession("missing") }
    rejected { f.repo.openFallbackSession("../default") }
    val backend = Backend(temporary.newFolder())
    val repo = gitRepository(backend)
    repo.catalog(refresh = true)
    rejected { repo.openFallbackSession("missing") }
    assertEquals(0, backend.opens)
    assertEquals(1, backend.fetches)
    assertEquals(1, f.downloads)
  }

  @Test fun nightFallbackUsesSharedDerivedCacheWithoutChangingDayLayersOrDownloading() = runBlocking {
    var renders = 0
    val f = Fixture(temporary.newFolder()) { layers, mask, check ->
      check()
      assertNull(mask)
      assertArrayEquals(png(10), layers[0])
      assertArrayEquals(png(20), layers[1])
      renders++
      PerfectStaticPair(png(60), png(70))
    }
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    assertArrayEquals(png(10), requireNotNull(source.icon(listOf("dev.thirdparty.client"))).layers[0])
    repeat(2) {
      assertArrayEquals(png(70),
        requireNotNull(source.icon(listOf("dev.thirdparty.client"), darkMode = true)).layers.single())
    }
    source.close()
    f.repo.openFallbackSession("default").let { reopened ->
      assertArrayEquals(png(70),
        requireNotNull(reopened.icon(listOf("dev.thirdparty.client"), darkMode = true)).layers.single())
      assertArrayEquals(png(20),
        requireNotNull(reopened.icon(listOf("dev.thirdparty.client"))).layers[1])
      reopened.close()
    }
    assertEquals(1, renders)
    assertEquals(1, f.downloads)
    assertFalse(File(f.root, "generated").exists())
  }

  @Test fun cancellingNightFallbackDoesNotPublishPartialArtwork() = runBlocking {
    val f = Fixture(temporary.newFolder()) { _, _, _ -> throw CancellationException("night cancelled") }
    f.acquire()
    f.repo.openFallbackSession("default").let { source ->
      try {
        source.icon(listOf("dev.thirdparty.client"), darkMode = true)
        fail("Expected cancellation")
      } catch (_: CancellationException) { }
      assertArrayEquals(png(10), requireNotNull(source.icon(listOf("dev.thirdparty.client"))).layers[0])
      source.close()
    }
    assertTrue(File(f.root, "rendered").listFiles().isNullOrEmpty())
  }

  @Test fun flatDayArtworkKeepsItsEquivalentLayersForNightFallback() = runBlocking {
    var renders = 0
    val f = Fixture(temporary.newFolder(), equivalentRenderer = { flat, layers, mask, check ->
      check()
      assertNull(mask)
      assertArrayEquals(png(30), flat)
      assertArrayEquals(png(10), layers[0])
      assertArrayEquals(png(20), layers[1])
      renders++
      PerfectStaticPair(flat, png(70))
    })
    f.files["themes/default/icons/dev.thirdparty.client.png"] = png(30)
    f.files["themes/default/icons/dev.thirdparty.client/0.png"] = png(10)
    f.files["themes/default/icons/dev.thirdparty.client/1.png"] = png(20)
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    try {
      assertArrayEquals(png(30),
        requireNotNull(source.icon(listOf("dev.thirdparty.client"))).layers.single())
      assertArrayEquals(png(70),
        requireNotNull(source.icon(listOf("dev.thirdparty.client"), darkMode = true)).layers.single())
      assertEquals(1, renders)
    } finally { source.close() }
    assertEquals(1, f.downloads)
  }

  @Test fun indexesArbitraryStaticNamesAndExcludesLiveOnlyAndIncompleteIcons() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.files["icons/dev.incomplete/0.png"] = png(60)
    f.files["themes/default/layer_animating_icons/dev.live/config.xml"] = "<Config/>".toByteArray()
    f.files["themes/default/layer_animating_icons/dev.live/0/quiet/quietImage.png"] = png(70)
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    assertEquals(f.repo.fallbackSourceHash("default"), source.sourceHash)
    assertEquals(setOf("dev.thirdparty.client", "dev.thirdparty.client.MainActivity",
      "com.android.settings"), source.nativeNames)
    assertArrayEquals(png(30), requireNotNull(source.icon(listOf("../invalid", "dev.absent",
      "dev.thirdparty.client.MainActivity", "dev.thirdparty.client"))).layers[0])
    assertArrayEquals(png(10), requireNotNull(source.icon(listOf("dev.thirdparty.client"))).layers[0])
    assertNull(source.icon(listOf("dev.live", "dev.incomplete")))
    rejected { (source.nativeNames as MutableSet<String>).add("dev.injected") }
    source.validate()
    source.close()
    assertEquals(1, f.downloads)
    assertFalse(File(f.root, "generated").exists())
    assertFalse(File(f.root, "previews").exists())
  }

  @Test fun flatStyleOverrideWinsOverBaseLayersEvenWhenLiveArtworkExists() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.files["themes/default/icons/dev.thirdparty.client.png"] = png(80)
    f.files["themes/default/layer_animating_icons/dev.thirdparty.client/config.xml"] =
      "<Config/>".toByteArray()
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    assertTrue("dev.thirdparty.client" in source.nativeNames)
    val icon = requireNotNull(source.icon(listOf("dev.thirdparty.client")))
    assertEquals(1, icon.layers.size)
    assertArrayEquals(png(80), icon.layers.single())
    source.validate()
    source.close()
  }

  @Test fun largeGitBatchUsesOneReaderAndNeverStampsOrScansGitPerLookup() = runBlocking {
    val backend = Backend(temporary.newFolder())
    repeat(2500) { index ->
      backend.files["themes/default/icons/dev.app$index.png"] = png(index % 200)
    }
    val repo = gitRepository(backend)
    repo.catalog(refresh = true)
    val source = repo.openFallbackSession("default")
    assertEquals(2501, source.nativeNames.size)
    val stamps = backend.stamps
    repeat(2500) { index ->
      assertArrayEquals(png(index % 200), requireNotNull(source.icon(listOf("dev.app$index"))).layers.single())
      assertNull(source.icon(listOf("dev.absent$index")))
    }
    assertEquals(stamps, backend.stamps)
    assertEquals(1, backend.opens)
    assertEquals(0, backend.closes)
    source.validate()
    assertEquals(stamps + 1, backend.stamps)
    assertEquals(1, backend.closes)
    source.close()
    assertEquals(1, backend.fetches)
    assertFalse(File(backend.root, "generated").exists())
    assertFalse(File(backend.root, "previews").exists())
  }

  @Test fun sourceDeletionRejectsHitsAndMissesAndCannotReviveOldSessionAfterRestoration() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    source.icon(listOf("dev.thirdparty.client"))
    val archive = f.archive
    val held = File(f.root, "held.zip")
    assertTrue(archive.renameTo(held))
    rejected { source.icon(listOf("dev.thirdparty.client")) }
    rejected { source.icon(listOf("dev.absent")) }
    assertTrue(held.renameTo(archive))
    rejected { source.icon(listOf("dev.thirdparty.client")) }
    rejected { source.validate() }
    f.repo.openFallbackSession("default").also { it.validate(); it.close() }
    source.close()
    assertEquals(1, f.downloads)
  }

  @Test fun catalogDeletionRejectsStaleIndexAndBlobCache() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    source.icon(listOf("dev.thirdparty.client"))
    assertTrue(f.catalog.delete())
    rejected { source.icon(listOf("dev.thirdparty.client")) }
    rejected { source.validate() }
    rejected { f.repo.openFallbackSession("default") }
    source.close()
    assertEquals(1, f.downloads)
  }

  @Test fun sameSizeArchiveMutationRejectsCachedLayers() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    source.icon(listOf("dev.thirdparty.client"))
    val archive = f.archive
    val modified = Files.getLastModifiedTime(archive.toPath())
    archive.writeBytes(archive.readBytes().apply { this[0] = (this[0].toInt() xor 1).toByte() })
    Files.setLastModifiedTime(archive.toPath(), FileTime.fromMillis(modified.toMillis() + 2000))
    rejected { source.icon(listOf("dev.thirdparty.client")) }
    rejected { source.validate() }
    source.close()
    assertEquals(1, f.downloads)
  }

  @Test fun sourceRevisionChangeInvalidatesSessionBeforeNextLookup() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    val modified = Files.getLastModifiedTime(f.catalog.toPath())
    f.catalog.writeText(JSONObject(f.catalog.readText()).put("revision", "2".repeat(40)).toString())
    Files.setLastModifiedTime(f.catalog.toPath(), FileTime.fromMillis(modified.toMillis() + 2000))
    rejected { source.icon(listOf("dev.thirdparty.client")) }
    rejected { source.validate() }
    f.repo.openFallbackSession("default").also { it.validate(); it.close() }
    source.close()
  }

  @Test fun endValidationRechecksSourceHashEvenWhenCatalogStatIsUnchanged() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    val modified = Files.getLastModifiedTime(f.catalog.toPath())
    val original = f.catalog.readText()
    val changed = original.replace(source.sourceHash, "0".repeat(64))
    assertEquals(original.length, changed.length)
    f.catalog.writeText(changed)
    Files.setLastModifiedTime(f.catalog.toPath(), modified)
    assertNotNull(source.icon(listOf("dev.thirdparty.client")))
    rejected { source.validate() }
    rejected { source.icon(listOf("dev.thirdparty.client")) }
    source.close()
  }

  @Test fun gitTipAndPackDeletionRejectEvenCachedIconsWithoutOpeningGitAgain() = runBlocking {
    for (removeTip in listOf(false, true)) {
      val backend = Backend(temporary.newFolder())
      val repo = gitRepository(backend)
      repo.catalog(refresh = true)
      val source = repo.openFallbackSession("default")
      source.icon(listOf("dev.thirdparty.client"))
      val stamps = backend.stamps
      val victim = if (removeTip) File(backend.directory, "tip")
        else backend.directory.listFiles()!!.first { it.extension == "pack" }
      assertTrue(victim.delete())
      rejected { source.icon(listOf("dev.thirdparty.client")) }
      rejected { source.validate() }
      assertEquals(stamps, backend.stamps)
      assertEquals(1, backend.opens)
      assertEquals(1, backend.closes)
      source.close()
    }
  }

  @Test fun gitInPlaceMutationIsRejectedAtCompletionWithoutPerLookupScanning() = runBlocking {
    val backend = Backend(temporary.newFolder())
    val repo = gitRepository(backend)
    repo.catalog(refresh = true)
    val source = repo.openFallbackSession("default")
    source.icon(listOf("dev.thirdparty.client"))
    val pack = backend.directory.listFiles()!!.first { it.extension == "pack" }
    val modified = Files.getLastModifiedTime(pack.toPath())
    pack.writeBytes(ByteArray(pack.length().toInt()))
    Files.setLastModifiedTime(pack.toPath(), FileTime.fromMillis(modified.toMillis() + 2000))
    rejected { source.validate() }
    rejected { source.icon(listOf("dev.thirdparty.client")) }
    assertEquals(1, backend.closes)
    source.close()
  }

  @Test fun corruptCachedBlobIsRepairedLazilyWithoutInvalidatingSourceOrRewritingHealthyBlob() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    val original = requireNotNull(source.icon(listOf("dev.thirdparty.client")))
    val first = File(f.root, "blobs/${PerfectIconFormat.sha(original.layers[0])}")
    val second = File(f.root, "blobs/${PerfectIconFormat.sha(original.layers[1])}")
    val secondTime = Files.getLastModifiedTime(second.toPath())
    first.writeBytes(ByteArray(original.layers[0].size))
    val repaired = requireNotNull(source.icon(listOf("dev.thirdparty.client")))
    assertArrayEquals(original.layers[0], repaired.layers[0])
    assertArrayEquals(original.layers[0], first.readBytes())
    assertEquals(secondTime, Files.getLastModifiedTime(second.toPath()))
    source.validate()
    source.close()
    assertEquals(1, f.downloads)
  }

  @Test fun cancellationPropagatesAndExplicitCloseReleasesReaderWithoutValidation() = runBlocking {
    val backend = Backend(temporary.newFolder())
    val repo = gitRepository(backend)
    repo.catalog(refresh = true)
    val source = repo.openFallbackSession("default")
    source.icon(listOf("dev.thirdparty.client"))
    repeat(3) { operation ->
      val cancelled = Job().apply { cancel() }
      try {
        withContext(cancelled) {
          when (operation) {
            0 -> repo.openFallbackSession("default")
            1 -> source.icon(listOf("dev.thirdparty.client"))
            else -> source.validate()
          }
        }
        fail("Expected cancellation")
      } catch (_: CancellationException) { }
    }
    source.close()
    source.close()
    assertEquals(1, backend.opens)
    assertEquals(1, backend.closes)
    rejected { source.icon(listOf("dev.thirdparty.client")) }
  }

  companion object {
    private fun png(tint: Int) = PerfectIconRepositoryTest.png(tint)
    private fun gitRepository(backend: Backend) = PerfectIconRepository(backend.root,
      PerfectIconTransport { _, _, _, _, _ -> error("No archive network") }, Settings(), git = backend).also {
        it.setOptions(PerfectIconOptions(style = "default", installedOnly = false, dynamic = false))
      }
    private suspend fun rejected(block: suspend () -> Unit) {
      try { block(); fail("Expected local source rejection") }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { }
    }
  }
}
