package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.iconpack.apple.*

class AppleIconRepositoryDeviceTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val options = AppleIconOptions(resolution = 256)
  private val target = AppleAppTarget("test.android.player", "Player", false)
  private val first = AppleAppMatch("123456789", "Test Music", "Publisher", "test.apple.player")

  @Test fun bundledSourceProvenanceMatchesAllDecodableImages() {
    val manifest = context.assets.open("apple-system-icons/provenance.json").use {
      JSONObject(it.bufferedReader().readText())
    }
    val records = manifest.getJSONArray("images")
    assertEquals(18, records.length())
    for (i in 0 until records.length()) {
      val record = records.getJSONObject(i)
      val bytes = context.assets.open("apple-system-icons/${record.getString("file")}").use { it.readBytes() }
      assertEquals(record.getString("sha256"), AppleIconPolicy.digest(bytes))
      val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      assertNotNull(bitmap)
      try {
        assertEquals(record.getInt("resolution"), bitmap.width)
        assertEquals(record.getInt("resolution"), bitmap.height)
      } finally { bitmap.recycle() }
    }
  }

  private class Server : AppleIconTransport {
    val requests = mutableListOf<String>()
    var offline = false
    var revision = 1
    var identicalDark = false
    var catalogLimited = false
    var tagDarkPng = false
    var suspendId: String? = null
    val suspended = CompletableDeferred<Unit>()
    override suspend fun get(url: String): ByteArray {
      requests += url
      if (offline) throw IOException("offline")
      if (url.contains("/apps/")) {
        if (catalogLimited) throw AppleRequestException(429)
        val id = java.net.URI(url).path.substringAfterLast('/')
        if (id == suspendId) { suspended.complete(Unit); awaitCancellation() }
        val art = JSONObject().put("url", "https://is1-ssl.mzstatic.com/image/thumb/r$revision/$id/{w}x{h}{c}.{f}")
        val attributes = JSONObject().put("name", "Test Music").put("platformAttributes",
          JSONObject().put("ios", JSONObject().put("iconArtwork", art)))
        return JSONObject().put("data", JSONArray().put(JSONObject().put("id", id)
          .put("attributes", attributes))).toString().toByteArray()
      }
      if ("itunes.apple.com/lookup" in url) {
        return JSONObject().put("results", JSONArray().put(JSONObject()
          .put("trackId", "123456789").put("trackName", "Music").put("artistName", "Publisher")
          .put("kind", "software").put("supportedDevices", JSONArray().put("iPhone").put("iPad"))
          .put("artworkUrl512", "https://is1-ssl.mzstatic.com/image/thumb/r1/123456789/512x512bb.jpg")))
          .toString().toByteArray()
      }
      val size = Regex("/(256|512|1024)x").find(url)!!.groupValues[1].toInt()
      val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(if ("appearance=dark" in url && !identicalDark) Color.BLACK
        else if ("/r2/" in url) Color.GREEN else Color.WHITE)
      return try {
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        if (!tagDarkPng || "appearance=dark" !in url) png else {
          val type = "tEXt".toByteArray()
          val text = "comment\u0000different metadata, identical pixels".toByteArray()
          val crc = java.util.zip.CRC32().apply { update(type); update(text) }
          val chunk = ByteArrayOutputStream().also { bytes ->
            java.io.DataOutputStream(bytes).apply { writeInt(text.size); write(type); write(text); writeInt(crc.value.toInt()) }
          }.toByteArray()
          png.copyOfRange(0, 33) + chunk + png.copyOfRange(33, png.size)
        }
      } finally { bitmap.recycle() }
    }
  }

  private fun fixture(block: suspend (AppleIconRepository, Server, File, String) -> Unit) = runBlocking {
    val namespace = "apple_test_${UUID.randomUUID()}"
    val directory = File(context.cacheDir, namespace)
    val server = Server()
    val repository = AppleIconRepository(context, AppleIconClient(server, metadataIntervalMillis = 0), directory, namespace)
    try { block(repository, server, directory, namespace)
    } finally { directory.deleteRecursively(); AppLocalStorage.open(context, namespace).clearAll() }
  }

  @Test fun incrementalGenerationReusesSharedArtworkAndKeepsArchiveUnchanged() = fixture { repo, server, _, _ ->
    val second = target.copy(packageName = "test.android.second")
    repo.setMatch(target, options, first); repo.setMatch(second, options, first)
    val initial = repo.generate(listOf(target, second), options) {}
    assertEquals(2, initial.mapped); assertEquals(2, initial.dark)
    assertEquals(3, server.requests.size)
    val file = (initial.pack.source as IconPackSource.Generated).file
    val modification = file.lastModified()
    ZipFile(file).use { zip ->
      assertEquals(2, zip.entries().asSequence().count { it.name.endsWith(".png") })
      assertTrue(zip.entries().asSequence().filter { it.name.endsWith(".png") }.all { it.method == ZipEntry.STORED })
      assertFalse(zip.entries().asSequence().any { "fancy_icons" in it.name || "manifest.xml" in it.name })
    }
    server.requests.clear(); server.offline = true
    val repeated = repo.generate(listOf(target, second), options) {}
    assertTrue(repeated.unchanged); assertEquals(1, repeated.reused)
    assertTrue(server.requests.isEmpty()); assertEquals(modification, file.lastModified())
  }

  @Test fun clearingDownloadsKeepsMappingsAndRestoresArtworkFromPublishedPackOffline() = fixture { repo, server, _, _ ->
    repo.setMatch(target, options, first)
    repo.generate(listOf(target), options) {}
    repo.clearDownloads()
    assertEquals(0L, repo.cacheBytes())
    assertEquals(first, repo.match(target, options))
    server.requests.clear(); server.offline = true
    val preview = repo.preview(first, options)
    assertTrue(preview.light.isFile)
    assertNotNull(preview.dark)
    assertTrue(server.requests.isEmpty())
    val rebuilt = repo.generate(listOf(target), options) {}
    assertTrue(rebuilt.unchanged); assertTrue(server.requests.isEmpty())
    assertTrue(repo.cacheBytes() > 0)
  }

  @Test fun metadataRefreshFetchesOnlyChangedImagesAndPreservesKnownArtworkOnFailure() = fixture { repo, server, _, _ ->
    repo.setMatch(target, options, first)
    val old = repo.generate(listOf(target), options) {}
    server.requests.clear()
    assertTrue(repo.generate(listOf(target), options, refresh = true) {}.unchanged)
    assertEquals(1, server.requests.size)
    server.revision = 2; server.requests.clear()
    val changed = repo.generate(listOf(target), options, refresh = true) {}
    assertFalse(changed.unchanged); assertEquals(3, server.requests.size)
    assertNotEquals(old.pack.source, changed.pack.source)
    server.offline = true; server.requests.clear()
    val offline = repo.generate(listOf(target), options, refresh = true) {}
    assertTrue(offline.unchanged); assertEquals(1, offline.stale)
    assertEquals(changed.pack.source, offline.pack.source)
  }

  @Test fun identicalLightAndDarkPixelsDoNotAdvertiseAnIndependentDarkIcon() = fixture { repo, server, _, _ ->
    server.identicalDark = true
    repo.setMatch(target, options, first)
    val generated = repo.generate(listOf(target), options) {}
    assertEquals(0, generated.dark)
    ZipFile((generated.pack.source as IconPackSource.Generated).file).use {
      assertFalse(it.entries().asSequence().any { entry -> "drawable-night" in entry.name })
    }
  }

  @Test fun mappingChangesPersistPerPlatformAndCanOverrideOrDisableSystemRoles() = fixture { repo, _, dir, name ->
    val system = AppleAppTarget("test.android.market", "应用商店", true)
    assertEquals("system_appstore", repo.match(system, options)?.id)
    repo.setMatch(system, options, first)
    val reopened = AppleIconRepository(context, AppleIconClient(Server(), 0), dir, name)
    assertEquals(first, reopened.match(system, options))
    assertEquals("system_appstore", reopened.match(system, options.copy(platform = ApplePlatform.MACOS))?.id)
    reopened.setMatch(system, options, null); assertNull(repo.match(system, options))
    reopened.resetMatch(system, options); assertEquals("system_appstore", repo.match(system, options)?.id)
  }

  @Test fun builtInSystemIconsWorkOfflineInAllPlatformsWithoutStoreRequests() = fixture { repo, server, _, _ ->
    server.offline = true
    for (platform in ApplePlatform.entries) {
      val settings = AppleAppTarget("com.android.settings", "设置", true)
      val store = AppleAppTarget("test.android.market", "软件商店", true)
      val result = repo.generate(listOf(settings, store), options.copy(platform = platform)) {}
      assertEquals(2, result.mapped); assertTrue(result.dark in 0..2)
      ZipFile((result.pack.source as IconPackSource.Generated).file).use { zip ->
        zip.entries().asSequence().filter { "drawable-night" in it.name }.forEach { entry ->
          val day = BitmapFactory.decodeStream(zip.getInputStream(zip.getEntry(entry.name.replace("-night", ""))))
          val night = BitmapFactory.decodeStream(zip.getInputStream(entry))
          try { assertFalse(day.sameAs(night)) } finally { day.recycle(); night.recycle() }
        }
      }
    }
    assertTrue(server.requests.isEmpty())
    assertEquals("system_appstore", repo.search("App Store", options).single().id)
    assertTrue(server.requests.isEmpty())
  }

  @Test fun cancellationKeepsPublishedPackAndCompletedDownloads() = fixture { repo, server, _, _ ->
    repo.setMatch(target, options, first)
    val old = repo.generate(listOf(target), options) {}
    val second = target.copy(packageName = "test.android.new")
    val next = first.copy(id = "987654321")
    repo.setMatch(second, options, next); server.suspendId = next.id
    val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
      repo.generate(listOf(target, second), options) {}
    }
    server.suspended.await(); job.cancelAndJoin()
    assertEquals(old.pack.source, repo.descriptor()?.source)
    assertTrue(repo.cacheBytes() > 0)
  }

  @Test fun corruptedCachedPngIsRecoveredFromTheValidatedPublishedResource() = fixture { repo, server, root, _ ->
    repo.setMatch(target, options, first)
    val old = repo.generate(listOf(target), options) {}
    File(root, "downloads/images").listFiles()!!.forEach { it.writeText("broken") }
    server.requests.clear(); server.offline = true
    val repaired = repo.generate(listOf(target), options) {}
    assertTrue(repaired.unchanged)
    assertEquals(old.pack.source, repaired.pack.source)
    assertTrue(server.requests.isEmpty())
  }

  @Test fun metadataRejectsWrongPlatformAndMismatchedStoreIdentity() = fixture { _, _, _, _ ->
    val iosOnly = """{"data":[{"id":"123456789","attributes":{"name":"App","platformAttributes":{"ios":{"artwork":{"url":"https://is1-ssl.mzstatic.com/{w}x{h}{c}.{f}"}}}}}]}""".toByteArray()
    assertEquals(first.id, AppleIconClient.parseArtwork(iosOnly, first, options, 0).app.id)
    assertThrows(Exception::class.java) {
      AppleIconClient.parseArtwork(iosOnly, first, options.copy(platform = ApplePlatform.MACOS), 0)
    }
    assertThrows(Exception::class.java) {
      AppleIconClient.parseArtwork(iosOnly, first.copy(id = "987654321"), options, 0)
    }
  }

  @Test fun catalogRateLimitFallsBackToStandardArtworkWithoutInventingDarkPixels() = fixture { repo, server, _, _ ->
    server.catalogLimited = true; server.identicalDark = true
    repo.setMatch(target, options, first)
    val result = repo.generate(listOf(target), options) {}
    assertEquals(1, result.mapped); assertEquals(0, result.dark)
    assertEquals(1, result.stale)
    assertTrue(server.requests.any { "itunes.apple.com/lookup" in it })
  }

  @Test fun standardFallbackDoesNotDowngradePreviouslyCachedNativeArtwork() = fixture { repo, server, _, _ ->
    repo.setMatch(target, options, first)
    val original = repo.generate(listOf(target), options) {}
    server.catalogLimited = true; server.identicalDark = true
    val result = repo.generate(listOf(target), options, refresh = true) {}
    assertTrue(result.unchanged); assertEquals(original.pack.source, result.pack.source)
    assertEquals(1, result.dark); assertEquals(1, result.stale)
  }

  @Test fun changingMappingToAnUnavailableAppRetainsThePreviousAppliedArtwork() = fixture { repo, server, _, _ ->
    repo.setMatch(target, options, first)
    val original = repo.generate(listOf(target), options) {}
    repo.setMatch(target, options, first.copy(id = "987654321"))
    server.offline = true
    val result = repo.generate(listOf(target), options) {}
    assertTrue(result.unchanged); assertEquals(original.pack.source, result.pack.source)
    assertEquals(1, result.failed)
    assertTrue(repo.needsGeneration(listOf(target), options))
  }

  @Test fun differentPngMetadataDoesNotCountAsDifferentDarkArtwork() = fixture { repo, server, _, _ ->
    server.identicalDark = true; server.tagDarkPng = true
    repo.setMatch(target, options, first)
    val result = repo.generate(listOf(target), options) {}
    assertEquals(0, result.dark)
    assertEquals(2, File((result.pack.source as IconPackSource.Generated).file.parentFile!!.parentFile,
      "downloads/images").listFiles()!!.size)
  }

  @Test fun offlineMusicUsesVerifiedRoundedPairAndDoesNotFakeOriginalDark() = fixture { repo, server, _, _ ->
    server.offline = true
    val music = AppleAppTarget("com.apple.android.music", "Apple Music", false)
    val rounded = repo.generate(listOf(music), options) {}
    assertEquals(1, rounded.dark)
    val original = repo.generate(listOf(music), options.copy(corners = AppleCorners.ORIGINAL)) {}
    assertEquals(0, original.dark)
    assertTrue(server.requests.isEmpty())
  }
}
