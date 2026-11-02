package os.kei.hyper.home.xposed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.UserHandle
import android.os.UserManager
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Policy for HYOS's package-keyed monochrome selector, not activity aliases. */
internal class MonetResourcePolicy(private val iconTheme: ActiveIconTheme) {
  private data class Source(
    val packageName: String,
    val path: String,
    val length: Long,
    val modified: Long,
    val icon: Int,
    val resources: List<ApkStamp>,
  )
  private data class ApkStamp(val path: String, val length: Long, val modified: Long)
  private data class CacheConfiguration(val userId: Int, val configuration: Configuration)

  private var previousSources: List<Source>? = null
  private var previousDenied: Set<String> = emptySet()
  private var previousUserId: Int? = null
  var themeSignature: ActiveIconTheme.Signature? = null
    private set
  private val masksCache = IconMaskCache<CacheConfiguration, Source>()
  private val invalidationLock = Any()
  private val revision = AtomicLong()
  private var invalidateAll = false
  private val invalidatedPackages = hashSetOf<String>()

  /** Only queue invalidation in system hooks; resource inspection remains on the worker. */
  fun invalidate(packageName: String? = null) = synchronized(invalidationLock) {
    revision.incrementAndGet()
    if (packageName == null) {
      invalidateAll = true
      invalidatedPackages.clear()
    } else if (!invalidateAll) invalidatedPackages += packageName
  }

  fun deniedPackages(context: Context, isCurrent: () -> Boolean): Set<String> {
    val resourceRevision = synchronized(invalidationLock) {
      if (invalidateAll) masksCache.invalidate()
      else invalidatedPackages.forEach(masksCache::invalidate)
      invalidateAll = false
      invalidatedPackages.clear()
      revision.get()
    }
    val active = { isCurrent() && revision.get() == resourceRevision }
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
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val components = profiles.flatMap { launcherApps?.getActivityList(null, it).orEmpty() }
      .groupBy { it.componentName.packageName }
      .mapValues { (_, entries) -> entries.map { it.componentName.className }.toSet() }
    val theme = iconTheme.snapshot(byPackage.keys, components, modEnabled = true)
    if (!theme.complete) {
      // A replacing/unreadable theme must not inherit the previous pack's APK-Monet choices.
      // Keep every icon on the original native source until this inventory can be read again.
      themeSignature = theme.signature
      return byPackage.keys
    }
    val sameTheme = theme.signature == themeSignature
    if (!sameTheme) android.util.Log.i("KeiHomeIconTheme",
      "Resource inventory complete=${theme.complete} themedPackages=${theme.coveredPackages.size}")
    val configuration = Configuration(context.resources.configuration)
    val sources = applications.map { app ->
      val file = File(app.sourceDir)
      val paths = (listOfNotNull(app.sourceDir, app.publicSourceDir) +
        app.splitSourceDirs.orEmpty() + app.splitPublicSourceDirs.orEmpty()).distinct()
      Source(app.packageName, app.sourceDir, file.length(), file.lastModified(), app.icon,
        paths.map { path -> File(path).let { ApkStamp(path, it.length(), it.lastModified()) } })
    }.distinct().sortedWith(compareBy(Source::packageName, Source::path, Source::icon))

    // A package-only Dart key cannot represent conflicting profile resources.
    // Keep the original selection when profiles disagree about the source/icon.
    val unambiguous = sources.groupBy(Source::packageName).filterValues { it.size == 1 }.keys
    val representatives: List<ApplicationInfo> = byPackage
      .filterKeys { it in unambiguous && it !in theme.coveredPackages }
      .values.map { it.first() }
    val sourceByPackage = sources.filter { it.packageName in unambiguous }.associateBy { it.packageName }
    val cacheKey = CacheConfiguration(userId, configuration)
    val reusable = masksCache.reuse(cacheKey, sourceByPackage, SystemClock.elapsedRealtime())
    val pending = representatives.filter { it.packageName !in reusable }
    if (pending.isNotEmpty()) android.util.Log.i("KeiHomeIconMasks",
      "Validating masks pending=${pending.size} reused=${representatives.size - pending.size}")
    val fresh = if (pending.isEmpty()) emptyMap() else
      IconResourceProbeClient(context).applicationMasks(pending, active).launcherMasks
    checkIconProbeActive(active)
    val masks = reusable + fresh
    val previousByPackage = previousSources.orEmpty().groupBy(Source::packageName)
    val unchanged = sources.groupBy(Source::packageName)
      .filter { (name, entries) -> previousByPackage[name] == entries }.keys
    val retained = previousDenied.takeIf { userId == previousUserId }.orEmpty()
    val denied = retainedUnusableMasks(masks, retained, unchanged) + (byPackage.keys - unambiguous)
    // An interrupted probe does not make a known bad mask usable. Retry unknown
    // resources without dropping their last result for the same installed source.
    previousSources = sources
    previousUserId = userId
    previousDenied = denied
    themeSignature = theme.signature
    masksCache.record(cacheKey, pending.associate { it.packageName to sourceByPackage.getValue(it.packageName) },
      fresh, SystemClock.elapsedRealtime())
    // The Dart veto selects the launcher's original styled path, preserving theme day/night art.
    // A theme PNG/MAML is not an APK monochrome mask and must not inherit its verdict.
    return denied + theme.coveredPackages
  }
}

internal fun retainedUnusableMasks(
  masks: Map<String, LauncherMaskStatus>, previousDenied: Set<String>, unchanged: Set<String>,
): Set<String> = masks.filterValues { it == LauncherMaskStatus.UNUSABLE }.keys +
  previousDenied.filter { name ->
    name in unchanged && (masks[name] == null || masks[name] == LauncherMaskStatus.UNKNOWN)
  }
