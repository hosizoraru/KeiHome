package os.kei.hyper.home.status

import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import io.github.libxposed.service.XposedService
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Remote preferences authenticate the request; a private provider accepts the system reply. */
internal object RuntimeStatusProtocol {
  const val GROUP = "runtime_status"
  const val REQUEST_ID = "request_id"
  private const val REQUEST_USER = "request_user"
  private const val REQUEST_STARTED = "request_started"

  fun readRequest(preferences: SharedPreferences?): RuntimeStatusRequest? {
    val values = preferences?.all ?: return null
    return RuntimeStatusRequest(
      values[REQUEST_ID] as? String ?: return null,
      values[REQUEST_USER] as? Int ?: return null,
      values[REQUEST_STARTED] as? Long ?: return null,
    )
  }

  private fun submit(service: XposedService): RuntimeStatusRequest {
    val request = RuntimeStatusRequest(UUID.randomUUID().toString(), Process.myUid() / 100_000,
      SystemClock.elapsedRealtime())
    check(service.getRemotePreferences(GROUP).edit()
      .putString(REQUEST_ID, request.id)
      .putInt(REQUEST_USER, request.userId)
      .putLong(REQUEST_STARTED, request.startedAtElapsed).commit()) { "Runtime status request failed" }
    return request
  }

  suspend fun query(service: XposedService): RuntimeStatusReport? = withContext(Dispatchers.IO) {
    val deadline = SystemClock.elapsedRealtime() + 6_000L
    val request = submit(service)
    currentCoroutineContext().ensureActive()
    if (SystemClock.elapsedRealtime() >= deadline) return@withContext null
    withTimeoutOrNull((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)) {
      // StateFlow also retains a reply delivered before submission returned.
      RuntimeStatusInbox.reports.first { it?.matches(request, SystemClock.elapsedRealtime()) == true }
    }
  }
}
