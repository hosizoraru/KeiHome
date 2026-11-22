package os.kei.hyper.home.ui.main

import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.data.RecommendedIconMappings
import os.kei.hyper.home.iconpack.BuiltinColorOsMappings
import os.kei.hyper.home.iconpack.ColorOsFallbackRenderer
import os.kei.hyper.home.iconpack.IconBuildInventory
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackEntryIndex
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.RecommendedPackageIcons
import os.kei.hyper.home.iconpack.nativeLauncherActivityName
import os.kei.hyper.home.iconpack.nativeLauncherIconNames

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
  preservedPackages: Set<String> = emptySet(),
  recommendedMappings: RecommendedIconMappings = RecommendedIconMappings(),
  customizedPackages: Set<String> = emptySet(),
  customIconIdentity: String? = null,
  monetUnadaptedIcons: Boolean = false,
  monetFullBleedIconPadding: Boolean = false,
  extraIconPackages: Set<String> = emptySet(),
  canComplete: (IconPackComponent) -> Boolean = { true }): Boolean {
  val pack = BuiltinColorOsMappings.selected(pack, recommendedMappings.colorOsThemeStores)
  val previousMappings = source.generation?.recommendedMappings ?: RecommendedIconMappings()
  if (previousMappings.copy(weiboLite = recommendedMappings.weiboLite) != recommendedMappings) return true
  val packageMappings by lazy { RecommendedPackageIcons(pack.entries, recommendedMappings) }
  if (source.generation?.customIconIdentity != customIconIdentity) return true
  if (source.generation?.extraIconPackages.orEmpty() != extraIconPackages) return true
  if ((source.generation?.monetUnadaptedIcons ?: false) != monetUnadaptedIcons) return true
  if ((source.generation?.monetFullBleedIconPadding ?: false) !=
    monetFullBleedIconPadding) return true
  if (monetUnadaptedIcons && source.generation?.schemaVersion?.let { it < 12 } == true) return true
  if (monetUnadaptedIcons && pack.format in setOf(IconPackFormat.MIUI_MTZ, IconPackFormat.MIUI_ICONS) &&
    source.generation?.schemaVersion?.let { it < 16 } == true) return true
  if ((pack.colorOsProfile != null || source.generation?.colorOsPackages?.isNotEmpty() == true) &&
    source.generation?.schemaVersion?.let { it < 14 } == true) return true
  val effectivePreserved = preservedPackages - customizedPackages
  if (previousMappings.weiboLite != recommendedMappings.weiboLite &&
    targets.any { it.packageName == RecommendedPackageIcons.WEIBO_LITE &&
      it.packageName !in effectivePreserved && it.packageName !in customizedPackages }) {
    val withWeibo = if (recommendedMappings.weiboLite) packageMappings
      else RecommendedPackageIcons(pack.entries, previousMappings)
    if (withWeibo.donor(IconPackComponent(RecommendedPackageIcons.WEIBO_LITE), null) != null) return true
  }
  if (source.generation?.preservedIconPackages.orEmpty() != effectivePreserved) return true
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
  val converted = pack.format !in setOf(IconPackFormat.MIUI_MTZ, IconPackFormat.MIUI_ICONS)
  if (converted && source.generation?.schemaVersion?.let { it < 7 } == true) return true
  val eligible = targets.filterNot { it.packageName in effectivePreserved }
  if (converted && source.generation?.schemaVersion?.let { it >= 6 } == true &&
    activeLauncherSelectionChanged(source, eligible, names)) return true
  if (source.generation?.nativeAdaptations?.any { (target, fingerprint) ->
      nativeAdaptations[target]?.let { it != fingerprint } == true } == true) return true
  val components = pack.entries.mapNotNull { it.component }.toSet()
  val channelFamily = os.kei.hyper.home.iconpack.TelegramChannelIcons(pack.entries, recommendedMappings.telegramChannels)
  val index by lazy { IconPackEntryIndex(pack.entries) }
  return eligible.any { target ->
    if (target.packageName in customizedPackages) return@any false
    val aliases = nativeLauncherIconNames(target)
    val perfect = fallback.isPerfect
    if (aliases.any { it in names } || target.flattened in source.generation?.considered.orEmpty() ||
      pack.colorOsProfile?.source(target)?.requiresLiveIcon == true) false else {
      val mapped = target in components || IconPackComponent(target.packageName) in components
      val colorOs = (pack.colorOsProfile != null || colorOsForOtherPacks) &&
        ColorOsFallbackRenderer.dynamicReason(target.packageName, null) == null &&
        target.packageName != "com.xiaomi.calendar"
      val nativeFallback = target.packageName in systemPackages && aliases.any { it in source.entries }
      val channel = channelFamily.donor(target, true, null) != null
      val packageMapping = target.packageName in RecommendedPackageIcons.TARGETS &&
        packageMappings.donor(target, index.find(target)) != null
      (mapped || channel || packageMapping || colorOs || nativeFallback || perfect ||
        (effectiveNameMatching && target.packageName in systemPackages)) && canComplete(target)
    }
  }
}

private fun activeLauncherSelectionChanged(source: IconBrowserSource,
  targets: List<IconPackComponent>, names: Set<String>): Boolean {
  val previous = source.generation?.activeLauncherTargets.orEmpty().mapNotNull(IconPackComponent::parse)
    .groupBy { it.packageName }
  val current = targets.groupBy { it.packageName }
  // New installs use the existing coverage policy; uninstalled packages have no launcher to repair.
  return previous.any { (pkg, before) ->
    val after = current[pkg] ?: return@any false
    val active = after.filter { it.className != null }
    if (before.toSet() == active.toSet()) return@any false
    val roots = listOf(pkg) + (before + active).mapNotNull(::nativeLauncherActivityName)
    roots.any { it in names }
  }
}
