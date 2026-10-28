package os.kei.hyper.home.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.IInterface
import android.os.RemoteCallbackList
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

internal class LauncherIconRefreshRuntime(private val xposed: XposedInterface) {
  private val lifecycle = IconRefreshReloadLifecycle()
  private val lock = lifecycle.lock
  private val generations = IconRefreshGeneration()
  private val pending = PendingIconRefresh()
  private var worker: HandlerThread? = null
  private var handler: Handler? = null
  private val closingWorkers = RetiringIconRefreshThreads()
  private val closingReporters = mutableListOf<IconRefreshReporter>()
  private var hooksReady = false
  private lateinit var profile: ServerProfile
  private var reporter = newReporter()
  private var lastHookReport: IconRefreshReportKind? = null

  @Volatile
  var service: Any? = null
    private set

  fun install(
    loader: ClassLoader,
    oldService: Any?,
    oldHooks: List<XposedInterface.HookHandle> = emptyList(),
    reconcileAfterReload: Boolean = false,
  ): Boolean {
    val handles = mutableListOf<XposedInterface.HookHandle>()
    val replaced = Collections.newSetFromMap(
      IdentityHashMap<XposedInterface.HookHandle, Boolean>(),
    )
    try {
      val resolved = ServerProfile.resolve(loader)
      profile = resolved
      val currentService = resolved.findExistingService(loader)
      if (currentService != null) {
        service = currentService
        xposed.log(Log.INFO, TAG, "Recovered existing LauncherApps service")
      } else if (oldService != null && resolved.launcherClass.isInstance(oldService)) {
        service = oldService
      }

      synchronized(lock) {
        if (reconcileAfterReload) {
          // Reconciliation reads the current configuration; it only needs a fresh token.
          val generation = generations.next(
            Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES,
          )!!
          pending.replace(IconRefreshRequest(generation, 0))
        }
      }

      val oldListener = findMatchingIconHook(
        oldHooks, LISTENER_HOOK_ID, resolved.registerListener,
      )
      val oldConfiguration = findMatchingIconHook(
        oldHooks, CONFIGURATION_HOOK_ID, resolved.updateConfiguration,
      )
      val listenerHooker = XposedInterface.Hooker { chain ->
        lifecycle.trackHook {
          val result = chain.proceed()
          try {
            if (chain.getArg(0) == LAUNCHER_PACKAGE) {
              synchronized(lock) {
                service = chain.thisObject
                postPendingLocked()
              }
            }
          } catch (failure: Throwable) {
            logFailure("could not observe launcher listener", failure)
          }
          result
        }
      }
      val listener = installOrReplaceIconHook(
        xposed, oldListener, LISTENER_HOOK_ID, resolved.registerListener, listenerHooker,
      )
      listener.second?.let(replaced::add)
      handles += listener.first
      require(listener.first.id == LISTENER_HOOK_ID &&
        listener.first.executable == resolved.registerListener)

      val configurationHooker = XposedInterface.Hooker { chain ->
        lifecycle.trackHook {
          val previous = readUiMode(chain.thisObject)
          val result = chain.proceed()
          try {
            val changes = result as? Int ?: 0
            if (changes and ActivityInfo.CONFIG_UI_MODE != 0) {
              val current = readUiMode(chain.thisObject)
              if (previous != null && current != null) {
                schedule(previous, current)
              }
            }
          } catch (failure: Throwable) {
            logFailure("could not schedule icon refresh", failure)
          }
          result
        }
      }
      val configuration = installOrReplaceIconHook(
        xposed, oldConfiguration, CONFIGURATION_HOOK_ID, resolved.updateConfiguration,
        configurationHooker,
      )
      configuration.second?.let(replaced::add)
      handles += configuration.first
      require(configuration.first.id == CONFIGURATION_HOOK_ID &&
        configuration.first.executable == resolved.updateConfiguration)
      check(removeOldHooks(oldHooks, replaced))
      synchronized(lock) {
        hooksReady = true
        postPendingLocked()
      }
      xposed.log(Log.INFO, TAG, "System-server icon refresh hooks installed")
      report(IconRefreshReportKind.HOOK_INSTALLED)
      return true
    } catch (failure: Throwable) {
      handles.forEach {
        try {
          it.unhook()
        } catch (_: Throwable) {
        }
      }
      removeOldHooks(oldHooks, replaced)
      logFailure("unsupported system-server profile; icon refresh disabled", failure)
      reportHookFailure()
      return false
    }
  }

  fun reportHookFailure() {
    report(IconRefreshReportKind.HOOK_FAILED)
  }

  fun retire(saveState: () -> Unit): Boolean = lifecycle.retire(
    prepare = {
      closingWorkers.pruneStopped()
      closingReporters.removeAll { it.awaitStopped(0) }
      val currentReporter = reporter
      if (closingWorkers.hasLiveThreads() || closingReporters.isNotEmpty()) {
        false
      } else {
        val reporterStopped = try {
          currentReporter?.stopIfIdle() != false
        } catch (failure: Throwable) {
          if (currentReporter != null) {
            reporter = null
            closingReporters += currentReporter
          }
          throw failure
        }
        if (!reporterStopped) {
          false
        } else {
          if (currentReporter != null) {
            reporter = null
            closingReporters += currentReporter
          }
          pending.invalidatePost()
          handler?.removeCallbacksAndMessages(null)
          handler = null
          worker?.let {
            worker = null
            closingWorkers.track(it)
            it.quitSafely()
          }
          true
        }
      }
    },
    await = ::awaitRetirement,
    commit = {
      closingWorkers.pruneStopped()
      closingReporters.removeAll { it.awaitStopped(0) }
      saveState()
      generations.retire()
    },
    rollback = {
      try {
        restoreReporterLocked()
      } finally {
        postPendingLocked()
      }
    },
  )

  private fun awaitRetirement(): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RETIRE_TIMEOUT_MS)
    for (oldReporter in closingReporters) {
      val remaining = TimeUnit.NANOSECONDS.toMillis(
        (deadline - System.nanoTime()).coerceAtLeast(0),
      )
      if (!oldReporter.awaitStopped(remaining)) return false
    }
    return closingWorkers.awaitStopped(deadline) && iconProbeReplyThreadsStopped()
  }

  private fun restoreReporterLocked() {
    if (reporter != null) return
    reporter = newReporter()
    lastHookReport?.let { reporter?.report(it) }
  }

  private fun report(kind: IconRefreshReportKind, count: Int? = null) {
    synchronized(lock) {
      val isHook = kind == IconRefreshReportKind.HOOK_INSTALLED ||
        kind == IconRefreshReportKind.HOOK_FAILED
      if (isHook) lastHookReport = kind
      if (reporter == null && !lifecycle.retiring && !lifecycle.retired) {
        reporter = newReporter()
        if (!isHook) lastHookReport?.let { reporter?.report(it) }
      }
      reporter?.report(kind, count)
    }
  }

  private fun newReporter(): IconRefreshReporter? = try {
    IconRefreshReporter()
  } catch (_: Throwable) {
    null
  }

  private fun removeOldHooks(
    oldHooks: List<XposedInterface.HookHandle>,
    replaced: Set<XposedInterface.HookHandle>,
  ): Boolean {
    var success = true
    oldHooks.forEach {
      if (!replaced.contains(it)) {
        try {
          it.unhook()
        } catch (failure: Throwable) {
          success = false
          logFailure("could not remove old hook", failure)
        }
      }
    }
    return success
  }

  private fun readUiMode(activityTaskManager: Any?): Int? = try {
    (profile.getGlobalConfiguration.invoke(activityTaskManager) as? Configuration)?.uiMode
  } catch (_: Throwable) {
    null
  }

  private fun schedule(previous: Int, current: Int) {
    synchronized(lock) {
      if (lifecycle.retired) return
      val generation = generations.next(previous, current) ?: return
      pending.replace(IconRefreshRequest(generation, 0))
      handler?.removeCallbacksAndMessages(null)
      postPendingLocked()
    }
  }

  private fun postPendingLocked() {
    if (!hooksReady || lifecycle.retiring || lifecycle.retired || service == null ||
      pending.posted
    ) return
    val request = pending.request ?: return
    repeat(2) {
      try {
        val currentHandler = handler ?: HandlerThread("KeiHomeIconRefresh").let { thread ->
          val interrupted = Thread.interrupted()
          try {
            thread.start()
            worker = thread
            Handler(thread.looper).also { handler = it }
          } finally {
            if (interrupted) Thread.currentThread().interrupt()
          }
        }
        val ticket = pending.markPosted()
        val delay = if (request.attempt == 0) REFRESH_DELAY_MS else SNAPSHOT_RETRY_MS
        if (currentHandler.postDelayed({ refresh(request, ticket) }, delay)) return
        pending.postFailed(ticket)
      } catch (failure: Throwable) {
        pending.invalidatePost()
        logFailure("could not queue pending icon refresh", failure)
      }
      handler = null
      worker?.let { failedWorker ->
        worker = null
        closingWorkers.track(failedWorker)
        try {
          failedWorker.quitSafely()
        } catch (failure: Throwable) {
          logFailure("could not stop failed refresh worker", failure)
        }
      }
      if (lifecycle.activeJobs != 0) return
    }
    xposed.log(Log.WARN, TAG, "Could not queue pending icon refresh")
  }

  private fun refresh(request: IconRefreshRequest, ticket: Long) {
    val generation = request.generation
    synchronized(lock) {
      if (lifecycle.retiring || lifecycle.retired || !pending.claim(request, ticket)) return
      if (!generations.isCurrent(generation)) return
      lifecycle.activeJobs += 1
    }
    var sent = 0
    var deferred = false
    var hadFailures = false
    var outcome = IconRefreshReportKind.REFRESH_FAILED
    try {
      val launcherService = service ?: return
      val listeners = snapshotListeners(launcherService)
      if (listeners == null) {
        val exhausted = synchronized(lock) {
          if (!lifecycle.retired && generations.isCurrent(generation) &&
            request.attempt < MAX_SNAPSHOT_RETRIES
          ) {
            pending.replace(IconRefreshRequest(generation, request.attempt + 1))
            postPendingLocked()
            deferred = true
            false
          } else if (!lifecycle.retired && generations.isCurrent(generation)) {
            true
          } else {
            outcome = IconRefreshReportKind.REFRESH_CANCELLED
            false
          }
        }
        if (exhausted) {
          xposed.log(Log.WARN, TAG, "Registered launcher listener snapshot unavailable")
        }
        return
      }
      val context = profile.context.get(launcherService) as? Context ?: return
      val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
      val userManager = context.getSystemService(UserManager::class.java) ?: return
      val probe = IconResourceProbeClient(context)
      for (listener in listeners) {
        val profiles = enabledProfiles(userManager, listener.owner)
        if (profiles == null) {
          hadFailures = true
          continue
        }
        for (user in profiles) {
          if (!generations.isCurrent(generation)) {
            outcome = IconRefreshReportKind.REFRESH_CANCELLED
            return
          }
          if (profile.isEnabledProfile.invoke(
              launcherService, listener.cookie, user, "KeiHome icon refresh",
            ) != true
          ) continue
          try {
            val activities = launcherApps.getActivityList(null, user)
            val probedPackages = probe.packagesToRefresh(activities) {
              generations.isCurrent(generation)
            }
            val shortcutPackages = shortcutPackages(launcherApps, user) ?: run {
              hadFailures = true
              emptySet()
            }
            val packages = (probedPackages + shortcutPackages).toSortedSet()
            xposed.log(
              Log.INFO, TAG,
              "Icon probe generation=$generation user=${profile.userId.invoke(user)} " +
                "activities=${activities.size} packages=${packages.size} " +
                "shortcuts=${shortcutPackages.size}",
            )
            for (packageName in packages) {
              if (!generations.isCurrent(generation)) {
                outcome = IconRefreshReportKind.REFRESH_CANCELLED
                return
              }
              if (!listener.callback.asBinder().isBinderAlive) {
                hadFailures = true
                break
              }
              try {
                if (dispatchIfRegistered(
                    launcherService, listener, user, packageName, generation,
                  )
                ) {
                  sent += 1
                }
              } catch (failure: Throwable) {
                hadFailures = true
                logFailure("launcher callback failed for $packageName", failure)
              }
              if (PACKAGE_INTERVAL_MS > 0) {
                try {
                  Thread.sleep(PACKAGE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                  Thread.currentThread().interrupt()
                  outcome = IconRefreshReportKind.REFRESH_CANCELLED
                  return
                }
              }
            }
          } catch (failure: Throwable) {
            hadFailures = true
            logFailure(
              "icon refresh skipped user=${profile.userId.invoke(user)}",
              failure,
            )
          }
        }
      }
      outcome = when {
        !generations.isCurrent(generation) -> IconRefreshReportKind.REFRESH_CANCELLED
        hadFailures -> IconRefreshReportKind.REFRESH_PARTIAL
        else -> IconRefreshReportKind.REFRESH_FINISHED
      }
      xposed.log(Log.INFO, TAG, "Icon refresh generation=$generation callbacks=$sent")
    } catch (failure: Throwable) {
      logFailure("icon refresh stopped", failure)
    } finally {
      try {
        if (!deferred) report(outcome, sent)
      } finally {
        synchronized(lock) {
          lifecycle.activeJobs -= 1
          if (pending.request != null && !pending.posted) postPendingLocked()
        }
      }
    }
  }

  private fun dispatchIfRegistered(
    launcherService: Any,
    listener: LauncherListener,
    user: UserHandle,
    packageName: String,
    generation: Long,
  ): Boolean {
    @Suppress("UNCHECKED_CAST")
    val callbacks = profile.listeners.get(launcherService) as? RemoteCallbackList<IInterface>
      ?: return false
    val callback = profile.registeredCallbacks.findRegistered(callbacks, listener.cookie) { entry ->
      entry is IInterface && profile.callbackClass.isInstance(entry) &&
        entry.asBinder() == listener.callback.asBinder()
    } as? IInterface ?: return false
    if (!generations.isCurrent(generation)) return false
    if (profile.isEnabledProfile.invoke(
        launcherService, listener.cookie, user, "KeiHome icon refresh",
      ) != true ||
      profile.isPackageVisible.invoke(
        launcherService, packageName, listener.cookie, user,
      ) != true ||
      !callback.asBinder().isBinderAlive
    ) return false
    profile.onPackageChanged.invoke(callback, user, packageName)
    return true
  }

  private fun snapshotListeners(launcherService: Any): List<LauncherListener>? {
    @Suppress("UNCHECKED_CAST")
    val callbacks = profile.listeners.get(launcherService) as? RemoteCallbackList<IInterface>
      ?: return null
    val entries = profile.registeredCallbacks.snapshot(callbacks) ?: return null
    val result = mutableListOf<LauncherListener>()
    for (entry in entries) {
      val cookie = entry.cookie ?: continue
      if (!profile.cookieClass.isInstance(cookie)) continue
      if (profile.cookiePackage.get(cookie) != LAUNCHER_PACKAGE) continue
      val owner = profile.cookieUser.get(cookie) as? UserHandle ?: continue
      val uid = profile.cookieUid.getInt(cookie)
      val callbackUser = profile.userId.invoke(
        UserHandle.getUserHandleForUid(uid),
      ) as Int
      if (callbackUser != profile.userId.invoke(owner) as Int) continue
      val callback = entry.callback as? IInterface ?: continue
      if (!profile.callbackClass.isInstance(callback)) continue
      result += LauncherListener(cookie, owner, callback)
    }
    return result
  }

  private fun enabledProfiles(manager: UserManager, owner: UserHandle): List<UserHandle>? {
    val ownerId = profile.userId.invoke(owner) as Int
    val profiles = profile.getEnabledProfiles.invoke(manager, ownerId) as? List<*>
      ?: return null
    return profiles.mapNotNull { info ->
      if (info == null) null else profile.userHandle.invoke(info) as? UserHandle
    }
  }

  private fun shortcutPackages(launcherApps: LauncherApps, user: UserHandle): Set<String>? =
    try {
      launcherApps.getShortcuts(
        LauncherApps.ShortcutQuery().setQueryFlags(
          LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
            LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED,
        ),
        user,
      ).orEmpty().mapNotNullTo(mutableSetOf()) { it.`package` }
    } catch (failure: Throwable) {
      xposed.log(
        Log.DEBUG, TAG,
        "Shortcut enumeration unavailable for user=${profile.userId.invoke(user)}",
        failure,
      )
      null
    }

  private fun logFailure(message: String, failure: Throwable) {
    xposed.log(Log.WARN, TAG, message, failure)
  }

  private data class LauncherListener(
    val cookie: Any,
    val owner: UserHandle,
    val callback: IInterface,
  )

  private data class ServerProfile(
    val launcherClass: Class<*>,
    val cookieClass: Class<*>,
    val callbackClass: Class<*>,
    val registerListener: Method,
    val updateConfiguration: Method,
    val getGlobalConfiguration: Method,
    val context: Field,
    val listeners: Field,
    val registeredCallbacks: RegisteredCallbackReader,
    val cookiePackage: Field,
    val cookieUser: Field,
    val cookieUid: Field,
    val isEnabledProfile: Method,
    val isPackageVisible: Method,
    val onPackageChanged: Method,
    val getEnabledProfiles: Method,
    val userId: Method,
    val userHandle: Method,
  ) {
    // This runs only in system_server; unavailable internals are treated as unsupported.
    @SuppressLint("BlockedPrivateApi")
    fun findExistingService(loader: ClassLoader): Any? = try {
      val internal = Class.forName(
        "com.android.server.pm.LauncherAppsService\$LauncherAppsServiceInternal", false, loader,
      )
      val localServices = Class.forName("com.android.server.LocalServices", false, loader)
      val local = localServices.getDeclaredMethod("getService", Class::class.java)
        .invoke(null, internal)
      val owner = local?.javaClass?.declaredFields?.singleOrNull {
        !Modifier.isStatic(it.modifiers) && it.type == launcherClass
      } ?: return null
      owner.isAccessible = true
      owner.get(local)?.takeIf { launcherClass.isInstance(it) }
    } catch (_: Throwable) {
      null
    }

    companion object {
      fun resolve(loader: ClassLoader): ServerProfile {
        val launcher = Class.forName(
          "com.android.server.pm.LauncherAppsService\$LauncherAppsImpl", false, loader,
        )
        val cookie = Class.forName(
          "com.android.server.pm.LauncherAppsService\$BroadcastCookie", false, loader,
        )
        val callback = Class.forName("android.content.pm.IOnAppsChangedListener", false, loader)
        val activityTaskManager = Class.forName(
          "com.android.server.wm.ActivityTaskManagerService", false, loader,
        )
        val userInfo = Class.forName("android.content.pm.UserInfo", false, loader)
        fun Class<*>.method(name: String, vararg args: Class<*>): Method =
          getDeclaredMethod(name, *args).apply { isAccessible = true }
        fun Class<*>.field(name: String): Field =
          getDeclaredField(name).apply { isAccessible = true }

        val updateConfiguration = activityTaskManager.method(
          "updateGlobalConfigurationLocked",
          Configuration::class.java,
          Boolean::class.javaPrimitiveType!!,
          Boolean::class.javaPrimitiveType!!,
          Int::class.javaPrimitiveType!!,
        )
        require(updateConfiguration.returnType == Int::class.javaPrimitiveType)
        val listeners = launcher.field("mListeners")
        require(RemoteCallbackList::class.java.isAssignableFrom(listeners.type))
        val registeredCallbacks = RegisteredCallbackReader.resolve(
          RemoteCallbackList::class.java,
          Class.forName("android.util.ArrayMap", false, RemoteCallbackList::class.java.classLoader),
          IInterface::class.java,
          IBinder::class.java,
        )
        return ServerProfile(
          launcherClass = launcher,
          cookieClass = cookie,
          callbackClass = callback,
          registerListener = launcher.method(
            "addOnAppsChangedListener", String::class.java, callback,
          ),
          updateConfiguration = updateConfiguration,
          getGlobalConfiguration = activityTaskManager.method("getGlobalConfiguration"),
          context = launcher.field("mContext"),
          listeners = listeners,
          registeredCallbacks = registeredCallbacks,
          cookiePackage = cookie.field("packageName"),
          cookieUser = cookie.field("user"),
          cookieUid = cookie.field("callingUid"),
          isEnabledProfile = launcher.method(
            "isEnabledProfileOf", cookie, UserHandle::class.java, String::class.java,
          ),
          isPackageVisible = launcher.method(
            "isPackageVisibleToListener", String::class.java, cookie, UserHandle::class.java,
          ),
          onPackageChanged = callback.method(
            "onPackageChanged", UserHandle::class.java, String::class.java,
          ),
          getEnabledProfiles = UserManager::class.java.method(
            "getEnabledProfiles", Int::class.javaPrimitiveType!!,
          ),
          userId = UserHandle::class.java.method("getIdentifier"),
          userHandle = userInfo.method("getUserHandle"),
        )
      }
    }
  }

  private companion object {
    const val TAG = "KeiHomeIconRefresh"
    const val LAUNCHER_PACKAGE = "com.miui.home"
    const val LISTENER_HOOK_ID = "keihome_launcher_listener"
    const val CONFIGURATION_HOOK_ID = "keihome_night_configuration"
    const val REFRESH_DELAY_MS = 1500L
    const val PACKAGE_INTERVAL_MS = 220L
    const val SNAPSHOT_RETRY_MS = 250L
    const val MAX_SNAPSHOT_RETRIES = 3
    const val RETIRE_TIMEOUT_MS = 1_000L
  }
}

internal fun findMatchingIconHook(
  oldHooks: List<XposedInterface.HookHandle>,
  id: String,
  executable: Executable,
): XposedInterface.HookHandle? {
  val matches = oldHooks.filter { it.id == id && it.executable == executable }
  require(matches.size <= 1) { "Multiple old hooks match $id and $executable" }
  return matches.singleOrNull()
}

internal fun installOrReplaceIconHook(
  xposed: XposedInterface,
  previous: XposedInterface.HookHandle?,
  id: String,
  executable: Executable,
  hooker: XposedInterface.Hooker,
): Pair<XposedInterface.HookHandle, XposedInterface.HookHandle?> {
  return if (previous != null) {
    require(previous.id == id && previous.executable == executable)
    previous.replaceHook(hooker) to previous
  } else {
    xposed.hook(executable).setId(id).intercept(hooker) to null
  }
}

internal fun iconProbeReplyThreadsStopped(): Boolean = try {
  Thread.getAllStackTraces().keys.none {
    it.isAlive && it.name == "KeiHomeIconProbeReply"
  }
} catch (_: Throwable) {
  false
}

// Access is serialized by the lifecycle except while retirement waits.
internal class RetiringIconRefreshThreads {
  private val threads = mutableListOf<Thread>()

  fun track(thread: Thread) {
    threads += thread
  }

  fun pruneStopped() {
    threads.removeAll { !it.isAlive }
  }

  fun hasLiveThreads(): Boolean = threads.any { it.isAlive }

  fun awaitStopped(deadlineNanos: Long): Boolean {
    for (thread in threads) {
      if (thread === Thread.currentThread()) return false
      val remaining = TimeUnit.NANOSECONDS.toMillis(
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0),
      )
      if (thread.isAlive && remaining > 0) {
        try {
          thread.join(remaining)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return false
        }
      }
      if (thread.isAlive) return false
    }
    return true
  }
}

internal data class IconRefreshRequest(val generation: Long, val attempt: Int)

// All operations are guarded by the runtime's lifecycle lock.
internal class PendingIconRefresh {
  var request: IconRefreshRequest? = null
    private set
  var posted = false
    private set
  private var ticket = 0L

  fun replace(value: IconRefreshRequest) {
    request = value
    invalidatePost()
  }

  fun invalidatePost() {
    ticket += 1
    posted = false
  }

  fun markPosted(): Long {
    check(request != null && !posted)
    ticket += 1
    posted = true
    return ticket
  }

  fun postFailed(value: Long) {
    if (ticket == value) posted = false
  }

  fun claim(value: IconRefreshRequest, postedTicket: Long): Boolean {
    if (!posted || ticket != postedTicket || request != value) return false
    request = null
    posted = false
    return true
  }
}

internal class IconRefreshReloadLifecycle {
  val lock = Any()
  var activeHooks = 0
  var activeJobs = 0
  var retiring = false
    private set
  var retired = false
    private set

  fun <T> trackHook(block: () -> T): T {
    synchronized(lock) { activeHooks += 1 }
    try {
      return block()
    } finally {
      synchronized(lock) { activeHooks -= 1 }
    }
  }

  fun retire(
    prepare: () -> Boolean,
    await: () -> Boolean,
    commit: () -> Unit,
    rollback: () -> Unit,
  ): Boolean {
    synchronized(lock) {
      if (retired || retiring || activeHooks != 0 || activeJobs != 0) return false
      retiring = true
      try {
        if (!prepare()) {
          retiring = false
          return false
        }
      } catch (_: Throwable) {
        retiring = false
        try {
          rollback()
        } catch (_: Throwable) {
        }
        return false
      }
    }

    val stopped = try {
      await()
    } catch (_: Throwable) {
      false
    }
    synchronized(lock) {
      if (!stopped || activeHooks != 0 || activeJobs != 0) {
        retiring = false
        try {
          rollback()
        } catch (_: Throwable) {
        }
        return false
      }
      try {
        commit()
      } catch (_: Throwable) {
        retiring = false
        try {
          rollback()
        } catch (_: Throwable) {
        }
        return false
      }
      retired = true
      retiring = false
      return true
    }
  }
}

internal data class RegisteredCallbackEntry(val callback: Any, val cookie: Any?)

internal class RegisteredCallbackReader private constructor(
  private val listClass: Class<*>,
  private val mapClass: Class<*>,
  private val callbackClass: Class<*>,
  private val binderClass: Class<*>,
  private val interfaces: Field,
  private val entryClass: Class<*>,
  private val entryBinder: Field,
  private val entryCallback: Field,
  private val entryCookie: Field,
  private val count: Method,
  private val item: Method,
  private val cookie: Method,
  private val keyAt: Method,
  private val valueAt: Method,
  private val baseMethods: List<Method>,
) {
  fun snapshot(callbacks: Any): List<RegisteredCallbackEntry>? = try {
    require(listClass.isInstance(callbacks))
    var currentClass = callbacks.javaClass
    while (currentClass != listClass) {
      require(currentClass.declaredFields.none { it.name == interfaces.name })
      currentClass = currentClass.superclass ?: error("Unsupported callback list")
    }
    for (method in baseMethods) {
      require(callbacks.javaClass.getMethod(method.name, *method.parameterTypes) == method)
    }
    val registry = requireNotNull(interfaces.get(callbacks))
    require(registry.javaClass == mapClass)
    // The verified platform layout uses this monitor for registration and binder death.
    synchronized(registry) {
      require(interfaces.get(callbacks) === registry)
      val size = count.invoke(callbacks) as Int
      require(size >= 0 && size == (registry as Map<*, *>).size)
      val entries = ArrayList<RegisteredCallbackEntry>(size)
      for (index in 0 until size) {
        val callback = requireNotNull(item.invoke(callbacks, index))
        val callbackCookie = cookie.invoke(callbacks, index)
        val key = keyAt.invoke(registry, index)
        val record = valueAt.invoke(registry, index)
        require(callbackClass.isInstance(callback) && binderClass.isInstance(key))
        require(entryClass.isInstance(record))
        require(entryCallback.get(record) === callback && entryCookie.get(record) === callbackCookie)
        require(entryBinder.get(record) === key)
        entries += RegisteredCallbackEntry(callback, callbackCookie)
      }
      require(interfaces.get(callbacks) === registry)
      entries
    }
  } catch (_: Throwable) {
    null
  }

  fun findRegistered(callbacks: Any, expectedCookie: Any, matches: (Any) -> Boolean): Any? =
    snapshot(callbacks)
      ?.firstOrNull { it.cookie === expectedCookie && matches(it.callback) }
      ?.callback

  companion object {
    fun resolve(
      listClass: Class<*>,
      mapClass: Class<*>,
      callbackClass: Class<*>,
      binderClass: Class<*>,
    ): RegisteredCallbackReader {
      require(callbackClass.isInterface && binderClass.isInterface)
      require(Modifier.isFinal(mapClass.modifiers) && Map::class.java.isAssignableFrom(mapClass))
      fun Class<*>.field(name: String, type: Class<*>, final: Boolean = false): Field =
        getDeclaredField(name).apply {
          require(this.type == type && !Modifier.isStatic(modifiers))
          require(!final || Modifier.isFinal(modifiers))
          isAccessible = true
        }
      fun Class<*>.method(name: String, result: Class<*>, vararg args: Class<*>): Method =
        getDeclaredMethod(name, *args).apply {
          require(returnType == result && Modifier.isPublic(modifiers))
          require(!Modifier.isStatic(modifiers) && !Modifier.isAbstract(modifiers))
          require(!Modifier.isNative(modifiers))
          isAccessible = true
        }

      val interfaces = listClass.field("mInterfaces", mapClass)
      val entryClass = listClass.declaredClasses.single { it.simpleName == "Interface" }
      require(entryClass.enclosingClass == listClass && !Modifier.isStatic(entryClass.modifiers))
      require(Modifier.isFinal(entryClass.modifiers))
      val count = listClass.method("getRegisteredCallbackCount", Int::class.javaPrimitiveType!!)
      val item = listClass.method(
        "getRegisteredCallbackItem", callbackClass, Int::class.javaPrimitiveType!!,
      )
      val cookie = listClass.method(
        "getRegisteredCallbackCookie", Any::class.java, Int::class.javaPrimitiveType!!,
      )
      val baseMethods = listOf(
        count, item, cookie,
        listClass.method("register", Boolean::class.javaPrimitiveType!!, callbackClass),
        listClass.method(
          "register", Boolean::class.javaPrimitiveType!!, callbackClass, Any::class.java,
        ),
        listClass.method("unregister", Boolean::class.javaPrimitiveType!!, callbackClass),
        listClass.method("kill", Void.TYPE),
      )
      return RegisteredCallbackReader(
        listClass = listClass,
        mapClass = mapClass,
        callbackClass = callbackClass,
        binderClass = binderClass,
        interfaces = interfaces,
        entryClass = entryClass,
        entryBinder = entryClass.field("mBinder", binderClass, final = true),
        entryCallback = entryClass.field("mInterface", callbackClass, final = true),
        entryCookie = entryClass.field("mCookie", Any::class.java, final = true),
        count = count,
        item = item,
        cookie = cookie,
        keyAt = mapClass.method("keyAt", Any::class.java, Int::class.javaPrimitiveType!!),
        valueAt = mapClass.method("valueAt", Any::class.java, Int::class.javaPrimitiveType!!),
        baseMethods = baseMethods,
      )
    }
  }
}
