package os.kei.hyper.home.xposed

import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Context
import android.os.IBinder
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.IdentityHashMap
import java.util.function.Consumer
import os.kei.hyper.home.data.WallpaperDimStrengths
import os.kei.hyper.home.data.wallpaperOverlayDimAlpha

/** Extend the native dispatch for each engine without writing to the platform's shared UID map. */
internal class WallpaperSurfaceDimming(loader: ClassLoader) {
  private val serviceClass = Class.forName("com.android.server.wallpaper.WallpaperManagerService", false, loader)
  private val connection = Class.forName("${serviceClass.name}\$WallpaperConnection", false, loader)
  private val connector = Class.forName("${serviceClass.name}\$DisplayConnector", false, loader)
  private val data = Class.forName("com.android.server.wallpaper.WallpaperData", false, loader)
  private val serviceLock = serviceClass.getDeclaredField("mLock").apply { isAccessible = true }
  private val user = serviceClass.getDeclaredField("mCurrentUserId").apply { isAccessible = true }
  private val which = connector.getDeclaredField("mWhich").apply { isAccessible = true }
  private val token = connector.getDeclaredField("mToken").apply { isAccessible = true }
  private val temporaryDim = connector.getDeclaredField("mTemporaryDimAmount").apply { isAccessible = true }
  private val owner = connector.getDeclaredField("this\$0").apply { isAccessible = true }
  private val linkedConnection = data.getDeclaredField("connection").apply { isAccessible = true }
  private val amount = data.getDeclaredField("mWallpaperDimAmount").apply { isAccessible = true }
  private val activeWallpapers = serviceClass.getDeclaredMethod("getActiveWallpapers").apply { isAccessible = true }
  private val forEach = connection.getDeclaredMethod("forEachDisplayConnector", Consumer::class.java)
    .apply { isAccessible = true }
  private val applyDimming = connector.getDeclaredMethod("applyDimming", Float::class.javaPrimitiveType)
    .apply { isAccessible = true }
  private val attach = connection.getMethod("attachEngine",
    Class.forName("android.service.wallpaper.IWallpaperEngine", false, loader), Int::class.javaPrimitiveType)
  private val keyguardCommand = serviceClass.getDeclaredMethod("dispatchKeyguardCommand", String::class.java)
  private val overlays = WallpaperDimOverlayHost.createOrNull(loader)

  @Volatile private var strengths = WallpaperDimStrengths.Off
  @Volatile private var keyguard: Boolean? = null
  private var applied = false
  private val internalApply = ThreadLocal.withInitial { false }

  fun install(
    replace: (String, Executable, XposedInterface.Hooker) -> Unit,
    request: () -> Unit,
    guard: SystemUiHookGuard,
  ) {
    replace("keihome.wallpaper-surface-dim", applyDimming, XposedInterface.Hooker { chain ->
      val baseline = chain.getArg(0) as Float
      if (overlays != null) {
        // Keep the wallpaper BLAST layer opaque (alpha = 1) for platform/editor captures;
        // let the platform baseline pass through unchanged and sync our black ColorLayer instead.
        return@Hooker chain.proceed().also {
          if (internalApply.get() != true && strengths != WallpaperDimStrengths.Off) request()
        }
      }
      val surface = chain.thisObject
      val adjusted = guard.run(baseline) {
        adjustedAmount(checkNotNull(surface), baseline)
      }
      if (adjusted == baseline) chain.proceed() else chain.proceed(arrayOf(adjusted))
    })
    replace("keihome.wallpaper-engine-dim", attach, XposedInterface.Hooker { chain ->
      chain.proceed().also { if (strengths != WallpaperDimStrengths.Off) request() }
    })
    overlays?.let { host ->
      replace("keihome.wallpaper-bbq-surface", host.setWallpaperBBQSurface, XposedInterface.Hooker { chain ->
        chain.proceed().also { if (strengths != WallpaperDimStrengths.Off) request() }
      })
    }
    replace("keihome.wallpaper-shared-engine-dim", keyguardCommand, XposedInterface.Hooker { chain ->
      val locked = when (chain.getArg(0)) {
        "android.wallpaper.keyguardappearing" -> true
        "android.wallpaper.keyguardgoingaway" -> false
        else -> return@Hooker chain.proceed()
      }
      val dispatched = guard.run(false) {
        updateKeyguard(checkNotNull(chain.thisObject), locked)
        true
      }
      chain.proceed().also { if (!dispatched) request() }
    })
  }

  private fun updateKeyguard(service: Any, locked: Boolean) {
    val host = overlays
    if (host != null) {
      val updates = ArrayList<Pair<IBinder, Float>>()
      synchronized(checkNotNull(serviceLock.get(service))) {
        val previous = keyguard
        keyguard = locked
        val current = strengths
        if (previous == locked || current.home == current.lock || user.getInt(service) != 0) return
        forEachConnector(service) { surface, baseline ->
          if (destination(surface) == SHARED) {
            val engineBaseline = engineBaseline(surface, baseline)
            val next = current.effectiveAmount(locked, engineBaseline)
            if (next != current.effectiveAmount(previous == true, engineBaseline)) {
              (token.get(surface) as? IBinder)?.let { binder ->
                updates += binder to wallpaperOverlayDimAlpha(next, engineBaseline)
              }
            }
          }
        }
      }
      if (updates.isNotEmpty()) host.updateTokens(updates)
      return
    }
    synchronized(checkNotNull(serviceLock.get(service))) {
      val previous = keyguard
      keyguard = locked
      val current = strengths
      if (previous == locked || current.home == current.lock || user.getInt(service) != 0) return
      // Queue brightness on the shared engine before the wallpaper receives its keyguard command.
      forEachConnector(service) { surface, baseline ->
        if (destination(surface) == SHARED) {
          val next = current.effectiveAmount(locked, baseline)
          if (next != current.effectiveAmount(previous == true, baseline)) {
            // The connector hook defers to its argument during guarded work.
            applyDimming.invoke(surface, next)
          }
        }
      }
    }
  }

  private fun forEachConnector(service: Any, action: (Any, Float) -> Unit) {
    for (wallpaper in activeWallpapers.invoke(service) as Array<*>) {
      if (wallpaper == null) continue
      val connection = linkedConnection.get(wallpaper) ?: continue
      val baseline = amount.getFloat(wallpaper)
      forEach.invoke(connection, Consumer<Any> { surface -> action(surface, baseline) })
    }
  }

  fun update(context: Context, service: Any, strengths: WallpaperDimStrengths, force: Boolean) {
    val initialKeyguard = if (keyguard == null && strengths.home != strengths.lock)
      context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked else null
    val host = overlays
    if (host != null) {
      val snapshot = IdentityHashMap<IBinder, Float>()
      var shouldSync = false
      synchronized(checkNotNull(serviceLock.get(service))) {
        if (user.getInt(service) != 0) return
        val previous = this.strengths
        if (applied && previous == strengths && (!force || strengths == WallpaperDimStrengths.Off)) return
        if (keyguard == null && initialKeyguard != null) keyguard = initialKeyguard
        val firstApply = !applied
        this.strengths = strengths
        try {
          internalApply.set(true)
          forEachConnector(service) { surface, baseline ->
            if (firstApply) {
              // Restore engine alpha to platform baseline (clears any dim left by older KeiHome builds).
              applyDimming.invoke(surface, baseline)
            }
            val engineBaseline = engineBaseline(surface, baseline)
            val target = adjustedAmount(surface, engineBaseline, strengths)
            val overlayAlpha = wallpaperOverlayDimAlpha(target, engineBaseline)
            (token.get(surface) as? IBinder)?.let { binder ->
              snapshot[binder] = overlayAlpha
            }
          }
          applied = true
          shouldSync = true
        } catch (failure: Exception) {
          this.strengths = previous
          applied = false
          throw failure
        } finally {
          internalApply.set(false)
        }
      }
      if (shouldSync) host.syncAll(snapshot)
      return
    }
    synchronized(checkNotNull(serviceLock.get(service))) {
      if (user.getInt(service) != 0) return
      val previous = this.strengths
      if (applied && previous == strengths && (!force || strengths == WallpaperDimStrengths.Off)) return
      if (keyguard == null && initialKeyguard != null) keyguard = initialKeyguard
      val reapply = force || !applied
      this.strengths = strengths
      try {
        forEachConnector(service) { surface, baseline ->
          if (reapply || adjustedAmount(surface, baseline, strengths) != adjustedAmount(surface, baseline, previous)) {
            applyDimming.invoke(surface, baseline)
          }
        }
        applied = true
      } catch (failure: Exception) {
        this.strengths = previous
        applied = false
        throw failure
      }
    }
  }

  fun clear() {
    overlays?.clear()
  }

  private fun engineBaseline(surface: Any, wallpaperDimAmount: Float): Float =
    maxOf(wallpaperDimAmount, temporaryDim.getFloat(surface)).coerceIn(0f, 1f)

  private fun adjustedAmount(
    surface: Any,
    baseline: Float,
    current: WallpaperDimStrengths = strengths,
  ): Float {
    if (current == WallpaperDimStrengths.Off || user.getInt(owner.get(surface)) != 0) return baseline
    val lock = when (destination(surface)) {
      WallpaperManager.FLAG_SYSTEM -> false
      WallpaperManager.FLAG_LOCK -> true
      SHARED -> keyguard == true
      else -> return baseline
    }
    return current.effectiveAmount(lock, baseline)
  }

  private fun destination(surface: Any): Int = which.getInt(surface) and SHARED

  private companion object {
    const val SHARED = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
  }
}

/**
 * Attaches an opaque-preserving black ColorLayer child to each wallpaper's BBQ SurfaceControl so
 * Wallpaper BBQ wrapper keeps alpha = 1.0 when captured in isolation (e.g. Lockscreen Editor).
 */
private class WallpaperDimOverlayHost private constructor(
  val setWallpaperBBQSurface: Executable,
  private val getService: java.lang.reflect.Method,
  private val globalLockField: java.lang.reflect.Field,
  private val rootField: java.lang.reflect.Field,
  private val getWindowToken: java.lang.reflect.Method,
  private val asWallpaperToken: java.lang.reflect.Method,
  private val childrenField: java.lang.reflect.Field,
  private val windowStateStubField: java.lang.reflect.Field,
  private val getWallpaperBBQSurface: java.lang.reflect.Method,
  private val makeChildSurface: java.lang.reflect.Method,
  private val setColorLayer: java.lang.reflect.Method,
  private val setColor: java.lang.reflect.Method,
) {
  private class Entry(val parent: SurfaceControl, val dimLayer: SurfaceControl, var alpha: Float)

  private val stateLock = Any()
  private val entries = IdentityHashMap<IBinder, Entry>()

  fun syncAll(targets: Map<IBinder, Float>) {
    synchronized(stateLock) {
      val activeTokens = IdentityHashMap<IBinder, Float>(targets.size)
      for ((binder, alpha) in targets) {
        if (alpha > 0f) activeTokens[binder] = alpha
      }
      if (activeTokens.isEmpty()) {
        clearLocked()
        return
      }
      val resolved = resolveParents(activeTokens.keys)
      val toApply = ArrayList<Pair<SurfaceControl, Float>>()
      val toRelease = ArrayList<SurfaceControl>()
      val iterator = entries.entries.iterator()
      while (iterator.hasNext()) {
        val (binder, existing) = iterator.next()
        val alpha = activeTokens[binder]
        val resolvedParent = resolved[binder]
        if (alpha == null || alpha <= 0f || resolvedParent == null ||
          existing.parent !== resolvedParent.parent || !existing.dimLayer.isValid
        ) {
          toRelease += existing.dimLayer
          iterator.remove()
        }
      }
      for ((binder, alpha) in activeTokens) {
        val parent = resolved[binder] ?: continue
        val existing = entries[binder]
        if (existing != null) {
          if (existing.alpha != alpha) {
            existing.alpha = alpha
            toApply += existing.dimLayer to alpha
          }
        } else {
          val dimLayer = createDimLayer(parent) ?: continue
          entries[binder] = Entry(parent.parent, dimLayer, alpha)
          toApply += dimLayer to alpha
        }
      }
      commit(toApply, toRelease)
    }
  }

  fun updateTokens(updates: List<Pair<IBinder, Float>>) {
    synchronized(stateLock) {
      if (updates.isEmpty()) return
      val needed = ArrayList<IBinder>()
      for ((binder, alpha) in updates) {
        if (alpha > 0f) needed += binder
      }
      val resolved = if (needed.isNotEmpty()) resolveParents(needed) else emptyMap()
      val toApply = ArrayList<Pair<SurfaceControl, Float>>()
      val toRelease = ArrayList<SurfaceControl>()
      for ((binder, alpha) in updates) {
        val existing = entries[binder]
        if (alpha <= 0f) {
          if (existing != null) {
            toRelease += existing.dimLayer
            entries.remove(binder)
          }
          continue
        }
        val parent = resolved[binder]
        if (parent == null) {
          if (existing != null && !existing.dimLayer.isValid) {
            toRelease += existing.dimLayer
            entries.remove(binder)
          }
          continue
        }
        if (existing != null && existing.parent === parent.parent && existing.dimLayer.isValid) {
          if (existing.alpha != alpha) {
            existing.alpha = alpha
            toApply += existing.dimLayer to alpha
          }
        } else {
          if (existing != null) toRelease += existing.dimLayer
          val dimLayer = createDimLayer(parent) ?: continue
          entries[binder] = Entry(parent.parent, dimLayer, alpha)
          toApply += dimLayer to alpha
        }
      }
      commit(toApply, toRelease)
    }
  }

  fun clear() = synchronized(stateLock) {
    clearLocked()
  }

  private fun clearLocked() {
    if (entries.isEmpty()) return
    val toRelease = ArrayList<SurfaceControl>(entries.size)
    for (entry in entries.values) toRelease += entry.dimLayer
    entries.clear()
    commit(emptyList(), toRelease)
  }

  private class ResolvedParent(val tokenContainer: Any, val parent: SurfaceControl)

  private fun resolveParents(tokens: Collection<IBinder>): Map<IBinder, ResolvedParent> {
    if (tokens.isEmpty()) return emptyMap()
    val wm = getService.invoke(null, "window") ?: return emptyMap()
    val globalLock = globalLockField.get(wm) ?: return emptyMap()
    val result = IdentityHashMap<IBinder, ResolvedParent>()
    synchronized(globalLock) {
      val root = rootField.get(wm) ?: return emptyMap()
      for (binder in tokens) {
        val wt = getWindowToken.invoke(root, binder) ?: continue
        val wwt = asWallpaperToken.invoke(wt) ?: continue
        @Suppress("UNCHECKED_CAST")
        val children = childrenField.get(wwt) as? List<Any> ?: continue
        for (i in children.lastIndex downTo 0) {
          val stub = windowStateStubField.get(children[i]) ?: continue
          val bbq = getWallpaperBBQSurface.invoke(stub) as? SurfaceControl ?: continue
          if (bbq.isValid) {
            result[binder] = ResolvedParent(wwt, bbq)
            break
          }
        }
      }
    }
    return result
  }

  private fun createDimLayer(resolved: ResolvedParent): SurfaceControl? {
    val wm = getService.invoke(null, "window") ?: return null
    val globalLock = globalLockField.get(wm) ?: return null
    return synchronized(globalLock) {
      if (!resolved.parent.isValid) return null
      val builder = makeChildSurface.invoke(resolved.tokenContainer, null) as SurfaceControl.Builder
      builder.setParent(resolved.parent)
      setColorLayer.invoke(builder)
      builder.setName(LAYER_NAME).build()
    }
  }

  private fun commit(toApply: List<Pair<SurfaceControl, Float>>, toRelease: List<SurfaceControl>) {
    if (toApply.isEmpty() && toRelease.isEmpty()) return
    SurfaceControl.Transaction().use { tx ->
      var dirty = false
      for ((layer, alpha) in toApply) {
        if (!layer.isValid) continue
        setColor.invoke(tx, layer, BLACK)
        tx.setLayer(layer, DIM_LAYER_Z).setAlpha(layer, alpha).setVisibility(layer, true)
        dirty = true
      }
      for (layer in toRelease) {
        if (!layer.isValid) continue
        tx.setVisibility(layer, false).reparent(layer, null)
        dirty = true
      }
      if (dirty) tx.apply()
    }
    for (layer in toRelease) {
      try {
        layer.release()
      } catch (_: Throwable) {
      }
    }
  }

  companion object {
    private const val LAYER_NAME = "KeiHome wallpaper dim"
    private const val DIM_LAYER_Z = 1
    private val BLACK = floatArrayOf(0f, 0f, 0f)

    private fun findDeclaredMethod(
      type: Class<*>,
      name: String,
      vararg params: Class<*>,
    ): java.lang.reflect.Method {
      var cursor: Class<*>? = type
      while (cursor != null) {
        try {
          return cursor.getDeclaredMethod(name, *params).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
          cursor = cursor.superclass
        }
      }
      throw NoSuchMethodException("${type.name}.$name")
    }

    fun createOrNull(loader: ClassLoader): WallpaperDimOverlayHost? = runCatching {
      val sessionClass = Class.forName("com.android.server.wm.Session", false, loader)
      val iWindowClass = Class.forName("android.view.IWindow", false, loader)
      val setBbq = sessionClass.getMethod("setWallpaperBBQSurface", iWindowClass, SurfaceControl::class.java)
      val serviceManager = Class.forName("android.os.ServiceManager", false, loader)
      val getService = serviceManager.getMethod("getService", String::class.java)
      val wmClass = Class.forName("com.android.server.wm.WindowManagerService", false, loader)
      val globalLock = wmClass.getDeclaredField("mGlobalLock").apply { isAccessible = true }
      val rootField = wmClass.getDeclaredField("mRoot").apply { isAccessible = true }
      val rootClass = Class.forName("com.android.server.wm.RootWindowContainer", false, loader)
      val getWindowToken = findDeclaredMethod(rootClass, "getWindowToken", IBinder::class.java)
      val windowTokenClass = Class.forName("com.android.server.wm.WindowToken", false, loader)
      val asWallpaperToken = findDeclaredMethod(windowTokenClass, "asWallpaperToken")
      val windowContainerClass = Class.forName("com.android.server.wm.WindowContainer", false, loader)
      val childrenField = windowContainerClass.getDeclaredField("mChildren").apply { isAccessible = true }
      val makeChildSurface = findDeclaredMethod(windowContainerClass, "makeChildSurface", windowContainerClass)
      val windowStateClass = Class.forName("com.android.server.wm.WindowState", false, loader)
      val stubField = windowStateClass.getDeclaredField("mWindowStateStub").apply { isAccessible = true }
      val stubClass = Class.forName("com.android.server.wm.WindowStateStub", false, loader)
      val getBbq = stubClass.getMethod("getWallpaperBBQSurface")
      val setColorLayer = SurfaceControl.Builder::class.java.getMethod("setColorLayer")
      val setColor = SurfaceControl.Transaction::class.java.getMethod(
        "setColor", SurfaceControl::class.java, FloatArray::class.java,
      )
      WallpaperDimOverlayHost(
        setWallpaperBBQSurface = setBbq,
        getService = getService,
        globalLockField = globalLock,
        rootField = rootField,
        getWindowToken = getWindowToken,
        asWallpaperToken = asWallpaperToken,
        childrenField = childrenField,
        windowStateStubField = stubField,
        getWallpaperBBQSurface = getBbq,
        makeChildSurface = makeChildSurface,
        setColorLayer = setColorLayer,
        setColor = setColor,
      )
    }.getOrNull()
  }
}
