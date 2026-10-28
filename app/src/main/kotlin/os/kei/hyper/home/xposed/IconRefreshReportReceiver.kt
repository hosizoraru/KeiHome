package os.kei.hyper.home.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class IconRefreshReportReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!isTrustedReportSender(sentFromUid)) return
    val report = IconRefreshReportProtocol.read(intent) ?: return
    val pending = goAsync()
    try {
      executor.execute {
        try {
          IconRefreshReportStore.record(context, report)
        } finally {
          pending.finish()
        }
      }
    } catch (_: Throwable) {
      pending.finish()
    }
  }

  private companion object {
    val executor = ThreadPoolExecutor(
      0, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(64),
      { runnable -> Thread(runnable, "KeiHomeReportStore").apply { isDaemon = true } },
    )
  }
}

internal fun isTrustedReportSender(uid: Int): Boolean = uid == Process.SYSTEM_UID
