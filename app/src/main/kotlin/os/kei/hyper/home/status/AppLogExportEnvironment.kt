package os.kei.hyper.home.status

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** An explicit allowlist of public device/build fields; no identifiers or captured logs. */
internal data class AppLogExportEnvironment(
  val marketName: String?,
  val model: String?,
  val codename: String?,
  val systemBuild: String?,
  val systemDisplay: String?,
  val androidRelease: String?,
  val androidSdk: Int,
  val launcherPackage: String,
  val launcherVersionName: String?,
  val launcherVersionCode: Long?,
  val moduleVersionName: String,
  val moduleVersionCode: Int,
  val rustVersion: String? = null,
  val flutterVersion: String? = null,
) {
  fun render(collectedAt: Long, timeZone: TimeZone = TimeZone.getDefault()): String = buildString {
    appendLine("KeiHome export-time environment; may differ from the incident-time environment.")
    appendLine("This is environment metadata, not a module/Hook feature diagnostic trace.")
    fun field(key: String, value: String?) {
      val safe = value?.take(256)?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")?.trim()
      append(key).append('=').appendLine(safe?.takeIf(String::isNotBlank) ?: "unavailable")
    }
    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).apply {
      this.timeZone = timeZone
    }.format(Date(collectedAt))
    field("collected.at", date)
    field("collected.timezone", timeZone.id)
    field("collected.epochMillis", collectedAt.toString())
    field("phone.marketName", marketName)
    field("phone.model", model)
    field("phone.codename", codename)
    field("system.build.incremental", systemBuild)
    field("system.build.display", systemDisplay)
    field("android.version.release", androidRelease)
    field("android.version.sdk", androidSdk.toString())
    field("launcher.package", launcherPackage)
    field("launcher.versionName", launcherVersionName)
    field("launcher.versionCode", launcherVersionCode?.toString())
    field("module.versionName", moduleVersionName)
    field("module.versionCode", moduleVersionCode.toString())
    rustVersion?.takeIf(String::isNotBlank)?.let { field("launcher.runtime.rust", it) }
    flutterVersion?.takeIf(String::isNotBlank)?.let { field("launcher.runtime.flutter", it) }
  }
}
