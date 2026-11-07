package os.kei.hyper.home.status

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Synchronous app-process archive; use one owner per directory (no cross-process locking).
 * Ranges are inclusive. Only regular files named `<nonnegative epoch hour>.log` are owned.
 * Each accepted append is flushed, but not fsynced, matching File.appendText's visibility.
 * Stats/export capture open file handles and byte limits under the lock, then read outside it.
 * Closing the archive rejects new operations; already captured readers can finish normally.
 */
class HourlyAppLogArchive(
  private val directory: File,
  private val clock: () -> Long = System::currentTimeMillis,
  private val maxFileBytes: Long = 1024L * 1024,
  private val retentionMillis: Long = 7L * 24 * 60 * 60 * 1000,
) : Closeable {
  private val lock = Any()
  private var closed = false
  private var writer: BufferedOutputStream? = null
  private var writerFile: File? = null
  private var writerBytes = 0L
  private var lastPruneHour: Long? = null

  init {
    require(maxFileBytes > 0) { "maxFileBytes must be positive" }
    require(retentionMillis >= 0) { "retentionMillis must be nonnegative" }
    val now = clock()
    prune(now)
    lastPruneHour = now / HOUR_MILLIS
  }

  /** Returns false if the whole UTF-8 line cannot fit, or its timestamp has expired. */
  fun append(timestamp: Long, contentLineWithoutTimestamp: String): Boolean {
    require(timestamp >= 0) { "timestamp must be nonnegative" }
    require('\n' !in contentLineWithoutTimestamp && '\r' !in contentLineWithoutTimestamp) {
      "content must be a single line"
    }
    val bytes = "$timestamp\t$contentLineWithoutTimestamp\n".toByteArray(Charsets.UTF_8)
    return synchronized(lock) {
      ensureOpen()
      ensureDirectory()
      val now = clock()
      val hour = now / HOUR_MILLIS
      if (lastPruneHour != hour) {
        prune(now)
        lastPruneHour = hour
      }
      if (timestamp < now - retentionMillis || bytes.size.toLong() > maxFileBytes) {
        return@synchronized false
      }
      val file = File(directory, "${timestamp / HOUR_MILLIS}.log")
      if (writerFile != file) {
        closeWriter()
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !isOwnedFile(file)) {
          throw IOException("Not a regular log file: $file")
        }
        writerBytes = file.length()
        if (writerBytes > maxFileBytes - bytes.size) return@synchronized false
        writer = BufferedOutputStream(FileOutputStream(file, true))
        writerFile = file
      }
      if (writerBytes > maxFileBytes - bytes.size) return@synchronized false
      try {
        writer!!.write(bytes)
        writer!!.flush()
        writerBytes += bytes.size
        true
      } catch (failure: Throwable) {
        try { closeWriter() } catch (closing: Throwable) { failure.addSuppressed(closing) }
        throw failure
      }
    }
  }

  /** UTF-8 byte count (including LF) and valid timestamped line count in the range. */
  fun stats(since: Long, until: Long = clock()): Pair<Long, Int> = capture(since, until).use { snapshot ->
    var bytes = 0L
    var lines = 0
    snapshot.files.forEach { file ->
      file.input.bufferedReader(Charsets.UTF_8).useLines { records ->
        records.forEach { line ->
          if (selected(line, since, until)) {
            bytes += line.toByteArray(Charsets.UTF_8).size + 1L
            lines++
          }
        }
      }
    }
    bytes to lines
  }

  /**
   * Streams retained lines into a sibling temporary file, then atomically replaces each file.
   * No in-place truncate fallback: existing snapshot handles must retain their prior contents.
   * Writers are serialized during clear. Atomicity is per hourly file, not across the range.
   */
  fun clear(since: Long, until: Long = clock()): Int = synchronized(lock) {
    ensureOpen()
    if (since > until) return@synchronized 0
    var removed = 0
    filesInRange(since, until).forEach { file ->
      withTemporaryFile(directory, ".${file.name}-") { replacement ->
        var removedFromFile = 0
        replacement.outputStream().buffered().use { output ->
          file.bufferedReader(Charsets.UTF_8).useLines { records ->
            records.forEach { line ->
              if (selected(line, since, until)) {
                removedFromFile++
              } else {
                output.write(line.toByteArray(Charsets.UTF_8))
                output.write('\n'.code)
              }
            }
          }
        }
        if (removedFromFile != 0) {
          if (writerFile == file) closeWriter()
          Files.move(replacement.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          removed += removedFromFile
        }
      }
    }
    removed
  }

  /** Atomically publishes a ZIP to target, which must not replace an hourly log. */
  fun export(since: Long, until: Long = clock(), target: File,
    environment: String = ENVIRONMENT_UNAVAILABLE): File {
    val canonicalTarget = target.canonicalFile
    require(canonicalTarget.parentFile != directory.canonicalFile || !LOG_NAME.matches(canonicalTarget.name)) {
      "Export target must not replace an hourly log"
    }
    // Capture first, so a closed archive cannot create or truncate the target.
    capture(since, until).use { snapshot ->
      val parent = target.absoluteFile.parentFile
      if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create $parent")
      withTemporaryFile(parent, ".${target.name}-") { temporary ->
        temporary.outputStream().buffered().use { output -> writeZip(snapshot, since, until, environment, output) }
        Files.move(temporary.toPath(), target.toPath(),
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      }
    }
    return target
  }

  /** Finishes and flushes a ZIP, leaving the caller's output stream open, even on failure. */
  fun exportTo(since: Long, until: Long = clock(), target: OutputStream,
    environment: String = ENVIRONMENT_UNAVAILABLE) {
    capture(since, until).use { snapshot -> writeZip(snapshot, since, until, environment, target) }
  }

  override fun close() = synchronized(lock) {
    if (!closed) {
      closed = true
      closeWriter()
    }
  }

  private fun writeZip(snapshot: Snapshot, since: Long, until: Long, environment: String, target: OutputStream) {
    // ZipOutputStream must be closed to release its deflater; the supplied stream is borrowed.
    val borrowed = object : FilterOutputStream(target) {
      override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
      override fun close() = flush()
    }
    ZipOutputStream(borrowed).use { zip ->
      val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      }
      zip.putNextEntry(ZipEntry("README-KeiHome.txt"))
      zip.write(("KeiHome App log export - guidance supplied by KeiHome, not captured log text.\n" +
        "Only App-process logs for App UI, settings and App-process failures are included.\n" +
        "For Dock, icons and other module/Hook feature issues, export LSPosed logs instead.\n" +
        "This ZIP does not read or include LSPosed logs or native feature logs.\n" +
        "environment.txt describes the environment at export time, not a feature diagnostic trace.\n" +
        "It may differ from the environment when the incident occurred.\n" +
        "Range: ${date.format(Date(since))} to ${date.format(Date(until))} (inclusive)\n")
        .toByteArray(Charsets.UTF_8))
      zip.closeEntry()
      zip.putNextEntry(ZipEntry("environment.txt"))
      zip.write(environment.toByteArray(Charsets.UTF_8))
      zip.closeEntry()
      snapshot.files.forEach { file ->
        var entryStarted = false
        file.input.bufferedReader(Charsets.UTF_8).useLines { records ->
          records.forEach { line ->
            if (selected(line, since, until)) {
              if (!entryStarted) {
                zip.putNextEntry(ZipEntry(file.name))
                entryStarted = true
              }
              zip.write(line.toByteArray(Charsets.UTF_8))
              zip.write('\n'.code)
            }
          }
        }
        if (entryStarted) zip.closeEntry()
      }
    }
  }

  private fun capture(since: Long, until: Long): Snapshot = synchronized(lock) {
    ensureOpen()
    val snapshot = Snapshot(mutableListOf())
    try {
      filesInRange(since, until).forEach { file ->
        val input = FileInputStream(file)
        try {
          snapshot.files.add(ReadFile(file.name, BoundedInput(input, input.channel.size())))
        } catch (failure: Throwable) {
          try { input.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
          throw failure
        }
      }
      snapshot
    } catch (failure: Throwable) {
      try { snapshot.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
      throw failure
    }
  }

  private fun prune(now: Long) {
    val oldestHour = (now - retentionMillis) / HOUR_MILLIS
    ownedFiles().forEach { file ->
      if (file.nameWithoutExtension.toLong() < oldestHour) {
        if (writerFile == file) closeWriter()
        Files.delete(file.toPath())
      }
    }
  }

  private fun ownedFiles(): List<File> {
    if (!directory.exists()) return emptyList()
    val files = directory.listFiles() ?: throw IOException("Cannot list $directory")
    return files.filter(::isOwnedFile).sortedBy { it.name }
  }

  private fun filesInRange(since: Long, until: Long): List<File> {
    if (since > until) return emptyList()
    // Divide timestamps, rather than multiplying filename hours, to avoid overflow.
    // floorDiv also keeps ranges ending before epoch zero from selecting 0.log.
    val firstHour = Math.floorDiv(since, HOUR_MILLIS)
    val lastHour = Math.floorDiv(until, HOUR_MILLIS)
    return ownedFiles().filter { it.nameWithoutExtension.toLong() in firstHour..lastHour }
  }

  private fun isOwnedFile(file: File): Boolean =
    LOG_NAME.matches(file.name) && file.nameWithoutExtension.toLongOrNull() != null &&
      Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

  private fun ensureDirectory() {
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
  }

  private fun <T> withTemporaryFile(parent: File, prefix: String, action: (File) -> T): T {
    val file = File.createTempFile(prefix, ".tmp", parent)
    return Closeable { Files.deleteIfExists(file.toPath()) }.use { action(file) }
  }

  private fun ensureOpen() = check(!closed) { "Archive is closed" }

  private fun closeWriter() {
    val previous = writer
    writer = null
    writerFile = null
    writerBytes = 0
    previous?.close()
  }

  private fun selected(line: String, since: Long, until: Long): Boolean {
    val tab = line.indexOf('\t')
    if (tab < 0) return false
    val timestamp = line.substring(0, tab).toLongOrNull() ?: return false
    return timestamp >= since && timestamp <= until
  }

  private data class ReadFile(val name: String, val input: InputStream)

  private class Snapshot(val files: MutableList<ReadFile>) : Closeable {
    override fun close() {
      var failure: Throwable? = null
      files.forEach { file ->
        try { file.input.close() } catch (closing: Throwable) {
          if (failure == null) failure = closing else failure.addSuppressed(closing)
        }
      }
      failure?.let { throw it }
    }
  }

  private class BoundedInput(private val input: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
      if (remaining == 0L) return -1
      val byte = input.read()
      if (byte < 0) remaining = 0 else remaining--
      return byte
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      if (length == 0) return 0
      if (remaining == 0L) return -1
      val count = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
      if (count < 0) remaining = 0 else remaining -= count
      return count
    }

    override fun close() = input.close()
  }

  private companion object {
    const val ENVIRONMENT_UNAVAILABLE =
      "Export-time environment was not collected by this archive caller; incident environment is unknown.\n"
    const val HOUR_MILLIS = 3_600_000L
    val LOG_NAME = Regex("[0-9]+\\.log")
  }
}
