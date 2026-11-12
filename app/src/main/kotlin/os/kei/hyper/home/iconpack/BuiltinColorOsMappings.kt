package os.kei.hyper.home.iconpack

/** Exact OEM aliases shared by the mapping browser, previews and generated icons. */
internal object BuiltinColorOsMappings {
  const val REVISION = "theme-alias-1"
  private val themeStore = IconPackComponent("com.heytap.themestore")
  private val themeTargets = listOf(
    IconPackComponent("com.android.thememanager"),
    IconPackComponent("com.miui.themestore"),
  )
  val themePackages: Set<String> = themeTargets.mapTo(hashSetOf()) { it.packageName }

  fun excludedNameMatches(pack: IconPackDescriptor, enabled: Boolean): Set<String> =
    if (!enabled && pack.colorOsProfile != null) themePackages else emptySet()

  fun selected(pack: IconPackDescriptor, enabled: Boolean): IconPackDescriptor {
    if (enabled) {
      if (pack.recommendedAliases.isNotEmpty() &&
        pack.recommendedAliases.all { it in pack.colorOsProfile?.icons.orEmpty() }) return pack
      return withAliases(pack)
    }
    if (pack.recommendedAliases.isEmpty()) return pack
    return pack.copy(entries = pack.entries.filterNot { it.component in pack.recommendedAliases },
      colorOsProfile = pack.colorOsProfile?.let {
        it.copy(icons = it.icons.filterKeys { component -> component !in pack.recommendedAliases })
      })
  }

  fun withAliases(pack: IconPackDescriptor): IconPackDescriptor {
    val profile = pack.colorOsProfile ?: return pack
    if (pack.source !is IconPackSource.Builtin || pack.builtinKey?.startsWith("coloros-") != true) return pack
    // HeyTap has both modes in every builtin style; Oplus can be absent or use different art.
    val source = pack.entries.firstOrNull { it.component == themeStore } ?: return pack
    if (source.light == null && source.dark == null) return pack
    val components = pack.entries.mapNotNullTo(hashSetOf()) { it.component }
    val aliases = themeTargets.filterNot { it in components }.map { target ->
      source.copy(key = target.flattened, component = target, nativeName = null)
    }
    if (aliases.isEmpty()) return pack
    val icons = profile.icons.toMutableMap()
    profile.icons[themeStore]?.let { metadata ->
      aliases.forEach { entry -> icons.putIfAbsent(requireNotNull(entry.component), metadata) }
    }
    return pack.copy(entries = pack.entries + aliases, colorOsProfile = profile.copy(icons = icons),
      recommendedAliases = pack.recommendedAliases + aliases.mapNotNull { it.component })
  }
}
