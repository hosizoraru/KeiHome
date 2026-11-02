package os.kei.hyper.home.iconpack

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.Locale

/** Optional, conservative label fallback for system apps after exact appfilter matching. */
internal class SystemAppNameMatcher(context: Context, entries: List<IconPackEntry>, private val enabled: Boolean) {
  private val pm = context.packageManager
  private val byArtworkName = HashMap<String, IconPackEntry>()
  private val ambiguousArtwork = HashSet<String>()
  private val byRole = HashMap<String, IconPackEntry>()
  private val ambiguousRole = HashSet<String>()
  private val labels = HashMap<IconPackComponent, String?>()

  init {
    if (enabled) entries.forEach { entry ->
      val names = buildSet {
        listOfNotNull(entry.light, entry.dark).forEach { art -> when (art) {
          is IconPackArtwork.Resource -> add(normalize(art.drawable.name))
          is IconPackArtwork.Archive -> art.variants.forEach { add(normalize(it.path.substringAfterLast('/').substringBeforeLast('.'))) }
        }
        }
      }
      names.filter { it.length >= 3 }.forEach { key ->
        record(byArtworkName, ambiguousArtwork, key, entry)
      }
      entry.component?.packageName?.let(::knownRole)?.let { record(byRole, ambiguousRole, it, entry) }
    }
  }

  fun match(target: IconPackComponent): IconPackEntry? {
    if (!enabled) return null
    val label = labels.getOrPut(target) {
      val app = try { pm.getApplicationInfo(target.packageName, PackageManager.ApplicationInfoFlags.of(0)) }
        catch (_: PackageManager.NameNotFoundException) { return@getOrPut null }
      if (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return@getOrPut null
      val activityName = target.className ?: pm.getLaunchIntentForPackage(target.packageName)?.component?.className
      val activityLabel = activityName?.let { name ->
        try { pm.getActivityInfo(ComponentName(target.packageName, name), PackageManager.ComponentInfoFlags.of(0))
          .loadLabel(pm).toString() } catch (_: PackageManager.NameNotFoundException) { null }
      }
      (activityLabel ?: app.loadLabel(pm).toString()).trim().takeIf { it.isNotEmpty() }
    } ?: return null
    val keys = keys(label) + if (normalize(label) == "安全服务" &&
      knownRole(target.packageName) == "phonemanager") listOf("phonemanager") else emptyList()
    for (key in keys) {
      if (key !in ambiguousArtwork) byArtworkName[key]?.let { return it }
      if (key !in ambiguousRole) byRole[key]?.let { return it }
    }
    return null
  }

  private fun record(index: MutableMap<String, IconPackEntry>, ambiguous: MutableSet<String>,
    key: String, entry: IconPackEntry) {
    val previous = index[key]
    if (previous == null) index[key] = entry
    else if (previous.light != entry.light || previous.dark != entry.dark) ambiguous += key
  }

  companion object {
    internal fun normalize(value: String): String = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    internal fun keys(label: String): List<String> {
      val normalized = normalize(label)
      val role = when {
        normalized in setOf("手机管家", "phonemanager", "安全中心", "securitycenter") -> "phonemanager"
        normalized in setOf("主题商店", "themestore", "主题壁纸") -> "themestore"
        normalized in setOf("应用商店", "软件商店", "appstore", "softwarestore", "应用市场") ||
          ((normalized.contains("商店") || normalized.contains("市场")) &&
            (normalized.contains("应用") || normalized.contains("软件"))) -> "appmarket"
        else -> null
      }
      return listOfNotNull(role, normalized).distinct()
    }

    private fun knownRole(packageName: String): String? = when (packageName) {
      "com.coloros.safecenter", "com.oplus.phonemanager", "com.miui.securitycenter" -> "phonemanager"
      "com.heytap.themestore", "com.oplus.themestore", "com.android.thememanager", "com.miui.themestore" -> "themestore"
      "com.oppo.market", "com.heytap.market", "com.xiaomi.market", "com.sec.android.app.samsungapps" -> "appmarket"
      else -> null
    }
  }
}
