package os.kei.hyper.home.status

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import os.kei.hyper.home.BuildConfig
import kotlin.coroutines.resume

internal object FrameworkManagerProtocol {
  const val ACTION = BuildConfig.APPLICATION_ID + ".OPEN_FRAMEWORK_MANAGER"
  const val PERMISSION = BuildConfig.APPLICATION_ID + ".permission.OPEN_FRAMEWORK_MANAGER"
  const val MAIN = 0
  const val LOGS = 1
  const val LSPOSED = 0
  const val VECTOR = 1
  const val OPENED = 1
  const val HOST = "com.android.shell"
  const val DIAL_CODE = "*#*#5776733#*#*"
  const val DEADLINE_MS = 1_500L

  fun flavor(name: String?): Int? = when {
    name.equals("LSPosed", true) -> LSPOSED
    name.equals("Vector", true) -> VECTOR
    else -> null
  }

  fun valid(target: Int, flavor: Int, deadline: Long, now: Long): Boolean =
    target in MAIN..LOGS && flavor in LSPOSED..VECTOR &&
      deadline > now && deadline - now <= DEADLINE_MS

  fun managerPackage(flavor: Int): String = when (flavor) {
    LSPOSED -> "org.lsposed.manager"
    VECTOR -> "org.matrix.vector.manager"
    else -> error("Unsupported framework")
  }

  /** Follows the daemon's main-process activity selection; no component/version table. */
  fun parasiticIntent(context: Context, target: Int, flavor: Int): Intent {
    require(target in MAIN..LOGS)
    val intent = context.packageManager.getLaunchIntentForPackage(HOST) ?: run {
      val info = context.packageManager.getPackageInfo(HOST, PackageManager.GET_ACTIVITIES)
      val activity = info.activities.orEmpty().first { it.enabled && it.processName == HOST }
      Intent(Intent.ACTION_MAIN).setComponent(ComponentName(HOST, activity.name))
    }
    return intent
      .addCategory(managerPackage(flavor) + ".LAUNCH_MANAGER")
      // The injected host Activity keeps its own manifest's launch mode.
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      .apply { if (target == LOGS) data = Uri.parse("logs") }
  }
}

internal object FrameworkManagerLauncher {
  suspend fun open(context: Context, target: Int): Boolean {
    require(target in FrameworkManagerProtocol.MAIN..FrameworkManagerProtocol.LOGS)
    val flavor = withContext(Dispatchers.IO) {
      runCatching {
        FrameworkManagerProtocol.flavor(FrameworkConnection.service.value?.frameworkName)
      }.getOrNull()
    }
    if (flavor != null && requestParasitic(context, target, flavor)) return true
    val intent = withContext(Dispatchers.IO) {
      val packages = listOfNotNull(flavor?.let(FrameworkManagerProtocol::managerPackage),
        "org.lsposed.manager", "org.matrix.vector.manager").distinct()
      packages.firstNotNullOfOrNull { name ->
        runCatching { context.packageManager.getLaunchIntentForPackage(name) }.getOrNull()
      }?.apply {
        if (target == FrameworkManagerProtocol.LOGS) {
          data = Uri.parse("logs")
          addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
      }
    } ?: return false
    return runCatching { context.startActivity(intent); true }.getOrDefault(false)
  }

  internal suspend fun requestParasitic(context: Context, target: Int, flavor: Int): Boolean =
    withTimeoutOrNull(2_000L) {
      suspendCancellableCoroutine { continuation ->
        val result = object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            if (continuation.isActive) continuation.resume(resultCode == FrameworkManagerProtocol.OPENED)
          }
        }
        val request = Intent(FrameworkManagerProtocol.ACTION).setPackage("android")
          .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
          .putExtra("target", target).putExtra("flavor", flavor)
          .putExtra("deadline", SystemClock.elapsedRealtime() + FrameworkManagerProtocol.DEADLINE_MS)
        try {
          context.sendOrderedBroadcast(request, null,
            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
            result, Handler(Looper.getMainLooper()), 0, null, null)
        } catch (_: Exception) {
          if (continuation.isActive) continuation.resume(false)
        }
      }
    } ?: false
}
