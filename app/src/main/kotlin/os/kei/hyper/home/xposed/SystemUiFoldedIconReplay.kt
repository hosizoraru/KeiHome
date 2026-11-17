package os.kei.hyper.home.xposed

import android.os.Handler
import java.util.concurrent.atomic.AtomicBoolean

/** Folded notifications flatten several icons into one RemoteViews bitmap. */
internal class SystemUiFoldedIconReplay(
  private val main: Handler,
  private val rebuild: () -> Unit,
) {
  @Volatile private var stopped = false
  private val queued = AtomicBoolean()
  private val replay = Runnable {
    queued.set(false)
    if (!stopped) rebuild()
  }

  fun request() {
    if (!stopped && queued.compareAndSet(false, true) && !main.postDelayed(replay, 150))
      queued.set(false)
  }

  fun stop() {
    stopped = true
    main.removeCallbacks(replay)
    queued.set(false)
  }
}
