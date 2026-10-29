package os.kei.hyper.home.ui.main

import android.app.Application
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
  val framework: StateFlow<FrameworkUiState>
    field = MutableStateFlow(FrameworkUiState())
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
    launcherApps?.registerCallback(packageCallback, Handler(Looper.getMainLooper()))
    viewModelScope.launch {
      FrameworkConnection.service.collectLatest { service ->
        framework.value = FrameworkUiState(connected = service != null)
        if (service != null) {
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
    try {
      if (page == 0 || page == 2) launch {
        while (true) { readFramework(); delay(5_000) }
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
        if (page == 0 || page == 2) readFramework()
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
          ((page == 0 || page == 2) && framework.value.readFailed) ||
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
    clearPreviewCache()
    if (visiblePage == 1) scanApps()
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
