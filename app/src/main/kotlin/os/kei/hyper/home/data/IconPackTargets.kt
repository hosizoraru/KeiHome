package os.kei.hyper.home.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import os.kei.hyper.home.iconpack.IconPackComponent

/** HYOS native frontends can have a system APK but no Android MAIN/LAUNCHER activity. */
internal fun iconPackTargets(context: Context, check: () -> Unit = {}): List<IconPackComponent> {
  val launcher = context.getSystemService(LauncherApps::class.java)
    .getActivityList(null, Process.myUserHandle()).map {
      IconPackComponent(it.componentName.packageName, it.componentName.className)
    }
  val visible = launcher.mapTo(HashSet()) { it.packageName }
  val pm = context.packageManager
  val nativeSystem = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)).mapNotNull { app ->
    check()
    if (app.packageName in visible || '.' !in app.packageName || app.icon == 0 ||
      app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 ||
      isResourceOverlay(pm, app)) null else IconPackComponent(app.packageName)
  }
  return (launcher + nativeSystem).distinct()
}

private val overlayVerdicts = ResourceOverlayCache()

internal fun isResourceOverlay(pm: PackageManager, info: ApplicationInfo): Boolean {
  val identity = info.sourceDir?.let {
    overlayApkIdentity(info.packageName, info.uid, listOf(it) + info.splitSourceDirs.orEmpty())
  }
  return overlayVerdicts.resolve(identity) { readResourceOverlay(pm, info) }
}

private fun readResourceOverlay(pm: PackageManager, info: ApplicationInfo): Boolean? = try {
  pm.getResourcesForApplication(info).assets.openXmlResourceParser("AndroidManifest.xml").use { xml ->
    var matchesPackage = false
    var overlay = false
    while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
      if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
        if (xml.depth == 1 && xml.name == "manifest")
          matchesPackage = xml.getAttributeValue(null, "package") == info.packageName
        if (xml.depth == 2 && xml.name == "overlay") overlay = true
      }
      xml.next()
    }
    matchesPackage && overlay
  }
} catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
} catch (_: Exception) { null }
