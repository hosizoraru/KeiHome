package os.kei.hyper.home.xposed

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.Handler
import android.os.HandlerThread
import android.os.IInterface
import android.os.RemoteCallbackList
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class LauncherIconRefreshRuntime(private val xposed: XposedInterface) {
  private val lock = Any()
  private val generations = IconRefreshGeneration()
  private var worker: HandlerThread? = null
  private var handler: Handler? = null
  private var dispatching = false
  private var retired = false
  private lateinit var profile: ServerProfile

  @Volatile
  var service: Any? = null
    private set

  fun install(loader: ClassLoader, oldService: Any?): Boolean {
    val handles = mutableListOf<XposedInterface.HookHandle>()
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

      handles += xposed.hook(resolved.registerListener)
        .setId("keihome_launcher_listener")
        .intercept { chain ->
          val result = chain.proceed()
          try {
            if (chain.getArg(0) == LAUNCHER_PACKAGE) {
              service = chain.thisObject
            }
          } catch (failure: Throwable) {
            logFailure("could not observe launcher listener", failure)
          }
          result
        }

      handles += xposed.hook(resolved.updateConfiguration)
        .setId("keihome_night_configuration")
        .intercept { chain ->
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
      xposed.log(Log.INFO, TAG, "System-server icon refresh hooks installed")
      return true
    } catch (failure: Throwable) {
      handles.forEach {
        try {
          it.unhook()
        } catch (_: Throwable) {
        }
      }
      logFailure("unsupported system-server profile; icon refresh disabled", failure)
      return false
    }
  }

  fun retire(): Boolean = synchronized(lock) {
    if (dispatching) return false
    retired = true
    generations.retire()
    handler?.removeCallbacksAndMessages(null)
    worker?.quitSafely()
    handler = null
    worker = null
    true
  }

  private fun readUiMode(activityTaskManager: Any?): Int? = try {
    (profile.getGlobalConfiguration.invoke(activityTaskManager) as? Configuration)?.uiMode
  } catch (_: Throwable) {
    null
  }

  private fun schedule(previous: Int, current: Int) {
    synchronized(lock) {
      if (retired || service == null) return
      val generation = generations.next(previous, current) ?: return
      val currentHandler = handler ?: HandlerThread("KeiHomeIconRefresh").let { thread ->
        thread.start()
        worker = thread
        Handler(thread.looper).also { handler = it }
      }
      currentHandler.removeCallbacksAndMessages(null)
      currentHandler.postDelayed({ refresh(generation, 0) }, REFRESH_DELAY_MS)
    }
  }

  private fun refresh(generation: Long, attempt: Int) {
    synchronized(lock) {
      if (retired || !generations.isCurrent(generation)) return
      dispatching = true
    }
    try {
      val launcherService = service ?: return
      val listeners = snapshotListeners(launcherService)
      if (listeners == null) {
        synchronized(lock) {
          if (!retired && generations.isCurrent(generation) && attempt < MAX_SNAPSHOT_RETRIES) {
            handler?.postDelayed({ refresh(generation, attempt + 1) }, SNAPSHOT_RETRY_MS)
          }
        }
        return
      }
      val context = profile.context.get(launcherService) as? Context ?: return
      val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
      val userManager = context.getSystemService(UserManager::class.java) ?: return
      var sent = 0
      for (listener in listeners) {
        for (user in enabledProfiles(userManager, listener.owner)) {
          if (!generations.isCurrent(generation)) return
          if (profile.isEnabledProfile.invoke(
              launcherService, listener.cookie, user, "KeiHome icon refresh",
            ) != true
          ) continue

          val activities = launcherApps.getActivityList(null, user)
          val packages = IconResourceProbeClient(context).packagesToRefresh(activities) {
            generations.isCurrent(generation)
          }
          xposed.log(
            Log.INFO, TAG,
            "Icon probe generation=$generation activities=${activities.size} packages=${packages.size}",
          )
          for (packageName in packages) {
            if (!generations.isCurrent(generation)) return
            if (!listener.callback.asBinder().isBinderAlive) break
            try {
              if (dispatchIfRegistered(launcherService, listener, user, packageName, generation)) {
                sent += 1
              }
            } catch (failure: Throwable) {
              logFailure("launcher callback failed for $packageName", failure)
            }
            Thread.sleep(PACKAGE_INTERVAL_MS)
          }
        }
      }
      xposed.log(Log.INFO, TAG, "Icon refresh generation=$generation callbacks=$sent")
    } catch (failure: Throwable) {
      logFailure("icon refresh stopped", failure)
    } finally {
      synchronized(lock) { dispatching = false }
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
    synchronized(callbacks) {
      val count = try {
        callbacks.beginBroadcast()
      } catch (_: IllegalStateException) {
        return false
      }
      try {
        for (index in 0 until count) {
          if (callbacks.getBroadcastCookie(index) !== listener.cookie ||
            callbacks.getBroadcastItem(index).asBinder() != listener.callback.asBinder()
          ) continue
          if (!generations.isCurrent(generation) ||
            profile.isEnabledProfile.invoke(
              launcherService, listener.cookie, user, "KeiHome icon refresh",
            ) != true ||
            profile.isPackageVisible.invoke(
              launcherService, packageName, listener.cookie, user,
            ) != true
          ) return false
          profile.onPackageChanged.invoke(listener.callback, user, packageName)
          return true
        }
      } finally {
        callbacks.finishBroadcast()
      }
    }
    return false
  }

  private fun snapshotListeners(launcherService: Any): List<LauncherListener>? {
    @Suppress("UNCHECKED_CAST")
    val callbacks = profile.listeners.get(launcherService) as? RemoteCallbackList<IInterface>
      ?: return emptyList()
    val result = mutableListOf<LauncherListener>()
    synchronized(callbacks) {
      val count = try {
        callbacks.beginBroadcast()
      } catch (_: IllegalStateException) {
        return null
      }
      try {
        for (index in 0 until count) {
          val cookie = callbacks.getBroadcastCookie(index) ?: continue
          if (!profile.cookieClass.isInstance(cookie)) continue
          if (profile.cookiePackage.get(cookie) != LAUNCHER_PACKAGE) continue
          val owner = profile.cookieUser.get(cookie) as? UserHandle ?: continue
          val uid = profile.cookieUid.getInt(cookie)
          if (UserHandle.getUserHandleForUid(uid) != owner) continue
          val callback = callbacks.getBroadcastItem(index) ?: continue
          if (!profile.callbackClass.isInstance(callback)) continue
          result += LauncherListener(cookie, owner, callback)
        }
      } finally {
        callbacks.finishBroadcast()
      }
    }
    return result
  }

  private fun enabledProfiles(manager: UserManager, owner: UserHandle): List<UserHandle> {
    val ownerId = profile.userId.invoke(owner) as Int
    val profiles = profile.getEnabledProfiles.invoke(manager, ownerId) as? List<*>
      ?: return emptyList()
    return profiles.mapNotNull { info ->
      if (info == null) null else profile.userHandle.invoke(info) as? UserHandle
    }
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
          listeners = launcher.field("mListeners"),
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
    const val REFRESH_DELAY_MS = 1500L
    const val PACKAGE_INTERVAL_MS = 400L
    const val SNAPSHOT_RETRY_MS = 250L
    const val MAX_SNAPSHOT_RETRIES = 3
  }
}
