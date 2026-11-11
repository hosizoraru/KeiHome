package os.kei.hyper.home.iconpack.perfect

import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.TrustManager

/** Per-operation connections: cancellation never changes JGit's global HTTP factory. */
internal class PerfectIconGitHttp(
  private val budget: Long,
  private val check: () -> Unit,
  private val onBytes: (Long) -> Unit,
) : HttpConnectionFactory {
  private val cancelled = AtomicBoolean()
  private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
  private var received = 0L

  fun abort() {
    cancelled.set(true)
    // Some platform disconnect implementations wait for a read lock. Never run them
    // on the cancelling coroutine's thread.
    connections.forEach { connection ->
      PerfectGitWorkers.execute { connection.disconnect() }
    }
  }

  override fun create(url: URL): HttpConnection = open(url, null)

  override fun create(url: URL, proxy: Proxy): HttpConnection = open(url, proxy)

  private fun open(url: URL, proxy: Proxy?): HttpConnection {
    check()
    if (cancelled.get()) throw IOException("Git request cancelled")
    if (url.protocol != "https" || url.host != "github.com" || url.port !in listOf(-1, 443) ||
      url.userInfo != null ||
      !url.path.startsWith("/pzcn/Perfect-Icons-Completion-Project.git/")) {
      throw IOException("Unexpected Git endpoint")
    }
    // Honor JGit's supplied proxy, or the platform ProxySelector for the default
    // overload. No Git proxy config or credential provider is loaded here.
    val connection = (if (proxy == null) url.openConnection() else url.openConnection(proxy)) as HttpsURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 20_000
    connection.instanceFollowRedirects = false
    connection.useCaches = false
    connections.add(connection)
    if (cancelled.get()) {
      connection.disconnect()
      throw IOException("Git request cancelled")
    }
    return Connection(connection)
  }

  private inner class Connection(private val c: HttpsURLConnection) : HttpConnection {
    override fun getResponseCode(): Int {
      check()
      return c.responseCode.also {
        if (it == 429 || it == 403 && (c.getHeaderField("Retry-After") != null ||
            c.getHeaderField("X-RateLimit-Remaining") == "0")) {
          val headers = listOf("Retry-After", "X-RateLimit-Reset").mapNotNull { name ->
            c.getHeaderField(name)?.let { value -> name to value }
          }.toMap()
          throw PerfectIconCooldownException(PerfectIconHttpResponse(it, headers).cooldown(System.currentTimeMillis()))
        }
        if (it in 300..399 || it == 401 || it == 407) throw IOException("Anonymous Git HTTP status $it")
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
        key.equals("Proxy-Authorization", true)) throw IOException("Git authentication is disabled")
      c.setRequestProperty(key, value)
    }
    override fun setRequestMethod(method: String) { c.requestMethod = method }
    override fun setUseCaches(usecaches: Boolean) { c.useCaches = false }
    override fun setConnectTimeout(timeout: Int) { c.connectTimeout = timeout.coerceIn(1, 15_000) }
    override fun setReadTimeout(timeout: Int) { c.readTimeout = timeout.coerceIn(1, 20_000) }
    override fun getInputStream(): InputStream {
      check()
      return object : FilterInputStream(c.inputStream) {
        override fun read(): Int {
          val one = ByteArray(1)
          return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
          check()
          if (cancelled.get()) throw IOException("Git request cancelled")
          // Read at most one byte beyond the remaining budget to detect overflow.
          val count = super.read(bytes, off, minOf(len.toLong(), budget - received + 1, 64L * 1024).toInt())
          if (count > 0) {
            received += count
            if (received > budget) throw IOException("Git transfer exceeds byte limit")
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
          try { super.close() } finally {
            connections.remove(c)
            c.disconnect()
          }
        }
      }
    }
    override fun setInstanceFollowRedirects(followRedirects: Boolean) { c.instanceFollowRedirects = false }
    override fun setDoOutput(dooutput: Boolean) { c.doOutput = dooutput }
    override fun setFixedLengthStreamingMode(length: Int) { c.setFixedLengthStreamingMode(length) }
    override fun getOutputStream() = c.outputStream
    override fun setChunkedStreamingMode(chunklen: Int) { c.setChunkedStreamingMode(chunklen) }
    override fun getRequestMethod(): String = c.requestMethod
    override fun usingProxy(): Boolean = c.usingProxy()
    override fun connect() { check(); c.connect() }
    override fun configure(km: Array<KeyManager>?, tm: Array<TrustManager>?, random: SecureRandom?) {
      // Keep platform certificate verification; never accept JGit's trust-all configuration.
      if (km != null || tm != null) throw IOException("Custom Git TLS trust is disabled")
    }
    override fun setHostnameVerifier(hostnameverifier: HostnameVerifier) {
      throw IOException("Custom Git hostname verification is disabled")
    }
  }
}
