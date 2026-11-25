package os.kei.hyper.home.xposed

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.UserManager
import os.kei.hyper.home.data.supportsLowBrightnessContrast
import os.kei.hyper.home.data.supportsSystemDarkStyle
import os.kei.hyper.home.status.NavigationBackdropInbox

/** Short control operations that do not depend on completing an icon sweep. */
internal class LauncherControlMonitor(
  private val navigationStatus: () -> Pair<Boolean, String>,
  private val notifyLauncher: (Context, () -> Boolean) -> Int,
  private val wallpaperPreferences: () -> SharedPreferences,
  private val wallpaperSurfaces: () -> WallpaperSurfaceDimming?,
  private val onReady: (Context, Handler) -> Unit,
  private val onFailure: (Throwable) -> Unit,
) {
  private val lifecycle = RestartableMonitor<Context>(::Watch, onFailure)

  fun start(context: Context) = lifecycle.start(context)
  fun requestNavigationStatus() = lifecycle.withWorker { (it as Watch).requestNavigationStatus() }
  fun requestLauncherUpdate() = lifecycle.withWorker { (it as Watch).requestLauncherUpdate() }
  fun requestWallpaperRefresh() = lifecycle.withWorker { (it as Watch).requestWallpaperRefresh() }
  fun stop() = lifecycle.stop()
  fun awaitStopped(deadline: Long) = lifecycle.awaitStopped(deadline)

  private inner class Watch(private val context: Context) : MonitorWorker {
    override val thread = HandlerThread("KeiHomeLauncherControl", Process.THREAD_PRIORITY_DEFAULT)
    private val handler by lazy { Handler(thread.looper) }
    @Volatile override var closed = false
      private set
    private val registrations = MonitorRegistrations()
    override val cleanupComplete: Boolean get() = closed && registrations.complete
    private var cleanupQueued = false
    private var published: Pair<Boolean, String>? = null
    private var updateAttempts = 0
    @Volatile private var dimming: WallpaperDimmingControl? = null

    fun requestWallpaperRefresh() {
      dimming?.request(force = true)
    }

    private val publish = Runnable {
      if (closed) return@Runnable
      try {
        // The unlock receiver requests another receipt even if the choice is unchanged.
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return@Runnable
        val status = navigationStatus()
        if (closed || status == published) return@Runnable
        NavigationBackdropInbox.publish(context, status.first, status.second)
        published = status
      } catch (failure: Exception) {
        if (!closed) onFailure(failure)
      }
    }

    @Synchronized fun requestNavigationStatus() {
      if (closed) return
      handler.removeCallbacks(publish)
      handler.post(publish)
    }

    private val launcherUpdate = object : Runnable {
      override fun run() {
        if (closed) return
        val delivered = try {
          notifyLauncher(context) { !closed }
        } catch (failure: Exception) {
          if (!closed) onFailure(failure)
          0
        }
        // Policy ACK can arrive before LauncherApps has registered a receiver.
        synchronized(this@Watch) {
          if (delivered == 0 && updateAttempts < 3 && !closed &&
            !handler.hasCallbacks(this)
          ) {
            updateAttempts++
            handler.postDelayed(this, 250L)
          }
        }
      }
    }

    @Synchronized fun requestLauncherUpdate() {
      if (closed) return
      updateAttempts = 0
      handler.removeCallbacks(launcherUpdate)
      handler.postDelayed(launcherUpdate, 100L)
    }

    override fun start() {
      thread.start()
      handler.post {
        if (closed) return@post
        try {
          onReady(context, handler)
        } catch (failure: Exception) {
          if (!closed) onFailure(failure)
        }
        try {
          if (supportsSystemDarkStyle()) {
            registerSystemDarkStyle(context, handler, registrations, { !closed }, onFailure)
          }
        } catch (failure: Exception) {
          if (!closed) onFailure(failure)
        }
        try {
          val surfaces = wallpaperSurfaces()
          WallpaperDimmingControl(context, handler, registrations, wallpaperPreferences,
            surfaces, { !closed }, onFailure, onPreferencesReady = { preferences ->
              if (supportsLowBrightnessContrast()) {
                LowBrightnessContrast(context, handler, registrations, { !closed }, onFailure).start(preferences)
              }
            }).also {
            dimming = it
            it.start()
          }
          registerWallpaperPreview(context, handler, registrations, surfaces != null) { !closed }
        } catch (failure: Exception) {
          if (!closed) onFailure(failure)
        }
      }
      requestNavigationStatus()
    }

    @Synchronized override fun stop() {
      if (cleanupQueued || closed && registrations.complete) return
      closed = true
      handler.removeCallbacksAndMessages(null)
      cleanupQueued = true
      val posted = handler.post {
        try {
          registrations.release(onFailure)
        } finally {
          synchronized(this@Watch) {
            cleanupQueued = false
            if (registrations.complete) thread.quitSafely()
          }
        }
      }
      if (!posted) {
        cleanupQueued = false
        onFailure(IllegalStateException("Launcher control cleanup queue is unavailable"))
      }
      thread.interrupt()
    }
  }
}
