package os.kei.hyper.home.iconpack.perfect

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in transport acceptance in an isolated cache directory, never the user's source store. */
@RunWith(AndroidJUnit4::class)
class PerfectGitDeviceTest {
  @Test fun anonymousGitHubCheckReadsMetadataWithoutInstallingSourceResources() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("perfectGitNetwork") == "true")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "qa-perfect-git-metadata")
    root.deleteRecursively()
    val started = System.currentTimeMillis()
    try {
      val git = PerfectIconGit(root)
      val remote = withTimeout(120_000) { git.check {} }
      assertTrue(remote.revision.matches(Regex("[0-9a-f]{40}")))
      assertTrue(remote.committedAt > 0)
      assertTrue(remote.availableStyles.isNotEmpty())
      assertTrue(remote.subject.isNotBlank())
      val firstBytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
      val again = withTimeout(120_000) { git.check {} }
      assertEquals(remote.revision, again.revision)
      assertEquals(remote.committedAt, again.committedAt)
      assertTrue(root.walkTopDown().none { it.extension == "png" })
      File(context.cacheDir, "perfect-git-metadata-acceptance.json").writeText(JSONObject()
        .put("revision", remote.revision).put("committedAt", remote.committedAt)
        .put("subject", remote.subject).put("styles", JSONArray(remote.availableStyles))
        .put("elapsedMillis", System.currentTimeMillis() - started)
        .put("persistedMetadataBytes", firstBytes)
        .put("usedRestApi", false).put("requiredCredentials", false).toString(2))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun selectiveLocalTransportUpdatesAndStyleRemovalKeepSharedObjectsOnAndroid() = runBlocking {
    val path = InstrumentationRegistry.getArguments().getString("perfectGitFixture")
    assumeTrue(!path.isNullOrEmpty())
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "qa-perfect-git-fixture")
    root.deleteRecursively()
    val remote = File(root, "remote.git")
    try {
      File(requireNotNull(path)).copyRecursively(remote, overwrite = true)
      val revisions = JSONObject(File(remote, "qa-revisions.json").readText())
      val backend = PerfectIconGit(File(root, "source"), fixtureRemote = remote)
      val first = withTimeout(60_000) { backend.fetch(null, false, setOf("default")) {} }
      assertEquals(revisions.getString("first"), first.remote.revision)
      assertEquals(listOf("default"), first.downloadedStyles)
      assertTrue(first.entries.none { it.path.startsWith("themes/HyperOS4/") || it.path.startsWith("unrelated/") })
      backend.published(first.store, first.remote.revision)
      val publishedBytes = File(root, "source/${first.store}").walkTopDown().filter { it.isFile }.sumOf { it.length() }
      val again = withTimeout(60_000) { backend.fetch(first.store, false, setOf("default")) {} }
      assertEquals(publishedBytes, again.storedBytes)
      val both = withTimeout(60_000) { backend.fetch(first.store, false, setOf("default", "HyperOS4")) {} }
      assertTrue(both.entries.any { it.path.startsWith("themes/HyperOS4/icons/") })
      assertTrue(both.entries.any { it.path.startsWith("themes/common/") })
      backend.open(both.store).use { reader ->
        for (entry in both.entries) assertEquals(entry.sha, PerfectIconFormat.sha(reader.read(entry) {}))
      }
      val keep = PerfectIconSelection.paths(both.entries.map { it.path }, setOf("default"))
      val remaining = both.entries.filter { it.path in keep }
      val compacted = withTimeout(60_000) { backend.retain(both.store, remaining) {} }
      assertTrue(compacted.storedBytes < both.storedBytes)
      backend.published(compacted.store, both.remote.revision)
      assertFalse(backend.exists(both.store))
      backend.open(compacted.store).use { assertTrue(it.healthy(remaining) {}) }
      File(remote, "refs/heads/main").writeText(revisions.getString("second") + "\n")
      val checked = withTimeout(60_000) { backend.check {} }
      assertEquals(revisions.getString("second"), checked.revision)
      val updated = withTimeout(60_000) { backend.fetch(compacted.store, false, setOf("default")) {} }
      assertTrue(updated.entries.any { it.path.startsWith("themes/default/icons/com.example.renamed/") })
      assertTrue(updated.entries.none { it.path.startsWith("themes/default/icons/com.example.alpha/") })
      backend.open(updated.store).use { reader ->
        for (entry in updated.entries) assertEquals(entry.sha, PerfectIconFormat.sha(reader.read(entry) {}))
      }
      File(context.cacheDir, "perfect-git-fixture-acceptance.json").writeText(JSONObject()
        .put("firstRevision", first.remote.revision).put("updatedRevision", updated.remote.revision)
        .put("initialBytes", publishedBytes).put("unchangedBytes", again.storedBytes)
        .put("twoStylesBytes", both.storedBytes).put("retainedBytes", compacted.storedBytes)
        .put("skippedUnselectedObjects", true).put("sharedResourcesSurvived", true)
        .put("renamedPathsReplaced", true).toString(2))
    } finally {
      root.deleteRecursively()
    }
  }
}
