package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import os.kei.hyper.home.data.AppLocalStorage
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
import os.kei.hyper.home.iconpack.perfect.PerfectIconRepository
import os.kei.hyper.home.iconpack.perfect.PerfectIconResult

internal enum class PerfectPackError { LOCAL, CATALOG, NETWORK, COOLDOWN, INSTALLED, GENERATE, IMPORT, EMPTY, CACHE }

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
  val loading: Boolean = false,
  val refreshing: Boolean = false,
  val generating: Boolean = false,
  val clearingCache: Boolean = false,
  val previewLoading: Boolean = false,
  val clearedBytes: Long? = null,
  val progress: PerfectIconProgress? = null,
  val cacheBytes: Long = 0,
  val retryAtMillis: Long = 0,
  val error: PerfectPackError? = null,
  val result: PerfectIconResult? = null,
  val generatedOptions: PerfectIconOptions? = null,
  val pack: IconPackCardData? = null,
) {
  val busy: Boolean get() = loading || refreshing || generating || clearingCache || previewLoading
}

/** Owns page work in the Home ViewModel, so navigation does not restart network requests. */
internal class PerfectPackController(
  context: Context,
  private val scope: CoroutineScope,
  private val repository: PerfectIconRepository,
  private val packs: IconPackRepository,
  private val onGenerated: () -> Unit,
) {
  private val app = context.applicationContext
  private val storage by lazy { AppLocalStorage.open(app, "perfect_icons_ui_v1") }
  private val mutableState = MutableStateFlow(PerfectPackState())
  val state = mutableState.asStateFlow()
  private val mutablePreview = MutableStateFlow(PerfectPackPreviewState())
  val previewState = mutablePreview.asStateFlow()

  fun loadPreview(refresh: Boolean = false) {
    val current = state.value
    if (current.busy) return
    val catalog = current.catalog ?: run {
      mutablePreview.value = PerfectPackPreviewState(options = current.options, failed = true)
      return
    }
    val previous = previewState.value
    if (!refresh && previous.options == current.options && previous.sourceHash == catalog.sourceHash &&
      (previous.pack != null || previous.failed)) return
    mutablePreview.value = PerfectPackPreviewState(current.options, catalog.sourceHash, loading = true)
    mutableState.update { it.copy(previewLoading = true) }
    scope.launch {
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
        mutablePreview.update { it.copy(pack = descriptor) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutablePreview.update { it.copy(failed = true) }
      } finally {
        mutablePreview.update { it.copy(loading = false) }
        updateCache()
        mutableState.update { it.copy(previewLoading = false) }
      }
    }
  }

  fun load() {
    if (state.value.busy) return
    mutableState.update { it.copy(loading = true, error = null) }
    scope.launch {
      var failure = PerfectPackError.LOCAL
      try {
        withContext(Dispatchers.IO) {
          val options = repository.options()
          val catalog = repository.catalog(refresh = false)
          mutableState.update { it.copy(options = options, catalog = catalog) }
          val saved = storage.getString("last_generated", null)?.let(::JSONObject)
          if (saved != null) {
            val result = saved.result()
            val generatedOptions = PerfectIconOptions(
              style = saved.optString("style"),
              installedOnly = saved.optBoolean("installedOnly", true),
              dynamic = saved.optBoolean("dynamic", true),
            )
            mutableState.update { it.copy(result = result, generatedOptions = generatedOptions) }
            val id = saved.optString("recordId")
            val pack = packs.listImported().packs.firstOrNull { it.id == id } ?: if (result.file.isFile) {
              failure = PerfectPackError.IMPORT
              importGenerated(result.file, generatedOptions).also {
                remember(result, generatedOptions, it.id)
                withContext(Dispatchers.Main.immediate) { onGenerated() }
              }
            } else null
            val targets = iconPackTargets(app) { coroutineContext.ensureActive() }
            mutableState.update { it.copy(
              pack = pack?.let { descriptor -> iconPackCardData(descriptor, targets) },
              error = if (pack == null) PerfectPackError.LOCAL else it.error,
            ) }
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(error = failure) }
      } finally {
        updateCache()
        mutableState.update { it.copy(loading = false) }
      }
    }
  }

  suspend fun cacheBytes(): Long = repository.cacheBytes()

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

  fun setOptions(options: PerfectIconOptions) {
    if (state.value.busy) return
    mutableState.update { it.copy(loading = true, error = null) }
    scope.launch {
      try {
        withContext(Dispatchers.IO) { repository.setOptions(options) }
        mutableState.update { it.copy(options = options) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(error = PerfectPackError.LOCAL) }
      } finally {
        mutableState.update { it.copy(loading = false) }
      }
    }
  }

  fun refreshCatalog(forceDownload: Boolean = false) {
    if (!canStart()) return
    mutableState.update { it.copy(refreshing = true, clearedBytes = null, error = null) }
    scope.launch {
      try {
        val catalog = withContext(Dispatchers.IO) {
          repository.catalog(refresh = true, forceDownload = forceDownload) { progress ->
            mutableState.update { it.copy(progress = progress) }
          }
        }
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
        updateCache()
        mutableState.update { it.copy(refreshing = false, progress = null) }
      }
    }
  }

  fun generate() {
    // Generating from the complete local snapshot never needs the network.
    if (state.value.busy) return
    if (state.value.catalog == null) {
      mutableState.update { it.copy(error = PerfectPackError.CATALOG) }
      return
    }
    val options = state.value.options
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
          mutableState.update { it.copy(result = result, generatedOptions = options, pack = null) }
          failure = PerfectPackError.IMPORT
          val pack = importGenerated(result.file, options)
          remember(result, options, pack.id)
          withContext(Dispatchers.Main.immediate) { onGenerated() }
          val targets = iconPackTargets(app) { coroutineContext.ensureActive() }
          mutableState.update { it.copy(pack = iconPackCardData(pack, targets)) }
          val catalog = repository.catalog(refresh = false)
          mutableState.update { it.copy(catalog = catalog ?: it.catalog) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: IOException) {
        android.util.Log.w("KeiHomePerfectIcons", "Local icon pack generation failed", error)
        mutableState.update { it.copy(error = failure) }
      } catch (_: Exception) {
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

  private suspend fun updateCache() {
    withContext(Dispatchers.IO) {
      try {
        val bytes = repository.cacheBytes()
        val retry = repository.retryAtMillis()
        mutableState.update { it.copy(cacheBytes = bytes, retryAtMillis = retry) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(error = it.error ?: PerfectPackError.LOCAL) }
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

  private suspend fun importGenerated(file: File, options: PerfectIconOptions): IconPackDescriptor =
    when (val imported = packs.importPerfectPublication(file,
      app.getString(os.kei.hyper.home.R.string.perfect_icons_title) + " · " +
        (options.style.ifEmpty { app.getString(os.kei.hyper.home.R.string.perfect_icons_style_default) })
          .take(96) + ".zip")) {
      is IconPackImportResult.Success -> imported.pack
      is IconPackImportResult.Failure -> throw IOException("Generated pack import failed")
    }

  private fun remember(result: PerfectIconResult, options: PerfectIconOptions, recordId: String) {
    val saved = JSONObject().put("recordId", recordId).put("file", result.file.absolutePath)
      .put("mapped", result.mapped).put("staticCount", result.staticCount)
      .put("dynamicCount", result.dynamicCount).put("downloaded", result.downloaded)
      .put("reused", result.reused).put("revision", result.revision)
      .put("rejectedDynamic", result.rejectedDynamic).put("contract", result.contract)
      .put("style", options.style).put("installedOnly", options.installedOnly).put("dynamic", options.dynamic)
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
  )
}
