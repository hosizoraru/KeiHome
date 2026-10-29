package os.kei.hyper.home.xposed

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.UserHandle
import android.os.UserManager
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

  fun deniedPackages(context: Context, isCurrent: () -> Boolean): Set<String> {
    val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptySet()
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
    val activities = profiles.flatMap { launcherApps.getActivityList(null, it) }
    val byPackage = activities.groupBy { it.componentName.packageName }
    val configuration = Configuration(context.resources.configuration)
    val sources = activities.map { activity ->
      val app = activity.applicationInfo
      val file = File(app.sourceDir)
      Source(app.packageName, app.sourceDir, file.length(), file.lastModified(), app.icon)
    }.distinct().sortedWith(compareBy(Source::packageName, Source::path, Source::icon))
    if (userId == previousUserId && sources == previousSources &&
      configuration == previousConfiguration
    ) return previousDenied

    // A package-only Dart key cannot represent conflicting profile resources.
    // Keep the original selection when profiles disagree about the source/icon.
    val unambiguous = sources.groupBy(Source::packageName).filterValues { it.size == 1 }.keys
    val representatives: List<LauncherActivityInfo> = byPackage
      .filterKeys { it in unambiguous }
      .values.map { it.first() }
    val result = IconResourceProbeClient(context).probe(representatives, true, isCurrent)
    if (!isCurrent()) return emptySet()
    val denied = result.launcherMasks.filterValues { it == LauncherMaskStatus.UNUSABLE }.keys.toSet()
    // Retry incomplete replies; missing evidence never becomes a denial.
    if (result.launcherMasks.size == representatives.size &&
      result.launcherMasks.values.none { it == LauncherMaskStatus.UNKNOWN }
    ) {
      previousSources = sources
      previousUserId = userId
      previousConfiguration = configuration
      previousDenied = denied
    }
    return denied
  }
}
