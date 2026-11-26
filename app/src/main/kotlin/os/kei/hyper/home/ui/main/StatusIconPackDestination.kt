package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.data.ColorOsStyle
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.iconpack.BuiltinIconPackStore
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.IconPackStore
import os.kei.hyper.home.iconpack.StoredIconPack
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.status.SystemIconThemeReport
import os.kei.hyper.home.ui.perfect.PERFECT_PACK_UI_STORAGE_ID

/** Metadata only: no archive inspection, payload hashing, or browser/controller initialization. */
internal suspend fun loadStatusIconPackDestination(
  context: Context,
  theme: SystemIconThemeReport?,
): String? = withContext(Dispatchers.IO) {
  try {
    if (theme?.source != SystemIconThemeReport.Source.THEME || !theme.complete) return@withContext null
    val localId = theme.localId?.takeIf { it.isNotBlank() } ?: return@withContext null
    val app = context.applicationContext
    val receipt = IconPackImportProtocol.state(app)
    val source = receipt.sources[localId] ?:
      receipt.associations.filterValues { it == localId }.keys.singleOrNull() ?: return@withContext null
    val id = statusCatalogPackId(source) ?: return@withContext null
    val caller = currentCoroutineContext()
    caller.ensureActive()
    if (id == "apple") return@withContext "apple"
    if (id.startsWith("installed:")) {
      val name = id.removePrefix("installed:")
      if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) {
        return@withContext null
      }
      app.packageManager.getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(0))
      caller.ensureActive()
      return@withContext "installed"
    }
    if (!id.matches(Regex("[0-9a-f]{64}"))) return@withContext null
    val stored = IconPackStore(File(app.noBackupFilesDir, "icon-packs")).list().records
    caller.ensureActive()
    val builtinKeys = BuiltinIconPackStore(app.assets).list { caller.ensureActive() }
      .filter { it.key != "coloros-runtime-fallback" }.associate { it.id to it.key }
    val saved = AppLocalStorage.open(app, PERFECT_PACK_UI_STORAGE_ID).getString("last_generated", null)
    val perfectId = saved?.takeIf { it.length <= 32_768 }?.let {
      try { JSONObject(it).optString("recordId").takeIf { value -> value.matches(Regex("[0-9a-f]{64}")) } }
      catch (_: Exception) { null }
    }
    caller.ensureActive()
    statusIconPackDestination(theme, statusIconPackMetadata(stored, builtinKeys,
      receipt.associations, receipt.sources, perfectId))
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    null
  }
}

internal data class StatusIconPackMetadata(
  val sections: Map<String, String>,
  val nativeAssociations: Map<String, String> = emptyMap(),
  val nativeSources: Map<String, String> = emptyMap(),
  val nativeHashes: Map<String, String> = emptyMap(),
)

internal fun statusIconPackMetadata(
  stored: List<StoredIconPack>,
  builtinKeys: Map<String, String>,
  associations: Map<String, String>,
  sources: Map<String, String>,
  perfectPackId: String?,
): StatusIconPackMetadata {
  val records = stored.groupBy { it.id }.filterValues { it.size == 1 }.mapValues { it.value.single() }
  val sections = records.mapValues { (_, record) ->
    if (record.format in setOf(IconPackFormat.APK, IconPackFormat.INSTALLED_APK)) "installed"
    else "imported"
  }.toMutableMap()
  builtinKeys.forEach { (id, key) ->
    sections[id] = if (key.startsWith("coloros-")) "coloros" else "builtin"
  }
  if (perfectPackId != null && records[perfectPackId]?.format == IconPackFormat.MIUI_ICONS) {
    sections[perfectPackId] = "perfect"
  }
  return StatusIconPackMetadata(sections, associations, sources,
    records.mapNotNull { (id, record) -> record.nativeIconsSha256?.let { id to it } }.toMap())
}

/** The caller supplies a fresh applied report; preview selections and display labels are ignored. */
internal fun statusIconPackDestination(
  theme: SystemIconThemeReport?,
  catalog: IconPacksUiState,
  perfectPackId: String? = null,
): String? = statusIconPackDestination(theme, StatusIconPackMetadata(
  sections = catalog.packs.groupBy { it.pack.id }.filterValues { it.size == 1 }.mapNotNull { (id, cards) ->
    statusPackSection(cards.single().pack, perfectPackId)?.let { id to it }
  }.toMap(),
  nativeAssociations = catalog.nativeAssociations,
  nativeSources = catalog.nativeSources,
  nativeHashes = catalog.packs.mapNotNull {
    (it.pack.source as? IconPackSource.Imported)?.record?.nativeIconsSha256?.let { hash -> it.pack.id to hash }
  }.toMap(),
))

internal fun statusIconPackDestination(
  theme: SystemIconThemeReport?,
  metadata: StatusIconPackMetadata,
): String? {
  if (theme?.source != SystemIconThemeReport.Source.THEME || !theme.complete) return null

  val localId = theme.localId?.takeIf { it.isNotBlank() }
  val historicalSource = localId?.let(metadata.nativeSources::get)
  val associatedSources = if (localId == null) emptyList() else
    metadata.nativeAssociations.filterValues { it == localId }.keys.toList()
  if (historicalSource == null && associatedSources.size > 1) return null
  val source = historicalSource ?: associatedSources.singleOrNull()
  val id = if (source != null) {
    val id = statusCatalogPackId(source) ?: return null
    // An applied receipt for a deleted pack must not redirect to another pack with similar artwork.
    id.takeIf { it in metadata.sections } ?: return null
  } else {
    val hash = theme.nativeHash?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
    metadata.nativeHashes.filterValues { it == hash }.keys.singleOrNull() ?: return null
  }
  return metadata.sections[id]
}

private fun statusCatalogPackId(source: String): String? {
  val id = SystemIconFallback.baseSourceId(source)
  if (id.isBlank()) return null
  if (!id.startsWith("coloros:")) return id
  val parts = id.split(':', limit = 4)
  if (parts.size != 4 || parts[3].isBlank() ||
    ColorOsStyle.entries.none { it.key == parts[1] } ||
    IconPackAppearance.entries.none { it.name == parts[2] }) return null
  return parts[3]
}

private fun statusPackSection(pack: IconPackDescriptor, perfectPackId: String?): String? = when {
  pack.source is IconPackSource.Imported && pack.id == perfectPackId -> "perfect"
  pack.source is IconPackSource.Generated && pack.id == "apple" -> "apple"
  pack.colorOsProfile != null || pack.builtinKey?.startsWith("coloros-") == true -> "coloros"
  pack.builtinKey != null -> "builtin"
  pack.source is IconPackSource.Installed -> "installed"
  pack.source is IconPackSource.Imported &&
    pack.format in setOf(IconPackFormat.APK, IconPackFormat.INSTALLED_APK) -> "installed"
  pack.source is IconPackSource.Imported -> "imported"
  else -> null
}
