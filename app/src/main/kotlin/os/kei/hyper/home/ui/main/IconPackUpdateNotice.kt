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
  systemPackages: Set<String>, nameMatching: Boolean = false,
  canComplete: (IconPackComponent) -> Boolean = { true }): Boolean {
  val effectiveNameMatching = nameMatching &&
    pack.format != IconPackFormat.MIUI_MTZ && pack.format != IconPackFormat.MIUI_ICONS
  if (source.buildSource != null && source.buildSource != IconBuildInventory.sourceIdentity(
      pack.copy(id = fallback.sourceId(pack.id)))) return true
  if (source.systemFallback != null && source.systemFallback.style != fallback) return true
  if (pack.format != IconPackFormat.MIUI_MTZ && pack.format != IconPackFormat.MIUI_ICONS &&
    source.systemFallback != null && source.systemFallback.nameMatching != effectiveNameMatching) return true
  val names = source.nativeNames ?: return false
  val components = pack.entries.mapNotNull { it.component }.toSet()
  return targets.any { target ->
    val aliases = listOfNotNull(target.className, target.packageName)
    if (aliases.any { it in names } || pack.colorOsProfile?.source(target)?.requiresLiveIcon == true) false else {
      val mapped = target in components || IconPackComponent(target.packageName) in components
      val colorOs = pack.colorOsProfile != null &&
        ColorOsFallbackRenderer.dynamicReason(target.packageName, null) == null &&
        target.packageName != "com.xiaomi.calendar"
      val nativeFallback = target.packageName in systemPackages && aliases.any { it in source.entries }
      (mapped || colorOs || nativeFallback || (effectiveNameMatching && target.packageName in systemPackages)) && canComplete(target)
    }
  }
}
