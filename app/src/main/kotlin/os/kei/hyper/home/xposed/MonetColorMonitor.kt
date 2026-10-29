package os.kei.hyper.home.xposed

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings

/**
 * Observe only the settings and palette consumed by the known launcher.
 * All resolver calls and hashing stay off system_server's main threads.
 */
internal class MonetColorMonitor(
  private val synchronizer: MonetColorSynchronizer,
  private val onResult: (MonetColorSynchronizer.SynchronizationResult) -> Unit,
  private val onFailure: (Throwable) -> Unit,
) {
  private val lifecycle = RestartableMonitor<Context>(
    createWorker = ::Watch,
    onFailure = onFailure,
  )

  fun start(context: Context) = lifecycle.start(context)

  fun request() = lifecycle.withWorker { (it as Watch).request() }

  fun stop() {
    lifecycle.stop()
    synchronizer.stop()
  }

  fun awaitStopped(deadlineNanos: Long): Boolean = lifecycle.awaitStopped(deadlineNanos) &&
    synchronizer.awaitStopped(deadlineNanos)

  private inner class Watch(private val context: Context) : MonitorWorker {
    override val thread = HandlerThread("KeiHomeMonet")
    private val handler by lazy { Handler(thread.looper) }
    @Volatile override var closed = false
      private set
    private var registered = false

    private val synchronize = Runnable {
      val canRun = synchronized(this@Watch) {
        if (closed) return@Runnable
        synchronizer.start()
      }
      if (!closed) {
        // A refused hot reload can leave cancelled native I/O unwinding.
        // Reopen only once that worker is gone, without blocking system hooks.
        if (!canRun) {
          request()
          return@Runnable
        }
        try {
          onResult(synchronizer.synchronize(context) { !closed })
        } catch (failure: Throwable) {
          onFailure(failure)
        } finally {
          if (!closed) handler.postDelayed(heartbeat, HEARTBEAT_MS)
        }
      }
    }
    private val heartbeat = Runnable { request() }
    private val observer by lazy {
      object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = request()
      }
    }

    override fun start() {
      thread.start()
      handler.post {
        if (closed) return@post
        try {
          val resolver = context.contentResolver
          resolver.registerContentObserver(
            Settings.System.getUriFor("key_monochrome"), false, observer,
          )
          registered = true
          resolver.registerContentObserver(
            Settings.System.getUriFor("key_monochrome_color"), false, observer,
          )
          resolver.registerContentObserver(
            Uri.parse("content://com.miui.launcher.wallpaper.palette"), false, observer,
          )
          if (!closed) request()
        } catch (failure: Throwable) {
          onFailure(failure)
        }
      }
    }

    fun request() {
      if (closed) return
      handler.removeCallbacks(synchronize)
      handler.removeCallbacks(heartbeat)
      handler.postDelayed(synchronize, DEBOUNCE_MS)
    }

    @Synchronized
    override fun stop() {
      if (closed) return
      closed = true
      handler.removeCallbacksAndMessages(null)
      handler.post {
        if (registered) {
          try {
            context.contentResolver.unregisterContentObserver(observer)
          } catch (failure: Throwable) {
            onFailure(failure)
          }
          registered = false
        }
      }
      thread.quitSafely()
    }
  }

  private companion object {
    const val DEBOUNCE_MS = 750L
    const val HEARTBEAT_MS = 15_000L
  }
}
