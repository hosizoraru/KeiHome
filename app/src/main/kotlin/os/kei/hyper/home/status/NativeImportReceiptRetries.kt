package os.kei.hyper.home.status

import kotlinx.coroutines.delay

internal data class NativeImportReceiptOperation(
  val token: String,
  val userId: Int,
  val sourceId: String,
  val startedAt: Long?,
)

internal fun IconImportLocalState.pendingReceiptOperation(userId: Int): NativeImportReceiptOperation? {
  val token = pendingToken ?: return null
  val source = pendingSource ?: return null
  val saved = request
  if (saved != null && (saved.userId != userId || saved.token != token || saved.sourceId != source)) return null
  return NativeImportReceiptOperation(token, userId, source, saved?.startedAt)
}

/** Receipt queries only. Time and attempt consumption survive observer restarts in this VM. */
internal class NativeImportReceiptRetries(private val nowMillis: () -> Long) {
  private var operation: NativeImportReceiptOperation? = null
  private var attempts = 0
  private var lastAttemptAt: Long? = null

  suspend fun observe(pending: NativeImportReceiptOperation, query: suspend () -> Unit) {
    if (operation != pending) {
      operation = pending
      attempts = 0
      lastAttemptAt = null
    }
    val startedAt = pending.startedAt ?: return // Legacy requests remain manually queryable.
    while (true) {
      val wait = nextNativeReceiptQueryDelay(startedAt, attempts, lastAttemptAt, nowMillis()) ?: return
      delay(wait)
      // Check the budget again after suspension, including clock reset and late foreground resume.
      val remaining = nextNativeReceiptQueryDelay(startedAt, attempts, lastAttemptAt, nowMillis()) ?: return
      if (remaining > 0) continue
      lastAttemptAt = nowMillis()
      attempts++ // A cancelled publication must not allow a burst on the next foreground entry.
      query()
    }
  }
}

private val nativeReceiptQueryOffsets = longArrayOf(15_000, 45_000, 90_000, 150_000, 240_000, 360_000)

internal fun nextNativeReceiptQueryDelay(
  startedAt: Long, attempts: Int, lastAttemptAt: Long?, now: Long,
): Long? {
  val offset = nativeReceiptQueryOffsets.getOrNull(attempts) ?: return null
  val elapsed = now - startedAt
  // A delayed dispatcher must still be able to run the final scheduled query.
  val budget = nativeReceiptQueryOffsets.last() + 30_000
  if (startedAt < 0 || elapsed < 0 || elapsed > budget) return null
  val spacing = offset - if (attempts == 0) 0 else nativeReceiptQueryOffsets[attempts - 1]
  val sinceLast = lastAttemptAt?.let { now - it }
  if (sinceLast != null && sinceLast < 0) return null
  val wait = maxOf(0, offset - elapsed, sinceLast?.let { spacing - it } ?: 0)
  return wait.takeIf { it <= budget - elapsed }
}

/** Manual and automatic queries remain distinct even within one elapsed-realtime tick. */
internal fun nextNativeReceiptQueryNonce(now: Long, published: Long, saved: Long): Long? {
  val previous = maxOf(published, saved)
  return if (previous == Long.MAX_VALUE) null else maxOf(now, previous + 1)
}
