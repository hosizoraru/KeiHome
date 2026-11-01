package os.kei.hyper.home.ui.main

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Process
import android.os.UserManager
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import os.kei.hyper.home.data.AppIconPreviews
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.iconpack.*
import os.kei.hyper.home.status.CurrentIconSnapshot
import os.kei.hyper.home.status.SystemIconThemeReport

internal data class IconBrowserOptions(val scope: Int = 0, val origin: Int = 0,
  val coverage: Int = 0, val support: Int = 0, val sort: Int = 0, val showOverlays: Boolean = false,
  val reverse: Boolean = false)
internal data class IconBrowserSource(val revision: String, val pack: IconPackDescriptor,
  val entries: Map<String, IconPackEntry>, val paletteNames: Set<String>, val dynamicNames: Set<String>,
  val nightNames: Set<String> = emptySet())
internal data class IconBrowserRow(val app: InstalledIconCapability, val entry: IconPackEntry?,
  val installed: Boolean = true, val dynamic: Boolean = false) {
  val covered get() = entry != null
  val key get() = if (installed) "${app.userId}:${app.packageName}" else "resource:${entry?.key}"
}

internal suspend fun readBrowserSource(context: Context, report: SystemIconThemeReport): IconBrowserSource? =
  withContext(Dispatchers.IO) {
    val key = report.previewKey ?: return@withContext null
    val file = CurrentIconSnapshot.file(context, key)
    val scratch = java.io.File(context.cacheDir, "browser-scratch.zip")
    val caller = currentCoroutineContext()
    val index = try { IconPackArchive().inspect(file, scratch, android.os.Build.VERSION.SDK_INT) { caller.ensureActive() } }
      finally { scratch.delete() }
    val record = StoredIconPack(key, report.title ?: "System icons", 0, index.format, file,
      file, file.length(), index.nativeIconsSha256)
    val pack = IconPackDescriptor("current:$key", report.title ?: "System icons", index.format,
      IconPackSource.Imported(record), index.entries, index.description, notices = index.notices)
    val palettes = hashSetOf<String>(); val dynamic = hashSetOf<String>(); val nights = hashSetOf<String>()
    ZipFile(file).use { zip ->
      fun differs(first: String?, second: String?): Boolean {
        val a = first?.let { zip.getEntry(it) } ?: return false
        val b = second?.let { zip.getEntry(it) } ?: return false
        return a.crc != b.crc || a.size != b.size
      }
      index.entries.forEach { entry ->
        caller.ensureActive()
        val name = entry.nativeName ?: return@forEach
        fun selectedPath(dark: Boolean) = (entry.artwork(dark) as? IconPackArtwork.Archive)?.let {
          selectArchiveVariant(it.variants, dark, context.resources.displayMetrics.densityDpi,
            android.os.Build.VERSION.SDK_INT)?.path
        }
        if (differs(selectedPath(false), selectedPath(true)) ||
          differs("fancy_icons/$name/day.png", "fancy_icons/$name/night.png")) nights += name
        val manifest = zip.getEntry("fancy_icons/$name/manifest.xml") ?: return@forEach
        val xml = zip.getInputStream(manifest).use { String(it.readNBytes(256 * 1024)) }
        if (xml.contains("keihome_maml_enabled") && xml.contains("keihome_maml_fg_hex") &&
          zip.getEntry("fancy_icons/$name/mask.png") != null) palettes += name
        if (!xml.contains("src=\"day.png\"") || !xml.contains("src=\"night.png\"")) dynamic += name
      }
    }
    IconBrowserSource(key, pack, pack.entries.mapNotNull { e -> e.nativeName?.let { it to e } }.toMap(), palettes, dynamic, nights)
  }

internal fun browserRows(apps: List<InstalledIconCapability>, source: IconBrowserSource?,
  options: IconBrowserOptions, query: String): List<IconBrowserRow> {
  fun row(app: InstalledIconCapability, entry: IconPackEntry?, installed: Boolean = true): IconBrowserRow {
    val name = entry?.nativeName
    val dynamic = name in source?.dynamicNames.orEmpty()
    val resolved = if (entry == null) app else app.copy(
      nightMode = when { dynamic -> IconCapabilityStatus.UNKNOWN
        name in source?.nightNames.orEmpty() -> IconCapabilityStatus.DETECTED
        else -> IconCapabilityStatus.NOT_DETECTED },
      monochrome = when { name in source?.paletteNames.orEmpty() -> IconCapabilityStatus.DETECTED
        dynamic -> IconCapabilityStatus.UNKNOWN; else -> IconCapabilityStatus.NOT_DETECTED },
      monochromeFallbackRecommended = false)
    return IconBrowserRow(resolved, entry, installed, dynamic)
  }
  val installed = apps.map { app -> row(app, app.componentName?.let { source?.entries?.get(it) }
    ?: source?.entries?.get(app.packageName)) }
  val rows = if (options.scope == 2) {
    val used = installed.mapNotNull { it.entry?.key }.toSet()
    installed.filter { it.covered } + source?.pack?.entries.orEmpty().filter { it.key !in used }.map { entry ->
      val name = entry.component?.packageName ?: entry.nativeName ?: entry.key
      row(InstalledIconCapability(name, name, -1, true, IconCapabilityStatus.UNKNOWN,
        IconCapabilityStatus.UNKNOWN, hasLauncher = false), entry, false)
    }
  } else installed
  val result = rows.filter { r ->
    (options.showOverlays || !r.app.overlayApp) &&
      (options.scope != 1 || r.app.hasLauncher) &&
      (options.origin == 0 || (r.installed && (if (options.origin == 1) !r.app.systemApp else r.app.systemApp))) &&
      (options.coverage == 0 || (if (options.coverage == 1) r.covered else !r.covered)) &&
      (when (options.support) {
        1 -> r.app.nightMode == IconCapabilityStatus.DETECTED
        2 -> r.app.monochrome == IconCapabilityStatus.DETECTED
        3 -> r.app.nightMode == IconCapabilityStatus.DETECTED && r.app.monochrome == IconCapabilityStatus.DETECTED
        4 -> r.dynamic || r.app.nightMode == IconCapabilityStatus.UNKNOWN || r.app.monochrome == IconCapabilityStatus.UNKNOWN
        else -> true
      }) && r.app.matchesQuery(query)
  }
  val sorted = when (options.sort) {
    1 -> result.sortedBy { it.app.packageName }
    2 -> result.sortedByDescending { it.app.updatedAt }
    3 -> result.sortedWith(compareByDescending<IconBrowserRow> { it.covered }.thenBy { it.app.label.lowercase() })
    else -> result.sortedWith(compareBy<IconBrowserRow> { it.app.label.lowercase() }.thenBy { it.app.packageName })
  }
  return if (options.reverse) sorted.reversed() else sorted
}

internal suspend fun browserPreview(model: HomeViewModel, row: IconBrowserRow,
  source: IconBrowserSource?): AppIconPreviews {
  if (source == null || row.entry == null) return if (row.installed) model.previews.load(row.app) else AppIconPreviews()
  val pair = model.packPreviews.load(source.pack, row.entry)
  return withContext(Dispatchers.IO) {
    val caller = currentCoroutineContext()
    val name = row.entry.nativeName
    var mono: android.graphics.Bitmap? = null
    var background: android.graphics.Bitmap? = null
    var day = pair.light; var night = pair.dark
    ZipFile((source.pack.source as IconPackSource.Imported).record.originalFile).use { zip ->
      fun png(path: String): android.graphics.Bitmap? {
        caller.ensureActive()
        val entry = zip.getEntry(path) ?: return null
        if (entry.size !in 1..4 * 1024 * 1024) return null
        val bytes = zip.getInputStream(entry).use { it.readNBytes(4 * 1024 * 1024 + 1) }
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, size)
        if (size.outWidth !in 1..512 || size.outHeight !in 1..512) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      }
      if (name in source.paletteNames) {
        mono = png("fancy_icons/$name/mask.png")
        background = png("fancy_icons/$name/bg.png")
      }
      if (name !in source.dynamicNames) {
        day = png("fancy_icons/$name/day.png") ?: day
        night = png("fancy_icons/$name/night.png") ?: night
      }
    }
    AppIconPreviews(day, night, mono, mono, backgroundMask = background)
  }
}
