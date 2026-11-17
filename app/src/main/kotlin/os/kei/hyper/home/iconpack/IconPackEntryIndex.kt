package os.kei.hyper.home.iconpack

/** Exact source ownership shared by preview, generation and recommended-channel completion. */
internal class IconPackEntryIndex(entries: List<IconPackEntry>) {
  private val components = entries.mapNotNull { entry -> entry.component?.let { it to entry } }.toMap()
  private val nativeNames = entries.mapNotNull { entry -> entry.nativeName?.let { it to entry } }.toMap()

  fun find(target: IconPackComponent): IconPackEntry? =
    components[target] ?: components[IconPackComponent(target.packageName)]
      ?: nativeLauncherIconNames(target).firstNotNullOfOrNull(nativeNames::get)
}
