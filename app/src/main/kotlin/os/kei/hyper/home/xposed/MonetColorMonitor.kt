package os.kei.hyper.home.xposed

import android.content.Context
import android.app.WallpaperManager
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.os.OperationCanceledException
import java.util.concurrent.atomic.AtomicBoolean
import os.kei.hyper.home.data.MonetAction

/**
 * Observe only the settings and palette consumed by the known launcher.
 * All resolver calls and hashing stay off system_server's main threads.
 */
internal class MonetColorMonitor(
  private val synchronizer: MonetColorSynchronizer,
  private val onResult: (MonetColorSynchronizer.SynchronizationResult) -> Unit,
  private val onFailure: (Throwable) -> Unit,
  private val requestPreferences: () -> SharedPreferences? = { null },
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
    // In system_server, service calls can hold AMS/Settings locks on this thread.
    // Background nice would also deprioritize the owner while UI callers wait.
    override val thread = HandlerThread("KeiHomeMonet", android.os.Process.THREAD_PRIORITY_DEFAULT)
    private val handler by lazy { Handler(thread.looper) }
    @Volatile override var closed = false
      private set
    private var registered = false
    private var wallpaperRegistered = false
    private var retryDelay = 1_000L
    private var actionPreferences: SharedPreferences? = null
    private var preferenceFailureReported = false
    // The framework holds listeners weakly. This worker owns the strong reference until stop.
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == null || key == MonetAction.REQUEST_REVISION || key == MonetAction.REQUEST_USER ||
        key == MonetAction.REFRESH_REVISION || key == MonetAction.REFRESH_USER) request()
    }
    private val wallpaperDirty = AtomicBoolean(true)
    private val wallpaperListener = WallpaperManager.OnColorsChangedListener { _, _ ->
      wallpaperDirty.set(true)
      request()
    }

    private fun connectActionPreferences() {
      if (closed || actionPreferences != null) return
      try {
        requestPreferences()?.let { preferences ->
          preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
          actionPreferences = preferences
          preferenceFailureReported = false
        }
      } catch (failure: Exception) {
        if (!preferenceFailureReported) onFailure(failure)
        preferenceFailureReported = true
      }
    }

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
        var nextDelay = HEARTBEAT_MS
        try {
          connectActionPreferences()
          val result = synchronizer.synchronize(
            context, observer, actionPreferences, wallpaperDirty.getAndSet(false),
          ) { !closed }
          onResult(result)
          if (result is MonetColorSynchronizer.SynchronizationResult.NativeUnavailable) {
            nextDelay = retryDelay
            retryDelay = (retryDelay * 2).coerceAtMost(HEARTBEAT_MS)
          } else {
            retryDelay = 1_000L
          }
        } catch (_: OperationCanceledException) {
          wallpaperDirty.set(true)
        } catch (_: IconProbeCancelledException) {
          wallpaperDirty.set(true)
        } catch (failure: Throwable) {
          onFailure(failure)
        } finally {
          if (!closed) handler.postDelayed(heartbeat, nextDelay)
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
        connectActionPreferences()
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
          WallpaperManager.getInstance(context).addOnColorsChangedListener(wallpaperListener, handler)
          wallpaperRegistered = true
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
        try {
          actionPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        } catch (failure: Exception) {
          onFailure(failure)
        }
        actionPreferences = null
        if (wallpaperRegistered) {
          WallpaperManager.getInstance(context).removeOnColorsChangedListener(wallpaperListener)
          wallpaperRegistered = false
        }
        if (registered) {
          try {
            context.contentResolver.unregisterContentObserver(observer)
          } catch (failure: Throwable) {
            onFailure(failure)
          }
          registered = false
        }
      }
      // Release a probe's latch wait before the API 102 generation handover.
      thread.interrupt()
      thread.quitSafely()
    }
  }

  private companion object {
    const val DEBOUNCE_MS = 750L
    const val HEARTBEAT_MS = 15_000L
  }
}
