package os.kei.hyper.home.data

import android.content.Context
import com.tencent.mmkv.MMKV
import os.kei.hyper.home.BuildConfig

/** App-owned UI data only. Framework remote preferences and injected OEM storage keep their contracts. */
internal object AppDisplayStorage {
  private const val MIGRATED = "__keihome_app_display_migrated_v1"
  @Volatile private var initialized = false

  fun open(context: Context, legacyName: String = "app_display", id: String = "app_display_v1"): MMKV {
    val app = context.applicationContext
    check(app.packageName == BuildConfig.APPLICATION_ID) { "UI storage must belong to KeiHome" }
    if (!initialized) synchronized(this) {
      if (!initialized) {
        MMKV.initialize(app)
        initialized = true
      }
    }
    return synchronized(this) {
      val store = MMKV.mmkvWithID(id, MMKV.SINGLE_PROCESS_MODE)
      if (!store.getBoolean(MIGRATED, false)) {
        val legacy = app.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val original = legacy.all
        check(store.importFromSharedPreferences(legacy).toLong() == original.size.toLong()) {
          "Incomplete UI preference migration"
        }
        original.forEach { (key, value) ->
          val imported = when (value) {
            is Boolean -> store.getBoolean(key, !value)
            is Int -> store.getInt(key, value.inv())
            is Long -> store.getLong(key, value.inv())
            is Float -> store.getFloat(key, Float.NaN)
            is String -> store.getString(key, null)
            is Set<*> -> store.getStringSet(key, null)
            else -> error("Unsupported UI preference: $key")
          }
          check(store.contains(key) && imported == value) { "UI preference migration failed: $key" }
        }
        store.sync()
        check(store.encode(MIGRATED, true))
        // Completion is durable before the App can expose or change the migrated values.
        store.sync()
      }
      store
    }
  }
}
