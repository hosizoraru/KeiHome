package os.kei.hyper.home.xposed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal interface MonitorWorker {
  val thread: Thread
  val closed: Boolean
  /** Cheap, thread-safe snapshot; thread exit must also be confirmed before accepting retirement. */
  val cleanupComplete: Boolean get() = true
  fun start()
  /** Must permit an explicit retry when closed with cleanup still incomplete. */
  fun stop()
}

/** Registration and cleanup run on the owning worker, without a monitor held over framework calls. */
internal class MonitorRegistrations {
  private val releases = mutableListOf<() -> Unit>()
  @Volatile var complete = true
    private set

  fun own(release: () -> Unit) {
    releases += release
    complete = false
  }

  fun release(onFailure: (Exception) -> Unit): Boolean {
    val iterator = releases.iterator()
    while (iterator.hasNext()) {
      try {
        iterator.next().invoke()
      } catch (failure: Exception) {
        try {
          onFailure(failure)
        } catch (_: Throwable) {
          // Diagnostics must not lose ownership or interrupt the remaining releases.
        }
        continue
      }
      iterator.remove()
    }
    complete = releases.isEmpty()
    return complete
  }
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
  private val ownedWaiters = mutableListOf<Thread>()
  private var starting: CountDownLatch? = null
  private var stopping: CountDownLatch? = null
  // A later start may request a replacement, but cannot cancel this worker's stop.
  private var stopRequested = false

  fun start(value: T) {
    val start = synchronized(lock) {
      desired = value
      if (worker?.let { it.closed && !it.cleanupComplete } == true) {
        // Rollback is an explicit retry signal, not permission to duplicate live registrations.
        stopRequested = true
        reserveStop()
      } else reserveStart()
    }
    start?.invoke()
  }

  fun stop() {
    val stop = synchronized(lock) {
      desired = null
      stopRequested = true
      reserveStop()
    }
    stop?.invoke()
  }

  fun withWorker(action: (MonitorWorker) -> Unit) {
    val current = synchronized(lock) {
      // Watch.request() can wait for the looper too; publish only after start returns.
      if (starting != null || stopping != null || stopRequested) null
      else worker?.takeUnless { it.closed }
    }
    current?.let(action)
  }

  fun awaitStopped(deadlineNanos: Long): Boolean {
    try {
      while (true) {
        val pending = synchronized(lock) { starting ?: stopping } ?: break
        val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
        if (remaining == 0L || !pending.await(remaining, TimeUnit.NANOSECONDS)) return false
      }
      val threads = synchronized(lock) { (listOfNotNull(worker?.thread) + ownedWaiters).distinct() }
      for (thread in threads) {
        if (thread === Thread.currentThread()) return false
        val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
        if (thread.isAlive && remaining > 0) {
          val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
          val nanos = (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
          thread.join(millis, nanos)
        }
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      return false
    }
    return synchronized(lock) {
      desired == null && starting == null && stopping == null && !stopRequested &&
        worker?.thread?.isAlive != true && worker?.cleanupComplete != false &&
        ownedWaiters.all { it.state == Thread.State.TERMINATED }
    }
  }

  // Called under lock; the returned work must run outside it.
  private fun reserveStart(): (() -> Unit)? {
    ownedWaiters.removeAll { it.state == Thread.State.TERMINATED }
    val value = desired ?: return null
    if (starting != null || stopping != null || stopRequested) return null
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
          try {
            val start = synchronized(lock) {
              if (waiter === Thread.currentThread()) waiter = null
              if (worker === previous && previous.cleanupComplete) worker = null
              reserveStart()
            }
            start?.invoke()
          } catch (failure: Throwable) {
            onFailure(failure)
          }
          if (interrupted) Thread.currentThread().interrupt()
        }, "KeiHomeMonetRetirement").apply { isDaemon = true }
        waiter = nextWaiter
        ownedWaiters += nextWaiter
        return {
          try {
            nextWaiter.start()
          } catch (failure: Throwable) {
            synchronized(lock) {
              if (waiter === nextWaiter) waiter = null
              ownedWaiters.remove(nextWaiter)
            }
            throw failure
          }
        }
      }
      return null
    }
    if (previous?.cleanupComplete == false) return null
    val complete = CountDownLatch(1)
    starting = complete
    return {
      try {
        val next = createWorker(value)
        synchronized(lock) { worker = next }
        next.start()
      } finally {
        val stop = synchronized(lock) {
          starting = null
          complete.countDown()
          reserveStop()
        }
        stop?.invoke()
      }
    }
  }

  private fun reserveStop(): (() -> Unit)? {
    if (!stopRequested || starting != null || stopping != null) return null
    val current = worker
    if (current == null) {
      stopRequested = false
      return null
    }
    val complete = CountDownLatch(1)
    stopping = complete
    return {
      try {
        current.stop()
      } finally {
        val start = synchronized(lock) {
          stopping = null
          stopRequested = false
          complete.countDown()
          reserveStart()
        }
        start?.invoke()
      }
    }
  }
}
