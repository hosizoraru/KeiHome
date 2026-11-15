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
  restartVersions: List<String> = ReleaseNotesCatalog.releases.filter { it.recommendSystemRestart }.map { it.version },
  installationTime: Long = firstInstallTime(context),
) {
  private val storage = AppLocalStorage.open(context, id)
  private val previous = decode(storage.decodeString(KEY)).let { snapshot ->
    runCatching {
      val ledger = JSONObject(storage.decodeString(READ_KEY) ?: return@runCatching snapshot)
      fun versions(key: String): List<String> {
        val list = ledger.optJSONArray(key) ?: return emptyList()
        return List(list.length()) { list.getString(it) }
      }
      snapshot.copy(unreadVersions = versions("unread"), readVersions = versions("read"), readTrackingInitialized = true)
    }.getOrDefault(snapshot)
  }
  private val oldInstallationTime = storage.decodeLong(INSTALL_KEY, 0L)
  private val restoredInstallation = oldInstallationTime > 0 && installationTime > 0 && oldInstallationTime != installationTime
  private val progress = MutableStateFlow(ReleaseNotesPolicy.observe(
    previous, versionName, updatedInstallation && !restoredInstallation &&
      (allowLegacyUpgrade || ReleaseVersion.parse(previous.observedVersion) != null), publishedVersions, restartVersions,
    initialUnreadVersions = ReleaseNotesCatalog.initiallyExpandedVersions(versionName, upgrade = true),
    newInstallation = restoredInstallation,
  ))
  val state = progress.asStateFlow()

  init {
    if (installationTime > 0 && installationTime != oldInstallationTime) check(storage.encode(INSTALL_KEY, installationTime))
    persist(progress.value, installationTime > 0 && installationTime != oldInstallationTime)
  }

  fun markRead(versions: Set<String>) {
    val next = ReleaseNotesPolicy.markRead(progress.value, versions)
    if (next == progress.value) return
    persist(next)
    progress.value = next
  }

  fun acknowledge() {
    val next = ReleaseNotesPolicy.acknowledge(progress.value)
    persist(next)
    progress.value = next
  }

  fun acknowledgeRestart(version: String) {
    val next = ReleaseNotesPolicy.acknowledgeRestart(progress.value, version)
    persist(next)
    progress.value = next
  }

  private fun persist(value: ReleaseNotesState, dirty: Boolean = false) {
    val ledger = JSONObject().put("unread", JSONArray(value.unreadVersions)).put("read", JSONArray(value.readVersions)).toString()
    var changed = dirty
    if (storage.decodeString(READ_KEY) != ledger) {
      check(storage.encode(READ_KEY, ledger))
      changed = true
    }
    val encoded = JSONObject().put("version", value.observedVersion).put("readThrough", value.readThrough)
      .put("pending", JSONArray(value.pendingVersions)).put("pendingVersion", value.pendingVersion)
      .put("restartPending", value.restartReminderVersion).put("restartReadThrough", value.restartReminderReadThrough).toString()
    if (storage.decodeString(KEY) != encoded) {
      check(storage.encode(KEY, encoded))
      changed = true
    }
    if (changed) storage.sync()
  }

  companion object {
    private const val KEY = "snapshot_v1"
    private const val READ_KEY = "read_ledger_v1"
    private const val INSTALL_KEY = "read_installation_v1"
    @Volatile private var instance: ReleaseNotesPreferences? = null

    fun get(context: Context, hasCompletedSetup: Boolean): ReleaseNotesPreferences = instance ?: synchronized(this) {
      instance ?: ReleaseNotesPreferences(context.applicationContext,
        allowLegacyUpgrade = hasCompletedSetup).also { instance = it }
    }

    private fun isUpdatedInstallation(context: Context): Boolean = runCatching {
      val info = context.packageManager.getPackageInfo(context.packageName, 0)
      info.firstInstallTime > 0 && info.lastUpdateTime > info.firstInstallTime
    }.getOrDefault(false)

    private fun firstInstallTime(context: Context): Long = runCatching {
      context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    }.getOrDefault(0L)

    private fun decode(text: String?): ReleaseNotesState = runCatching {
      val json = JSONObject(text ?: return ReleaseNotesState())
      val pending = json.optJSONArray("pending")
      ReleaseNotesState(json.optString("version", ""), json.optString("readThrough", ""),
        List(pending?.length() ?: 0) { pending!!.getString(it) }, json.optString("pendingVersion", ""),
        json.optString("restartPending", ""), json.optString("restartReadThrough", ""))
    }.getOrDefault(ReleaseNotesState())
  }
}
