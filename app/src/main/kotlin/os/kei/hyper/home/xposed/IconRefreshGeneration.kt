package os.kei.hyper.home.xposed

import android.content.res.Configuration

internal fun isNightModeTransition(previous: Int, current: Int): Boolean {
  val before = previous and Configuration.UI_MODE_NIGHT_MASK
  val after = current and Configuration.UI_MODE_NIGHT_MASK
  val validModes = setOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)
  return before in validModes && after in validModes && before != after
}

internal class IconRefreshGeneration {
  private var value = 0L
  private var retired = false

  @Synchronized
  fun next(previous: Int, current: Int): Long? {
    if (retired || !isNightModeTransition(previous, current)) return null
    value += 1
    return value
  }

  @Synchronized
  fun isCurrent(generation: Long): Boolean = !retired && generation == value

  @Synchronized
  fun retire() {
    retired = true
    value += 1
  }
}
