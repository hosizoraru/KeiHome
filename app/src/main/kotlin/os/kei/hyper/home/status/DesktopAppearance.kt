package os.kei.hyper.home.status

import androidx.compose.runtime.Immutable

internal enum class DesktopAppearanceAction { INSPECT, REFRESH }
internal enum class AppearanceRefreshResult { REFRESHED, UNSUPPORTED, FAILED, RESTORE_FAILED, UNCONFIRMED }

@Immutable
internal data class DesktopAppearanceReport(
  val requestId: String,
  val userId: Int,
  val checkedAtElapsed: Long,
  val selectedMode: Int?,
  val night: Boolean?,
  val wallpaperDark: Boolean?,
  val homeSubscribed: Boolean?,
  val homeDarkIcons: Boolean?,
  val launcherVersion: Long?,
  val refreshResult: AppearanceRefreshResult? = null,
  val launcherVersionName: String? = null,
  val wallpaperVersionName: String? = null,
  val wallpaperVersionCode: Long? = null,
  val homeListenerCount: Int? = null,
  val wallpaperReconnectActive: Boolean? = null,
) {
  fun matches(request: RuntimeStatusRequest, now: Long): Boolean =
    request.isPending(now) && requestId == request.id && userId == request.userId &&
      checkedAtElapsed in request.startedAtElapsed..now

  fun encode(): String = listOf(
    "2", requestId, userId, checkedAtElapsed, selectedMode ?: "?",
    night.wire(), wallpaperDark.wire(), homeSubscribed.wire(), homeDarkIcons.wire(),
    launcherVersion ?: "?", refreshResult?.name ?: "?",
    launcherVersionName.wireText(), wallpaperVersionName.wireText(),
    wallpaperVersionCode ?: "?", homeListenerCount ?: "?",
    wallpaperReconnectActive.wire(),
  ).joinToString("|")

  fun formatFeedbackSummary(keihomeVersion: String): String {
    fun yn(value: Boolean?, yes: String, no: String) = when (value) {
      true -> yes
      false -> no
      null -> "unknown"
    }
    val modeLabel = when (selectedMode) {
      0 -> "auto(0)"
      1 -> "light(1)"
      2 -> "dark(2)"
      3 -> "scheduled(3)"
      else -> "unknown"
    }
    val launcher = listOfNotNull(
      launcherVersionName?.takeIf { it.isNotBlank() },
      launcherVersion?.let { "($it)" },
    ).joinToString(" ").ifEmpty { "unknown" }
    val wallpaper = listOfNotNull(
      wallpaperVersionName?.takeIf { it.isNotBlank() },
      wallpaperVersionCode?.let { "($it)" },
    ).joinToString(" ").ifEmpty { "unknown" }
    val sub = when {
      homeSubscribed == true && homeListenerCount != null -> "connected($homeListenerCount)"
      homeSubscribed == true -> "connected"
      homeSubscribed == false -> "missing(0)"
      else -> "unknown"
    }
    return buildString {
      appendLine("KeiHome: $keihomeVersion")
      appendLine("Launcher: $launcher")
      appendLine("MiWallpaper: $wallpaper")
      appendLine("SelfHeal: ${yn(wallpaperReconnectActive, "active", "inactive")}")
      appendLine("Subscription: $sub")
      appendLine("WallpaperStatusBarRegion: ${yn(wallpaperDark, "dark", "light")}")
      appendLine("LauncherIconRequest: ${yn(homeDarkIcons, "dark_icons", "light_icons")}")
      appendLine("SystemAppearance: ${yn(night, "dark", "light")} | Mode: $modeLabel")
      refreshResult?.let { appendLine("LastRefresh: ${it.name}") }
    }.trimEnd()
  }

  companion object {
    fun decode(value: String?): DesktopAppearanceReport? = runCatching {
      val p = value?.split('|') ?: return null
      val schema = p.firstOrNull() ?: return null
      val validSize = (schema == "1" && p.size == 11) || (schema == "2" && p.size == 16)
      if (!validSize || !p[1].matches(Regex("[a-zA-Z0-9-]{1,64}"))) return null
      fun bool(index: Int): Boolean? = when (p[index]) {
        "1" -> true
        "0" -> false
        "?" -> null
        else -> error("Invalid boolean")
      }
      fun integer(index: Int): Long? = if (p[index] == "?") null else p[index].toLong()
      fun text(index: Int): String? = p[index].takeUnless { it == "?" || it.isBlank() }
        ?.also { require(it.length <= 96 && '\n' !in it && '\r' !in it) }
      val user = p[2].toInt().also { require(it >= 0) }
      val checked = p[3].toLong().also { require(it >= 0) }
      val mode = integer(4)?.also { require(it in 0..3) }?.toInt()
      val version = integer(9)?.also { require(it > 0) }
      val refresh = p[10].takeUnless { it == "?" }?.let(AppearanceRefreshResult::valueOf)
      if (schema == "1") {
        return@runCatching DesktopAppearanceReport(
          p[1], user, checked, mode, bool(5), bool(6), bool(7), bool(8), version, refresh)
      }
      val wallpaperCode = integer(13)?.also { require(it > 0) }
      val listeners = integer(14)?.also { require(it in 0..64) }?.toInt()
      DesktopAppearanceReport(
        requestId = p[1],
        userId = user,
        checkedAtElapsed = checked,
        selectedMode = mode,
        night = bool(5),
        wallpaperDark = bool(6),
        homeSubscribed = bool(7),
        homeDarkIcons = bool(8),
        launcherVersion = version,
        refreshResult = refresh,
        launcherVersionName = text(11),
        wallpaperVersionName = text(12),
        wallpaperVersionCode = wallpaperCode,
        homeListenerCount = listeners,
        wallpaperReconnectActive = bool(15),
      )
    }.getOrNull()
  }
}

private fun Boolean?.wire(): String = when (this) { true -> "1"; false -> "0"; null -> "?" }

private fun String?.wireText(): String {
  val cleaned = this?.replace('|', ' ')?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.take(96)
  return if (cleaned.isNullOrEmpty()) "?" else cleaned
}

internal data class WallpaperDiagnosticSnapshot(
  val wallpaperDark: Boolean?,
  val homeSubscribed: Boolean?,
  val homeListenerCount: Int?,
)

/** New or incomplete OEM dump formats fail to unknown, rather than implying a healthy state. */
internal object DesktopAppearanceDiagnostics {
  fun wallpaperDetail(dump: String?, userId: Int, homePackage: String): WallpaperDiagnosticSnapshot {
    if (dump == null ||
      !Regex("""(?m)^SERVICE [^\n]*\buser=$userId(?:\s|$)""").containsMatchIn(dump))
      return WallpaperDiagnosticSnapshot(null, null, null)
    val records = dump.lineSequence().filter { it.contains("miuiWallpaperData = MiuiWallpaperData{which=1,") }
      .toList()
    val dark = records.singleOrNull()?.let {
      Regex("""partIsDeep\s*=\{[^}]*\bstatus_bar_rect=(-?\d+)(?:,|\})""").find(it)
        ?.groupValues?.get(1)?.let { value -> when (value) { "1" -> true; "0" -> false; else -> null } }
    }
    val count = Regex("""mWallpaperChangeListeners\.size=(\d+)""").find(dump)
      ?.groupValues?.get(1)?.toIntOrNull()
    val listeners = dump.lineSequence().filter { it.trimStart().startsWith("listener: ") }.toList()
    val homeAliveCount = if (count == null || count != listeners.size) null else listeners.count {
      it.contains("packageName=$homePackage,") && it.contains("binderAlive=true") &&
        Regex("""which=(1|3),""").containsMatchIn(it)
    }
    return WallpaperDiagnosticSnapshot(dark, homeAliveCount?.let { it > 0 }, homeAliveCount)
  }

  fun wallpaper(dump: String?, userId: Int, homePackage: String): Pair<Boolean?, Boolean?> =
    wallpaperDetail(dump, userId, homePackage).let { it.wallpaperDark to it.homeSubscribed }

  fun homeDarkIcons(dump: String?, userId: Int, homePackage: String): Boolean? {
    if (dump == null) return null
    val windows = Regex("""(?m)^  Window #""").split(dump).drop(1).filter {
      it.lineSequence().first().contains(" u$userId $homePackage/") &&
        Regex("""mDisplayId=0\b""").containsMatchIn(it) &&
        Regex("""\bty=BASE_APPLICATION\b""").containsMatchIn(it)
    }
    val attributes = windows.singleOrNull()?.let {
      Regex("""mAttrs=\{[\s\S]*?\n[^\n]*\}""").find(it)?.value
    } ?: return null
    return attributes.contains("LIGHT_STATUS_BAR")
  }
}

/** Owned by the existing system-server worker, independently of the settings page's lifetime. */
internal object DesktopAppearanceRefresh {
  interface Port {
    fun canonicalMode(): Int
    fun currentMode(): Int
    fun applyMode(mode: Int)
    fun settle()
  }

  fun run(port: Port, mayStart: () -> Boolean): AppearanceRefreshResult {
    val original = try { port.canonicalMode() } catch (_: Exception) {
      return AppearanceRefreshResult.UNSUPPORTED
    }
    val originalNight = original and 0x30
    if (originalNight != 0x10 && originalNight != 0x20) return AppearanceRefreshResult.UNSUPPORTED
    val initial = try { port.currentMode() and 0x30 } catch (_: Exception) {
      return AppearanceRefreshResult.UNSUPPORTED
    }
    if (initial != 0x10 && initial != 0x20) return AppearanceRefreshResult.UNSUPPORTED
    if (!mayStart()) return AppearanceRefreshResult.FAILED
    var result = AppearanceRefreshResult.REFRESHED
    var interrupted = false
    try {
      val temporary = (original and 0x30.inv()) or if (originalNight == 0x20) 0x10 else 0x20
      port.applyMode(temporary)
      if ((port.currentMode() and 0x30) != (temporary and 0x30)) result = AppearanceRefreshResult.FAILED
      else port.settle()
    } catch (_: InterruptedException) {
      interrupted = true
      result = AppearanceRefreshResult.FAILED
    } catch (_: Exception) {
      result = AppearanceRefreshResult.FAILED
    } finally {
      interrupted = Thread.interrupted() || interrupted
      try {
        // Re-read the mode service: a user/scheduled change during the pulse takes precedence.
        var restored = false
        repeat(2) {
          var known = true
          val target = try { port.canonicalMode() } catch (_: Exception) { known = false; original }
          try {
            port.applyMode(target)
            val expected = try { port.canonicalMode() } catch (_: Exception) { known = false; target }
            restored = port.currentMode() == expected && known
          } catch (_: Exception) { restored = false }
        }
        if (!restored) result = AppearanceRefreshResult.RESTORE_FAILED
      } catch (_: Exception) {
        result = AppearanceRefreshResult.RESTORE_FAILED
      }
      if (interrupted) Thread.currentThread().interrupt()
    }
    return result
  }
}
