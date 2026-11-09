package os.kei.hyper.home.xposed

import android.content.ComponentName
import android.content.Context
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

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
    val component: ComponentName,
    val info: Any,
    val controller: WeakReference<Any>,
    var delivered: Bitmap? = null,
  )

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
      val thread = HandlerThread("KeiHomeRecentIcons")
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
            val candidate = CrossSurfaceIconCache(context) { ready(profile) }
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

  /** A failed drain remains retryable, so an old module generation cannot outlive retirement. */
  fun stop(): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_TIMEOUT_MS)
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
      try { thread.join(STOP_TIMEOUT_MS) } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return false
      }
    }
    val closing = synchronized(lock) { closingCaches.toList() }
    drained = drained && thread?.isAlive != true
    closing.forEach { cache ->
      if (cache.stop()) synchronized(lock) { closingCaches.remove(cache) } else drained = false
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
      val args = chain.args.toTypedArray()
      try {
        val controller = checkNotNull(chain.thisObject)
        val update = checkNotNull(chain.getArg(0))
        // Native description notifications are lite TaskInfo objects, without reliable user/component.
        val previous = synchronized(lock) {
          entries.values.singleOrNull {
            it.key.taskId == profile.taskId(update) && it.controller.get() === controller
          }
        }
        if (previous != null) {
          val merged = profile.withDescription(previous.info, update)
          val entry = remember(profile, merged, controller)
          if (entry != null) bitmap(entry)?.let { bitmap ->
            // Keep the native notification's concrete type and all its metadata.
            profile.withIcon(update, Icon.createWithBitmap(bitmap))?.let {
              args[0] = it
              synchronized(lock) { entry.delivered = bitmap }
            }
          }
        }
      } catch (failure: Throwable) {
        recoverableSystemUiFailure(failure)
      }
      chain.proceed(args)
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
    val component = profile.component(info, contextUserId) ?: run {
      synchronized(lock) { entries.remove(key) }
      return null
    }
    val entry = Entry(key, component, profile.copy(info), WeakReference(controller))
    synchronized(lock) {
      if (stopped) return null
      entries.remove(key)
      entries[key] = entry
      entries.entries.removeAll { it.value.controller.get() == null }
      while (entries.size > MAX_TASKS) entries.remove(entries.keys.first())
    }
    return entry
  }

  private fun bitmap(entry: Entry): Bitmap? {
    val current = synchronized(lock) { if (stopped) null else cache }
    return current?.get(entry.component.packageName, entry.component.className)
      ?.takeUnless { it.isRecycled }
  }

  private fun replacement(profile: RecentTaskIconProfile, entry: Entry): Any? {
    val bitmap = bitmap(entry) ?: return null
    val copy = profile.withIcon(entry.info, Icon.createWithBitmap(bitmap)) ?: return null
    synchronized(lock) {
      if (stopped || entries[entry.key] !== entry) return null
      entry.delivered = bitmap
      if (firstDelivery) {
        firstDelivery = false
        xposed.log(Log.INFO, TAG, "First themed task icon delivered: ${entry.component.packageName}")
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
        val bitmap = bitmap(entry) ?: return@forEach
        val controller = entry.controller.get() ?: return@forEach
        val handler = profile.handler(controller)
        synchronized(lock) {
          if (stopped || entries[entry.key] !== entry || entry.delivered === bitmap) return@forEach
          // Claim before posting. Reentrant cache callbacks cannot enqueue a second replay.
          entry.delivered = bitmap
          replayHandlers.removeAll { it.get() == null }
          if (replayHandlers.none { it.get() === handler }) replayHandlers += WeakReference(handler)
        }
        if (!handler.postAtTime({ replay(profile, entry, bitmap) }, token, SystemClock.uptimeMillis())) {
          synchronized(lock) { if (entry.delivered === bitmap) entry.delivered = null }
        }
      } catch (failure: Throwable) {
        recoverableSystemUiFailure(failure)
      }
    }
  }

  private fun replay(profile: RecentTaskIconProfile, entry: Entry, expected: Bitmap) {
    synchronized(lock) {
      if (stopped || entries[entry.key] !== entry || entry.delivered !== expected) return
      activeReplays += 1
    }
    try {
      // Drop an obsolete configuration's queued pixels, rather than replaying them after invalidation.
      if (bitmap(entry) !== expected) return
      val controller = entry.controller.get() ?: return
      val copy = profile.withIcon(entry.info, Icon.createWithBitmap(expected)) ?: return
      if (stopped) return
      replaying.set(true)
      try { profile.notify.invoke(controller, profile.notification(copy)) } finally { replaying.remove() }
    } catch (failure: Throwable) {
      recoverableSystemUiFailure(failure)
      synchronized(lock) { if (entry.delivered === expected) entry.delivered = null }
      xposed.log(Log.WARN, TAG, "Native task-description replay failed", failure)
    } finally { synchronized(lock) { activeReplays -= 1; lock.notifyAll() } }
  }

  private companion object {
    const val TAG = "KeiHomeRecentIcons"
    // Keep all retained 192px ARGB snapshots below the cache's 12 MiB ceiling,
    // so cache-ready replay cannot perpetually reload its own evicted entries.
    const val MAX_TASKS = 64
    val STOP_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5)
  }
}
