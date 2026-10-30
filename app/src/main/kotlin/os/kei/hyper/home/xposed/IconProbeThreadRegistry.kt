package os.kei.hyper.home.xposed

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tracks only this module generation's reply threads, including threads still starting. */
internal class IconProbeThreadRegistry {
  private data class Owned(val thread: Thread, val startFinished: CountDownLatch = CountDownLatch(1))
  private val owned = ConcurrentHashMap<Thread, Owned>()

  fun start(thread: Thread) {
    require(thread.state == Thread.State.NEW)
    reapTerminated()
    val entry = Owned(thread)
    check(owned.putIfAbsent(thread, entry) == null)
    try {
      thread.start()
    } finally {
      // A failed start has no running thread to drain. NEW must otherwise remain owned.
      if (thread.state == Thread.State.NEW) owned.remove(thread, entry)
      entry.startFinished.countDown()
    }
  }

  fun reapTerminated() {
    for ((thread, entry) in owned) {
      if (thread.state == Thread.State.TERMINATED) owned.remove(thread, entry)
    }
  }

  /** The caller must stop admitting new probes before awaiting this absolute nanoTime deadline. */
  fun awaitStopped(deadlineNanos: Long): Boolean {
    try {
      while (true) {
        if (Thread.currentThread().isInterrupted) return false
        reapTerminated()
        if (owned.isEmpty()) return true
        if (deadlineNanos - System.nanoTime() <= 0) return false
        for (entry in owned.values.toList()) {
          val thread = entry.thread
          if (thread === Thread.currentThread()) return false
          if (entry.startFinished.count != 0L) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0 || !entry.startFinished.await(remaining, TimeUnit.NANOSECONDS)) {
              return false
            }
          }
          if (thread.isAlive) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return false
            val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
            val nanos = (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
            thread.join(millis, nanos)
          }
          if (thread.isAlive) return false
          if (thread.state == Thread.State.TERMINATED) owned.remove(thread, entry)
        }
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      return false
    }
  }
}
