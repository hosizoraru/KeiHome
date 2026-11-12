package os.kei.hyper.home.iconpack

/** Completes missing official-channel artwork without changing an explicit target selection. */
internal class TelegramChannelIcons(entries: List<IconPackEntry>, private val enabled: Boolean = true) {
  private data class Candidate(val entry: IconPackEntry, val family: String, val alias: Int)

  private val candidates = (if (enabled) entries else emptyList()).mapNotNull { entry ->
    val identity = entry.component ?: entry.nativeName?.let(::nativeComponent) ?: return@mapNotNull null
    val alias = aliasPriority(identity) ?: return@mapNotNull null
    Candidate(entry, identity.packageName, alias)
  }.groupBy { it.family }.mapValues { (_, values) ->
    values.sortedWith(compareBy<Candidate> { it.alias }.thenBy { it.entry.key }
      .thenBy { it.entry.component?.flattened.orEmpty() }.thenBy { it.entry.nativeName.orEmpty() })
  }

  /** Returns one replacement entry, or null when the target should keep its own artwork. */
  fun donor(
    target: IconPackComponent,
    dark: Boolean,
    existing: IconPackEntry?,
    supportsNight: (IconPackEntry) -> Boolean = ::declaredNight,
  ): IconPackEntry? {
    if (!enabled || aliasPriority(target) == null) return null
    if (!dark && existing?.light != null) return null
    if (dark && existing != null && supportsNight(existing)) return null
    for (family in FAMILIES) {
      if (family == target.packageName) continue
      for (candidate in candidates[family].orEmpty()) {
        val entry = candidate.entry
        if ((dark || entry.light != null) && supportsNight(entry)) return entry
      }
    }
    return null
  }

  companion object {
    private val FAMILIES = listOf(
      "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
    )
    private const val DEFAULT_ALIAS = "org.telegram.messenger.DefaultIcon"
    private const val LEGACY_ALIAS = "org.telegram.ui.LaunchActivity"

    fun offlineTargets(): List<IconPackComponent> = FAMILIES.map { IconPackComponent(it) }

    fun isFamily(packageName: String): Boolean = packageName in FAMILIES

    /** Named alternate icons stay excluded even when they launch the same activity. */
    fun isDefaultTarget(target: IconPackComponent): Boolean = aliasPriority(target) != null

    /** A shared compiled drawable needs the caller's resource-aware proof instead. */
    fun declaredNight(entry: IconPackEntry): Boolean =
      (entry.dark != null && entry.dark != entry.light) ||
        listOfNotNull(entry.light, entry.dark).filterIsInstance<IconPackArtwork.Archive>().any { artwork ->
          (artwork.variants.asSequence() + artwork.layers.asSequence().flatten()).any {
            it.qualifiers.night == IconNightMode.DARK || it.path.startsWith("nightmode/")
          }
        }

    private fun aliasPriority(target: IconPackComponent): Int? {
      if (!isFamily(target.packageName)) return null
      val name = target.className ?: return 0
      val fullName = if (name.startsWith('.')) target.packageName + name else name
      return when (fullName) {
        DEFAULT_ALIAS, "${target.packageName}.DefaultIcon" -> 1
        LEGACY_ALIAS -> 2
        else -> null
      }
    }

    private fun nativeComponent(name: String): IconPackComponent? {
      if (isFamily(name)) return IconPackComponent(name)
      if ('#' in name) {
        if (name.count { it == '#' } != 1) return null
        return IconPackComponent(name.substringBefore('#'), name.substringAfter('#'))
          .takeIf { aliasPriority(it) != null }
      }
      // A bare class stem is owned only when it is a channel's exact DefaultIcon name.
      val family = FAMILIES.firstOrNull { name == "$it.DefaultIcon" } ?: return null
      return IconPackComponent(family, name)
    }
  }
}
