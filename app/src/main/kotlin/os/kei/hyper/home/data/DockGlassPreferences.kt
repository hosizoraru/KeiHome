package os.kei.hyper.home.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object DockGlassPreferences {
  const val GROUP = ThemeEffectsPreferences.GROUP
  const val KEY = "dock_glass_background"
  private val mutex = Mutex()
  private val unconfirmed = WeakHashMap<SharedPreferences, Boolean>()

  fun read(preferences: SharedPreferences?): Boolean = preferences?.getBoolean(KEY, false) ?: false

  suspend fun read(service: XposedService): Boolean = withContext(Dispatchers.IO) {
    mutex.withLock {
      val preferences = remote(service)
      check(!unconfirmed.containsKey(preferences)) { "Remote save is unconfirmed" }
      read(preferences)
    }
  }

  suspend fun save(service: XposedService, enabled: Boolean) = withContext(Dispatchers.IO) {
    mutex.withLock {
      val preferences = remote(service)
      // RemotePreferences can change its local cache before a failed Binder commit.
      unconfirmed[preferences] = true
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
