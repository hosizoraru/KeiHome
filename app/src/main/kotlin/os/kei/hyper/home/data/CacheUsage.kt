package os.kei.hyper.home.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

internal enum class CacheBucket { CURRENT_ICONS, BUILTIN_PACKS, HANDOFF, OTHER }

internal data class DiskCacheUsage(val bytes: Map<CacheBucket, Long>, val complete: Boolean) {
  val total: Long get() = bytes.values.sum()
}

/** Counts private cache files without following links or changing active working files. */
internal fun measureDiskCache(root: File, check: () -> Unit = {}): DiskCacheUsage {
  val sizes = CacheBucket.entries.associateWith { 0L }.toMutableMap()
  var complete = true
  if (!root.exists()) return DiskCacheUsage(sizes, true)
  val children = root.listFiles() ?: return DiskCacheUsage(sizes, false)
  for (child in children) {
    check()
    val bucket = when (child.name) {
      "current-icons" -> CacheBucket.CURRENT_ICONS
      "builtin-icon-packs" -> CacheBucket.BUILTIN_PACKS
      "icon-pack-exports", "native-icon-imports" -> CacheBucket.HANDOFF
      else -> CacheBucket.OTHER
    }
    try {
      Files.walkFileTree(child.toPath(), object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          check()
          if (attrs.isRegularFile) sizes[bucket] = sizes.getValue(bucket) + attrs.size()
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
  return DiskCacheUsage(sizes, complete)
}
