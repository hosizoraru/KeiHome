package os.kei.hyper.home.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val DEFAULT_WALLPAPER_DIM_PERCENT = 25

internal data class WallpaperDimStrengths(
  val home: Int = DEFAULT_WALLPAPER_DIM_PERCENT,
  val lock: Int = DEFAULT_WALLPAPER_DIM_PERCENT,
) {
  fun forScreen(lockScreen: Boolean): Int = if (lockScreen) lock else home

  fun effectiveAmount(lockScreen: Boolean, systemAmount: Float): Float =
    maxOf(systemAmount, forScreen(lockScreen) / 100f)

  companion object {
    val Off = WallpaperDimStrengths(0, 0)
  }
}

internal object WallpaperDimmingPreferences {
  const val GROUP = "wallpaper_dimming"
  const val KEY = "follow_dark_mode"
  private const val HOME = "home_percent"
  private const val LOCK = "lock_percent"
  private val mutex = Mutex()
  private val unconfirmed = WeakHashMap<SharedPreferences, Unit>()
  private val unconfirmedStrengths = WeakHashMap<SharedPreferences, Unit>()

  fun enabled(preferences: SharedPreferences): Boolean = preferences.getBoolean(KEY, false)

  fun affectsDimming(key: String?): Boolean = key == null || key == KEY || key == HOME || key == LOCK

  fun strengths(preferences: SharedPreferences) = WallpaperDimStrengths(
    preferences.getInt(HOME, DEFAULT_WALLPAPER_DIM_PERCENT).coerceIn(0, 100),
    preferences.getInt(LOCK, DEFAULT_WALLPAPER_DIM_PERCENT).coerceIn(0, 100),
  )

  suspend fun readStrengths(service: XposedService): WallpaperDimStrengths = withContext(Dispatchers.IO) {
    mutex.withLock {
      val preferences = remote(service)
      check(preferences !in unconfirmedStrengths) { "Remote wallpaper strength save is unconfirmed" }
      strengths(preferences)
    }
  }

  suspend fun saveStrengths(service: XposedService, strengths: WallpaperDimStrengths) =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val preferences = remote(service)
        unconfirmedStrengths[preferences] = Unit
        check(preferences.edit().putInt(HOME, strengths.home).putInt(LOCK, strengths.lock).commit()) {
          "Remote wallpaper strength save is unconfirmed"
        }
        unconfirmedStrengths.remove(preferences)
        Unit
      }
    }

  suspend fun read(service: XposedService): Boolean = withContext(Dispatchers.IO) {
    mutex.withLock {
      val preferences = remote(service)
      check(preferences !in unconfirmed) { "Remote save is unconfirmed" }
      enabled(preferences)
    }
  }

  suspend fun save(service: XposedService, enabled: Boolean) = withContext(Dispatchers.IO) {
    mutex.withLock {
      val preferences = remote(service)
      unconfirmed[preferences] = Unit
      check(preferences.edit().putBoolean(KEY, enabled).commit()) { "Remote save is unconfirmed" }
      unconfirmed.remove(preferences)
      Unit
    }
  }

  private fun remote(service: XposedService): SharedPreferences {
    if (service.apiVersion < 102 || service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L)
      throw UnsupportedOperationException("Framework remote preferences unavailable")
    return service.getRemotePreferences(GROUP)
  }
}
