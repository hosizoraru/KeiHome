package os.kei.hyper.home.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class DockGlassCornerStyle(val nativeValue: Int) {
  ROUNDED(0), RECENTS(1);
  companion object {
    fun from(value: Int) = entries.firstOrNull { it.nativeValue == value } ?: RECENTS
  }
}

@androidx.compose.runtime.Immutable
internal data class DockGlassChoice(
  val enabled: Boolean = false,
  val cornerStyle: DockGlassCornerStyle = DockGlassCornerStyle.RECENTS,
)

internal object DockGlassPreferences {
  const val GROUP = ThemeEffectsPreferences.GROUP
  const val KEY = "dock_glass_background"
  const val CORNER_STYLE_KEY = "dock_glass_corner_style"
  private val mutex = Mutex()
  private val unconfirmed = WeakHashMap<SharedPreferences, Boolean>()

  fun read(preferences: SharedPreferences?): Boolean = preferences?.getBoolean(KEY, false) ?: false

  fun readChoice(preferences: SharedPreferences?): DockGlassChoice {
    // One snapshot prevents mixing the enabled/style fields of two committed choices.
    val values = preferences?.all.orEmpty()
    return DockGlassChoice(
      enabled = values[KEY] as? Boolean ?: false,
      cornerStyle = DockGlassCornerStyle.from(values[CORNER_STYLE_KEY] as? Int ?: 1),
    )
  }

  suspend fun read(service: XposedService): DockGlassChoice = withContext(Dispatchers.IO) { readConfirmed(remote(service)) }

  internal suspend fun readConfirmed(preferences: SharedPreferences): DockGlassChoice = withContext(Dispatchers.IO) {
    mutex.withLock {
      check(!unconfirmed.containsKey(preferences)) { "Remote save is unconfirmed" }
      readChoice(preferences)
    }
  }

  suspend fun save(service: XposedService, choice: DockGlassChoice) = withContext(Dispatchers.IO) { save(remote(service), choice) }

  internal suspend fun save(preferences: SharedPreferences, choice: DockGlassChoice) = withContext(Dispatchers.IO) {
    mutex.withLock {
      // RemotePreferences can change its local cache before a failed Binder commit.
      unconfirmed[preferences] = true
      check(preferences.edit().putBoolean(KEY, choice.enabled)
        .putInt(CORNER_STYLE_KEY, choice.cornerStyle.nativeValue).commit()) { "Remote save is unconfirmed" }
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
