package os.kei.hyper.home.xposed

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartableMonitorTest {
  @Test
  fun rejectedReloadResumesOnlyAfterThePreviousWorkerExits() {
    val created = CopyOnWriteArrayList<FakeWorker>()
    val failures = CopyOnWriteArrayList<Throwable>()
    val secondStarted = CountDownLatch(1)
    val monitor = RestartableMonitor<Int>(
      createWorker = { value ->
        FakeWorker(value, if (value == 2) secondStarted else null).also(created::add)
      },
      onFailure = failures::add,
    )
    try {
      monitor.start(1)
      assertTrue(created[0].started.await(2, TimeUnit.SECONDS))
      monitor.stop()
      assertFalse(monitor.awaitStopped(deadline(20)))

      monitor.start(2)
      assertEquals(1, created.size)
      created[0].release.countDown()
      assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
      assertEquals(listOf(1, 2), created.map(FakeWorker::value))
      assertFalse(created[0].thread.isAlive)
      assertTrue(failures.isEmpty())
    } finally {
      monitor.stop()
      created.forEach { it.release.countDown() }
      assertTrue(monitor.awaitStopped(deadline(2_000)))
    }
  }

  @Test
  fun laterStopCancelsADeferredRestart() {
    val created = CopyOnWriteArrayList<FakeWorker>()
    val monitor = RestartableMonitor<Int>(
      createWorker = { FakeWorker(it).also(created::add) },
      onFailure = { throw AssertionError(it) },
    )
    try {
      monitor.start(1)
      assertTrue(created[0].started.await(2, TimeUnit.SECONDS))
      monitor.stop()
      monitor.start(2)
      monitor.stop()
      created[0].release.countDown()
      assertTrue(monitor.awaitStopped(deadline(2_000)))
      assertEquals(listOf(1), created.map(FakeWorker::value))
    } finally {
      monitor.stop()
      created.forEach { it.release.countDown() }
    }
  }

  private fun deadline(timeoutMillis: Long): Long =
    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

  private class FakeWorker(
    val value: Int,
    private val notified: CountDownLatch? = null,
  ) : MonitorWorker {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    override val thread = Thread({
      started.countDown()
      notified?.countDown()
      release.await()
    }, "test-monitor-$value")
    @Volatile override var closed = false
      private set

    override fun start() = thread.start()

    override fun stop() {
      closed = true
    }
  }
}
