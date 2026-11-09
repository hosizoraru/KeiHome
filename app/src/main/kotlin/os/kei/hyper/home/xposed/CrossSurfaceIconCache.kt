package os.kei.hyper.home.xposed

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import os.kei.hyper.home.iconpack.NativePaletteIcon
import os.kei.hyper.home.iconpack.validDottedName

/** Host-local, bounded snapshots. Hooks only read memory and enqueue; never decode or use Binder. */
internal class CrossSurfaceIconCache(
  private val context: Context,
  private val onReady: () -> Unit,
) {
  private data class Key(val name: String, val activity: String?)
  private val lock = Any()
  private val revision = AtomicLong()
  private val thread = HandlerThread("KeiHomeSurfaceIcons")
  private lateinit var handler: Handler
  @Volatile private var active = false
  private var started = false
  private var stopping = false
  @Volatile private var cleanupComplete = false
  // Registration and cleanup run on the worker, including a registration already in flight at stop.
  private var componentRegistered = false
  private var receiverRegistered = false
  private var observerRegistered = false
  private val wanted = linkedSetOf<Key>()
  private val pending = linkedSetOf<Key>()
  private val misses = hashSetOf<Key>()
  private val dynamicMinutes = hashMapOf<Key, Long>()
  private val cache = object : LruCache<Key, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
  }
  private var stamp = ""
  // Worker-owned for one drain batch; closing the batch avoids retaining obsolete theme files.
  private var archive: ZipFile? = null
  private var archiveGeneration = -1L
  private var warned = false
  private var notifyReady: Runnable? = null
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (active) invalidate()
    }
  }
  private val configuration = object : ComponentCallbacks {
    override fun onConfigurationChanged(config: Configuration) = invalidate()
    override fun onLowMemory() {
      synchronized(lock) {
        if (!active) return
        revision.incrementAndGet()
        clearSnapshotsLocked()
      }
    }
  }
  private val observer by lazy { object : ContentObserver(handler) {
    override fun onChange(selfChange: Boolean) = invalidate()
  } }

  fun start() {
    synchronized(lock) {
      // Instances are single-use. A late start must not resurrect a retired cache.
      if (started || stopping) return
      started = true
      thread.start()
      handler = Handler(thread.looper)
      active = true
      handler.post {
        registerResources()
        // Warm commonly visible system entries without enumerating packages or starting MAML engines.
        listOf("com.android.calendar", "com.android.deskclock", "com.miui.weather2",
          "com.android.settings", "com.android.mms", "com.android.contacts").forEach { get(it) }
      }
    }
  }

  private fun registerResources() {
    fun register(action: () -> Unit) {
      if (!active) return
      try { action() } catch (failure: Exception) {
        Log.w(TAG, "Some appearance events unavailable; native icon fallback retained", failure)
      }
    }
    register {
      context.registerComponentCallbacks(configuration)
      componentRegistered = true
    }
    register {
      context.registerReceiver(receiver, IntentFilter().apply {
        addAction(Intent.ACTION_DATE_CHANGED)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
        addAction("com.miui.home.ACTION_HD_ICON_UPDATE")
        addAction("android.intent.action.THEME_CHANGED")
      }, null, handler, Context.RECEIVER_NOT_EXPORTED)
      receiverRegistered = true
    }
    register {
      context.contentResolver.registerContentObserver(
        Settings.System.getUriFor(NativePaletteIcon.STATE_KEY), false, observer)
      observerRegistered = true
    }
  }

  fun get(packageName: String, activityName: String? = null): Bitmap? {
    if (!active || !validDottedName(packageName) || packageName.length > 240) return null
    val key = Key(packageName, activityName?.takeIf { validDottedName(it) && it.length <= 240 })
    synchronized(lock) {
      if (!active) return null
      dynamicMinutes[key]?.let { minute ->
        if (minute != System.currentTimeMillis() / 60_000L) cache.remove(key)
      }
      cache.get(key)?.let { return it }
      if (key in misses || key in pending) return null
      if (wanted.size >= 128 && key !in wanted) return null
      wanted += key
      enqueueLocked(key, revision.get())
    }
    return null
  }

  fun invalidate() {
    synchronized(lock) {
      if (!active) return
      val generation = revision.incrementAndGet()
      clearSnapshotsLocked()
      wanted.forEach { enqueueLocked(it, generation) }
    }
  }

  private fun clearSnapshotsLocked() {
    cache.evictAll()
    pending.clear()
    misses.clear()
    dynamicMinutes.clear()
    notifyReady?.let { handler.removeCallbacks(it) }
    notifyReady = null
  }

  private fun enqueueLocked(key: Key, generation: Long) {
    pending += key
    if (!handler.post { load(key, generation) }) pending -= key
  }

  private fun load(key: Key, generation: Long) {
    if (!active || generation != revision.get()) {
      closeArchive()
      return
    }
    var bitmap: Bitmap? = null
    var dynamic = false
    val now = System.currentTimeMillis()
    try {
      val file = File("/data/system/theme/icons")
      val currentStamp = "${file.length()}:${file.lastModified()}"
      if (stamp.isNotEmpty() && currentStamp != stamp) {
        stamp = currentStamp
        closeArchive()
        invalidate()
        return
      }
      stamp = currentStamp
      check(file.length() in 1..(1024L * 1024 * 1024))
      val config = context.resources.configuration
      val dark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
      val locale = config.locales[0] ?: Locale.getDefault()
      val palette = Settings.System.getLong(context.contentResolver, NativePaletteIcon.STATE_KEY, 0L)
      fun checkCurrent() { check(active && generation == revision.get()) }
      if (archiveGeneration != generation) {
        check(closeArchive())
        archiveGeneration = generation
      }
      val zip = archive ?: ZipFile(file).also { archive = it }
      bitmap = SurfaceIconSnapshotRenderer.render(zip, key.activity ?: key.name, dark, locale,
        now, palette, ::checkCurrent, packageName = key.name) { dynamic = true }
    } catch (failure: Exception) {
      if (active && generation == revision.get() && !warned) {
        warned = true
        Log.w(TAG, "Themed snapshot unavailable; retaining native app icon", failure)
      }
    }
    val drained = synchronized(lock) {
      if (!active || generation != revision.get()) {
        bitmap?.recycle()
        return@synchronized true
      }
      pending -= key
      bitmap?.let { cache.put(key, it) } ?: run { misses += key }
      if (dynamic && bitmap != null) dynamicMinutes[key] = now / 60_000L else dynamicMinutes.remove(key)
      if (bitmap != null) {
        notifyReady?.let { handler.removeCallbacks(it) }
        val ready = Runnable {
          if (active && generation == revision.get()) try { onReady() } catch (failure: Exception) {
            Log.w(TAG, "Native icon refresh unavailable", failure)
          }
        }
        notifyReady = ready
        handler.postDelayed(ready, 32L)
      }
      pending.isEmpty()
    }
    if (drained) closeArchive()
  }

  private fun closeArchive(): Boolean {
    return try {
      archive?.close()
      archive = null
      archiveGeneration = -1L
      true
    } catch (failure: Exception) {
      Log.w(TAG, "Could not close themed icon archive", failure)
      false
    }
  }

  /**
   * Framework retirement calls this outside hooks. Wait without holding the admission lock;
   * main/worker callers only request shutdown. Timeout or incomplete cleanup blocks retirement.
   */
  fun stop(): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_TIMEOUT_MS)
    synchronized(lock) {
      if (!stopping) {
        stopping = true
        active = false
        revision.incrementAndGet()
        if (started && ::handler.isInitialized) {
          handler.removeCallbacksAndMessages(null)
          if (!handler.post { cleanup() }) {
            Log.w(TAG, "Could not enqueue icon-cache cleanup; retirement remains blocked")
          }
        } else if (!started) {
          cleanupComplete = true
        }
      }
    }
    if (thread.isAlive) {
      if (Thread.currentThread() === thread || Looper.myLooper() == Looper.getMainLooper()) return false
      val remaining = deadline - System.nanoTime()
      if (remaining <= 0) return false
      try { TimeUnit.NANOSECONDS.timedJoin(thread, remaining) } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return false
      }
    }
    return cleanupComplete && !thread.isAlive
  }

  private fun cleanup() {
    var complete = closeArchive()
    fun unregister(action: () -> Unit) {
      try { action() } catch (failure: Exception) {
        complete = false
        Log.w(TAG, "Could not unregister icon-cache events; retirement remains blocked", failure)
      }
    }
    try {
      if (componentRegistered) unregister {
        context.unregisterComponentCallbacks(configuration)
        componentRegistered = false
      }
      if (receiverRegistered) unregister {
        context.unregisterReceiver(receiver)
        receiverRegistered = false
      }
      if (observerRegistered) unregister {
        context.contentResolver.unregisterContentObserver(observer)
        observerRegistered = false
      }
      synchronized(lock) {
        clearSnapshotsLocked()
        wanted.clear()
      }
      cleanupComplete = complete
    } finally {
      thread.quitSafely()
    }
  }

  private companion object {
    const val TAG = "KeiHomeSurfaceIcons"
    const val STOP_TIMEOUT_MS = 1_500L
  }
}
