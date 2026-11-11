package os.kei.hyper.home.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import os.kei.hyper.home.BuildConfig

internal class ReleaseNotesPreferences(
  context: Context,
  id: String = "release_notes_v1",
  versionName: String = BuildConfig.VERSION_NAME,
  updatedInstallation: Boolean = isUpdatedInstallation(context),
  publishedVersions: List<String> = ReleaseNotesCatalog.releases.map { it.version },
  allowLegacyUpgrade: Boolean = true,
) {
  private val storage = AppLocalStorage.open(context, id)
  private val previous = decode(storage.decodeString(KEY))
  private val progress = MutableStateFlow(ReleaseNotesPolicy.observe(
    previous, versionName, updatedInstallation &&
      (allowLegacyUpgrade || ReleaseVersion.parse(previous.observedVersion) != null), publishedVersions,
  ))
  val state = progress.asStateFlow()

  init { persist(progress.value) }

  fun acknowledge() {
    val next = ReleaseNotesPolicy.acknowledge(progress.value)
    persist(next)
    progress.value = next
  }

  private fun persist(value: ReleaseNotesState) {
    val encoded = JSONObject().put("version", value.observedVersion).put("readThrough", value.readThrough)
      .put("pending", JSONArray(value.pendingVersions)).put("pendingVersion", value.pendingVersion).toString()
    if (storage.decodeString(KEY) != encoded) check(storage.encode(KEY, encoded))
  }

  companion object {
    private const val KEY = "snapshot_v1"
    @Volatile private var instance: ReleaseNotesPreferences? = null

    fun get(context: Context, hasCompletedSetup: Boolean): ReleaseNotesPreferences = instance ?: synchronized(this) {
      instance ?: ReleaseNotesPreferences(context.applicationContext,
        allowLegacyUpgrade = hasCompletedSetup).also { instance = it }
    }

    private fun isUpdatedInstallation(context: Context): Boolean = runCatching {
      val info = context.packageManager.getPackageInfo(context.packageName, 0)
      info.firstInstallTime > 0 && info.lastUpdateTime > info.firstInstallTime
    }.getOrDefault(false)

    private fun decode(text: String?): ReleaseNotesState = runCatching {
      val json = JSONObject(text ?: return ReleaseNotesState())
      val pending = json.optJSONArray("pending")
      ReleaseNotesState(json.optString("version", ""), json.optString("readThrough", ""),
        List(pending?.length() ?: 0) { pending!!.getString(it) }, json.optString("pendingVersion", ""))
    }.getOrDefault(ReleaseNotesState())
  }
}
