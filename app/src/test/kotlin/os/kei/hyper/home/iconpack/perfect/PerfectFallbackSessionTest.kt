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

  private class Fixture(
    val root: File,
    equivalentRenderer: ((ByteArray, List<ByteArray>, String?, () -> Unit) -> PerfectStaticPair)? = null,
    renderer: ((List<ByteArray>, String?, () -> Unit) -> PerfectStaticPair)? = null,
    availableStylesOverride: List<String>? = null,
  ) {
    val settings = InMemoryPerfectIconSettings()
    val backend = FakePerfectGitBackend(
      root,
      linkedMapOf(
        "icons/dev.thirdparty.client/0.png" to testPng(10),
        "icons/dev.thirdparty.client/1.png" to testPng(20),
        "icons/dev.thirdparty.client.MainActivity/0.png" to testPng(30),
        "icons/dev.thirdparty.client.MainActivity/1.png" to testPng(40),
        "themes/default/icons/com.android.settings.png" to testPng(50),
      ),
      availableStylesOverride = availableStylesOverride,
    )
    val files get() = backend.files
    val downloads get() = backend.fetches
    val repo = PerfectIconRepository(root, settings, git = backend,
      staticRenderer = renderer, staticEquivalentRenderer = equivalentRenderer).also {
      it.setOptions(PerfectIconOptions(style = "default", installedOnly = false, dynamic = false))
    }
    suspend fun acquire(styles: Set<String> = setOf("default")) {
      repo.catalog(refresh = true, styles = styles)
    }
    val storeDir get() = backend.storeDir()
    val catalog get() = File(root, "catalog.json")
  }

  @Test fun missingSourceAndUndownloadedStyleThrowWithoutNetwork() = runBlocking {
    val f = Fixture(temporary.newFolder(), availableStylesOverride = listOf("default", "missing"))
    rejected { f.repo.openFallbackSession("default") }
    assertEquals(0, f.downloads)
    f.acquire(setOf("default"))
    rejected { f.repo.openFallbackSession("missing") }
    rejected { f.repo.openFallbackSession("../default") }
    assertEquals(0, f.backend.opens)
    assertEquals(1, f.downloads)
  }

  @Test fun nightFallbackUsesSharedDerivedCacheWithoutChangingDayLayersOrDownloading() = runBlocking {
    var renders = 0
    val f = Fixture(temporary.newFolder(), renderer = { layers, mask, check ->
      check()
      assertNull(mask)
      assertArrayEquals(testPng(10), layers[0])
      assertArrayEquals(testPng(20), layers[1])
      renders++
      PerfectStaticPair(testPng(60), testPng(70))
    })
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    assertArrayEquals(testPng(10), requireNotNull(source.icon(listOf("dev.thirdparty.client"))).layers[0])
    repeat(2) {
      assertArrayEquals(testPng(70),
        requireNotNull(source.icon(listOf("dev.thirdparty.client"), darkMode = true)).layers.single())
    }
    source.close()
    f.repo.openFallbackSession("default").let { reopened ->
      assertArrayEquals(testPng(70),
        requireNotNull(reopened.icon(listOf("dev.thirdparty.client"), darkMode = true)).layers.single())
      assertArrayEquals(testPng(20),
        requireNotNull(reopened.icon(listOf("dev.thirdparty.client"))).layers[1])
      reopened.close()
    }
    assertEquals(1, renders)
    assertEquals(1, f.downloads)
    assertFalse(File(f.root, "generated").exists())
  }

  @Test fun cancellingNightFallbackDoesNotPublishPartialArtwork() = runBlocking {
    val f = Fixture(temporary.newFolder(), renderer = { _, _, _ -> throw CancellationException("night cancelled") })
    f.acquire()
    f.repo.openFallbackSession("default").let { source ->
      try {
        source.icon(listOf("dev.thirdparty.client"), darkMode = true)
        fail("Expected cancellation")
      } catch (_: CancellationException) { }
      assertArrayEquals(testPng(10), requireNotNull(source.icon(listOf("dev.thirdparty.client"))).layers[0])
      source.close()
    }
    assertTrue(File(f.root, "rendered").listFiles().isNullOrEmpty())
  }

  @Test fun flatDayArtworkKeepsItsEquivalentLayersForNightFallback() = runBlocking {
    var renders = 0
    val f = Fixture(temporary.newFolder(), equivalentRenderer = { flat, layers, mask, check ->
      check()
      assertNull(mask)
      assertArrayEquals(testPng(30), flat)
      assertArrayEquals(testPng(10), layers[0])
      assertArrayEquals(testPng(20), layers[1])
      renders++
      PerfectStaticPair(flat, testPng(70))
    })
    f.files["themes/default/icons/dev.thirdparty.client.png"] = testPng(30)
    f.files["themes/default/icons/dev.thirdparty.client/0.png"] = testPng(10)
    f.files["themes/default/icons/dev.thirdparty.client/1.png"] = testPng(20)
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    try {
      assertArrayEquals(testPng(30),
        requireNotNull(source.icon(listOf("dev.thirdparty.client"))).layers.single())
      assertArrayEquals(testPng(70),
        requireNotNull(source.icon(listOf("dev.thirdparty.client"), darkMode = true)).layers.single())
      assertEquals(1, renders)
    } finally { source.close() }
    assertEquals(1, f.downloads)
  }

  @Test fun indexesArbitraryStaticNamesAndExcludesLiveOnlyAndIncompleteIcons() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.files["icons/dev.incomplete/0.png"] = testPng(60)
    f.files["themes/default/layer_animating_icons/dev.live/config.xml"] = "<Config/>".toByteArray()
    f.files["themes/default/layer_animating_icons/dev.live/0/quiet/quietImage.png"] = testPng(70)
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    assertEquals(f.repo.fallbackSourceHash("default"), source.sourceHash)
    assertEquals(setOf("dev.thirdparty.client", "dev.thirdparty.client.MainActivity",
      "com.android.settings"), source.nativeNames)
    assertArrayEquals(testPng(30), requireNotNull(source.icon(listOf("../invalid", "dev.absent",
      "dev.thirdparty.client.MainActivity", "dev.thirdparty.client"))).layers[0])
    assertArrayEquals(testPng(10), requireNotNull(source.icon(listOf("dev.thirdparty.client"))).layers[0])
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
    f.files["themes/default/icons/dev.thirdparty.client.png"] = testPng(80)
    f.files["themes/default/layer_animating_icons/dev.thirdparty.client/config.xml"] =
      "<Config/>".toByteArray()
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    assertTrue("dev.thirdparty.client" in source.nativeNames)
    val icon = requireNotNull(source.icon(listOf("dev.thirdparty.client")))
    assertEquals(1, icon.layers.size)
    assertArrayEquals(testPng(80), icon.layers.single())
    source.validate()
    source.close()
  }

  @Test fun largeGitBatchUsesOneReaderAndNeverStampsOrScansGitPerLookup() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.files.clear()
    f.files["icons/dev.thirdparty.client/0.png"] = testPng(10)
    f.files["icons/dev.thirdparty.client/1.png"] = testPng(20)
    repeat(2500) { index ->
      f.files["themes/default/icons/dev.app$index.png"] = testPng(index % 200)
    }
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    assertEquals(2501, source.nativeNames.size)
    val stamps = f.backend.stamps
    repeat(2500) { index ->
      assertArrayEquals(testPng(index % 200), requireNotNull(source.icon(listOf("dev.app$index"))).layers.single())
      assertNull(source.icon(listOf("dev.absent$index")))
    }
    assertEquals(stamps, f.backend.stamps)
    assertEquals(1, f.backend.opens)
    assertEquals(0, f.backend.closes)
    source.validate()
    assertEquals(stamps + 1, f.backend.stamps)
    assertEquals(1, f.backend.closes)
    source.close()
    assertEquals(1, f.backend.fetches)
    assertFalse(File(f.root, "generated").exists())
    assertFalse(File(f.root, "previews").exists())
  }

  @Test fun sourceDeletionRejectsHitsAndMissesAndCannotReviveOldSessionAfterRestoration() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    source.icon(listOf("dev.thirdparty.client"))
    val tip = File(f.storeDir, "tip")
    val held = File(f.root, "held-tip")
    assertTrue(tip.renameTo(held))
    rejected { source.icon(listOf("dev.thirdparty.client")) }
    rejected { source.icon(listOf("dev.absent")) }
    assertTrue(held.renameTo(tip))
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
      val f = Fixture(temporary.newFolder())
      f.acquire()
      val source = f.repo.openFallbackSession("default")
      source.icon(listOf("dev.thirdparty.client"))
      val stamps = f.backend.stamps
      val victim = if (removeTip) File(f.storeDir, "tip")
        else f.storeDir.listFiles()!!.first { it.extension == "pack" }
      assertTrue(victim.delete())
      rejected { source.icon(listOf("dev.thirdparty.client")) }
      rejected { source.validate() }
      assertEquals(stamps, f.backend.stamps)
      assertEquals(1, f.backend.opens)
      assertEquals(1, f.backend.closes)
      source.close()
    }
  }

  @Test fun gitInPlaceMutationIsRejectedAtCompletionWithoutPerLookupScanning() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    source.icon(listOf("dev.thirdparty.client"))
    val pack = f.storeDir.listFiles()!!.first { it.extension == "pack" }
    val modified = Files.getLastModifiedTime(pack.toPath())
    pack.writeBytes(ByteArray(pack.length().toInt()))
    Files.setLastModifiedTime(pack.toPath(), FileTime.fromMillis(modified.toMillis() + 2000))
    rejected { source.validate() }
    rejected { source.icon(listOf("dev.thirdparty.client")) }
    assertEquals(1, f.backend.closes)
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
    val f = Fixture(temporary.newFolder())
    f.acquire()
    val source = f.repo.openFallbackSession("default")
    source.icon(listOf("dev.thirdparty.client"))
    repeat(3) { operation ->
      val cancelled = Job().apply { cancel() }
      try {
        withContext(cancelled) {
          when (operation) {
            0 -> f.repo.openFallbackSession("default")
            1 -> source.icon(listOf("dev.thirdparty.client"))
            else -> source.validate()
          }
        }
        fail("Expected cancellation")
      } catch (_: CancellationException) { }
    }
    source.close()
    source.close()
    assertEquals(1, f.backend.opens)
    assertEquals(1, f.backend.closes)
    rejected { source.icon(listOf("dev.thirdparty.client")) }
  }

  companion object {
    private suspend fun rejected(block: suspend () -> Unit) {
      try { block(); fail("Expected local source rejection") }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { }
    }
  }
}
