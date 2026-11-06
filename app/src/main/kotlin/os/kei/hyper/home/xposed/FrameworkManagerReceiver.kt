package os.kei.hyper.home.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.status.FrameworkManagerProtocol

/** A fixed manager-launch operation, available only to this module's signed App. */
internal class FrameworkManagerReceiver(
  private val runActive: (() -> Unit) -> Unit,
  private val onFailure: (Throwable) -> Unit,
) {
  private var owner: Context? = null
  @Volatile private var registered = false
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (!registered || intent.action != FrameworkManagerProtocol.ACTION || !isOrderedBroadcast) return
      try {
        // Read the actual sender, shared through BroadcastOptions, never a caller-supplied UID.
        val appUid = context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0)
        if (sentFromUid != appUid || sentFromPackage != BuildConfig.APPLICATION_ID) return
        val target = intent.getIntExtra("target", -1)
        val flavor = intent.getIntExtra("flavor", -1)
        val deadline = intent.getLongExtra("deadline", 0)
        if (!FrameworkManagerProtocol.valid(target, flavor, deadline, SystemClock.elapsedRealtime())) return
        runActive {
          val launch = FrameworkManagerProtocol.parasiticIntent(context, target, flavor)
          if (registered && deadline > SystemClock.elapsedRealtime()) {
            context.startActivity(launch)
            resultCode = FrameworkManagerProtocol.OPENED
          }
        }
      } catch (failure: Exception) { onFailure(failure) }
    }
  }

  @Synchronized fun start(context: Context, handler: Handler) {
    if (registered) return
    context.registerReceiver(receiver, IntentFilter(FrameworkManagerProtocol.ACTION),
      FrameworkManagerProtocol.PERMISSION, handler, Context.RECEIVER_EXPORTED)
    owner = context
    registered = true
  }

  @Synchronized fun stop() {
    registered = false
    val previous = owner
    owner = null
    if (previous != null) try {
      previous.unregisterReceiver(receiver)
    } catch (failure: IllegalArgumentException) {
      onFailure(failure)
    }
  }
}
