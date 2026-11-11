package os.kei.hyper.home.iconpack.perfect

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PerfectIconGitTest {
  @get:Rule val temporary = TemporaryFolder()

  private val baseAlpha = "icons/com.example.alpha/0.png"
  private val baseBeta = "icons/com.example.beta/0.png"
  private val baseBetaNight = "icons/com.example.beta/1.png"
  private val themedAlpha = "themes/default/icons/com.example.alpha/0.png"
  private val themedAlphaNight = "themes/default/icons/com.example.alpha/1.png"
  private val secondAlpha = "themes/second/icons/com.example.alpha/0.png"
  private val secondAlphaNight = "themes/second/icons/com.example.alpha/1.png"
  private val secondBeta = "themes/second/icons/com.example.beta/0.png"
  private val secondBetaNight = "themes/second/icons/com.example.beta/1.png"
  private val description = "themes/default/description.xml"

  private fun content() = linkedMapOf(
    baseAlpha to "base-alpha".toByteArray(),
    "icons/com.example.alpha/1.png" to "base-alpha-night".toByteArray(),
    baseBeta to "base-beta".toByteArray(),
    baseBetaNight to "base-beta-night".toByteArray(),
    themedAlpha to "themed-alpha".toByteArray(),
    themedAlphaNight to "themed-alpha-night".toByteArray(),
    secondAlpha to "second-alpha".toByteArray(),
    secondAlphaNight to "second-alpha-night".toByteArray(),
    secondBeta to "second-beta".toByteArray(),
    secondBetaNight to "second-beta-night".toByteArray(),
    description to "<theme/>".toByteArray(),
    "unrelated/large.bin" to ByteArray(1024 * 1024) { 73 },
  )

  private class Fixture(val repo: FileRepository) : AutoCloseable {
    var ids: Map<String, ObjectId> = emptyMap()
    var tip: ObjectId? = null
    fun commit(files: Map<String, ByteArray>, mode: Pair<String, FileMode>? = null): ObjectId {
      repo.newObjectInserter().use { insert ->
        ids = files.mapValues { insert.insert(Constants.OBJ_BLOB, it.value) }
        fun tree(prefix: String): ObjectId {
          val names = files.keys.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }.distinct().sorted()
          val formatter = TreeFormatter()
          names.forEach { name ->
            val path = prefix + name
            if (path in files) formatter.append(name, mode?.takeIf { it.first == path }?.second
              ?: FileMode.REGULAR_FILE, ids.getValue(path))
            else formatter.append(name, FileMode.TREE, tree("$path/"))
          }
          return insert.insert(formatter)
        }
        val commit = CommitBuilder().apply {
          setTreeId(tree(""))
          author = PersonIdent("Backend fixture", "fixture@example.invalid")
          committer = author
          message = "Fixture snapshot"
          tip?.let { setParentId(it) }
        }
        val id = insert.insert(commit)
        insert.flush()
        repo.updateRef("refs/heads/main").apply { setNewObjectId(id) }.forceUpdate()
        tip = id
        return id
      }
    }
    override fun close() { repo.close() }
  }

  private fun fixture(filter: Boolean = true): Fixture {
    val repo = FileRepository(File(temporary.newFolder(), "remote.git"))
    repo.create(true)
    repo.config.apply {
      setBoolean("uploadpack", null, "allowFilter", filter)
      setBoolean("uploadpack", null, "allowReachableSHA1InWant", true)
      save()
    }
    return Fixture(repo).also { it.commit(content()) }
  }

  private fun backend(fixture: Fixture, limits: PerfectIconZipLimits = PerfectIconZipLimits()) =
    PerfectIconGit(temporary.newFolder(), limits, fixtureRemote = fixture.repo.directory)

  @Test fun metadataCheckCachesCommitAndStylesWithoutAcquiringImageBlobs() = runBlocking {
    fixture().use { fixture ->
      val root = temporary.newFolder()
      val backend = PerfectIconGit(root, fixtureRemote = fixture.repo.directory)
      val checked = backend.check {}
      assertEquals(fixture.tip!!.name, checked.revision)
      assertEquals(listOf("default", "second"), checked.availableStyles)
      assertTrue(checked.committedAt > 0)
      assertEquals("Fixture snapshot", checked.subject)
      assertEquals(checked.revision, backend.check {}.revision)
      assertEquals(listOf(".remote"), root.listFiles()!!.map { it.name })
    }
  }

  @Test fun selectsDependencyGraphAndReadsEveryAcquiredBlobOffline() = runBlocking {
    fixture().use { fixture ->
      val backend = backend(fixture)
      val snapshot = backend.fetch(null, false, setOf("default")) {}
      assertEquals(listOf("default", "second"), snapshot.availableStyles)
      assertEquals(listOf("default"), snapshot.downloadedStyles)
      assertEquals(setOf(baseBeta, baseBetaNight, themedAlpha, themedAlphaNight, description),
        snapshot.entries.map { it.path }.toSet())
      assertTrue(backend.exists(snapshot.store))
      assertEquals(0L, snapshot.transferredBytes) // Local transport has no HTTP byte counter.
      backend.published(snapshot.store, snapshot.remote.revision)
      val expected = content()
      fixture.repo.directory.deleteRecursively()
      backend.open(snapshot.store).use { reader ->
        assertTrue(reader.healthy(snapshot.entries) {})
        snapshot.entries.forEach { entry ->
          assertEquals(0L, entry.crc)
          assertEquals(fixture.ids.getValue(entry.path).name, entry.sha)
          assertArrayEquals(expected.getValue(entry.path), reader.read(entry) {})
        }
        assertFalse(reader.healthy(listOf(snapshot.entries.first().copy(sha = "0".repeat(40)))) {})
      }
    }
  }

  @Test fun addingStyleReusesStoreAndUnionGraphWithoutFetchingOverriddenBase() = runBlocking {
    fixture().use { fixture ->
      val backend = backend(fixture)
      val first = backend.fetch(null, false, setOf("default")) {}
      val unchanged = backend.fetch(first.store, false, setOf("default")) {}
      assertEquals("Unchanged graph grew its pack store", first.storedBytes, unchanged.storedBytes)
      backend.published(first.store, first.remote.revision)
      val next = backend.fetch(first.store, false, setOf("default", "second")) {}
      assertEquals(first.store, next.store)
      assertEquals(setOf(baseBeta, baseBetaNight, themedAlpha, themedAlphaNight, description,
        secondAlpha, secondAlphaNight, secondBeta, secondBetaNight),
        next.entries.map { it.path }.toSet())
      backend.open(next.store).use { reader ->
        assertTrue(reader.healthy(first.entries) {})
        assertTrue(reader.healthy(next.entries) {})
        try {
          reader.read(PerfectIconSourceEntry(baseAlpha, fixture.ids.getValue(baseAlpha).name,
            content().getValue(baseAlpha).size.toLong(), 0)) {}
          fail("Overridden base object was downloaded")
        } catch (_: IOException) { }
      }
    }
  }

  @Test fun changedCommitKeepsPublishedObjectsUsableAndRepairUsesNewGeneration() = runBlocking {
    fixture().use { fixture ->
      val backend = backend(fixture)
      val first = backend.fetch(null, false, setOf("default")) {}
      backend.published(first.store, first.remote.revision)
      fixture.commit(content().apply { this[themedAlpha] = "updated-alpha".toByteArray() })
      val changed = backend.fetch(first.store, false, setOf("default")) {}
      assertNotEquals(first.remote.revision, changed.remote.revision)
      backend.open(first.store).use { reader ->
        assertTrue(reader.healthy(first.entries) {})
        assertTrue(reader.healthy(changed.entries) {})
      }
      val repair = backend.fetch(first.store, true, setOf("second")) {}
      assertNotEquals(first.store, repair.store)
      backend.open(first.store).use { assertTrue(it.healthy(first.entries) {}) }
      backend.published(repair.store, repair.remote.revision)
      assertFalse(backend.exists(first.store))
    }
  }

  @Test fun offlineRetentionReclaimsExclusiveObjectsOnlyAfterPublication() = runBlocking {
    fixture().use { fixture ->
      val backend = backend(fixture)
      val first = backend.fetch(null, false, setOf("default", "second")) {}
      backend.published(first.store, first.remote.revision)
      val remainingPaths = PerfectIconSelection.paths(first.entries.map { it.path }, setOf("default"))
      val remaining = first.entries.filter { it.path in remainingPaths }
      fixture.repo.directory.deleteRecursively()
      val retained = backend.retain(first.store, remaining) {}
      assertNotEquals(first.store, retained.store)
      assertTrue("Exclusive blobs were not reclaimed", retained.storedBytes < first.storedBytes)
      backend.open(first.store).use { assertTrue(it.healthy(first.entries) {}) }
      backend.open(retained.store).use { reader ->
        assertTrue(reader.healthy(remaining) {})
        remaining.forEach { entry -> assertArrayEquals(content().getValue(entry.path), reader.read(entry) {}) }
        val exclusive = first.entries.first { it.path.startsWith("themes/second/") }
        try { reader.read(exclusive) {}; fail("Removed exclusive blob survived retention") }
        catch (_: IOException) { }
      }
      backend.published(retained.store, first.remote.revision)
      assertFalse(backend.exists(first.store))
      assertTrue(backend.exists(retained.store))
    }
  }

  @Test fun cancelledRetentionDrainsAndRemovesNewGenerationWithoutDamagingSource() = runBlocking {
    fixture().use { fixture ->
      val root = temporary.newFolder()
      val backend = PerfectIconGit(root, fixtureRemote = fixture.repo.directory)
      val first = backend.fetch(null, false, setOf("default", "second")) {}
      backend.published(first.store, first.remote.revision)
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val job = async {
        backend.retain(first.store, first.entries) {
          entered.countDown()
          release.await(10, TimeUnit.SECONDS)
        }
      }
      try {
        assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
          entered.await(5, TimeUnit.SECONDS)
        })
        job.cancel()
        kotlinx.coroutines.delay(50)
        assertFalse(job.isCompleted)
      } finally { release.countDown() }
      job.cancelAndJoin()
      assertEquals(listOf(first.store), root.listFiles()!!.filter { it.isDirectory }.map { it.name })
      backend.open(first.store).use { assertTrue(it.healthy(first.entries) {}) }
    }
  }

  @Test fun failedSyncRetainsPublishedRefAndObjects() = runBlocking {
    fixture().use { fixture ->
      val root = temporary.newFolder()
      val backend = PerfectIconGit(root, fixtureRemote = fixture.repo.directory)
      val first = backend.fetch(null, false, setOf("default")) {}
      backend.published(first.store, first.remote.revision)
      val before = File(root, "${first.store}/active").readText()
      fixture.commit(mapOf("README.md" to "empty source".toByteArray()))
      try { backend.fetch(first.store, false, setOf("default")) {}; fail("Empty source accepted") }
      catch (_: IOException) { }
      assertEquals(before, File(root, "${first.store}/active").readText())
      backend.open(first.store).use { assertTrue(it.healthy(first.entries) {}) }
    }
  }

  @Test fun removedUpstreamStyleDoesNotBlockTheRemainingRequestedGraph() = runBlocking {
    fixture().use { fixture ->
      val backend = backend(fixture)
      val first = backend.fetch(null, false, setOf("default", "second")) {}
      backend.published(first.store, first.remote.revision)
      fixture.commit(content().filterKeys { !it.startsWith("themes/second/") })
      val next = backend.fetch(first.store, false, setOf("default", "second")) {}
      assertEquals(listOf("default"), next.availableStyles)
      assertEquals(listOf("default"), next.downloadedStyles)
      assertFalse(next.entries.any { it.path.startsWith("themes/second/") })
      backend.open(first.store).use { assertTrue(it.healthy(first.entries) {}) }
    }
  }

  @Test fun filterRefusalNeverFallsBackToFullRepository() = runBlocking {
    fixture(filter = false).use { fixture ->
      try { backend(fixture).check {}; fail("Server without blob filtering was accepted") }
      catch (_: IOException) { }
    }
  }

  @Test fun rejectsSymlinksSubmodulesUnsafeStoresUnknownStylesAndExpandedOverflow() = runBlocking {
    fixture().use { fixture ->
      val backend = backend(fixture)
      assertFalse(backend.exists("../outside"))
      try { backend.fetch(null, false, setOf("missing")) {}; fail("Unknown style accepted") }
      catch (_: IOException) { }
      try { backend.fetch(null, false, setOf("../outside")) {}; fail("Unsafe style accepted") }
      catch (_: IOException) { }
      fixture.commit(content(), themedAlpha to FileMode.SYMLINK)
      try { backend.check {}; fail("Symlink accepted") } catch (_: IOException) { }
      fixture.commit(content(), themedAlpha to FileMode.GITLINK)
      try { backend.check {}; fail("Submodule accepted") } catch (_: IOException) { }
      fixture.commit(content())
      val bounded = backend(fixture, PerfectIconZipLimits(expanded = 8))
      try { bounded.fetch(null, false, setOf("default")) {}; fail("Expanded limit bypassed") }
      catch (_: IOException) { }
    }
  }

  @Test fun cancellationDrainsWorkerBeforeReturningAndCleansTransientRepo() = runBlocking {
    fixture().use { fixture ->
      val root = temporary.newFolder()
      val backend = PerfectIconGit(root, fixtureRemote = fixture.repo.directory)
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val job = async {
        backend.check { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
      }
      try {
        assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
          entered.await(5, TimeUnit.SECONDS)
        })
        job.cancel()
        kotlinx.coroutines.delay(50)
        assertFalse("Cancellation returned while its pack worker was active", job.isCompleted)
        val started = System.nanoTime()
        release.countDown()
        job.cancelAndJoin()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
      } finally { release.countDown() }
      // Another operation takes the same mutex, so it waits for cancellation cleanup.
      backend.check {}
      assertFalse(root.listFiles()!!.any { it.name.startsWith(".metadata-") })
    }
  }
}
