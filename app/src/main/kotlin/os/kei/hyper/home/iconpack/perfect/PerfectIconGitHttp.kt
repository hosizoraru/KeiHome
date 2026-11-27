package os.kei.hyper.home.iconpack.perfect

import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.TrustManager

/** Policy, budget, validation, or protocol violation that must never be retried. */
internal class NonRetryableGitException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal fun parseHttpCooldownMillis(
  status: Int,
  retryAfter: String?,
  rateLimitReset: String?,
  now: Long = System.currentTimeMillis(),
): Long {
  fun date(value: String): Long? = runCatching {
    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("GMT")
      isLenient = false
    }.parse(value)?.time
  }.getOrNull()
  val after = retryAfter?.trim()?.let { value ->
    value.toLongOrNull()?.takeIf { it in 0..604800 }?.let { now + it * 1000 } ?: date(value)
  } ?: 0
  val reset = rateLimitReset?.trim()?.toLongOrNull()
    ?.takeIf { it in 1..(Long.MAX_VALUE / 1000) }?.times(1000) ?: 0
  return maxOf(now + if (status == 403) 3_600_000 else 60_000, after, reset)
    .coerceAtMost(now + 604_800_000)
}

internal fun parseShortRetryAfterMillis(retryAfter: String?, now: Long = System.currentTimeMillis()): Long {
  val raw = retryAfter?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
  val seconds = raw.toLongOrNull()
  if (seconds != null) return (seconds.coerceIn(0L, 15L) * 1000L)
  val parsed = runCatching {
    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("GMT")
      isLenient = false
    }.parse(raw)?.time
  }.getOrNull() ?: return 0L
  return (parsed - now).coerceIn(0L, 15_000L)
}

/** Per-operation connections: cancellation never changes JGit's global HTTP factory. */
internal class PerfectIconGitHttp(
  private val budget: Long,
  private val check: () -> Unit,
  private val network: PerfectGitNetworkOptions,
  private val onBytes: (Long) -> Unit,
) : HttpConnectionFactory {
  constructor(budget: Long, check: () -> Unit, onBytes: (Long) -> Unit) :
    this(budget, check, PerfectGitNetworkOptions(), onBytes)

  private val endpoint = URL(network.checked().repositoryUrl().trimEnd('/') + "/")
  private val cancelled = AtomicBoolean()
  private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
  private var received = 0L
  @Volatile var lastStatus: Int = 0
    private set
  @Volatile var lastRetryAfterMillis: Long = 0L
    private set

  fun abort() {
    cancelled.set(true)
    // Some platform disconnect implementations wait for a read lock. Never run them
    // on the cancelling coroutine's thread.
    connections.forEach { connection ->
      PerfectGitWorkers.execute { connection.disconnect() }
    }
    connections.clear()
  }

  override fun create(url: URL): HttpConnection = open(url, null)

  override fun create(url: URL, proxy: Proxy): HttpConnection = open(url, proxy)

  private fun open(url: URL, proxy: Proxy?): HttpConnection {
    check()
    if (cancelled.get()) throw NonRetryableGitException("Git request cancelled")
    if (budget <= 0L) throw NonRetryableGitException("Git transfer exceeds byte limit")
    if (url.protocol != "https" || !url.host.equals(endpoint.host, true) ||
      (if (url.port == -1) 443 else url.port) != (if (endpoint.port == -1) 443 else endpoint.port) ||
      url.userInfo != null || url.path !in listOf(endpoint.path + "info/refs", endpoint.path + "git-upload-pack")) {
      throw NonRetryableGitException("Unexpected Git endpoint")
    }
    val selected = network.proxy(proxy)
    val connection = (if (selected == null) url.openConnection() else url.openConnection(selected)) as HttpsURLConnection
    connection.connectTimeout = MAX_CONNECT_TIMEOUT_MS
    connection.readTimeout = MAX_READ_TIMEOUT_MS
    connection.instanceFollowRedirects = false
    connection.useCaches = false
    connections.add(connection)
    if (cancelled.get()) {
      connections.remove(connection)
      connection.disconnect()
      throw NonRetryableGitException("Git request cancelled")
    }
    return Connection(connection)
  }

  private inner class Connection(private val c: HttpsURLConnection) : HttpConnection {
    override fun getResponseCode(): Int {
      check()
      return try {
        val code = c.responseCode
        lastStatus = code
        lastRetryAfterMillis = if (code == 503) parseShortRetryAfterMillis(c.getHeaderField("Retry-After")) else 0L
        if (code == 429 || code == 403 && (c.getHeaderField("Retry-After") != null ||
            c.getHeaderField("X-RateLimit-Remaining") == "0")) {
          throw PerfectIconCooldownException(
            parseHttpCooldownMillis(
              status = code,
              retryAfter = c.getHeaderField("Retry-After"),
              rateLimitReset = c.getHeaderField("X-RateLimit-Reset"),
            ),
          )
        }
        if (code in 300..399 || code == 401 || code == 407) {
          throw NonRetryableGitException("Anonymous Git HTTP status $code")
        }
        code
      } catch (failure: Throwable) {
        connections.remove(c)
        c.disconnect()
        throw failure
      }
    }
    override fun getResponseMessage(): String = c.responseMessage
    override fun getURL(): URL = c.url
    override fun getContentType(): String? = c.contentType
    override fun getContentLength(): Int = c.contentLength
    override fun getHeaderFields(): Map<String, List<String>> = c.headerFields
    override fun getHeaderField(name: String): String? = c.getHeaderField(name)
    override fun getHeaderFields(name: String): List<String> =
      c.headerFields.entries.firstOrNull { it.key?.equals(name, ignoreCase = true) == true }?.value.orEmpty()
    override fun setRequestProperty(key: String, value: String) {
      if (key.equals("Authorization", true) || key.equals("Cookie", true) ||
        key.equals("Proxy-Authorization", true)) throw NonRetryableGitException("Git authentication is disabled")
      c.setRequestProperty(key, value)
    }
    override fun setRequestMethod(method: String) { c.requestMethod = method }
    override fun setUseCaches(usecaches: Boolean) { c.useCaches = false }
    override fun setConnectTimeout(timeout: Int) { c.connectTimeout = timeout.coerceIn(1, MAX_CONNECT_TIMEOUT_MS) }
    override fun setReadTimeout(timeout: Int) { c.readTimeout = timeout.coerceIn(1, MAX_READ_TIMEOUT_MS) }
    override fun getInputStream(): InputStream {
      check()
      val upstream = try {
        c.inputStream
      } catch (failure: Throwable) {
        connections.remove(c)
        c.disconnect()
        throw failure
      }
      return object : FilterInputStream(upstream) {
        private var eofReached = false
        private var failed = false

        override fun read(): Int {
          val one = ByteArray(1)
          return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
          check()
          if (cancelled.get()) {
            failed = true
            throw NonRetryableGitException("Git request cancelled")
          }
          // Read at most one byte beyond the remaining budget to detect overflow.
          val remaining = budget - received + 1
          if (remaining <= 0L) {
            failed = true
            throw NonRetryableGitException("Git transfer exceeds byte limit")
          }
          val count = try {
            super.read(bytes, off, minOf(len.toLong(), remaining, 64L * 1024).toInt())
          } catch (failure: Throwable) {
            failed = true
            throw failure
          }
          if (count < 0) {
            eofReached = true
          } else if (count > 0) {
            received += count
            if (received > budget) {
              failed = true
              throw NonRetryableGitException("Git transfer exceeds byte limit")
            }
            onBytes(received)
          }
          check()
          return count
        }
        override fun skip(n: Long): Long {
          val buffer = ByteArray(minOf(n.coerceAtLeast(0), 8192L).toInt())
          var skipped = 0L
          while (skipped < n) {
            val count = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
            if (count < 0) break
            skipped += count
          }
          return skipped
        }
        override fun close() {
          try {
            super.close()
          } finally {
            connections.remove(c)
            // Keep clean EOF sockets eligible for Android's HTTP/1.1 keep-alive and HTTP/2 pool.
            if (!eofReached || failed || cancelled.get()) c.disconnect()
          }
        }
      }
    }
    override fun setInstanceFollowRedirects(followRedirects: Boolean) { c.instanceFollowRedirects = false }
    override fun setDoOutput(dooutput: Boolean) { c.doOutput = dooutput }
    override fun setFixedLengthStreamingMode(length: Int) { c.setFixedLengthStreamingMode(length) }
    override fun getOutputStream(): OutputStream {
      check()
      if (cancelled.get()) throw NonRetryableGitException("Git request cancelled")
      return try {
        c.outputStream
      } catch (failure: Throwable) {
        connections.remove(c)
        c.disconnect()
        throw failure
      }
    }
    override fun setChunkedStreamingMode(chunklen: Int) { c.setChunkedStreamingMode(chunklen) }
    override fun getRequestMethod(): String = c.requestMethod
    override fun usingProxy(): Boolean = c.usingProxy()
    override fun connect() {
      check()
      try {
        c.connect()
      } catch (failure: Throwable) {
        connections.remove(c)
        c.disconnect()
        throw failure
      }
    }
    override fun configure(km: Array<KeyManager>?, tm: Array<TrustManager>?, random: SecureRandom?) {
      // Keep platform certificate verification; never accept JGit's trust-all configuration.
      if (km != null || tm != null) throw NonRetryableGitException("Custom Git TLS trust is disabled")
    }
    override fun setHostnameVerifier(hostnameverifier: HostnameVerifier) {
      throw NonRetryableGitException("Custom Git hostname verification is disabled")
    }
  }

  companion object {
    const val MAX_CONNECT_TIMEOUT_MS = 30_000
    const val MAX_READ_TIMEOUT_MS = 60_000
  }
}
