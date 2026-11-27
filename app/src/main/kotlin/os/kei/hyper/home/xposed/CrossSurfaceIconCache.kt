package os.kei.hyper.home.xposed

import android.content.BroadcastReceiver
import android.app.UiModeManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver
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
import os.kei.hyper.home.xposed.SurfaceIconKeyIndex.Key
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.nativeLauncherActivityName

/** Host-local, bounded snapshots. Hooks only read memory and enqueue; never decode or use Binder. */
internal class CrossSurfaceIconCache(
  private val context: Context,
  private val maxEntries: Int = 64,
  private val themeFile: File = File("/data/system/theme/icons"),
  private val paletteState: () -> Long = {
    Settings.System.getLong(context.contentResolver, NativePaletteIcon.STATE_KEY, 0L)
  },
  private val systemPaletteEvents: Boolean = false,
  private val eagerReload: Boolean = true,
  private val refreshOnMinute: Boolean = false,
  private val notifyOnMiss: Boolean = !eagerReload,
  private val acceptsComponent: ((IconPackComponent) -> Boolean)? = null,
  private val originalOnMiss: Set<String> = emptySet(),
  private val maxIdentities: Int = maxEntries,
  private val watchThemeArchive: Boolean = false,
  /** Called on the worker before an archive or appearance reload, so host caches can follow it. */
  private val beforeAppearanceReload: (() -> Unit)? = null,
  /** Kept last so Kotlin trailing-lambda callers always wire cache-ready replay notifications. */
  private val onReady: (() -> Unit)? = null,
) {
  init {
    require(maxEntries in 1..64)
    require(maxIdentities in maxEntries..2 * maxEntries)
  }
  private val keys = SurfaceIconKeyIndex()
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
  private var settingsObserver: ContentObserver? = null
  private var themeObserver: FileObserver? = null
  private val refreshAppearance = Runnable {
    if (active) beforeAppearanceReload?.let { step ->
      try { step() } catch (failure: Exception) {
        Log.w(TAG, "Host icon caches kept their previous artwork", failure)
      }
    }
    invalidate()
  }
  private var appearanceRefreshQueued = false
  private var observedPalette: Long? = null
  private val wanted = LinkedHashMap<Key, Unit>(16, 0.75f, true)
  private val pending = linkedSetOf<Key>()
  private val misses = hashSetOf<Key>()
  private val dynamicMinutes = hashMapOf<Key, Long>()
  private val weatherTemperature = WeatherIconTemperature()
  private val cache = object : LruCache<Key, Bitmap>(maxEntries * SurfaceIconSnapshotRenderer.SIZE *
    SurfaceIconSnapshotRenderer.SIZE * 4) {
    override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
  }
  private var stamp = ""
  private var stampGeneration = -1L
  // Worker-owned for one drain batch; closing the batch avoids retaining obsolete theme files.
  private var archive: ZipFile? = null
  private var archiveGeneration = -1L
  private var appearance: Appearance? = null
  private var drainScheduled = false
  private val drainWork = Runnable { drain() }
  private var warned = false
  private var notifyReady: Runnable? = null
  private var configurationKey = configurationKey(context.resources.configuration)
  private var appearanceConfiguration = Configuration(context.resources.configuration)
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == Intent.ACTION_TIME_TICK) {
        synchronized(lock) {
          if (active && dynamicMinutes.keys.any { it in wanted }) scheduleReadyLocked()
        }
        return
      }
      if (active) {
        if (intent.action == CrossSurfaceIconEvents.PALETTE_CHANGED) paletteChanged() else appearanceChanged()
      }
    }
  }
  private val paletteReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (active && intent.action == CrossSurfaceIconEvents.PALETTE_CHANGED &&
        sentFromUid == Process.SYSTEM_UID) paletteChanged()
    }
  }
  private val configuration = object : ComponentCallbacks {
    override fun onConfigurationChanged(config: Configuration) {
      synchronized(lock) {
        appearanceConfiguration = Configuration(config)
        val next = configurationKey(config)
        if (next == configurationKey) return
        configurationKey = next
        appearanceChanged()
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
      wanted.keys.forEach { enqueueLocked(it) }
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
    if (watchThemeArchive) register {
      // Watch the directory so replacing the archive by rename keeps the watch alive.
      themeObserver = object : FileObserver(requireNotNull(themeFile.parentFile),
        CLOSE_WRITE or MOVED_TO or MOVED_FROM or DELETE) {
        override fun onEvent(event: Int, path: String?) {
          if (path == themeFile.name) appearanceChanged()
        }
      }.also { it.startWatching() }
    }
    register {
      val paletteUri = Settings.System.getUriFor(NativePaletteIcon.STATE_KEY)
      val nightUri = Settings.Secure.getUriFor("ui_night_mode")
      val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
          if (!active) return
          if (uri == nightUri) appearanceChanged() else paletteChanged()
        }
        override fun onChange(selfChange: Boolean) {
          if (active) paletteChanged()
        }
      }
      context.contentResolver.registerContentObserver(paletteUri, false, observer)
      settingsObserver = observer
      context.contentResolver.registerContentObserver(nightUri, false, observer)
    }
    register {
      context.registerReceiver(receiver, IntentFilter().apply {
        addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        addAction(Intent.ACTION_DATE_CHANGED)
        if (refreshOnMinute) addAction(Intent.ACTION_TIME_TICK)
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

  /** Runs on the existing worker; one packed commit invalidates every retained identity. */
  private fun paletteChanged() {
    if (!active) return
    try {
      val palette = paletteState()
      if (observedPalette == palette) return
      observedPalette = palette
      beforeAppearanceReload?.let { step ->
        try { step() } catch (failure: Exception) {
          Log.w(TAG, "Host icon caches kept their previous artwork", failure)
        }
      }
      invalidate()
    } catch (failure: Exception) {
      if (!warned) {
        warned = true
        Log.w(TAG, "Could not read committed icon palette", failure)
      }
    }
  }

  fun get(packageName: String, activityName: String? = null): Bitmap? {
    if (!validDottedName(packageName) || packageName.length > 240) return null
    val activity = activityName?.takeIf { validDottedName(it) && it.length <= 240 }
    synchronized(lock) {
      if (!active) {
        // Only remember identities. Threads, decoding and Binder work wait for start().
        if (!started && !stopping) retainWantedLocked(packageName, activity)
        return null
      }
      val key = retainWantedLocked(packageName, activity)
      dynamicMinutes[key]?.let { minute ->
        if (minute != System.currentTimeMillis() / 60_000L) cache.remove(key)
      }
      cache.get(key)?.let { return it }
      if (key in misses || key in pending) return null
      enqueueLocked(key)
    }
    return null
  }

  fun isActive(): Boolean = active && !stopping

  private fun retainWantedLocked(packageName: String, activity: String?): Key {
    val key = keys.retain(packageName, activity)
    // Bound cold requests and the active working set; never recycle host-owned bitmaps.
    wanted[key] = Unit
    if (wanted.size > maxIdentities) {
      val oldest = wanted.keys.first()
      wanted.remove(oldest)
      keys.remove(oldest)
      pending.remove(oldest)
      misses.remove(oldest)
      dynamicMinutes.remove(oldest)
      cache.remove(oldest)
    }
    return key
  }

  /** A definitive miss, distinct from an asynchronously pending snapshot. */
  fun isMissing(packageName: String, activityName: String? = null): Boolean = synchronized(lock) {
    val key = keys.find(packageName, activityName?.takeIf { validDottedName(it) && it.length <= 240 })
    key != null && key in misses
  }

  private fun appearanceChanged() {
    if (!watchThemeArchive) {
      invalidate()
      return
    }
    synchronized(lock) {
      if (!active || appearanceRefreshQueued) return
      // A theme apply can deliver a file event, a broadcast and a configuration change.
      appearanceRefreshQueued = handler.postDelayed(refreshAppearance, 64L)
    }
  }

  fun invalidate() {
    synchronized(lock) {
      if (!active) return
      if (appearanceRefreshQueued) handler.removeCallbacks(refreshAppearance)
      appearanceRefreshQueued = false
      revision.incrementAndGet()
      clearSnapshotsLocked()
      if (eagerReload) wanted.keys.forEach { enqueueLocked(it) }
      else scheduleReadyLocked()
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

  private fun scheduleReadyLocked() {
    if (!active || onReady == null) return
    notifyReady?.let {
      if (!eagerReload) return
      handler.removeCallbacks(it)
    }
    val generation = revision.get()
    val ready = object : Runnable {
      override fun run() {
        val publish = synchronized(lock) {
          if (notifyReady !== this) return
          notifyReady = null
          active && generation == revision.get()
        }
        if (publish) try { onReady.invoke() } catch (failure: Exception) {
          Log.w(TAG, "Native icon refresh unavailable", failure)
        }
      }
    }
    notifyReady = ready
    if (!handler.postDelayed(ready, 32L)) notifyReady = null
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
      if (changed) scheduleReadyLocked()
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
      if (stampGeneration == generation && currentStamp != stamp) {
        closeArchive()
        invalidate()
        return false
      }
      stamp = currentStamp
      stampGeneration = generation
      val hasArchive = file.length() in 1..(1024L * 1024 * 1024)
      val canUseOriginal = key.name in originalOnMiss
      val component = IconPackComponent(key.name, key.activity)
      if (acceptsComponent?.invoke(component) == false)
        return publish(key, generation, null, false, now)
      fun checkCurrent() { check(active && generation == revision.get()) }
      if (archiveGeneration != generation) {
        check(closeArchive())
        archiveGeneration = generation
      }
      val current = appearance ?: run {
        val config = synchronized(lock) { Configuration(appearanceConfiguration) }
        // A host can retain a context pinned to its old UI mode. Read the global
        // explicit setting once per generation, never from an icon-binding hook.
        val mode = try {
          (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.nightMode
        } catch (_: Exception) { null }
        val dark = when (mode) {
          UiModeManager.MODE_NIGHT_YES -> true
          UiModeManager.MODE_NIGHT_NO -> false
          else -> {
            val global = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val delivered = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val effective = if (global == Configuration.UI_MODE_NIGHT_UNDEFINED ||
              (watchThemeArchive && delivered != Configuration.UI_MODE_NIGHT_UNDEFINED)) delivered else global
            effective == Configuration.UI_MODE_NIGHT_YES
          }
        }
        val palette = paletteState()
        val previousPalette = observedPalette
        if (previousPalette != null && previousPalette != palette) {
          observedPalette = palette
          closeArchive()
          beforeAppearanceReload?.let { step ->
            try { step() } catch (failure: Exception) {
              Log.w(TAG, "Host icon caches kept their previous artwork", failure)
            }
          }
          invalidate()
          return false
        }
        observedPalette = palette
        Appearance(dark, config.locales[0] ?: Locale.getDefault(), palette)
          .also { appearance = it }
      }
      val monetActive = current.palette ushr 48 == 1L
      if (!hasArchive && !canUseOriginal && !monetActive) return publish(key, generation, null, false, now)
      fun original(): Bitmap? = os.kei.hyper.home.iconpack.OriginalAppIcons.render(
        context, component, SurfaceIconSnapshotRenderer.SIZE, current.dark, ::checkCurrent)
      fun originalOrMonet(): Bitmap? = if (monetActive) {
        val resolvedMask = archive?.let { SurfaceIconSnapshotRenderer.resolveMask(it, ::checkCurrent) }
          ?: os.kei.hyper.home.iconpack.OriginalIconShape.mask
        os.kei.hyper.home.iconpack.OriginalAppIcons.renderTintedMonochrome(
          context, component, SurfaceIconSnapshotRenderer.SIZE, current.dark, current.palette,
          mask = resolvedMask, check = ::checkCurrent,
        ) ?: original()
      } else original()
      if (hasArchive) {
        val zip = archive ?: ZipFile(file).also { archive = it }
        val name = nativeLauncherActivityName(component) ?: key.name
        bitmap = SurfaceIconSnapshotRenderer.render(zip, name, current.dark, current.locale,
          now, current.palette, ::checkCurrent, packageName = key.name, originalIcon = ::originalOrMonet,
          weatherTemperature = { global -> weatherTemperature.read(context, global, now) }) {
          dynamic = true
        }
      }
      if (bitmap == null && (canUseOriginal || monetActive)) {
        bitmap = if (monetActive) {
          val resolvedMask = archive?.let { SurfaceIconSnapshotRenderer.resolveMask(it, ::checkCurrent) }
            ?: os.kei.hyper.home.iconpack.OriginalIconShape.mask
          os.kei.hyper.home.iconpack.OriginalAppIcons.renderTintedMonochrome(
            context, component, SurfaceIconSnapshotRenderer.SIZE, current.dark, current.palette,
            mask = resolvedMask, check = ::checkCurrent,
          ) ?: if (canUseOriginal) original() else null
        } else {
          original()
        }
      }
    } catch (failure: Exception) {
      if (active && generation == revision.get() && !warned) {
        warned = true
        Log.w(TAG, "Themed snapshot unavailable; retaining native app icon", failure)
      }
    }
    return publish(key, generation, bitmap, dynamic, now)
  }

  private fun publish(key: Key, generation: Long, bitmap: Bitmap?, dynamic: Boolean, now: Long): Boolean =
    synchronized(lock) {
      if (!active || generation != revision.get() || key !in wanted) {
        bitmap?.recycle()
        return@synchronized false
      }
      pending -= key
      if (bitmap != null) {
        misses.remove(key)
        cache.put(key, bitmap)
      } else {
        misses += key
      }
      if (dynamic && bitmap != null) dynamicMinutes[key] = now / 60_000L else dynamicMinutes.remove(key)
      bitmap != null || notifyOnMiss
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
          wanted.clear()
          keys.clear()
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
      themeObserver?.let { observer -> unregister {
        cleanupStage = "stop theme archive observer"
        observer.stopWatching()
        themeObserver = null
      } }
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
      settingsObserver?.let { observer -> unregister {
        cleanupStage = "unregister appearance settings observer"
        context.contentResolver.unregisterContentObserver(observer)
        settingsObserver = null
      } }
      synchronized(lock) {
        clearSnapshotsLocked()
        wanted.clear()
        keys.clear()
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
