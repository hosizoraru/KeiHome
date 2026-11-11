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

internal enum class SearchGlassStyle(val nativeValue: Int) {
  FOLLOW_DOCK(-1), SYSTEM(0), LIGHT(1), DARK(2);
  companion object {
    fun from(value: Int) = entries.firstOrNull { it.nativeValue == value } ?: FOLLOW_DOCK
  }
}

@androidx.compose.runtime.Immutable
internal data class LauncherGlassChoice(
  val dock: LauncherGlassStyle,
  val search: SearchGlassStyle = SearchGlassStyle.FOLLOW_DOCK,
) {
  val resolvedSearch: LauncherGlassStyle
    get() = if (search == SearchGlassStyle.FOLLOW_DOCK) dock else LauncherGlassStyle.from(search.nativeValue)
  val nativePolicy: Int get() = dock.nativeValue or (resolvedSearch.nativeValue shl 2)
}

/** The system worker intentionally observes API102 framework remote preferences, not app MMKV. */
internal object LauncherGlassPreferences {
  const val GROUP = ThemeEffectsPreferences.GROUP
  const val KEY = "launcher_glass_style"
  const val SEARCH_KEY = "launcher_search_glass_style"
  private val mutex = Mutex()
  private val unconfirmed = WeakHashMap<SharedPreferences, MutableSet<String>>()

  fun read(preferences: SharedPreferences?): LauncherGlassStyle = readChoice(preferences).dock

  fun readChoice(preferences: SharedPreferences?): LauncherGlassChoice = synchronized(unconfirmed) {
    check(!unconfirmed.containsKey(preferences)) { "Remote save is unconfirmed" }
    // Decode both fields from the same snapshot, including the legacy no-search-key case.
    val values = preferences?.all.orEmpty()
    LauncherGlassChoice(
      dock = LauncherGlassStyle.from(values[KEY] as? Int ?: 0),
      search = SearchGlassStyle.from(values[SEARCH_KEY] as? Int ?: -1),
    )
  }

  suspend fun read(service: XposedService): LauncherGlassStyle = readChoice(service).dock

  internal suspend fun readConfirmed(preferences: SharedPreferences): LauncherGlassStyle =
    readChoiceConfirmed(preferences).dock

  suspend fun readChoice(service: XposedService): LauncherGlassChoice =
    withContext(Dispatchers.IO) { readChoiceConfirmed(remote(service)) }

  internal suspend fun readChoiceConfirmed(preferences: SharedPreferences): LauncherGlassChoice =
    withContext(Dispatchers.IO) {
      mutex.withLock { readChoice(preferences) }
    }

  suspend fun save(service: XposedService, style: LauncherGlassStyle) =
    withContext(Dispatchers.IO) { save(remote(service), style) }

  internal suspend fun save(preferences: SharedPreferences, style: LauncherGlassStyle) =
    saveValue(preferences, KEY, style.nativeValue)

  suspend fun saveSearch(service: XposedService, search: SearchGlassStyle) =
    withContext(Dispatchers.IO) { saveSearch(remote(service), search) }

  internal suspend fun saveSearch(preferences: SharedPreferences, search: SearchGlassStyle) =
    saveValue(preferences, SEARCH_KEY, search.nativeValue)

  private suspend fun saveValue(preferences: SharedPreferences, key: String, value: Int) =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        synchronized(unconfirmed) {
          // A successful write to the other field cannot confirm a failed cached write.
          val pending = unconfirmed.getOrPut(preferences) { mutableSetOf() }
          pending.add(key)
          check(preferences.edit().putInt(key, value).commit()) { "Remote save is unconfirmed" }
          pending.remove(key)
          if (pending.isEmpty()) unconfirmed.remove(preferences)
          Unit
        }
      }
    }

  private fun remote(service: XposedService): SharedPreferences {
    if (service.apiVersion < 102 || service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L)
      throw UnsupportedOperationException("Framework remote preferences unavailable")
    return service.getRemotePreferences(GROUP)
  }
}
