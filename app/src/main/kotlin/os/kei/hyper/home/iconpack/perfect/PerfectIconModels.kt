package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.io.IOException

data class PerfectIconOptions(
  val style: String = "",
  val installedOnly: Boolean = true,
  val dynamic: Boolean = true,
)

data class PerfectIconCatalog(
  val revision: String,
  val styles: List<String>,
  val updatedAt: Long,
  val iconCount: Int,
  val downloadedAt: Long = updatedAt,
  val sourceHash: String = revision,
  val upToDate: Boolean = false,
  val archiveBytes: Long = 0,
)

data class PerfectIconProgress(val done: Int, val total: Int, val label: String)

/** Counts are distinct IDs; downloaded/reused count blobs extracted locally/reused from cache. */
data class PerfectIconResult(
  val file: File,
  val mapped: Int,
  val staticCount: Int,
  val dynamicCount: Int,
  val downloaded: Int,
  val reused: Int,
  val revision: String,
)

class PerfectIconCooldownException(val retryAt: Long) :
  IOException("Perfect Icons requests paused until $retryAt")

internal interface PerfectIconSettings {
  fun read(key: String): String?
  fun write(key: String, value: String)
}
