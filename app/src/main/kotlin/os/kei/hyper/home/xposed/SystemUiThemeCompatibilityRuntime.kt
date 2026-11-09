package os.kei.hyper.home.xposed

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import os.kei.hyper.home.data.ThemeEffectsPreferences
import os.kei.hyper.home.data.SplashAppearancePreferences

/**
 * Keeps the real theme identity and resources intact. Only the material gate
 * proven to add a default-theme condition is replaced by its native capability gate.
 */
internal class SystemUiThemeCompatibilityRuntime(
  private val xposed: XposedInterface,
  val classLoader: ClassLoader,
) {
  private val lifecycle = ThemeCompatibilityLifecycle()
  private val workers = mutableListOf<Thread>()
  private val diagnosticQueue = ArrayBlockingQueue<Pair<String, Throwable>>(8)
  private val diagnosticsScheduled = AtomicBoolean()
  private val scheduled = Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())
  private val handles = mutableListOf<XposedInterface.HookHandle>()
  private val installing = mutableSetOf<Pair<Method, String>>()
  private val preferencesInitializing = AtomicBoolean()
  private val enabled = AtomicBoolean()
  private val splashEnabled = AtomicBoolean(SplashAppearancePreferences.DEFAULT)
  private val materialRevision = AtomicLong(1L)
  private val volumeRevisions = WeakHashMap<Any, Long>()
  private val controlCenterRevisions = WeakHashMap<Any, Long>()
  private val tileRevisions = WeakHashMap<Any, Long>()
  private val sliderRevisions = WeakHashMap<Any, Long>()
  private val pendingTiles = WeakHashMap<Any, WeakReference<Any>>()
  private val tileOwners = WeakHashMap<Any, WeakReference<Any>>()
  private val restoredLogged = AtomicBoolean()
  private val preferenceGuard = optionalGuard("material preference")
  private val splashPreferenceGuard = optionalGuard("splash preference")
  @Volatile private var preferences: SharedPreferences? = null
  @Volatile private var splashPreferences: SharedPreferences? = null
  private var appIconRuntime: SystemUiAppIconRuntime? = null
  private val splashPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
    if (key == null || key == SplashAppearancePreferences.KEY) {
      if (lifecycle.enter()) try {
        splashEnabled.set(splashPreferenceGuard.run(false) {
          prefs.getBoolean(SplashAppearancePreferences.KEY, SplashAppearancePreferences.DEFAULT)
        })
      } finally { lifecycle.leave() }
    }
  }
  private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
    if (key == null || key == ThemeEffectsPreferences.KEY) {
      if (lifecycle.enter()) try {
        val selected = preferenceGuard.run(false) { prefs.getBoolean(ThemeEffectsPreferences.KEY, false) }
        if (enabled.getAndSet(selected) != selected) materialRevision.incrementAndGet()
        restoredLogged.set(false)
      } finally { lifecycle.leave() }
    }
  }

  fun install(oldHooks: List<XposedInterface.HookHandle> = emptyList()) {
    val factory = Class.forName(FACTORY_CLASS, false, classLoader)
    val createLoader = factory.declaredMethods.single {
      it.name == "createClassLoader" && it.parameterCount == 0 &&
        it.returnType == ClassLoader::class.java && !Modifier.isStatic(it.modifiers)
    }
    val appInfo = factory.declaredFields.single {
      it.type == ApplicationInfo::class.java && !Modifier.isStatic(it.modifiers)
    }.apply { isAccessible = true }
    synchronized(handles) { handles.addAll(oldHooks) }
    // Release old module closures immediately while the new profile is resolving.
    // Callers retain the native decision during this short asynchronous handover.
    oldHooks.filter {
      (it.id?.startsWith(REFRESH_ID_PREFIX) == true && it.id != LOADER_ID) ||
        it.id?.startsWith("keihome.systemui.notification.") == true
    }.forEach {
      replace(it.executable as Method, requireNotNull(it.id), XposedInterface.Hooker { chain -> chain.proceed() })
    }
    val loaderGuard = optionalGuard("plugin scheduling")
    replace(createLoader, LOADER_ID, XposedInterface.Hooker { chain ->
      if (!lifecycle.enter()) chain.proceed() else try {
        val result = chain.proceed()
        loaderGuard.run(Unit) {
          val info = appInfo.get(chain.thisObject) as? ApplicationInfo
          if (result is ClassLoader && info?.packageName == PLUGIN_PACKAGE) {
            scheduleProfile(result, info.sourceDir)
          }
        }
        result
      } finally { lifecycle.leave() }
    })
    launchOwned {
      initializePreferences()
      try {
        val prefs = xposed.getRemotePreferences(SplashAppearancePreferences.GROUP)
        prefs.registerOnSharedPreferenceChangeListener(splashPreferenceListener)
        splashPreferences = prefs
        splashPreferenceListener.onSharedPreferenceChanged(prefs, SplashAppearancePreferences.KEY)
        SplashBackgroundRuntime(xposed, classLoader, lifecycle, splashEnabled, ::replace,
          ::reportOptionalFailure).install()
      } catch (failure: Throwable) {
        recoverableSystemUiFailure(failure)
        xposed.log(Log.WARN, "KeiHomeSplash", "Native splash profile unavailable; keeping system behavior", failure)
      }
      // On hot reload the plugin already exists. Read the host's live loader,
      // never create a second loader for the same plugin.
      val injector = Class.forName(INJECTOR_CLASS, false, classLoader)
      val cache = injector.declaredFields.single {
        Modifier.isStatic(it.modifiers) && Map::class.java.isAssignableFrom(it.type)
      }.apply { isAccessible = true }.get(null) as Map<*, *>
      val loader = cache[PLUGIN_PACKAGE] as? ClassLoader
      val app = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
        .invoke(null) as? Application
      if (loader != null && app != null) {
        scheduleProfile(loader, app.packageManager.getApplicationInfo(PLUGIN_PACKAGE, 0).sourceDir)
      }
    }
    xposed.log(Log.INFO, TAG, "Watching the native SystemUI plugin loader")
  }

  private fun scheduleProfile(loader: ClassLoader, apkPath: String) {
    synchronized(scheduled) { if (!scheduled.add(loader)) return }
    launchOwned {
      initializePreferences()
      val application = Class.forName("android.app.ActivityThread")
        .getDeclaredMethod("currentApplication").invoke(null) as? Application
      val hostContext = application ?: runCatching {
        val thread = Class.forName("android.app.ActivityThread")
          .getDeclaredMethod("currentActivityThread").invoke(null)
        thread.javaClass.getDeclaredMethod("getSystemContext")
          .apply { isAccessible = true }.invoke(thread) as Context
      }.getOrNull()
      val profiles = SystemUiProfilesCache.resolve(
        loader = loader,
        apkPath = apkPath,
        directory = hostContext?.codeCacheDir,
      )
      val profile = profiles.material
      if (hostContext != null && appIconRuntime == null) {
        try {
          SystemUiAppIconRuntime(hostContext, loader, lifecycle, ::replace,
            ::reportOptionalFailure,
            report = { priority, tag, message -> xposed.log(priority, tag, message) },
          ).also { appIconRuntime = it }.start()
        } catch (failure: Throwable) {
          recoverableSystemUiFailure(failure)
          reportOptionalFailure("notification app icon install", failure)
        }
      }
      xposed.log(Log.INFO, TAG, "SystemUI profile descriptors: " + if (profiles.restored) "restored" else "resolved")
      val materialGuard = optionalGuard("material capability")
      replace(profile.themedGate, MATERIAL_ID, XposedInterface.Hooker { chain ->
        if (!lifecycle.enter()) chain.proceed() else try {
          val original = chain.proceed()
          if (original != false || !enabled.get()) original else materialGuard.run(original) {
            val context = chain.getArg(0) as Context
            val capable = profile.capabilityGate.invoke(null, profile.capabilityArgument(context)) as Boolean
            if (capable && restoredLogged.compareAndSet(false, true)) {
              Log.i(TAG, "Restored theme-limited material; native=false capability=true; theme identity unchanged")
            }
            if (enabled.get()) capable else original
          }
        } finally { lifecycle.leave() }
      })
      profile.volumeRefresh?.let(::installVolumeRefresh)
      profile.volumeRefreshUnavailableReason?.let { xposed.log(Log.INFO, TAG, it) }
      val controlCenter = profiles.controlCenter
      controlCenter.profile?.let(::installControlCenterRefresh)
      controlCenter.unavailableReason?.let { xposed.log(Log.INFO, TAG, it) }
      profile.callers.forEach { xposed.deoptimize(it) }
      xposed.log(Log.INFO, TAG,
        "Dynamic material profile ready: ${profile.themedGate.name} -> ${profile.capabilityGate.name}; " +
          "callers=${profile.callers.size} enabled=${enabled.get()}")
    }
  }

  private fun installControlCenterRefresh(profile: SystemUiControlCenterRefreshProfile) {
    replace(profile.lifecycleEvent, CC_REFRESH_ID, afterNative { chain ->
      val owner = chain.getArg(0) ?: return@afterNative
      when ((chain.getArg(2) as? Enum<*>)?.name) {
        "ON_DESTROY" -> controlCenterRevisions.remove(owner)
        "ON_START" -> {
          val revision = materialRevision.get()
          if (controlCenterRevisions[owner] == revision) return@afterNative
          val lifecycleOwner = profile.lifecycleField.get(owner) ?: return@afterNative
          val state = (profile.lifecycleState.invoke(lifecycleOwner) as? Enum<*>)?.name
          if (state != "STARTED" && state != "RESUMED") return@afterNative
          val repository = profile.materialRepository.get(owner) ?: return@afterNative
          val flow = profile.materialFlow.invoke(repository) ?: return@afterNative
          val mode = profile.materialValue.invoke(flow) ?: return@afterNative
          if (profile.disabledModeClass.isInstance(mode)) return@afterNative
          val children = profile.childControllers.get(owner) as? List<*> ?: return@afterNative
          profile.updateThemeResources.invoke(owner)
          children.filterNotNull().forEach { profile.dispatchMaterialModeChanged.invoke(it, mode) }
          controlCenterRevisions[owner] = revision
          Log.i(TAG, "Control center materials refreshed at native start; revision=$revision")
        }
      }
    })
    val tile = profile.smallTile
    replace(tile.materialChanged, TILE_REFRESH_ID, afterNative { chain ->
      chain.thisObject?.let { owner ->
        tile.icon.invoke(owner)?.let { tileOwners[it] = WeakReference(owner) }
        refreshSmallTile(tile, owner)
      }
    })
    replace(tile.pendingUpdateCompleted, TILE_COMPLETION_ID, afterNative { chain ->
      val icon = chain.getArg(0) ?: return@afterNative
      pendingTiles.remove(icon)?.get()?.let { refreshSmallTile(tile, it) }
    })
    tile.backgroundCache?.let { background ->
      replace(background.updateIconInternal, TILE_BACKGROUND_ID, afterNative { chain ->
        val icon = chain.thisObject ?: return@afterNative
        val owner = tileOwners[icon]?.get() ?: return@afterNative
        if (tile.icon.invoke(owner) !== icon) return@afterNative
        if (prepareTileBackground(tile, icon)) tile.updateResources.invoke(icon)
      })
      background.callers.forEach { xposed.deoptimize(it) }
      xposed.log(Log.INFO, TAG, "Control center real-tile background geometry follows native resources")
    }
    tile.backgroundCacheUnavailableReason?.let { xposed.log(Log.INFO, TAG, it) }
    profile.slider?.let { slider ->
      replace(slider.updateBlendBlur, SLIDER_REFRESH_ID, beforeNative { chain ->
        val owner = chain.thisObject ?: return@beforeNative
        val revision = materialRevision.get()
        if (sliderRevisions[owner] == revision) return@beforeNative
        // Blending removes the fill drawable. Restore native resources first so
        // the original opt-out branch has a fill after it clears the blend.
        slider.updateResources.invoke(owner)
        sliderRevisions[owner] = revision
      })
      slider.callers.forEach { xposed.deoptimize(it) }
      xposed.log(Log.INFO, TAG, "Control center slider resources refresh before native blend changes")
    }
    profile.sliderUnavailableReason?.let { xposed.log(Log.INFO, TAG, it) }
    profile.callers.forEach { xposed.deoptimize(it) }
    xposed.log(Log.INFO, TAG, "Control center materials refresh at the native STARTED boundary")
  }

  private fun refreshSmallTile(profile: ControlCenterSmallTileRefreshProfile, owner: Any) {
    if ((owner as? View)?.isAttachedToWindow != true) return
    val revision = materialRevision.get()
    if (tileRevisions[owner] == revision) return
    val icon = profile.icon.invoke(owner) ?: return
    if (profile.updatePending.getBoolean(icon)) {
      pendingTiles[icon] = WeakReference(owner)
      return
    }
    val state = profile.currentState.get(icon) ?: return
    prepareTileBackground(profile, icon)
    profile.updateResources.invoke(icon)
    profile.updateIcon.invoke(icon, state, false, profile.added.getBoolean(owner), true, false)
    profile.updateEnhancement.invoke(icon)
    tileRevisions[owner] = revision
  }

  private fun prepareTileBackground(profile: ControlCenterSmallTileRefreshProfile, icon: Any): Boolean {
    if (!enabled.get()) return false
    val background = profile.backgroundCache ?: return false
    if (background.enabledBackground.get(icon) is GradientDrawable ||
      background.disabledBackground.get(icon) is GradientDrawable) return false
    val state = profile.currentState.get(icon) ?: return false
    val drawable = background.backgroundForState.invoke(icon, state) as? GradientDrawable ?: return false
    background.setEnabledBackground.invoke(icon, drawable.mutate())
    return true
  }

  /** OEM calls finish first. Only our optional refresh can fail without escaping. */
  private fun afterNative(action: (XposedInterface.Chain) -> Unit): XposedInterface.Hooker {
    val guard = optionalGuard("material refresh")
    return XposedInterface.Hooker { chain ->
      if (!lifecycle.enter()) chain.proceed() else try {
        val result = chain.proceed()
        refreshOnMainThread(guard) { action(chain) }
        result
      } finally { lifecycle.leave() }
    }
  }

  private fun beforeNative(action: (XposedInterface.Chain) -> Unit): XposedInterface.Hooker {
    val guard = optionalGuard("material refresh")
    return XposedInterface.Hooker { chain ->
      if (!lifecycle.enter()) chain.proceed() else try {
        refreshOnMainThread(guard) { action(chain) }
        chain.proceed()
      } finally { lifecycle.leave() }
    }
  }

  private fun refreshOnMainThread(guard: SystemUiHookGuard, action: () -> Unit) {
    if (Looper.myLooper() != Looper.getMainLooper()) return
    guard.run(Unit, action)
  }

  private fun optionalGuard(feature: String) = SystemUiHookGuard { failure ->
    reportOptionalFailure(feature, failure)
  }

  private fun reportOptionalFailure(feature: String, failure: Throwable) {
    // This can run under a framework listener lock. Only local logging/enqueueing here.
    Log.w(TAG, "Optional SystemUI $feature disabled until reload; keeping native behavior", failure)
    if (diagnosticQueue.offer(feature to failure)) flushDiagnostics()
  }

  private fun flushDiagnostics() {
    if (!diagnosticsScheduled.compareAndSet(false, true)) return
    try {
      launchOwned {
        try {
          while (true) {
            val (feature, failure) = diagnosticQueue.poll() ?: break
            try {
              xposed.log(Log.WARN, TAG, "Optional $feature disabled until reload; keeping native behavior", failure)
            } catch (reportFailure: Throwable) {
              recoverableSystemUiFailure(reportFailure)
              Log.w(TAG, "Framework diagnostic delivery unavailable", reportFailure)
            }
          }
        } finally {
          diagnosticsScheduled.set(false)
          if (diagnosticQueue.isNotEmpty()) flushDiagnostics()
        }
      }
    } catch (reportFailure: Throwable) {
      diagnosticsScheduled.set(false)
      recoverableSystemUiFailure(reportFailure)
    }
  }

  private fun installVolumeRefresh(profile: VolumeMaterialRefreshProfile) {
    replace(profile.show, VOLUME_REFRESH_ID, beforeNative { chain ->
      val owner = chain.thisObject ?: return@beforeNative
      val revision = materialRevision.get()
      // The native showH reInit branch waits until the dialog is not animating.
      if (volumeRevisions[owner] != revision) {
        profile.reinitializeFlag.setBoolean(owner, true)
        volumeRevisions[owner] = revision
      }
    })
    xposed.log(Log.INFO, TAG, "Volume materials refresh at the native next-show boundary")
  }

  private fun initializePreferences() {
    if (!preferencesInitializing.compareAndSet(false, true)) return
    try {
      if (preferences != null) return
      // Acquiring the initial remote snapshot may use Binder; hold no module monitor.
      val prefs = xposed.getRemotePreferences(ThemeEffectsPreferences.GROUP)
      prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
      preferences = prefs
      preferenceListener.onSharedPreferenceChanged(prefs, ThemeEffectsPreferences.KEY)
    } finally {
      preferencesInitializing.set(false)
    }
  }

  /** No parsing, provider access or waiting on a configuration/UI callback. */
  private fun launchOwned(action: () -> Unit) {
    if (!lifecycle.enter()) return
    val thread = try {
      Thread({
        try {
          android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
          action()
        } catch (failure: Throwable) {
          recoverableSystemUiFailure(failure)
          Log.w(TAG, "Material compatibility unavailable; keeping native behavior", failure)
        } finally { lifecycle.leave() }
      }, "KeiHomeMaterialProfile").apply { isDaemon = true }
    } catch (failure: Throwable) {
      lifecycle.leave()
      throw failure
    }
    synchronized(workers) {
      workers.removeAll { it.state == Thread.State.TERMINATED }
      workers += thread
    }
    try { thread.start() } catch (failure: Throwable) {
      synchronized(workers) { workers.remove(thread) }
      lifecycle.leave()
      throw failure
    }
  }

  fun retire(saveState: () -> Unit): Boolean {
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6)
    val retired = lifecycle.retire(
      workersStopped = { synchronized(workers) { workers.none { it.isAlive } } },
    ) {
      try {
        check(appIconRuntime?.stop(deadline) != false) {
          "Notification icon runtime has not stopped: ${appIconRuntime?.retirementStatus()}"
        }
        appIconRuntime = null
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        splashPreferences?.unregisterOnSharedPreferenceChangeListener(splashPreferenceListener)
        saveState()
      } catch (failure: Throwable) {
        preferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
        splashPreferences?.registerOnSharedPreferenceChangeListener(splashPreferenceListener)
        throw failure
      }
      preferences = null
      splashPreferences = null
    }
    if (!retired) {
      // A callback rejected during a failed retirement must not leave live settings stale.
      preferences?.let { preferenceListener.onSharedPreferenceChanged(it, null) }
      splashPreferences?.let { splashPreferenceListener.onSharedPreferenceChanged(it, null) }
    }
    return retired
  }

  fun retirementStatus(): String = lifecycle.retirementFailure ?: "cleanup pending"

  private fun replace(method: Method, id: String, hooker: XposedInterface.Hooker) {
    val key = method to id
    val previous = synchronized(handles) {
      if (!installing.add(key)) return
      handles.singleOrNull { it.id == id && it.executable == method }
    }
    try {
      // Hook publication belongs to the framework; do not hold our handle monitor.
      val installed = if (previous == null) xposed.hook(method).setId(id).intercept(hooker)
        else previous.replaceHook(hooker)
      synchronized(handles) {
        handles.remove(previous)
        handles += installed
      }
    } finally {
      synchronized(handles) { installing.remove(key) }
    }
  }

  companion object {
    const val PACKAGE_NAME = "com.android.systemui"
    private const val PLUGIN_PACKAGE = "miui.systemui.plugin"
    private const val FACTORY_CLASS = "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"
    private const val INJECTOR_CLASS = "com.miui.systemui.plugin.PluginInstanceInjector"
    private const val TAG = "KeiHomeSystemUiTheme"
    private const val REFRESH_ID_PREFIX = "keihome.systemui.material."
    private const val LOADER_ID = "keihome.systemui.material.loader.v1"
    private const val MATERIAL_ID = "keihome.systemui.material.gate.v1"
    private const val VOLUME_REFRESH_ID = "keihome.systemui.material.volume-show.v1"
    private const val CC_REFRESH_ID = "keihome.systemui.material.cc-start.v1"
    private const val TILE_REFRESH_ID = "keihome.systemui.material.tile-mode.v1"
    private const val TILE_COMPLETION_ID = "keihome.systemui.material.tile-pending.v1"
    private const val TILE_BACKGROUND_ID = "keihome.systemui.material.tile-background.v1"
    private const val SLIDER_REFRESH_ID = "keihome.systemui.material.slider-blend.v1"

    fun isHostClassLoader(loader: ClassLoader): Boolean =
      runCatching { Class.forName(FACTORY_CLASS, false, loader) }.isSuccess
  }
}

/** Close admission before draining hooks, preference callbacks and owned installation work. */
internal class ThemeCompatibilityLifecycle(
  private val drainTimeoutMillis: Long = 1_000L,
  private val isMainThread: () -> Boolean = {
    Looper.getMainLooper()?.let { it == Looper.myLooper() } == true
  },
) {
  private val lock = java.lang.Object()
  private var active = 0
  private var retiring = false
  private var retired = false
  @Volatile var retirementFailure: String? = null
    private set

  init { require(drainTimeoutMillis >= 0) }

  fun enter(): Boolean = synchronized(lock) {
    if (retiring || retired) false else { active++; true }
  }

  fun leave() = synchronized(lock) {
    check(active > 0)
    active--
    if (active == 0) lock.notifyAll()
  }

  fun retire(workersStopped: () -> Boolean = { true }, saveState: () -> Unit): Boolean {
    synchronized(lock) {
      if (retired) return true
      if (retiring) return false
      retiring = true
      retirementFailure = null
    }
    var complete = false
    try {
      synchronized(lock) {
        if (active != 0) {
          if (isMainThread()) {
            retirementFailure = "activeCallbacks=$active; refusing main-thread wait"
            return false
          }
          val timeoutNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(drainTimeoutMillis)
          val started = System.nanoTime()
          while (active != 0) {
            val remaining = timeoutNanos - (System.nanoTime() - started)
            if (remaining <= 0) {
              retirementFailure = "callback drain timed out; activeCallbacks=$active"
              return false
            }
            // Release admission's monitor so existing callbacks and installs can leave.
            lock.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
          }
        }
      }
      if (!workersStopped()) {
        retirementFailure = "owned worker has not exited"
        return false
      }
      // Framework listener removal can wait for a Binder callback which calls enter().
      // Close admission first, then clean up without holding the admission monitor.
      saveState()
      complete = true
      return true
    } catch (failure: Throwable) {
      recoverableSystemUiFailure(failure)
      retirementFailure = "cleanup ${failure.javaClass.simpleName}: ${failure.message}"
      return false
    } finally {
      synchronized(lock) {
        if (complete) retired = true
        retiring = false
      }
    }
  }
}

