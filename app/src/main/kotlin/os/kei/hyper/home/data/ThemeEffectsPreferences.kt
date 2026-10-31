package os.kei.hyper.home.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The framework preference is the only persisted choice; a commit is not a runtime ACK. */
internal object ThemeEffectsPreferences {
  const val GROUP = "theme_effects"
  const val KEY = "preserve_system_materials"

  private val mutex = Mutex()
  // RemotePreferences mutates its cache before the Binder commit can fail. Quarantine that
  // cache until a successful retry or a new service supplies a fresh preference instance.
  private val unconfirmed = Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())

  suspend fun read(service: XposedService, dispatcher: CoroutineDispatcher = Dispatchers.IO): Boolean =
    withContext(dispatcher) { read(remote(service)) }

  suspend fun save(service: XposedService, enabled: Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.IO) = withContext(dispatcher) {
    save(remote(service), enabled)
  }

  internal suspend fun read(preferences: SharedPreferences): Boolean = mutex.withLock {
    check(preferences !in unconfirmed) { "Remote save is unconfirmed" }
    preferences.getBoolean(KEY, false)
  }

  internal suspend fun save(preferences: SharedPreferences, enabled: Boolean) = mutex.withLock {
    unconfirmed.add(preferences)
    check(preferences.edit().putBoolean(KEY, enabled).commit()) { "Remote save is unconfirmed" }
    unconfirmed.remove(preferences)
    Unit
  }

  private fun remote(service: XposedService): SharedPreferences {
    if (service.apiVersion < 102 || service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) {
      throw UnsupportedOperationException("Framework remote preferences unavailable")
    }
    return service.getRemotePreferences(GROUP)
  }
}
