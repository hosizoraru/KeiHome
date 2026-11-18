package os.kei.hyper.home.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.iconpack.IconPackComponent

/**
 * Repairs outgoing task descriptions before the native launcher caches their pixels.
 * Hooks only copy objects and consult memory; discovery, user contexts and rendering run on workers.
 */
internal class RecentTaskAppIconRuntime(
  private val xposed: XposedInterface,
  private val loader: ClassLoader,
  private val context: Context,
) {
  private data class Key(val taskId: Int, val userId: Int)
  private class Entry(
    val key: Key,
    val artwork: RecentTaskArtwork,
    val info: Any,
    val controller: WeakReference<Any>,
    var delivered: WeakReference<Bitmap>? = null,
    var replayQueued: Boolean = false,
  ) {
    val component get() = artwork.component

    fun alreadyDelivered(snapshot: Bitmap?): Boolean =
      if (snapshot == null) delivered == null else delivered?.get() === snapshot
  }

  private val lock = java.lang.Object()
  private val token = Any()
  private val entries = LinkedHashMap<Key, Entry>()
  private var cache: CrossSurfaceIconCache? = null
  private var contextUserId = -1
  private val hooks = arrayListOf<XposedInterface.HookHandle>()
  private val replayHandlers = arrayListOf<WeakReference<Handler>>()
  private val replaying = ThreadLocal<Boolean>()
  private val closingCaches = arrayListOf<CrossSurfaceIconCache>()
  private var worker: HandlerThread? = null
  private var workerHandler: Handler? = null
  private var activeHooks = 0
  private var activeReplays = 0
  private var firstDelivery = true
  @Volatile private var stopped = false

  fun start() {
    synchronized(lock) {
      check(worker == null && !stopped)
      val thread = HandlerThread("KeiHomeRecentIcons", Process.THREAD_PRIORITY_BACKGROUND)
      thread.start()
      worker = thread
      workerHandler = Handler(thread.looper).also { handler ->
        handler.post {
          try {
            val profile = RecentTaskIconProfile.resolve(loader, context)
            val userId = runCatching {
              Context::class.java.getDeclaredMethod("getUserId")
                .apply { isAccessible = true }.invoke(context) as Int
            }.getOrDefault(0)
            // Each retained task can need both an entry check and a package snapshot.
            val candidate = CrossSurfaceIconCache(context, maxEntries = MAX_TASKS,
              maxIdentities = 2 * MAX_TASKS, refreshOnMinute = true, notifyOnMiss = true,
              acceptsComponent = ::isLauncherEntry,
              originalOnMiss = setOf(BuildConfig.APPLICATION_ID)) { ready(profile) }
            synchronized(lock) {
              if (stopped) {
                closingCaches += candidate
                return@post
              }
              contextUserId = userId
              cache = candidate
            }
            candidate.start()
            if (!stopped) {
              install(profile)
              // Older launcher task-ID images may predate this generation.
              // This single worker query uses the same root/user checks as later
              // native requests; it launches no app and introduces no polling.
              try {
                val manager = checkNotNull(context.getSystemService(ActivityManager::class.java))
                val existing = manager.getRecentTasks(MAX_TASKS, 0)
                xposed.log(Log.INFO, TAG,
                  "Reconciled existing recent tasks: returned=${existing.size} tracked=${synchronized(lock) { entries.size }}")
              } catch (failure: Exception) {
                xposed.log(Log.WARN, TAG,
                  "Existing recent-task reconciliation unavailable; native requests remain active", failure)
              }
              xposed.log(Log.INFO, TAG,
                "Recent-task icon bridge enabled for user=$userId uiMode=${context.resources.configuration.uiMode}")
            }
          } catch (failure: Throwable) {
            recoverableSystemUiFailure(failure)
            stop()
            xposed.log(Log.WARN, TAG, "Recent-task icon contract unavailable; preserving native delivery", failure)
          }
        }
      }
    }
  }

  private fun isLauncherEntry(component: IconPackComponent): Boolean {
    if (component.className == null) return true
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      .setPackage(component.packageName)
    // A retained task may still refer to a launcher alias disabled after an icon change.
    return context.packageManager.queryIntentActivities(intent,
      PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong())).any {
      it.activityInfo?.let { activity ->
        activity.packageName == component.packageName && activity.name == component.className
      } == true
    }
  }

  fun requestStop() {
    val closing = synchronized(lock) {
      stopped = true
      cache?.let { closingCaches += it }
      cache = null
      workerHandler?.removeCallbacksAndMessages(null)
      closingCaches.toList()
    }
    closing.forEach { it.requestStop() }
  }

  fun retirementStatus(): String = synchronized(lock) {
    "hooks=$activeHooks replays=$activeReplays worker=${worker?.state} " +
      "caches=${closingCaches.joinToString { it.retirementStatus() }}"
  }

  /** A failed drain remains retryable, so an old module generation cannot outlive retirement. */
  fun stop(
    deadline: Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_TIMEOUT_MS),
  ): Boolean {
    requestStop()
    val thread: HandlerThread?
    val handlers: List<Handler>
    val handles: List<XposedInterface.HookHandle>
    synchronized(lock) {
      stopped = true
      entries.clear()
      handlers = replayHandlers.mapNotNull { it.get() }
      replayHandlers.clear()
      handles = hooks.toList()
      cache?.let { closingCaches += it }
      cache = null
      thread = worker
      workerHandler?.removeCallbacksAndMessages(null)
      workerHandler = null
    }
    handlers.forEach { it.removeCallbacksAndMessages(token) }
    var drained = true
    handles.forEach { handle ->
      try {
        handle.unhook()
        synchronized(lock) { hooks.remove(handle) }
      } catch (failure: Throwable) {
        recoverableSystemUiFailure(failure)
        drained = false
      }
    }
    thread?.quitSafely()
    if (thread?.isAlive == true && thread !== Thread.currentThread()) {
      val remaining = deadline - System.nanoTime()
      if (remaining <= 0) return false
      try { TimeUnit.NANOSECONDS.timedJoin(thread, remaining) } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return false
      }
    }
    val closing = synchronized(lock) { closingCaches.toList() }
    drained = drained && thread?.isAlive != true
    closing.forEach { cache ->
      if (cache.awaitStopped(deadline)) synchronized(lock) { closingCaches.remove(cache) }
      else drained = false
    }
    return synchronized(lock) {
      while (activeHooks != 0 || activeReplays != 0) {
        val left = deadline - System.nanoTime()
        if (left <= 0) return@synchronized false
        try { TimeUnit.NANOSECONDS.timedWait(lock, left) } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return@synchronized false
        }
      }
      drained && closingCaches.isEmpty() && hooks.isEmpty()
    }
  }

  private fun install(profile: RecentTaskIconProfile) {
    fun hook(method: java.lang.reflect.Method, id: String, body: (XposedInterface.Chain) -> Any?) {
      val handle = xposed.hook(method).setId(id).intercept { chain ->
        synchronized(lock) { activeHooks += 1 }
        try {
          if (stopped || replaying.get() == true) chain.proceed() else body(chain)
        } finally { synchronized(lock) { activeHooks -= 1; lock.notifyAll() } }
      }
      synchronized(lock) {
        hooks += handle
        if (stopped) {
          handle.unhook()
          hooks.remove(handle)
        }
      }
      xposed.deoptimize(method)
    }
    hook(profile.create, "keihome_recent_task_icon") { chain ->
      val original = chain.proceed() ?: return@hook null
      try {
        val controller = profile.controller(checkNotNull(chain.thisObject))
        val entry = remember(profile, original, controller) ?: return@hook original
        replacement(profile, entry) ?: original
      } catch (failure: Throwable) {
        recoverableSystemUiFailure(failure)
        original
      }
    }
    hook(profile.notify, "keihome_recent_task_description") { chain ->
      var notification: Any? = null
      try {
        val controller = checkNotNull(chain.thisObject)
        val update = checkNotNull(chain.getArg(0))
        val taskId = profile.taskId(update)
        // Native description notifications are lite TaskInfo objects, without reliable user/component.
        val previous = synchronized(lock) {
          entries.values.singleOrNull {
            it.key.taskId == taskId && it.controller.get() === controller
          }
        }
        if (previous != null) {
          val merged = profile.withDescription(previous.info, update)
          val entry = remember(profile, merged, controller)
          if (entry != null) {
            val snapshot = bitmap(entry)
            // Keep the native notification's concrete type and all its metadata.
            if (snapshot != null) notification = profile.withIcon(update, Icon.createWithBitmap(snapshot))
            synchronized(lock) {
              entry.delivered = snapshot?.takeIf { notification != null }?.let(::WeakReference)
            }
          }
        }
      } catch (failure: Throwable) {
        recoverableSystemUiFailure(failure)
      }
      proceedRecentTaskNotification(notification, { chain.proceed() },
        { chain.args.toTypedArray() }, { chain.proceed(it) })
    }
    hook(profile.remove, "keihome_recent_task_removed") { chain ->
      val id = chain.getArg(0) as? Int
      synchronized(lock) { entries.entries.removeAll { it.key.taskId == id } }
      chain.proceed()
    }
  }

  private fun remember(profile: RecentTaskIconProfile, info: Any, controller: Any): Entry? {
    val key = Key(profile.taskId(info), profile.userId(info))
    if (key.taskId < 0 || key.userId < 0) return null
    val artwork = profile.artwork(info, contextUserId) ?: run {
      synchronized(lock) { entries.remove(key) }
      return null
    }
    val entry = Entry(key, artwork, profile.copy(info), WeakReference(controller))
    synchronized(lock) {
      if (stopped) return null
      val previous = entries.remove(key)
      // Query results can go to system_server or another client. Only a native
      // description notification establishes what was sent to task listeners.
      if (previous?.artwork == artwork && previous.controller.get() === controller)
        entry.delivered = previous.delivered
      entries[key] = entry
      entries.entries.removeAll { it.value.controller.get() == null }
      while (entries.size > MAX_TASKS) entries.remove(entries.keys.first())
    }
    return entry
  }

  private fun bitmap(entry: Entry): Bitmap? {
    val current = synchronized(lock) { if (stopped) null else cache } ?: return null
    val component = entry.component
    val exact = current.get(component.packageName, component.className)
    val bitmap = if (exact == null && entry.artwork.allowPackageFallback &&
      current.isMissing(component.packageName, component.className))
      current.get(component.packageName) else exact
    return bitmap?.takeUnless { it.isRecycled }
  }

  private fun replacement(profile: RecentTaskIconProfile, entry: Entry): Any? {
    val bitmap = bitmap(entry) ?: return null
    val copy = profile.withIcon(entry.info, Icon.createWithBitmap(bitmap)) ?: return null
    synchronized(lock) {
      if (stopped || entries[entry.key] !== entry) return null
      if (firstDelivery) {
        firstDelivery = false
        xposed.log(Log.INFO, TAG, "First themed task icon in recent-task query: ${entry.component.packageName}")
      }
    }
    return copy
  }

  private fun ready(profile: RecentTaskIconProfile) {
    val observed = synchronized(lock) {
      if (stopped) return
      entries.values.toList()
    }
    observed.forEach { entry ->
      try {
        val bitmap = bitmap(entry)
        if (bitmap == null && !isMissing(entry)) return@forEach
        val controller = entry.controller.get() ?: return@forEach
        val handler = profile.handler(controller)
        synchronized(lock) {
          if (stopped || entries[entry.key] !== entry || entry.alreadyDelivered(bitmap) || entry.replayQueued)
            return@forEach
          entry.replayQueued = true
          replayHandlers.removeAll { it.get() == null }
          if (replayHandlers.none { it.get() === handler }) replayHandlers += WeakReference(handler)
          // Queue ownership and shutdown's token removal must be serialized.
          if (!handler.postAtTime({ replay(profile, entry) }, token, SystemClock.uptimeMillis()))
            entry.replayQueued = false
        }
      } catch (failure: Throwable) {
        recoverableSystemUiFailure(failure)
      }
    }
  }

  private fun isMissing(entry: Entry): Boolean {
    val current = synchronized(lock) { if (stopped) null else cache } ?: return false
    val component = entry.component
    return current.isMissing(component.packageName, component.className) &&
      (!entry.artwork.allowPackageFallback || current.isMissing(component.packageName))
  }

  private fun replay(profile: RecentTaskIconProfile, entry: Entry) {
    synchronized(lock) {
      if (stopped || entries[entry.key] !== entry) return
      activeReplays += 1
    }
    try {
      // Read the current generation on delivery, including a confirmed theme miss.
      val snapshot = bitmap(entry)
      if (snapshot == null && !isMissing(entry)) return
      val controller = entry.controller.get() ?: return
      val copy = if (snapshot == null) entry.info
        else profile.withIcon(entry.info, Icon.createWithBitmap(snapshot)) ?: return
      synchronized(lock) {
        if (stopped || entries[entry.key] !== entry || entry.alreadyDelivered(snapshot)) return
      }
      replaying.set(true)
      try { profile.notify.invoke(controller, profile.notification(copy)) } finally { replaying.remove() }
      synchronized(lock) { entry.delivered = snapshot?.let(::WeakReference) }
    } catch (failure: Throwable) {
      recoverableSystemUiFailure(failure)
      xposed.log(Log.WARN, TAG, "Native task-description replay failed", failure)
    } finally { synchronized(lock) {
      entry.replayQueued = false
      activeReplays -= 1
      lock.notifyAll()
    } }
  }

  private companion object {
    const val TAG = "KeiHomeRecentIcons"
    // Keep all retained 192px ARGB snapshots below the cache's 12 MiB ceiling,
    // so cache-ready replay cannot perpetually reload its own evicted entries.
    const val MAX_TASKS = 64
    val STOP_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5)
  }
}

internal inline fun <T> proceedRecentTaskNotification(
  replacement: Any?, unchanged: () -> T, copyArguments: () -> Array<Any?>, changed: (Array<Any?>) -> T,
): T {
  if (replacement == null) return unchanged()
  val args = copyArguments()
  args[0] = replacement
  return changed(args)
}
