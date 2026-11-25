package os.kei.hyper.home.xposed

import android.system.StructStat
import java.util.concurrent.atomic.AtomicReference

/** File identity that survives a rename: device, inode, size and modification time. */
internal data class AppearanceFileKey(val device: Long, val inode: Long, val size: Long, val modifiedNanos: Long) {
  fun toLongArray() = longArrayOf(device, inode, size, modifiedNanos)

  companion object {
    fun of(stat: StructStat) = AppearanceFileKey(stat.st_dev, stat.st_ino, stat.st_size,
      stat.st_mtim.tv_sec * 1_000_000_000L + stat.st_mtim.tv_nsec)

    fun of(values: LongArray?): AppearanceFileKey? =
      values?.takeIf { it.size == 4 }?.let { AppearanceFileKey(it[0], it[1], it[2], it[3]) }
  }
}

/**
 * system_server's view of the copy Themes staged for the next night transition.
 *
 * Called from the UiModeManager commit hook before the new night bit is dispatched, so Home
 * already finds the matching archive when it applies the night change and reloads its icons.
 * It performs two stats and one same-directory rename, and only for exact identities Themes
 * just verified. Anything else returns false and leaves the slower Themes path to the worker.
 */
internal class AppearanceFastSwap(
  private val stat: (String) -> AppearanceFileKey?,
  private val rename: (String, String) -> Unit,
) {
  data class State(
    val activeKey: AppearanceFileKey,
    val activeNight: Boolean,
    val stagedPath: String,
    val stagedKey: AppearanceFileKey,
    val stagedNight: Boolean,
  )

  private val state = AtomicReference<State?>(null)

  fun publish(value: State?) {
    state.set(value?.takeIf { valid(it) })
  }

  fun beforeNightChange(night: Boolean): Boolean {
    val current = state.get() ?: return false
    if (current.activeNight == night || current.stagedNight != night) return false
    // Consume first: a second transition must not reuse a copy that is now active.
    if (!state.compareAndSet(current, null)) return false
    if (stat(ACTIVE) != current.activeKey || stat(current.stagedPath) != current.stagedKey) return false
    rename(current.stagedPath, ACTIVE)
    return true
  }

  private fun valid(value: State): Boolean =
    value.activeNight != value.stagedNight && value.activeKey != value.stagedKey &&
      STAGED.matches(value.stagedPath)

  companion object {
    const val ACTIVE = ThemeAppearanceVariants.ACTIVE
    private val STAGED = Regex(Regex.escape("/data/system/theme/${ThemeAppearanceVariants.STAGED_PREFIX}") +
      "(light|dark)\\.icons")
  }
}
