package os.kei.hyper.home.xposed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IconProbeSessionTest {
  @Test fun oneBindingServesSequentialChunksIncludingSweepsLargerThan512Packages() {
    val transport = FakeTransport()
    val pending = mutableListOf<Int>()
    transport.onSend = { id, callbacks ->
      pending += id
      assertTrue(pending.size <= 128)
      if (id % 128 == 127 || id == 512) {
        pending.reversed().forEach { callbacks.result(it, false, LauncherMaskStatus.USABLE) }
        pending.clear()
      }
    }
    val session = session(transport, 513)
    val result = session.probe(128)
    assertEquals((0..512).toList(), transport.sent)
    assertEquals(513, result.launcherMasks.size)
    assertTrue(result.launcherMasks.values.all { it == LauncherMaskStatus.USABLE })
    assertTrue(result.packagesToRefresh.isEmpty())
    assertEquals(1, transport.binds)
    assertEquals(1, transport.closes)
    assertThrows(IllegalStateException::class.java) { session.probe(128) }
    assertEquals(1, transport.binds)
    assertEquals(1, transport.closes)

    val next = FakeTransport()
    session(next, 1).probe(128)
    assertEquals(1, next.binds)
    assertEquals(1, next.closes)
  }

  @Test fun duplicateLateAndOutOfRangeRepliesCannotRewriteOrCompleteAnotherChunk() {
    val transport = FakeTransport()
    transport.onSend = { id, callbacks ->
      if (id == 0) {
        callbacks.result(0, false, LauncherMaskStatus.UNUSABLE)
        callbacks.result(0, true, LauncherMaskStatus.USABLE)
        callbacks.result(1, false, LauncherMaskStatus.USABLE)
      } else {
        callbacks.result(0, true, LauncherMaskStatus.USABLE)
        callbacks.result(-1, false, LauncherMaskStatus.USABLE)
        callbacks.result(999, false, LauncherMaskStatus.USABLE)
      }
    }
    transport.onClose = {
      transport.callbacks.connected()
      transport.callbacks.result(1, false, LauncherMaskStatus.USABLE)
    }
    val result = session(transport, 3, resultTimeoutMs = 0).probe(1)
    transport.callbacks.result(0, true, LauncherMaskStatus.USABLE)
    assertEquals(listOf(0, 1), transport.sent)
    assertEquals(mapOf(
      "app0" to LauncherMaskStatus.UNUSABLE,
      "app1" to LauncherMaskStatus.UNKNOWN,
      "app2" to LauncherMaskStatus.UNKNOWN,
    ), result.launcherMasks)
    assertEquals(setOf("app1", "app2"), result.packagesToRefresh)
    assertEquals(1, transport.binds)
    assertEquals(1, transport.closes)
  }

  @Test fun timeoutOrDisconnectPreservesPartialResultsWithoutAdmittingAnotherChunk() {
    for (disconnect in listOf(false, true)) {
      val transport = FakeTransport()
      transport.onSend = { id, callbacks ->
        if (id == 0) callbacks.result(id, false, LauncherMaskStatus.UNUSABLE)
        else if (disconnect) {
          callbacks.disconnected()
          callbacks.connected()
          callbacks.result(id, false, LauncherMaskStatus.USABLE)
        }
      }
      val result = session(transport, 4, resultTimeoutMs = 0).probe(2)
      assertEquals(listOf(0, 1), transport.sent)
      assertEquals(LauncherMaskStatus.UNUSABLE, result.launcherMasks["app0"])
      assertTrue((1..3).all { result.launcherMasks["app$it"] == LauncherMaskStatus.UNKNOWN })
      assertEquals(setOf("app1", "app2", "app3"), result.packagesToRefresh)
      assertEquals(1, transport.binds)
      assertEquals(1, transport.closes)
    }
  }

  @Test fun failedNullOrTimedOutBindingClosesWithoutSending() {
    val bindings: List<(IconProbeTransport.Callbacks) -> Boolean> = listOf(
      { false },
      { throw IllegalStateException("bind failed") },
      { true },
      { it.disconnected(); it.connected(); true },
    )
    for (bind in bindings) {
      val transport = FakeTransport().apply { onBind = bind }
      val result = session(transport, 1, connectTimeoutMs = 0).probe(128)
      assertEquals(LauncherMaskStatus.UNKNOWN, result.launcherMasks["app0"])
      assertEquals(setOf("app0"), result.packagesToRefresh)
      assertTrue(transport.sent.isEmpty())
      assertEquals(1, transport.binds)
      assertEquals(1, transport.closes)
    }
  }

  @Test fun staleOrPreInterruptedSessionNeverBinds() {
    for (interrupt in listOf(false, true)) {
      val transport = FakeTransport()
      try {
        if (interrupt) Thread.currentThread().interrupt()
        assertThrows(IconProbeCancelledException::class.java) {
          session(transport, 1, isCurrent = { interrupt }).probe(128)
        }
        assertEquals(interrupt, Thread.currentThread().isInterrupted)
        assertEquals(0, transport.binds)
        assertEquals(0, transport.closes)
      } finally {
        Thread.interrupted()
      }
    }
  }

  @Test fun cancellingEitherWaitClosesOnceAndPreservesInterruptStatus() {
    for (connectWait in listOf(false, true)) {
      for (interrupt in listOf(false, true)) {
        val transport = FakeTransport()
        val waiting = CountDownLatch(1)
        val current = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean()
        if (connectWait) {
          transport.onBind = { waiting.countDown(); true }
        } else {
          transport.onSend = { _, _ -> waiting.countDown() }
        }
        val worker = Thread {
          try {
            session(transport, 2, isCurrent = current::get).probe(1)
          } catch (caught: Throwable) {
            failure.set(caught)
          } finally {
            interrupted.set(Thread.currentThread().isInterrupted)
          }
        }
        worker.start()
        try {
          assertTrue(waiting.await(2, TimeUnit.SECONDS))
          if (interrupt) worker.interrupt() else current.set(false)
          worker.join(2_000)
          assertFalse(worker.isAlive)
          assertTrue(failure.get() is IconProbeCancelledException)
          assertEquals(interrupt, interrupted.get())
          assertEquals(if (connectWait) emptyList<Int>() else listOf(0), transport.sent)
          assertEquals(1, transport.binds)
          assertEquals(1, transport.closes)
        } finally {
          worker.interrupt()
          worker.join(2_000)
        }
      }
    }
  }

  @Test fun staleGenerationAtChunkBoundaryDiscardsResultsAndStopsTheSweep() {
    var current = true
    val transport = FakeTransport()
    transport.onSend = { id, callbacks ->
      callbacks.result(id, false, LauncherMaskStatus.USABLE)
      if (id == 127) current = false
    }
    assertThrows(IconProbeCancelledException::class.java) {
      session(transport, 129, isCurrent = { current }).probe(128)
    }
    assertEquals((0..127).toList(), transport.sent)
    assertEquals(1, transport.binds)
    assertEquals(1, transport.closes)
  }

  @Test fun sendFailureIsUnknownButInterruptedSendAbortsTheSession() {
    for (interrupt in listOf(false, true)) {
      val transport = FakeTransport()
      transport.onSend = { id, callbacks ->
        if (id == 1) {
          if (interrupt) throw InterruptedException("send interrupted")
          throw IllegalStateException("send failed")
        }
        callbacks.result(id, false, LauncherMaskStatus.USABLE)
      }
      try {
        if (interrupt) {
          assertThrows(IconProbeCancelledException::class.java) { session(transport, 3).probe(2) }
          assertTrue(Thread.currentThread().isInterrupted)
          assertEquals(listOf(0, 1), transport.sent)
        } else {
          val result = session(transport, 3).probe(2)
          assertEquals(setOf("app1"), result.packagesToRefresh)
          assertEquals(LauncherMaskStatus.UNKNOWN, result.launcherMasks["app1"])
          assertEquals(LauncherMaskStatus.USABLE, result.launcherMasks["app2"])
          assertEquals(listOf(0, 1, 2), transport.sent)
        }
        assertEquals(1, transport.binds)
        assertEquals(1, transport.closes)
      } finally {
        Thread.interrupted()
      }
    }
  }

  @Test fun cancellationInCleanupDiscardsAnAlreadyCompletedResult() {
    for (interrupt in listOf(false, true)) {
      var current = true
      val transport = FakeTransport()
      transport.onClose = {
        if (interrupt) Thread.currentThread().interrupt() else current = false
      }
      try {
        assertThrows(IconProbeCancelledException::class.java) {
          session(transport, 1, isCurrent = { current }).probe(128)
        }
        assertEquals(interrupt, Thread.currentThread().isInterrupted)
        assertEquals(1, transport.binds)
        assertEquals(1, transport.closes)
      } finally {
        Thread.interrupted()
      }
    }
  }

  private fun session(
    transport: FakeTransport,
    count: Int,
    isCurrent: () -> Boolean = { true },
    connectTimeoutMs: Long = 3_000,
    resultTimeoutMs: Long = 5_000,
  ) = IconProbeSession(List(count) { "app$it" }, transport, isCurrent, connectTimeoutMs, resultTimeoutMs)

  private class FakeTransport : IconProbeTransport {
    lateinit var callbacks: IconProbeTransport.Callbacks
    var binds = 0
    var closes = 0
    val sent = mutableListOf<Int>()
    var onBind: (IconProbeTransport.Callbacks) -> Boolean = { it.connected(); true }
    var onSend: (Int, IconProbeTransport.Callbacks) -> Unit =
      { id, callbacks -> callbacks.result(id, false, LauncherMaskStatus.USABLE) }
    var onClose: () -> Unit = {}

    override fun bind(callbacks: IconProbeTransport.Callbacks): Boolean {
      binds++
      this.callbacks = callbacks
      return onBind(callbacks)
    }

    override fun send(requestId: Int) {
      sent += requestId
      onSend(requestId, callbacks)
    }

    override fun close() {
      closes++
      onClose()
    }
  }
}
