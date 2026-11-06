package os.kei.hyper.home.status

import androidx.compose.runtime.Immutable
import java.util.Base64
import os.kei.hyper.home.data.DockGlassOptions

@Immutable
internal data class IconRefreshReceipt(val callbacks: Int, val completedAtMillis: Long)

internal enum class NativeRuntimeState { READY, UNAVAILABLE, INACTIVE_USER }

internal object NativeRuntimeDiagnostic {
  // Preserve the wire diagnostic so older resident payload reports remain recognizable.
  const val MONET_CAPABILITY_UNAVAILABLE = "native Monet capability unavailable"
}

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
  val dockGlassCornerStyle: Int? = null, // Raw command-8 options; low 3 bits are the corner style.
  val launcherGlassAvailable: Boolean = false,
  val launcherGlassStyle: Int? = null,
) {
  val hasMissingMonetCapability: Boolean
    get() = nativeState == NativeRuntimeState.UNAVAILABLE &&
      detail == NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE

  fun acknowledgesDockGlass(choice: os.kei.hyper.home.data.DockGlassChoice?): Boolean =
    choice != null && dockGlassEnabled == choice.enabled && dockGlassCornerStyle == choice.options &&
      dockGlassPid != null && dockGlassStartTicks != null

  fun acknowledgesLauncherGlass(style: os.kei.hyper.home.data.LauncherGlassStyle?): Boolean =
    style != null && launcherGlassAvailable && nativeState != NativeRuntimeState.INACTIVE_USER &&
      launcherGlassStyle == style.nativeValue

  fun isFresh(now: Long): Boolean = now >= checkedAtElapsed && now - checkedAtElapsed <= FRESHNESS_MS

  fun matches(request: RuntimeStatusRequest, now: Long): Boolean = request.isPending(now) && requestId == request.id &&
    userId == request.userId && checkedAtElapsed >= request.startedAtElapsed && isFresh(now)

  // Schema 6 has 19 pipe-delimited fields; schema 5's fields 0..16 retain their positions.
  // [16] carries raw Dock options, including the legacy default-geometry words 0 and 1.
  // [17] wallpaper glass capability: "0"/"1"; [18] accepted material: "-1" unknown,
  // "0" follow wallpaper, "1" light, "2" dark. Numeric fields stay wire compatible.
  // Capability and ACK are independent of Monet readiness; neither is a painted-frame receipt.
  // Fixed fields keep this small protocol usable in the host tests and in system_server.
  // Only the diagnostic text needs escaping; request IDs are generated UUIDs.
  fun encode(): String = listOf(
    "6", requestId, userId, moduleVersion, checkedAtElapsed, nativeState.name,
    launcherPid ?: 0, launcherStartTicks ?: 0, refresh?.callbacks ?: -1,
    refresh?.completedAtMillis ?: 0,
    Base64.getEncoder().encodeToString(detail.orEmpty().take(400).toByteArray(Charsets.UTF_8)),
    iconTheme?.encode().orEmpty(),
    launcherLabelMode ?: -1,
    dockGlassEnabled?.let { if (it) 1 else 0 } ?: -1,
    dockGlassPid ?: 0, dockGlassStartTicks ?: 0, dockGlassCornerStyle ?: -1,
    if (launcherGlassAvailable) 1 else 0, launcherGlassStyle ?: -1,
  ).joinToString("|")

  companion object {
    const val FRESHNESS_MS = 30_000L

    fun decode(raw: String?): RuntimeStatusReport? = try {
      val fields = raw?.split('|')
      if (fields == null || !((fields.size == 11 && fields[0] == "1") ||
          (fields.size == 12 && fields[0] == "2") || (fields.size == 13 && fields[0] == "3") ||
          (fields.size == 16 && fields[0] == "4") || (fields.size == 17 && fields[0] == "5") ||
          (fields.size == 19 && fields[0] == "6"))) null else {
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
          // v4 had only the existing rounded style; earlier schemas had no Dock ACK.
          dockGlassCornerStyle = if (fields.size == 16 && fields[13] != "-1") 0
            else fields.getOrNull(16)?.toInt()?.takeIf(DockGlassOptions::isValid),
          launcherGlassAvailable = fields.getOrNull(17) == "1",
          launcherGlassStyle = fields.getOrNull(18)?.toInt()?.takeIf { it in 0..2 },
        ).takeIf {
          it.requestId.isNotBlank() && it.userId >= 0 && it.moduleVersion > 0 && it.checkedAtElapsed >= 0 &&
            (fields.getOrNull(11).isNullOrEmpty() || it.iconTheme != null) &&
            (fields.size < 13 || fields[12].toInt() in -1..2) &&
            (fields.size < 16 || (fields[13].toInt() in -1..1 &&
              (if (it.dockGlassEnabled == null) fields[14] == "0" && fields[15] == "0"
              else it.dockGlassPid != null && it.dockGlassStartTicks != null))) &&
            (fields.size < 17 || ((fields[16] == "-1" || DockGlassOptions.isValid(fields[16].toInt())) &&
              (if (it.dockGlassEnabled == null) it.dockGlassCornerStyle == null
              else it.dockGlassCornerStyle != null))) &&
            (fields.size < 19 || (fields[17] in listOf("0", "1") && fields[18].toInt() in -1..2 &&
              (it.launcherGlassAvailable || it.launcherGlassStyle == null))) &&
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
