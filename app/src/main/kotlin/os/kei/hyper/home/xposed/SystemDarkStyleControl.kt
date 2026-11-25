package os.kei.hyper.home.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.PatternMatcher
import android.os.UserHandle
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.status.SystemDarkStyleProtocol as Protocol
import os.kei.hyper.home.status.WallpaperPreviewProtocol

internal fun registerSystemDarkStyle(
  context: Context,
  handler: Handler,
  registrations: MonitorRegistrations,
  active: () -> Boolean,
  onFailure: (Throwable) -> Unit,
) {
  val owner = UserHandle.getUserHandleForUid(0)
  val overlays by lazy { SystemDarkStyleOverlays(context) }
  val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (!active() || !isOrderedBroadcast || sentFromPackage != BuildConfig.APPLICATION_ID ||
        sentFromUid < 0 || UserHandle.getUserHandleForUid(sentFromUid) != owner) return
      val uid = runCatching { context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0) }.getOrNull()
      if (uid != sentFromUid) return
      val requested = Protocol.parse(intent.getStringExtra(Protocol.STYLE))
      if (intent.hasExtra(Protocol.STYLE) && requested == null) return
      try {
        if (requested != null) overlays.apply(requested)
        setResult(1, overlays.read().name, null)
      } catch (failure: Exception) {
        onFailure(failure)
      }
    }
  }
  context.registerReceiver(receiver, IntentFilter(Protocol.ACTION), WallpaperPreviewProtocol.PERMISSION,
    handler, Context.RECEIVER_EXPORTED)
  registrations.own { context.unregisterReceiver(receiver) }

  val uninstall = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val uid = intent.getIntExtra(Intent.EXTRA_UID, -1)
      if (active() && uid >= 0 && UserHandle.getUserHandleForUid(uid) == owner) {
        runCatching { overlays.restore() }.onFailure(onFailure)
      }
    }
  }
  val filter = IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply {
    addDataScheme("package")
    addDataSchemeSpecificPart(BuildConfig.APPLICATION_ID, PatternMatcher.PATTERN_LITERAL)
  }
  context.registerReceiver(uninstall, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
  registrations.own { context.unregisterReceiver(uninstall) }
}
