package os.kei.hyper.home.xposed

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

internal enum class IconRefreshReportBoot {
  SAME_BOOT,
  OTHER_BOOT,
  UNKNOWN,
}

internal sealed interface IconRefreshReportReadResult {
  data object Unknown : IconRefreshReportReadResult
  data object Unavailable : IconRefreshReportReadResult
  data class History(
    val report: IconRefreshHistory,
    val boot: IconRefreshReportBoot,
  ) : IconRefreshReportReadResult
}

internal object IconRefreshReportStore {
  private const val PREFERENCES = "icon_refresh_reports"
  private const val KEY_HISTORY = "history_v1"

  // Call from a background thread. History is never proof that the hook is currently active.
  fun read(context: Context): IconRefreshReportReadResult = try {
    val stored = preferences(context).getString(KEY_HISTORY, null)
      ?: return IconRefreshReportReadResult.Unknown
    val history = decode(stored)
    val boot = bootCount(context)
    val relation = when {
      boot < 0 -> IconRefreshReportBoot.UNKNOWN
      boot == history.bootCount -> IconRefreshReportBoot.SAME_BOOT
      else -> IconRefreshReportBoot.OTHER_BOOT
    }
    IconRefreshReportReadResult.History(history, relation)
  } catch (_: Throwable) {
    IconRefreshReportReadResult.Unavailable
  }

  fun record(context: Context, report: IconRefreshReport): Boolean = try {
    val boot = bootCount(context)
    val now = SystemClock.elapsedRealtimeNanos()
    if (!report.isValid(boot, now)) return false
    val prefs = preferences(context)
    val previous = prefs.getString(KEY_HISTORY, null)?.let(::decode)
    val updated = mergeIconRefreshReport(previous, report, boot, now) ?: return false
    if (updated == previous) return false
    prefs.edit().putString(KEY_HISTORY, encode(updated)).commit()
  } catch (_: Throwable) {
    false
  }

  private fun preferences(context: Context) =
    context.createDeviceProtectedStorageContext()
      .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  private fun bootCount(context: Context): Int =
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

  private fun encode(history: IconRefreshHistory): String = JSONObject().apply {
    put("boot", history.bootCount)
    put("generation", history.generation)
    put("start", history.generationStartedNanos)
    history.hook?.let { put("hook", encodeEvent(it)) }
    put("refreshes", JSONArray().apply { history.refreshes.forEach { put(encodeEvent(it)) } })
  }.toString()

  private fun encodeEvent(report: IconRefreshReport): JSONObject = JSONObject().apply {
    put("sequence", report.sequence)
    put("elapsed", report.completedElapsedNanos)
    put("wall", report.completedWallMillis)
    put("kind", report.kind.name)
    report.callbackCount?.let { put("callbacks", it) }
  }

  private fun decode(raw: String): IconRefreshHistory {
    val root = JSONObject(raw)
    val boot = root.getInt("boot")
    val generation = root.getString("generation")
    val start = root.getLong("start")
    fun event(value: JSONObject): IconRefreshReport = IconRefreshReport(
      bootCount = boot,
      generation = generation,
      generationStartedNanos = start,
      sequence = value.getLong("sequence"),
      completedElapsedNanos = value.getLong("elapsed"),
      completedWallMillis = value.getLong("wall"),
      kind = IconRefreshReportKind.valueOf(value.getString("kind")),
      callbackCount = if (value.has("callbacks")) value.getInt("callbacks") else null,
    ).also { require(it.isValid(boot, Long.MAX_VALUE)) }

    val hook = if (root.has("hook")) event(root.getJSONObject("hook")) else null
    require(hook == null || hook.kind == IconRefreshReportKind.HOOK_INSTALLED ||
      hook.kind == IconRefreshReportKind.HOOK_FAILED)
    val array = root.getJSONArray("refreshes")
    require(array.length() <= MAX_STORED_REFRESH_REPORTS)
    val refreshes = (0 until array.length()).map { event(array.getJSONObject(it)) }
    require(refreshes.all { it.callbackCount != null })
    val sequences = listOfNotNull(hook?.sequence) + refreshes.map { it.sequence }
    require(sequences.toSet().size == sequences.size)
    require(refreshes.zipWithNext().all { (first, second) -> first.sequence < second.sequence })
    require(hook != null || refreshes.isNotEmpty())
    return IconRefreshHistory(boot, generation, start, hook, refreshes)
  }
}
