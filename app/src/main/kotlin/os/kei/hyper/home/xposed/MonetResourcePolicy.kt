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
  private var previousDenied: Set<String> = emptySet()
  private var previousUserId: Int? = null
  private data class CachedMasks(val configuration: Configuration, val denied: Set<String>, val retryAfter: Long)
  private val configurations = ArrayDeque<CachedMasks>()

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
    if (userId != previousUserId || sources != previousSources) configurations.clear()
    val cached = configurations.firstOrNull { it.configuration == configuration }
    if (cached != null && SystemClock.elapsedRealtime() < cached.retryAfter) return cached.denied

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
    val previousByPackage = previousSources.orEmpty().groupBy(Source::packageName)
    val unchanged = sources.groupBy(Source::packageName)
      .filter { (name, entries) -> previousByPackage[name] == entries }.keys
    val retained = cached?.denied ?: previousDenied.takeIf { userId == previousUserId }.orEmpty()
    val denied = retainedUnusableMasks(masks, retained, unchanged)
    // An interrupted probe does not make a known bad mask usable. Retry unknown
    // resources without dropping their last result for the same installed source.
    previousSources = sources
    previousUserId = userId
    previousDenied = denied
    val retryAfter = if (masks.size == representatives.size &&
      masks.values.none { it == LauncherMaskStatus.UNKNOWN }
    ) Long.MAX_VALUE else SystemClock.elapsedRealtime() + 60_000L
    // Keep both night variants warm; source/profile changes invalidate the small configuration cache.
    configurations.removeAll { it.configuration == configuration }
    configurations.addLast(CachedMasks(configuration, denied, retryAfter))
    if (configurations.size > 4) configurations.removeFirst()
    return denied
  }
}

internal fun retainedUnusableMasks(
  masks: Map<String, LauncherMaskStatus>, previousDenied: Set<String>, unchanged: Set<String>,
): Set<String> = masks.filterValues { it == LauncherMaskStatus.UNUSABLE }.keys +
  previousDenied.filter { name ->
    name in unchanged && (masks[name] == null || masks[name] == LauncherMaskStatus.UNKNOWN)
  }
