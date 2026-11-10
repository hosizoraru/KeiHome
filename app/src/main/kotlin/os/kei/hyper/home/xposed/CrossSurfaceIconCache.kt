package os.kei.hyper.home.xposed

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
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
  private val maxEntries: Int = 64,
  private val themeFile: File = File("/data/system/theme/icons"),
  private val paletteState: () -> Long = {
    Settings.System.getLong(context.contentResolver, NativePaletteIcon.STATE_KEY, 0L)
  },
  private val systemPaletteEvents: Boolean = false,
  private val onReady: (() -> Unit)? = null,
) {
  init { require(maxEntries in 1..64) }
  private data class Key(val name: String, val activity: String?)
  private data class Appearance(val dark: Boolean, val locale: Locale, val palette: Long)
  private val lock = java.lang.Object()
  private val revision = AtomicLong()
  private val thread = HandlerThread("KeiHomeSurfaceIcons", Process.THREAD_PRIORITY_BACKGROUND)
  private lateinit var handler: Handler
  @Volatile private var active = false
  private var started = false
  private var stopping = false
  @Volatile private var cleanupComplete = false
  @Volatile private var cleanupStage = "not requested"
  private var cleanupScheduled = false
  // Registration and cleanup run on the worker, including a registration already in flight at stop.
  private var componentRegistered = false
  private var receiverRegistered = false
  private var paletteReceiverRegistered = false
  private val wanted = LinkedHashMap<Key, Unit>(16, 0.75f, true)
  private val pending = linkedSetOf<Key>()
  private val misses = hashSetOf<Key>()
  private val dynamicMinutes = hashMapOf<Key, Long>()
  private val cache = object : LruCache<Key, Bitmap>(maxEntries * SurfaceIconSnapshotRenderer.SIZE *
    SurfaceIconSnapshotRenderer.SIZE * 4) {
    override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
  }
  private var stamp = ""
  // Worker-owned for one drain batch; closing the batch avoids retaining obsolete theme files.
  private var archive: ZipFile? = null
  private var archiveGeneration = -1L
  private var appearance: Appearance? = null
  private var drainScheduled = false
  private val drainWork = Runnable { drain() }
  private var warned = false
  private var notifyReady: Runnable? = null
  private var configurationKey = configurationKey(context.resources.configuration)
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (active) invalidate()
    }
  }
  private val paletteReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (active && intent.action == CrossSurfaceIconEvents.PALETTE_CHANGED &&
        sentFromUid == Process.SYSTEM_UID) invalidate()
    }
  }
  private val configuration = object : ComponentCallbacks {
    override fun onConfigurationChanged(config: Configuration) {
      synchronized(lock) {
        val next = configurationKey(config)
        if (next == configurationKey) return
        configurationKey = next
        invalidate()
      }
    }
    override fun onLowMemory() {
      synchronized(lock) {
        if (!active) return
        revision.incrementAndGet()
        clearSnapshotsLocked()
      }
    }
  }
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
        addAction(CrossSurfaceIconEvents.PALETTE_CHANGED)
        addAction("android.intent.action.THEME_CHANGED")
      }, null, handler, Context.RECEIVER_NOT_EXPORTED)
      receiverRegistered = true
        if (systemPaletteEvents) {
          context.registerReceiver(paletteReceiver, IntentFilter(CrossSurfaceIconEvents.PALETTE_CHANGED),
            "android.permission.INTERACT_ACROSS_USERS_FULL", handler, Context.RECEIVER_EXPORTED)
          paletteReceiverRegistered = true
        }
    }
  }

  fun get(packageName: String, activityName: String? = null): Bitmap? {
    if (!active || !validDottedName(packageName) || packageName.length > 240) return null
    val key = Key(packageName, activityName?.takeIf { validDottedName(it) && it.length <= 240 })
    synchronized(lock) {
      if (!active) return null
      // Keep the working set aligned with the bitmap bound, including negative results.
      // Native task/notification owners may still hold a returned bitmap: never recycle on eviction.
      wanted[key] = Unit
      if (wanted.size > maxEntries) {
        val oldest = wanted.keys.first()
        wanted.remove(oldest)
        pending.remove(oldest)
        misses.remove(oldest)
        dynamicMinutes.remove(oldest)
        cache.remove(oldest)
      }
      dynamicMinutes[key]?.let { minute ->
        if (minute != System.currentTimeMillis() / 60_000L) cache.remove(key)
      }
      cache.get(key)?.let { return it }
      if (key in misses || key in pending) return null
      enqueueLocked(key)
    }
    return null
  }

  fun invalidate() {
    synchronized(lock) {
      if (!active) return
      revision.incrementAndGet()
      clearSnapshotsLocked()
      wanted.keys.forEach { enqueueLocked(it) }
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

  private fun enqueueLocked(key: Key) {
    pending += key
    if (!drainScheduled) {
      drainScheduled = true
      if (!handler.post(drainWork)) {
        drainScheduled = false
        pending.clear()
      }
    }
  }

  private fun drain() {
    var changed = false
    // Yield to appearance events and retirement between small batches, rather than
    // queueing a runnable (and a palette Binder read) for every retained icon.
    repeat(8) {
      val next = synchronized(lock) {
        if (active) pending.firstOrNull()?.let { it to revision.get() } else null
      }
      if (next != null) changed = load(next.first, next.second) || changed
    }
    val more = synchronized(lock) {
      if (active && changed && onReady != null) {
        notifyReady?.let { handler.removeCallbacks(it) }
        val generation = revision.get()
        val ready = Runnable {
          if (active && generation == revision.get()) try { onReady.invoke() } catch (failure: Exception) {
            Log.w(TAG, "Native icon refresh unavailable", failure)
          }
        }
        notifyReady = ready
        handler.postDelayed(ready, 32L)
      }
      if (active && pending.isNotEmpty() && handler.post(drainWork)) true
      else {
        drainScheduled = false
        false
      }
    }
    if (!more) closeArchive()
  }

  private fun load(key: Key, generation: Long): Boolean {
    if (!active || generation != revision.get()) {
      closeArchive()
      return false
    }
    var bitmap: Bitmap? = null
    var dynamic = false
    val now = System.currentTimeMillis()
    try {
      val file = themeFile
      val currentStamp = "${file.length()}:${file.lastModified()}"
      if (stamp.isNotEmpty() && currentStamp != stamp) {
        stamp = currentStamp
        closeArchive()
        invalidate()
        return false
      }
      stamp = currentStamp
      check(file.length() in 1..(1024L * 1024 * 1024))
      fun checkCurrent() { check(active && generation == revision.get()) }
      if (archiveGeneration != generation) {
        check(closeArchive())
        archiveGeneration = generation
      }
      val zip = archive ?: ZipFile(file).also { archive = it }
      val current = appearance ?: run {
        val config = context.resources.configuration
        Appearance(config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
          config.locales[0] ?: Locale.getDefault(),
          paletteState())
          .also { appearance = it }
      }
      bitmap = SurfaceIconSnapshotRenderer.render(zip, key.activity ?: key.name, current.dark, current.locale,
        now, current.palette, ::checkCurrent, packageName = key.name) { dynamic = true }
    } catch (failure: Exception) {
      if (active && generation == revision.get() && !warned) {
        warned = true
        Log.w(TAG, "Themed snapshot unavailable; retaining native app icon", failure)
      }
    }
    return synchronized(lock) {
      if (!active || generation != revision.get() || key !in wanted) {
        bitmap?.recycle()
        return@synchronized false
      }
      pending -= key
      bitmap?.let { cache.put(key, it) } ?: run { misses += key }
      if (dynamic && bitmap != null) dynamicMinutes[key] = now / 60_000L else dynamicMinutes.remove(key)
      bitmap != null
    }
  }

  private fun closeArchive(): Boolean {
    return try {
      archive?.close()
      archive = null
      archiveGeneration = -1L
      appearance = null
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
    requestStop()
    return awaitStopped(deadline)
  }

  /** Close admission immediately; let independent owners drain in parallel. */
  fun requestStop() {
    synchronized(lock) {
      if (!stopping) {
        stopping = true
        active = false
        cleanupStage = "queued"
        revision.incrementAndGet()
        if (started && ::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        else if (!started) {
          cleanupComplete = true
          cleanupStage = "complete"
        }
      }
      if (!cleanupComplete && !cleanupScheduled && started && ::handler.isInitialized) {
        cleanupScheduled = true
        if (!handler.post { cleanup() }) {
          cleanupScheduled = false
          Log.w(TAG, "Could not enqueue icon-cache cleanup; retirement remains blocked")
        }
      }
    }
  }

  fun awaitStopped(deadline: Long): Boolean {
    // A failed cleanup leaves the worker alive for a later explicit retry.
    // Wait for this attempt's result as well as thread termination.
    if (Thread.currentThread() === thread || Looper.myLooper() == Looper.getMainLooper()) {
      return cleanupComplete && !thread.isAlive
    }
    synchronized(lock) {
      while (cleanupScheduled) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return false
        try { TimeUnit.NANOSECONDS.timedWait(lock, remaining) } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return false
        }
      }
      if (!cleanupComplete) return false
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

  fun retirementStatus(): String =
    "stage=$cleanupStage cleanupComplete=$cleanupComplete worker=${thread.state}"

  private fun cleanup() {
    cleanupStage = "close archive"
    var complete = closeArchive()
    fun unregister(action: () -> Unit) {
      try { action() } catch (failure: Exception) {
        complete = false
        Log.w(TAG, "Could not unregister icon-cache events; retirement remains blocked", failure)
      }
    }
    try {
      if (componentRegistered) unregister {
        cleanupStage = "unregister component callbacks"
        context.unregisterComponentCallbacks(configuration)
        componentRegistered = false
      }
      if (receiverRegistered) unregister {
        cleanupStage = "unregister receiver"
        context.unregisterReceiver(receiver)
        receiverRegistered = false
      }
      if (paletteReceiverRegistered) unregister {
        cleanupStage = "unregister system palette receiver"
        context.unregisterReceiver(paletteReceiver)
        paletteReceiverRegistered = false
      }
      synchronized(lock) {
        clearSnapshotsLocked()
        wanted.clear()
      }
      cleanupComplete = complete
      cleanupStage = if (complete) "complete" else "cleanup failed"
    } finally {
      if (cleanupComplete) thread.quitSafely()
      synchronized(lock) {
        cleanupScheduled = false
        lock.notifyAll()
      }
    }
  }

  private companion object {
    const val TAG = "KeiHomeSurfaceIcons"
    const val STOP_TIMEOUT_MS = 5_000L
    fun configurationKey(config: Configuration): String =
      "${config.uiMode and Configuration.UI_MODE_NIGHT_MASK}:${config.locales.toLanguageTags()}"
  }
}
