package os.kei.hyper.home.xposed

/**
 * Decides when HyperOS Home is rebuilt after its icon archive was replaced.
 *
 * The batched package refresh redraws icons that are mounted on the workspace. Home's drawer
 * keeps its grid items alive with the images they were built with, and `IconCacheV2` replaces
 * an entry only while a mounted widget for that key updates. Drawer-only apps therefore keep
 * the previous artwork; only a new Home engine reads every icon again. A rebuild returns Home
 * to its first page, so it waits until the screen has been off for [delayMs]. A screen that
 * turns on again before then cancels the attempt until the next screen-off.
 *
 * Right after an import is applied, Themes' own theme change has already reloaded Home and
 * Home keeps the original it opened then. That change is urgent: Home is rebuilt at once, as
 * part of the same apply, so the applied pack is complete without a later screen-off.
 *
 * Not thread-safe; the owner calls it from one worker.
 */
internal class LauncherRebuildSchedule(val delayMs: Long = DELAY_MS) {
  /** The archive changed after Home last started. */
  var pending = false
    private set

  /** Returns the delay to wait, or null to wait for the next screen-off. */
  fun archiveReplaced(interactive: Boolean, urgent: Boolean = false, launcherVisible: Boolean = true): Long? {
    pending = true
    if (urgent) {
      urgentPending = true
      return 0
    }
    return if (interactive) null else delayMs
  }

  private var urgentPending = false

  /** Returns the delay to wait, or null when Home needs no rebuild. */
  fun screenOff(): Long? = if (pending) delayMs else null

  /** True only once per pending change, and only while the screen is still off. */
  fun due(interactive: Boolean, launcherVisible: Boolean = true): Boolean {
    if (!pending) return false
    if (interactive && !urgentPending) return false
    pending = false
    urgentPending = false
    return true
  }

  /** The rebuild did not happen; try again at the next screen-off. */
  fun retry() {
    pending = true
  }

  companion object {
    const val DELAY_MS = 3_000L
  }
}
