package os.kei.hyper.home.status

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** App-process logcat archive. Hook-process logs remain in the framework's own log store. */
object AppLogStore {
  enum class Level(val priority: Int) { VERBOSE(2), DEBUG(3), INFO(4), WARN(5), ERROR(6), OFF(7) }

  private const val MAX_FILE_BYTES = 1L * 1024 * 1024
  private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
  private val lock = Any()
  private val logLine = Regex("^\\s*(\\d+\\.\\d+)\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+.*")
  private var started = false
  @Volatile private var activeLevel = Level.INFO

  fun level(context: Context): Level = runCatching {
    Level.valueOf(context.getSharedPreferences("app_logs", Context.MODE_PRIVATE).getString("level", "INFO")!!)
  }.getOrDefault(Level.INFO)

  fun setLevel(context: Context, level: Level) {
    context.getSharedPreferences("app_logs", Context.MODE_PRIVATE).edit().putString("level", level.name).apply()
    activeLevel = level
    record(context, Level.INFO, "Log level set to ${level.name}")
  }

  fun record(context: Context, priority: Level, message: String) {
    if (priority.priority < level(context).priority) return
    val timestamp = System.currentTimeMillis()
    synchronized(lock) {
      val file = File(directory(context), "${timestamp / 3_600_000}.log")
      if (file.length() < MAX_FILE_BYTES) file.appendText("$timestamp\t${priority.name} KeiHome: ${message.replace('\n', ' ')}\n")
    }
  }

  fun start(context: Context) {
    synchronized(lock) { if (started) return; started = true }
    val app = context.applicationContext
    activeLevel = level(app)
    Thread({
      runCatching {
        val process = ProcessBuilder("logcat", "-v", "epoch", "-T", "1", "--pid=${Process.myPid()}")
          .redirectErrorStream(true).start()
        val dir = directory(app)
        synchronized(lock) { prune(dir) }
        var lastHour = -1L
        process.inputStream.bufferedReader().useLines { lines ->
          lines.forEach { line ->
            val match = logLine.matchEntire(line) ?: return@forEach
            val priority = when (match.groupValues[2]) {
              "V" -> 2; "D" -> 3; "I" -> 4; "W" -> 5; else -> 6
            }
            if (priority < activeLevel.priority) return@forEach
            val timestamp = (match.groupValues[1].toDoubleOrNull()?.times(1000))?.toLong() ?: return@forEach
            if (timestamp < System.currentTimeMillis() - RETENTION_MS) return@forEach
            val hour = timestamp / 3_600_000
            val file = File(dir, "$hour.log")
            synchronized(lock) {
              if (hour != lastHour) { prune(dir); lastHour = hour }
              if (file.length() < MAX_FILE_BYTES) file.appendText("$timestamp\t$line\n")
            }
          }
        }
      }.onFailure { android.util.Log.w("KeiHomeLogs", "App log collection unavailable", it) }
    }, "KeiHomeAppLogs").apply { isDaemon = true; start() }
  }

  private fun directory(context: Context) = File(context.filesDir, "app-logs").apply { mkdirs() }
  private fun prune(dir: File) {
    val oldestHour = (System.currentTimeMillis() - RETENTION_MS) / 3_600_000
    dir.listFiles()?.forEach { file ->
      if (file.nameWithoutExtension.toLongOrNull()?.let { it < oldestHour } == true) file.delete()
    }
  }

  /** Returns (byte count, line count) in the selected rolling window. */
  fun stats(context: Context, since: Long, until: Long = System.currentTimeMillis()): Pair<Long, Int> = synchronized(lock) {
    var bytes = 0L
    var lines = 0
    directory(context).listFiles()?.filter { it.extension == "log" }?.forEach { file ->
      file.forEachLine { line ->
        val time = line.substringBefore('\t').toLongOrNull() ?: return@forEachLine
        if (time in since..until) { bytes += line.toByteArray().size + 1; lines++ }
      }
    }
    bytes to lines
  }

  fun clear(context: Context, since: Long, until: Long = System.currentTimeMillis()): Int = synchronized(lock) {
    var removed = 0
    directory(context).listFiles()?.filter { it.extension == "log" }?.forEach { file ->
      val retained = file.readLines().filter { line ->
        val time = line.substringBefore('\t').toLongOrNull()
        val selected = time != null && time in since..until
        if (selected) removed++
        !selected
      }
      if (retained.isEmpty()) file.delete()
      else {
        val replacement = File(file.parentFile, "${file.name}.tmp")
        replacement.writeText(retained.joinToString("\n", postfix = "\n"))
        if (!replacement.renameTo(file)) { file.writeText(replacement.readText()); replacement.delete() }
      }
    }
    removed
  }

  fun export(context: Context, since: Long, until: Long = System.currentTimeMillis()): File = synchronized(lock) {
    val target = File(context.cacheDir, "share/KeiHome-logs.zip")
    target.parentFile?.mkdirs()
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    ZipOutputStream(target.outputStream().buffered()).use { zip ->
      zip.putNextEntry(ZipEntry("README.txt"))
      zip.write("KeiHome app-process logs. Hook-process logs are stored by the framework.\nRange: ${date.format(Date(since))} to ${date.format(Date(until))}\n".toByteArray())
      zip.closeEntry()
      directory(context).listFiles()?.filter { it.extension == "log" }?.sortedBy { it.name }?.forEach { file ->
        val lines = file.readLines().filter { (it.substringBefore('\t').toLongOrNull() ?: 0) in since..until }
        if (lines.isNotEmpty()) {
          zip.putNextEntry(ZipEntry(file.name))
          zip.write(lines.joinToString("\n", postfix = "\n").toByteArray())
          zip.closeEntry()
        }
      }
    }
    target
  }
}
