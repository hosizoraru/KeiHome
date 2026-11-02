package os.kei.hyper.home.data

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
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

class InstalledIconScanner(private val context: Context) {
  private val packageManager = context.packageManager
  private val launcherApps = context.getSystemService(android.content.pm.LauncherApps::class.java)
  private val userManager = context.getSystemService(UserManager::class.java)

  fun scan(checkCancelled: () -> Unit = {}): List<InstalledIconCapability> {
    val apps = launcherApps ?: return emptyList()
    val users = userManager?.userProfiles.orEmpty()
    if (users.isEmpty()) return emptyList()
    val inspector = IconResourceInspector(packageManager)
    val configuration = context.resources.configuration
    return users.flatMap { user ->
      scanUser(apps, inspector, configuration, user, checkCancelled)
    }.sortedWith(
      compareByDescending<InstalledIconCapability> {
        it.nightMode == IconCapabilityStatus.DETECTED
      }.thenBy { it.label.lowercase() }.thenBy { it.packageName },
    )
  }

  private fun scanUser(
    apps: android.content.pm.LauncherApps,
    inspector: IconResourceInspector,
    configuration: android.content.res.Configuration,
    user: UserHandle,
    checkCancelled: () -> Unit,
  ): List<InstalledIconCapability> {
    val activities = try {
      apps.getActivityList(null, user)
    } catch (_: Throwable) {
      return emptyList()
    }
    val launched = activities.groupBy { it.componentName.packageName }
    val installed = if (user == Process.myUserHandle()) packageManager.getInstalledPackages(
      android.content.pm.PackageManager.PackageInfoFlags.of(0)) else emptyList()
    val infos = installed.associateBy { it.packageName }
    return (launched.keys + infos.keys).mapNotNull { packageName ->
      checkCancelled()
      val entries = launched[packageName].orEmpty()
      val info = infos[packageName] ?: try { packageManager.getPackageInfo(packageName, 0) }
        catch (_: android.content.pm.PackageManager.NameNotFoundException) { return@mapNotNull null }
      val app = info.applicationInfo ?: return@mapNotNull null
      val sourceActivities = entries.map(LauncherActivityInfo::getActivityInfo).ifEmpty {
        listOf(android.content.pm.ActivityInfo().apply {
          this.packageName = packageName; applicationInfo = app; icon = app.icon; name = packageName
        })
      }
      val overlay = isOverlay(app)
      val assessment = if (overlay) null else inspector.assess(sourceActivities, configuration)
      InstalledIconCapability(
        label = entries.firstOrNull()?.label?.toString() ?: app.loadLabel(packageManager).toString(),
        packageName = packageName,
        userId = userManager?.getSerialNumberForUser(user) ?: -1L,
        personalProfile = user == Process.myUserHandle(),
        nightMode = assessment?.nightMode?.toCapabilityStatus() ?: IconCapabilityStatus.UNKNOWN,
        monochrome = assessment?.monochrome?.toCapabilityStatus() ?: IconCapabilityStatus.UNKNOWN,
        monochromeFallbackRecommended = assessment?.monochromeFallbackRecommended == true,
        versionCode = info.longVersionCode,
        iconResourceIds = sourceActivities.map { it.iconResource }.filter { it != 0 }.toSet(),
        overlayApp = overlay,
        systemApp = app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
        hasLauncher = entries.isNotEmpty(), updatedAt = info.lastUpdateTime,
        componentName = entries.firstOrNull()?.componentName?.className,
      )
    }
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
