package os.kei.hyper.home.iconpack.apple

internal enum class AppleMatchAttemptStatus { MATCHED, REVIEW, NO_MATCH, FAILED }

internal data class AppleMatchAttempt(
  val status: AppleMatchAttemptStatus,
  val attemptedAt: Long,
  val retryAt: Long,
  val country: String,
  val candidates: List<AppleAppMatch> = emptyList(),
)

internal data class AppleBatchProgress(val done: Int, val total: Int, val label: String)
internal data class AppleBatchResult(
  val attempted: Int = 0,
  val matched: Int = 0,
  val review: Int = 0,
  val unmatched: Int = 0,
  val failed: Int = 0,
  val skipped: Int = 0,
)
