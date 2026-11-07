package os.kei.hyper.home.status

import android.content.Context
import android.os.Build
import android.os.Process
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.ui.main.loadAboutDeviceDetails
import java.io.File
import java.util.TimeZone

/** App-process logcat archive. Hook-process logs remain in the framework's own log store. */
object AppLogStore {
  enum class Level(val priority: Int) { VERBOSE(2), DEBUG(3), INFO(4), WARN(5), ERROR(6), OFF(7) }

  private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
  private val exports = AppLogExportCache(retentionMillis = RETENTION_MS)
  private val lock = Any()
  private val logLine = Regex("^\\s*(\\d+\\.\\d+)\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+.*")
  private var started = false
  private val archives = mutableMapOf<String, HourlyAppLogArchive>()
  @Volatile private var activeLevel = Level.INFO

  fun level(context: Context): Level = runCatching {
    Level.valueOf(AppLocalStorage.migrate(context, "app_logs", "app_logs_v1").getString("level", "INFO")!!)
  }.getOrDefault(Level.INFO)

  fun setLevel(context: Context, level: Level) {
    synchronized(lock) {
      AppLocalStorage.migrate(context, "app_logs", "app_logs_v1").let { store ->
        check(store.encode("level", level.name)) { "Could not save log level" }
        store.sync()
      }
      activeLevel = level
    }
    record(context, Level.INFO, "Log level set to ${level.name}")
  }

  fun record(context: Context, priority: Level, message: String) {
    if (priority.priority < level(context).priority) return
    val timestamp = System.currentTimeMillis()
    runCatching {
      archive(context).append(timestamp, "${priority.name} KeiHome: ${message.replace('\n', ' ').replace('\r', ' ')}")
    }.onFailure { android.util.Log.w("KeiHomeLogs", "Could not append App log", it) }
  }

  fun start(context: Context) {
    synchronized(lock) { if (started) return; started = true }
    val app = context.applicationContext
    activeLevel = level(app)
    Thread({
      runCatching {
        val process = ProcessBuilder("logcat", "-v", "epoch", "-T", "1", "--pid=${Process.myPid()}")
          .redirectErrorStream(true).start()
        val archive = archive(app)
        try { process.inputStream.bufferedReader().useLines { lines ->
          lines.forEach { line ->
            val match = logLine.matchEntire(line) ?: return@forEach
            val priority = when (match.groupValues[2]) {
              "V" -> 2; "D" -> 3; "I" -> 4; "W" -> 5; else -> 6
            }
            if (priority < activeLevel.priority) return@forEach
            val timestamp = (match.groupValues[1].toDoubleOrNull()?.times(1000))?.toLong() ?: return@forEach
            if (timestamp < System.currentTimeMillis() - RETENTION_MS) return@forEach
            archive.append(timestamp, line.replace('\r', ' '))
          }
        } } finally { process.destroy() }
      }.onFailure { android.util.Log.w("KeiHomeLogs", "App log collection unavailable", it) }
    }, "KeiHomeAppLogs").apply { isDaemon = true; start() }
  }

  private fun archive(context: Context): HourlyAppLogArchive = synchronized(lock) {
    val directory = File(context.filesDir, "app-logs").canonicalFile
    archives.getOrPut(directory.path) { HourlyAppLogArchive(directory) }
  }

  /** Releases an App-owned test archive; production keeps one writer for its process lifetime. */
  internal fun closeArchive(context: Context) = synchronized(lock) {
    archives.remove(File(context.filesDir, "app-logs").canonicalPath)?.close()
  }

  fun stats(context: Context, since: Long, until: Long = System.currentTimeMillis()): Pair<Long, Int> =
    archive(context).stats(since, until)

  fun clear(context: Context, since: Long, until: Long = System.currentTimeMillis()): Int =
    archive(context).clear(since, until)

  /** Runs on IO. Each returned ZIP keeps its own name and contents for subsequent sharing. */
  fun export(context: Context, since: Long, until: Long = System.currentTimeMillis()): File {
    val archive = archive(context)
    return exports.export(File(context.cacheDir, "share"), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) {
      archive.export(since, until, it, exportEnvironment(context))
    }
  }

  /** Reuses About's property/one bounded Flutter asset probe, exclusively on the export IO path. */
  private fun exportEnvironment(context: Context): String {
    val collectedAt = System.currentTimeMillis()
    val timeZone = TimeZone.getDefault()
    val details = runCatching { loadAboutDeviceDetails(context) }.getOrNull()
    val launcherPackage = "com.miui.home"
    val launcher = runCatching { context.packageManager.getPackageInfo(launcherPackage, 0) }.getOrNull()
    return AppLogExportEnvironment(
      marketName = details?.marketName ?: Build.MODEL,
      model = Build.MODEL,
      codename = Build.DEVICE,
      systemBuild = Build.VERSION.INCREMENTAL,
      systemDisplay = Build.DISPLAY,
      androidRelease = Build.VERSION.RELEASE,
      androidSdk = Build.VERSION.SDK_INT,
      launcherPackage = launcherPackage,
      launcherVersionName = launcher?.versionName,
      launcherVersionCode = launcher?.longVersionCode,
      moduleVersionName = BuildConfig.VERSION_NAME,
      moduleVersionCode = BuildConfig.VERSION_CODE,
      rustVersion = details?.rustVersion,
      flutterVersion = details?.flutterVersion,
    ).render(collectedAt, timeZone)
  }
}
