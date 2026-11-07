package os.kei.hyper.home.iconpack

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** App-process catalog only. The native bridge owns loaded-theme observations and apply.
 * No root paths, all-packages permission, package installation, or persistent URI grant. */
internal class IconPackRepository(
  context: Context,
  private val limits: IconPackLimits = IconPackLimits(),
  dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val application = context.applicationContext
  private val worker = dispatcher.limitedParallelism(2)
  private val store = IconPackStore(File(application.noBackupFilesDir, "icon-packs"), limits)
  private val builtinRoot = File(application.cacheDir, "builtin-icon-packs")
  private val builtins = BuiltinIconPackStore(application.assets, limits)
  private val android = AndroidIconPackResources(application, limits)
  private val importedCache = mutableMapOf<String, IconPackDescriptor>()
  private data class InstalledKey(val packageName: String, val version: Long, val updated: Long, val configuration: String)
  private val installedCache = mutableMapOf<InstalledKey, AndroidIconPackIndex?>()
  private var builtinCache: IconPackScanResult? = null
  private val builtinMutex = Mutex()

  fun invalidateInstalled() = synchronized(installedCache) { installedCache.clear() }

  suspend fun listBuiltins(): IconPackScanResult = withContext(worker) {
    builtinMutex.withLock {
      builtinCache?.let { return@withLock it }
      // Legacy per-version copies are regenerable. New builtins read directly from the APK.
      builtinRoot.deleteRecursively()
      val caller = currentCoroutineContext()
      val packs = mutableListOf<IconPackDescriptor>()
      val failures = mutableListOf<IconPackFailure>()
      for (record in builtins.list { caller.ensureActive() }.filter { it.key != "coloros-runtime-fallback" }) {
        caller.ensureActive()
        try {
          packs += loadBuiltin(record) { caller.ensureActive() }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { failures += IconPackFailure(record.key, failure.message.orEmpty()) }
      }
      IconPackScanResult(packs, failures).also { builtinCache = it }
    }
  }

  suspend fun listInstalled(): IconPackScanResult = withContext(worker) {
    val caller = currentCoroutineContext()
    val pm = application.packageManager
    val candidates = linkedSetOf<String>()
    val failures = mutableListOf<IconPackFailure>()
    // The current Manifest already exposes launcher activities. Intent-specific queries can
    // discover additional visible packs without requiring QUERY_ALL_PACKAGES.
    val intents = listOf(
      Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
      Intent("org.adw.launcher.THEMES"),
      Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"),
      Intent("com.novalauncher.THEME"),
      Intent("ch.deletescape.lawnchair.ICONPACK"),
    )
    for (intent in intents) {
      caller.ensureActive()
      try {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
          .forEach { it.activityInfo?.packageName?.let(candidates::add) }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { failures += IconPackFailure(intent.action.orEmpty(), failure.message ?: "Package discovery failed") }
    }
    val packs = mutableListOf<IconPackDescriptor>()
    val configuration = application.resources.configuration.toString()
    for (pkg in candidates.take(2000)) {
      caller.ensureActive()
      try {
        val info = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        val key = InstalledKey(pkg, info.longVersionCode, info.lastUpdateTime, configuration)
        val cached = synchronized(installedCache) { installedCache.containsKey(key) to installedCache[key] }
        val index = if (cached.first) cached.second else {
          android.index(info, check = { caller.ensureActive() }).also { value ->
            synchronized(installedCache) {
              installedCache.keys.removeAll { it.packageName == pkg }
              installedCache[key] = value
            }
          }
        }
        if (index == null) continue
        packs += IconPackDescriptor("installed:$pkg", index.displayName, IconPackFormat.INSTALLED_APK,
          IconPackSource.Installed(pkg, info.longVersionCode, info.lastUpdateTime, info.versionName), index.entries,
          notices = index.notices, directives = index.directives)
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { failures += IconPackFailure(pkg, failure.message ?: "Icon pack cannot be read") }
    }
    synchronized(installedCache) { installedCache.keys.removeAll { it.packageName !in candidates } }
    if (candidates.size > 2000) failures += IconPackFailure("installed", "Installed-package scan limit exceeded")
    IconPackScanResult(packs.sortedBy { it.displayName.lowercase() }, failures, visibilityLimited = true)
  }

  suspend fun listImported(): IconPackScanResult = withContext(worker) {
    val caller = currentCoroutineContext()
    val listing = store.list()
    val packs = mutableListOf<IconPackDescriptor>()
    val failures = listing.failures.toMutableList()
    for (record in listing.records) {
      caller.ensureActive()
      try { packs += load(record) { caller.ensureActive() }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { failures += IconPackFailure(record.id, failure.message ?: "Imported pack cannot be read") }
    }
    IconPackScanResult(packs, failures)
  }

  /** Call from the OpenDocument result while its read grant is alive. Bytes are copied before
   * returning; the document provider's DISPLAY_NAME is cosmetic and its reported SIZE is ignored. */
  suspend fun import(uri: Uri): IconPackImportResult = withContext(worker) {
    val caller = currentCoroutineContext()
    try {
      if (uri.scheme != "content") throw IconPackException("Select an icon pack using the system document picker")
      val resolver = application.contentResolver
      val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
          ?.let(cursor::getString)?.take(256) else null
      } ?: "Icon pack"
      caller.ensureActive()
      val stored = (resolver.openInputStream(uri) ?: throw IconPackException("Document cannot be opened")).use { input ->
        store.import(input, name, Build.VERSION.SDK_INT, { caller.ensureActive() }, validateApk = { file ->
          if (android.index(android.archiveInfo(file), file) { caller.ensureActive() } == null) {
            throw IconPackException("APK contains no supported appfilter")
          }
        })
      }
      IconPackImportResult.Success(load(stored.record) { caller.ensureActive() }, stored.alreadyPresent)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) { IconPackImportResult.Failure(failure.message ?: "Icon pack could not be imported") }
  }

  /** Classic/APK export is a static snapshot of one mode. Native imports preserve the entire
   * original icons module, including night/animation resources. Neither path applies the pack. */
  suspend fun exportNativeIcons(
    pack: IconPackDescriptor,
    targets: List<IconPackComponent>,
    darkMode: Boolean,
    sizePx: Int = 192,
    systemFallback: os.kei.hyper.home.data.SystemIconFallback? = null,
    systemAppNameMatching: Boolean = false,
    colorOsForOtherPacks: Boolean = false,
    installedOnly: Boolean = false,
  ): IconPackExportResult = withContext(worker) {
    val caller = currentCoroutineContext()
    val fallback = if (colorOsForOtherPacks && pack.colorOsProfile == null) runtimeFallback() else null
    IconPackExporter(application, limits).export(pack, targets, darkMode, sizePx, systemFallback,
      systemAppNameMatching, fallback, installedOnly) { caller.ensureActive() }
  }

  private var runtimeFallbackCache: IconPackDescriptor? = null
  suspend fun runtimeFallback(): IconPackDescriptor? = withContext(worker) {
    builtinMutex.withLock {
      runtimeFallbackCache?.let { return@withLock it }
      val caller = currentCoroutineContext()
      val record = builtins.list { caller.ensureActive() }.firstOrNull { it.key == "coloros-runtime-fallback" }
        ?: return@withLock null
      loadBuiltin(record) { caller.ensureActive() }.also { runtimeFallbackCache = it }
    }
  }

  private fun loadBuiltin(record: BuiltinIconPack, check: () -> Unit): IconPackDescriptor {
    val index = IconPackArchive(limits).classicIndex(record.paths, Build.VERSION.SDK_INT, check) { path, max ->
      record.read(path, max, check)
    }
    return IconPackDescriptor(record.id, index.description.title ?: record.key, index.format,
      IconPackSource.Builtin(record), index.entries, index.description, index.previews, index.notices, index.directives,
      colorOsProfile = ColorOsPackProfile.load(record, check), builtinKey = record.key)
  }

  private fun load(record: StoredIconPack, check: () -> Unit): IconPackDescriptor {
    synchronized(importedCache) { importedCache[record.id]?.let { return it } }
    if (sha256(record.originalFile, check) != record.id) throw IconPackException("Stored archive checksum mismatch")
    val descriptor = if (record.format == IconPackFormat.APK) {
      val index = android.index(android.archiveInfo(record.originalFile), record.originalFile, check)
        ?: throw IconPackException("APK contains no supported appfilter")
      IconPackDescriptor(record.id, index.displayName, record.format, IconPackSource.Imported(record), index.entries,
        notices = index.notices, directives = index.directives)
    } else {
      val scratch = File.createTempFile("icon-pack-index-", ".zip", application.cacheDir)
      try {
        val index = IconPackArchive(limits).inspect(record.originalFile, scratch, Build.VERSION.SDK_INT, check)
        if (index.nativeIconsSha256 != record.nativeIconsSha256 ||
          (record.iconsFile != null && sha256(record.iconsFile, check) != record.nativeIconsSha256)
        ) throw IconPackException("Stored icons module checksum mismatch")
        IconPackDescriptor(record.id, index.description.title ?: record.originalName, index.format,
          IconPackSource.Imported(record), index.entries, index.description, index.previews, index.notices, index.directives,
          colorOsProfile = ColorOsPackProfile.load(record, check))
      } finally { scratch.delete() }
    }
    check()
    synchronized(importedCache) { importedCache[record.id] = descriptor }
    return descriptor
  }

  companion object {
    /** MTZ providers commonly report application/octet-stream, so do not filter only ZIP MIME. */
    fun createImportIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
      addCategory(Intent.CATEGORY_OPENABLE)
      type = "*/*"
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }
}
