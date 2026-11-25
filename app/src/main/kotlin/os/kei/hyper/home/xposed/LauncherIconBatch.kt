package os.kei.hyper.home.xposed

/**
 * Packages HyperOS Home can refresh with a single `onPackagesUnsuspended` callback.
 *
 * Home's Rust model handles PACKAGES_UNSUSPENDED in `AllAppsList.update_disabled_flags`. For each
 * listed package it rewrites the suspended word of the package's *first* app entry and reports that
 * entry as modified. One `ItemChangeProcessor._handleAppUpdate` pass then evicts and reloads the
 * icons of all modified entries, instead of one model task per PACKAGE_CHANGED callback. The word
 * holds only the suspended bit (`AppInfo.update_suspended_flag_from_activity_info`), so a package
 * that is not suspended keeps its state. Suspended packages and packages with several launcher
 * entries keep the per-package path.
 *
 * Home drops a batch equal to its previous one (same action, user and package order) for about
 * five seconds, so consecutive batches for the same user alternate their order. One refresh sends
 * a batch per profile; a shared toggle would flip twice and repeat each profile's last order.
 */
internal class LauncherIconBatch {
  data class Entry(val packageName: String, val suspended: Boolean)

  private val reversed = HashMap<Int, Boolean>()

  fun eligible(entries: Collection<Entry>): Set<String> =
    entries.groupBy { it.packageName }
      .filterValues { it.size == 1 && !it.single().suspended }
      .keys.toSortedSet()

  @Synchronized
  fun order(userId: Int, packages: Collection<String>): Array<String> {
    val next = reversed[userId] != true
    reversed[userId] = next
    val sorted = packages.toSortedSet().toList()
    return (if (next) sorted.asReversed() else sorted).toTypedArray()
  }

  companion object {
    /** A single package gains nothing from the batch and could repeat Home's last batch. */
    const val MIN_SIZE = 2
  }
}
