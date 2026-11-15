package os.kei.hyper.home.ui.main

// Compose retains equal remember keys. Comparing a replacement catalog with that old key
// would walk every mapping again on each frame, including in offscreen pager pages.
internal class SnapshotIdentity(private val snapshot: Any?) {
  override fun equals(other: Any?): Boolean = other is SnapshotIdentity && snapshot === other.snapshot
  override fun hashCode(): Int = System.identityHashCode(snapshot)
}
