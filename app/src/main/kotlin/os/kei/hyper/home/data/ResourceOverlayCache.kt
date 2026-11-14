package os.kei.hyper.home.data

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

internal data class OverlayFileIdentity(
  val path: String,
  val size: Long,
  val modifiedNanos: Long,
  val fileKey: String?,
)

internal data class OverlayApkIdentity(
  val packageName: String,
  val uid: Int,
  val files: List<OverlayFileIdentity>,
)

/** Only manifest verdicts survive scans, never Resources, parsers or application contexts. */
internal class ResourceOverlayCache(private val capacity: Int = 512) {
  init { require(capacity > 0) }
  private val verdicts = object : LinkedHashMap<OverlayApkIdentity, Boolean>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<OverlayApkIdentity, Boolean>) =
      size > capacity
  }

  @Synchronized
  fun resolve(identity: OverlayApkIdentity?, read: () -> Boolean?): Boolean {
    if (identity != null) verdicts[identity]?.let { return it }
    val verdict = read()
    if (identity != null && verdict != null) verdicts[identity] = verdict
    return verdict ?: false
  }
}

internal fun overlayApkIdentity(packageName: String, uid: Int, paths: List<String>): OverlayApkIdentity? {
  if (paths.isEmpty() || paths.any { it.isBlank() }) return null
  return try {
    val files = paths.distinct().map { name ->
      val path = java.nio.file.Paths.get(name)
      val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
      if (!attributes.isRegularFile) return null
      OverlayFileIdentity(name, attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
        attributes.fileKey()?.toString())
    }
    OverlayApkIdentity(packageName, uid, files)
  } catch (_: Exception) { null }
}
