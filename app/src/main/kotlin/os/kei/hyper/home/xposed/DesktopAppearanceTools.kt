package os.kei.hyper.home.xposed

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import os.kei.hyper.home.status.AppearanceRefreshResult
import os.kei.hyper.home.status.DesktopAppearanceAction
import os.kei.hyper.home.status.DesktopAppearanceDiagnostics
import os.kei.hyper.home.status.DesktopAppearanceInbox
import os.kei.hyper.home.status.DesktopAppearanceRefresh
import os.kei.hyper.home.status.DesktopAppearanceReport
import os.kei.hyper.home.status.RuntimeStatusInbox
import os.kei.hyper.home.status.RuntimeStatusRequest

/** On-demand diagnostics only; no shell, root, persistent subscription or extra polling worker. */
internal object DesktopAppearanceTools {
  fun handle(context: Context, request: RuntimeStatusRequest, isCurrent: () -> Boolean,
    systemClassLoader: ClassLoader?, diagnostic: (String) -> Unit): Boolean {
    if (!isCurrent()) return false
    val uri = Uri.parse("content://${RuntimeStatusInbox.AUTHORITY}")
    val resolver = context.contentResolver
    val result = if (request.desktopAction == DesktopAppearanceAction.REFRESH) {
      if (resolver.call(uri, DesktopAppearanceInbox.CLAIM, null,
          DesktopAppearanceInbox.claimedBundle(request.id, request.userId))?.getBoolean("accepted") != true)
        return false
      if (request.userId != currentUserId()) AppearanceRefreshResult.UNSUPPORTED else {
        val port = read("refresh interface") { ConfigurationPulse(requireNotNull(systemClassLoader)) }
        if (port == null) AppearanceRefreshResult.UNSUPPORTED else DesktopAppearanceRefresh.run(port, isCurrent)
      }
    } else null
    val home = runCatching {
      context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()
    val service = runCatching {
      context.packageManager.resolveService(
        Intent("android.service.wallpaper.WallPaperControllerService").setPackage("com.miui.miwallpaper"), 0)
        ?.serviceInfo?.let { "${it.packageName}/${it.name}" }
    }.getOrNull()
    val wallpaperDump = service?.let { dump("activity", arrayOf("service", it), diagnostic) }
    val (dark, subscribed) = if (home == null) null to null
      else DesktopAppearanceDiagnostics.wallpaper(wallpaperDump, request.userId, home)
    val homeDark = if (home == null) null else DesktopAppearanceDiagnostics.homeDarkIcons(
      dump("window", arrayOf("windows"), diagnostic), request.userId, home)
    diagnostic("appearance snapshot region=$dark subscription=$subscribed homeDark=$homeDark wallpaperService=${service != null}")
    val manager = context.getSystemService(UiModeManager::class.java)
    val report = DesktopAppearanceReport(request.id, request.userId, SystemClock.elapsedRealtime(),
      runCatching { manager?.nightMode?.takeIf { it in 0..3 } }.getOrNull(),
      read("current appearance") { ConfigurationPulse(requireNotNull(systemClassLoader)).canonicalMode().night() },
      dark, subscribed, homeDark,
      home?.let { runCatching { context.packageManager.getPackageInfo(it, 0).longVersionCode }.getOrNull() },
      result)
    resolver.call(uri, DesktopAppearanceInbox.REPORT, null, Bundle().apply { putString("report", report.encode()) })
    return true
  }

  private class ConfigurationPulse(loader: ClassLoader) : DesktopAppearanceRefresh.Port {
    private val internalClass = Class.forName("com.android.server.UiModeManagerInternal", false, loader)
    private val internal = Class.forName("com.android.server.LocalServices", false, loader)
      .getMethod("getService", Class::class.java).invoke(null, internalClass)
      ?: error("No mode service")
    private val canonical = internalClass.getMethod("getDisplayUiMode", Int::class.javaPrimitiveType)
    private val taskService = Class.forName("android.app.ActivityTaskManager")
      .getMethod("getService").invoke(null)
    private val update = Class.forName("android.app.IActivityTaskManager")
      .getMethod("updateConfiguration", Configuration::class.java)
    private val activityService = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
    private val configuration = Class.forName("android.app.IActivityManager").getMethod("getConfiguration")

    override fun canonicalMode(): Int = canonical.invoke(internal, 0) as Int
    override fun currentMode(): Int = (configuration.invoke(activityService) as Configuration).uiMode
    override fun applyMode(mode: Int) {
      // Nonpersistent delta: never set UiModeManager's selected mode, overrides or schedule.
      update.invoke(taskService, Configuration().apply { uiMode = mode })
    }
    override fun settle() = Thread.sleep(600)
  }

  private fun Int.night(): Boolean? = when (this and Configuration.UI_MODE_NIGHT_MASK) {
    Configuration.UI_MODE_NIGHT_YES -> true
    Configuration.UI_MODE_NIGHT_NO -> false
    else -> null
  }

  private fun currentUserId(): Int? = runCatching {
    Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
  }.getOrNull()

  /** On the existing worker; the service dump uses the framework's own transfer deadline. */
  private fun dump(service: String, arguments: Array<String>, diagnostic: (String) -> Unit): String? = read("$service diagnostics") {
    val binder = Class.forName("android.os.ServiceManager")
      .getMethod("getService", String::class.java).invoke(null, service) as? IBinder
    if (binder == null) {
      diagnostic("appearance $service binder unavailable")
      return null
    }
    BoundedBinderDiagnostics.read(binder, arguments).also {
      diagnostic("appearance $service diagnostic chars=${it?.length ?: 0}")
    }
  }

  private inline fun <T> read(label: String, block: () -> T): T? = try {
    block()
  } catch (failure: Exception) {
    Log.w("KeiHomeAppearance", "$label unavailable", failure)
    null
  }
}
