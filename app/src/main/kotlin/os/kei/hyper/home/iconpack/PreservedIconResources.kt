package os.kei.hyper.home.iconpack

/** Exact component ownership avoids confusing a package with another channel's namespace. */
internal class PreservedIconResources(
  private val packages: Set<String>,
  components: List<IconPackComponent>,
) {
  private val names = packages + components.filter { it.packageName in packages }
    .mapNotNull(::nativeLauncherActivityName)

  fun containsName(name: String): Boolean =
    name in names || '#' in name && name.substringBefore('#') in packages

  fun containsPath(path: String): Boolean {
    val parts = path.removePrefix("nightmode/").split('/')
    val name = when {
      parts.size >= 3 && parts[0] == "res" && parts[1].substringBefore('-') == "drawable" ->
        parts[2].removeSuffix(".png")
      parts.size >= 3 && parts[0] in setOf("fancy_icons", "layer_animating_icons", "animating_icons") -> parts[1]
      parts.size == 1 && parts[0].endsWith(".png") -> parts[0].removeSuffix(".png")
      else -> return false
    }
    return containsName(name)
  }

  companion object {
    /** Include disabled aliases so a later style switch cannot revive a preserved app's theme entry. */
    fun fromInstalled(context: android.content.Context, packages: Set<String>,
      components: List<IconPackComponent>, check: () -> Unit): PreservedIconResources {
      val declared = packages.flatMap { pkg ->
        check()
        try {
          val flags = android.content.pm.PackageManager.GET_ACTIVITIES or
            android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
          context.packageManager.getPackageInfo(pkg,
            android.content.pm.PackageManager.PackageInfoFlags.of(flags.toLong())).activities.orEmpty()
            .map { IconPackComponent(pkg, it.name) }
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { emptyList()
        } catch (_: SecurityException) { emptyList() }
      }
      return PreservedIconResources(packages, components + declared)
    }
  }
}
