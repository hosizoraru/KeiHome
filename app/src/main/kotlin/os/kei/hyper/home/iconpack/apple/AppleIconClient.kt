package os.kei.hyper.home.iconpack.apple

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class AppleRequestException(val status: Int) : IOException("Apple HTTP $status")

internal fun interface AppleIconTransport {
  suspend fun get(url: String): ByteArray
}

/** One bounded request at a time; cancellation also disconnects blocking HTTP reads. */
internal class AppleHttpTransport : AppleIconTransport {
  override suspend fun get(url: String): ByteArray {
    try { return withTimeout(30_000) { fetch(url) }
    } catch (failure: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      throw IOException("Apple request timed out", failure)
    }
  }

  private suspend fun fetch(url: String): ByteArray = withContext(Dispatchers.IO) {
    var next = url
    repeat(4) {
      AppleIconPolicy.validateUrl(next)
      val connection = URI(next).toURL().openConnection() as HttpURLConnection
      connection.instanceFollowRedirects = false
      connection.connectTimeout = 12_000
      connection.readTimeout = 15_000
      connection.setRequestProperty("User-Agent", "KeiHome/AppleArtwork")
      connection.setRequestProperty("Accept", "application/json,image/png")
      val response = coroutineScope {
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
          try { awaitCancellation() } finally { connection.disconnect() }
        }
        try {
          currentCoroutineContext().ensureActive()
          val code = connection.responseCode
          if (code in listOf(301, 302, 303, 307, 308)) {
            val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
            next = URI(next).resolve(location).toString()
            null
          } else {
            if (code != 200) throw AppleRequestException(code)
            if (connection.contentLengthLong > AppleIconPolicy.MAX_RESPONSE) throw IOException("Apple response too large")
            connection.inputStream.use { input ->
              val output = ByteArrayOutputStream()
              val buffer = ByteArray(16 * 1024)
              while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > AppleIconPolicy.MAX_RESPONSE) throw IOException("Apple response too large")
                output.write(buffer, 0, count)
              }
              output.toByteArray()
            }
          }
        } finally { closer.cancel(); connection.disconnect() }
      }
      if (response != null) return@withContext response
    }
    throw IOException("Too many Apple redirects")
  }
}

internal class AppleIconClient(private val transport: AppleIconTransport = AppleHttpTransport(),
  private val metadataIntervalMillis: Long = 3_500) {
  private val requests = Mutex()
  private var nextMetadataAt = 0L
  private val blockedUntil = mutableMapOf<String, Long>()

  suspend fun search(term: String, options: AppleIconOptions): List<AppleAppMatch> {
    require(term.trim().length in 1..100)
    val direct = term.trim().matches(Regex("[0-9]{6,12}"))
    val body = metadataRequest("https://itunes.apple.com/${if (direct) "lookup" else "search"}?" + query(mapOf(
      (if (direct) "id" else "term") to term.trim(), "country" to options.country,
      "entity" to options.platform.entity, "limit" to "20",
    )))
    val results = JSONObject(String(body, Charsets.UTF_8)).getJSONArray("results")
    return buildList {
      for (index in 0 until minOf(results.length(), 20)) {
        val row = results.getJSONObject(index)
        if (options.platform == ApplePlatform.MACOS && row.optString("kind") != "mac-software") continue
        if (options.platform == ApplePlatform.IPADOS && (row.optJSONArray("supportedDevices")?.let { devices ->
            (0 until devices.length()).none { devices.optString(it).startsWith("iPad") }
          } ?: true)) continue
        val id = row.optString("trackId")
        if (id.matches(Regex("[0-9]{6,12}"))) add(AppleAppMatch(id,
          row.optString("trackName").take(200), row.optString("artistName").take(200),
          row.optString("bundleId").take(200), country = options.country))
      }
    }.distinctBy { it.id }
  }

  suspend fun artwork(app: AppleAppMatch, options: AppleIconOptions): AppleArtwork {
    val effective = options.forApp(app)
    // The storefront groups iPhone and iPad artwork under ios. iPad eligibility comes from search.
    val additional = if (options.platform == ApplePlatform.MACOS) "mac" else "iphone"
    try {
      val body = metadataRequest("https://apps.apple.com/api/apps/v1/catalog/${effective.country}/apps/${app.id}?" +
        query(mapOf("platform" to "web", "additionalPlatforms" to additional, "extend" to "iconArtwork",
          "fields[apps]" to "name,artwork,iconArtwork", "l" to "en-US")))
      return parseArtwork(body, app, effective, System.currentTimeMillis())
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) {
      val body = metadataRequest("https://itunes.apple.com/lookup?" +
        query(mapOf("id" to app.id, "country" to effective.country)))
      return parseStandardArtwork(body, app, effective, System.currentTimeMillis())
    }
  }

  suspend fun image(url: String): ByteArray {
    AppleIconPolicy.validateUrl(url, artwork = true)
    return transport.get(url)
  }

  private suspend fun metadataRequest(url: String): ByteArray = requests.withLock {
    val now = android.os.SystemClock.elapsedRealtime()
    val host = URI(url).host
    if (now < (blockedUntil[host] ?: 0L)) throw AppleRequestException(429)
    delay((nextMetadataAt - now).coerceAtLeast(0))
    try {
      transport.get(url)
    } catch (failure: AppleRequestException) {
      if (failure.status == 429) blockedUntil[host] = android.os.SystemClock.elapsedRealtime() + 60_000
      throw failure
    } finally { nextMetadataAt = android.os.SystemClock.elapsedRealtime() + metadataIntervalMillis }
  }

  companion object {
    private fun query(values: Map<String, String>): String = values.entries.joinToString("&") {
      "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }.replace("%2C", ",").replace("%5B", "[").replace("%5D", "]")

    internal fun parseArtwork(body: ByteArray, app: AppleAppMatch, options: AppleIconOptions,
      now: Long): AppleArtwork {
      val data = JSONObject(String(body, Charsets.UTF_8)).getJSONArray("data")
      val entry = (0 until data.length()).map { data.getJSONObject(it) }.singleOrNull {
        it.optString("id") == app.id
      } ?: throw IOException("Apple application unavailable")
      val attributes = entry.getJSONObject("attributes")
      val platform = attributes.getJSONObject("platformAttributes").optJSONObject(options.platform.attribute)
        ?: throw IOException("Apple platform unavailable")
      val extended = platform.optJSONObject("iconArtwork")
      val artwork = extended ?: platform.optJSONObject("artwork") ?: throw IOException("Apple artwork unavailable")
      val template = artwork.getString("url")
      AppleIconPolicy.rendition(template, options, false)
      return AppleArtwork(app.copy(name = attributes.optString("name", app.name)), template, now,
        fallback = extended == null)
    }

    internal fun parseStandardArtwork(body: ByteArray, app: AppleAppMatch, options: AppleIconOptions,
      now: Long): AppleArtwork {
      val results = JSONObject(String(body, Charsets.UTF_8)).getJSONArray("results")
      val entry = (0 until results.length()).map { results.getJSONObject(it) }
        .singleOrNull { it.optString("trackId") == app.id } ?: throw IOException("Apple app unavailable")
      if (options.platform == ApplePlatform.MACOS && entry.optString("kind") != "mac-software")
        throw IOException("Apple Mac app unavailable")
      if (options.platform == ApplePlatform.IPADOS) {
        val devices = entry.optJSONArray("supportedDevices") ?: throw IOException("Apple iPad app unavailable")
        if ((0 until devices.length()).none { devices.optString(it).startsWith("iPad") })
          throw IOException("Apple iPad app unavailable")
      }
      val url = entry.optString("artworkUrl512", entry.optString("artworkUrl100"))
      AppleIconPolicy.validateUrl(url, artwork = true)
      val uri = URI(url)
      require(uri.path.substringAfterLast('/').matches(Regex("[0-9]+x[0-9]+[A-Za-z0-9.-]*\\.(jpg|jpeg|png|webp)")))
      val template = url.substringBefore('?').substringBeforeLast('/') + "/{w}x{h}{c}.{f}"
      AppleIconPolicy.rendition(template, options, false)
      return AppleArtwork(app.copy(name = entry.optString("trackName", app.name),
        publisher = entry.optString("artistName", app.publisher)), template, now, fallback = true, limited = true)
    }
  }
}
