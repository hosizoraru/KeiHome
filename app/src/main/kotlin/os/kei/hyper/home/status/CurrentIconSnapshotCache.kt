package os.kei.hyper.home.status

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import os.kei.hyper.home.iconpack.IconPackLimits

/** Provider-side metadata checks only; payload CRC and decoding belong to the worker/readers. */
internal class CurrentIconSnapshotCache(
  private val directory: File,
  private val limits: IconPackLimits = IconPackLimits(),
) {
  fun file(key: String): File {
    require(KEY.matches(key))
    return File(directory, "$key.zip")
  }

  fun exists(key: String): Boolean = synchronized(lock) {
    marker(key).isFile && validArchive(file(key))
  }

  fun <T> openWriter(key: String, open: (File) -> T): T = synchronized(lock) {
    val archive = file(key)
    remove(marker(key))
    open(archive)
  }

  fun ready(key: String): Boolean = synchronized(lock) {
    val archive = file(key)
    val marker = marker(key)
    val temporary = File(directory, "$key.ready.tmp")
    try {
      remove(marker)
      if (!validArchive(archive)) return@synchronized false
      cleanup(key)
      temporary.writeText("ready")
      if (!temporary.renameTo(marker)) throw IOException("Could not publish snapshot marker")
      true
    } catch (_: Exception) {
      false
    } finally {
      temporary.delete()
    }
  }

  private fun marker(key: String): File {
    file(key) // Validate before constructing any related path.
    return File(directory, "$key.ready")
  }

  private fun validArchive(file: File): Boolean = try {
    val inputBytes = file.length()
    if (!file.isFile || inputBytes !in 1..limits.maxInputBytes) false
    else ZipFile(file).use { zip ->
      require(zip.size() <= limits.maxEntries)
      val names = HashSet<String>()
      val files = HashSet<String>()
      var expanded = 0L
      for (entry in zip.entries()) {
        val path = entry.name
        // Same lexical path policy as IconPackArchive; no entry paths are extracted.
        require(path.isNotEmpty() && path.length <= 1024 && !path.startsWith('/') &&
          '\\' !in path && ':' !in path && path.none { it.isISOControl() })
        val name = path.removeSuffix("/")
        val parts = name.split('/')
        require(parts.size <= 12 && parts.none { it.isEmpty() || it == "." || it == ".." })
        require(names.add(name))
        if (!entry.isDirectory) files += name
        require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED)
        require(entry.size in 0..limits.maxEntryBytes &&
          entry.compressedSize in 0..inputBytes && entry.crc in 0..0xffffffffL &&
          entry.size <= maxOf(1L, entry.compressedSize) * limits.maxCompressionRatio &&
          entry.size <= limits.maxExpandedBytes - expanded &&
          (!entry.isDirectory || entry.size == 0L))
        expanded += entry.size
      }
      for (name in names) {
        var slash = name.indexOf('/')
        while (slash >= 0) {
          require(name.substring(0, slash) !in files)
          slash = name.indexOf('/', slash + 1)
        }
      }
      true
    }
  } catch (_: Exception) {
    false
  }

  private fun cleanup(current: String) {
    val contents = directory.listFiles() ?: throw IOException("Could not list snapshot cache")
    val previous = contents.filter { it.name.endsWith(".ready") &&
      KEY.matches(it.name.removeSuffix(".ready")) && it.name != "$current.ready" }
      .sortedByDescending { it.lastModified() }
      .firstOrNull { it.isFile && validArchive(file(it.name.removeSuffix(".ready"))) }
      ?.name?.removeSuffix(".ready")
    for (file in contents) {
      val key = when {
        file.name.endsWith(".zip") -> file.name.removeSuffix(".zip")
        file.name.endsWith(".ready") -> file.name.removeSuffix(".ready")
        file.name.endsWith(".ready.tmp") -> file.name.removeSuffix(".ready.tmp")
        else -> continue
      }
      if (KEY.matches(key) && key != current && key != previous) remove(file)
    }
  }

  private fun remove(file: File) {
    if (file.exists() && !file.delete()) throw IOException("Could not invalidate snapshot file")
  }

  companion object {
    private val KEY = Regex("[a-f0-9]{64}")
    // Provider calls may arrive on different binder threads and create separate helper instances.
    private val lock = Any()
  }
}
