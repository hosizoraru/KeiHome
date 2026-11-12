package os.kei.hyper.home.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import os.kei.hyper.home.status.AppLogExportCache

internal enum class CacheBucket { CURRENT_ICONS, BUILTIN_PACKS, HANDOFF, OTHER, LOGS }

internal data class DiskCacheUsage(val bytes: Map<CacheBucket, Long>, val complete: Boolean) {
  val total: Long get() = bytes.values.sum()
}

/** Includes private App logs and their shared exports, without following links or touching writers. */
internal fun measureDiskCache(root: File, logRoot: File? = null, check: () -> Unit = {}): DiskCacheUsage {
  val sizes = CacheBucket.entries.associateWith { 0L }.toMutableMap()
  var complete = true
  val cachePath = root.toPath().toAbsolutePath().normalize()
  val logPath = logRoot?.toPath()?.toAbsolutePath()?.normalize()
  val sharePath = cachePath.resolve("share")
  fun measure(directory: File, bucketFor: (Path) -> CacheBucket) {
    if (!directory.exists()) return
    try {
      Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
          check()
          return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          check()
          if (attrs.isRegularFile) {
            val bucket = bucketFor(file.toAbsolutePath().normalize())
            sizes[bucket] = sizes.getValue(bucket) + attrs.size()
          }
          return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
          complete = false
          return FileVisitResult.CONTINUE
        }
      })
    } catch (_: IOException) { complete = false
    } catch (_: SecurityException) { complete = false }
  }
  measure(root) { path ->
    when {
      logPath != null && path.startsWith(logPath) -> CacheBucket.LOGS
      path.parent == sharePath && AppLogExportCache.isLogFileName(path.fileName.toString()) -> CacheBucket.LOGS
      else -> when (cachePath.relativize(path).first().toString()) {
        "current-icons" -> CacheBucket.CURRENT_ICONS
        "builtin-icon-packs" -> CacheBucket.BUILTIN_PACKS
        "icon-pack-exports", "native-icon-imports" -> CacheBucket.HANDOFF
        else -> CacheBucket.OTHER
      }
    }
  }
  // A nested log directory was already measured above.
  if (logRoot != null && logPath?.startsWith(cachePath) == false) measure(logRoot) { CacheBucket.LOGS }
  return DiskCacheUsage(sizes, complete)
}
