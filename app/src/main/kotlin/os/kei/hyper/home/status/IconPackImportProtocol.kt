package os.kei.hyper.home.status

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class IconPackImportRequest(
  val token: String, val userId: Int, val startedAt: Long, val sourceId: String, val recheck: Long = 0,
  val convertedOrigin: String? = null,
) {
  fun pending(now: Long) = now >= startedAt && now - startedAt <= 600_000L &&
    userId >= 0 && token.matches(Regex("[0-9a-f-]{36}")) && sourceId.isNotBlank()
}

internal data class NativeIconImportReport(
  val token: String, val userId: Int, val sourceId: String,
  val status: String, val localId: String? = null, val title: String? = null, val message: String? = null,
  val convertedReady: Boolean? = null,
) {
  val imported get() = status in setOf("IMPORTED", "ALREADY_IMPORTED") && !localId.isNullOrEmpty()
  val pending get() = status == "IMPORTING" || status == "UNCERTAIN"
  fun encode(): String = JSONObject().put("token", token).put("user", userId).put("source", sourceId)
    .put("status", status).put("local", localId).put("title", title).put("message", message)
    .put("convertedReady", convertedReady).toString()
  fun bundle() = Bundle().apply {
    putString("token", token); putInt("user", userId); putString("source", sourceId)
    putString("status", status); putString("local", localId); putString("title", title); putString("message", message)
    convertedReady?.let { putBoolean("convertedReady", it) }
  }
  companion object {
    fun decode(raw: String?): NativeIconImportReport? = runCatching {
      val value = JSONObject(raw ?: return null)
      fun optional(key: String) = value.optString(key).takeIf { it.isNotEmpty() && it != "null" }
      NativeIconImportReport(value.getString("token"), value.getInt("user"), value.getString("source"),
        value.getString("status"), optional("local"), optional("title"), optional("message"),
        if (value.has("convertedReady") && !value.isNull("convertedReady")) value.getBoolean("convertedReady") else null)
    }.getOrNull()
    fun read(value: Bundle?): NativeIconImportReport? = value?.let {
      NativeIconImportReport(it.getString("token") ?: return null, it.getInt("user", -1),
        it.getString("source") ?: return null, it.getString("status") ?: return null,
        it.getString("local"), it.getString("title"), it.getString("message"),
        if (it.containsKey("convertedReady")) it.getBoolean("convertedReady") else null)
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
  private const val ASSOCIATIONS = "native_icon_associations"
  private const val OPERATIONS = "native_icon_operation"
  private const val RECHECK = "recheck"
  private const val ORIGIN = "converted_origin"
  private val mutableReports = MutableStateFlow<NativeIconImportReport?>(null)
  val reports = mutableReports.asStateFlow()

  fun read(preferences: SharedPreferences?): IconPackImportRequest? {
    val values = preferences?.all ?: return null
    return IconPackImportRequest(values[TOKEN] as? String ?: return null, values[USER] as? Int ?: return null,
      values[START] as? Long ?: return null, values[SOURCE] as? String ?: return null,
      values[RECHECK] as? Long ?: 0, values[ORIGIN] as? String)
  }

  fun accept(context: Context, extras: Bundle?): Boolean {
    val report = NativeIconImportReport.read(extras) ?: return false
    if (report.userId != Process.myUid() / 100_000) return false
    if (report.imported) context.getSharedPreferences(ASSOCIATIONS, Context.MODE_PRIVATE).edit()
      .putString(report.sourceId, report.localId).commit()
    val storage = context.getSharedPreferences(OPERATIONS, Context.MODE_PRIVATE)
    val current = storage.getString(TOKEN, null)
    if (current == report.token) {
      storage.edit().putString("result", report.encode()).apply {
        if (!report.pending) { remove(TOKEN); remove(SOURCE) }
      }.commit()
      if (!report.pending) IconPackHandoffs.file(context, report.token).delete()
    }
    mutableReports.value = report
    return true
  }

  fun associations(context: Context): Map<String, String> =
    context.getSharedPreferences(ASSOCIATIONS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
      (v as? String)?.let { k to it }
    }.toMap()

  fun pendingSource(context: Context): String? = context.getSharedPreferences(OPERATIONS, Context.MODE_PRIVATE)
    .getString(SOURCE, null)
  fun lastResult(context: Context): NativeIconImportReport? = NativeIconImportReport.decode(
    context.getSharedPreferences(OPERATIONS, Context.MODE_PRIVATE).getString("result", null))

  suspend fun recheck(service: XposedService) = withContext(Dispatchers.IO) {
    service.getRemotePreferences(GROUP).edit().putLong(RECHECK, SystemClock.elapsedRealtime()).commit()
  }

  suspend fun submit(context: Context, service: XposedService, handoff: IconPackHandoff): NativeIconImportReport? = withContext(Dispatchers.IO) {
    val request = IconPackImportRequest(handoff.token, Process.myUid() / 100_000,
      SystemClock.elapsedRealtime(), handoff.sourceId, convertedOrigin = handoff.convertedOrigin)
    val storage = context.getSharedPreferences(OPERATIONS, Context.MODE_PRIVATE)
    check(storage.edit().putString(TOKEN, request.token).putString(SOURCE, request.sourceId).remove("result").commit())
    val committed = service.getRemotePreferences(GROUP).edit().putString(TOKEN, request.token)
      .putInt(USER, request.userId).putLong(START, request.startedAt)
      .putLong(RECHECK, 0).putString(SOURCE, request.sourceId).putString(ORIGIN, request.convertedOrigin).commit()
    if (!committed) {
      storage.edit().remove(TOKEN).remove(SOURCE).commit()
      error("Could not submit native import")
    }
    reports.first { it?.token == request.token && it.userId == request.userId &&
      it.sourceId == request.sourceId && it.status != "IMPORTING" }
  }

  fun nativeDetail(localId: String): Intent = Intent(Intent.ACTION_VIEW, Uri.Builder()
    .scheme("ViewLocalResource").authority("view.local.resource").fragment(localId).build())
    .setClassName("com.android.thememanager", "com.android.thememanager.module.detail.view.ThemeDetailActivity")
    .putExtra("REQUEST_RESOURCE_CODE", "icons")
}
