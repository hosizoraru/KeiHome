package os.kei.hyper.home.ui.main

import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.iconpack.ColorOsFallbackRenderer
import os.kei.hyper.home.iconpack.IconBuildInventory
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat

/** Unknown/unrenderable resources and deliberately uncovered user apps are not update tasks. */
internal fun iconPackNeedsUpdate(pack: IconPackDescriptor, source: IconBrowserSource,
  targets: List<IconPackComponent>, fallback: SystemIconFallback,
  systemPackages: Set<String>, nameMatching: Boolean = false, colorOsForOtherPacks: Boolean = false,
  nativeAdaptations: Map<String, String> = emptyMap(),
  installedOnly: Boolean = false,
  perfectSource: String? = null,
  completeSystemIconConfig: Boolean = false,
  iconMaskIdentity: String = if (completeSystemIconConfig)
    os.kei.hyper.home.iconpack.IconMaskPolicy.DEFAULT_IDENTITY else "original",
  canComplete: (IconPackComponent) -> Boolean = { true }): Boolean {
  if (source.generation?.schemaVersion?.let { it < 3 } == true ||
    (source.generation?.completeSystemIconConfig ?: false) != completeSystemIconConfig ||
    (completeSystemIconConfig && (source.generation?.schemaVersion?.let { it < 4 } == true ||
      source.generation?.iconMaskIdentity != iconMaskIdentity)) ||
    (source.generation?.installedOnly ?: false) != installedOnly) return true
  val effectiveNameMatching = nameMatching &&
    pack.format != IconPackFormat.MIUI_MTZ && pack.format != IconPackFormat.MIUI_ICONS
  if (source.buildSource != null && source.buildSource != IconBuildInventory.sourceIdentity(
      pack.copy(id = fallback.sourceId(pack.id)))) return true
  if (source.systemFallback != null && source.systemFallback.style != fallback) return true
  if (fallback.isPerfect && perfectSource != null && source.systemFallback?.perfectSource != perfectSource) return true
  if ((source.systemFallback?.colorOsFallback == true) !=
    (colorOsForOtherPacks && pack.colorOsProfile == null)) return true
  if (pack.format != IconPackFormat.MIUI_MTZ && pack.format != IconPackFormat.MIUI_ICONS &&
    source.systemFallback != null && source.systemFallback.nameMatching != effectiveNameMatching) return true
  val names = source.nativeNames ?: return false
  if (source.generation?.nativeAdaptations?.any { (target, fingerprint) ->
      nativeAdaptations[target]?.let { it != fingerprint } == true } == true) return true
  val components = pack.entries.mapNotNull { it.component }.toSet()
  return targets.any { target ->
    val aliases = listOfNotNull(target.className, target.packageName)
    val perfect = fallback.isPerfect && target.packageName in systemPackages
    if (aliases.any { it in names } || target.flattened in source.generation?.considered.orEmpty() ||
      pack.colorOsProfile?.source(target)?.requiresLiveIcon == true) false else {
      val mapped = target in components || IconPackComponent(target.packageName) in components
      val colorOs = (pack.colorOsProfile != null || colorOsForOtherPacks) &&
        ColorOsFallbackRenderer.dynamicReason(target.packageName, null) == null &&
        target.packageName != "com.xiaomi.calendar"
      val nativeFallback = target.packageName in systemPackages && aliases.any { it in source.entries }
      (mapped || colorOs || nativeFallback || perfect || (effectiveNameMatching && target.packageName in systemPackages)) && canComplete(target)
    }
  }
}
