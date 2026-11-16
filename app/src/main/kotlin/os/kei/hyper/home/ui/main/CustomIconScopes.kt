package os.kei.hyper.home.ui.main

import os.kei.hyper.home.iconpack.IconPackEntry

/** Unknown or uninstalled owners stay reachable in All; package prefixes do not imply an origin. */
internal fun customIconMatchesOrigin(system: Boolean?, origin: Int): Boolean = when (origin) {
  1 -> system == false
  2 -> system == true
  else -> true
}

internal class CustomIconOrigins(
  private val packages: Map<String, Boolean>,
  apps: List<PreservedIconApp>,
) {
  private val activities = apps.filter { it.activeLauncherClass != null }
    .groupBy { it.activeLauncherClass }
    .mapNotNull { (name, owners) ->
      owners.singleOrNull()?.let { name!! to it.systemApp }
    }.toMap()

  fun system(entry: IconPackEntry): Boolean? {
    entry.component?.let { return packages[it.packageName] }
    val name = entry.nativeName ?: return null
    return packages[name.substringBefore('#')] ?: activities[name]
  }
}
