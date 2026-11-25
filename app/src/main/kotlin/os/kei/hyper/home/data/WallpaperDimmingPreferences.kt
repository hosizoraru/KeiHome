package os.kei.hyper.home.data

import android.content.SharedPreferences
import android.os.Build
import io.github.libxposed.service.XposedService
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val DEFAULT_WALLPAPER_DIM_PERCENT = 25
internal const val COLOR_OS_WALLPAPER_DIM_PERCENT = 24

internal enum class WallpaperDimStyle { HYPER_OS, COLOR_OS }

internal fun supportsLowBrightnessContrast(): Boolean =
  Build.VERSION.SDK_INT >= 37 && Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

internal data class WallpaperDimStrengths(
  val home: Int = DEFAULT_WALLPAPER_DIM_PERCENT,
  val lock: Int = DEFAULT_WALLPAPER_DIM_PERCENT,
  val style: WallpaperDimStyle = WallpaperDimStyle.HYPER_OS,
) {
  fun forScreen(lockScreen: Boolean): Int = when (style) {
    WallpaperDimStyle.HYPER_OS -> if (lockScreen) lock else home
    WallpaperDimStyle.COLOR_OS -> COLOR_OS_WALLPAPER_DIM_PERCENT
  }

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
  private const val STYLE = "dim_style"
  const val LOW_CONTRAST = "low_brightness_contrast"
  private val mutex = Mutex()
  private val unconfirmed = WeakHashMap<SharedPreferences, Unit>()
  private val unconfirmedStrengths = WeakHashMap<SharedPreferences, Unit>()
  private val unconfirmedContrast = WeakHashMap<SharedPreferences, Unit>()

  fun enabled(preferences: SharedPreferences): Boolean = preferences.getBoolean(KEY, false)

  fun affectsDimming(key: String?): Boolean =
    key == null || key == KEY || key == HOME || key == LOCK || key == STYLE

  fun strengths(preferences: SharedPreferences) = WallpaperDimStrengths(
    preferences.getInt(HOME, DEFAULT_WALLPAPER_DIM_PERCENT).coerceIn(0, 100),
    preferences.getInt(LOCK, DEFAULT_WALLPAPER_DIM_PERCENT).coerceIn(0, 100),
    WallpaperDimStyle.entries.firstOrNull { it.name == preferences.getString(STYLE, null) }
      ?: WallpaperDimStyle.HYPER_OS,
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
        check(preferences.edit().putInt(HOME, strengths.home).putInt(LOCK, strengths.lock)
          .putString(STYLE, strengths.style.name).commit()) {
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

  suspend fun readContrast(service: XposedService): Boolean = withContext(Dispatchers.IO) {
    mutex.withLock {
      val preferences = remote(service)
      check(preferences !in unconfirmedContrast) { "Remote contrast save is unconfirmed" }
      preferences.getBoolean(LOW_CONTRAST, false)
    }
  }

  suspend fun saveContrast(service: XposedService, enabled: Boolean) = withContext(Dispatchers.IO) {
    mutex.withLock {
      val preferences = remote(service)
      unconfirmedContrast[preferences] = Unit
      check(preferences.edit().putBoolean(LOW_CONTRAST, enabled).commit()) {
        "Remote contrast save is unconfirmed"
      }
      unconfirmedContrast.remove(preferences)
      Unit
    }
  }

  private fun remote(service: XposedService): SharedPreferences {
    if (service.apiVersion < 102 || service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L)
      throw UnsupportedOperationException("Framework remote preferences unavailable")
    return service.getRemotePreferences(GROUP)
  }
}
