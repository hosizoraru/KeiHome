package os.kei.hyper.home.data

import android.content.Context
import com.tencent.mmkv.MMKV

/** App-owned UI data only. Framework remote preferences and injected OEM storage keep their contracts. */
internal object AppDisplayStorage {
  private const val MIGRATED = "__keihome_app_display_migrated_v1"
  fun open(context: Context, legacyName: String = "app_display", id: String = "app_display_v1"): MMKV =
    AppLocalStorage.migrate(context, legacyName, id, MIGRATED)
}
