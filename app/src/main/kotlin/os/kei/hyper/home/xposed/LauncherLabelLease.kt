package os.kei.hyper.home.xposed

/** Only an authenticated, current native ACK can advance this lease. */
internal class LauncherLabelLease {
  private data class Ack(val mode: Int, val user: Int, val process: NativeMonetProcessIdentity, val at: Long)
  private var previous: Ack? = null
  private var dirty = false

  fun acknowledgedMode(process: NativeMonetProcessIdentity?, now: Long, user: Int? = null): Int? = previous?.takeIf {
    !dirty && it.process == process && (user == null || it.user == user) && now >= it.at && now - it.at < 45_000
  }?.mode

  fun synchronize(mode: Int, user: Int, now: () -> Long, isCurrent: () -> Boolean,
    exchange: (onWriteAttempted: () -> Unit) -> NativeMonetProcessIdentity?): Boolean {
    if (mode !in 0..2 || !isCurrent()) return false
    val started = now()
    var attempted = false
    var committed = false
    try {
      val process = exchange { attempted = true } ?: return false
      if (!isCurrent()) return false
      val old = previous
      val changed = dirty || if (old == null || old.user != user || old.process != process) mode != 0
        else old.mode != mode || (mode != 0 && (started < old.at || started - old.at >= 45_000))
      previous = Ack(mode, user, process, started)
      dirty = false
      committed = true
      return changed
    } finally { if (attempted && !committed) dirty = true }
  }
}
