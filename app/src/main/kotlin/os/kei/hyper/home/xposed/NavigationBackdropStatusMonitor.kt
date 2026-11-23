package os.kei.hyper.home.xposed

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.UserManager
import os.kei.hyper.home.status.NavigationBackdropInbox

/** Keeps the configuration receipt responsive while the launcher refreshes icons. */
internal class NavigationBackdropStatusMonitor(
  private val current: () -> Pair<Boolean, String>,
  private val onFailure: (Throwable) -> Unit,
) {
  private val lifecycle = RestartableMonitor<Context>(::Watch, onFailure)

  fun start(context: Context) = lifecycle.start(context)
  fun request() = lifecycle.withWorker { (it as Watch).request() }
  fun stop() = lifecycle.stop()
  fun awaitStopped(deadline: Long) = lifecycle.awaitStopped(deadline)

  private inner class Watch(private val context: Context) : MonitorWorker {
    override val thread = HandlerThread("KeiHomeNavStatus", Process.THREAD_PRIORITY_DEFAULT)
    private val handler by lazy { Handler(thread.looper) }
    @Volatile override var closed = false
      private set
    private var published: Pair<Boolean, String>? = null

    private val publish = Runnable {
      if (closed) return@Runnable
      try {
        // The unlock receiver requests another receipt even if the choice is unchanged.
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return@Runnable
        val status = current()
        if (closed || status == published) return@Runnable
        NavigationBackdropInbox.publish(context, status.first, status.second)
        published = status
      } catch (failure: Exception) {
        if (!closed) onFailure(failure)
      }
    }

    fun request() {
      if (closed) return
      handler.removeCallbacks(publish)
      handler.post(publish)
    }

    override fun start() {
      thread.start()
      request()
    }

    override fun stop() {
      closed = true
      handler.removeCallbacks(publish)
      thread.quitSafely()
      thread.interrupt()
    }
  }
}
