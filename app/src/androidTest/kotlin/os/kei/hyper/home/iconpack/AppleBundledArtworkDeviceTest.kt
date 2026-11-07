package os.kei.hyper.home.iconpack

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.iconpack.apple.*

@RunWith(AndroidJUnit4::class)
class AppleBundledArtworkDeviceTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val options = AppleIconOptions(resolution = 256)
  private val store = AppleAppMatch("system_appstore", "App Store", "Apple")

  private fun fixture(cancelNetwork: Boolean = false,
    block: suspend (AppleIconRepository, File, String) -> Unit) = runBlocking {
    val namespace = "apple_test_${UUID.randomUUID()}"
    val directory = File(context.cacheDir, namespace)
    var requests = 0
    val transport = object : AppleIconTransport {
      override suspend fun get(url: String): ByteArray {
        requests++
        if (cancelNetwork) throw kotlinx.coroutines.CancellationException("Cancelled metadata request")
        error("Bundled artwork and mapping transfer must not access the network")
      }
    }
    try {
      block(AppleIconRepository(context, AppleIconClient(transport), directory, namespace), directory, namespace)
      assertEquals(if (cancelNetwork) 1 else 0, requests)
    } finally {
      directory.deleteRecursively()
      AppLocalStorage.open(context, namespace).clearAll()
    }
  }

  private fun background(file: File): Int {
    val bitmap = BitmapFactory.decodeFile(file.path)
    return try { bitmap.getPixel(32, 128) } finally { bitmap.recycle() }
  }

  @Test fun coldCacheOnlyPreviewRendersTheCorrectNativeAppearancesOffline() = fixture { repo, _, _ ->
    val preview = requireNotNull(repo.cachedPreview(store, options))
    val light = background(preview.light)
    val dark = background(requireNotNull(preview.dark))
    assertTrue("App Store light background must be blue", Color.blue(light) > Color.red(light) + 80)
    assertTrue("App Store dark background must stay dark", Color.red(dark) + Color.green(dark) + Color.blue(dark) < 120)
    for (id in listOf("1584215428", "1146562112", "1232058109")) {
      val pair = requireNotNull(repo.cachedPreview(AppleAppMatch(id, "Apple", "Apple"), options))
      assertNotNull(pair.dark)
      assertFalse(pair.light.readBytes().contentEquals(requireNotNull(pair.dark).readBytes()))
    }
  }

  @Test fun obsoleteBundledPublicationDoesNotOverrideCorrectedPreviewSlots() = fixture { repo, root, _ ->
    val target = AppleAppTarget("com.example.store", "Store", true)
    repo.setMatch(target, options, store)
    repo.generate(listOf(target), options) {}
    val expected = requireNotNull(repo.cachedPreview(store, options)).light.readBytes()
    val publication = File(root, "current.json")
    val old = JSONObject(publication.readText())
    val artwork = old.getJSONObject("artwork")
    val key = store.artworkId(options)
    artwork.put(key, artwork.getString(key).split(':').reversed().joinToString(":"))
    old.put("bundled", "obsolete-appearance-recipe")
    publication.writeText(old.toString())
    val corrected = requireNotNull(repo.cachedPreview(store, options))
    assertArrayEquals(expected, corrected.light.readBytes())
  }

  @Test fun cameraHasIndependentOfflineVariantsAcrossIOSPlatformsAndResolutions() = fixture { repo, root, _ ->
    val camera = AppleAppMatch("1584216193", "Camera", "Apple", "com.apple.camera")
    // Simulate the standard, appearance-insensitive metadata retained by the older app.
    File(root, "metadata").mkdirs()
    val key = AppleIconPolicy.key("art:ios:us:${camera.id}")
    File(root, "metadata/$key.json").writeText(JSONObject()
      .put("template", "https://is1-ssl.mzstatic.com/image/thumb/Placeholder.mill/{w}x{h}{c}.{f}")
      .put("time", System.currentTimeMillis()).put("fallback", true).put("limited", true).toString())
    for (platform in listOf(ApplePlatform.IOS, ApplePlatform.IPADOS)) {
      for (size in listOf(256, 512, 1024)) {
        val pair = requireNotNull(repo.cachedPreview(camera, options.copy(platform = platform, resolution = size)))
        val dark = requireNotNull(pair.dark)
        assertFalse(pair.light.readBytes().contentEquals(dark.readBytes()))
        val lightImage = BitmapFactory.decodeFile(pair.light.path)
        val darkImage = BitmapFactory.decodeFile(dark.path)
        try {
          assertEquals(size, lightImage.width)
          assertEquals(size, darkImage.width)
          assertTrue(Color.red(lightImage.getPixel(size / 2, size / 32)) >
            Color.red(darkImage.getPixel(size / 2, size / 32)) + 50)
        } finally {
          lightImage.recycle()
          darkImage.recycle()
        }
      }
    }
  }

  @Test fun cancelledMetadataRefreshDoesNotPublishAnOfflineReplacement() = fixture(true) { repo, root, _ ->
    val target = AppleAppTarget("com.example.photos", "Photos", true)
    repo.setMatch(target, options, AppleAppMatch("1584215428", "Photos", "Apple"))
    val result = runCatching { repo.generate(listOf(target), options, refresh = true) {} }
    assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    assertFalse(File(root, "current.json").exists())
  }

  @Test fun transferPersistsAllPlatformsExclusionsAndStorefrontsWithoutImages() = fixture { repo, _, namespace ->
    val mapped = AppleAppTarget("com.example.uninstalled", "Missing", false)
    val excluded = AppleAppTarget("com.example.excluded", "Excluded", false)
    val unrelated = AppleAppTarget("com.example.preserve", "Preserve", false)
    val choice = AppleAppMatch("1108187390", "Apple Music", "Apple", "com.apple.Music", "jp")
    ApplePlatform.entries.forEach { platform ->
      val opts = options.copy(platform = platform)
      repo.setMatch(mapped, opts, choice)
      repo.setMatch(excluded, opts, null)
    }
    val document = repo.exportMappings()
    assertFalse(document.toString(Charsets.UTF_8).contains("downloads"))
    AppLocalStorage.open(context, namespace).clearAll()
    repo.setMatch(unrelated, options, store)
    assertEquals(6, repo.importMappings(document))
    ApplePlatform.entries.forEach { platform ->
      val opts = options.copy(platform = platform)
      assertEquals(choice, repo.match(mapped, opts))
      assertTrue(repo.custom(excluded, opts))
      assertNull(repo.match(excluded, opts))
    }
    assertEquals(store, repo.match(unrelated, options))
    val before = repo.exportMappings()
    assertTrue(runCatching { repo.importMappings(document.toString(Charsets.UTF_8)
      .replace("\"IOS\"", "\"INVALID\"").toByteArray()) }.isFailure)
    assertArrayEquals(before, repo.exportMappings())
  }
}
