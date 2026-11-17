package os.kei.hyper.home.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object SplashAppearancePreferences {
  const val GROUP = "splash_appearance"
  const val KEY = "adapt_neutral_background"
  const val DEFAULT = true
  const val ICON_KEY = "fill_missing_icon"
  const val ICON_DEFAULT = false
  private val mutex = Mutex()
  private val unconfirmed = Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())

  suspend fun read(service: XposedService): Boolean = withContext(Dispatchers.IO) {
    mutex.withLock {
      val prefs = remote(service)
      check(prefs !in unconfirmed) { "Remote save is unconfirmed" }
      prefs.getBoolean(KEY, DEFAULT)
    }
  }

  suspend fun save(service: XposedService, enabled: Boolean) = withContext(Dispatchers.IO) {
    mutex.withLock {
      val prefs = remote(service)
      unconfirmed.add(prefs)
      check(prefs.edit().putBoolean(KEY, enabled).commit()) { "Remote save is unconfirmed" }
      unconfirmed.remove(prefs)
      Unit
    }
  }

  suspend fun readIcons(service: XposedService): Boolean = withContext(Dispatchers.IO) {
    mutex.withLock {
      val prefs = remote(service)
      check(prefs !in unconfirmed) { "Remote save is unconfirmed" }
      prefs.getBoolean(ICON_KEY, ICON_DEFAULT)
    }
  }

  suspend fun saveIcons(service: XposedService, enabled: Boolean) = withContext(Dispatchers.IO) {
    mutex.withLock {
      val prefs = remote(service)
      unconfirmed.add(prefs)
      check(prefs.edit().putBoolean(ICON_KEY, enabled).commit()) { "Remote save is unconfirmed" }
      unconfirmed.remove(prefs)
    }
  }

  private fun remote(service: XposedService): SharedPreferences {
    if (service.apiVersion < 102 || service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) {
      throw UnsupportedOperationException("Framework remote preferences unavailable")
    }
    return service.getRemotePreferences(GROUP)
  }
}
