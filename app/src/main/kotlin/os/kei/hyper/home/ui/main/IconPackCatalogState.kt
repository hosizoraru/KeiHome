package os.kei.hyper.home.ui.main

import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackEntry

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
  val nativeAssociations: Map<String, String> = emptyMap(),
  val nativeResult: os.kei.hyper.home.status.NativeIconImportReport? = null,
)

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
