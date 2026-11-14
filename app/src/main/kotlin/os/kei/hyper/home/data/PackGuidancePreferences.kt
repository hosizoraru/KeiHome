package os.kei.hyper.home.data

import android.content.Context
import com.tencent.mmkv.MMKV

internal enum class PackGuidancePage(val storageKey: String) {
  INSTALLED("installed"),
  IMPORTED("imported"),
  COLOROS("coloros"),
  APPLE("apple"),
  APPLE_MAPPINGS("apple-mappings"),
  APPLE_MATCHED("apple-matched"),
  APPLE_SEARCH("apple-search"),
  PERFECT("perfect"),
}

/** Guidance revisions change only when the guidance changes, never for a QA build. */
internal class PackGuidancePreferences(
  private val readRevision: (String) -> Int,
  private val writeRevision: (String, Int) -> Boolean,
) {
  constructor(context: Context, id: String = STORAGE_ID) : this(AppLocalStorage.open(context, id))

  private constructor(storage: MMKV) : this(
    readRevision = { storage.decodeInt(it, 0) },
    writeRevision = { key, revision ->
      storage.encode(key, revision).also { if (it) storage.sync() }
    },
  )

  fun hasSeen(page: PackGuidancePage, revision: Int = CURRENT_REVISION): Boolean =
    readRevision(page.storageKey) >= revision

  fun markSeen(page: PackGuidancePage, revision: Int = CURRENT_REVISION) {
    require(revision > 0)
    if (!hasSeen(page, revision)) check(writeRevision(page.storageKey, revision)) {
      "Cannot save icon pack guidance progress"
    }
  }

  companion object {
    // Deliberate guidance update for the always-compact help-sheet rollout.
    const val CURRENT_REVISION = 20261114
    private const val STORAGE_ID = "pack_guidance_v1"
    @Volatile private var instance: PackGuidancePreferences? = null

    fun get(context: Context): PackGuidancePreferences = instance ?: synchronized(this) {
      instance ?: PackGuidancePreferences(context.applicationContext).also { instance = it }
    }
  }
}
