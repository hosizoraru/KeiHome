package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.io.IOException

data class PerfectIconOptions(
  val style: String = "",
  val installedOnly: Boolean = true,
  val dynamic: Boolean = true,
  val staticNight: Boolean = false,
  val fixedDark: Boolean = false,
) {
  internal val useFixedDark: Boolean get() = staticNight && fixedDark
}

data class PerfectIconCatalog(
  val revision: String,
  val styles: List<String>,
  val updatedAt: Long,
  val iconCount: Int,
  val downloadedAt: Long = updatedAt,
  val sourceHash: String = revision,
  val upToDate: Boolean = false,
  val archiveBytes: Long = 0,
  val downloadedStyles: List<String> = styles,
  val remoteCommittedAt: Long = 0,
  val remoteSubject: String = "",
  val transferredBytes: Long = 0,
  val gitBacked: Boolean = false,
  val styleDetails: Map<String, PerfectIconStyleDetails> = emptyMap(),
)

/**
 * Uncompressed bytes of this style's selected resource graph, including required shared/base
 * resources, with repeated Git blob hashes counted once. Excludes physical Git/cache overhead.
 * A blob referenced by both own and shared paths belongs to sharedBytes, so ownBytes + sharedBytes
 * equals bytes. The ownBytes default preserves totals in fixtures constructed with bytes alone.
 */
data class PerfectIconStyleDetails(
  val bytes: Long = 0,
  val ownBytes: Long = bytes,
  val sharedBytes: Long = 0,
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
  val rejectedDynamic: Int = 0,
  val contract: String = PerfectIconRepository.LAYER_CONTRACT,
  val nightRevision: String = "",
  val derivedDynamicCount: Int = 0,
) {
  internal fun needsContractUpdate(options: PerfectIconOptions): Boolean =
    contract != PerfectIconRepository.LAYER_CONTRACT ||
      options.staticNight && nightRevision != options.nightContract()
}

internal fun PerfectIconOptions.nightContract(): String =
  if (staticNight) {
    val dynamicRevision = if (dynamic) PerfectDynamicNight.REVISION else "static"
    val ordinary = if (useFixedDark) "fixed-dark-1" else "selector-2"
    "${PerfectStaticNightRenderer.REVISION}:$dynamicRevision:$ordinary"
  }
  else ""

class PerfectIconCooldownException(val retryAt: Long) :
  IOException("Perfect Icons requests paused until $retryAt")

internal interface PerfectIconSettings {
  fun read(key: String): String?
  fun write(key: String, value: String)
}
