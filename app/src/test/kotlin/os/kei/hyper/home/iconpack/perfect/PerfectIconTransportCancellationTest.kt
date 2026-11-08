package os.kei.hyper.home.iconpack.perfect

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerfectIconTransportCancellationTest {
  @get:Rule val temporary = TemporaryFolder()

  private class StalledConnection(private val stallHeaders: Boolean) :
    HttpURLConnection(URL(PerfectIconUrls.SOURCE)) {
    val entered = CompletableDeferred<Unit>()
    val disconnected = CountDownLatch(1)

    override fun connect() = Unit
    override fun usingProxy() = false
    override fun disconnect() { disconnected.countDown() }
    override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
    override fun getContentLengthLong() = -1L
    override fun getResponseCode(): Int {
      if (stallHeaders) stall()
      return 200
    }
    override fun getInputStream(): InputStream = object : InputStream() {
      override fun read(): Int = stall()
    }

    private fun stall(): Nothing {
      entered.complete(Unit)
      check(disconnected.await(3, TimeUnit.SECONDS)) { "Cancellation did not close the connection" }
      throw IOException("Socket closed")
    }
  }

  private fun assertCancellation(stallHeaders: Boolean, dispatcher: CoroutineDispatcher = Dispatchers.IO) = runBlocking {
    val connection = StalledConnection(stallHeaders)
    val transport = PerfectIconUrlConnection(ioDispatcher = dispatcher, openConnection = { connection })
    val task = async {
      transport.download(PerfectIconUrls.SOURCE, temporary.newFile(), 1024, emptyMap()) {}
    }
    withTimeout(2_000) { connection.entered.await() }
    withTimeout(2_000) { task.cancelAndJoin() }
    assertTrue(task.isCancelled)
    assertEquals(0L, connection.disconnected.count)
  }

  @Test fun cancellingStalledResponseHeadersClosesConnection() = assertCancellation(true)

  @Test fun cancellingStalledBodyClosesConnection() = assertCancellation(false)

  @Test fun cancellationDoesNotWaitForTheSingleBlockedHeaderWorker() {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { assertCancellation(true, it) }
  }

  @Test fun cancellationDoesNotWaitForTheSingleBlockedBodyWorker() {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { assertCancellation(false, it) }
  }
}
