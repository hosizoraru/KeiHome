package os.kei.hyper.home.iconpack.apple

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
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

private const val MAX_BACKOFF_MILLIS = 24 * 60 * 60 * 1_000L

internal class AppleRequestException(val status: Int, val retryAtMillis: Long = 0L) :
  IOException("Apple HTTP $status")

internal fun appleRetryAfterMillis(header: String?, now: Long): Long {
  val value = header?.trim()?.takeIf { it.length <= 128 } ?: return 0L
  val seconds = value.toLongOrNull()
  val deadline = if (seconds != null) {
    if (seconds < 0) return 0L
    now + seconds.coerceAtMost(MAX_BACKOFF_MILLIS / 1_000) * 1_000
  } else try {
    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
  } catch (_: Exception) { return 0L }
  return deadline.coerceIn(now, now + MAX_BACKOFF_MILLIS)
}

internal fun interface AppleIconTransport {
  suspend fun get(url: String): ByteArray
}

/** One bounded request at a time; cancellation also disconnects blocking HTTP reads. */
internal class AppleHttpTransport(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : AppleIconTransport {
  override suspend fun get(url: String): ByteArray {
    try { return withTimeout(30_000) { fetch(url) }
    } catch (failure: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      throw IOException("Apple request timed out", failure)
    }
  }

  private suspend fun fetch(url: String): ByteArray = withContext(ioDispatcher) {
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
        val closer = launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
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
            if (code != 200) throw AppleRequestException(code,
              if (code == 429 || code == 503)
                appleRetryAfterMillis(connection.getHeaderField("Retry-After"), System.currentTimeMillis()) else 0L)
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
  private val metadataIntervalMillis: Long = 3_500,
  private val clockMillis: () -> Long = System::currentTimeMillis) {
  init { require(metadataIntervalMillis in 0..60_000) }
  private data class Backoff(val retryAt: Long, val strikes: Int, val status: Int)
  private val requests = Mutex()
  private val nextMetadataAt = mutableMapOf<String, Long>()
  private val stateLock = Any()
  private val backoffs = linkedMapOf<String, Backoff>()
  private var onBackoffChanged: ((String) -> Unit)? = null

  fun backoffSnapshot(): String = synchronized(stateLock) { snapshotLocked() }

  fun setOnBackoffChanged(callback: (String) -> Unit) {
    synchronized(stateLock) { onBackoffChanged = callback }
  }

  fun retryAtMillis(): Long = synchronized(stateLock) {
    backoffs.values.maxOfOrNull { it.retryAt.takeIf { time -> time > clockMillis() } ?: 0L } ?: 0L
  }

  fun restoreBackoff(snapshot: String?) {
    if (snapshot == null || snapshot.length !in 1..8_192) return
    val restored = try {
      val root = JSONObject(snapshot)
      if (root.optInt("version") != 1) return
      val hosts = root.getJSONObject("hosts")
      if (hosts.length() > 16) return
      val now = clockMillis()
      buildMap<String, Backoff> {
        for (host in hosts.keys()) {
          if (!allowedHost(host)) continue
          val value = hosts.optJSONObject(host) ?: continue
          val time = value.opt("retryAtMillis") as? Number ?: continue
          val strikes = value.opt("strikes") as? Number ?: continue
          val status = value.opt("status") as? Number ?: continue
          if (time.toDouble() != time.toLong().toDouble() ||
            strikes.toDouble() != strikes.toInt().toDouble() ||
            status.toDouble() != status.toInt().toDouble()) continue
          if (time.toLong() !in (now - MAX_BACKOFF_MILLIS)..(now + MAX_BACKOFF_MILLIS) ||
            strikes.toInt() !in 1..7 || status.toInt() !in listOf(429, 503)) continue
          put(host, Backoff(time.toLong(), strikes.toInt(), status.toInt()))
        }
      }
    } catch (_: Exception) { return }
    synchronized(stateLock) {
      for ((host, state) in restored) {
        val current = backoffs[host]
        if (current == null || state.retryAt > current.retryAt) backoffs[host] = state
      }
    }
    notifyBackoffChanged()
  }

  private fun snapshotLocked(): String {
    val hosts = JSONObject()
    val now = clockMillis()
    val persisted = backoffs.entries.filter { (host, state) ->
      allowedHost(host) && state.retryAt in (now - MAX_BACKOFF_MILLIS)..(now + MAX_BACKOFF_MILLIS)
    }.sortedWith(compareBy<Map.Entry<String, Backoff>> {
      if (it.key == "apps.apple.com" || it.key == "itunes.apple.com") 0 else 1
    }.thenByDescending { it.value.retryAt }).take(16)
    for ((host, state) in persisted) hosts.put(host, JSONObject()
      .put("retryAtMillis", state.retryAt).put("strikes", state.strikes).put("status", state.status))
    return JSONObject().put("version", 1).put("hosts", hosts).toString()
  }

  private fun notifyBackoffChanged() {
    val (callback, snapshot) = synchronized(stateLock) { onBackoffChanged to snapshotLocked() }
    try { callback?.invoke(snapshot)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { /* Persistence failure must not hide the typed HTTP failure. */ }
  }

  suspend fun search(term: String, options: AppleIconOptions, allowIdLookup: Boolean = true): List<AppleAppMatch> {
    require(term.trim().length in 1..100)
    val direct = allowIdLookup && term.trim().matches(Regex("[0-9]{6,12}"))
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
    return artworks(listOf(app), options)[app.id] ?: throw IOException("Apple artwork unavailable")
  }

  /** Plain app-ID keys; callers must keep effective storefront/platform together. */
  suspend fun artworks(apps: List<AppleAppMatch>, options: AppleIconOptions): Map<String, AppleArtwork> {
    val result = linkedMapOf<String, AppleArtwork>()
    var lastFailure: Exception? = null
    val publicApps = apps.filter { it.id.matches(Regex("[0-9]{6,12}")) }
    require(publicApps.map { options.forApp(it).country }.distinct().size <= 1) {
      "Apple artwork batches must use one effective storefront"
    }
    val groups = publicApps.distinctBy { it.id }.groupBy { options.forApp(it).country }
    val additional = if (options.platform == ApplePlatform.MACOS) "mac" else "iphone"
    for ((country, countryApps) in groups) for (batch in countryApps.chunked(25)) {
      currentCoroutineContext().ensureActive()
      val effective = options.copy(country = country)
      try {
        val body = metadataRequest("https://apps.apple.com/api/apps/v1/catalog/$country/apps?" +
          query(mapOf("ids" to batch.joinToString(",") { it.id }, "platform" to "web",
            "additionalPlatforms" to additional, "extend" to "iconArtwork",
            "fields[apps]" to "name,bundleId,artwork,iconArtwork", "l" to "en-US")))
        for (app in batch) {
          currentCoroutineContext().ensureActive()
          try { result[app.id] = parseArtwork(body, app, effective, clockMillis())
          } catch (cancelled: CancellationException) { throw cancelled
          } catch (failure: Exception) { lastFailure = failure }
        }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { lastFailure = failure }
      val missing = batch.filter { it.id !in result }
      if (missing.isEmpty()) continue
      try {
        val body = metadataRequest("https://itunes.apple.com/lookup?" +
          query(mapOf("id" to missing.joinToString(",") { it.id }, "country" to country,
            "entity" to options.platform.entity)))
        for (app in missing) {
          currentCoroutineContext().ensureActive()
          try { result[app.id] = parseStandardArtwork(body, app, effective, clockMillis())
          } catch (cancelled: CancellationException) { throw cancelled
          } catch (failure: Exception) {
            if (lastFailure !is AppleRequestException) lastFailure = failure
          }
        }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) {
        if (failure is AppleRequestException || lastFailure !is AppleRequestException) lastFailure = failure
      }
    }
    if (result.isEmpty() && lastFailure != null) throw lastFailure
    return result
  }

  suspend fun image(url: String): ByteArray {
    AppleIconPolicy.validateUrl(url, artwork = true)
    return request(url, paced = false)
  }

  private suspend fun metadataRequest(url: String): ByteArray = request(url, paced = true)

  private suspend fun request(url: String, paced: Boolean): ByteArray = requests.withLock {
    AppleIconPolicy.validateUrl(url)
    val host = URI(url).host
    fun checkBlocked() {
      val blocked = synchronized(stateLock) { backoffs[host]?.takeIf { it.retryAt > clockMillis() } }
      if (blocked != null) throw AppleRequestException(blocked.status, blocked.retryAt)
    }
    checkBlocked()
    if (paced) delay(((nextMetadataAt[host] ?: 0L) - System.nanoTime() / 1_000_000).coerceAtLeast(0))
    checkBlocked()
    try {
      val body = transport.get(url)
      val changed = synchronized(stateLock) { backoffs.remove(host) != null }
      if (changed) notifyBackoffChanged()
      body
    } catch (failure: AppleRequestException) {
      if (failure.status == 429 || failure.status == 503) {
        val deadline = synchronized(stateLock) {
          val now = clockMillis()
          val previous = backoffs[host]?.takeIf { it.retryAt >= now - MAX_BACKOFF_MILLIS }
          val strikes = ((previous?.strikes ?: 0) + 1).coerceAtMost(7)
          val exponential = (60_000L shl (strikes - 1)).coerceAtMost(60 * 60 * 1_000L)
          val retryAt = maxOf(now + exponential, failure.retryAtMillis)
            .coerceAtMost(now + MAX_BACKOFF_MILLIS)
          if (host !in backoffs && backoffs.size >= 16) {
            backoffs.entries.firstOrNull { it.value.retryAt <= now }?.let { backoffs.remove(it.key) }
          }
          backoffs[host] = Backoff(retryAt, strikes, failure.status)
          retryAt
        }
        notifyBackoffChanged()
        throw AppleRequestException(failure.status, deadline)
      }
      throw failure
    } finally {
      if (paced) nextMetadataAt[host] = System.nanoTime() / 1_000_000 + metadataIntervalMillis
    }
  }

  companion object {
    private fun allowedHost(host: String): Boolean =
      host in listOf("apps.apple.com", "itunes.apple.com") ||
        host.matches(Regex("is[0-9]{1,2}-ssl\\.mzstatic\\.com"))
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
        fallback = extended == null, limited = extended == null)
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
