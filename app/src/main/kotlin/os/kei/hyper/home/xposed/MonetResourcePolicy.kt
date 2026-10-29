package os.kei.hyper.home.xposed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.UserHandle
import android.os.UserManager
import android.os.SystemClock
import java.io.File

/** Policy for HYOS's package-keyed monochrome selector, not activity aliases. */
internal class MonetResourcePolicy {
  private data class Source(
    val packageName: String,
    val path: String,
    val length: Long,
    val modified: Long,
    val icon: Int,
  )

  private var previousSources: List<Source>? = null
  private var previousConfiguration: Configuration? = null
  private var previousDenied: Set<String> = emptySet()
  private var previousUserId: Int? = null
  private var retryAfter = 0L

  fun deniedPackages(context: Context, isCurrent: () -> Boolean): Set<String> {
    val userId = Context::class.java.getMethod("getUserId").invoke(context) as Int
    val manager = context.getSystemService(UserManager::class.java) ?: return emptySet()
    // LauncherApps.getProfiles uses Binder caller user 0 in system_server,
    // even when its Context has been scoped to another foreground user.
    val userInfo = Class.forName("android.content.pm.UserInfo")
    val handles = userInfo.getMethod("getUserHandle")
    val profiles = (UserManager::class.java.getMethod(
      "getEnabledProfiles", Int::class.javaPrimitiveType,
    ).invoke(manager, userId) as? List<*>)?.map {
      handles.invoke(it) as UserHandle
    } ?: return emptySet()
    if (profiles.isEmpty()) return emptySet()
    // A desktop entry or pinned shortcut can borrow another package's icon.
    // Inspect resource owners without requiring their own MAIN/LAUNCHER entry.
    // This privileged enumeration runs only in system_server, not the module UI.
    val applications = profiles.flatMap { user ->
      val scoped = Context::class.java.getMethod(
        "createContextAsUser", UserHandle::class.java, Int::class.javaPrimitiveType,
      ).invoke(context, user, 0) as Context
      scoped.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        .filter { it.icon != 0 }
    }
    val byPackage = applications.groupBy { it.packageName }
    val configuration = Configuration(context.resources.configuration)
    val sources = applications.map { app ->
      val file = File(app.sourceDir)
      Source(app.packageName, app.sourceDir, file.length(), file.lastModified(), app.icon)
    }.distinct().sortedWith(compareBy(Source::packageName, Source::path, Source::icon))
    if (userId == previousUserId && sources == previousSources &&
      configuration == previousConfiguration && SystemClock.elapsedRealtime() < retryAfter
    ) return previousDenied

    // A package-only Dart key cannot represent conflicting profile resources.
    // Keep the original selection when profiles disagree about the source/icon.
    val unambiguous = sources.groupBy(Source::packageName).filterValues { it.size == 1 }.keys
    val representatives: List<ApplicationInfo> = byPackage
      .filterKeys { it in unambiguous }
      .values.map { it.first() }
    val masks = representatives.chunked(128).flatMap { batch ->
      if (!isCurrent()) return emptySet()
      IconResourceProbeClient(context).applicationMasks(batch, isCurrent).launcherMasks.entries
    }.associate { it.toPair() }
    if (!isCurrent()) return emptySet()
    val denied = masks.filterValues { it == LauncherMaskStatus.UNUSABLE }.keys.toSet()
    // Unknown evidence never denies an icon. Retry it, but do not repeatedly
    // rasterize every resource on each native policy lease renewal.
    previousSources = sources
    previousUserId = userId
    previousConfiguration = configuration
    previousDenied = denied
    retryAfter = if (masks.size == representatives.size &&
      masks.values.none { it == LauncherMaskStatus.UNKNOWN }
    ) Long.MAX_VALUE else SystemClock.elapsedRealtime() + 60_000L
    return denied
  }
}
