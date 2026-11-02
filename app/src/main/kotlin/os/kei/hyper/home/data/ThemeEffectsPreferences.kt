package os.kei.hyper.home.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
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
  const val AUTO_ENABLE_HANDLED = "non_default_auto_enable_handled"

  private val mutex = Mutex()
  // RemotePreferences mutates its cache before the Binder commit can fail. Quarantine that
  // cache until a successful retry or a new service supplies a fresh preference instance.
  private enum class WriteOrigin { MANUAL, AUTOMATIC }
  private val unconfirmed = WeakHashMap<SharedPreferences, WriteOrigin>()

  suspend fun read(service: XposedService, dispatcher: CoroutineDispatcher = Dispatchers.IO): Boolean =
    withContext(dispatcher) { read(remote(service)) }

  suspend fun save(service: XposedService, enabled: Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.IO) = withContext(dispatcher) {
    save(remote(service), enabled)
  }

  suspend fun enableForNonDefaultOnce(service: XposedService,
    dispatcher: CoroutineDispatcher = Dispatchers.IO): Boolean = withContext(dispatcher) {
    enableForNonDefaultOnce(remote(service))
  }

  internal suspend fun read(preferences: SharedPreferences): Boolean = mutex.withLock {
    check(!unconfirmed.containsKey(preferences)) { "Remote save is unconfirmed" }
    preferences.getBoolean(KEY, false)
  }

  internal suspend fun save(preferences: SharedPreferences, enabled: Boolean) = mutex.withLock {
    write(preferences, enabled, WriteOrigin.MANUAL)
  }

  /** Called only after system_server has verified that the applied icons are non-default. */
  internal suspend fun enableForNonDefaultOnce(preferences: SharedPreferences): Boolean = mutex.withLock {
    // An automatic retry may recover its own failed commit, but cannot replace a pending manual choice.
    check(unconfirmed[preferences] != WriteOrigin.MANUAL) { "Manual save is unconfirmed" }
    if (!unconfirmed.containsKey(preferences) && preferences.getBoolean(AUTO_ENABLE_HANDLED, false)) return@withLock false
    write(preferences, true, WriteOrigin.AUTOMATIC)
    true
  }

  private fun write(preferences: SharedPreferences, enabled: Boolean, origin: WriteOrigin) {
    unconfirmed[preferences] = origin
    // Both automatic enablement and an explicit user choice consume the one-time suggestion.
    check(preferences.edit().putBoolean(KEY, enabled).putBoolean(AUTO_ENABLE_HANDLED, true).commit()) {
      "Remote save is unconfirmed"
    }
    unconfirmed.remove(preferences)
  }

  private fun remote(service: XposedService): SharedPreferences {
    if (service.apiVersion < 102 || service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) {
      throw UnsupportedOperationException("Framework remote preferences unavailable")
    }
    return service.getRemotePreferences(GROUP)
  }
}
