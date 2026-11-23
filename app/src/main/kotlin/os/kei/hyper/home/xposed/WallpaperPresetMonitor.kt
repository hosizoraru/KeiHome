package os.kei.hyper.home.xposed

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.WallpaperPresetPreferences
import os.kei.hyper.home.data.wallpaperPresetToApply
import os.kei.hyper.home.status.WallpaperPresetProtocol as Protocol

internal class WallpaperPresetMonitor(
  private val openPreferences: () -> SharedPreferences,
  private val onFailure: (Throwable) -> Unit,
) {
  private val lifecycle = RestartableMonitor<Context>(::Watch, onFailure)

  fun start(context: Context) = lifecycle.start(context)
  fun stop() = lifecycle.stop()
  fun awaitStopped(deadline: Long) = lifecycle.awaitStopped(deadline)

  private inner class Watch(private val context: Context) : MonitorWorker {
    override val thread = HandlerThread("KeiHomeWallpaperPresets", Process.THREAD_PRIORITY_BACKGROUND)
    private val handler by lazy { Handler(thread.looper) }
    private val registrations = MonitorRegistrations()
    private val presets = HyperOsWallpaperPresets(context)
    @Volatile override var closed = false
      private set
    override val cleanupComplete: Boolean get() = closed && registrations.complete
    private var cleanupQueued = false
    private var preferences: SharedPreferences? = null
    private var attempted: Pair<Boolean, String>? = null
    private var applyRetries = 0
    private var failedKey: String? = null
    private var catalog: HyperOsWallpaperPresets.Catalog? = null
    private var readAt = 0L
    private var revision = 0L
    private var retries = 0
    private val currentUser = ActivityManager::class.java.getMethod("getCurrentUser")
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> schedule() }

    private fun synchronize() {
      if (closed) return
      if (!available()) {
        attempted = null
        applyRetries = 0
        failedKey = null
        return
      }
      val prefs = try {
        connectPreferences()
      } catch (failure: Exception) {
        if (++retries <= 3 && !closed) handler.postDelayed(synchronizeWork, 1_000)
        else onFailure(failure)
        return
      }
      retries = 0
      val dark = isDark()
      val key = WallpaperPresetPreferences.read(prefs).forMode(dark)
      val selection = key?.let { dark to it }
      if (attempted != selection) {
        attempted = selection
        applyRetries = 0
        failedKey = null
      } else if (applyRetries != 1) return
      if (key == null) return
      try {
        val inventory = inventory(force = true)
        if (closed || !available()) return
        if (isDark() != dark || WallpaperPresetPreferences.read(prefs).forMode(dark) != key) {
          attempted = null
          schedule()
          return
        }
        val item = wallpaperPresetToApply(key, inventory.current?.key, inventory.history) { it.key }
        if (item == null) {
          applyRetries = 0
          return
        }
        val applied = presets.apply(item)
        catalog = null
        if (applied) applyRetries = 0
        else recordApplyFailure(key)
      } catch (failure: Exception) {
        recordApplyFailure(key)
        if (!closed) onFailure(failure)
      } catch (failure: LinkageError) {
        applyRetries = 0
        failedKey = key
        if (!closed) onFailure(failure)
      }
    }

    private fun recordApplyFailure(key: String) {
      if (closed) return
      if (++applyRetries == 1) {
        handler.removeCallbacks(synchronizeWork)
        handler.postDelayed(synchronizeWork, 1_000)
      } else {
        failedKey = key
      }
    }
    private val synchronizeWork = Runnable { synchronize() }

    private fun schedule() {
      if (closed) return
      handler.removeCallbacks(synchronizeWork)
      handler.postDelayed(synchronizeWork, 200)
    }

    private fun isDark(): Boolean =
      context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun available(): Boolean = currentUser.invoke(null) == 0 &&
      context.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    private fun connectPreferences(): SharedPreferences = preferences ?: openPreferences().also {
      it.registerOnSharedPreferenceChangeListener(listener)
      registrations.own { it.unregisterOnSharedPreferenceChangeListener(listener) }
      preferences = it
    }

    private fun inventory(force: Boolean = false): HyperOsWallpaperPresets.Catalog {
      val now = SystemClock.elapsedRealtime()
      if (!force && now - readAt < 3_000L) catalog?.let { return it }
      return presets.read().also {
        if (force || catalog != it) revision = SystemClock.elapsedRealtimeNanos()
        catalog = it
        readAt = SystemClock.elapsedRealtime()
      }
    }

    override fun start() {
      thread.start()
      handler.post {
        if (closed) return@post
        try {
          registerEvents()
          registerCatalog()
          schedule()
        } catch (failure: Exception) {
          if (!closed) onFailure(failure)
        }
      }
    }

    private fun registerEvents() {
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          if (intent.action == Intent.ACTION_WALLPAPER_CHANGED) catalog = null
          else schedule()
        }
      }
      val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED).apply {
        addAction(Intent.ACTION_USER_UNLOCKED)
        addAction(Intent.ACTION_WALLPAPER_CHANGED)
        addAction("android.intent.action.USER_SWITCHED")
      }
      context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
      registrations.own { context.unregisterReceiver(receiver) }
    }

    private fun registerCatalog() {
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          if (closed || !isOrderedBroadcast || sentFromPackage != BuildConfig.APPLICATION_ID ||
            sentFromUid != context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0) || !available()) return
          val key = intent.getStringExtra(Protocol.KEY)
          val reply = try {
            val list = inventory(intent.getBooleanExtra(Protocol.FORCE, false))
            val entries = (listOfNotNull(list.current) + list.history).distinctBy { it.key }
            Bundle().apply {
              if (key == null) {
                putStringArrayList(Protocol.KEYS, ArrayList(entries.map { it.key }))
                putString(Protocol.CURRENT, list.current?.key)
                putLong(Protocol.REVISION, revision)
                putString(Protocol.APPLY_FAILED, failedKey)
              } else entries.firstOrNull { it.key == key }?.let { putBundle(Protocol.IMAGES, presets.preview(it)) }
            }
          } catch (failure: Exception) {
            if (!closed) onFailure(failure)
            return
          } catch (failure: LinkageError) {
            if (!closed) onFailure(failure)
            return
          }
          if (!closed) setResult(1, null, reply)
        }
      }
      context.registerReceiver(receiver, IntentFilter(Protocol.ACTION), Protocol.PERMISSION, handler, Context.RECEIVER_EXPORTED)
      registrations.own { context.unregisterReceiver(receiver) }
    }

    @Synchronized override fun stop() {
      if (cleanupQueued || closed && registrations.complete) return
      closed = true
      handler.removeCallbacksAndMessages(null)
      cleanupQueued = true
      if (!handler.post {
        try {
          registrations.release(onFailure)
        } finally {
          synchronized(this@Watch) {
            cleanupQueued = false
            if (registrations.complete) thread.quitSafely()
          }
        }
      }) {
        cleanupQueued = false
        onFailure(IllegalStateException("Wallpaper preset cleanup queue unavailable"))
      }
    }
  }
}
