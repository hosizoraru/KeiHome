package os.kei.hyper.home.xposed

/** Owned by the existing monitor; a save or a greeting is never a setting ACK. */
internal class DockGlassLease {
  private data class Ack(val enabled: Boolean, val cornerQ8: Int, val user: Int, val process: NativeMonetProcessIdentity, val at: Long)
  private var previous: Ack? = null
  private var dirty = false

  fun hasAcknowledgement(user: Int, now: Long): Boolean = previous?.let {
    !dirty && it.user == user && now >= it.at && now - it.at < 45_000
  } == true

  fun acknowledgedEnabled(process: NativeMonetProcessIdentity?, user: Int, now: Long): Boolean? =
    previous?.takeIf {
      !dirty && it.process == process && it.user == user && now >= it.at && now - it.at < 45_000
    }?.enabled

  fun synchronize(enabled: Boolean, user: Int, now: () -> Long, isCurrent: () -> Boolean, cornerQ8: Int = 0,
    exchange: (onWriteAttempted: () -> Unit) -> NativeMonetProcessIdentity?): Boolean {
    if (!isCurrent()) return false
    val started = now()
    var attempted = false
    var committed = false
    try {
      val process = exchange { attempted = true } ?: return false
      if (!isCurrent()) return false
      val old = previous
      // The first off ACK must also reconcile a resident bridge left enabled by
      // an old API-102 owner. Unchanged renewals do not schedule another callback.
      val changed = dirty || old == null || old.user != user || old.process != process ||
        old.enabled != enabled || enabled && old.cornerQ8 != cornerQ8 || started < old.at || started - old.at >= 45_000
      previous = Ack(enabled, cornerQ8, user, process, started)
      dirty = false
      committed = true
      return changed
    } finally { if (attempted && !committed) dirty = true }
  }
}
