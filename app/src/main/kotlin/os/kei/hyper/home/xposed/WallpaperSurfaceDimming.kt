package os.kei.hyper.home.xposed

import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Context
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.function.Consumer
import os.kei.hyper.home.data.WallpaperDimStrengths

/** Extend the native dispatch for each engine without writing to the platform's shared UID map. */
internal class WallpaperSurfaceDimming(loader: ClassLoader) {
  private val serviceClass = Class.forName("com.android.server.wallpaper.WallpaperManagerService", false, loader)
  private val connection = Class.forName("${serviceClass.name}\$WallpaperConnection", false, loader)
  private val connector = Class.forName("${serviceClass.name}\$DisplayConnector", false, loader)
  private val data = Class.forName("com.android.server.wallpaper.WallpaperData", false, loader)
  private val serviceLock = serviceClass.getDeclaredField("mLock").apply { isAccessible = true }
  private val user = serviceClass.getDeclaredField("mCurrentUserId").apply { isAccessible = true }
  private val which = connector.getDeclaredField("mWhich").apply { isAccessible = true }
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

  @Volatile private var strengths = WallpaperDimStrengths.Off
  @Volatile private var keyguard: Boolean? = null
  private var applied = false

  fun install(
    replace: (String, Executable, XposedInterface.Hooker) -> Unit,
    request: () -> Unit,
    guard: SystemUiHookGuard,
  ) {
    replace("keihome.wallpaper-surface-dim", applyDimming, XposedInterface.Hooker { chain ->
      val baseline = chain.getArg(0) as Float
      val surface = chain.thisObject
      val adjusted = guard.run(baseline) {
        adjustedAmount(checkNotNull(surface), baseline)
      }
      if (adjusted == baseline) chain.proceed() else chain.proceed(arrayOf(adjusted))
    })
    replace("keihome.wallpaper-engine-dim", attach, XposedInterface.Hooker { chain ->
      chain.proceed().also { if (strengths != WallpaperDimStrengths.Off) request() }
    })
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
