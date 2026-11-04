package os.kei.hyper.home.status

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class IconPackImportRequest(
  val token: String, val userId: Int, val startedAt: Long, val sourceId: String, val recheck: Long = 0,
  val convertedOrigin: String? = null,
  val autoApply: Boolean = false,
  val preparedPalette: Boolean? = null,
) {
  fun pending(now: Long) = now >= startedAt && now - startedAt <= 600_000L &&
    userId >= 0 && token.matches(Regex("[0-9a-f-]{36}")) && sourceId.isNotBlank()
}

internal data class NativeIconImportReport(
  val token: String, val userId: Int, val sourceId: String,
  val status: String, val localId: String? = null, val title: String? = null, val message: String? = null,
  val convertedReady: Boolean? = null,
  val autoApplyToken: String? = null,
) {
  val imported get() = status in setOf("IMPORTED", "ALREADY_IMPORTED") && !localId.isNullOrEmpty()
  val pending get() = status == "IMPORTING" || status == "UNCERTAIN"
  fun encode(): String = JSONObject().put("token", token).put("user", userId).put("source", sourceId)
    .put("status", status).put("local", localId).put("title", title).put("message", message)
    .put("convertedReady", convertedReady).put("autoApplyToken", autoApplyToken).toString()
  fun bundle() = Bundle().apply {
    putString("token", token); putInt("user", userId); putString("source", sourceId)
    putString("status", status); putString("local", localId); putString("title", title); putString("message", message)
    convertedReady?.let { putBoolean("convertedReady", it) }
    putString("autoApplyToken", autoApplyToken)
  }
  companion object {
    fun decode(raw: String?): NativeIconImportReport? = runCatching {
      val value = JSONObject(raw ?: return null)
      fun optional(key: String) = value.optString(key).takeIf { it.isNotEmpty() && it != "null" }
      NativeIconImportReport(value.getString("token"), value.getInt("user"), value.getString("source"),
        value.getString("status"), optional("local"), optional("title"), optional("message"),
        if (value.has("convertedReady") && !value.isNull("convertedReady")) value.getBoolean("convertedReady") else null,
        optional("autoApplyToken"))
    }.getOrNull()
    fun read(value: Bundle?): NativeIconImportReport? = value?.let {
      NativeIconImportReport(it.getString("token") ?: return null, it.getInt("user", -1),
        it.getString("source") ?: return null, it.getString("status") ?: return null,
        it.getString("local"), it.getString("title"), it.getString("message"),
        if (it.containsKey("convertedReady")) it.getBoolean("convertedReady") else null, it.getString("autoApplyToken"))
    }
  }
}

internal object IconPackImportProtocol {
  const val GROUP = "icon_packs"
  const val REPORT_METHOD = "icon_import"
  private const val TOKEN = "request_token"
  private const val USER = "request_user"
  private const val START = "request_start"
  private const val SOURCE = "request_source"
  private const val RECHECK = "recheck"
  private const val ORIGIN = "converted_origin"
  private val remoteWrites = Mutex()

  fun read(preferences: SharedPreferences?): IconPackImportRequest? {
    val values = preferences?.all ?: return null
    return IconPackImportRequest(values[TOKEN] as? String ?: return null, values[USER] as? Int ?: return null,
      values[START] as? Long ?: return null, values[SOURCE] as? String ?: return null,
      values[RECHECK] as? Long ?: 0, values[ORIGIN] as? String, values["auto_apply"] as? Boolean ?: false)
  }

  @Volatile private var appStore: IconImportLocalStore? = null
  private fun local(context: Context): IconImportLocalStore = appStore ?: synchronized(this) {
    appStore ?: IconImportLocalStore(context.applicationContext).also { appStore = it }
  }

  fun accept(context: Context, extras: Bundle?): Boolean {
    val report = NativeIconImportReport.read(extras) ?: return false
    if (report.userId != Process.myUid() / 100_000) return false
    val mutation = local(context).accept(report)
    // Persistence precedes both publication and release of the original handoff.
    if (mutation.complete) IconPackHandoffs.file(context, report.token).delete()
    return mutation.accepted
  }

  fun associations(context: Context): Map<String, String> = local(context).snapshot().associations
  fun state(context: Context): IconImportLocalState = local(context).snapshot()
  fun states(context: Context) = local(context).states

  fun sources(context: Context): Map<String, String> = local(context).snapshot().allSources

  fun pendingSource(context: Context): String? = local(context).snapshot().pendingSource
  fun lastResult(context: Context): NativeIconImportReport? = local(context).snapshot().result

  suspend fun recheck(context: Context, service: XposedService): Boolean = withContext(Dispatchers.IO) {
    recheckNativeImport(local(context), remoteWrites) { stored ->
      val request = stored.request
      if (request != null) publishRequest(service, request, SystemClock.elapsedRealtime())
      else {
        val remote = service.getRemotePreferences(GROUP)
        val requestOnHost = read(remote)
        check(stored.pendingToken == null || (requestOnHost?.token == stored.pendingToken &&
          requestOnHost.sourceId == stored.pendingSource)) { "Pending import needs its original framework request" }
        remote.edit().putLong(RECHECK, SystemClock.elapsedRealtime()).commit()
      }
    }
  }

  private fun publishRequest(service: XposedService, request: IconPackImportRequest, recheck: Long = 0): Boolean =
    service.getRemotePreferences(GROUP).edit().putString(TOKEN, request.token)
      .putInt(USER, request.userId).putLong(START, request.startedAt)
      .putLong(RECHECK, recheck).putString(SOURCE, request.sourceId).putString(ORIGIN, request.convertedOrigin)
      .putBoolean("auto_apply", request.autoApply).commit()

  suspend fun submit(context: Context, service: XposedService, handoff: IconPackHandoff,
    autoApply: Boolean = false, preparedPalette: Boolean? = null): NativeIconImportReport? = withContext(Dispatchers.IO) {
    val request = IconPackImportRequest(handoff.token, Process.myUid() / 100_000,
      SystemClock.elapsedRealtime(), handoff.sourceId, convertedOrigin = handoff.convertedOrigin, autoApply = autoApply, preparedPalette = preparedPalette)
    submitNativeImport(local(context), request, local(context).states.map { it?.result }, publicationMutex = remoteWrites) { publishRequest(service, request) }
  }

  fun nativeDetail(localId: String): Intent = Intent(Intent.ACTION_VIEW, Uri.Builder()
    .scheme("ViewLocalResource").authority("view.local.resource").fragment(localId).build())
    .setClassName("com.android.thememanager", "com.android.thememanager.module.detail.view.ThemeDetailActivity")
    .putExtra("REQUEST_RESOURCE_CODE", "icons")

  fun takeAutoApply(context: Context, report: NativeIconImportReport): Intent? {
    val token = report.autoApplyToken ?: return null
    if (!report.imported || token != report.token) return null
    val consumed = try { local(context).takeNavigation(report)
    } catch (failure: Exception) {
      android.util.Log.w("KeiHomePackImport", "Automatic navigation receipt is unconfirmed", failure)
      false
    }
    if (!consumed) return null
    return nativeDetail(requireNotNull(report.localId)).putExtra("keihome.AUTO_APPLY_TOKEN", token)
  }
}

/** The local/remote boundary is not an atomic transaction. An uncertain publication retains
 * the original request and handoff; a recheck republishes its identity for journal recovery. */
internal suspend fun submitNativeImport(store: IconImportLocalStore, request: IconPackImportRequest,
  reports: Flow<NativeIconImportReport?>, timeoutMillis: Long = 120_000L,
  publicationMutex: Mutex = Mutex(), publish: suspend () -> Boolean): NativeIconImportReport? {
  val published = publicationMutex.withLock {
    store.begin(request)
    try { publish()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) {
      android.util.Log.w("KeiHomePackImport", "Native submission is unconfirmed", failure)
      false
    }
  }
  val report = if (published) withTimeoutOrNull(timeoutMillis) {
    reports.first { it?.token == request.token && it.userId == request.userId &&
      it.sourceId == request.sourceId && it.status != "IMPORTING" }
  } else null
  return report ?: store.uncertain(request)
}

/** Both snapshot acquisition and the Binder write share the publication owner. Reports may
 * arrive during that call; no store monitor is held across Binder or UI work. */
internal suspend fun recheckNativeImport(store: IconImportLocalStore, publicationMutex: Mutex,
  publish: suspend (IconImportLocalState) -> Boolean): Boolean = try {
  publicationMutex.withLock { publish(store.snapshot()) }
} catch (cancelled: CancellationException) { throw cancelled
} catch (failure: Exception) {
  android.util.Log.w("KeiHomePackImport", "Native operation recheck is unconfirmed", failure)
  false
}
