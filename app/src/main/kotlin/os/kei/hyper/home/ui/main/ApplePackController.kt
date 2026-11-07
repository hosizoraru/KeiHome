package os.kei.hyper.home.ui.main

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

internal enum class ApplePackError { NETWORK, LIMITED, EMPTY, GENERATION, SIZE }
internal data class AppleMappingRow(val target: AppleAppTarget, val app: AppleAppMatch?, val custom: Boolean)
internal data class ApplePackUiState(
  val options: AppleIconOptions,
  val loading: Boolean = false,
  val busy: Boolean = false,
  val rows: List<AppleMappingRow> = emptyList(),
  val pack: IconPackDescriptor? = null,
  val publishedOptions: AppleIconOptions? = null,
  val cacheBytes: Long = 0,
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

  fun load() {
    if (mutable.value.loading || mutable.value.busy) return
    mutable.update { it.copy(loading = true) }
    scope.launch {
      try {
        val targets = repository.targets()
        val pack = repository.descriptor()
        val published = repository.publishedOptions()
        val bytes = repository.cacheBytes()
        val pending = repository.needsGeneration(targets, mutable.value.options)
        mutable.update { value -> value.copy(loading = false, rows = rows(targets, value.options),
          pack = pack, publishedOptions = published, cacheBytes = bytes, pendingChanges = pending) }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (_: Exception) { mutable.update { it.copy(loading = false, error = ApplePackError.GENERATION) } }
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
  }

  fun edit(target: AppleAppTarget) {
    search?.cancel()
    mutable.update { it.copy(editing = target, candidates = emptyList(), searching = false, searched = false,
      error = null, preview = null, previewing = false) }
  }

  fun search(query: String) {
    val snapshot = mutable.value
    if (snapshot.busy || snapshot.editing == null || query.trim().length !in 1..100) return
    search?.cancel()
    mutable.update { it.copy(searching = true, searched = false, candidates = emptyList(), error = null,
      preview = null, previewing = false) }
    search = scope.launch {
      try {
        val candidates = repository.search(query, snapshot.options)
        mutable.update { it.copy(searching = false, searched = true, candidates = candidates) }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { mutable.update { it.copy(searching = false, error = failure.uiError()) } }
    }
  }

  fun mapping(app: AppleAppMatch?, reset: Boolean = false) {
    val snapshot = mutable.value
    val target = snapshot.editing ?: return
    if (snapshot.busy) return
    if (reset) repository.resetMatch(target, snapshot.options) else repository.setMatch(target, snapshot.options, app)
    mutable.update { it.copy(rows = rows(it.rows.map { row -> row.target }, it.options), result = null, error = null,
      pendingChanges = true, preview = null) }
  }

  fun preview(app: AppleAppMatch) {
    if (mutable.value.busy) return
    search?.cancel()
    val options = mutable.value.options
    mutable.update { it.copy(previewing = true, preview = null, error = null) }
    search = scope.launch {
      try {
        val preview = repository.preview(app, options)
        val bytes = repository.cacheBytes()
        mutable.update { it.copy(preview = preview, previewing = false, cacheBytes = bytes) }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { mutable.update { it.copy(previewing = false, error = failure.uiError()) } }
    }
  }

  fun clearDownloads() {
    if (mutable.value.busy || mutable.value.searching || mutable.value.previewing) return
    mutable.update { it.copy(busy = true) }
    work = scope.launch {
      try { repository.clearDownloads(); mutable.update { it.copy(cacheBytes = 0) }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { mutable.update { it.copy(error = failure.uiError()) }
      } finally { mutable.update { it.copy(busy = false) } }
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
        val result = repository.generate(targets, snapshot.options, refresh) { progress ->
          mutable.update { it.copy(progress = progress) }
        }
        val bytes = repository.cacheBytes()
        mutable.update { it.copy(pack = result.pack, result = result,
          publishedOptions = snapshot.options, cacheBytes = bytes, pendingChanges = result.failed > 0) }
        onGenerated()
        onReady?.invoke(result.pack)
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) {
        mutable.update { it.copy(error = failure.uiError()) }
        android.util.Log.w("KeiHomeAppleIcons", "Apple icon generation failed", failure)
      } finally { mutable.update { it.copy(busy = false, progress = null) } }
    }
  }

  fun cancel() { work?.cancel(); search?.cancel(); mutable.update { it.copy(searching = false, previewing = false) } }
  fun closeSearch() { search?.cancel(); mutable.update { it.copy(searching = false, previewing = false, preview = null) } }

  private fun rows(targets: List<AppleAppTarget>, options: AppleIconOptions) =
    targets.map { AppleMappingRow(it, repository.match(it, options), repository.custom(it, options)) }

  private fun Exception.uiError(): ApplePackError = when (this) {
    is ApplePackSizeException -> ApplePackError.SIZE
    is AppleRequestException -> if (status == 429) ApplePackError.LIMITED else ApplePackError.NETWORK
    is IOException -> ApplePackError.NETWORK
    else -> ApplePackError.GENERATION
  }
}
