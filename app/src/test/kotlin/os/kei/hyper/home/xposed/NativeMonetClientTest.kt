package os.kei.hyper.home.xposed

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.InterruptedIOException
import java.lang.reflect.InvocationTargetException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMonetClientTest {
  @Test
  fun publishUsesLittleEndianArgbAndVariableLengthAsciiWithoutPadding() {
    val request = NativeMonetProtocol.publishRequest(
      foreground, background, linkedSetOf("com.long", "a"),
    )
    assertArrayEquals(
      hex(
        "4b484d33 01000000 01000000 5d0021ff ffddeaff 02000000 " +
          "01000000 61 08000000 636f6d2e6c6f6e67",
      ),
      request,
    )
  }

  @Test
  fun disableUsesFlagsZeroAndNoColorsOrEntries() {
    assertArrayEquals(
      hex("4b484d33 01000000 00000000 00000000 00000000 00000000"),
      NativeMonetProtocol.disableRequest(),
    )
  }

  @Test
  fun publishRejectsInvalidColorsAndNeverTruncatesOversizedOrNonAsciiDenySets() {
    assertNull(NativeMonetProtocol.publishRequest(0x0021005d, background, emptySet()))
    assertNull(NativeMonetProtocol.publishRequest(foreground, 0x80eaddff.toInt(), emptySet()))
    assertNull(NativeMonetProtocol.publishRequest(background, background, emptySet()))
    val maximum = (1..512).map { "com.example.p$it" }.toSet()
    assertNotNull(NativeMonetProtocol.publishRequest(foreground, background, maximum))
    assertNull(NativeMonetProtocol.publishRequest(foreground, background, maximum + "extra"))
    assertNotNull(NativeMonetProtocol.publishRequest(foreground, background, setOf("a".repeat(255))))
    for (invalid in listOf("", "a".repeat(256), "com.中文", "com.é", "com.\u0000app",
      "com.\napp", "com. app", "com.\u007fapp", "com..app", "com.", ".com", "1com.app",
      "com.1app", "com._app", "com.app/name")) {
      assertNull(invalid, NativeMonetProtocol.publishRequest(foreground, background, setOf(invalid)))
    }
  }

  @Test
  fun readinessAuthenticatesGreetingAndClosesWithoutRequestOrAck() {
    val output = ByteArrayOutputStream()
    val input = ByteArrayInputStream(greeting())
    assertTrue(exchange(input, output, request = null))
    assertEquals(0, output.size())
    assertEquals(0, input.available())
  }

  @Test
  fun wrongPeerUidIsRejectedBeforeReadingOrWriting() {
    val input = ByteArrayInputStream(greeting())
    val output = ByteArrayOutputStream()
    assertFalse(exchange(input, output, peerUid = targetUid + 100_000))
    assertEquals(16, input.available())
    assertEquals(0, output.size())
  }

  @Test
  fun greetingMustMatchMagicVersionFullUidAndProfileBeforeAnyRequest() {
    val invalidGreetings = listOf(
      words(0x334d484a, 1, targetUid, 1),
      words(magic, 2, targetUid, 1),
      words(magic, 1, targetUid + 100_000, 1),
      words(magic, 1, targetUid, 0),
      words(magic, 1, targetUid, 2),
      words(magic, 1, -1, 1),
      ByteBuffer.allocate(16).putInt(magic).putInt(1).putInt(targetUid).putInt(1).array(),
    )
    for (greeting in invalidGreetings) {
      val output = ByteArrayOutputStream()
      assertFalse(exchange(ByteArrayInputStream(greeting + ack()), output))
      assertEquals(0, output.size())
    }
  }

  @Test
  fun userSwitchBetweenConnectionAndGreetingPreventsRequest() {
    val output = ByteArrayOutputStream()
    assertFalse(NativeMonetProtocol.exchange(
      ByteArrayInputStream(greeting()), output, targetUid, targetUid, request(),
      isCurrentTarget = { false },
    ))
    assertEquals(0, output.size())
  }

  @Test
  fun fragmentedGreetingAndAckAreFullyReadAndRequestIsWrittenExactlyOnce() {
    val output = ByteArrayOutputStream()
    val response = greeting() + ack()
    val input = object : ByteArrayInputStream(response) {
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (pos < 16) assertEquals(0, output.size())
        else assertArrayEquals(request(), output.toByteArray())
        return super.read(bytes, offset, minOf(1, length))
      }
    }
    var ioChecks = 0
    assertTrue(NativeMonetProtocol.exchange(
      input, output, targetUid, targetUid, request(), beforeIo = { ioChecks++ },
    ))
    assertArrayEquals(request(), output.toByteArray())
    // One deadline check per partial byte read, plus write and flush.
    assertEquals(16 + 12 + 2, ioChecks)
    assertEquals(0, input.available())
  }

  @Test
  fun aRequestIsSuccessfulOnlyAfterExactSuccessAckIncludingDisable() {
    for (invalidAck in listOf(
      words(magic, 1, 1), words(magic, 1, -1), words(magic, 2, 0), words(0, 1, 0),
    )) {
      val output = ByteArrayOutputStream()
      assertFalse(exchange(ByteArrayInputStream(greeting() + invalidAck), output))
      assertArrayEquals(request(), output.toByteArray())
    }
    val output = ByteArrayOutputStream()
    assertTrue(exchange(
      ByteArrayInputStream(greeting() + ack()), output, request = NativeMonetProtocol.disableRequest(),
    ))
    assertArrayEquals(NativeMonetProtocol.disableRequest(), output.toByteArray())
  }

  @Test
  fun rejectionDiagnosticsIdentifyTheFailedGreetingFieldWithoutPublishing() {
    for ((frame, expected) in listOf(
      words(0, 1, targetUid, 1) to "magic",
      words(magic, 2, targetUid, 1) to "protocol",
      words(magic, 1, targetUid + 100_000, 1) to "UID",
      words(magic, 1, targetUid, 2) to "profile",
    )) {
      val output = ByteArrayOutputStream()
      val failures = mutableListOf<String>()
      assertFalse(NativeMonetProtocol.exchange(
        ByteArrayInputStream(frame + ack()), output, targetUid, targetUid, request(),
        onFailure = failures::add,
      ))
      assertEquals(0, output.size())
      assertEquals(1, failures.size)
      assertTrue(failures.single(), failures.single().contains(expected))
    }
  }

  @Test
  fun successfulAckDoesNotAuthorizeAWriteAfterLauncherRetirement() {
    val output = ByteArrayOutputStream()
    var checks = 0
    var failure: String? = null
    assertFalse(NativeMonetProtocol.exchange(
      ByteArrayInputStream(greeting() + ack()), output, targetUid, targetUid, request(),
      isCurrentTarget = { ++checks == 1 }, onFailure = { failure = it },
    ))
    assertArrayEquals(request(), output.toByteArray())
    assertEquals(2, checks)
    assertEquals("launcher changed after policy ACK", failure)
  }

  @Test
  fun nativePolicyDenialIsNotConfusedWithInvalidAckOrSuccessfulPublication() {
    for ((reply, expected) in listOf(
      words(magic, 1, 1) to "native policy rejected: status=1",
      words(magic, 1, -1) to "native policy rejected: status=-1",
      words(magic, 2, 0) to "native policy ACK identity mismatch",
    )) {
      var failure: String? = null
      val output = ByteArrayOutputStream()
      assertFalse(NativeMonetProtocol.exchange(
        ByteArrayInputStream(greeting() + reply), output, targetUid, targetUid, request(),
        onFailure = { failure = it },
      ))
      assertEquals(expected, failure)
      assertArrayEquals(request(), output.toByteArray())
    }
  }

  @Test
  fun failureDiagnosticsUnwrapHiddenApiExceptionsWithoutLeakingCredentialsOrNewlines() {
    val cause = SecurityException("Permission denied\nAuthorization: Bearer do-not-print; token=private-value")
    val message = nativeMonetFailure("broker identity", ExecutionException(InvocationTargetException(cause)))
    assertTrue(message.startsWith("broker identity: SecurityException: Permission denied"))
    assertFalse(message.contains("do-not-print"))
    assertFalse(message.contains("private-value"))
    assertFalse(message.contains('\n'))
    assertTrue(message.contains("[redacted]"))
  }

  @Test
  fun truncatedGreetingNeverPublishesAndTruncatedAckNeverSucceeds() {
    for (length in 0 until 16) {
      val output = ByteArrayOutputStream()
      assertThrows(EOFException::class.java) {
        exchange(ByteArrayInputStream(greeting().copyOf(length)), output)
      }
      assertEquals(0, output.size())
    }
    for (length in 0 until 12) {
      val output = ByteArrayOutputStream()
      assertThrows(EOFException::class.java) {
        exchange(ByteArrayInputStream(greeting() + ack().copyOf(length)), output)
      }
      assertArrayEquals(request(), output.toByteArray())
    }
  }

  @Test
  fun readTimeoutPropagatesWithoutRetryOrRequest() {
    var reads = 0
    val input = object : InputStream() {
      override fun read(): Int {
        reads++
        throw SocketTimeoutException()
      }
    }
    val output = ByteArrayOutputStream()
    assertThrows(SocketTimeoutException::class.java) { exchange(input, output) }
    assertEquals(1, reads)
    assertEquals(0, output.size())
  }

  @Test
  fun cancellationAfterGreetingPreventsRequest() {
    var checks = 0
    val output = ByteArrayOutputStream()
    assertThrows(InterruptedIOException::class.java) {
      NativeMonetProtocol.exchange(
        ByteArrayInputStream(greeting()), output, targetUid, targetUid, request(),
        beforeIo = { if (++checks == 2) throw InterruptedIOException() },
      )
    }
    assertEquals(0, output.size())
  }

  @Test
  fun stopBeforeFirstExchangeIsIdempotentAndExplicitStartReopens() {
    val worker = NativeMonetWorker()
    assertFalse(worker.awaitStopped(System.nanoTime()))
    worker.stop()
    worker.stop()
    assertTrue(worker.awaitStopped(System.nanoTime()))
    assertFalse(worker.submit(FutureTask { true }) {})
    assertTrue(worker.start())
    val task = FutureTask { true }
    try {
      assertTrue(worker.submit(task) {})
      assertTrue(task.get(1, TimeUnit.SECONDS))
      worker.release(task)
    } finally {
      worker.stop()
      assertTrue(worker.awaitStopped(deadline()))
    }
  }

  @Test
  fun stopClosesInFlightAndQueuedExchangesAndJoinsTheirWorker() {
    val worker = NativeMonetWorker()
    val entered = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val workerThread = AtomicReference<Thread>()
    val queuedRuns = AtomicInteger()
    val queuedCloses = AtomicInteger()
    val running = FutureTask {
      workerThread.set(Thread.currentThread())
      entered.countDown()
      awaitUninterruptibly(closed)
      true
    }
    val queued = FutureTask { queuedRuns.incrementAndGet(); true }
    try {
      assertTrue(worker.submit(running) { closed.countDown() })
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      assertTrue(worker.submit(queued) { queuedCloses.incrementAndGet() })
      worker.stop()
      assertEquals(0L, closed.count)
      assertEquals(1, queuedCloses.get())
      assertTrue(worker.awaitStopped(deadline()))
      assertFalse(workerThread.get().isAlive)
      assertTrue(queued.isCancelled)
      assertEquals(0, queuedRuns.get())
    } finally {
      closed.countDown()
      worker.stop()
      assertTrue(worker.awaitStopped(deadline()))
    }
  }

  @Test
  fun cancelledFutureDoesNotPermitRestartWhileOldWorkerIgnoresInterruption() {
    val worker = NativeMonetWorker()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val oldThread = AtomicReference<Thread>()
    val closes = AtomicInteger()
    val blocked = FutureTask {
      oldThread.set(Thread.currentThread())
      entered.countDown()
      awaitUninterruptibly(release)
      true
    }
    try {
      assertTrue(worker.submit(blocked) { closes.incrementAndGet() })
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      worker.stop()
      assertTrue(blocked.isCancelled)
      assertEquals(1, closes.get())
      assertFalse(worker.awaitStopped(deadline(20)))
      assertFalse(worker.start())
      assertFalse(worker.submit(FutureTask { true }) {})

      release.countDown()
      assertTrue(worker.awaitStopped(deadline()))
      assertFalse(oldThread.get().isAlive)
      assertTrue(worker.start())
      val next = FutureTask { true }
      assertTrue(worker.submit(next) {})
      assertTrue(next.get(1, TimeUnit.SECONDS))
      worker.release(next)
    } finally {
      release.countDown()
      worker.stop()
      assertTrue(worker.awaitStopped(deadline()))
    }
  }

  @Test
  fun workersAreOwnedByEachClientLifecycle() {
    val retired = NativeMonetWorker()
    val active = NativeMonetWorker()
    retired.stop()
    assertTrue(retired.awaitStopped(System.nanoTime()))
    val task = FutureTask { true }
    try {
      assertTrue(active.submit(task) {})
      assertTrue(task.get(1, TimeUnit.SECONDS))
      active.release(task)
    } finally {
      active.stop()
      assertTrue(active.awaitStopped(deadline()))
    }
  }

  private fun exchange(
    input: InputStream,
    output: ByteArrayOutputStream,
    peerUid: Int = targetUid,
    request: ByteArray? = request(),
  ): Boolean = NativeMonetProtocol.exchange(input, output, targetUid, peerUid, request)

  private fun request(): ByteArray =
    requireNotNull(NativeMonetProtocol.publishRequest(foreground, background, setOf("com.example")))

  private fun greeting(): ByteArray = words(magic, 1, targetUid, 1)

  private fun ack(): ByteArray = words(magic, 1, 0)

  private fun words(vararg values: Int): ByteArray =
    ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
      values.forEach { putInt(it) }
    }.array()

  private fun hex(value: String): ByteArray =
    value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  private fun deadline(timeoutMs: Long = 1_000): Long =
    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

  private fun awaitUninterruptibly(latch: CountDownLatch) {
    var interrupted = false
    while (true) {
      try {
        latch.await()
        break
      } catch (_: InterruptedException) {
        interrupted = true
      }
    }
    if (interrupted) Thread.currentThread().interrupt()
  }

  private companion object {
    const val magic = 0x334d484b
    const val targetUid = 1_010_123
    val foreground = 0xff21005d.toInt()
    val background = 0xffeaddff.toInt()
  }
}
