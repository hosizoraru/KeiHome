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
import java.util.zip.ZipOutputStream
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
    assertEquals(38, records.length())
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
    var catalogStandardOnly = false
    var tagDarkPng = false
    var storefrontArtwork = false
    var lightColor: Int? = null
    var suspendId: String? = null
    val suspended = CompletableDeferred<Unit>()
    override suspend fun get(url: String): ByteArray {
      requests += url
      if (offline) throw IOException("offline")
      if (url.contains("/apps/")) {
        if (catalogLimited) throw AppleRequestException(429)
        val ids = java.net.URI(url).query.split('&').first { it.startsWith("ids=") }.substringAfter('=').split(',')
        if (suspendId in ids) { suspended.complete(Unit); awaitCancellation() }
        val country = java.net.URI(url).path.substringAfter("/catalog/").substringBefore('/')
        val region = (if (catalogStandardOnly) "standard/" else "") +
          (if (storefrontArtwork) "$country/" else "")
        val data = JSONArray()
        for (id in ids) {
          val art = JSONObject().put("url", "https://is1-ssl.mzstatic.com/image/thumb/r$revision/$region$id/{w}x{h}{c}.{f}")
          val attributes = JSONObject().put("name", "Test Music").put("platformAttributes",
            JSONObject().put("ios", JSONObject().put(if (catalogStandardOnly) "artwork" else "iconArtwork", art)))
          data.put(JSONObject().put("id", id).put("attributes", attributes))
        }
        return JSONObject().put("data", data).toString().toByteArray()
      }
      if ("itunes.apple.com/lookup" in url || "itunes.apple.com/search" in url) {
        return JSONObject().put("results", JSONArray().put(JSONObject()
          .put("trackId", "123456789").put("trackName", "Music").put("artistName", "Publisher")
          .put("kind", "software").put("supportedDevices", JSONArray().put("iPhone").put("iPad"))
          .put("artworkUrl512", "https://is1-ssl.mzstatic.com/image/thumb/r1/123456789/512x512bb.jpg")))
          .toString().toByteArray()
      }
      val size = Regex("/(256|512|1024)x").find(url)!!.groupValues[1].toInt()
      val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(if ("appearance=dark" in url && !identicalDark) Color.BLACK
        else lightColor ?: if ("/jp/" in url) Color.RED else if ("/r2/" in url) Color.GREEN else Color.WHITE)
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
    val preview = checkNotNull(repo.cachedPreview(first, options))
    assertTrue(preview.light.isFile)
    assertNotNull(preview.dark)
    assertTrue(server.requests.isEmpty())
    val rebuilt = repo.generate(listOf(target), options) {}
    assertTrue(rebuilt.unchanged); assertTrue(server.requests.isEmpty())
    assertTrue(repo.cacheBytes() > 0)
  }

  @Test fun sameAppleIdInTwoStorefrontsPublishesDistinctResourcesAndRestoresEachOffline() = fixture { repo, server, _, _ ->
    server.storefrontArtwork = true
    val second = target.copy(packageName = "test.android.japanese")
    val japanese = first.copy(country = "jp")
    repo.setMatch(target, options, first.copy(country = "us"))
    repo.setMatch(second, options, japanese)
    val result = repo.generate(listOf(target, second), options) {}
    assertEquals(2, result.mapped)
    assertEquals(6, server.requests.size)
    assertTrue(server.requests.any { "/catalog/us/apps?ids=${first.id}" in it })
    assertTrue(server.requests.any { "/catalog/jp/apps?ids=${first.id}" in it })
    assertFalse(repo.needsGeneration(listOf(target, second), options))
    ZipFile((result.pack.source as IconPackSource.Generated).file).use { zip ->
      val manifest = JSONObject(zip.getInputStream(zip.getEntry("apple-source-manifest.json")).bufferedReader().readText())
      assertEquals(2, manifest.getInt("schema"))
      assertEquals("${first.id}_us", manifest.getJSONObject("mappings").getString(target.packageName))
      assertEquals("${first.id}_jp", manifest.getJSONObject("mappings").getString(second.packageName))
      assertTrue(zip.getInputStream(zip.getEntry("appfilter.xml")).bufferedReader().readText()
        .contains("drawable=\"apple_${first.id}_jp\""))
      assertEquals(4, zip.entries().asSequence().count { it.name.endsWith(".png") })
    }
    repo.clearDownloads()
    server.requests.clear(); server.offline = true
    val us = checkNotNull(repo.cachedPreview(first, options))
    val jp = checkNotNull(repo.cachedPreview(japanese, options.copy(country = "cn")))
    assertPixel(Color.WHITE, us.light)
    assertPixel(Color.RED, jp.light)
    assertNotEquals(us.light, jp.light)
    assertNotNull(jp.dark)
    assertTrue(server.requests.isEmpty())
    assertTrue(repo.generate(listOf(target, second), options) {}.unchanged)
    assertTrue(server.requests.isEmpty())
  }

  @Test fun manualStorefrontPersistsAcrossReopeningAndGlobalCountryChangesWithLegacyJsonSupport() = fixture { repo, _, dir, name ->
    val japanese = first.copy(country = "jp")
    repo.setMatch(target, options, japanese)
    val reopened = AppleIconRepository(context, AppleIconClient(Server(), 0), dir, name)
    assertEquals(japanese, reopened.match(target, options.copy(country = "cn")))
    val storage = AppLocalStorage.open(context, name)
    val key = "mapping.${options.platform.name}.${target.packageName}"
    assertEquals("jp", JSONObject(storage.getString(key, null)!!).getString("country"))
    val legacy = JSONObject().put("id", first.id).put("name", first.name)
      .put("publisher", first.publisher).put("bundleId", first.bundleId)
    storage.putString(key, legacy.toString())
    assertEquals(first, reopened.match(target, options))
    legacy.put("country", JSONObject.NULL)
    storage.putString(key, legacy.toString())
    assertEquals(first, reopened.match(target, options))
    repo.setMatch(target, options, first)
    assertEquals(first, reopened.match(target, options))
    for (country in listOf("gb", "ca", "tw", "kr")) {
      val app = first.copy(country = country)
      repo.setMatch(target, options, app)
      assertEquals(app, reopened.match(target, options.copy(country = "hk")))
    }
  }

  @Test fun miHomeStaysUnmappedUnlessTheUserChoosesAnAppleProduct() = fixture { repo, _, dir, name ->
    val home = AppleAppTarget("com.xiaomi.smarthome", "米家", true)
    assertNull(repo.match(home, options))
    assertNull(repo.match(home.copy(system = false), options))
    val chosen = AppleAppMatch("1110145103", "Home", "Apple", "com.apple.Home", country = "jp")
    repo.setMatch(home, options, chosen)
    val reopened = AppleIconRepository(context, AppleIconClient(Server(), 0), dir, name)
    assertEquals(chosen, reopened.match(home, options.copy(country = "cn")))
    repo.resetMatch(home, options)
    assertNull(reopened.match(home, options))
  }

  @Test fun automaticStorefrontHintsNeverReplaceManualChoicesOrExclusions() = fixture { repo, _, _, _ ->
    val google = AppleAppTarget("com.google.android.googlequicksearchbox", "Google", true)
    val cn = options.copy(country = "cn")
    val automatic = checkNotNull(repo.match(google, cn))
    assertEquals("us", automatic.country)
    val manual = automatic.copy(country = "cn")
    repo.setMatch(google, cn, manual)
    assertEquals(manual, repo.match(google, cn))
    repo.setMatch(google, cn, null)
    assertNull(repo.match(google, cn))
    repo.resetMatch(google, cn)
    assertEquals(automatic, repo.match(google, cn))
  }

  @Test fun onlineAndCachedSearchResultsCarryTheirSelectedStorefrontWithoutArtworkRequests() = fixture { repo, server, root, _ ->
    val japaneseOptions = options.copy(country = "jp")
    val result = repo.search("Test player", japaneseOptions).single()
    assertEquals("jp", result.country)
    assertEquals(1, server.requests.size)
    assertTrue(server.requests.single().contains("country=jp"))
    assertEquals(result, repo.search("Test player", japaneseOptions).single())
    // Older search caches lack the new optional field, but their cache key proves the storefront.
    val searchFile = File(root, "downloads/metadata").listFiles()!!.single()
    val legacy = JSONObject(searchFile.readText())
    legacy.getJSONArray("apps").getJSONObject(0).remove("country")
    searchFile.writeText(legacy.toString())
    assertEquals("jp", repo.search("Test player", japaneseOptions).single().country)
    assertEquals(1, server.requests.size)
  }

  @Test fun regionOnlyMappingChangeInvalidatesPublicationAndCannotRetainPixelsFromOldStorefront() = fixture { repo, server, _, _ ->
    server.storefrontArtwork = true
    repo.setMatch(target, options, first)
    val old = repo.generate(listOf(target), options) {}
    val japanese = first.copy(country = "jp")
    repo.setMatch(target, options, japanese)
    assertTrue(repo.needsGeneration(listOf(target), options))
    server.requests.clear(); server.offline = true
    assertNull(repo.cachedPreview(japanese, options))
    assertTrue(server.requests.isEmpty())
    val failure = runCatching { repo.generate(listOf(target), options) {} }.exceptionOrNull()
    assertTrue(failure is IOException)
    assertEquals(old.pack.source, repo.descriptor()?.source)
    assertTrue(repo.needsGeneration(listOf(target), options))
    server.requests.clear(); server.offline = false
    val changed = repo.generate(listOf(target), options) {}
    assertFalse(changed.unchanged)
    assertEquals(0, changed.reused)
    assertEquals(3, server.requests.size)
    assertNotEquals(old.pack.source, changed.pack.source)
    assertPixel(Color.RED, checkNotNull(repo.cachedPreview(japanese, options)).light)
    assertFalse(repo.needsGeneration(listOf(target), options))
  }

  @Test fun sharedCdnUrlDoesNotReuseAnotherStorefrontsCachedPixels() = fixture { repo, server, _, _ ->
    val us = repo.preview(first, options)
    assertPixel(Color.WHITE, us.light)
    server.lightColor = Color.RED
    val japanese = first.copy(country = "jp")
    val jp = repo.preview(japanese, options)
    assertPixel(Color.RED, jp.light)
    assertEquals(6, server.requests.size)
    server.requests.clear(); server.offline = true
    assertPixel(Color.WHITE, checkNotNull(repo.cachedPreview(first, options)).light)
    assertPixel(Color.RED, checkNotNull(repo.cachedPreview(japanese, options)).light)
    assertTrue(server.requests.isEmpty())
  }

  @Test fun explicitStorefrontSurvivesGlobalCountryChangeAndStandardLookupFallback() = fixture { repo, server, _, _ ->
    server.catalogLimited = true
    val korean = first.copy(country = "kr")
    repo.setMatch(target, options, korean)
    repo.generate(listOf(target), options.copy(country = "gb")) {}
    assertTrue(server.requests.any { "/catalog/kr/apps?ids=${first.id}" in it })
    assertTrue(server.requests.any { "itunes.apple.com/lookup" in it && "country=kr" in it })
    server.requests.clear(); server.offline = true
    assertNotNull(repo.cachedPreview(korean, options.copy(country = "tw")))
    val result = repo.generate(listOf(target), options.copy(country = "ca")) {}
    assertEquals(1, result.reused)
    assertTrue(server.requests.isEmpty())
    assertFalse(repo.needsGeneration(listOf(target), options.copy(country = "ca")))
  }

  @Test fun legacyPublicationOnlyRestoresItsRecordedCountryAndMigratesOfflineToRegionResources() = fixture { repo, server, root, _ ->
    repo.setMatch(target, options, first)
    val initial = repo.generate(listOf(target), options) {}
    val currentFile = File(root, "current.json")
    val legacy = JSONObject(currentFile.readText())
    val resourceId = "${first.id}_us"
    val artwork = legacy.getJSONObject("artwork")
    artwork.put(first.id, artwork.remove(resourceId))
    legacy.getJSONObject("mappings").put(target.packageName, first.id)
    legacy.put("schema", 1).remove("digest")
    val createdAt = legacy.remove("createdAt")
    val digest = AppleIconPolicy.key(legacy.toString())
    val legacyZip = File(root, "generated/$digest.zip")
    ZipFile((initial.pack.source as IconPackSource.Generated).file).use { original ->
      ZipOutputStream(legacyZip.outputStream().buffered()).use { output ->
        for (entry in original.entries().asSequence()) {
          val name = entry.name.replace("apple_$resourceId", "apple_${first.id}")
          val data = when (name) {
            "apple-source-manifest.json" -> legacy.toString().toByteArray()
            "appfilter.xml" -> original.getInputStream(entry).bufferedReader().use { it.readText() }
              .replace("apple_$resourceId", "apple_${first.id}").toByteArray()
            else -> original.getInputStream(entry).use { it.readBytes() }
          }
          output.putNextEntry(ZipEntry(name)); output.write(data); output.closeEntry()
        }
      }
    }
    legacy.put("digest", digest).put("createdAt", createdAt)
    currentFile.writeText(legacy.toString())
    repo.clearDownloads()
    server.requests.clear(); server.offline = true
    assertTrue(repo.needsGeneration(listOf(target), options))
    assertNull(repo.cachedPreview(first.copy(country = "gb"), options))
    assertPixel(Color.WHITE, checkNotNull(repo.cachedPreview(first, options)).light)
    val migrated = repo.generate(listOf(target), options) {}
    assertEquals(1, migrated.reused)
    assertFalse(migrated.unchanged)
    assertNotEquals(legacyZip, (migrated.pack.source as IconPackSource.Generated).file)
    assertEquals(2, JSONObject(currentFile.readText()).getInt("schema"))
    assertFalse(repo.needsGeneration(listOf(target), options))
    assertTrue(server.requests.isEmpty())
    repo.clearDownloads()
    assertNotNull(repo.cachedPreview(first.copy(country = "us"), options.copy(country = "tw")))
    assertNull(repo.cachedPreview(first, options.copy(country = "gb")))
    assertTrue(server.requests.isEmpty())
  }

  @Test fun cacheOnlyPreviewMissesNeverRequestEvenWithMissingOrCorruptCachedImages() = fixture { repo, server, root, _ ->
    assertNull(repo.cachedPreview(first, options))
    assertTrue(server.requests.isEmpty())
    repo.preview(first, options)
    server.requests.clear()
    assertNotNull(repo.cachedPreview(first, options))
    assertNull(repo.cachedPreview(first, options.copy(resolution = 512)))
    assertNull(repo.cachedPreview(first.copy(country = "jp"), options))
    assertNull(repo.cachedPreview(first.copy(id = "987654321"), options))
    assertTrue(server.requests.isEmpty())
    File(root, "downloads/images").listFiles()!!.forEach { it.writeText("broken") }
    assertNull(repo.cachedPreview(first, options))
    assertTrue(server.requests.isEmpty())
    repo.clearDownloads()
    assertNull(repo.cachedPreview(first, options))
    assertTrue(server.requests.isEmpty())
  }

  private fun assertPixel(expected: Int, file: File) {
    val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path))
    try { assertEquals(expected, bitmap.getPixel(0, 0)) } finally { bitmap.recycle() }
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

  @Test fun oldLimitedMetadataIsPromotedWhileOtherArtworkRemainsCached() = fixture { repo, server, root, _ ->
    server.catalogStandardOnly = true; server.identicalDark = true
    repo.setMatch(target, options, first)
    assertEquals(0, repo.generate(listOf(target), options) {}.dark)
    val metadata = File(root, "downloads/metadata/${AppleIconPolicy.key("art:ios:us:${first.id}")}.json")
    metadata.writeText(JSONObject(metadata.readText()).apply { remove("catalogVersion") }.toString())
    server.catalogStandardOnly = false; server.identicalDark = false
    val promoted = repo.generate(listOf(target), options) {}
    assertEquals(1, promoted.dark)
    assertEquals(0, promoted.stale)
    val calls = server.requests.size
    assertTrue(repo.generate(listOf(target), options) {}.unchanged)
    assertEquals(calls, server.requests.size)
    assertTrue(server.requests.first().contains("/apps?ids=${first.id}"))
  }

  @Test fun limitedMetadataRetriesAfterCooldown() = fixture { repo, server, root, _ ->
    server.catalogStandardOnly = true; server.identicalDark = true
    repo.setMatch(target, options, first)
    repo.generate(listOf(target), options) {}
    val calls = server.requests.size
    repo.generate(listOf(target), options) {}
    assertEquals(calls, server.requests.size)
    val metadata = File(root, "downloads/metadata/${AppleIconPolicy.key("art:ios:us:${first.id}")}.json")
    metadata.writeText(JSONObject(metadata.readText())
      .put("time", System.currentTimeMillis() - 16 * 60 * 1000L).toString())
    server.catalogStandardOnly = false; server.identicalDark = false
    assertEquals(1, repo.generate(listOf(target), options) {}.dark)
  }

  @Test fun completingMissingResourcesDoesNotRefreshFinishedApps() = fixture { repo, server, _, _ ->
    repo.setMatch(target, options, first)
    val initial = repo.generate(listOf(target), options) {}
    val calls = server.requests.size
    val again = repo.generate(listOf(target), options, retryIncomplete = true) {}
    assertTrue(again.unchanged)
    assertEquals(initial.dark, again.dark)
    assertEquals(calls, server.requests.size)
  }

  @Test fun individualRefreshIsUsedByTheNextGenerationWithoutRefetchingOtherApps() = fixture { repo, server, _, _ ->
    val other = AppleAppTarget("android.other", "Other", false)
    val second = first.copy(id = "987654321")
    repo.setMatch(target, options, first); repo.setMatch(other, options, second)
    repo.generate(listOf(target, other), options) {}
    assertEquals(1, server.requests.count { "/api/apps/" in it })
    val before = repo.cachedPreview(second, options)!!.light.readBytes()
    server.revision = 2; server.lightColor = Color.GREEN
    repo.preview(first, options, refresh = true)
    assertTrue(repo.needsGeneration(listOf(target, other), options))
    val calls = server.requests.size
    val regenerated = repo.generate(listOf(target, other), options) {}
    assertFalse(regenerated.unchanged)
    assertEquals(calls, server.requests.size)
    assertArrayEquals(before, repo.cachedPreview(second, options)!!.light.readBytes())
    assertFalse(repo.needsGeneration(listOf(target, other), options))
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

  @Test fun localChoicesAndTheirPreviewsRetainTheSearchStorefront() = fixture { repo, server, _, _ ->
    for (query in listOf("Apple Music", "App Store", "Settings")) {
      val match = repo.search(query, options.copy(country = "jp")).single()
      assertEquals("jp", match.country)
      val preview = repo.preview(match, options)
      assertEquals("jp", preview.app.country)
      assertTrue(preview.light.isFile)
    }
    assertTrue(server.requests.isEmpty())
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
