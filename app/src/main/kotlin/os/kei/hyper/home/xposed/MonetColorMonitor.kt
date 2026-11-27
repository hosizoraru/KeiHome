package os.kei.hyper.home.xposed

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.view.Display
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
  private val onDockGlassChanged: () -> Unit = {},
  private val onLauncherGlassChanged: () -> Unit = {},
  private val onPackageResourcesChanged: (String) -> Unit = {},
  private val onOverlayResourcesChanged: (String) -> Unit = {},
  private val onTimeResourcesChanged: (Boolean) -> Unit = {},
  private val onStatusDiagnostic: (String) -> Unit = {},
  private val onUserUnlocked: () -> Unit = {},
) {
  private val lifecycle = RestartableMonitor<Context>(
    createWorker = ::Watch,
    onFailure = onFailure,
  )

  fun start(context: Context) = lifecycle.start(context)

  fun request(immediate: Boolean = false) = lifecycle.withWorker { (it as Watch).request(immediate) }

  fun requestLauncherMaterials() = lifecycle.withWorker {
    (it as Watch).requestMaterialsFromLauncher()
  }

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
    private val registrations = MonitorRegistrations()
    override val cleanupComplete: Boolean get() = closed && registrations.complete
    private var cleanupQueued = false
    private val displayExecutor = RetiringDisplayListenerExecutor { handler.post(it) }
    private val displayListener = object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) = Unit
      override fun onDisplayRemoved(displayId: Int) = Unit
      override fun onDisplayChanged(displayId: Int) {
        if (!closed && displayId == Display.DEFAULT_DISPLAY &&
          os.kei.hyper.home.data.DockGlassPreferences.read(materialPreferences)) requestMaterials()
      }
    }
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
        key == MonetAction.REFRESH_REVISION || key == MonetAction.REFRESH_USER) request(immediate = true)
    }
    private val statusDiagnostics = java.util.concurrent.atomic.AtomicInteger()
    private fun statusDiagnostic(message: String) {
      if (statusDiagnostics.getAndIncrement() < 16) onStatusDiagnostic(message)
    }
    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
      statusDiagnostic("request notification")
      requestStatus()
    }
    private val materialListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == null || key == ThemeEffectsPreferences.KEY ||
        key == os.kei.hyper.home.data.LauncherLabelPreferences.KEY ||
        os.kei.hyper.home.data.DockGlassPreferences.isPreferenceKey(key) ||
        key == os.kei.hyper.home.data.LauncherGlassPreferences.KEY ||
        key == os.kei.hyper.home.data.LauncherGlassPreferences.SEARCH_KEY) requestMaterials()
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
          registrations.own {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            actionPreferences = null
          }
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
          statusDiagnostic("listener connected fields=" + preferences.all.mapValues { it.value?.javaClass?.simpleName })
          runtimePreferences = preferences
          registrations.own {
            preferences.unregisterOnSharedPreferenceChangeListener(statusListener)
            runtimePreferences = null
          }
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
          registrations.own {
            preferences.unregisterOnSharedPreferenceChangeListener(materialListener)
            materialPreferences = null
          }
          materialPreferenceFailureReported = false
        }
      } catch (failure: Exception) {
        if (!materialPreferenceFailureReported) onFailure(failure)
        materialPreferenceFailureReported = true
      }
    }

    private val retryMaterials = Runnable { requestMaterials() }

    private fun requestMaterials() {
      if (closed) return
      handler.removeCallbacks(retryMaterials)
      handler.removeCallbacks(synchronizeMaterials)
      handler.post(synchronizeMaterials)
    }

    private val synchronizeMaterials = Runnable {
      val canRun = synchronized(this@Watch) {
        if (closed) return@Runnable
        synchronizer.start()
      }
      if (!canRun) {
        if (!closed) {
          handler.removeCallbacks(retryMaterials)
          handler.postDelayed(retryMaterials, 1_000L)
        }
        return@Runnable
      }
      try {
        connectMaterialPreferences()
        try {
          if (synchronizer.synchronizeLauncherGlass(context, materialPreferences) { !closed } && !closed)
            onLauncherGlassChanged()
        } catch (failure: Exception) {
          if (!closed) onFailure(failure)
        }
        try {
          if (synchronizer.synchronizeDockGlass(context, materialPreferences) { !closed } && !closed)
            onDockGlassChanged()
        } catch (failure: Exception) {
          if (!closed) onFailure(failure)
        }
        try {
          if (synchronizer.synchronizeLauncherLabels(context, materialPreferences) { !closed } && !closed)
            onLauncherLabelsChanged()
        } catch (failure: Exception) {
          if (!closed) onFailure(failure)
        }
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
        val request = RuntimeStatusProtocol.readRequest(runtimePreferences) ?: run {
          statusDiagnostic("request missing fields=" + runtimePreferences?.all?.mapValues { it.value?.javaClass?.simpleName })
          return@Runnable
        }
        statusDiagnostic("check id=${request.id.take(8)} age=${SystemClock.elapsedRealtime() - request.startedAtElapsed}")
        if (request.id == lastStatusRequest || !request.isPending(SystemClock.elapsedRealtime())) return@Runnable
        synchronized(this@Watch) {
          if (closed) return@Runnable
          synchronizer.start()
        }
        // Report unavailable/draining native workers instead of withholding the status receipt.
        val isCurrent = {
          !closed && !Thread.currentThread().isInterrupted &&
            request.isPending(SystemClock.elapsedRealtime()) &&
            RuntimeStatusProtocol.readRequest(runtimePreferences) == request
        }
        val started = SystemClock.elapsedRealtime()
        val reported = synchronizer.reportRuntimeStatus(context, request, refreshReceipt(), isCurrent, onStatusDiagnostic)
        statusDiagnostic("report id=${request.id.take(8)} sent=$reported elapsed=${SystemClock.elapsedRealtime() - started}")
        if (reported) {
          lastStatusRequest = request.id
        }
      } catch (failure: Exception) {
        if (!closed) onFailure(failure)
      }
    }

    private val synchronize = Runnable {
      if (closed) return@Runnable
      try {
        connectStatusPreferences()
      } catch (failure: Exception) {
        onFailure(failure)
      }
      val canRun = synchronized(this@Watch) {
        if (closed) return@Runnable
        synchronizer.start()
      }
      if (!closed) {
        // A refused hot reload can leave cancelled native I/O unwinding.
        // Reopen only once that worker is gone, without blocking system hooks.
        if (!canRun) {
          statusDiagnostic("native worker draining; status listener remains available")
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
          // Probe cancellation reflects launcher/package invalidation, not a wallpaper change.
        } catch (failure: Throwable) {
          onFailure(failure)
        } finally {
          if (!closed) handler.postDelayed(heartbeat, nextDelay)
        }
      }
    }
    private val heartbeat = Runnable { request() }

    fun requestMaterialsFromLauncher() {
      if (!closed) handler.post { requestMaterials() }
    }
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
          val unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
              if (closed || intent.action != Intent.ACTION_USER_UNLOCKED) return
              onUserUnlocked()
              requestStatus()
            }
          }
          context.registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED),
            null, handler, Context.RECEIVER_NOT_EXPORTED)
          registrations.own { context.unregisterReceiver(unlockReceiver) }
        } catch (failure: Exception) { onFailure(failure) }
        try {
          val packageObserver = os.kei.hyper.home.data.PackageChangeObserver(context, handler) { name ->
            if (!closed) {
              synchronizer.invalidateIconResources(name)
              onPackageResourcesChanged(name)
              request()
            }
          }
          registrations.own { packageObserver.close() }
        } catch (failure: Exception) { onFailure(failure) }
        try {
          val overlayObserver = os.kei.hyper.home.data.IconOverlayObserver(context, handler) { target ->
            if (!closed) {
              synchronizer.invalidateIconResources(target.takeUnless { it == "android" })
              onOverlayResourcesChanged(target)
              request()
            }
          }
          registrations.own { overlayObserver.close() }
        } catch (failure: Exception) { onFailure(failure) }
        try {
          val timeObserver = os.kei.hyper.home.data.IconTimeObserver(context, handler) { calendarOnly ->
            if (!closed) onTimeResourcesChanged(calendarOnly)
          }
          registrations.own { timeObserver.close() }
        } catch (failure: Exception) { onFailure(failure) }
        connectMaterialPreferences()
        try {
          context.getSystemService(DisplayManager::class.java)?.let {
            // The Handler overload creates a HandlerExecutor that throws on late dispatch
            // after quit, even when the delegate's listener generation was cancelled.
            // A failed registration reply must not discard possible framework ownership.
            registrations.own { it.unregisterDisplayListener(displayListener) }
            it.registerDisplayListener(
              displayExecutor,
              DisplayManager.EVENT_TYPE_DISPLAY_ADDED or DisplayManager.EVENT_TYPE_DISPLAY_CHANGED or
                DisplayManager.EVENT_TYPE_DISPLAY_REMOVED,
              displayListener,
            )
          }
        } catch (failure: Exception) { onFailure(failure) }
        requestMaterials()
        try {
          connectStatusPreferences()
          val resolver = context.contentResolver
          registrations.own { resolver.unregisterContentObserver(observer) }
          resolver.registerContentObserver(
            Settings.System.getUriFor("key_monochrome"), false, observer,
          )
          resolver.registerContentObserver(
            Settings.System.getUriFor("key_monochrome_color"), false, observer,
          )
          resolver.registerContentObserver(
            Uri.parse("content://com.miui.launcher.wallpaper.palette"), false, observer,
          )
          val wallpaper = WallpaperManager.getInstance(context)
          registrations.own { wallpaper.removeOnColorsChangedListener(wallpaperListener) }
          wallpaper.addOnColorsChangedListener(wallpaperListener, handler)
        } catch (failure: Throwable) {
          onFailure(failure)
        } finally {
          // Material renewal must still run if a palette/wallpaper observer is unavailable.
          if (!closed) request()
        }
      }
    }

    fun request(immediate: Boolean = false) {
      if (closed) return
      handler.removeCallbacks(synchronize)
      handler.removeCallbacks(heartbeat)
      if (immediate) handler.post(synchronize) else handler.postDelayed(synchronize, DEBOUNCE_MS)
    }

    @Synchronized
    override fun stop() {
      if (cleanupQueued || closed && registrations.complete) return
      closed = true
      displayExecutor.close()
      handler.removeCallbacksAndMessages(null)
      cleanupQueued = true
      val posted = handler.post {
        var complete = false
        try {
          complete = registrations.release(onFailure)
        } finally {
          synchronized(this@Watch) {
            cleanupQueued = false
            // A failed release retains ownership and an idle looper for the next explicit retry.
            if (complete) thread.quitSafely()
          }
        }
      }
      if (!posted) {
        cleanupQueued = false
        onFailure(IllegalStateException("Monet observer cleanup queue is unavailable"))
      }
      // Release a probe's latch wait before the API 102 generation handover.
      thread.interrupt()
    }
  }

  private companion object {
    const val DEBOUNCE_MS = 750L
    const val HEARTBEAT_MS = 15_000L
  }
}
