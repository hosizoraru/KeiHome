package os.kei.hyper.home.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler

/** Real PackageManager broadcasts; synthetic launcher refresh callbacks cannot feed this observer. */
internal class PackageChangeObserver(
  private val context: Context, handler: Handler, onChanged: (String) -> Unit,
) : AutoCloseable {
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) &&
        intent.action != Intent.ACTION_PACKAGE_REPLACED) return
      val name = intent.data?.takeIf { it.scheme == "package" }?.schemeSpecificPart ?: return
      if (name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) onChanged(name)
    }
  }
  init {
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REPLACED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addAction(Intent.ACTION_PACKAGE_CHANGED)
      addDataScheme("package")
    }
    context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
  }
  override fun close() = context.unregisterReceiver(receiver)
}
