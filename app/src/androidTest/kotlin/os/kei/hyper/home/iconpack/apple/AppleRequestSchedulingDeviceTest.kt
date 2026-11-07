package os.kei.hyper.home.iconpack.apple

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppleRequestSchedulingDeviceTest {
  private val options = AppleIconOptions()
  private val apps = (0 until 26).map {
    AppleAppMatch((1_000_000_000L + it).toString(), "App $it", "Apple")
  }
  private val template = "https://is1-ssl.mzstatic.com/image/thumb/test/Placeholder.mill/{w}x{h}{c}.{f}"
  private class Clock(var now: Long = 1_800_000_000_000L)

  private fun ids(url: String): List<String> {
    val query = URI(url).rawQuery.split("&").associate {
      val pair = it.split("=", limit = 2)
      URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }
    return (query["ids"] ?: query["id"]).orEmpty().split(",").filter { it.isNotEmpty() }
  }

  private fun catalog(ids: List<String>, attribute: String = "ios", name: String = "App"): ByteArray =
    JSONObject().put("data", JSONArray(ids.map { id ->
      JSONObject().put("id", id).put("attributes", JSONObject().put("name", "$name $id")
        .put("platformAttributes", JSONObject().put(attribute,
          JSONObject().put("iconArtwork", JSONObject().put("url", template)))))
    })).toString().toByteArray()

  private fun lookup(ids: List<String>): ByteArray =
    JSONObject().put("results", JSONArray(ids.map { id ->
      JSONObject().put("trackId", id).put("trackName", "Fallback $id").put("artistName", "Apple")
        .put("kind", "mac-software").put("supportedDevices", JSONArray().put("iPadAir"))
        .put("artworkUrl512", "https://is1-ssl.mzstatic.com/image/thumb/test/512x512bb.jpg")
    })).toString().toByteArray()

  private suspend fun rateFailure(block: suspend () -> Unit): AppleRequestException {
    try { block() } catch (failure: AppleRequestException) { return failure }
    throw AssertionError("Expected a typed Apple request failure")
  }

  @Test fun twentyFiveDistinctAppsAndDuplicatesUseOneCatalogRequest() = runBlocking {
    val urls = mutableListOf<String>()
    val client = AppleIconClient(AppleIconTransport { url ->
      urls += url
      catalog(ids(url))
    }, 0)
    val selected = apps.take(25)
    val result = client.artworks(selected + selected, options)
    assertEquals(1, urls.size)
    assertEquals(25, ids(urls.single()).size)
    assertEquals(25, result.size)
    assertTrue(urls.single().contains("/catalog/us/apps?"))
    selected.forEach { assertEquals(it.id, result[it.id]?.app?.id) }
  }

  @Test fun groupsNeverExceedTwentyFiveIds() = runBlocking {
    val urls = mutableListOf<String>()
    val client = AppleIconClient(AppleIconTransport { url -> urls += url; catalog(ids(url)) }, 0)
    assertEquals(26, client.artworks(apps, options).size)
    assertEquals(listOf(25, 1), urls.map { ids(it).size })
  }

  @Test fun aFailedCatalogHasOnlyOneBulkLookupForTheWholeGroup() = runBlocking {
    val urls = mutableListOf<String>()
    val client = AppleIconClient(AppleIconTransport { url ->
      urls += url
      if (URI(url).host == "apps.apple.com") throw IOException("Catalog unavailable")
      lookup(ids(url))
    }, 0)
    val result = client.artworks(apps.take(25), options)
    assertEquals(25, result.size)
    assertEquals(2, urls.size)
    assertEquals(25, ids(urls.last()).size)
    assertEquals("itunes.apple.com", URI(urls.last()).host)
    assertTrue(result.values.all { it.fallback && it.limited })
  }

  @Test fun aRateLimitedCatalogStillAllowsOneBulkFallbackOnTheOtherHost() = runBlocking {
    val clock = Clock()
    val urls = mutableListOf<String>()
    val client = AppleIconClient(AppleIconTransport { url ->
      urls += url
      if (URI(url).host == "apps.apple.com") throw AppleRequestException(429, clock.now + 300_000)
      lookup(ids(url))
    }, 0, { clock.now })
    assertEquals(25, client.artworks(apps.take(25), options).size)
    assertEquals(2, urls.size)
    assertEquals(25, ids(urls.last()).size)
    assertEquals(clock.now + 300_000, client.retryAtMillis())
  }

  @Test fun repeatedAndRestoredBatchesCannotRepeatHttpWhenBothHostsAreBlocked() = runBlocking {
    val clock = Clock()
    var calls = 0
    val client = AppleIconClient(AppleIconTransport { url ->
      calls++
      if (URI(url).host == "apps.apple.com") throw AppleRequestException(429, clock.now + 300_000)
      throw AppleRequestException(503, clock.now + 600_000)
    }, 0, { clock.now })
    repeat(3) {
      val failure = rateFailure { client.artworks(apps.take(25), options) }
      assertEquals(503, failure.status)
      assertEquals(clock.now + 600_000, failure.retryAtMillis)
    }
    assertEquals(2, calls)
    val restored = AppleIconClient(AppleIconTransport { throw AssertionError("Unexpected HTTP") },
      0, { clock.now })
    restored.restoreBackoff(client.backoffSnapshot())
    assertEquals(clock.now + 600_000,
      rateFailure { restored.artworks(apps.take(25), options) }.retryAtMillis)
  }

  @Test fun partialCatalogLooksUpOnlyMissingIdsOnce() = runBlocking {
    val urls = mutableListOf<String>()
    val client = AppleIconClient(AppleIconTransport { url ->
      urls += url
      if (URI(url).host == "apps.apple.com") catalog(ids(url).take(10)) else lookup(ids(url))
    }, 0)
    val result = client.artworks(apps.take(25), options)
    assertEquals(25, result.size)
    assertEquals(2, urls.size)
    assertEquals(apps.drop(10).take(15).map { it.id }, ids(urls.last()))
    assertEquals(10, result.values.count { !it.fallback })
  }

  @Test fun failedBulkLookupNeverBecomesPerAppLookups() = runBlocking {
    val urls = mutableListOf<String>()
    val client = AppleIconClient(AppleIconTransport { url -> urls += url; throw IOException("Unavailable") }, 0)
    try {
      client.artworks(apps.take(25), options)
      fail("Expected failure")
    } catch (_: IOException) { }
    assertEquals(2, urls.size)
  }

  @Test fun storefrontOverridesAreSeparateEvenForTheSameId() = runBlocking {
    val urls = mutableListOf<String>()
    val client = AppleIconClient(AppleIconTransport { url ->
      urls += url
      catalog(ids(url), name = URI(url).path.split("/")[5])
    }, 0)
    val british = options.copy(country = "gb")
    val selected = listOf(apps[0], apps[0].copy(country = "gb"),
      apps[0].copy(country = "us"), apps[0].copy(country = "jp"))
    for ((country, group) in selected.groupBy { british.forApp(it).country }) {
      val result = client.artworks(group, british)
      assertEquals(setOf(apps[0].id), result.keys)
      assertTrue(result.getValue(apps[0].id).app.name.startsWith(country))
    }
    assertEquals(3, urls.size)
    assertTrue(urls.any { it.contains("/catalog/gb/apps?") })
    assertTrue(urls.any { it.contains("/catalog/us/apps?") })
    assertTrue(urls.any { it.contains("/catalog/jp/apps?") })
    assertTrue(urls.all { ids(it).size == 1 })
  }

  @Test fun mixedStorefrontBatchesAreRejectedBeforeAnyHttp() {
    val client = AppleIconClient(AppleIconTransport { throw AssertionError("Unexpected request") }, 0)
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { client.artworks(listOf(apps[0], apps[0].copy(country = "jp")), options) }
    }
  }

  @Test fun eachFallbackAlsoRespectsTheAppsCountry() = runBlocking {
    val urls = mutableListOf<String>()
    val client = AppleIconClient(AppleIconTransport { url ->
      urls += url
      if (URI(url).host == "apps.apple.com") throw IOException("Unavailable")
      lookup(ids(url))
    }, 0)
    for (app in listOf(apps[0].copy(country = "jp"), apps[1])) {
      assertEquals(setOf(app.id), client.artworks(listOf(app), options).keys)
    }
    val lookups = urls.filter { URI(it).host == "itunes.apple.com" }
    assertEquals(2, lookups.size)
    assertTrue(lookups.any { it.contains("country=jp") && ids(it) == listOf(apps[0].id) })
    assertTrue(lookups.any { it.contains("country=us") && ids(it) == listOf(apps[1].id) })
  }

  @Test fun singleDelegatesAndPlatformAttributesStaySeparate() = runBlocking {
    val urls = mutableListOf<String>()
    val client = AppleIconClient(AppleIconTransport { url ->
      urls += url
      catalog(ids(url), if (url.contains("additionalPlatforms=mac")) "osx" else "ios")
    }, 0)
    for (platform in ApplePlatform.entries) {
      assertEquals(apps[0].id, client.artwork(apps[0], options.copy(platform = platform)).app.id)
    }
    assertEquals(3, urls.size)
    assertEquals(2, urls.count { it.contains("additionalPlatforms=iphone") })
    assertEquals(1, urls.count { it.contains("additionalPlatforms=mac") })
  }

  @Test fun emptyAndNativeListsNeverRequestPublicMetadata() = runBlocking {
    val client = AppleIconClient(AppleIconTransport { throw AssertionError("Unexpected request") }, 0)
    assertTrue(client.artworks(emptyList(), options).isEmpty())
    assertTrue(client.artworks(listOf(AppleAppMatch("system_siri", "Siri", "Apple")), options).isEmpty())
  }

  @Test fun retryAfterSupportsSecondsAndHttpDatesAndBoundsUntrustedValues() {
    val clock = Clock()
    assertEquals(clock.now + 120_000, appleRetryAfterMillis("120", clock.now))
    val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(
      Instant.ofEpochMilli(clock.now + 300_000).atZone(ZoneOffset.UTC))
    assertEquals(clock.now + 300_000, appleRetryAfterMillis(date, clock.now))
    for (header in listOf(null, "invalid", "-4", "NaN", "9".repeat(200))) {
      assertEquals(0, appleRetryAfterMillis(header, clock.now))
    }
    assertEquals(clock.now + 86_400_000, appleRetryAfterMillis(Long.MAX_VALUE.toString(), clock.now))
  }

  @Test fun retryAfterCooldownStopsRepeatedHttpAndPublishesItsDeadline() = runBlocking {
    val clock = Clock()
    var calls = 0
    val snapshots = mutableListOf<String>()
    val client = AppleIconClient(AppleIconTransport {
      calls++
      throw AppleRequestException(429, clock.now + 300_000)
    }, 0, { clock.now })
    client.setOnBackoffChanged { snapshots += it }
    val first = rateFailure { client.search("Notes", options) }
    assertEquals(clock.now + 300_000, first.retryAtMillis)
    repeat(3) {
      assertEquals(first.retryAtMillis, rateFailure { client.search("Mail", options) }.retryAtMillis)
    }
    assertEquals(1, calls)
    assertEquals(1, snapshots.size)
    assertEquals(first.retryAtMillis, client.retryAtMillis())
    assertEquals(1, JSONObject(snapshots.single()).getJSONObject("hosts")
      .getJSONObject("itunes.apple.com").getInt("strikes"))
  }

  @Test fun aRestoredCooldownBlocksRequestsWithoutContactingApple() = runBlocking {
    val clock = Clock()
    val original = AppleIconClient(AppleIconTransport { throw AppleRequestException(503) }, 0, { clock.now })
    val failure = rateFailure { original.search("Notes", options) }
    val restored = AppleIconClient(AppleIconTransport { throw AssertionError("Cooldown ignored") },
      0, { clock.now })
    restored.restoreBackoff(original.backoffSnapshot())
    assertEquals(failure.retryAtMillis, restored.retryAtMillis())
    val next = rateFailure { restored.search("Notes", options) }
    assertEquals(503, next.status)
    assertEquals(failure.retryAtMillis, next.retryAtMillis)
  }

  @Test fun backoffGrowsOnManualAttemptsAfterExpiryAndIsBounded() = runBlocking {
    val clock = Clock()
    var calls = 0
    val client = AppleIconClient(AppleIconTransport { calls++; throw AppleRequestException(503) },
      0, { clock.now })
    repeat(10) { index ->
      val failure = rateFailure { client.search("Notes", options) }
      val expectedDelay = (60_000L shl index.coerceAtMost(6)).coerceAtMost(3_600_000)
      assertEquals(clock.now + expectedDelay, failure.retryAtMillis)
      assertEquals(index + 1, calls)
      clock.now = failure.retryAtMillis
    }
    val state = JSONObject(client.backoffSnapshot()).getJSONObject("hosts").getJSONObject("itunes.apple.com")
    assertEquals(7, state.getInt("strikes"))
  }

  @Test fun aSuccessfulManualRequestClearsBackoffAndPersistsTheChange() = runBlocking {
    val clock = Clock()
    var fail = true
    val snapshots = mutableListOf<String>()
    val client = AppleIconClient(AppleIconTransport {
      if (fail) throw AppleRequestException(429)
      """{"results":[]}""".toByteArray()
    }, 0, { clock.now })
    client.setOnBackoffChanged { snapshots += it }
    clock.now = rateFailure { client.search("Notes", options) }.retryAtMillis
    fail = false
    assertTrue(client.search("Notes", options).isEmpty())
    assertEquals(0, client.retryAtMillis())
    assertEquals(2, snapshots.size)
    assertEquals(0, JSONObject(snapshots.last()).getJSONObject("hosts").length())
  }

  @Test fun restoredStrikesContinueExponentialBackoff() = runBlocking {
    val clock = Clock()
    val first = AppleIconClient(AppleIconTransport { throw AppleRequestException(429) }, 0, { clock.now })
    clock.now = rateFailure { first.search("Notes", options) }.retryAtMillis
    val restored = AppleIconClient(AppleIconTransport { throw AppleRequestException(429) }, 0, { clock.now })
    restored.restoreBackoff(first.backoffSnapshot())
    assertEquals(clock.now + 120_000, rateFailure { restored.search("Notes", options) }.retryAtMillis)
  }

  @Test fun invalidSnapshotsCannotInstallForeignOrUnboundedCooldowns() {
    val clock = Clock()
    val client = AppleIconClient(AppleIconTransport { throw AssertionError("Unexpected request") },
      0, { clock.now })
    val state = JSONObject().put("retryAtMillis", clock.now + 60_000).put("strikes", 1).put("status", 429)
    val hosts = JSONObject().put("evil.example", state)
      .put("itunes.apple.com", JSONObject(state.toString()).put("retryAtMillis", clock.now + 86_400_001))
      .put("apps.apple.com", JSONObject(state.toString()).put("strikes", 999))
      .put("is1-ssl.mzstatic.com", JSONObject(state.toString()).put("status", 404))
    client.restoreBackoff(JSONObject().put("version", 1).put("hosts", hosts).toString())
    for (value in listOf(null, "", "bad json", "x".repeat(8_193),
      JSONObject().put("version", 999).put("hosts", JSONObject().put("itunes.apple.com", state)).toString())) {
      client.restoreBackoff(value)
    }
    assertEquals(0, client.retryAtMillis())
    assertEquals(0, JSONObject(client.backoffSnapshot()).getJSONObject("hosts").length())
  }

  @Test fun concurrentCallsCannotHammerABlockedHost() = runBlocking {
    var calls = 0
    val client = AppleIconClient(AppleIconTransport { calls++; throw AppleRequestException(429) }, 0)
    coroutineScope {
      (0 until 8).map { async { rateFailure { client.search("Notes", options) } } }.awaitAll()
    }
    assertEquals(1, calls)
  }

  @Test fun cdnCooldownIsEnforcedAndDoesNotBlockMetadataOnAnotherHost() = runBlocking {
    val clock = Clock()
    val urls = mutableListOf<String>()
    val client = AppleIconClient(AppleIconTransport { url ->
      urls += url
      if (URI(url).host == "is1-ssl.mzstatic.com") throw AppleRequestException(503, clock.now + 120_000)
      """{"results":[]}""".toByteArray()
    }, 0, { clock.now })
    val image = "https://is1-ssl.mzstatic.com/image/test.png"
    repeat(2) { assertEquals(clock.now + 120_000, rateFailure { client.image(image) }.retryAtMillis) }
    assertTrue(client.search("Notes", options).isEmpty())
    assertEquals(2, urls.size)
  }

  @Test fun cancellationNeverStartsFallbackOrCreatesBackoff() {
    var calls = 0
    val client = AppleIconClient(AppleIconTransport { calls++; throw CancellationException("Cancelled") }, 0)
    assertThrows(CancellationException::class.java) {
      runBlocking { client.artworks(apps.take(25), options) }
    }
    assertEquals(1, calls)
    assertEquals(0, client.retryAtMillis())
  }
}
