package os.kei.hyper.home.iconpack.perfect

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

class PerfectIconGitHttpTest {
  private class FakeConnection(
    private val blockHeaders: Boolean = false,
    private val blockBody: Boolean = false,
    private val status: Int = 200,
    private val headers: Map<String, String> = emptyMap(),
  ) : HttpsURLConnection(URL("https://github.com/pzcn/Perfect-Icons-Completion-Project.git/info/refs")) {
    val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    @Volatile var aborted = false
    var proxied = false
    private fun waitForAbort() {
      entered.countDown()
      if (!released.await(5, TimeUnit.SECONDS)) throw IOException("Fake connection timed out")
      throw IOException("Fake connection disconnected")
    }
    override fun getResponseCode(): Int { if (blockHeaders) waitForAbort(); return status }
    override fun getResponseMessage(): String = "Fixture"
    override fun getHeaderField(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    override fun getInputStream(): InputStream = if (blockBody) object : InputStream() {
      override fun read(): Int { waitForAbort(); return -1 }
      override fun read(bytes: ByteArray, off: Int, len: Int): Int { waitForAbort(); return -1 }
    } else ByteArrayInputStream(ByteArray(128) { 7 })
    override fun disconnect() { aborted = true; released.countDown() }
    override fun usingProxy(): Boolean = proxied
    override fun connect() = Unit
    override fun getCipherSuite(): String = "fixture"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
  }

  private fun url(connection: FakeConnection, address: String =
    "https://github.com/pzcn/Perfect-Icons-Completion-Project.git/info/refs",
    onOpen: (Proxy?) -> Unit = {},
  ): URL =
    URL(null, address, object : URLStreamHandler() {
      override fun openConnection(url: URL): URLConnection { onOpen(null); return connection }
      override fun openConnection(url: URL, proxy: Proxy): URLConnection { onOpen(proxy); return connection }
    })

  private fun abortsBlockedRead(headers: Boolean) {
    val fake = FakeConnection(blockHeaders = headers, blockBody = !headers)
    val factory = PerfectIconGitHttp(1024, {}, {})
    val connection = factory.create(url(fake))
    val executor = Executors.newSingleThreadExecutor()
    try {
      val worker = executor.submit<Int> {
        if (headers) connection.responseCode else connection.inputStream.read()
      }
      assertTrue(fake.entered.await(1, TimeUnit.SECONDS))
      factory.abort()
      try { worker.get(1, TimeUnit.SECONDS); fail("Blocked read survived disconnect") }
      catch (error: ExecutionException) { assertTrue(error.cause is IOException) }
      assertTrue(fake.aborted)
    } finally {
      factory.abort()
      executor.shutdownNow()
    }
  }

  @Test fun abortDisconnectsBlockedResponseHeaders() { abortsBlockedRead(headers = true) }
  @Test fun abortDisconnectsBlockedResponseBody() { abortsBlockedRead(headers = false) }

  @Test fun preservesExplicitProxyAndDelegatesDefaultSelectionToPlatform() {
    val factory = PerfectIconGitHttp(1024, {}, {})
    try {
      val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.invalid", 3128))
      val fake = FakeConnection().apply { proxied = true }
      var opened = false
      var observed: Proxy? = null
      val address = url(fake, onOpen = { opened = true; observed = it })
      assertTrue(factory.create(address, proxy).usingProxy())
      assertTrue(opened)
      assertEquals(proxy, observed)
      opened = false
      factory.create(address)
      assertTrue(opened)
      assertNull("Default overload bypassed the platform selector", observed)
      factory.create(address, Proxy.NO_PROXY)
      assertNull("A stale JGit DIRECT result must not suppress Android per-URL proxy selection", observed)
    } finally { factory.abort() }
  }

  @Test fun customAccelerationOnlyAcceptsItsOwnSmartGitPaths() {
    val network = PerfectGitNetworkOptions(acceleration = "https://mirror.example/").checked()
    val factory = PerfectIconGitHttp(1024, {}, network) {}
    try {
      val prefix = network.repositoryUrl()
      factory.create(url(FakeConnection(), "$prefix/info/refs"))
      factory.create(url(FakeConnection(), "$prefix/git-upload-pack"))
      for (address in listOf("$prefix/other", "$prefix/../secret",
        "https://foreign.example/${prefix.substringAfter("://")}")) {
        assertThrows(IOException::class.java) { factory.create(url(FakeConnection(), address)) }
      }
    } finally { factory.abort() }
  }

  @Test fun streamedResponseCannotExceedBudget() {
    val factory = PerfectIconGitHttp(32, {}, {})
    try {
      factory.create(url(FakeConnection())).inputStream.use {
        try { it.read(ByteArray(1024)); fail("HTTP byte budget bypassed") }
        catch (_: IOException) { }
      }
    } finally { factory.abort() }
  }

  @Test fun anonymousGitThrottlingHonorsRetryAfterWithoutCredentials() {
    val factory = PerfectIconGitHttp(1024, {}, {})
    val before = System.currentTimeMillis()
    try {
      val connection = factory.create(url(FakeConnection(status = 429, headers = mapOf("Retry-After" to "120"))))
      val failure = assertThrows(PerfectIconCooldownException::class.java) { connection.responseCode }
      assertTrue(failure.retryAt >= before + 120_000)
      assertTrue(failure.retryAt <= System.currentTimeMillis() + 120_000)
    } finally { factory.abort() }
  }

  @Test fun rejectsCredentialsForeignEndpointsRedirectsAndExcessiveTimeouts() {
    val factory = PerfectIconGitHttp(1024, {}, {})
    try {
      val fake = FakeConnection()
      val connection = factory.create(url(fake))
      listOf("Authorization", "Cookie", "Proxy-Authorization").forEach {
        try { connection.setRequestProperty(it, "secret"); fail("Credential header accepted") }
        catch (_: IOException) { }
      }
      listOf(
        "https://other.invalid/pzcn/Perfect-Icons-Completion-Project.git/info/refs",
        "http://github.com/pzcn/Perfect-Icons-Completion-Project.git/info/refs",
        "https://token@github.com/pzcn/Perfect-Icons-Completion-Project.git/info/refs",
        "https://github.com/another/repository.git/info/refs",
      ).forEach {
        try { factory.create(url(fake, it)); fail("Foreign endpoint accepted") }
        catch (_: IOException) { }
      }
      connection.setReadTimeout(120_000)
      connection.setConnectTimeout(120_000)
      connection.setInstanceFollowRedirects(true)
      assertEquals(20_000, fake.readTimeout)
      assertEquals(15_000, fake.connectTimeout)
      assertFalse(fake.instanceFollowRedirects)
      try { factory.create(url(FakeConnection(status = 302))).responseCode; fail("Redirect accepted") }
      catch (_: IOException) { }
    } finally { factory.abort() }
  }
}
