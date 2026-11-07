package os.kei.hyper.home.ui.main

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.apple.AppleAppMatch
import os.kei.hyper.home.iconpack.apple.AppleAppTarget
import os.kei.hyper.home.iconpack.apple.AppleArtworkPreview
import os.kei.hyper.home.iconpack.apple.AppleGenerationProgress
import os.kei.hyper.home.iconpack.apple.AppleGenerationResult
import os.kei.hyper.home.iconpack.apple.AppleIconOptions
import os.kei.hyper.home.iconpack.apple.AppleIconRepository
import os.kei.hyper.home.iconpack.apple.AppleRequestException
import os.kei.hyper.home.iconpack.apple.ApplePackSizeException
import os.kei.hyper.home.iconpack.apple.AppleMappingTransfer
import os.kei.hyper.home.iconpack.apple.AppleStorefrontPolicy

internal enum class ApplePackError { NETWORK, LIMITED, EMPTY, GENERATION, SIZE }
internal enum class AppleDarkCapability { UNKNOWN, INDEPENDENT, SHARED, MISSING }
internal data class AppleMappingRow(val target: AppleAppTarget, val app: AppleAppMatch?, val custom: Boolean,
  val darkCapability: AppleDarkCapability = AppleDarkCapability.UNKNOWN)
internal data class ApplePackUiState(
  val options: AppleIconOptions,
  val loading: Boolean = false,
  val busy: Boolean = false,
  val rows: List<AppleMappingRow> = emptyList(),
  val pack: IconPackDescriptor? = null,
  val publishedOptions: AppleIconOptions? = null,
  val cacheBytes: Long = 0,
  val retryAtMillis: Long = 0,
  val progress: AppleGenerationProgress? = null,
  val result: AppleGenerationResult? = null,
  val error: ApplePackError? = null,
  val editing: AppleAppTarget? = null,
  val searching: Boolean = false,
  val searched: Boolean = false,
  val candidates: List<AppleAppMatch> = emptyList(),
  val pendingChanges: Boolean = false,
  val previewing: Boolean = false,
  val preview: AppleArtworkPreview? = null,
  val currentPreview: AppleArtworkPreview? = null,
  val searchCountry: String = options.country,
  val previewRevision: Int = 0,
)

/** Owned by HomeViewModel, so navigation and recomposition cannot restart a download. */
internal class ApplePackController(
  private val scope: CoroutineScope,
  private val repository: AppleIconRepository,
  private val onGenerated: () -> Unit,
) {
  private val mutable = MutableStateFlow(ApplePackUiState(repository.options()))
  val state = mutable.asStateFlow()
  private var work: Job? = null
  private var search: Job? = null
  private var currentPreviewJob: Job? = null
  private var capabilitiesJob: Job? = null
  private var reloadRequested = false
  private var searchCountryExplicit = false

  fun load() {
    if (mutable.value.loading || mutable.value.busy) { reloadRequested = true; return }
    reloadRequested = false
    mutable.update { it.copy(loading = true, error = null) }
    scope.launch {
      try {
        val targets = repository.targets()
        val pack = repository.descriptor()
        val published = repository.publishedOptions()
        val bytes = repository.cacheBytes()
        val pending = repository.needsGeneration(targets, mutable.value.options)
        val options = mutable.value.options
        val snapshot = resolvedRows(rows(targets, options), options)
        mutable.update { value -> value.copy(loading = false,
          rows = if (value.options == options) snapshot else value.rows,
          pack = pack, publishedOptions = published, cacheBytes = bytes, pendingChanges = pending,
          retryAtMillis = repository.retryAtMillis(),
          previewRevision = value.previewRevision + 1) }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (_: Exception) { mutable.update { it.copy(loading = false, error = ApplePackError.GENERATION) } }
      if (reloadRequested) load()
    }
  }
  fun refreshTargetsIfLoaded() { if (mutable.value.rows.isNotEmpty()) load() }

  fun options(options: AppleIconOptions) {
    if (mutable.value.busy) return
    search?.cancel()
    repository.setOptions(options)
    mutable.update { it.copy(options = options, rows = rows(it.rows.map { row -> row.target }, options),
      result = null, error = null, candidates = emptyList(), searched = false, searching = false,
      pendingChanges = true, preview = null, previewing = false) }
    refreshCapabilities()
  }

  fun edit(target: AppleAppTarget) {
    search?.cancel()
    currentPreviewJob?.cancel()
    val snapshot = mutable.value
    val current = snapshot.rows.firstOrNull { it.target.packageName == target.packageName }?.app
    searchCountryExplicit = current?.country != null
    mutable.update { it.copy(editing = target, candidates = emptyList(), searching = false, searched = false,
      error = null, preview = null, currentPreview = null, previewing = false,
      searchCountry = AppleStorefrontPolicy.searchCountry(target.label, it.options.country, current?.country)) }
    if (current != null) currentPreviewJob = scope.launch {
      try {
        val preview = repository.cachedPreview(current, snapshot.options)
        mutable.update { it.copy(currentPreview = preview) }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (_: Exception) { /* Explicit preview remains available if the local cache is missing. */ }
    }
  }

  fun searchCountry(country: String) {
    if (mutable.value.busy) return
    searchCountryExplicit = true
    if (mutable.value.searchCountry == country) return
    search?.cancel()
    mutable.update { it.copy(searchCountry = country, candidates = emptyList(), preview = null,
      searched = false, searching = false, previewing = false, error = null) }
  }

  suspend fun cachedPreview(row: AppleMappingRow): AppleArtworkPreview? =
    row.app?.let { repository.cachedPreview(it, mutable.value.options) }

  suspend fun cachedPreview(app: AppleAppMatch): AppleArtworkPreview? =
    repository.cachedPreview(app, mutable.value.options)

  suspend fun exportMappings(): ByteArray = repository.exportMappings()

  suspend fun importMappings(input: java.io.InputStream): Int {
    val count = withContext(Dispatchers.IO) { repository.importMappings(AppleMappingTransfer.read(input)) }
    mutable.update { it.copy(pendingChanges = true, result = null) }
    load()
    return count
  }

  fun search(query: String) {
    val snapshot = mutable.value
    if (snapshot.busy || snapshot.editing == null || query.trim().length !in 1..100) return
    val country = AppleStorefrontPolicy.searchCountry(query, snapshot.searchCountry,
      snapshot.searchCountry.takeIf { searchCountryExplicit })
    search?.cancel()
    mutable.update { it.copy(searching = true, searched = false, candidates = emptyList(), error = null,
      preview = null, previewing = false, searchCountry = country) }
    search = scope.launch {
      try {
        val candidates = repository.search(query, snapshot.options.copy(country = country))
        mutable.update { it.copy(searching = false, searched = true, candidates = candidates) }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { mutable.update { it.copy(searching = false, error = failure.uiError()) }
      } finally { refreshCooldown() }
    }
  }

  fun mapping(app: AppleAppMatch?, reset: Boolean = false) {
    val snapshot = mutable.value
    val target = snapshot.editing ?: return
    if (snapshot.busy) return
    if (reset) repository.resetMatch(target, snapshot.options) else repository.setMatch(target, snapshot.options,
      app?.copy(country = app.country ?: snapshot.searchCountry))
    mutable.update { it.copy(rows = rows(it.rows.map { row -> row.target }, it.options), result = null, error = null,
      pendingChanges = true, preview = null) }
    refreshCapabilities()
  }

  fun preview(app: AppleAppMatch, refresh: Boolean = false) {
    if (mutable.value.busy) return
    search?.cancel()
    val options = mutable.value.options
    mutable.update { it.copy(previewing = true, preview = null, error = null) }
    search = scope.launch {
      try {
        val preview = repository.preview(app, options, refresh)
        val bytes = repository.cacheBytes()
        mutable.update { it.copy(preview = preview, previewing = false, cacheBytes = bytes,
          currentPreview = if (refresh && it.currentPreview?.app?.id == app.id) preview else it.currentPreview,
          pendingChanges = it.pendingChanges || refresh,
          previewRevision = it.previewRevision + 1) }
        refreshCapabilities()
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { mutable.update { it.copy(previewing = false, error = failure.uiError()) }
      } finally { refreshCooldown() }
    }
  }

  fun clearDownloads() {
    if (mutable.value.busy || mutable.value.searching || mutable.value.previewing) return
    mutable.update { it.copy(busy = true) }
    work = scope.launch {
      try { repository.clearDownloads(); mutable.update { it.copy(cacheBytes = 0,
          rows = it.rows.map { row -> row.copy(darkCapability = AppleDarkCapability.UNKNOWN) },
          previewRevision = it.previewRevision + 1) }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { mutable.update { it.copy(error = failure.uiError()) }
      } finally {
        mutable.update { it.copy(busy = false) }
        if (reloadRequested) load() else refreshCapabilities()
      }
    }
  }

  fun generate(refresh: Boolean = false, onReady: ((IconPackDescriptor) -> Unit)? = null) {
    if (mutable.value.busy || mutable.value.loading) return
    search?.cancel()
    mutable.update { it.copy(busy = true, progress = null, result = null, error = null, searching = false,
      previewing = false, preview = null) }
    work = scope.launch {
      try {
        val snapshot = mutable.value
        val targets = repository.targets()
        mutable.update { it.copy(rows = rows(targets, snapshot.options)) }
        if (targets.none { repository.match(it, snapshot.options) != null }) {
          mutable.update { it.copy(error = ApplePackError.EMPTY) }
          return@launch
        }
        val result = repository.generate(targets, snapshot.options, retryIncomplete = refresh) { progress ->
          mutable.update { it.copy(progress = progress) }
        }
        val bytes = repository.cacheBytes()
        mutable.update { it.copy(pack = result.pack, result = result,
          publishedOptions = snapshot.options, cacheBytes = bytes, pendingChanges = result.failed > 0,
          previewRevision = it.previewRevision + 1) }
        onGenerated()
        onReady?.invoke(result.pack)
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) {
        mutable.update { it.copy(error = failure.uiError()) }
        android.util.Log.w("KeiHomeAppleIcons", "Apple icon generation failed", failure)
      } finally {
        mutable.update { it.copy(busy = false, progress = null, retryAtMillis = repository.retryAtMillis()) }
        if (reloadRequested) load() else refreshCapabilities()
      }
    }
  }

  fun cancel() { work?.cancel(); search?.cancel(); mutable.update { it.copy(searching = false, previewing = false) } }
  fun refreshCooldown() { mutable.update { it.copy(retryAtMillis = repository.retryAtMillis()) } }
  fun closeSearch() {
    search?.cancel(); currentPreviewJob?.cancel()
    mutable.update { it.copy(searching = false, previewing = false, preview = null, currentPreview = null) }
  }

  private fun rows(targets: List<AppleAppTarget>, options: AppleIconOptions) =
    targets.map { AppleMappingRow(it, repository.match(it, options), repository.custom(it, options)) }

  private fun refreshCapabilities() {
    capabilitiesJob?.cancel()
    val snapshot = mutable.value
    capabilitiesJob = scope.launch {
      val resolved = resolvedRows(snapshot.rows, snapshot.options)
      mutable.update { if (it.options == snapshot.options && it.rows == snapshot.rows) it.copy(rows = resolved) else it }
    }
  }

  private suspend fun resolvedRows(rows: List<AppleMappingRow>, options: AppleIconOptions): List<AppleMappingRow> =
    withContext(Dispatchers.IO) {
      // Preview reads can validate image files. Probe at most 128 distinct local pairs per snapshot.
      val known = mutableMapOf<AppleAppMatch, AppleDarkCapability>()
      rows.map { row ->
        ensureActive()
        val app = row.app
        val capability = if (app == null) AppleDarkCapability.UNKNOWN else known[app] ?: if (known.size >= 128) {
          AppleDarkCapability.UNKNOWN
        } else {
          val preview = repository.cachedPreview(app, options)
          val value = when {
            preview == null -> AppleDarkCapability.UNKNOWN
            preview.dark == null -> if (preview.limited) AppleDarkCapability.MISSING else AppleDarkCapability.SHARED
            else -> AppleDarkCapability.INDEPENDENT
          }
          known[app] = value
          value
        }
        row.copy(darkCapability = capability)
      }
    }

  private fun Exception.uiError(): ApplePackError = when (this) {
    is ApplePackSizeException -> ApplePackError.SIZE
    is AppleRequestException -> if (status == 429 || retryAtMillis > System.currentTimeMillis())
      ApplePackError.LIMITED else ApplePackError.NETWORK
    is IOException -> ApplePackError.NETWORK
    else -> ApplePackError.GENERATION
  }
}
