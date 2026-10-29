package os.kei.hyper.home.xposed

import java.util.concurrent.TimeUnit

internal interface MonitorWorker {
  val thread: Thread
  val closed: Boolean
  fun start()
  fun stop()
}

/**
 * A rejected hot reload may leave a stopped worker finishing an in-flight
 * provider call. Resume it only after that worker has exited.
 */
internal class RestartableMonitor<T : Any>(
  private val createWorker: (T) -> MonitorWorker,
  private val onFailure: (Throwable) -> Unit,
) {
  private val lock = Any()
  private var desired: T? = null
  private var worker: MonitorWorker? = null
  private var waiter: Thread? = null

  fun start(value: T) {
    synchronized(lock) {
      desired = value
      startIfPossible()
    }
  }

  fun stop() {
    synchronized(lock) {
      desired = null
      worker?.stop()
    }
  }

  fun withWorker(action: (MonitorWorker) -> Unit) {
    synchronized(lock) {
      worker?.takeUnless { it.closed }?.let(action)
    }
  }

  fun awaitStopped(deadlineNanos: Long): Boolean {
    val threads = synchronized(lock) { listOfNotNull(worker?.thread, waiter).distinct() }
    for (thread in threads) {
      if (thread === Thread.currentThread()) return false
      val remaining = TimeUnit.NANOSECONDS.toMillis(
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0),
      )
      if (thread.isAlive && remaining > 0) {
        try {
          thread.join(remaining)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return false
        }
      }
    }
    return synchronized(lock) {
      desired == null && worker?.thread?.isAlive != true && waiter?.isAlive != true
    }
  }

  private fun startIfPossible() {
    val value = desired ?: return
    val previous = worker
    if (previous != null && previous.thread.isAlive) {
      if (previous.closed && waiter == null) {
        val nextWaiter = Thread({
          var interrupted = false
          while (previous.thread.isAlive) {
            try {
              previous.thread.join()
            } catch (_: InterruptedException) {
              interrupted = true
            }
          }
          synchronized(lock) {
            if (waiter === Thread.currentThread()) waiter = null
            if (worker === previous) worker = null
            try {
              startIfPossible()
            } catch (failure: Throwable) {
              onFailure(failure)
            }
          }
          if (interrupted) Thread.currentThread().interrupt()
        }, "KeiHomeMonetRetirement").apply { isDaemon = true }
        waiter = nextWaiter
        try {
          nextWaiter.start()
        } catch (failure: Throwable) {
          waiter = null
          throw failure
        }
      }
      return
    }
    val next = createWorker(value)
    worker = next
    next.start()
  }
}
