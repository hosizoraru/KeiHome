package os.kei.hyper.home.xposed

import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal enum class IconRefreshReportKind {
  HOOK_INSTALLED,
  HOOK_FAILED,
  REFRESH_FINISHED,
  REFRESH_PARTIAL,
  REFRESH_FAILED,
  REFRESH_CANCELLED,
}

internal data class IconRefreshReport(
  val bootCount: Int,
  val generation: String,
  val generationStartedNanos: Long,
  val sequence: Long,
  val completedElapsedNanos: Long,
  val completedWallMillis: Long,
  val kind: IconRefreshReportKind,
  val callbackCount: Int?,
) {
  fun isValid(currentBoot: Int, nowNanos: Long): Boolean {
    val validGeneration = try {
      UUID.fromString(generation).toString() == generation
    } catch (_: IllegalArgumentException) {
      false
    }
    val hook = kind == IconRefreshReportKind.HOOK_INSTALLED ||
      kind == IconRefreshReportKind.HOOK_FAILED
    return currentBoot >= 0 && bootCount == currentBoot && validGeneration &&
      generationStartedNanos > 0 && generationStartedNanos <= completedElapsedNanos &&
      completedElapsedNanos <= nowNanos && completedWallMillis > 0 && sequence > 0 &&
      if (hook) callbackCount == null else callbackCount != null && callbackCount >= 0
  }
}

internal data class IconRefreshHistory(
  val bootCount: Int,
  val generation: String,
  val generationStartedNanos: Long,
  val hook: IconRefreshReport?,
  val refreshes: List<IconRefreshReport>,
)

internal const val MAX_STORED_REFRESH_REPORTS = 32

internal fun mergeIconRefreshReport(
  history: IconRefreshHistory?,
  report: IconRefreshReport,
  currentBoot: Int,
  nowNanos: Long,
): IconRefreshHistory? {
  if (!report.isValid(currentBoot, nowNanos)) return history
  val previous = history?.takeIf { it.bootCount == currentBoot }
  if (previous != null) {
    if (report.generationStartedNanos < previous.generationStartedNanos) return history
    if (report.generationStartedNanos == previous.generationStartedNanos &&
      report.generation != previous.generation
    ) return history
  }
  val sameGeneration = previous?.takeIf {
    it.generationStartedNanos == report.generationStartedNanos &&
      it.generation == report.generation
  }
  val base = sameGeneration ?: IconRefreshHistory(
    report.bootCount, report.generation, report.generationStartedNanos, null, emptyList(),
  )
  val existing = (listOfNotNull(base.hook) + base.refreshes)
    .firstOrNull { it.sequence == report.sequence }
  if (existing != null) return history

  val hook = report.kind == IconRefreshReportKind.HOOK_INSTALLED ||
    report.kind == IconRefreshReportKind.HOOK_FAILED
  if (hook) {
    if (base.hook != null) return history
    return base.copy(hook = report)
  }
  if (base.refreshes.size == MAX_STORED_REFRESH_REPORTS &&
    report.sequence < base.refreshes.first().sequence
  ) return history
  return base.copy(
    refreshes = (base.refreshes + report).sortedBy { it.sequence }
      .takeLast(MAX_STORED_REFRESH_REPORTS),
  )
}

/**
 * One scheduled task and a bounded queue; send runs without the queue lock held.
 */
internal class IconRefreshReportDispatcher(
  private val send: (IconRefreshReport) -> Boolean,
  private val retryDelayMillis: Long = 1_000,
  private val maxAttempts: Int = 120,
  private val queueCapacity: Int = 64,
) {
  private data class Pending(val report: IconRefreshReport, var attempts: Int = 0)

  private val lock = Any()
  private val queue = ArrayDeque<Pending>()
  private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
    Thread(runnable, "KeiHomeRuntimeReport").apply { isDaemon = true }
  }.apply {
    removeOnCancelPolicy = true
    setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
  }
  private var scheduled = false
  private var sending = false
  private var stopped = false

  init {
    require(retryDelayMillis >= 0 && maxAttempts > 0 && queueCapacity > 0)
  }

  fun enqueue(report: IconRefreshReport): Boolean = synchronized(lock) {
    if (stopped || queue.size >= queueCapacity) return false
    queue.addLast(Pending(report))
    if (!scheduled) scheduleLocked(0)
    true
  }

  fun stopIfIdle(): Boolean = synchronized(lock) {
    if (sending) return false
    if (!stopped) {
      stopped = true
      queue.clear()
      executor.shutdownNow()
    }
    true
  }

  fun awaitStopped(timeoutMillis: Long): Boolean = try {
    executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
  } catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
  }

  private fun scheduleLocked(delayMillis: Long) {
    scheduled = true
    executor.schedule(::drain, delayMillis, TimeUnit.MILLISECONDS)
  }

  private fun drain() {
    val pending = synchronized(lock) {
      if (stopped || queue.isEmpty()) {
        scheduled = false
        return
      }
      sending = true
      queue.first()
    }
    val sent = try {
      send(pending.report)
    } catch (_: Throwable) {
      false
    }
    synchronized(lock) {
      sending = false
      pending.attempts += 1
      if (sent || pending.attempts >= maxAttempts) queue.removeFirst()
      if (queue.isEmpty()) {
        scheduled = false
      } else {
        scheduleLocked(if (sent || pending.attempts >= maxAttempts) 0 else retryDelayMillis)
      }
    }
  }
}
