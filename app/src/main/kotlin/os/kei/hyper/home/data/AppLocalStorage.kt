package os.kei.hyper.home.data

import android.content.Context
import android.os.Process
import com.tencent.mmkv.MMKV
import os.kei.hyper.home.BuildConfig

/** Single App process only. A SharedPreferences-shaped API102 proxy is a separate IPC contract. */
internal object AppLocalStorage {
  @Volatile private var initialized = false

  @Synchronized
  fun open(context: Context, id: String): MMKV {
    val app = context.applicationContext
    check(app.packageName == BuildConfig.APPLICATION_ID && Process.myUid() == app.applicationInfo.uid) {
      "Local storage must belong to the KeiHome App process"
    }
    require(id.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
    if (!initialized) {
      MMKV.initialize(app)
      initialized = true
    }
    return MMKV.mmkvWithID(id, MMKV.SINGLE_PROCESS_MODE)
  }

  /** Simple typed preferences only. Correlated import state uses a single serialized snapshot. */
  @Synchronized
  fun migrate(context: Context, legacyName: String, id: String,
    marker: String = "__keihome_migrated_v1"): MMKV {
    val store = open(context, id)
    if (!store.getBoolean(marker, false)) {
      val legacy = context.applicationContext.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
      val original = legacy.all
      check(store.importFromSharedPreferences(legacy).toLong() == original.size.toLong()) {
        "Incomplete local preference migration: $legacyName"
      }
      original.forEach { (key, value) ->
        val migrated = when (value) {
          is Boolean -> store.getBoolean(key, !value)
          is Int -> store.getInt(key, value.inv())
          is Long -> store.getLong(key, value.inv())
          is Float -> store.getFloat(key, Float.NaN)
          is String -> store.getString(key, null)
          is Set<*> -> store.getStringSet(key, null)
          else -> error("Unsupported local preference: $key")
        }
        check(store.contains(key) && migrated == value) { "Local preference migration failed: $key" }
      }
      store.sync()
      check(store.encode(marker, true)) { "Cannot finish local preference migration" }
      store.sync()
    }
    return store
  }
}
