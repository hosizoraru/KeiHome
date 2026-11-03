package os.kei.hyper.home.xposed

import android.content.res.Configuration
import android.content.pm.ActivityInfo

/** Resource qualifiers can change artwork without changing a package or night mode. */
internal fun iconResourceConfigurationChanged(changes: Int): Boolean = changes and (
  ActivityInfo.CONFIG_LOCALE or ActivityInfo.CONFIG_LAYOUT_DIRECTION or
    ActivityInfo.CONFIG_DENSITY or ActivityInfo.CONFIG_COLOR_MODE or ActivityInfo.CONFIG_ASSETS_PATHS
  ) != 0

internal fun isNightModeTransition(previous: Int, current: Int): Boolean {
  val before = previous and Configuration.UI_MODE_NIGHT_MASK
  val after = current and Configuration.UI_MODE_NIGHT_MASK
  val validModes = setOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)
  return before in validModes && after in validModes && before != after
}

internal enum class IconRefreshCause(val initialDelayMs: Long, val packageIntervalMs: Long) {
  RESOURCE_CHANGE(1500L, 220L),
  PALETTE_ACK(100L, 60L),
  LAUNCHER_LABEL_ACK(100L, 0L),
  PACKAGE_CHANGE(500L, 60L),
}

internal class IconRefreshGeneration {
  private var value = 0L
  private var retired = false
  private var fullRefreshRequired = false
  // A newer palette ACK can replace a queued/running resource refresh, but
  // cannot turn its unfinished work into a fast palette-only sweep.
  private var resourceChangePending = false
  private val changedPackages = linkedSetOf<String>()
  private var packageChangesPending = false

  @Synchronized
  fun nextPackageRefresh(packageName: String): Long? {
    return nextScopedRefresh(packageName, packageChange = true)
  }

  @Synchronized
  fun nextLauncherLabelRefresh(packageName: String): Long? {
    return nextScopedRefresh(packageName, packageChange = false)
  }

  private fun nextScopedRefresh(packageName: String, packageChange: Boolean): Long? {
    if (retired) return null
    packageChangesPending = packageChangesPending || packageChange
    changedPackages += packageName
    value += 1
    return value
  }

  @Synchronized
  fun packages(generation: Long): Set<String>? =
    if (!retired && generation == value && !fullRefreshRequired && !resourceChangePending &&
      changedPackages.isNotEmpty()) changedPackages.toSet() else null

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
    if (packages(generation) != null) {
      if (packageChangesPending) IconRefreshCause.PACKAGE_CHANGE
      else IconRefreshCause.LAUNCHER_LABEL_ACK
    }
    else if (!retired && generation == value && fullRefreshRequired && !resourceChangePending) {
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
      changedPackages.clear()
      packageChangesPending = false
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
    changedPackages.clear()
    packageChangesPending = false
    value += 1
  }
}
