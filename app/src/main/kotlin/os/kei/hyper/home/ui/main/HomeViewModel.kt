package os.kei.hyper.home.ui.main

import android.app.Application
import android.net.Uri
import android.os.Process
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.data.IconPreviewRepository
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.data.InstalledIconScanner
import os.kei.hyper.home.data.MonetAction
import os.kei.hyper.home.data.MonetActionStatus
import os.kei.hyper.home.data.MonetCustomization
import os.kei.hyper.home.data.ColorOsStyle
import os.kei.hyper.home.data.MonetPaletteReader
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.ThemeEffectsPreferences
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.readFrameworkSnapshot
import os.kei.hyper.home.status.upgradeOlderSystemTarget
import os.kei.hyper.home.status.RuntimeStatusProtocol
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState
import os.kei.hyper.home.iconpack.IconPackRepository
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.IconPackImportResult
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.status.NativeIconImportReport

internal data class FrameworkUiState(
  val connected: Boolean = false,
  val snapshot: FrameworkSnapshot? = null,
  val readFailed: Boolean = false,
)

internal fun FrameworkUiState.themeEffectsInScope(): Boolean =
  connected && !readFailed && snapshot?.let { it.systemInScope || it.systemUiInScope } == true

internal data class MonetUiState(
  val snapshot: MonetPaletteSnapshot? = null,
  val readFailed: Boolean = false,
  val action: MonetActionStatus = MonetActionStatus.IDLE,
)

internal data class ThemeEffectsUiState(
  val savedChoice: Boolean? = null,
  val loading: Boolean = false,
  val saving: Boolean = false,
  val readFailed: Boolean = false,
  val saveFailed: Boolean = false,
  val unavailable: Boolean = false,
)

// An unknown choice may only be recovered by an explicit write of off.
internal fun ThemeEffectsUiState.canSaveThemeEffects(enabled: Boolean): Boolean =
  !saving && !loading && !unavailable &&
    ((savedChoice != null && !readFailed) || (!enabled && (readFailed || saveFailed)))

internal data class AppsUiState(
  val capabilities: List<InstalledIconCapability>? = null,
  val loading: Boolean = false,
  val error: String? = null,
  val previewGeneration: Int = 0,
)

internal data class PageRefreshResult(val completedAt: Long, val failed: Boolean)

internal class HomeViewModel(application: Application) : AndroidViewModel(application) {
  val preferences = AppPreferences.get(application)
  val browserOptions: StateFlow<IconBrowserOptions>
    field = MutableStateFlow(preferences.readBrowserOptions())

  fun updateBrowserOptions(value: IconBrowserOptions) {
    browserOptions.value = value
    preferences.saveBrowserOptions(value)
  }

  fun setRememberMenuSelections(value: Boolean) {
    preferences.setRememberMenuSelections(value)
    if (value) preferences.saveBrowserOptions(browserOptions.value)
  }
  val browserSource = MutableStateFlow<IconBrowserSource?>(null)
  val iconPackUpdateNeeded: StateFlow<Boolean>
    field = MutableStateFlow(false)
  private val packageInventoryRevision = MutableStateFlow(0)
  val previews = IconPreviewRepository(application)
  val packPreviews = IconPackPreviewRepository(application)
  private val packRepository = IconPackRepository(application)
  val iconPacks: StateFlow<IconPacksUiState>
    field = MutableStateFlow(IconPacksUiState())
  val framework: StateFlow<FrameworkUiState>
    field = MutableStateFlow(FrameworkUiState())
  val runtime: StateFlow<RuntimeStatusUiState>
    field = MutableStateFlow(RuntimeStatusUiState())
  val monet: StateFlow<MonetUiState>
    field = MutableStateFlow(MonetUiState())
  val themeEffects: StateFlow<ThemeEffectsUiState>
    field = MutableStateFlow(ThemeEffectsUiState())
  val apps: StateFlow<AppsUiState>
    field = MutableStateFlow(AppsUiState())
  val filter: StateFlow<Int>
    field = MutableStateFlow(0)
  val refreshing: StateFlow<Set<Int>>
    field = MutableStateFlow(emptySet())
  val refreshResults: StateFlow<Map<Int, PageRefreshResult>>
    field = MutableStateFlow(emptyMap())

  private val paletteReader = MonetPaletteReader(application)
  private val paletteMutex = Mutex()
  private val runtimeMutex = Mutex()
  private val iconPacksMutex = Mutex()
  private val themeEffectsMutex = Mutex()
  private var iconPacksDirty = true
  private var runtimeRead: Deferred<RuntimeStatusReport?>? = null
  private val launcherApps = application.getSystemService(LauncherApps::class.java)
  private var visiblePage: Int? = null
  private var pageObservation = 0L
  private var locale = ""
  private var scannedLocale: String? = null
  private var appsDirty = true
  private var scanJob: Job? = null
  private var applyJob: Job? = null
  private var packageChangeJob: Job? = null
  private val packageObserver = os.kei.hyper.home.data.PackageChangeObserver(
    application, Handler(Looper.getMainLooper()),
  ) { packagesChanged() }
  private val packageCallback = object : LauncherApps.Callback() {
    override fun onPackageAdded(packageName: String, user: UserHandle) = packagesChanged()
    override fun onPackageRemoved(packageName: String, user: UserHandle) = packagesChanged()
    // Real resource/component changes are observed through PackageManager broadcasts.
    override fun onPackageChanged(packageName: String, user: UserHandle) = Unit
    override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = packagesChanged()
    override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = packagesChanged()
  }

  init {
    viewModelScope.launch {
      browserSource.filterNotNull().first()
      val active = runtime.value.report?.iconTheme?.localId ?: return@launch
      val managed = withContext(Dispatchers.IO) { active in IconPackImportProtocol.sources(application) }
      if (managed && !iconPacks.value.scanned) readIconPacks()
    }
    viewModelScope.launch {
      combine(browserSource,
        iconPacks.map { it.packs to it.nativeSources }.distinctUntilChanged(),
        preferences.state.map { it.systemIconFallback }.distinctUntilChanged(),
        packageInventoryRevision) { _, _, _, _ -> Unit }.collectLatest {
        val needed = try { withContext(Dispatchers.IO) {
          val source = browserSource.value ?: return@withContext false
          val pack = currentFallbackSource() ?: return@withContext false
          val targets = launcherIconTargets()
          val system = application.packageManager.getInstalledApplications(
            android.content.pm.PackageManager.ApplicationInfoFlags.of(0)).filter {
            it.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
              android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
          }.mapTo(hashSetOf()) { it.packageName }
          val fallback = preferences.state.value.systemIconFallback
          val caller = currentCoroutineContext()
          val exporter = os.kei.hyper.home.iconpack.IconPackExporter(application,
            os.kei.hyper.home.iconpack.IconPackLimits())
          iconPackNeedsUpdate(pack, source, targets, fallback, system) {
            exporter.canComplete(pack, it, fallback) { caller.ensureActive() }
          }
        } } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
          android.util.Log.w("KeiHomePackUpdate", "Icon update inventory unavailable", failure)
          false
        }
        iconPackUpdateNeeded.value = needed
      }
    }
    viewModelScope.launch {
      val restored = withContext(Dispatchers.IO) {
        IconPackImportProtocol.pendingSource(application) to IconPackImportProtocol.lastResult(application)
      }
      iconPacks.update { it.copy(nativeImporting = restored.first, nativeResult = restored.second) }
    }
    viewModelScope.launch {
      IconPackImportProtocol.reports.collect { result ->
        if (result != null && result.status != "IMPORTING") {
          if (result.imported) preferences.recordColorOsComponent(result.sourceId, result.localId!!)
          val associations = withContext(Dispatchers.IO) { IconPackImportProtocol.associations(application) }
          val sources = withContext(Dispatchers.IO) { IconPackImportProtocol.sources(application) }
          val pending = withContext(Dispatchers.IO) { IconPackImportProtocol.pendingSource(application) }
          iconPacks.update { it.copy(nativeAssociations = associations, nativeSources = sources,
            nativeResult = result, nativeImporting = pending) }
        }
      }
    }
    launcherApps?.registerCallback(packageCallback, Handler(Looper.getMainLooper()))
    viewModelScope.launch {
      FrameworkConnection.service.collectLatest { service ->
        framework.value = FrameworkUiState(connected = service != null)
        runtime.value = RuntimeStatusUiState()
        themeEffects.value = ThemeEffectsUiState()
        if (service != null) coroutineScope {
          if (visiblePage != null && visiblePage != 4) launch { readRuntimeStatus(true) }
          readFramework()
          readThemeEffects()
          delay(1_500)
          withContext(Dispatchers.IO) {
            try { service.upgradeOlderSystemTarget() }
            catch (failure: Exception) { android.util.Log.w("KeiHomeReload", "Runtime upgrade unavailable", failure) }
          }
        }
      }
    }
  }

  /** Called only while the activity is STARTED; switching pages cancels its old polling jobs. */
  suspend fun observePage(page: Int, localeTags: String): Unit = coroutineScope {
    val observation = ++pageObservation
    visiblePage = page
    locale = localeTags
    if (page in 0..1 && (appsDirty || scannedLocale != locale)) scanApps()
    if (page in 2..3 && iconPacksDirty) launch { readIconPacks() }
    try {
      if (page in 0..3) launch {
        while (true) {
          readFramework()
          if (page == 3) readThemeEffects()
          delay(5_000)
        }
      }
      if (page in 0..3) launch {
        while (true) { readRuntimeStatus(true); delay(10_000) }
      }
      if (page in 0..2) launch {
        while (true) { readPalette(); delay(5_000) }
      }
      awaitCancellation()
    } finally {
      if (pageObservation == observation) visiblePage = null
    }
  }

  fun setFilter(value: Int) { filter.value = value }

  fun refresh(page: Int) {
    if (page in refreshing.value) return
    refreshing.update { it + page }
    viewModelScope.launch {
      try {
        if (page in 0..3) readFramework()
        if (page in 0..3) readRuntimeStatus(true, force = true)
        if (page == 3) {
          readThemeEffects()
          packPreviews.clear()
          packRepository.invalidateInstalled()
          readIconPacks()
        }
        val wallpaperRefreshed = if (page == 2) refreshWallpaper() else true
        readPalette()
        if (page == 0 || page == 1) {
          appsDirty = true
          clearPreviewCache()
          scanApps()
          while (appsDirty || scanJob?.isActive == true) {
            if (scanJob?.isActive != true) scanApps()
            scanJob?.join()
          }
        }
        val failed = !wallpaperRefreshed || monet.value.readFailed ||
          ((page == 0 || page == 3) && (runtime.value.readFailed || runtime.value.timedOut)) ||
          ((page == 0 || page == 2 || page == 3) && framework.value.readFailed) ||
          (page == 3 && (iconPacks.value.error != null || iconPacks.value.failedSources > 0)) ||
          (page == 3 && (themeEffects.value.readFailed || themeEffects.value.saveFailed)) ||
          ((page == 0 || page == 1) && apps.value.error != null)
        refreshResults.update { it + (page to PageRefreshResult(System.currentTimeMillis(), failed)) }
      } finally { refreshing.update { it - page } }
    }
  }

  fun clearPreviewCache() {
    previews.clear()
    apps.update { it.copy(previewGeneration = it.previewGeneration + 1) }
  }

  private suspend fun refreshWallpaper(): Boolean {
    val service = FrameworkConnection.service.value ?: return false
    return try {
      val request = withContext(Dispatchers.IO) { MonetAction.requestPaletteRefresh(service) }
      kotlinx.coroutines.withTimeoutOrNull(4_000) {
        var completed = false
        while (!completed) {
          completed = withContext(Dispatchers.IO) {
            android.provider.Settings.Secure.getString(getApplication<Application>().contentResolver,
              MonetAction.PALETTE_REFRESHED)?.toLongOrNull() == request.revision
          }
          if (!completed) delay(250)
        }
        true
      } == true
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) { false }
  }

  private fun packagesChanged() {
    appsDirty = true
    iconPacksDirty = true
    packageChangeJob?.cancel()
    packageChangeJob = viewModelScope.launch {
      delay(350)
      clearPreviewCache()
      packPreviews.clear()
      packRepository.invalidateInstalled()
      if (visiblePage in 0..1) scanApps()
      if (visiblePage == 3 || iconPacks.value.scanned) readIconPacks()
      packageInventoryRevision.value++
    }
  }

  fun importIconPack(uri: Uri) {
    if (iconPacks.value.importing) return
    iconPacks.update { it.copy(importing = true, error = null, importName = null) }
    viewModelScope.launch {
      try {
        when (val result = packRepository.import(uri)) {
          is IconPackImportResult.Success -> {
            readIconPacks()
            iconPacks.update { it.copy(importName = result.pack.displayName) }
          }
          is IconPackImportResult.Failure -> iconPacks.update { it.copy(error = result.message) }
        }
      } finally { iconPacks.update { it.copy(importing = false) } }
    }
  }

  fun importNativeIconPack(original: IconPackDescriptor, includePalette: Boolean = true,
    appearanceOverride: IconPackAppearance? = null) {
    if (iconPacks.value.nativeImporting != null) return
    val service = FrameworkConnection.service.value ?: return
    val selected = preferences.state.value
    val appearance = appearanceOverride ?: selected.iconPackAppearance
    val pack = original.copy(id = selected.systemIconFallback.sourceId(original.id))
    if (pack.builtinKey != null) preferences.prepareColorOsComponent(pack.id, includePalette)
    // Retain the previous receipt while replacing it so detail rows keep their height.
    iconPacks.update { it.copy(nativeImporting = pack.id, preparing = true) }
    viewModelScope.launch {
      var handoff: os.kei.hyper.home.status.IconPackHandoff? = null
      try {
        handoff = IconPackHandoffs.prepare(getApplication(), packRepository, packPreviews, pack,
          launcherIconTargets(), appearance, includePalette, selected.systemIconFallback)
        iconPacks.update { it.copy(preparing = false) }
        val result = IconPackImportProtocol.submit(getApplication(), service, handoff, selected.autoApplyIconPack)
          ?: NativeIconImportReport(handoff.token, Process.myUid() / 100_000, pack.id, "UNCERTAIN")
        if (result.imported) preferences.recordColorOsComponent(result.sourceId, result.localId!!)
        iconPacks.update { it.copy(nativeResult = result) }
        readIconPacks()
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) {
        iconPacks.update { it.copy(nativeResult = NativeIconImportReport(handoff?.token.orEmpty(),
          Process.myUid() / 100_000, pack.id, "FAILED", message = failure.message)) }
      } finally {
        val pending = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
          IconPackImportProtocol.pendingSource(getApplication())
        }
        if (pending != pack.id) handoff?.file?.delete()
        iconPacks.update { it.copy(nativeImporting = pending, preparing = false) }
      }
    }
  }

  fun setSystemIconFallback(value: os.kei.hyper.home.data.SystemIconFallback) {
    preferences.setSystemIconFallback(value)
  }

  fun currentFallbackSource(): IconPackDescriptor? {
    val active = runtime.value.report?.iconTheme?.localId ?: return null
    return currentIconPackSource(active, runtime.value.report?.iconTheme?.title, iconPacks.value,
      preferences.state.value.colorOsComponents, ::colorOsDisplayName)
  }

  fun updateCurrentSystemFallback() {
    val pack = currentFallbackSource() ?: return
    val active = runtime.value.report?.iconTheme?.localId
    val colorOs = pack.id.startsWith("coloros:")
    val appearance = if (colorOs) runCatching {
      IconPackAppearance.valueOf(pack.id.split(':', limit = 4)[2])
    }.getOrNull() else null
    importNativeIconPack(pack, includePalette = !colorOs || preferences.state.value.colorOsComponents[active] == true,
      appearanceOverride = appearance)
  }

  fun recheckNativeIconImport() {
    val service = FrameworkConnection.service.value ?: return
    viewModelScope.launch { IconPackImportProtocol.recheck(service) }
  }

  private suspend fun launcherIconTargets(): List<IconPackComponent> = withContext(Dispatchers.IO) {
    val caller = currentCoroutineContext()
    os.kei.hyper.home.data.iconPackTargets(getApplication()) { caller.ensureActive() }
  }

  private suspend fun readIconPacks() = iconPacksMutex.withLock {
    iconPacksDirty = false
    iconPacks.update { it.copy(loading = true, error = null) }
    try {
      val targets = launcherIconTargets()
      val scans = coroutineScope {
        val installed = async { packRepository.listInstalled() }
        val builtins = async { packRepository.listBuiltins() }
        val imported = async { packRepository.listImported() }
        val builtinResult = builtins.await()
        val builtinIds = builtinResult.packs.map { it.id }.toSet()
        listOf(builtinResult, installed.await(),
          imported.await().let { it.copy(packs = it.packs.filterNot { pack -> pack.id in builtinIds }) })
      }
      val cards = withContext(Dispatchers.Default) {
        scans.flatMap { it.packs }.map { iconPackCardData(it, targets) }
      }
      val associations = withContext(Dispatchers.IO) { IconPackImportProtocol.associations(getApplication()) }
      val sources = withContext(Dispatchers.IO) { IconPackImportProtocol.sources(getApplication()) }
      iconPacks.update { it.copy(packs = cards, scanned = true,
        nativeAssociations = associations, nativeSources = sources,
        failedSources = scans.sumOf { scan -> scan.failures.size }) }
    } catch (cancelled: CancellationException) { iconPacksDirty = true; throw cancelled
    } catch (failure: Exception) { iconPacks.update { it.copy(error = failure.message.orEmpty()) }
    } finally { iconPacks.update { it.copy(loading = false) } }
  }

  private fun scanApps() {
    if (scanJob?.isActive == true) return
    appsDirty = false
    val requestedLocale = locale
    apps.update { it.copy(loading = true, error = null) }
    scanJob = viewModelScope.launch {
      try {
        val result = withContext(Dispatchers.Default) {
          val caller = kotlinx.coroutines.currentCoroutineContext()
          InstalledIconScanner(getApplication()).scan { caller.ensureActive() }
        }
        scannedLocale = requestedLocale
        apps.update { it.copy(capabilities = result, loading = false) }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (failure: Exception) {
        apps.update { it.copy(loading = false, error = failure.message.orEmpty()) }
      } finally {
        scanJob = null
        if ((appsDirty || requestedLocale != locale) && visiblePage in 0..1) scanApps()
      }
    }
  }

  private suspend fun readFramework() {
    val service = FrameworkConnection.service.value ?: return
    try {
      val result = withContext(Dispatchers.IO) { service.readFrameworkSnapshot() }
      if (FrameworkConnection.service.value === service) framework.value = FrameworkUiState(true, result)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      if (FrameworkConnection.service.value === service) framework.update { it.copy(readFailed = true) }
    }
  }

  private suspend fun readThemeEffects(): Unit {
    themeEffectsMutex.withLock {
      val service = FrameworkConnection.service.value ?: return
      if (!framework.value.themeEffectsInScope() || themeEffects.value.saving || themeEffects.value.saveFailed) return
      themeEffects.update { it.copy(loading = it.savedChoice == null) }
      try {
        val saved = ThemeEffectsPreferences.read(service)
        // A read that was already running must not clear a queued save's busy state.
        if (FrameworkConnection.service.value === service && !themeEffects.value.saving) {
          themeEffects.value = ThemeEffectsUiState(savedChoice = saved)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: UnsupportedOperationException) {
        if (FrameworkConnection.service.value === service && !themeEffects.value.saving) {
          themeEffects.value = ThemeEffectsUiState(unavailable = true)
        }
      } catch (_: Exception) {
        if (FrameworkConnection.service.value === service && !themeEffects.value.saving) {
          themeEffects.value = ThemeEffectsUiState(readFailed = true)
        }
      } finally {
        if (FrameworkConnection.service.value === service) themeEffects.update { it.copy(loading = false) }
      }
    }
  }

  fun setPreserveSystemMaterials(enabled: Boolean) {
    val service = FrameworkConnection.service.value ?: return
    val state = themeEffects.value
    if (!framework.value.themeEffectsInScope() || !state.canSaveThemeEffects(enabled)) return
    themeEffects.update { it.copy(saving = true) }
    viewModelScope.launch {
      themeEffectsMutex.withLock {
        try {
          if (FrameworkConnection.service.value !== service || !framework.value.themeEffectsInScope()) return@withLock
          ThemeEffectsPreferences.save(service, enabled)
          if (FrameworkConnection.service.value === service) {
            themeEffects.value = ThemeEffectsUiState(savedChoice = enabled)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: UnsupportedOperationException) {
          if (FrameworkConnection.service.value === service) {
            themeEffects.update { it.copy(unavailable = true) }
          }
        } catch (_: Exception) {
          if (FrameworkConnection.service.value === service) {
            themeEffects.update { it.copy(saveFailed = true) }
          }
        } finally {
          if (FrameworkConnection.service.value === service) themeEffects.update { it.copy(saving = false) }
        }
      }
    }
  }

  private suspend fun readRuntimeStatus(includeIconTheme: Boolean = true, force: Boolean = false) {
    val requestedAt = SystemClock.elapsedRealtime()
    runtimeMutex.withLock {
      val service = FrameworkConnection.service.value ?: return
      // Rapid pager changes reuse a recent receipt; manual refresh still requests a new one.
      val previous = runtime.value.report
      if (!force && !runtime.value.readFailed && !runtime.value.timedOut && previous != null &&
        requestedAt - previous.checkedAtElapsed in 0..3_000 && (!includeIconTheme || previous.iconTheme != null)) return
      // Share a probe that completed while a manual refresh waited for the periodic one.
      if ((runtime.value.report?.checkedAtElapsed ?: -1L) >= requestedAt &&
        (!includeIconTheme || runtime.value.report?.iconTheme != null)) return
      // Binder calls cannot always be interrupted. Keep at most one outstanding I/O task,
      // while allowing the page/refresh to finish at its deadline and cancel its observation.
      if (runtimeRead?.isCompleted == false) {
        val fresh = runtime.value.report?.takeIf { it.isFresh(requestedAt) }
        runtime.value = RuntimeStatusUiState(report = fresh, checking = true)
        if (fresh == null) browserSource.value = null
        return
      }
      runtime.update {
        RuntimeStatusUiState(report = it.report?.takeIf { report ->
          report.isFresh(SystemClock.elapsedRealtime())
        }, checking = true)
      }
      val operation = viewModelScope.async(Dispatchers.IO) { RuntimeStatusProtocol.query(service, includeIconTheme) }
      runtimeRead = operation
      try {
        val report = kotlinx.coroutines.withTimeoutOrNull(6_000L) { operation.await() }
        if (FrameworkConnection.service.value === service) {
          runtime.value = RuntimeStatusUiState(report = report, timedOut = report == null)
          val theme = report?.iconTheme
          if (theme?.previewKey == null) browserSource.value = null
          else if (browserSource.value?.revision != theme.previewKey) {
            browserSource.value = null
            try { browserSource.value = readBrowserSource(getApplication(), theme) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { android.util.Log.w("KeiHomeBrowser", "Active icon resources unavailable", failure) }
          }
        }
      } catch (cancellation: CancellationException) {
        if (FrameworkConnection.service.value === service) runtime.update { it.copy(checking = false) }
        throw cancellation
      } catch (_: Exception) {
        if (FrameworkConnection.service.value === service) {
          runtime.value = RuntimeStatusUiState(readFailed = true)
          browserSource.value = null
        }
      } finally {
        operation.cancel()
      }
    }
  }

  private suspend fun readPalette() = paletteMutex.withLock {
    try {
      val result = withContext(Dispatchers.IO) { paletteReader.read() }
      monet.update { it.copy(snapshot = result, readFailed = false) }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) { monet.update { it.copy(readFailed = true) } }
  }

  fun applyMonet(choice: MonetCustomization) {
    preferences.setMonetInspiration(choice.style == os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION)
    submitMonet(choice)
  }

  fun setMonetEnabled(enabled: Boolean) {
    if (enabled && ordinaryColorOsActive()) return
    val choice = monet.value.snapshot?.customization ?: MonetCustomization()
    val active = runtime.value.report?.iconTheme?.localId
    val inspiration = preferences.state.value.monetInspiration ?:
      (active != null && preferences.state.value.colorOsComponents[active] == true)
    submitMonet(choice.copy(style = if (inspiration) os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION
      else choice.style.takeUnless { it == os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION }
        ?: os.kei.hyper.home.data.MonetSchemeStyle.TONAL_SPOT), enabled)
  }

  fun ordinaryColorOsActive(): Boolean {
    val active = runtime.value.report?.iconTheme?.localId ?: return false
    return preferences.state.value.colorOsComponents[active] == false
  }

  fun applyColorOs(inspiration: Boolean = false) {
    val selected = preferences.state.value
    val style = if (inspiration) ColorOsStyle.INSPIRATION else selected.colorOsStyle
      .takeUnless { it.usesMonet } ?: ColorOsStyle.CLASSIC
    preferences.setColorOsStyle(style)
    if (style.usesMonet) preferences.setMonetInspiration(true)
    val key = "coloros-${style.artworkKey}-art-${if (style.usesMonet || selected.colorOsArtPlus) "on" else "off"}"
    val pack = iconPacks.value.packs.firstOrNull { it.pack.builtinKey == key }?.pack ?: return
    // An ordinary ColorOS component has no palette template, even if another source enables Monet.
    if (!style.usesMonet) submitMonet(monet.value.snapshot?.customization ?: MonetCustomization(), false)
    val title = colorOsDisplayName(style, selected.colorOsArtPlus)
    importNativeIconPack(pack.copy(id = style.importSourceId(pack.id, selected.iconPackAppearance),
      displayName = title), includePalette = style.usesMonet)
  }

  private fun colorOsDisplayName(style: ColorOsStyle, art: Boolean): String =
    if (style.usesMonet) getApplication<Application>().getString(os.kei.hyper.home.R.string.pack_style_inspiration)
    else "ColorOS · ${getApplication<Application>().getString(colorOsStyleLabel(style))} · ART${if (art) "+" else "-"}"

  private fun submitMonet(choice: MonetCustomization, enabled: Boolean = true) {
    if (enabled && ordinaryColorOsActive()) return
    val service = FrameworkConnection.service.value ?: return
    applyJob?.cancel()
    monet.update { it.copy(action = MonetActionStatus.APPLYING) }
    applyJob = viewModelScope.launch {
      try {
        val request = withContext(Dispatchers.IO) {
          MonetAction.submit(service, choice.copy(iconAppearance = preferences.state.value.iconPackAppearance), enabled)
        }
        // Continue waiting across navigation/configuration changes, including a late native ACK.
        val started = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - started < MonetAction.REQUEST_LIFETIME_MS &&
          FrameworkConnection.service.value === service) {
          if (withContext(Dispatchers.IO) { MonetAction.isApplied(getApplication<Application>().contentResolver, request) }) {
            monet.update { it.copy(action = MonetActionStatus.APPLIED) }
            readPalette()
            return@launch
          }
          if (android.os.SystemClock.elapsedRealtime() - started >= 3_000) {
            monet.update { it.copy(action = MonetActionStatus.UNCONFIRMED) }
          }
          delay(500)
        }
        monet.update { it.copy(action = MonetActionStatus.UNCONFIRMED) }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: UnsupportedOperationException) {
        monet.update { it.copy(action = MonetActionStatus.UNAVAILABLE) }
      } catch (_: Exception) {
        monet.update { it.copy(action = MonetActionStatus.FAILED) }
      }
    }
  }

  override fun onCleared() {
    packageObserver.close()
    launcherApps?.unregisterCallback(packageCallback)
    previews.clear()
  }
}
