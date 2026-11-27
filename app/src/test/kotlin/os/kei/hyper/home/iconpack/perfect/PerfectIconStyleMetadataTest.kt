package os.kei.hyper.home.iconpack.perfect

import java.io.File
import kotlinx.coroutines.runBlocking
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

  private fun repository(root: File) = PerfectIconRepository(root, settings,
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
}
