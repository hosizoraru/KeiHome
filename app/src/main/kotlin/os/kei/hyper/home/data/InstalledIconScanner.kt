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
)

class InstalledIconScanner(private val context: Context) {
  private val packageManager = context.packageManager
  private val launcherApps = context.getSystemService(android.content.pm.LauncherApps::class.java)
  private val userManager = context.getSystemService(UserManager::class.java)

  fun scan(): List<InstalledIconCapability> {
    val apps = launcherApps ?: return emptyList()
    val users = userManager?.userProfiles.orEmpty()
    if (users.isEmpty()) return emptyList()
    val inspector = IconResourceInspector(packageManager)
    val configuration = context.resources.configuration
    return users.flatMap { user ->
      scanUser(apps, inspector, configuration, user)
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
  ): List<InstalledIconCapability> {
    val activities = try {
      apps.getActivityList(null, user)
    } catch (_: Throwable) {
      return emptyList()
    }
    return activities.groupBy { it.componentName.packageName }.mapNotNull { (packageName, entries) ->
      val assessment = inspector.assess(entries.map(LauncherActivityInfo::getActivityInfo), configuration)
      InstalledIconCapability(
        label = entries.firstNotNullOfOrNull { it.label?.toString() }
          ?: packageName,
        packageName = packageName,
        userId = userManager?.getSerialNumberForUser(user) ?: -1L,
        personalProfile = user == Process.myUserHandle(),
        nightMode = assessment.nightMode.toCapabilityStatus(),
        monochrome = assessment.monochrome.toCapabilityStatus(),
      )
    }
  }

}

private fun IconSupportStatus.toCapabilityStatus(): IconCapabilityStatus =
  when (this) {
    IconSupportStatus.DETECTED -> IconCapabilityStatus.DETECTED
    IconSupportStatus.NOT_DETECTED -> IconCapabilityStatus.NOT_DETECTED
    IconSupportStatus.UNKNOWN -> IconCapabilityStatus.UNKNOWN
  }
