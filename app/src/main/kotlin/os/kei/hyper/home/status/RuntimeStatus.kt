package os.kei.hyper.home.status

import androidx.compose.runtime.Immutable
import java.util.Base64

@Immutable
internal data class IconRefreshReceipt(val callbacks: Int, val completedAtMillis: Long)

internal enum class NativeRuntimeState { READY, UNAVAILABLE, INACTIVE_USER }

internal data class RuntimeStatusRequest(
  val id: String, val userId: Int, val startedAtElapsed: Long, val includeIconTheme: Boolean = false,
) {
  fun isPending(now: Long): Boolean = id.isNotBlank() && userId >= 0 && startedAtElapsed >= 0 &&
    now >= startedAtElapsed && now - startedAtElapsed <= REQUEST_LIFETIME_MS

  companion object { const val REQUEST_LIFETIME_MS = 10_000L }
}

/** A nonce-bound observation of the resident native endpoint, not a pixel/rendering receipt. */
@Immutable
internal data class RuntimeStatusReport(
  val requestId: String,
  val userId: Int,
  val moduleVersion: Long,
  val checkedAtElapsed: Long,
  val nativeState: NativeRuntimeState,
  val launcherPid: Int? = null,
  val launcherStartTicks: Long? = null,
  val refresh: IconRefreshReceipt? = null,
  val detail: String? = null,
  val iconTheme: SystemIconThemeReport? = null,
  val launcherLabelMode: Int? = null,
  val dockGlassEnabled: Boolean? = null,
  val dockGlassPid: Int? = null,
  val dockGlassStartTicks: Long? = null,
) {
  fun isFresh(now: Long): Boolean = now >= checkedAtElapsed && now - checkedAtElapsed <= FRESHNESS_MS

  fun matches(request: RuntimeStatusRequest, now: Long): Boolean = request.isPending(now) && requestId == request.id &&
    userId == request.userId && checkedAtElapsed >= request.startedAtElapsed && isFresh(now)

  // Fixed fields keep this small protocol usable in the host tests and in system_server.
  // Only the diagnostic text needs escaping; request IDs are generated UUIDs.
  fun encode(): String = listOf(
    "4", requestId, userId, moduleVersion, checkedAtElapsed, nativeState.name,
    launcherPid ?: 0, launcherStartTicks ?: 0, refresh?.callbacks ?: -1,
    refresh?.completedAtMillis ?: 0,
    Base64.getEncoder().encodeToString(detail.orEmpty().take(400).toByteArray(Charsets.UTF_8)),
    iconTheme?.encode().orEmpty(),
    launcherLabelMode ?: -1,
    dockGlassEnabled?.let { if (it) 1 else 0 } ?: -1,
    dockGlassPid ?: 0, dockGlassStartTicks ?: 0,
  ).joinToString("|")

  companion object {
    const val FRESHNESS_MS = 30_000L

    fun decode(raw: String?): RuntimeStatusReport? = try {
      val fields = raw?.split('|')
      if (fields == null || !((fields.size == 11 && fields[0] == "1") ||
          (fields.size == 12 && fields[0] == "2") || (fields.size == 13 && fields[0] == "3") ||
          (fields.size == 16 && fields[0] == "4"))) null else {
        val state = NativeRuntimeState.valueOf(fields[5])
        val pid = fields[6].toInt().takeIf { it > 0 }
        val start = fields[7].toLong().takeIf { it > 0 }
        val count = fields[8].toInt()
        val refreshedAt = fields[9].toLong()
        RuntimeStatusReport(
          requestId = fields[1], userId = fields[2].toInt(), moduleVersion = fields[3].toLong(),
          checkedAtElapsed = fields[4].toLong(), nativeState = state,
          launcherPid = pid, launcherStartTicks = start,
          refresh = if (count >= 0 && refreshedAt > 0) IconRefreshReceipt(count, refreshedAt) else null,
          detail = String(Base64.getDecoder().decode(fields[10]), Charsets.UTF_8).takeIf { it.isNotEmpty() },
          iconTheme = fields.getOrNull(11)?.takeIf { it.isNotEmpty() }?.let(SystemIconThemeReport::decode),
          launcherLabelMode = fields.getOrNull(12)?.toInt()?.takeIf { it in 0..2 },
          dockGlassEnabled = fields.getOrNull(13)?.toInt()?.takeIf { it in 0..1 }?.let { it == 1 },
          dockGlassPid = fields.getOrNull(14)?.toInt()?.takeIf { it > 0 },
          dockGlassStartTicks = fields.getOrNull(15)?.toLong()?.takeIf { it > 0 },
        ).takeIf {
          it.requestId.isNotBlank() && it.userId >= 0 && it.moduleVersion > 0 && it.checkedAtElapsed >= 0 &&
            (fields.getOrNull(11).isNullOrEmpty() || it.iconTheme != null) &&
            (fields.size < 13 || fields[12].toInt() in -1..2) &&
            (fields.size < 16 || (fields[13].toInt() in -1..1 &&
              (if (it.dockGlassEnabled == null) fields[14] == "0" && fields[15] == "0"
              else it.dockGlassPid != null && it.dockGlassStartTicks != null))) &&
            (state != NativeRuntimeState.READY || (pid != null && start != null))
        }
      }
    } catch (_: IllegalArgumentException) { null }
  }
}

@Immutable
internal data class RuntimeStatusUiState(
  val report: RuntimeStatusReport? = null,
  val checking: Boolean = false,
  val timedOut: Boolean = false,
  val readFailed: Boolean = false,
)
