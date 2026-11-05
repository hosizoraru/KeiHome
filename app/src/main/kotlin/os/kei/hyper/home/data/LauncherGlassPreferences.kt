package os.kei.hyper.home.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Wallpaper glass material: follow the wallpaper, or use its light/dark scheme. */
internal enum class LauncherGlassStyle(val nativeValue: Int) {
  SYSTEM(0), LIGHT(1), DARK(2);
  companion object {
    fun from(value: Int) = entries.firstOrNull { it.nativeValue == value } ?: SYSTEM
  }
}

/** The system worker intentionally observes API102 framework remote preferences, not app MMKV. */
internal object LauncherGlassPreferences {
  const val GROUP = ThemeEffectsPreferences.GROUP
  const val KEY = "launcher_glass_style"
  private val mutex = Mutex()
  private val unconfirmed = WeakHashMap<SharedPreferences, Boolean>()

  fun read(preferences: SharedPreferences?): LauncherGlassStyle =
    LauncherGlassStyle.from(preferences?.all?.get(KEY) as? Int ?: 0)

  suspend fun read(service: XposedService): LauncherGlassStyle =
    withContext(Dispatchers.IO) { readConfirmed(remote(service)) }

  internal suspend fun readConfirmed(preferences: SharedPreferences): LauncherGlassStyle =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        check(!unconfirmed.containsKey(preferences)) { "Remote save is unconfirmed" }
        read(preferences)
      }
    }

  suspend fun save(service: XposedService, style: LauncherGlassStyle) =
    withContext(Dispatchers.IO) { save(remote(service), style) }

  internal suspend fun save(preferences: SharedPreferences, style: LauncherGlassStyle) =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        // RemotePreferences may update its cache before a failed Binder commit.
        unconfirmed[preferences] = true
        check(preferences.edit().putInt(KEY, style.nativeValue).commit()) { "Remote save is unconfirmed" }
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
