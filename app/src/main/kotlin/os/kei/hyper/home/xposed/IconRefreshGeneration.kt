package os.kei.hyper.home.xposed

import android.content.res.Configuration

internal fun isNightModeTransition(previous: Int, current: Int): Boolean {
  val before = previous and Configuration.UI_MODE_NIGHT_MASK
  val after = current and Configuration.UI_MODE_NIGHT_MASK
  val validModes = setOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)
  return before in validModes && after in validModes && before != after
}

internal enum class IconRefreshCause(val initialDelayMs: Long, val packageIntervalMs: Long) {
  RESOURCE_CHANGE(1500L, 220L),
  PALETTE_ACK(100L, 60L),
}

internal class IconRefreshGeneration {
  private var value = 0L
  private var retired = false
  private var fullRefreshRequired = false
  // A newer palette ACK can replace a queued/running resource refresh, but
  // cannot turn its unfinished work into a fast palette-only sweep.
  private var resourceChangePending = false

  @Synchronized
  fun nextFullRefresh(cause: IconRefreshCause = IconRefreshCause.RESOURCE_CHANGE): Long? {
    if (retired) return null
    fullRefreshRequired = true
    if (cause == IconRefreshCause.RESOURCE_CHANGE) resourceChangePending = true
    value += 1
    return value
  }

  @Synchronized
  fun cause(generation: Long): IconRefreshCause =
    if (!retired && generation == value && fullRefreshRequired && !resourceChangePending) {
      IconRefreshCause.PALETTE_ACK
    } else {
      IconRefreshCause.RESOURCE_CHANGE
    }

  @Synchronized
  fun needsFullRefresh(generation: Long): Boolean =
    !retired && generation == value && fullRefreshRequired

  @Synchronized
  fun complete(generation: Long) {
    if (!retired && generation == value) {
      fullRefreshRequired = false
      resourceChangePending = false
    }
  }

  @Synchronized
  fun next(previous: Int, current: Int): Long? {
    if (retired || !isNightModeTransition(previous, current)) return null
    resourceChangePending = true
    value += 1
    return value
  }

  @Synchronized
  fun isCurrent(generation: Long): Boolean = !retired && generation == value

  @Synchronized
  fun retire() {
    retired = true
    fullRefreshRequired = false
    resourceChangePending = false
    value += 1
  }
}
