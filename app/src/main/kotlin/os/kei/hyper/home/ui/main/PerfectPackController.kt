package os.kei.hyper.home.ui.main

import android.content.Context
import android.os.SystemClock
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONObject
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.data.DiskCacheUsage
import os.kei.hyper.home.data.iconPackTargets
import os.kei.hyper.home.iconpack.IconPackRepository
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackImportResult
import os.kei.hyper.home.iconpack.IconPackArchive
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.sha256
import os.kei.hyper.home.iconpack.perfect.PerfectIconCatalog
import os.kei.hyper.home.iconpack.perfect.PerfectIconCooldownException
import os.kei.hyper.home.iconpack.perfect.PerfectIconOptions
import os.kei.hyper.home.iconpack.perfect.PerfectIconProgress
import os.kei.hyper.home.iconpack.perfect.PerfectIconRemote
import os.kei.hyper.home.iconpack.perfect.PerfectIconRepository
import os.kei.hyper.home.iconpack.perfect.PerfectIconResult
import os.kei.hyper.home.iconpack.perfect.PerfectIconStyles
import os.kei.hyper.home.iconpack.perfect.PerfectLocalHealth
import os.kei.hyper.home.iconpack.perfect.PerfectSyncFailure

internal const val PERFECT_PACK_UI_STORAGE_ID = "perfect_icons_ui_v1"

internal enum class PerfectPackError {
  LOCAL, CATALOG, NETWORK, CHECK, STYLE_NEEDED, COOLDOWN, INSTALLED, GENERATE, IMPORT, EMPTY, CACHE, DELETE,
}
internal enum class PerfectSyncIssue { FAILED, INTERRUPTED, CORRUPT }

internal data class PerfectPackPreviewState(
  val options: PerfectIconOptions? = null,
  val sourceHash: String? = null,
  val pack: IconPackDescriptor? = null,
  val loading: Boolean = false,
  val failed: Boolean = false,
)

internal data class PerfectPackState(
  val options: PerfectIconOptions = PerfectIconOptions(),
  val catalog: PerfectIconCatalog? = null,
  val remote: PerfectIconRemote? = null,
  val loading: Boolean = false,
  val checking: Boolean = false,
  val refreshing: Boolean = false,
  val syncingStyles: Set<String> = emptySet(),
  val syncStartedAtElapsedMillis: Long = 0,
  val generating: Boolean = false,
  val clearingCache: Boolean = false,
  val deletingResources: Boolean = false,
  val previewLoading: Boolean = false,
  val clearedBytes: Long? = null,
  val deletedBytes: Long? = null,
  val progress: PerfectIconProgress? = null,
  val cacheBytes: Long = 0,
  val resourceBytes: Long = 0,
  val retryAtMillis: Long = 0,
  val error: PerfectPackError? = null,
  val result: PerfectIconResult? = null,
  val generatedOptions: PerfectIconOptions? = null,
  val pack: IconPackCardData? = null,
  val syncIssue: PerfectSyncIssue? = null,
  val syncIssueAtMillis: Long = 0,
  val lastSuccessfulSyncAtMillis: Long = 0,
) {
  val busy: Boolean get() = loading || checking || refreshing || generating || clearingCache ||
    deletingResources || previewLoading
  val selectedStyle: String get() = options.style.ifEmpty { "default" }
  val ready: Boolean get() = catalog?.downloadedStyles?.any {
    it.ifEmpty { "default" } == selectedStyle
  } == true
  val updateAvailable: Boolean get() = remote?.let { latest ->
    catalog?.let { it.revision != latest.revision }
  } == true
}

/** Owns page work in the Home ViewModel, so navigation does not restart network requests. */
internal class PerfectPackController(
  context: Context,
  private val scope: CoroutineScope,
  private val repository: PerfectIconRepository,
  private val packs: IconPackRepository,
  private val storageId: String = PERFECT_PACK_UI_STORAGE_ID,
  private val keepLatestGeneratedPacks: () -> Boolean = { true },
  private val onGenerated: () -> Unit,
) {
  private val app = context.applicationContext
  private val storage by lazy { AppLocalStorage.open(app, storageId) }
  private val mutableState = MutableStateFlow(PerfectPackState(options = PerfectIconStyles.initialOptions))
  val state = mutableState.asStateFlow()
  private val mutablePreview = MutableStateFlow(PerfectPackPreviewState())
  val previewState = mutablePreview.asStateFlow()
  @Volatile private var loaded = false
  private var loadJob: Job? = null
  private var checkJob: Job? = null
  private var localRefreshJob: Job? = null
  private var previewJob: Job? = null
  private var previewEpoch = 0
  private val catalogBootstrap = PerfectCatalogBootstrap()
  private val resumeWork = PerfectResourceResumeWork(scope, refreshLocal = {
    state.first { !it.busy }
    if (loaded) startLocalRefresh()?.join() else startLoad()?.join()
    state.first { !it.busy }
  }, bootstrap = {
    if (catalogBootstrap.shouldCheck(state.value, System.currentTimeMillis())) startCheckUpdates()?.join()
  })

  /** Only the resource browser opts into complete metadata discovery; other pages remain local-only. */
  fun onResourcesResumed(ensureCatalog: Boolean = false): Job = resumeWork.resume(ensureCatalog)

  fun invalidateMaskPreview() {
    // Rebuild lazily on returning to the preview page; do not start downloads or apply a theme.
    previewEpoch++
    previewJob?.cancel()
    previewJob = null
    mutablePreview.value = PerfectPackPreviewState()
    mutableState.update { it.copy(previewLoading = false) }
  }

  fun loadPreview(refresh: Boolean = false) {
    val current = state.value
    if (current.busy) return
    if (!current.ready) {
      mutablePreview.value = PerfectPackPreviewState(options = current.options, failed = true)
      return
    }
    val catalog = current.catalog ?: run {
      mutablePreview.value = PerfectPackPreviewState(options = current.options, failed = true)
      return
    }
    val previous = previewState.value
    if (!refresh && previous.options == current.options && previous.sourceHash == catalog.sourceHash &&
      (previous.pack != null || previous.failed)) return
    mutablePreview.value = PerfectPackPreviewState(current.options, catalog.sourceHash, loading = true)
    mutableState.update { it.copy(previewLoading = true) }
    val epoch = ++previewEpoch
    previewJob = scope.launch {
      try {
        val descriptor = withContext(Dispatchers.IO) {
          val file = repository.previewArchive(current.options, limit = 24)
          val scratch = File(app.cacheDir, "perfect-preview-icons.zip")
          val caller = coroutineContext
          val index = try {
            IconPackArchive().inspect(file, scratch, android.os.Build.VERSION.SDK_INT) { caller.ensureActive() }
          } finally {
            scratch.delete()
          }
          val digest = sha256(file) { caller.ensureActive() }
          IconPackDescriptor(
            id = "perfect-preview:$digest",
            displayName = current.options.style.ifBlank { "default" },
            format = index.format,
            source = IconPackSource.Generated(file, digest, file.lastModified()),
            entries = index.entries,
            description = index.description,
            previews = index.previews,
            notices = index.notices,
            directives = index.directives,
          )
        }
        if (epoch == previewEpoch) mutablePreview.update { it.copy(pack = descriptor) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (epoch == previewEpoch) mutablePreview.update { it.copy(failed = true) }
      } finally {
        if (epoch == previewEpoch) {
          mutablePreview.update { it.copy(loading = false) }
          updateCache()
          mutableState.update { it.copy(previewLoading = false) }
        }
      }
    }
  }

  fun load() {
    startLoad()
  }

  private fun startLoad(): Job? {
    loadJob?.takeIf { it.isActive }?.let { return it }
    if (loaded || state.value.busy) return null
    mutableState.update { it.copy(loading = true, error = null) }
    return scope.launch(start = CoroutineStart.LAZY) {
      var failure = PerfectPackError.LOCAL
      try {
        loaded = withContext(Dispatchers.IO) {
          val options = repository.options()
          val remote = repository.remoteStatus()
          mutableState.update { it.copy(options = options,
            remote = retainPerfectCatalogMetadata(it.remote, remote, it.catalog)) }
          val catalog = repository.catalog(refresh = false)
          mutableState.update { it.copy(options = options, catalog = catalog) }
          val saved = storage.getString("last_generated", null)?.let(::JSONObject)
          if (saved != null) {
            val result = saved.result()
            val generatedOptions = PerfectIconOptions(
              style = saved.optString("style"),
              installedOnly = saved.optBoolean("installedOnly", true),
              dynamic = saved.optBoolean("dynamic", true),
              staticNight = saved.optBoolean("staticNight", false),
              fixedDark = saved.optBoolean("fixedDark", false),
            )
            mutableState.update { it.copy(result = result, generatedOptions = generatedOptions) }
            val id = saved.optString("recordId")
            val pack = packs.listImported().packs.firstOrNull { it.id == id } ?: if (result.file.isFile) {
              failure = PerfectPackError.IMPORT
              val keepLatest = keepLatestGeneratedPacks()
              importGenerated(result.file, generatedOptions, keepLatest).also {
                remember(result, generatedOptions, it.id)
                if (keepLatest) prunePrevious(it, result.file)
                withContext(Dispatchers.Main.immediate) { onGenerated() }
              }
            } else null
            val targets = iconPackTargets(app) { coroutineContext.ensureActive() }
            mutableState.update { it.copy(
              pack = pack?.let { descriptor -> iconPackCardData(descriptor, targets) },
              error = if (pack == null) PerfectPackError.LOCAL else it.error,
            ) }
            pack != null
          } else true
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        android.util.Log.w("KeiHomePerfectIcons", "Perfect Icons local state load failed", error)
        mutableState.update { it.copy(error = failure) }
      } finally {
        withContext(NonCancellable) {
          updateCache()
          mutableState.update { it.copy(loading = false) }
        }
      }
    }.also { loadJob = it; it.start() }
  }

  suspend fun cacheBytes(): Long = repository.cacheBytes()

  internal suspend fun storageUsage(): DiskCacheUsage = repository.storageUsage()

  fun clearDownloads() {
    if (state.value.busy) return
    mutableState.update { it.copy(clearingCache = true, clearedBytes = null, error = null) }
    scope.launch {
      try {
        val cleared = repository.clearDownloadCache()
        mutablePreview.value = PerfectPackPreviewState()
        mutableState.update { it.copy(clearedBytes = cleared) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(error = PerfectPackError.CACHE) }
      } finally {
        updateCache()
        mutableState.update { it.copy(clearingCache = false) }
      }
    }
  }

  fun deleteResources() {
    removeResources(null)
  }

  fun removeStyles(styles: Set<String>) {
    val downloaded = state.value.catalog?.downloadedStyles.orEmpty().map { it.ifEmpty { "default" } }.toSet()
    val requested = styles.map { it.ifEmpty { "default" } }.toSet().intersect(downloaded)
    if (requested.isEmpty()) return
    removeResources(requested)
  }

  private fun removeResources(styles: Set<String>?) {
    if (state.value.busy) return
    mutableState.update { it.copy(deletingResources = true, deletedBytes = null, progress = null, error = null) }
    scope.launch {
      try {
        val bytes = withContext(Dispatchers.IO) {
          if (styles == null) repository.deleteResources()
          else repository.removeStyles(styles) { progress ->
            mutableState.update { it.copy(progress = progress) }
          }
        }
        mutableState.update { it.copy(deletedBytes = bytes, clearedBytes = null, progress = null) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(error = PerfectPackError.DELETE) }
      } finally {
        withContext(NonCancellable) {
          mutablePreview.value = PerfectPackPreviewState()
          updateCache()
          mutableState.update { it.copy(deletingResources = false) }
          onGenerated()
        }
      }
    }
  }

  /** Reconcile resumes without flashing a loading card or performing network work. */
  fun refreshLocal() {
    startLocalRefresh()
  }

  private fun startLocalRefresh(): Job? {
    localRefreshJob?.takeIf { it.isActive }?.let { return it }
    if (!loaded || state.value.busy) return null
    val expected = state.value
    return scope.launch(start = CoroutineStart.LAZY) {
      updateCache(expected)
    }.also { localRefreshJob = it; it.start() }
  }

  fun previewStyle(style: String, onReady: () -> Unit) {
    if (state.value.catalog?.downloadedStyles?.contains(style.ifEmpty { "default" }) != true) return
    setOptions(state.value.options.copy(style = style.takeUnless { it == "default" }.orEmpty()), onReady)
  }

  fun setOptions(options: PerfectIconOptions, onSaved: () -> Unit = {}) {
    if (state.value.busy) return
    if (options == state.value.options) { onSaved(); return }
    mutableState.update { it.copy(loading = true, error = null) }
    scope.launch {
      var saved = false
      try {
        withContext(Dispatchers.IO) { repository.setOptions(options) }
        mutablePreview.value = PerfectPackPreviewState()
        mutableState.update { it.copy(options = options) }
        saved = true
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(error = PerfectPackError.LOCAL) }
      } finally {
        mutableState.update { it.copy(loading = false) }
      }
      if (saved) onSaved()
    }
  }

  fun checkUpdates() {
    startCheckUpdates()
  }

  private fun startCheckUpdates(): Job? {
    checkJob?.takeIf { it.isActive }?.let { return it }
    if (!canStart()) return null
    catalogBootstrap.started()
    mutableState.update { it.copy(checking = true, progress = null, error = null) }
    return scope.launch(start = CoroutineStart.LAZY) {
      try {
        val remote = withContext(Dispatchers.IO) {
          repository.checkUpdates { progress ->
            mutableState.update { it.copy(progress = progress) }
          }
        }
        mutableState.update { it.copy(remote = retainPerfectCatalogMetadata(it.remote, remote, it.catalog)) }
      } catch (cancelled: CancellationException) {
        catalogBootstrap.retryWhenEligible()
        throw cancelled
      } catch (_: PerfectIconCooldownException) {
        catalogBootstrap.retryWhenEligible()
        mutableState.update { it.copy(error = PerfectPackError.COOLDOWN) }
      } catch (_: Exception) {
        catalogBootstrap.failed(System.currentTimeMillis())
        mutableState.update { it.copy(error = PerfectPackError.CHECK) }
      } finally {
        withContext(NonCancellable) {
          updateCache()
          mutableState.update { it.copy(checking = false, progress = null) }
        }
      }
    }.also { checkJob = it; it.start() }
  }

  fun refreshCatalog(forceDownload: Boolean = false) {
    syncStyles(setOf(state.value.selectedStyle), forceDownload)
  }

  fun downloadStyles(styles: Set<String>) {
    if (styles.isEmpty()) return
    syncStyles(styles.map { it.ifEmpty { "default" } }.toSet(), forceDownload = false)
  }

  fun repairResources() {
    val downloaded = state.value.catalog?.downloadedStyles.orEmpty().map { it.ifEmpty { "default" } }.toSet()
    if (downloaded.isNotEmpty()) syncStyles(downloaded, forceDownload = true)
  }

  private fun syncStyles(styles: Set<String>, forceDownload: Boolean) {
    if (!canStart()) return
    mutableState.update { it.copy(refreshing = true, progress = null, clearedBytes = null,
      deletedBytes = null, error = null, syncingStyles = styles,
      syncStartedAtElapsedMillis = SystemClock.elapsedRealtime()) }
    scope.launch {
      try {
        val catalog = withContext(Dispatchers.IO) {
          repository.catalog(refresh = true, forceDownload = forceDownload, styles = styles) { progress ->
            mutableState.update { it.copy(progress = progress) }
          }
        }
        if (catalog != null) mutablePreview.value = PerfectPackPreviewState()
        mutableState.update { it.copy(catalog = catalog ?: it.catalog,
          error = if (catalog == null) PerfectPackError.CATALOG else null) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: PerfectIconCooldownException) {
        mutableState.update { it.copy(error = PerfectPackError.COOLDOWN) }
      } catch (failure: IOException) {
        android.util.Log.w("KeiHomePerfectIcons", "Repository snapshot download failed", failure)
        mutableState.update { it.copy(error = PerfectPackError.NETWORK) }
      } catch (_: Exception) {
        mutableState.update { it.copy(error = PerfectPackError.CATALOG) }
      } finally {
        withContext(NonCancellable) {
          updateCache()
          mutableState.update { it.copy(refreshing = false, progress = null,
            syncingStyles = emptySet(), syncStartedAtElapsedMillis = 0) }
        }
      }
    }
  }

  fun generate() {
    // Only explicitly downloaded styles can generate; this path stays offline.
    if (state.value.busy) return
    if (!state.value.ready) {
      mutableState.update { it.copy(error = PerfectPackError.STYLE_NEEDED) }
      return
    }
    val options = state.value.options
    val keepLatest = keepLatestGeneratedPacks()
    mutableState.update { it.copy(generating = true, progress = null, clearedBytes = null, error = null) }
    scope.launch {
      var failure = PerfectPackError.INSTALLED
      try {
        withContext(Dispatchers.IO) {
          val installed = installedNames()
          failure = PerfectPackError.GENERATE
          val result = repository.generate(options, installed) { progress ->
            mutableState.update { it.copy(progress = progress) }
          }
          if (result.mapped <= 0) {
            mutableState.update { it.copy(error = PerfectPackError.EMPTY) }
            return@withContext
          }
          failure = PerfectPackError.LOCAL
          // Save the file before import so process death can recover the completed ZIP locally.
          remember(result, options, "")
          // A later entry must recover this publication if import fails or is cancelled.
          loaded = false
          mutableState.update { it.copy(result = result, generatedOptions = options, pack = null) }
          failure = PerfectPackError.IMPORT
          val pack = importGenerated(result.file, options, keepLatest)
          remember(result, options, pack.id)
          if (keepLatest) prunePrevious(pack, result.file)
          withContext(Dispatchers.Main.immediate) { onGenerated() }
          val targets = iconPackTargets(app) { coroutineContext.ensureActive() }
          mutableState.update { it.copy(pack = iconPackCardData(pack, targets)) }
          val catalog = repository.catalog(refresh = false)
          loaded = true
          mutableState.update { it.copy(catalog = catalog ?: it.catalog) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        android.util.Log.w("KeiHomePerfectIcons", "Local icon pack generation failed", error)
        mutableState.update { it.copy(error = failure) }
      } finally {
        updateCache()
        mutableState.update { it.copy(generating = false, progress = null) }
      }
    }
  }

  fun updateCooldown() {
    mutableState.update { if (it.retryAtMillis <= System.currentTimeMillis())
      it.copy(retryAtMillis = 0,
        error = it.error.takeUnless { error -> error == PerfectPackError.COOLDOWN }) else it }
  }

  private fun canStart(): Boolean = !state.value.busy &&
    state.value.retryAtMillis <= System.currentTimeMillis()

  private suspend fun updateCache(expected: PerfectPackState? = null) {
    withContext(Dispatchers.IO) {
      try {
        val bytes = repository.cacheBytes()
        val resourceBytes = repository.resourceBytes()
        val retry = repository.retryAtMillis()
        val local = repository.localStatus()
        val history = repository.syncHistory()
        val remote = repository.remoteStatus()
        val issue = if (local.health == PerfectLocalHealth.CORRUPT) PerfectSyncIssue.CORRUPT
          else when (history.issue) {
            PerfectSyncFailure.FAILED -> PerfectSyncIssue.FAILED
            PerfectSyncFailure.INTERRUPTED -> PerfectSyncIssue.INTERRUPTED
            null -> null
          }
        mutableState.update { if (expected != null && it !== expected) it else it.copy(cacheBytes = bytes, resourceBytes = resourceBytes,
          retryAtMillis = retry, remote = retainPerfectCatalogMetadata(it.remote, remote, it.catalog),
          syncIssue = issue,
          syncIssueAtMillis = if (issue == PerfectSyncIssue.CORRUPT) local.corruptAtMillis else history.issueAtMillis,
          lastSuccessfulSyncAtMillis = history.lastSuccessfulSyncAtMillis,
          catalog = local.catalog.takeIf { local.health == PerfectLocalHealth.READY }) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { if (expected != null && it !== expected) it else it.copy(error = it.error ?: PerfectPackError.LOCAL) }
      }
    }
  }

  private fun installedNames(): Set<String> = buildSet {
    val launcher = requireNotNull(app.getSystemService(LauncherApps::class.java))
    launcher.getActivityList(null, Process.myUserHandle()).forEach { activity ->
      add(activity.componentName.packageName)
      add(activity.componentName.className)
      add(activity.componentName.flattenToString())
    }
    app.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)).forEach {
      add(it.packageName)
    }
  }

  private suspend fun importGenerated(file: File, options: PerfectIconOptions,
    keepLatest: Boolean = keepLatestGeneratedPacks()): IconPackDescriptor =
    when (val imported = packs.importPerfectPublication(file,
      app.getString(os.kei.hyper.home.R.string.perfect_icons_title) + " · " +
        (options.style.ifEmpty { app.getString(os.kei.hyper.home.R.string.perfect_icons_style_default) })
          .take(96) + ".zip", keepLatest)) {
      is IconPackImportResult.Success -> imported.pack
      is IconPackImportResult.Failure ->
        throw IOException("Generated pack import failed: ${imported.message}", imported.cause)
    }

  private suspend fun prunePrevious(pack: IconPackDescriptor, file: File) {
    try { packs.prunePerfectPublications(pack, file)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) {
      android.util.Log.w("KeiHomePerfectIcons", "Previous generated packs could not be cleaned", failure)
    }
  }

  private fun remember(result: PerfectIconResult, options: PerfectIconOptions, recordId: String) {
    val saved = JSONObject().put("recordId", recordId).put("file", result.file.absolutePath)
      .put("mapped", result.mapped).put("staticCount", result.staticCount)
      .put("dynamicCount", result.dynamicCount).put("downloaded", result.downloaded)
      .put("reused", result.reused).put("revision", result.revision)
      .put("rejectedDynamic", result.rejectedDynamic).put("contract", result.contract)
      .put("nightRevision", result.nightRevision).put("derivedDynamicCount", result.derivedDynamicCount)
      .put("style", options.style).put("installedOnly", options.installedOnly).put("dynamic", options.dynamic)
      .put("staticNight", options.staticNight)
      .put("fixedDark", options.fixedDark)
    check(storage.encode("last_generated", saved.toString()))
    storage.sync()
  }

  private fun JSONObject.result() = PerfectIconResult(
    file = File(getString("file")),
    mapped = getInt("mapped"),
    staticCount = getInt("staticCount"),
    dynamicCount = getInt("dynamicCount"),
    downloaded = getInt("downloaded"),
    reused = getInt("reused"),
    revision = getString("revision"),
    rejectedDynamic = optInt("rejectedDynamic", 0),
    contract = optString("contract", ""),
    nightRevision = optString("nightRevision", ""),
    derivedDynamicCount = optInt("derivedDynamicCount", 0),
  )
}

/** Discovery is coalesced; failed attempts can retry on a later resume, never on a timer. */
internal class PerfectCatalogBootstrap {
  private var attempted = false
  private var retryAfter = 0L

  fun shouldCheck(state: PerfectPackState, nowMillis: Long): Boolean =
    !attempted && !state.busy && state.retryAtMillis <= nowMillis && retryAfter <= nowMillis &&
      state.remote?.availableStyles.isNullOrEmpty() &&
      !(state.catalog?.let { it.gitBacked && it.styles.isNotEmpty() } == true)

  fun started() { attempted = true }
  fun retryWhenEligible() { attempted = false }
  fun failed(nowMillis: Long) {
    attempted = false
    retryAfter = nowMillis + 30_000
  }
}

/** Local/sparse status reads must not erase a complete index already read for this revision. */
internal fun retainPerfectCatalogMetadata(
  previous: PerfectIconRemote?,
  current: PerfectIconRemote?,
  catalog: PerfectIconCatalog? = null,
): PerfectIconRemote? {
  val complete = previous?.takeIf {
    it.availableStyles.isNotEmpty() && (current == null || it.revision == current.revision)
  } ?: catalog?.takeIf {
    it.gitBacked && it.styles.isNotEmpty() && (current == null || it.revision == current.revision)
  }?.let {
    PerfectIconRemote(it.revision, it.remoteCommittedAt, it.remoteSubject, it.updatedAt, it.styles)
  }
  return when {
    current == null -> complete ?: previous
    complete?.revision == current.revision && current.availableStyles.isEmpty() ->
      current.copy(availableStyles = complete.availableStyles)
    else -> current
  }
}

/** Parent/child resumes share one local reconciliation and can upgrade it to catalog discovery. */
internal class PerfectResourceResumeWork(
  private val scope: CoroutineScope,
  private val refreshLocal: suspend () -> Unit,
  private val bootstrap: suspend () -> Unit,
) {
  private var job: Job? = null
  private var catalogRequested = false

  fun resume(ensureCatalog: Boolean): Job {
    catalogRequested = catalogRequested || ensureCatalog
    job?.takeIf { it.isActive }?.let { return it }
    return scope.launch(start = CoroutineStart.LAZY) {
      refreshLocal()
      if (catalogRequested) {
        catalogRequested = false
        bootstrap()
      }
    }.also { job = it; it.start() }
  }
}
