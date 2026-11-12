package os.kei.hyper.home.status

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One app-process owner. Call on IO; recent shares and active writers are never age-pruned. */
internal class AppLogExportCache(
  private val retentionMillis: Long,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private val active = mutableSetOf<File>()

  init {
    require(retentionMillis > 0) { "Export retention must be positive" }
  }

  /** Reserves a unique path, then lets the archive atomically publish its completed ZIP there. */
  fun export(directory: File, versionName: String, versionCode: Int, write: (File) -> Unit): File {
    val target = synchronized(lock) {
      val parent = directory.canonicalFile
      if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create $parent")
      val now = clock()
      prune(parent, now)
      val name = appLogExportName(now, versionName, versionCode)
      val preferred = File(parent, name)
      val reserved = if (preferred.createNewFile()) preferred
        else File.createTempFile("${name.removeSuffix(".zip")}-", ".zip", parent)
      active.add(reserved)
      reserved
    }
    try {
      write(target)
      // Retention starts at completion, including an export that took a long time to write.
      Files.setLastModifiedTime(target.toPath(), FileTime.fromMillis(clock()))
      return target
    } catch (failure: Throwable) {
      try { Files.deleteIfExists(target.toPath()) } catch (cleanup: Throwable) {
        failure.addSuppressed(cleanup)
      }
      throw failure
    } finally {
      synchronized(lock) { active.remove(target) }
    }
  }

  private fun prune(directory: File, now: Long) {
    val files = directory.listFiles() ?: throw IOException("Cannot list $directory")
    files.forEach { file ->
      val owner = when {
        EXPORT_NAME.matches(file.name) -> file.name
        else -> TEMPORARY_NAME.matchEntire(file.name)?.groupValues?.get(1) ?: return@forEach
      }
      if (File(directory, owner) !in active &&
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
        file.lastModified() < now - retentionMillis) {
        Files.deleteIfExists(file.toPath())
      }
    }
  }

  internal companion object {
    fun isLogFileName(name: String): Boolean = EXPORT_NAME.matches(name) || TEMPORARY_NAME.matches(name)

    private val EXPORT_NAME = Regex(
      "(?:KeiHome-logs\\.zip|KeiHome-AppLogs-[0-9]{8}-[0-9]{6}-[0-9]{3}-" +
        "v(?:[0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{1,2}|unknown)-b[0-9]{4}(?:-[0-9]+)?\\.zip)",
    )
    private val TEMPORARY_NAME = Regex("\\.(${EXPORT_NAME.pattern})-[0-9]+\\.tmp")
  }
}

/** Only the numeric version series and build iteration enter the filename, never build metadata. */
internal fun appLogExportName(
  exportedAt: Long,
  versionName: String,
  versionCode: Int,
  timeZone: TimeZone = TimeZone.getDefault(),
): String {
  require(versionCode >= 0) { "versionCode must be nonnegative" }
  val version = versionName.substringBefore('+')
    .takeIf { Regex("[0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{1,2}").matches(it) } ?: "unknown"
  val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).apply {
    this.timeZone = timeZone
  }.format(Date(exportedAt))
  val iteration = String.format(Locale.ROOT, "%04d", versionCode % 10_000)
  return "KeiHome-AppLogs-$timestamp-v$version-b$iteration.zip"
}
