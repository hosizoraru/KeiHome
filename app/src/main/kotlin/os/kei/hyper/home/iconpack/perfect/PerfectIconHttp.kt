package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PerfectIconHttpResponse(
  val status: Int,
  val headers: Map<String, String> = emptyMap(),
) {
  fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}

/** The transport streams directly to a private temporary file; it never buffers an archive. */
internal fun interface PerfectIconTransport {
  suspend fun download(
    url: String,
    destination: File,
    limit: Long,
    headers: Map<String, String>,
    progress: (PerfectIconProgress) -> Unit,
  ): PerfectIconHttpResponse
}

internal object PerfectIconUrls {
  const val REPO = "pzcn/Perfect-Icons-Completion-Project"
  const val SOURCE = "https://codeload.github.com/$REPO/zip/refs/heads/main"
  fun check(value: String) {
    require(value == SOURCE) { "Only the fixed Perfect Icons main codeload URL is allowed" }
  }
}

internal class PerfectIconUrlConnection(
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val cancellationDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
  private val openConnection: (String) -> HttpURLConnection = {
    URL(it).openConnection() as HttpURLConnection
  },
) : PerfectIconTransport {
  override suspend fun download(
    url: String, destination: File, limit: Long, headers: Map<String, String>,
    progress: (PerfectIconProgress) -> Unit,
  ): PerfectIconHttpResponse = withContext(ioDispatcher) { coroutineScope {
    PerfectIconUrls.check(url)
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    val connection = openConnection(url)
    // Closing the socket also interrupts response-header acquisition and a stalled read.
    val cancellation = launch(cancellationDispatcher, start = CoroutineStart.UNDISPATCHED) {
      try { awaitCancellation() } finally { connection.disconnect() }
    }
    try {
      connection.instanceFollowRedirects = false
      connection.connectTimeout = 15_000
      connection.readTimeout = 30_000
      connection.useCaches = false
      connection.setRequestProperty("User-Agent", "KeiHome-PerfectIcons")
      connection.setRequestProperty("Accept", "application/zip")
      connection.setRequestProperty("Accept-Encoding", "identity")
      headers.forEach { (name, value) ->
        require(name == "If-None-Match" && value.length <= 256 && '\r' !in value && '\n' !in value)
        connection.setRequestProperty(name, value)
      }
      val status = connection.responseCode
      val responseHeaders = connection.headerFields.filterKeys { it != null }
        .mapValues { it.value.firstOrNull().orEmpty() }
      if (status != 200) return@coroutineScope PerfectIconHttpResponse(status, responseHeaders)
      if (connection.contentLengthLong > limit) throw IOException("Perfect Icons ZIP exceeds $limit bytes")
      val expected = connection.contentLengthLong.coerceAtLeast(0)
      val reporter = PerfectIconProgressReporter("source.download", progress)
      reporter.update(0, expected, force = true)
      var total = 0L
      connection.inputStream.use { input ->
        FileOutputStream(destination).use { output ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            coroutine.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Perfect Icons ZIP exceeds $limit bytes")
            output.write(buffer, 0, count)
            reporter.update(total, expected)
          }
          coroutine.ensureActive()
          output.fd.sync()
        }
      }
      if (connection.contentLengthLong >= 0 && connection.contentLengthLong != total) {
        throw IOException("Interrupted Perfect Icons ZIP response")
      }
      reporter.update(total, expected, force = true)
      PerfectIconHttpResponse(status, responseHeaders)
    } catch (failure: IOException) {
      coroutine.ensureActive()
      throw failure
    } finally {
      cancellation.cancel()
      connection.disconnect()
    }
  } }
}

/** Monotonic time keeps wall-clock changes from delaying UI updates. Unknown totals stay zero. */
internal class PerfectIconProgressReporter(
  private val label: String,
  private val progress: (PerfectIconProgress) -> Unit,
  private val nanos: () -> Long = System::nanoTime,
) {
  private var last: Long? = null

  fun update(done: Long, total: Long, force: Boolean = false) {
    val time = nanos()
    if (force || last == null || time - requireNotNull(last) >= 250_000_000) {
      last = time
      progress(PerfectIconProgress(done.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        total.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), label))
    }
  }
}

internal fun PerfectIconHttpResponse.cooldown(now: Long): Long {
  fun date(value: String): Long? = runCatching {
    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("GMT")
      isLenient = false
    }.parse(value)?.time
  }.getOrNull()
  val after = header("Retry-After")?.let { value ->
    value.toLongOrNull()?.takeIf { it in 0..604800 }?.let { now + it * 1000 } ?: date(value)
  } ?: 0
  val reset = header("X-RateLimit-Reset")?.toLongOrNull()
    ?.takeIf { it in 1..(Long.MAX_VALUE / 1000) }?.times(1000) ?: 0
  return maxOf(now + if (status == 403) 3_600_000 else 60_000, after, reset)
    .coerceAtMost(now + 604_800_000)
}
