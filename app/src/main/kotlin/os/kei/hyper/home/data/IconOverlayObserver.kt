package os.kei.hyper.home.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler

/** Overlay broadcasts identify the resource target, not the overlay APK being installed. */
internal class IconOverlayObserver(
  context: Context, handler: Handler, onChanged: (String) -> Unit,
) : AutoCloseable {
  private val context = context.applicationContext
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action != ACTION || intent.data?.scheme != "package") return
      val target = intent.data?.schemeSpecificPart ?: return
      if (target == "android" || target.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
        onChanged(target)
    }
  }
  init {
    this.context.registerReceiver(receiver, IntentFilter(ACTION).apply { addDataScheme("package") },
      null, handler, Context.RECEIVER_NOT_EXPORTED)
  }
  override fun close() = context.unregisterReceiver(receiver)

  private companion object {
    const val ACTION = "android.intent.action.OVERLAY_CHANGED"
  }
}
