package os.kei.hyper.home.iconpack.perfect

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.nativeLauncherIconNames

/** Upstream keys include package names and native activity filenames. */
internal fun perfectInstalledIconNames(context: Context): Set<String> = buildSet {
  val launcher = requireNotNull(context.getSystemService(LauncherApps::class.java))
  launcher.getActivityList(null, Process.myUserHandle()).forEach { activity ->
    val component = activity.componentName
    addAll(nativeLauncherIconNames(IconPackComponent(component.packageName, component.className)))
  }
  context.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    .forEach { add(it.packageName) }
}
