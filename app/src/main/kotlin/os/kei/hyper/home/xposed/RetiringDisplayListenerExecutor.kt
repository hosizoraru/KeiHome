package os.kei.hyper.home.xposed

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Cancels this listener's dispatches before its worker looper is shut down. */
internal class RetiringDisplayListenerExecutor(
  private val post: (Runnable) -> Boolean,
) : Executor, AutoCloseable {
  @Volatile private var closed = false

  override fun execute(command: Runnable) {
    if (closed) return
    val accepted = post(Runnable {
      if (!closed) command.run()
    })
    // A dispatch already in progress may reach post after retirement closed the queue.
    if (!accepted && !closed) {
      throw RejectedExecutionException("Active display listener handler rejected a callback")
    }
  }

  override fun close() {
    closed = true
  }
}
