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
    // Small local ZIP assembled from exact source bytes; no API or raw-file transport exists.
    sourceFiles["icons/fixture.third.party/0.png"] = PerfectIconRepositoryTest.png(0)
    sourceFiles["icons/fixture.third.party/1.png"] = PerfectIconRepositoryTest.png(1)
    val archive = PerfectIconRepositoryTest.zip(sourceFiles, revision)
    var requests = 0
    val transport = PerfectIconTransport { url, destination, _, _, _ ->
      assertEquals(PerfectIconUrls.SOURCE, url)
      requests++
      destination.outputStream().use { it.write(archive) }
      PerfectIconHttpResponse(200)
    }
    val values = HashMap<String, String>()
    val settings = object : PerfectIconSettings {
      override fun read(key: String) = values[key]
      override fun write(key: String, value: String) { values[key] = value }
    }
    val repo = PerfectIconRepository(temporary.newFolder(), transport, settings)
    val catalog = repo.catalog(true)!!
    assertEquals(revision, catalog.revision)
    assertTrue(catalog.styles.containsAll(listOf("default", "HyperOS4")))
    assertEquals(1, catalog.iconCount)
    assertEquals(1, requests)
    val options = PerfectIconOptions(style = "HyperOS4")
    val installed = setOf("com.miui.gallery", "com.android.settings", "com.android.calendar", "mark.via", "mark.via.Shell")
    val first = repo.generate(options, installed) {}
    assertEquals(3, first.mapped)
    assertEquals(3, first.staticCount)
    assertEquals(1, first.dynamicCount)
    assertTrue(first.downloaded > 0)
    val before = requests
    val again = repo.generate(options, installed) {}
    assertEquals(0, again.downloaded)
    assertEquals(first.downloaded, again.reused)
    assertEquals("Pinned repeated generation must issue zero HTTP requests", before, requests)
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
