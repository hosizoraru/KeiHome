package os.kei.hyper.home.status

import android.content.Context
import android.os.Bundle
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AppearanceRefreshReceipt(val result: AppearanceRefreshResult, val atMillis: Long)

internal object DesktopAppearanceInbox {
  const val REPORT = "desktop_appearance_report"
  const val CLAIM = "desktop_appearance_claim"
  private const val STORE = "desktop_appearance_tools"
  private val mutableReports = MutableStateFlow<DesktopAppearanceReport?>(null)
  val reports = mutableReports.asStateFlow()
  private val mutableLastRefresh = MutableStateFlow<AppearanceRefreshReceipt?>(null)
  val lastRefresh = mutableLastRefresh.asStateFlow()

  fun load(context: Context) {
    val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    val result = prefs.getString("result", null)?.let { value ->
      AppearanceRefreshResult.entries.firstOrNull { it.name == value }
    }
    mutableLastRefresh.value = result?.let { AppearanceRefreshReceipt(it, prefs.getLong("at", 0)) }
  }

  @Synchronized
  fun claim(context: Context, id: String?, userId: Int): Boolean {
    if (userId != Process.myUid() / 100_000 || id == null ||
      !id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) return false
    val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    val claims = prefs.getString("claims", "").orEmpty().split(',').filter { it.isNotBlank() }
    if (id in claims) return false
    val at = System.currentTimeMillis()
    if (!prefs.edit().putString("claims", (claims + id).takeLast(32).joinToString(","))
        .putString("result", AppearanceRefreshResult.UNCONFIRMED.name).putLong("at", at).commit()) return false
    mutableLastRefresh.value = AppearanceRefreshReceipt(AppearanceRefreshResult.UNCONFIRMED, at)
    return true
  }

  @Synchronized
  fun accept(context: Context, report: DesktopAppearanceReport): Boolean {
    if (report.userId != Process.myUid() / 100_000) return false
    report.refreshResult?.let { result ->
      val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
      if (report.requestId !in prefs.getString("claims", "").orEmpty().split(',')) return false
      val at = System.currentTimeMillis()
      if (prefs.edit().putString("result", result.name).putLong("at", at).commit())
        mutableLastRefresh.value = AppearanceRefreshReceipt(result, at)
    }
    mutableReports.value = report
    return true
  }

  fun claimedBundle(id: String, userId: Int): Bundle = Bundle().apply {
    putString("id", id)
    putInt("user", userId)
  }
}
