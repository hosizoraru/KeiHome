package os.kei.hyper.home.iconpack

/** Completes native activity filenames only after PackageManager proves application-icon identity. */
internal object NativeDefaultAliasCompletion {
  const val REVISION = "native-default-alias-1"
  const val MAX_COPY_BYTES = 64L * 1024 * 1024
  private const val MAX_TARGETS = 5000

  data class Identity(
    val activityPackage: String,
    val applicationPackage: String,
    val applicationIcon: Int,
    val effectiveActivityIcon: Int,
    val launcher: Boolean,
  )

  data class Alias(val target: IconPackComponent, val name: String, val paths: Map<String, String>)
  private data class Resource(val name: String, val prefix: String, val suffix: String)

  fun plan(
    paths: Set<String>, targets: List<IconPackComponent>, preservedPackages: Set<String>,
    identity: (IconPackComponent) -> Identity?, limits: IconPackLimits = IconPackLimits(),
    check: () -> Unit = {},
  ): List<Alias> {
    check()
    if (paths.size > limits.maxEntries || targets.size > MAX_TARGETS)
      throw IconPackException("Native default alias limit exceeded")
    val resources = linkedMapOf<String, MutableList<Pair<String, Resource>>>()
    for (path in paths) {
      check()
      val item = resource(path) ?: continue
      resources.getOrPut(item.name) { arrayListOf() }.add(path to item)
    }
    val additions = arrayListOf<Alias>()
    val reserved = hashSetOf<String>()
    val reservedNames = hashSetOf<String>()
    for (target in targets.distinct()) {
      check()
      if (target.packageName in preservedPackages || target.className == null) continue
      val name = nativeLauncherActivityName(target) ?: continue
      if (!validNativeIconName(name) || name.toByteArray(Charsets.UTF_8).size > 240 ||
        name == target.packageName || name in preservedPackages) continue
      // Even a partial night tree or a legacy explicit activity entry is authoritative.
      if (nativeLauncherIconNames(target).filterNot { it == target.packageName }.any {
          it in resources || it in reservedNames
        }) continue
      val source = resources[target.packageName] ?: continue
      if (paths.size.toLong() + reserved.size + source.size > limits.maxEntries) continue
      val proof = identity(target) ?: continue
      check()
      if (!proof.launcher || proof.activityPackage != target.packageName ||
        proof.applicationPackage != target.packageName || proof.applicationIcon == 0 ||
        proof.effectiveActivityIcon != proof.applicationIcon) continue
      val copy = linkedMapOf<String, String>()
      for ((path, item) in source) {
        check()
        val destination = item.prefix + name + item.suffix
        if (destination in paths || destination in reserved)
          throw IconPackException("Native default alias collision")
        copy[destination] = path
      }
      additions += Alias(target, name, copy)
      reserved += copy.keys
      reservedNames += name
    }
    return additions
  }

  private fun resource(path: String): Resource? {
    val night = if (path.startsWith("nightmode/")) "nightmode/" else ""
    val parts = path.removePrefix(night).split('/')
    val (name, prefix, suffix) = when {
      parts.size == 1 && parts[0].endsWith(".png") ->
        Triple(parts[0].removeSuffix(".png"), night, ".png")
      parts.size == 3 && parts[0] == "res" &&
        (parts[1] == "drawable" || parts[1].startsWith("drawable-")) && parts[2].endsWith(".png") ->
        Triple(parts[2].removeSuffix(".png"), "$night${parts[0]}/${parts[1]}/", ".png")
      parts.size >= 4 && parts[0] == "res" &&
        (parts[1] == "drawable" || parts[1].startsWith("drawable-")) ->
        Triple(parts[2], "$night${parts[0]}/${parts[1]}/", "/" + parts.drop(3).joinToString("/"))
      parts.size >= 3 && parts[0] in setOf("fancy_icons", "animating_icons", "layer_animating_icons") ->
        Triple(parts[1], "$night${parts[0]}/", "/" + parts.drop(2).joinToString("/"))
      else -> return null
    }
    return if (validNativeIconName(name)) Resource(name, prefix, suffix) else null
  }
}
