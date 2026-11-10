package os.kei.hyper.home.iconpack.perfect

internal data class PerfectLocalProbeStamp(
  val pointerExists: Boolean,
  val pointerLength: Long,
  val pointerModified: Long,
  val sourceHash: String,
  val archiveExists: Boolean,
  val archiveLength: Long,
  val archiveModified: Long,
  val corruptSourceHash: String?,
  val knownSourceHash: String?,
  val sourceDirectoryModified: Long,
)

/** One result, guarded by the repository mutex; never retains a ZIP handle or source payload. */
internal class PerfectLocalHealthProbe {
  private var cached: Pair<PerfectLocalProbeStamp, Boolean>? = null

  suspend fun healthy(stamp: PerfectLocalProbeStamp, probe: suspend () -> Boolean): Boolean {
    cached?.takeIf { it.first == stamp }?.let { return it.second }
    return probe().also { cached = stamp to it }
  }

  fun invalidate() { cached = null }
}
