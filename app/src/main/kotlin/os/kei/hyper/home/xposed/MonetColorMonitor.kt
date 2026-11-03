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
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import os.kei.hyper.home.data.MonetAction
import os.kei.hyper.home.data.ThemeEffectsPreferences
import os.kei.hyper.home.status.IconRefreshReceipt
import os.kei.hyper.home.status.RuntimeStatusProtocol

/**
 * Observe only the settings and palette consumed by the known launcher.
 * All resolver calls and hashing stay off system_server's main threads.
 */
internal class MonetColorMonitor(
  private val synchronizer: MonetColorSynchronizer,
  private val onResult: (MonetColorSynchronizer.SynchronizationResult) -> Unit,
  private val onFailure: (Throwable) -> Unit,
  private val requestPreferences: () -> SharedPreferences? = { null },
  private val statusPreferences: () -> SharedPreferences? = { null },
  private val refreshReceipt: () -> IconRefreshReceipt? = { null },
  private val themePreferences: () -> SharedPreferences? = { null },
  private val onThemeMaterialsChanged: () -> Unit = {},
  private val onLauncherLabelsChanged: () -> Unit = {},
  private val onPackageResourcesChanged: (String) -> Unit = {},
  private val onOverlayResourcesChanged: (String) -> Unit = {},
  private val onTimeResourcesChanged: (Boolean) -> Unit = {},
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
    private var packageObserver: os.kei.hyper.home.data.PackageChangeObserver? = null
    private var overlayObserver: os.kei.hyper.home.data.IconOverlayObserver? = null
    private var timeObserver: os.kei.hyper.home.data.IconTimeObserver? = null
    private var retryDelay = 1_000L
    private var actionPreferences: SharedPreferences? = null
    private var runtimePreferences: SharedPreferences? = null
    private var materialPreferences: SharedPreferences? = null
    private var lastStatusRequest: String? = null
    private var statusPreferenceFailureReported = false
    private var preferenceFailureReported = false
    private var materialPreferenceFailureReported = false
    // The framework holds listeners weakly. This worker owns the strong reference until stop.
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == null || key == MonetAction.REQUEST_REVISION || key == MonetAction.REQUEST_USER ||
        key == MonetAction.REFRESH_REVISION || key == MonetAction.REFRESH_USER) request()
    }
    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> requestStatus() }
    private val materialListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == null || key == ThemeEffectsPreferences.KEY ||
        key == os.kei.hyper.home.data.LauncherLabelPreferences.KEY) requestMaterials()
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

    private fun connectStatusPreferences() {
      if (closed || runtimePreferences != null) return
      try {
        statusPreferences()?.let { preferences ->
          preferences.registerOnSharedPreferenceChangeListener(statusListener)
          runtimePreferences = preferences
          statusPreferenceFailureReported = false
          requestStatus()
        }
      } catch (failure: Exception) {
        if (!statusPreferenceFailureReported) onFailure(failure)
        statusPreferenceFailureReported = true
      }
    }

    private fun connectMaterialPreferences() {
      if (closed || materialPreferences != null) return
      try {
        themePreferences()?.let { preferences ->
          preferences.registerOnSharedPreferenceChangeListener(materialListener)
          materialPreferences = preferences
          materialPreferenceFailureReported = false
        }
      } catch (failure: Exception) {
        if (!materialPreferenceFailureReported) onFailure(failure)
        materialPreferenceFailureReported = true
      }
    }

    private fun requestMaterials() {
      if (closed) return
      handler.removeCallbacks(synchronizeMaterials)
      handler.post(synchronizeMaterials)
    }

    private val synchronizeMaterials = Runnable {
      val canRun = synchronized(this@Watch) {
        if (closed) return@Runnable
        synchronizer.start()
      }
      if (!canRun) {
        if (!closed) handler.postDelayed({ requestMaterials() }, 1_000L)
        return@Runnable
      }
      try {
        connectMaterialPreferences()
        if (synchronizer.synchronizeLauncherLabels(context, materialPreferences) { !closed } && !closed)
          onLauncherLabelsChanged()
        if (synchronizer.synchronizeThemeMaterials(context, materialPreferences) { !closed } && !closed) {
          onThemeMaterialsChanged()
        }
      } catch (failure: Exception) {
        if (!closed) onFailure(failure)
      }
    }

    private fun requestStatus() {
      if (closed) return
      handler.removeCallbacks(checkStatus)
      handler.postDelayed(checkStatus, 100L)
    }

    private val checkStatus = Runnable {
      if (closed) return@Runnable
      try {
        val request = RuntimeStatusProtocol.readRequest(runtimePreferences) ?: return@Runnable
        if (request.id == lastStatusRequest || !request.isPending(SystemClock.elapsedRealtime())) return@Runnable
        val canRun = synchronized(this@Watch) { !closed && synchronizer.start() }
        if (!canRun) {
          requestStatus()
          return@Runnable
        }
        val isCurrent = {
          !closed && !Thread.currentThread().isInterrupted &&
            request.isPending(SystemClock.elapsedRealtime()) &&
            RuntimeStatusProtocol.readRequest(runtimePreferences) == request
        }
        if (synchronizer.reportRuntimeStatus(context, request, refreshReceipt(), isCurrent)) {
          lastStatusRequest = request.id
        }
      } catch (failure: Exception) {
        if (!closed) onFailure(failure)
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
          connectStatusPreferences()
          // Renew the independent opt-in before any Monet-OFF/manual/no-palette early return.
          // A rejected optional opcode does not change normal Monet retry/publication state.
          handler.removeCallbacks(synchronizeMaterials)
          synchronizeMaterials.run()
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
          packageObserver = os.kei.hyper.home.data.PackageChangeObserver(context, handler) { name ->
            if (!closed) {
              synchronizer.invalidateIconResources(name)
              onPackageResourcesChanged(name)
              request()
            }
          }
        } catch (failure: Exception) { onFailure(failure) }
        try {
          overlayObserver = os.kei.hyper.home.data.IconOverlayObserver(context, handler) { target ->
            if (!closed) {
              synchronizer.invalidateIconResources(target.takeUnless { it == "android" })
              onOverlayResourcesChanged(target)
              request()
            }
          }
        } catch (failure: Exception) { onFailure(failure) }
        try {
          timeObserver = os.kei.hyper.home.data.IconTimeObserver(context, handler) { calendarOnly ->
            if (!closed) onTimeResourcesChanged(calendarOnly)
          }
        } catch (failure: Exception) { onFailure(failure) }
        connectMaterialPreferences()
        requestMaterials()
        try {
          connectStatusPreferences()
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
        } catch (failure: Throwable) {
          onFailure(failure)
        } finally {
          // Material renewal must still run if a palette/wallpaper observer is unavailable.
          if (!closed) request()
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
        try { packageObserver?.close() } catch (failure: Exception) { onFailure(failure) }
        packageObserver = null
        try { overlayObserver?.close() } catch (failure: Exception) { onFailure(failure) }
        overlayObserver = null
        try { timeObserver?.close() } catch (failure: Exception) { onFailure(failure) }
        timeObserver = null
        try {
          actionPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        } catch (failure: Exception) {
          onFailure(failure)
        }
        actionPreferences = null
        try {
          runtimePreferences?.unregisterOnSharedPreferenceChangeListener(statusListener)
        } catch (failure: Exception) {
          onFailure(failure)
        }
        runtimePreferences = null
        try {
          materialPreferences?.unregisterOnSharedPreferenceChangeListener(materialListener)
        } catch (failure: Exception) {
          onFailure(failure)
        }
        materialPreferences = null
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
