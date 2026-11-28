package os.kei.hyper.home.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import os.kei.hyper.home.R
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.iconpack.GeneratedPackProducer
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.KeiHomePackVersion
import os.kei.hyper.home.status.SystemIconThemeReport
import os.kei.hyper.home.ui.components.Os4BadgePalette
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.FolderOutline
import top.yukonga.miuix.kmp.icon.glass.GridView
import top.yukonga.miuix.kmp.icon.glass.Iphone
import top.yukonga.miuix.kmp.icon.glass.Phone
import top.yukonga.miuix.kmp.icon.glass.Theme

@Immutable
internal data class ActiveIconPackSummary(
  val sourceSection: String? = null,
  val version: String? = null,
  val appliedAtMillis: Long? = null,
  val updateNeeded: Boolean = false,
  val sourceUpdated: Boolean = false,
  val pendingApply: Boolean = false,
)

@Composable
internal fun rememberActiveIconPackSummary(
  theme: SystemIconThemeReport?,
  packs: IconPacksUiState,
  fallback: SystemIconFallback,
  updateNeeded: Boolean,
  sourceUpdated: Boolean = false,
  perfectPackId: String? = null,
): ActiveIconPackSummary {
  val context = LocalContext.current
  val matchedPack = remember(theme?.localId, theme?.nativeHash, packs) {
    findActiveCatalogPack(theme, packs)
  }
  val catalogSection = remember(theme, packs, matchedPack, perfectPackId) {
    when ((matchedPack?.source as? IconPackSource.Imported)?.record?.generatedBy) {
      GeneratedPackProducer.PERFECT -> "perfect"
      GeneratedPackProducer.APPLE -> "apple"
      null -> statusIconPackDestination(theme, packs, perfectPackId)
    }
  }
  val fallbackSection by produceState<String?>(initialValue = null, theme, catalogSection) {
    value = if ((catalogSection != null && catalogSection != "imported") ||
      theme?.source != SystemIconThemeReport.Source.THEME || !theme.complete
    ) {
      null
    } else {
      loadStatusIconPackDestination(context, theme)
    }
  }
  val section = fallbackSection ?: catalogSection
  val version = remember(theme?.version, matchedPack) {
    val reported = theme?.version?.trim()?.takeUnless(KeiHomePackVersion::isPlaceholder)
    if (reported != null) return@remember reported
    val catalogVersion = when (val src = matchedPack?.source) {
      is IconPackSource.Installed -> src.versionName
      else -> matchedPack?.description?.version
    }
    catalogVersion?.trim()?.takeUnless(KeiHomePackVersion::isPlaceholder)
  }
  val pendingApply = remember(packs, theme?.localId, fallback) {
    pendingNativeApplication(packs, theme?.localId, fallback) != null
  }
  return ActiveIconPackSummary(
    sourceSection = section,
    version = version,
    appliedAtMillis = theme?.appliedAtMillis,
    updateNeeded = updateNeeded && !pendingApply,
    sourceUpdated = sourceUpdated && !pendingApply,
    pendingApply = pendingApply,
  )
}

private fun findActiveCatalogPack(
  theme: SystemIconThemeReport?,
  packs: IconPacksUiState,
): IconPackDescriptor? {
  if (theme == null || packs.packs.isEmpty()) return null
  val byReceipt = activeCatalogPackId(theme.localId, packs)?.let { id ->
    packs.packs.firstOrNull { it.pack.id == id }?.pack
  }
  if (byReceipt != null) return byReceipt
  val hash = theme.nativeHash?.takeIf { it.isNotBlank() } ?: return null
  return packs.packs.singleOrNull { it.pack.matchesLoadedIcons(hash) }?.pack
}

@Composable
internal fun activePackSourceLabel(
  section: String?,
  source: SystemIconThemeReport.Source?,
): String? = when (section) {
  "perfect" -> stringResource(R.string.pack_source_kind_perfect)
  "apple" -> stringResource(R.string.pack_source_kind_apple)
  "coloros" -> stringResource(R.string.pack_source_kind_coloros)
  "builtin" -> stringResource(R.string.pack_source_kind_builtin)
  "installed" -> stringResource(R.string.pack_source_kind_installed)
  "imported" -> stringResource(R.string.pack_source_kind_imported)
  else -> when (source) {
    SystemIconThemeReport.Source.DEFAULT -> stringResource(R.string.pack_source_kind_default)
    SystemIconThemeReport.Source.THEME -> stringResource(R.string.pack_source_kind_theme)
    else -> null
  }
}

internal fun activePackSourceTint(
  section: String?,
  source: SystemIconThemeReport.Source?,
): Color = when (section) {
  "perfect" -> Os4BadgePalette.Pink
  "apple" -> Os4BadgePalette.Blue
  "coloros" -> Os4BadgePalette.Indigo
  "builtin" -> Os4BadgePalette.Teal
  "installed" -> Os4BadgePalette.Purple
  "imported" -> Os4BadgePalette.Gold
  else -> when (source) {
    SystemIconThemeReport.Source.DEFAULT -> Os4BadgePalette.Teal
    else -> Os4BadgePalette.Pink
  }
}

internal fun activePackSourceIcon(
  section: String?,
  source: SystemIconThemeReport.Source?,
): ImageVector = when (section) {
  "perfect" -> MiuixIcons.Glass.Theme
  "apple" -> MiuixIcons.Glass.Iphone
  "coloros", "builtin" -> MiuixIcons.Glass.Phone
  "installed" -> MiuixIcons.Glass.GridView
  "imported" -> MiuixIcons.Glass.FolderOutline
  else -> when (source) {
    SystemIconThemeReport.Source.DEFAULT -> MiuixIcons.Glass.Phone
    else -> MiuixIcons.Glass.Theme
  }
}
