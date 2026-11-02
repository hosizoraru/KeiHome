package os.kei.hyper.home.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler

/** Ordinary minute ticking stays with the native, visibility-bound MAML updater. */
internal class IconTimeObserver(
  private val context: Context, handler: Handler, onChanged: (calendarOnly: Boolean) -> Unit,
) : AutoCloseable {
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      when (intent.action) {
        Intent.ACTION_DATE_CHANGED -> onChanged(true)
        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> onChanged(false)
      }
    }
  }
  init {
    context.registerReceiver(receiver, IntentFilter().apply {
      addAction(Intent.ACTION_DATE_CHANGED)
      addAction(Intent.ACTION_TIME_CHANGED)
      addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }, null, handler, Context.RECEIVER_NOT_EXPORTED)
  }
  override fun close() = context.unregisterReceiver(receiver)
}
