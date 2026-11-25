package os.kei.hyper.home.status

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.SystemDarkStyle

internal object SystemDarkStyleProtocol {
  const val ACTION = BuildConfig.APPLICATION_ID + ".SYSTEM_DARK_STYLE"
  const val STYLE = "style"

  fun parse(value: String?): SystemDarkStyle? = SystemDarkStyle.entries.firstOrNull { it.name == value }

  suspend fun request(context: Context, style: SystemDarkStyle? = null): SystemDarkStyle? =
    withTimeoutOrNull(5_000) {
      suspendCancellableCoroutine { continuation ->
        val result = object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            if (continuation.isActive) continuation.resume(parse(resultData).takeIf { resultCode == 1 })
          }
        }
        try {
          val request = Intent(ACTION).setPackage("android")
          if (style != null) request.putExtra(STYLE, style.name)
          context.sendOrderedBroadcast(request, null,
            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
            result, Handler(Looper.getMainLooper()), 0, null, null)
        } catch (_: Exception) {
          if (continuation.isActive) continuation.resume(null)
        }
      }
    }
}
