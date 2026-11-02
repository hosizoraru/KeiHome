package os.kei.hyper.home.ui.main

import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.data.ColorOsStyle
import os.kei.hyper.home.data.SystemIconFallback
import java.util.Locale

internal data class IconPackCardData(
  val pack: IconPackDescriptor,
  val matchedApps: Int,
  val previewEntries: List<IconPackEntry>,
  val originalApps: Int = 0,
  val dynamicApps: Int = 0,
)

internal data class IconPacksUiState(
  val packs: List<IconPackCardData> = emptyList(),
  val loading: Boolean = false,
  val scanned: Boolean = false,
  val importing: Boolean = false,
  val error: String? = null,
  val importName: String? = null,
  val failedSources: Int = 0,
  val nativeImporting: String? = null,
  val preparing: Boolean = false,
  val nativeAssociations: Map<String, String> = emptyMap(),
  val nativeSources: Map<String, String> = emptyMap(),
  val nativeResult: os.kei.hyper.home.status.NativeIconImportReport? = null,
)

internal fun activeCatalogPackId(activeId: String?, state: IconPacksUiState): String? {
  if (activeId == null) return null
  val source = state.nativeSources[activeId] ?: state.nativeAssociations.entries
    .firstOrNull { it.value == activeId }?.key ?: return null
  return SystemIconFallback.baseSourceId(source)
}

internal fun catalogPackRows(state: IconPacksUiState, section: String, activePackId: String?,
  query: String, order: Int, reverse: Boolean): List<IconPackCardData> {
  val needle = query.trim().lowercase(Locale.ROOT)
  fun identity(card: IconPackCardData): String = when (val source = card.pack.source) {
    is IconPackSource.Installed -> source.packageName
    is IconPackSource.Imported -> source.record.originalName
  }
  fun updated(card: IconPackCardData): Long = when (val source = card.pack.source) {
    is IconPackSource.Installed -> source.updatedAt
    is IconPackSource.Imported -> source.record.importedAt
  }
  val name = compareBy<IconPackCardData> { it.pack.displayName.lowercase(Locale.ROOT) }.thenBy { it.pack.id }
  val comparator = when (order) {
    1 -> compareBy<IconPackCardData> { identity(it).lowercase(Locale.ROOT) }.then(name)
    2 -> compareByDescending<IconPackCardData> { updated(it) }.then(name)
    3 -> compareByDescending<IconPackCardData> { it.matchedApps }.then(name)
    else -> name
  }.let { if (reverse) it.reversed() else it }
  return state.packs.asSequence().filter { card ->
    card.pack.builtinKey == null && when (section) {
      "installed" -> card.pack.format == IconPackFormat.INSTALLED_APK || card.pack.format == IconPackFormat.APK
      "imported" -> card.pack.format != IconPackFormat.INSTALLED_APK && card.pack.format != IconPackFormat.APK
      else -> false
    } && (needle.isEmpty() || card.pack.displayName.lowercase(Locale.ROOT).contains(needle) ||
      identity(card).lowercase(Locale.ROOT).contains(needle))
  }.sortedWith(compareByDescending<IconPackCardData> { it.pack.id == activePackId }.then(comparator)).toList()
}

/** Reverse receipts are authoritative. Older owned ColorOS components can be recovered only
 * when their known source and generated title resolve to a single original configuration. */
internal fun currentIconPackSource(
  active: String, title: String?, state: IconPacksUiState, ownedColorOs: Map<String, Boolean>,
  colorOsTitle: (ColorOsStyle, Boolean) -> String,
): IconPackDescriptor? {
  fun resolve(source: String): IconPackDescriptor? {
    val base = SystemIconFallback.baseSourceId(source)
    val id = if (base.startsWith("coloros:")) base.split(':', limit = 4).getOrNull(3) ?: return null else base
    val direct = state.packs.firstOrNull { it.pack.id == id }?.pack
    if (direct != null) return direct.copy(id = base, displayName = title ?: direct.displayName)
    // Built-in bytes (and thus their store hash) change when new OEM mappings ship.
    // Recover only the exact known style/appearance/ART title; never substitute an APK.
    val fields = base.split(':', limit = 4)
    if (fields.size != 4 || fields[0] != "coloros" || title == null ||
      os.kei.hyper.home.data.IconPackAppearance.entries.none { it.name == fields[2] }) return null
    val style = ColorOsStyle.entries.firstOrNull { it.key == fields[1] } ?: return null
    val updated = state.packs.map { it.pack }.singleOrNull { pack ->
      listOf(false, true).any { art ->
        pack.builtinKey == "coloros-${style.artworkKey}-art-${if (art) "on" else "off"}" &&
          colorOsTitle(style, art) == title
      }
    } ?: return null
    return updated.copy(id = "coloros:${fields[1]}:${fields[2]}:${updated.id}", displayName = title)
  }
  state.nativeSources[active]?.let { return resolve(it) }
  if (active !in ownedColorOs || title == null) return null
  return (state.nativeSources.values + state.nativeAssociations.keys)
    .map { SystemIconFallback.baseSourceId(it) }.distinct().mapNotNull { source ->
      val fields = source.split(':', limit = 4)
      if (fields.size != 4 || fields[0] != "coloros") return@mapNotNull null
      if (os.kei.hyper.home.data.IconPackAppearance.entries.none { it.name == fields[2] }) return@mapNotNull null
      val style = ColorOsStyle.entries.firstOrNull { it.key == fields[1] } ?: return@mapNotNull null
      if (style.usesMonet != ownedColorOs[active]) return@mapNotNull null
      val pack = resolve(source) ?: return@mapNotNull null
      val art = pack.builtinKey == "coloros-${style.artworkKey}-art-on"
      if (pack.builtinKey !in setOf("coloros-${style.artworkKey}-art-on",
          "coloros-${style.artworkKey}-art-off") || colorOsTitle(style, art) != title) null else pack
    }.singleOrNull()
}

internal fun iconPackCardData(pack: IconPackDescriptor, targets: List<IconPackComponent>): IconPackCardData {
  val components = pack.entries.mapNotNull { entry -> entry.component?.let { it to entry } }.toMap()
  val native = pack.entries.mapNotNull { entry -> entry.nativeName?.let { it to entry } }.toMap()
  val matches = targets.mapNotNull { target ->
    val entry = components[target] ?: components[IconPackComponent(target.packageName)] ?:
      target.className?.let(native::get) ?: native[target.packageName]
    entry?.let { target.packageName to it }
  }
  val dynamicPackages = if (pack.colorOsProfile == null) emptySet() else targets.filter {
    pack.colorOsProfile.source(it)?.requiresLiveIcon == true
  }.map { it.packageName }.toSet()
  return IconPackCardData(pack, matches.map { it.first }.distinct().size,
    matches.map { it.second }.distinctBy { it.key }.take(4)
      .ifEmpty { pack.entries.filter { it.light != null || it.dark != null }.take(4) },
    originalApps = if (pack.colorOsProfile == null) 0 else
      (targets.map { it.packageName }.toSet() - matches.map { it.first }.toSet() - dynamicPackages).size,
    dynamicApps = dynamicPackages.size)
}
