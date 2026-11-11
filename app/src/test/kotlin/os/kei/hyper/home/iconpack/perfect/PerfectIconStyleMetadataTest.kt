package os.kei.hyper.home.iconpack.perfect

import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerfectIconStyleMetadataTest {
  @get:Rule val temporary = TemporaryFolder()
  private val store = "12345678-1234-1234-1234-123456789abc"

  private val settings = object : PerfectIconSettings {
    override fun read(key: String): String? = null
    override fun write(key: String, value: String) = Unit
  }

  private fun repository(root: File) = PerfectIconRepository(root,
    PerfectIconTransport { _, _, _, _, _ -> error("Catalog must remain offline") }, settings,
    git = PerfectIconGit(File(root, "git")))

  private fun legacyCatalog(root: File, hyperSize: Long = 75_000_000) {
    File(root, "git/$store/tip").apply { requireNotNull(parentFile).mkdirs(); writeText("a".repeat(40)) }
    File(root, "catalog.json").writeText(JSONObject()
      .put("version", 3).put("revision", "a".repeat(40)).put("sourceHash", "b".repeat(64))
      .put("archiveBytes", 80_000_000).put("updatedAt", 1230).put("downloadedAt", 1234)
      .put("count", 4).put("gitStore", store)
      .put("styles", JSONArray(listOf("HyperOS4", "default", "StarrySky")))
      .put("downloadedStyles", JSONArray(listOf("HyperOS4", "default")))
      .put("entries", JSONArray(listOf(
        JSONArray(listOf("themes/HyperOS4/icons/test.app/0.png", "1".repeat(40), hyperSize, 0)),
        JSONArray(listOf("themes/HyperOS4/icons/test.app/1.png", "2".repeat(40), 50, 0)),
        JSONArray(listOf("themes/default/icons/test.app/0.png", "3".repeat(40), 12, 0)),
        JSONArray(listOf("themes/default/icons/test.app/1.png", "2".repeat(40), 50, 0)),
        JSONArray(listOf("icons/base.app/0.png", "4".repeat(40), 600, 0)),
        JSONArray(listOf("icons/base.app/1.png", "5".repeat(40), 800, 0)),
        JSONArray(listOf("themes/common/layer_animating_icons/common.app/config.xml", "6".repeat(40), 20, 0)),
        JSONArray(listOf("themes/common/layer_animating_icons/common.app/quiet/icon.png", "7".repeat(40), 30, 0)),
      ))).toString())
  }

  @Test fun cachedStylesIncludeOwnAndRequiredSharedResourcesWithoutNetworkOrStoreScan() = runBlocking {
    val root = temporary.newFolder()
    legacyCatalog(root)
    val original = File(root, "catalog.json").readBytes()
    val repo = repository(root)
    repeat(3) {
      val catalog = requireNotNull(repo.catalog())
      assertEquals(75_001_500L, catalog.styleDetails.getValue("HyperOS4").bytes)
      assertEquals(75_000_050L, catalog.styleDetails.getValue("HyperOS4").ownBytes)
      assertEquals(1450L, catalog.styleDetails.getValue("HyperOS4").sharedBytes)
      assertEquals(1512L, catalog.styleDetails.getValue("default").bytes)
      assertEquals(62L, catalog.styleDetails.getValue("default").ownBytes)
      assertEquals(1450L, catalog.styleDetails.getValue("default").sharedBytes)
      assertNull(catalog.styleDetails.getValue("HyperOS4").iconFile)
      assertFalse(catalog.styleDetails.containsKey("StarrySky"))
      assertEquals(80_000_000L, catalog.archiveBytes)
    }
    assertArrayEquals(original, File(root, "catalog.json").readBytes())
    assertEquals(listOf("tip"), File(root, "git/$store").listFiles()!!.map { it.name })
    val restarted = repository(root)
    assertEquals(75_001_500L, restarted.catalog()!!.styleDetails.getValue("HyperOS4").bytes)
  }

  @Test fun duplicateObjectReferencesCountOncePerStyleAndSharedObjectsCountInBothStyles() = runBlocking {
    val root = temporary.newFolder()
    legacyCatalog(root)
    val file = File(root, "catalog.json")
    val json = JSONObject(file.readText())
    json.getJSONArray("entries").apply {
      put(JSONArray(listOf("icons/alias.app/0.png", "4".repeat(40), 600, 0)))
      put(JSONArray(listOf("icons/alias.app/1.png", "5".repeat(40), 800, 0)))
      put(JSONArray(listOf("themes/HyperOS4/icons/duplicate.app/0.png", "1".repeat(40), 75_000_000, 0)))
      put(JSONArray(listOf("themes/HyperOS4/icons/duplicate.app/1.png", "2".repeat(40), 50, 0)))
      put(JSONArray(listOf("themes/HyperOS4/icons/same.shared/0.png", "4".repeat(40), 600, 0)))
      put(JSONArray(listOf("themes/HyperOS4/icons/same.shared/1.png", "5".repeat(40), 800, 0)))
      put(JSONArray(listOf("themes/common/layer_animating_icons/common.app/quiet/alias.png", "7".repeat(40), 30, 0)))
    }
    file.writeText(json.toString())
    val details = repository(root).catalog()!!.styleDetails
    assertEquals(75_001_500L, details.getValue("HyperOS4").bytes)
    assertEquals(75_000_050L, details.getValue("HyperOS4").ownBytes)
    assertEquals(1450L, details.getValue("HyperOS4").sharedBytes)
    assertEquals(1512L, details.getValue("default").bytes)
    assertEquals(62L, details.getValue("default").ownBytes)
    assertEquals(1450L, details.getValue("default").sharedBytes)
  }

  @Test fun styleOverridesUseTheSameDependencyRulesAsGitSelection() = runBlocking {
    val root = temporary.newFolder()
    legacyCatalog(root)
    val file = File(root, "catalog.json")
    val json = JSONObject(file.readText())
    json.getJSONArray("entries").apply {
      put(JSONArray(listOf("themes/HyperOS4/icons/base.app/0.png", "8".repeat(40), 25, 0)))
      put(JSONArray(listOf("themes/HyperOS4/icons/base.app/1.png", "9".repeat(40), 35, 0)))
      put(JSONArray(listOf("themes/HyperOS4/description.xml", "a".repeat(40), 7, 0)))
    }
    file.writeText(json.toString())
    val details = repository(root).catalog()!!.styleDetails
    assertEquals(75_000_167L, details.getValue("HyperOS4").bytes)
    assertEquals(75_000_117L, details.getValue("HyperOS4").ownBytes)
    assertEquals(50L, details.getValue("HyperOS4").sharedBytes)
    assertEquals(1512L, details.getValue("default").bytes)
  }

  @Test fun bytesOnlyFixtureConstructorPreservesTotalAndBreakdown() {
    val details = PerfectIconStyleDetails(123)
    assertEquals(123L, details.bytes)
    assertEquals(123L, details.ownBytes)
    assertEquals(0L, details.sharedBytes)
  }

  private fun jpeg(width: Int = 512, height: Int = 300): ByteArray = byteArrayOf(
    0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0, 8, 8,
    (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 0,
    0xff.toByte(), 0xd9.toByte(),
  )

  private fun artworkCache(root: File, revision: String, style: String, bytes: ByteArray): File {
    val directory = File(root, ".artwork-$revision").apply { mkdirs() }
    val sha = PerfectIconFormat.sha(bytes)
    val file = File(directory, "$sha.jpg").apply { writeBytes(bytes) }
    DataOutputStream(File(directory, "index").outputStream()).use {
      it.writeUTF(revision)
      it.writeInt(1)
      it.writeUTF(style)
      it.writeUTF(sha)
      it.writeLong(bytes.size.toLong())
    }
    return file
  }

  @Test fun previewSheetsAndStaleArtworkCachesNeverBecomePackIcons() = runBlocking {
    val root = temporary.newFolder()
    legacyCatalog(root)
    val gitRoot = File(root, "git")
    val revision = "a".repeat(40)
    val cached = artworkCache(gitRoot, revision, "StarrySky", jpeg())
    val backend = PerfectIconGit(gitRoot)
    assertTrue(backend.styleIcons(revision).isEmpty())
    assertTrue(backend.styleIcons("../outside").isEmpty())
    assertTrue(repository(root).catalog()!!.styleDetails.values.all { it.iconFile == null })
    val outside = temporary.newFile().apply { writeBytes(jpeg()) }
    cached.delete()
    Files.createSymbolicLink(cached.toPath(), outside.toPath())
    assertTrue(backend.styleIcons(revision).isEmpty())
    assertTrue(Files.isSymbolicLink(cached.toPath()))
  }

  @Test fun metadataFetchDoesNotDownloadPreviewSheetsOrSubstituteApplicationIcons() = runBlocking {
    val source = temporary.newFolder()
    Git.init().setDirectory(source).setInitialBranch("main").call().use { git ->
      git.repository.config.apply {
        setBoolean("uploadpack", null, "allowFilter", true)
        setBoolean("uploadpack", null, "allowReachableSHA1InWant", true)
        setBoolean("commit", null, "gpgSign", false)
        save()
      }
      fun resource(path: String, bytes: ByteArray) {
        File(source, path).apply { requireNotNull(parentFile).mkdirs(); writeBytes(bytes) }
      }
      val icon = jpeg()
      resource("icons/test.app/0.png", byteArrayOf(1))
      resource("icons/test.app/1.png", byteArrayOf(4))
      resource("themes/default/icons/test.app/0.png", byteArrayOf(2))
      resource("themes/default/icons/test.app/1.png", byteArrayOf(5))
      resource("themes/StarrySky/icons/test.app/0.png", ByteArray(2 * 1024 * 1024) { 3 })
      resource("themes/StarrySky/icons/test.app/1.png", byteArrayOf(6))
      resource("emui/themes/StarrySky/preview/icon_small.jpg", icon)
      resource("emui/themes/StarrySky/preview/cover.jpg", jpeg(width = 800))
      resource("themes/StarrySky/icons/stand.in/0.png", jpeg())
      git.add().addFilepattern(".").call()
      val revision = git.commit().setMessage("Resource fixture").setAuthor("Fixture", "test@example.invalid")
        .setCommitter("Fixture", "test@example.invalid").call().name
      val root = temporary.newFolder()
      val backend = PerfectIconGit(root, fixtureRemote = git.repository.directory)
      val remote = backend.check {}
      assertEquals(revision, remote.revision)
      assertTrue(backend.styleIcons(revision).isEmpty())
      assertEquals(listOf(".remote"), root.listFiles()!!.map { it.name })
      val modified = File(root, ".remote").lastModified()
      assertEquals(remote.revision, backend.check {}.revision)
      assertEquals(modified, File(root, ".remote").lastModified())
      assertTrue(PerfectIconGit(root).styleIcons(revision).isEmpty())
    }
  }
}
