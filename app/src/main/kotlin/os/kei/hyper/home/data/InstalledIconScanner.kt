package os.kei.hyper.home.data

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.content.res.Configuration
import java.util.concurrent.atomic.AtomicLong
import os.kei.hyper.home.xposed.IconResourceInspector
import os.kei.hyper.home.xposed.IconSupportStatus

enum class IconCapabilityStatus {
  DETECTED,
  NOT_DETECTED,
  UNKNOWN,
}

data class InstalledIconCapability(
  val label: String,
  val packageName: String,
  val userId: Long,
  val personalProfile: Boolean,
  val nightMode: IconCapabilityStatus,
  val monochrome: IconCapabilityStatus,
  val monochromeFallbackRecommended: Boolean = false,
  val versionCode: Long = -1L,
  val iconResourceIds: Set<Int> = emptySet(),
  val systemApp: Boolean = false,
  val hasLauncher: Boolean = true,
  val updatedAt: Long = 0,
  val componentName: String? = null,
  val overlayApp: Boolean = false,
)

internal data class InstalledIconInventory(
  val capabilities: List<InstalledIconCapability>,
  val fullRefresh: Boolean,
)

class InstalledIconScanner(private val context: Context) {
  private val packageManager = context.packageManager
  private val launcherApps = context.getSystemService(android.content.pm.LauncherApps::class.java)
  private val userManager = context.getSystemService(UserManager::class.java)
  private val assessments = PackageAssessmentCache<AssessmentKey, os.kei.hyper.home.xposed.IconResourceAssessment>()
  private val overlayRevision = AtomicLong()
  private var inventory: Inventory? = null

  private data class Inventory(
    val scope: PackageInventoryScope<Configuration>,
    val rows: List<InstalledIconCapability>,
  )

  private val ordering = compareByDescending<InstalledIconCapability> {
    it.nightMode == IconCapabilityStatus.DETECTED
  }.thenBy { it.label.lowercase() }.thenBy { it.packageName }

  internal fun invalidate(targets: Set<PackageRefreshTarget>) {
    assessments.invalidate { key -> targets.any { it.matches(key.packageName, key.userSerial) } }
  }

  fun invalidateAll() {
    overlayRevision.incrementAndGet()
    assessments.invalidate()
  }

  private data class AssessmentKey(
    val packageName: String,
    val userSerial: Long,
    val apk: OverlayApkIdentity,
    val version: Long,
    val updated: Long,
    val activities: List<Pair<String, Int>>,
    val configuration: Configuration,
    val overlayRevision: Long,
  )

  fun scan(checkCancelled: () -> Unit = {}): List<InstalledIconCapability> =
    refresh(PackageRefreshBatch(emptySet(), true), checkCancelled).capabilities

  /** One worker owns each scanner. Only successfully completed inventories become merge baselines. */
  internal fun refresh(
    request: PackageRefreshBatch,
    checkCancelled: () -> Unit = {},
  ): InstalledIconInventory {
    checkCancelled()
    val apps = requireNotNull(launcherApps) { "LauncherApps unavailable" }
    val users = userManager?.userProfiles.orEmpty()
    check(users.isNotEmpty()) { "User profiles unavailable" }
    val profiles = users.associateBy { userManager?.getSerialNumberForUser(it) ?: -1L }
    val configuration = Configuration(context.resources.configuration)
    var scope = PackageInventoryScope(configuration, profiles.keys.toSet(), overlayRevision.get())
    val previous = inventory
    val inspector = IconResourceInspector(packageManager)
    val revision = assessments.revision
    var fullRefresh = false
    val result = refreshPackageInventory(
      previous?.rows, previous?.scope, scope, request,
      { PackageRefreshTarget(it.packageName, it.userId) }, { it.overlayApp }, ordering,
      readTarget = { target ->
        checkCancelled()
        readTarget(apps, inspector, configuration, profiles.getValue(target.userSerial!!),
          target.packageName, revision, scope.resourceRevision, checkCancelled)
      },
      readFull = {
        fullRefresh = true
        if (previous == null || overlayRevision.get() == previous.scope.resourceRevision) invalidateAll()
        scope = scope.copy(resourceRevision = overlayRevision.get())
        val fullRevision = assessments.revision
        users.flatMap { user ->
          scanUser(apps, inspector, configuration, user, fullRevision, scope.resourceRevision, checkCancelled)
        }.sortedWith(ordering)
      },
    )
    checkCancelled()
    if (overlayRevision.get() == scope.resourceRevision) inventory = Inventory(scope, result)
    return InstalledIconInventory(result, fullRefresh)
  }

  private fun scanUser(
    apps: LauncherApps,
    inspector: IconResourceInspector,
    configuration: android.content.res.Configuration,
    user: UserHandle,
    revision: Long,
    overlayRevision: Long,
    checkCancelled: () -> Unit,
  ): List<InstalledIconCapability> {
    checkCancelled()
    val launched = apps.getActivityList(null, user).groupBy { it.componentName.packageName }
    val installed = if (user == Process.myUserHandle()) packageManager.getInstalledPackages(
      PackageManager.PackageInfoFlags.of(0)) else emptyList()
    val infos = installed.associateBy { it.packageName }
    return (launched.keys + infos.keys).mapNotNull { packageName ->
      checkCancelled()
      val entries = launched[packageName].orEmpty()
      val info = infos[packageName] ?: if (user == Process.myUserHandle()) {
        try { packageManager.getPackageInfo(packageName, 0) }
        catch (_: PackageManager.NameNotFoundException) { return@mapNotNull null }
      } else null
      val app = entries.firstOrNull()?.activityInfo?.applicationInfo ?: info?.applicationInfo
        ?: return@mapNotNull null
      buildRow(inspector, configuration, user, packageName, entries, info, app, revision,
        overlayRevision, checkCancelled)
    }
  }

  private fun readTarget(
    apps: LauncherApps, inspector: IconResourceInspector, configuration: Configuration,
    user: UserHandle, packageName: String, revision: Long, overlayRevision: Long,
    checkCancelled: () -> Unit,
  ): InstalledIconCapability? {
    val entries = apps.getActivityList(packageName, user)
    val personal = user == Process.myUserHandle()
    val info = if (personal) try { packageManager.getPackageInfo(packageName, 0) }
      catch (_: PackageManager.NameNotFoundException) { return null }
    else null
    if (!personal && entries.isEmpty()) return null
    val app = entries.firstOrNull()?.activityInfo?.applicationInfo ?: info?.applicationInfo
      ?: error("ApplicationInfo unavailable for $packageName")
    return buildRow(inspector, configuration, user, packageName, entries, info, app, revision,
      overlayRevision, checkCancelled)
  }

  private fun buildRow(
    inspector: IconResourceInspector, configuration: Configuration, user: UserHandle,
    packageName: String, entries: List<LauncherActivityInfo>, info: PackageInfo?, app: ApplicationInfo,
    revision: Long, overlayRevision: Long, checkCancelled: () -> Unit,
  ): InstalledIconCapability {
    checkCancelled()
    val sourceActivities = entries.map(LauncherActivityInfo::getActivityInfo).ifEmpty {
      listOf(android.content.pm.ActivityInfo().apply {
        this.packageName = packageName; applicationInfo = app; icon = app.icon; name = packageName
      })
    }
    val overlay = isOverlay(app)
    val serial = userManager?.getSerialNumberForUser(user) ?: -1L
    // Public PackageManager is scoped to the current user. Never substitute its metadata for work apps.
    val version = info?.longVersionCode ?: -1L
    val updated = info?.lastUpdateTime ?: app.sourceDir?.let { java.io.File(it).lastModified() } ?: 0L
    val assessment = if (overlay) null else {
      val apk = app.sourceDir?.let {
        overlayApkIdentity(packageName, app.uid, listOf(it) + app.splitSourceDirs.orEmpty())
      }
      val key = apk?.let { AssessmentKey(packageName, serial, it, version, updated,
        sourceActivities.map { activity -> activity.name to activity.iconResource },
        configuration, overlayRevision) }
      assessments.resolve(key, revision, cacheable = {
        it.nightMode != IconSupportStatus.UNKNOWN && it.monochrome != IconSupportStatus.UNKNOWN
      }) {
        checkCancelled()
        inspector.assess(sourceActivities, configuration).also { checkCancelled() }
      }
    }
    return InstalledIconCapability(
      label = entries.firstOrNull()?.label?.toString() ?: app.loadLabel(packageManager).toString(),
      packageName = packageName, userId = serial, personalProfile = user == Process.myUserHandle(),
      nightMode = assessment?.nightMode?.toCapabilityStatus() ?: IconCapabilityStatus.UNKNOWN,
      monochrome = assessment?.monochrome?.toCapabilityStatus() ?: IconCapabilityStatus.UNKNOWN,
      monochromeFallbackRecommended = assessment?.monochromeFallbackRecommended == true,
      versionCode = version, iconResourceIds = sourceActivities.map { it.iconResource }.filter { it != 0 }.toSet(),
      overlayApp = overlay, systemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
      hasLauncher = entries.isNotEmpty(), updatedAt = updated,
      componentName = entries.firstOrNull()?.componentName?.className,
    )
  }

  private fun isOverlay(info: android.content.pm.ApplicationInfo): Boolean =
    isResourceOverlay(packageManager, info)
}

private fun IconSupportStatus.toCapabilityStatus(): IconCapabilityStatus =
  when (this) {
    IconSupportStatus.DETECTED -> IconCapabilityStatus.DETECTED
    IconSupportStatus.NOT_DETECTED -> IconCapabilityStatus.NOT_DETECTED
    IconSupportStatus.UNKNOWN -> IconCapabilityStatus.UNKNOWN
  }
