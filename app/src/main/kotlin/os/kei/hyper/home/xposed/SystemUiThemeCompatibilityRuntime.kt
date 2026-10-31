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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import os.kei.hyper.home.data.ThemeEffectsPreferences

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
  private val scheduled = Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())
  private val handles = mutableListOf<XposedInterface.HookHandle>()
  private val preferenceLock = Any()
  private val enabled = AtomicBoolean()
  private val materialRevision = AtomicLong(1L)
  private val volumeRevisions = WeakHashMap<Any, Long>()
  private val controlCenterRevisions = WeakHashMap<Any, Long>()
  private val tileRevisions = WeakHashMap<Any, Long>()
  private val sliderRevisions = WeakHashMap<Any, Long>()
  private val pendingTiles = WeakHashMap<Any, WeakReference<Any>>()
  private val tileOwners = WeakHashMap<Any, WeakReference<Any>>()
  private val refreshFailureLogged = AtomicBoolean()
  private val restoredLogged = AtomicBoolean()
  private val firstMaterialCall = AtomicBoolean()
  @Volatile private var preferences: SharedPreferences? = null
  private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
    if (key == null || key == ThemeEffectsPreferences.KEY) {
      if (lifecycle.enter()) try {
        val selected = prefs.getBoolean(ThemeEffectsPreferences.KEY, false)
        if (enabled.getAndSet(selected) != selected) materialRevision.incrementAndGet()
        restoredLogged.set(false)
        xposed.log(Log.INFO, TAG, "Preserve system materials: ${enabled.get()}")
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
    oldHooks.filter { it.id?.startsWith(REFRESH_ID_PREFIX) == true && it.id != LOADER_ID }.forEach {
      replace(it.executable as Method, requireNotNull(it.id), XposedInterface.Hooker { chain -> chain.proceed() })
    }
    replace(createLoader, LOADER_ID, XposedInterface.Hooker { chain ->
      if (!lifecycle.enter()) chain.proceed() else try {
        val result = chain.proceed()
        val info = appInfo.get(chain.thisObject) as? ApplicationInfo
        if (result is ClassLoader && info?.packageName == PLUGIN_PACKAGE) {
          scheduleProfile(result, info.sourceDir)
        }
        result
      } finally { lifecycle.leave() }
    })
    launchOwned {
      initializePreferences()
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
      val profile = SystemUiMaterialProfile.resolve(
        loader = loader,
        apkPath = apkPath,
        nativeLibraryCacheDir = application?.codeCacheDir,
      )
      replace(profile.themedGate, MATERIAL_ID, XposedInterface.Hooker { chain ->
        if (!lifecycle.enter()) chain.proceed() else try {
          val original = chain.proceed()
          val inspectFirstCall = firstMaterialCall.compareAndSet(false, true)
          if (!inspectFirstCall && (original != false || !enabled.get())) original else {
            val context = chain.getArg(0) as Context
            val capable = profile.capabilityGate.invoke(null, profile.capabilityArgument(context)) as Boolean
            if (inspectFirstCall) xposed.log(Log.INFO, TAG,
              "Material gate observed: native=$original capability=$capable enabled=${enabled.get()}")
            if (original == false && enabled.get() && capable && restoredLogged.compareAndSet(false, true)) {
              xposed.log(Log.INFO, TAG,
                "Restored theme-limited material; native=false capability=true; theme identity unchanged")
            }
            if (original == false && enabled.get()) capable else original
          }
        } finally { lifecycle.leave() }
      })
      profile.volumeRefresh?.let(::installVolumeRefresh)
      profile.volumeRefreshUnavailableReason?.let { xposed.log(Log.INFO, TAG, it) }
      val controlCenter = SystemUiControlCenterRefreshProfile.resolve(
        loader, apkPath, profile, application?.codeCacheDir,
      )
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
          xposed.log(Log.INFO, TAG, "Control center materials refreshed at native start; revision=$revision")
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
  private fun afterNative(action: (XposedInterface.Chain) -> Unit) = XposedInterface.Hooker { chain ->
    if (!lifecycle.enter()) chain.proceed() else try {
      val result = chain.proceed()
      refreshOnMainThread { action(chain) }
      result
    } finally { lifecycle.leave() }
  }

  private fun beforeNative(action: (XposedInterface.Chain) -> Unit) = XposedInterface.Hooker { chain ->
    if (!lifecycle.enter()) chain.proceed() else try {
      refreshOnMainThread { action(chain) }
      chain.proceed()
    } finally { lifecycle.leave() }
  }

  private fun refreshOnMainThread(action: () -> Unit) {
    if (Looper.myLooper() != Looper.getMainLooper()) return
    try { action() } catch (failure: Exception) {
      if (refreshFailureLogged.compareAndSet(false, true)) {
        xposed.log(Log.WARN, TAG, "Optional material refresh unavailable", failure)
      }
    }
  }

  private fun installVolumeRefresh(profile: VolumeMaterialRefreshProfile) {
    replace(profile.show, VOLUME_REFRESH_ID, XposedInterface.Hooker { chain ->
      if (!lifecycle.enter()) chain.proceed() else try {
        val owner = chain.thisObject
        val revision = materialRevision.get()
        // showH runs on the native main Handler. Its own reInit branch waits
        // until the dialog is neither showing nor animating before rebuilding.
        if (owner != null && volumeRevisions[owner] != revision) {
          try {
            profile.reinitializeFlag.setBoolean(owner, true)
            volumeRevisions[owner] = revision
          } catch (failure: ReflectiveOperationException) {
            xposed.log(Log.WARN, TAG, "Volume material refresh unavailable", failure)
          }
        }
        chain.proceed()
      } finally { lifecycle.leave() }
    })
    xposed.log(Log.INFO, TAG, "Volume materials refresh at the native next-show boundary")
  }

  private fun initializePreferences() = synchronized(preferenceLock) {
    if (preferences != null) return@synchronized
    val prefs = xposed.getRemotePreferences(ThemeEffectsPreferences.GROUP)
    prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    preferences = prefs
    enabled.set(prefs.getBoolean(ThemeEffectsPreferences.KEY, false))
  }

  /** No parsing, provider access or waiting on a configuration/UI callback. */
  private fun launchOwned(action: () -> Unit) {
    if (!lifecycle.enter()) return
    val thread = Thread({
      try { action() }
      catch (failure: Throwable) {
        xposed.log(Log.WARN, TAG, "Material compatibility unavailable; keeping native behavior", failure)
      } finally { lifecycle.leave() }
    }, "KeiHomeMaterialProfile").apply { isDaemon = true }
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

  fun retire(saveState: () -> Unit): Boolean = lifecycle.retire(
    workersStopped = { synchronized(workers) { workers.none { it.isAlive } } },
  ) {
    preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    try { saveState() } catch (failure: Throwable) {
      preferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
      throw failure
    }
    preferences = null
  }

  private fun replace(method: Method, id: String, hooker: XposedInterface.Hooker) {
    synchronized(handles) {
      val previous = handles.singleOrNull { it.id == id && it.executable == method }
      val installed = if (previous == null) xposed.hook(method).setId(id).intercept(hooker)
        else previous.replaceHook(hooker)
      handles.remove(previous)
      handles += installed
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

/** Admission closes only after hooks, preference callbacks and owned workers have drained. */
internal class ThemeCompatibilityLifecycle {
  private val lock = Any()
  private var active = 0
  private var retiring = false
  private var retired = false

  fun enter(): Boolean = synchronized(lock) {
    if (retiring || retired) false else { active++; true }
  }

  fun leave() = synchronized(lock) { check(active > 0); active-- }

  fun retire(workersStopped: () -> Boolean = { true }, saveState: () -> Unit): Boolean = synchronized(lock) {
    if (retired) return@synchronized true
    if (retiring || active != 0 || !workersStopped()) return@synchronized false
    retiring = true
    try {
      saveState()
      retired = true
      true
    } catch (_: Throwable) {
      false
    } finally { retiring = false }
  }
}

