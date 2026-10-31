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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.IconPreviewRepository
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.data.InstalledIconScanner
import os.kei.hyper.home.data.MonetAction
import os.kei.hyper.home.data.MonetActionStatus
import os.kei.hyper.home.data.MonetCustomization
import os.kei.hyper.home.data.MonetPaletteReader
import os.kei.hyper.home.data.MonetPaletteSnapshot
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

internal data class MonetUiState(
  val snapshot: MonetPaletteSnapshot? = null,
  val readFailed: Boolean = false,
  val action: MonetActionStatus = MonetActionStatus.IDLE,
)

internal data class AppsUiState(
  val capabilities: List<InstalledIconCapability>? = null,
  val loading: Boolean = false,
  val error: String? = null,
  val previewGeneration: Int = 0,
)

internal data class PageRefreshResult(val completedAt: Long, val failed: Boolean)

internal class HomeViewModel(application: Application) : AndroidViewModel(application) {
  val preferences = AppPreferences.get(application)
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
  private var iconPacksDirty = true
  private var runtimeRead: Deferred<RuntimeStatusReport?>? = null
  private val launcherApps = application.getSystemService(LauncherApps::class.java)
  private var visiblePage: Int? = null
  private var locale = ""
  private var scannedLocale: String? = null
  private var appsDirty = true
  private var scanJob: Job? = null
  private var applyJob: Job? = null
  private val packageCallback = object : LauncherApps.Callback() {
    override fun onPackageAdded(packageName: String, user: UserHandle) = packagesChanged()
    override fun onPackageRemoved(packageName: String, user: UserHandle) = packagesChanged()
    override fun onPackageChanged(packageName: String, user: UserHandle) = packagesChanged()
    override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = packagesChanged()
    override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = packagesChanged()
  }

  init {
    viewModelScope.launch {
      val restored = withContext(Dispatchers.IO) {
        IconPackImportProtocol.pendingSource(application) to IconPackImportProtocol.lastResult(application)
      }
      iconPacks.update { it.copy(nativeImporting = restored.first, nativeResult = restored.second) }
    }
    viewModelScope.launch {
      IconPackImportProtocol.reports.collect { result ->
        if (result != null && result.status != "IMPORTING") {
          val associations = withContext(Dispatchers.IO) { IconPackImportProtocol.associations(application) }
          val pending = withContext(Dispatchers.IO) { IconPackImportProtocol.pendingSource(application) }
          iconPacks.update { it.copy(nativeAssociations = associations, nativeResult = result, nativeImporting = pending) }
        }
      }
    }
    launcherApps?.registerCallback(packageCallback, Handler(Looper.getMainLooper()))
    viewModelScope.launch {
      FrameworkConnection.service.collectLatest { service ->
        framework.value = FrameworkUiState(connected = service != null)
        runtime.value = RuntimeStatusUiState()
        if (service != null) coroutineScope {
          if (visiblePage == 0 || visiblePage == 3) launch { readRuntimeStatus(visiblePage == 3) }
          readFramework()
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
    visiblePage = page
    locale = localeTags
    if (page == 1 && (appsDirty || scannedLocale != locale)) scanApps()
    if (page == 3 && iconPacksDirty) launch { readIconPacks() }
    try {
      if (page == 0 || page == 2 || page == 3) launch {
        while (true) { readFramework(); delay(5_000) }
      }
      if (page == 0 || page == 3) launch {
        while (true) { readRuntimeStatus(page == 3); delay(10_000) }
      }
      if (page in 0..2) launch {
        while (true) { readPalette(); delay(5_000) }
      }
      awaitCancellation()
    } finally { visiblePage = null }
  }

  fun setFilter(value: Int) { filter.value = value }

  fun refresh(page: Int) {
    if (page in refreshing.value) return
    refreshing.update { it + page }
    viewModelScope.launch {
      try {
        if (page == 0 || page == 2 || page == 3) readFramework()
        if (page == 0 || page == 3) readRuntimeStatus(page == 3)
        if (page == 3) { packPreviews.clear(); packRepository.invalidateInstalled(); readIconPacks() }
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
    clearPreviewCache()
    if (visiblePage == 1) scanApps()
    if (visiblePage == 3) viewModelScope.launch { readIconPacks() }
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

  fun importNativeIconPack(pack: IconPackDescriptor) {
    if (iconPacks.value.nativeImporting != null) return
    val service = FrameworkConnection.service.value ?: return
    iconPacks.update { it.copy(nativeImporting = pack.id, nativeResult = null) }
    viewModelScope.launch {
      var handoff: os.kei.hyper.home.status.IconPackHandoff? = null
      try {
        handoff = IconPackHandoffs.prepare(getApplication(), packRepository, packPreviews, pack, launcherIconTargets())
        val result = IconPackImportProtocol.submit(getApplication(), service, handoff)
          ?: NativeIconImportReport(handoff.token, Process.myUid() / 100_000, pack.id, "UNCERTAIN")
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
        iconPacks.update { it.copy(nativeImporting = pending) }
      }
    }
  }

  fun recheckNativeIconImport() {
    val service = FrameworkConnection.service.value ?: return
    viewModelScope.launch { IconPackImportProtocol.recheck(service) }
  }

  private suspend fun launcherIconTargets(): List<IconPackComponent> = withContext(Dispatchers.IO) {
    launcherApps?.getActivityList(null, Process.myUserHandle()).orEmpty().map {
      IconPackComponent(it.componentName.packageName, it.componentName.className)
    }.distinct()
  }

  private suspend fun readIconPacks() = iconPacksMutex.withLock {
    iconPacksDirty = false
    iconPacks.update { it.copy(loading = true, error = null) }
    try {
      val targets = launcherIconTargets()
      val scans = coroutineScope {
        val installed = async { packRepository.listInstalled() }
        val imported = async { packRepository.listImported() }
        listOf(installed.await(), imported.await())
      }
      val cards = withContext(Dispatchers.Default) {
        scans.flatMap { it.packs }.map { iconPackCardData(it, targets) }
      }
      val associations = withContext(Dispatchers.IO) { IconPackImportProtocol.associations(getApplication()) }
      iconPacks.update { it.copy(packs = cards, scanned = true,
        nativeAssociations = associations, failedSources = scans.sumOf { scan -> scan.failures.size }) }
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
        val result = withContext(Dispatchers.Default) { InstalledIconScanner(getApplication()).scan() }
        scannedLocale = requestedLocale
        apps.update { it.copy(capabilities = result, loading = false) }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (failure: Exception) {
        apps.update { it.copy(loading = false, error = failure.message.orEmpty()) }
      } finally {
        scanJob = null
        if ((appsDirty || requestedLocale != locale) && visiblePage == 1) scanApps()
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
    } catch (_: Exception) { framework.update { it.copy(readFailed = true) } }
  }

  private suspend fun readRuntimeStatus(includeIconTheme: Boolean = false) {
    val requestedAt = SystemClock.elapsedRealtime()
    runtimeMutex.withLock {
      val service = FrameworkConnection.service.value ?: return
      // Share a probe that completed while a manual refresh waited for the periodic one.
      if ((runtime.value.report?.checkedAtElapsed ?: -1L) >= requestedAt &&
        (!includeIconTheme || runtime.value.report?.iconTheme != null)) return
      // Binder calls cannot always be interrupted. Keep at most one outstanding I/O task,
      // while allowing the page/refresh to finish at its deadline and cancel its observation.
      if (runtimeRead?.isCompleted == false) {
        runtime.value = RuntimeStatusUiState(timedOut = true)
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
        }
      } catch (cancellation: CancellationException) {
        if (FrameworkConnection.service.value === service) runtime.update { it.copy(checking = false) }
        throw cancellation
      } catch (_: Exception) {
        if (FrameworkConnection.service.value === service) runtime.value = RuntimeStatusUiState(readFailed = true)
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
    val service = FrameworkConnection.service.value ?: return
    if (applyJob?.isActive == true && monet.value.action == MonetActionStatus.APPLYING) return
    applyJob?.cancel()
    monet.update { it.copy(action = MonetActionStatus.APPLYING) }
    applyJob = viewModelScope.launch {
      try {
        val request = withContext(Dispatchers.IO) { MonetAction.submit(service, choice) }
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
    launcherApps?.unregisterCallback(packageCallback)
    previews.clear()
  }
}
