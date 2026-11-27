package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Optional real-source regression. All I/O is local: a missing fixture skips, never fetches. */
class PerfectIconSourceFixtureTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun pinnedHyperOS4GallerySettingsCalendarAndViaHaveExactOfflineCoverage() = runBlocking {
    val source = sequenceOf(File(".artifacts/research/perfect-icons-20261108/source"),
      File("../.artifacts/research/perfect-icons-20261108/source")).firstOrNull {
      File(it, "representative-identities.json").isFile
    }
    assumeTrue("Optional complete source research fixtures are unavailable", source != null)
    val directory = requireNotNull(source)
    val sourceFiles = linkedMapOf<String, ByteArray>()
    val identities = JSONObject(File(directory, "representative-identities.json").readText())
    val revision = identities.getString("commit")
    val files = identities.getJSONArray("files")
    for (index in 0 until files.length()) {
      val identity = files.getJSONObject(index)
      val path = identity.getString("source")
      val bytes = File(directory, "representative/$path").readBytes()
      assertEquals(identity.getString("sha"), PerfectIconFormat.sha(bytes))
      assertEquals(identity.getLong("size"), bytes.size.toLong())
      sourceFiles[path] = bytes
    }
    sourceFiles["icons/fixture.third.party/0.png"] = testPng(0)
    sourceFiles["icons/fixture.third.party/1.png"] = testPng(1)
    val root = temporary.newFolder()
    val backend = FakePerfectGitBackend(root, sourceFiles, revision = revision)
    val settings = InMemoryPerfectIconSettings()
    val repo = PerfectIconRepository(root, settings, git = backend)
    val catalog = repo.catalog(true, styles = setOf("default", "HyperOS4"))!!
    assertEquals(revision, catalog.revision)
    assertTrue(catalog.styles.containsAll(listOf("default", "HyperOS4")))
    assertEquals(4, catalog.iconCount)
    assertEquals(1, backend.fetches)
    val options = PerfectIconOptions(style = "HyperOS4")
    val installed = setOf("com.miui.gallery", "com.android.settings", "com.android.calendar", "mark.via", "mark.via.Shell")
    val first = repo.generate(options, installed) {}
    assertEquals(3, first.mapped)
    assertEquals(2, first.staticCount) // Calendar keeps a static fallback but is counted once as dynamic.
    assertEquals(1, first.dynamicCount)
    assertTrue(first.downloaded > 0)
    val before = backend.fetches
    val again = repo.generate(options, installed) {}
    assertEquals(0, again.downloaded)
    assertEquals(first.downloaded, again.reused)
    assertEquals("Pinned repeated generation must issue zero Git fetches", before, backend.fetches)
    assertEquals(first.file, again.file)
    ZipFile(again.file).use { zip ->
      assertNotNull(zip.getEntry("res/drawable-xxhdpi/com.android.settings/0.png"))
      assertNotNull(zip.getEntry("res/drawable-xxhdpi/com.miui.gallery/1.png"))
      assertNotNull(zip.getEntry("layer_animating_icons/com.android.calendar/2/fancy/manifest.xml"))
      assertNotNull(zip.getEntry("layer_animating_icons/com.android.calendar/config.xml"))
      assertFalse(zip.entries().asSequence().any { it.name.contains("mark.via") })
    }
  }
}
