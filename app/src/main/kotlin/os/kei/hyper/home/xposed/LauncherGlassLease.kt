package os.kei.hyper.home.xposed

/** Independent wallpaper-material lease; only a current ACK acknowledges the saved choice. */
internal class LauncherGlassLease {
  private data class Ack(val style: Int, val user: Int, val process: NativeMonetProcessIdentity, val at: Long)
  private var previous: Ack? = null
  private var dirty = false

  fun acknowledgedStyle(process: NativeMonetProcessIdentity?, user: Int, now: Long): Int? =
    previous?.takeIf {
      !dirty && it.process == process && it.user == user && now >= it.at && now - it.at < EXPIRY_MS
    }?.style

  fun synchronize(style: Int, user: Int, now: () -> Long, isCurrent: () -> Boolean,
    exchange: (onWriteAttempted: () -> Unit) -> NativeMonetProcessIdentity?): Boolean {
    if (!NativeMonetProtocol.validGlassPolicy(style) || user < 0 || !isCurrent()) return false
    val started = now()
    var attempted = false
    var committed = false
    try {
      val process = exchange { attempted = true } ?: return false
      if (!isCurrent()) return false
      val old = previous
      // Reconcile a resident override on the first SYSTEM ACK too; normal renewals stay quiet.
      val changed = dirty || old == null || old.user != user || old.process != process ||
        old.style != style || started < old.at || started - old.at >= EXPIRY_MS
      previous = Ack(style, user, process, started)
      dirty = false
      committed = true
      return changed
    } finally { if (attempted && !committed) dirty = true }
  }

  private companion object { const val EXPIRY_MS = 45_000L }
}
