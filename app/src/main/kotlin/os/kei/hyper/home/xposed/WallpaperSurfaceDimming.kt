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
      chain.proceed().also {
        val previous = keyguard
        val next = when (chain.getArg(0)) {
          "android.wallpaper.keyguardappearing" -> true
          "android.wallpaper.keyguardgoingaway" -> false
          else -> previous
        }
        keyguard = next
        val current = strengths
        if (previous != next && current.home != current.lock) request()
      }
    })
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
        for (wallpaper in activeWallpapers.invoke(service) as Array<*>) {
          if (wallpaper == null) continue
          val connection = linkedConnection.get(wallpaper) ?: continue
          val baseline = amount.getFloat(wallpaper)
          forEach.invoke(connection, Consumer<Any> { surface ->
            val next = adjustedAmount(surface, baseline, strengths)
            if (reapply || next != adjustedAmount(surface, baseline, previous)) {
              applyDimming.invoke(surface, next)
            }
          })
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
    val lock = when (which.getInt(surface) and (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)) {
      WallpaperManager.FLAG_SYSTEM -> false
      WallpaperManager.FLAG_LOCK -> true
      WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK -> keyguard == true
      else -> return baseline
    }
    return current.effectiveAmount(lock, baseline)
  }
}
