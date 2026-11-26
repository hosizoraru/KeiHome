package os.kei.hyper.home.iconpack

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import os.kei.hyper.home.BuildConfig

internal object KeiHomePackVersion {
  private val dateTimeFormat = DateTimeFormatter.ofPattern("yyMMdd.HHmm", Locale.US).withZone(ZoneOffset.UTC)
  private val dateFormat = DateTimeFormatter.ofPattern("yyMMdd", Locale.US).withZone(ZoneOffset.UTC)
  private val placeholders = setOf("1", "1.0", "1.0.0")

  val appVersion: String
    get() = BuildConfig.VERSION_NAME.substringBefore('+').ifBlank { "1.0" }

  fun isPlaceholder(version: String?): Boolean {
    val trimmed = version?.trim()
    return trimmed.isNullOrEmpty() || trimmed in placeholders
  }

  fun format(
    epochMillis: Long = System.currentTimeMillis(),
    revision: String? = null,
    appVersion: String = this.appVersion,
  ): String {
    val instant = Instant.ofEpochMilli(epochMillis.coerceAtLeast(0L))
    val shortRev = revision?.trim()?.filter { it.isLetterOrDigit() }?.take(7)?.takeIf { it.isNotEmpty() }
    return if (shortRev != null) "$appVersion-$shortRev.${dateFormat.format(instant)}"
    else "$appVersion-${dateTimeFormat.format(instant)}"
  }

  fun resolveForExport(
    upstreamVersion: String?,
    installedVersion: String?,
    epochMillis: Long = System.currentTimeMillis(),
  ): String {
    upstreamVersion?.trim()?.takeIf { !isPlaceholder(it) }?.let { return it }
    installedVersion?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return format(epochMillis)
  }
}
