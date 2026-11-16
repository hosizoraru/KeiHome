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
import kotlinx.coroutines.CoroutineStart
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
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.InitialActivationPreferences
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.data.IconPreviewRepository
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.data.InstalledIconScanner
import os.kei.hyper.home.data.PackageRefreshTarget
import os.kei.hyper.home.data.PendingPackageRefresh
import os.kei.hyper.home.data.preserveUnchangedReference
import os.kei.hyper.home.data.MonetAction
import os.kei.hyper.home.data.MonetActionStatus
import os.kei.hyper.home.data.MonetCustomization
import os.kei.hyper.home.data.ColorOsStyle
import os.kei.hyper.home.data.MonetPaletteReader
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.ThemeEffectsPreferences
import os.kei.hyper.home.data.SplashAppearancePreferences
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.readFrameworkSnapshot
import os.kei.hyper.home.status.reloadMismatchedSystemTargets
import os.kei.hyper.home.status.RuntimeStatusProtocol
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState
import os.kei.hyper.home.iconpack.IconPackRepository
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.IconPackImportResult
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.status.NativeImportReceiptRetries
import os.kei.hyper.home.status.pendingReceiptOperation
import os.kei.hyper.home.status.NativeIconImportReport
import os.kei.hyper.home.status.LibxposedStaticScopeClient
import os.kei.hyper.home.status.StaticScopeRequests

internal data class FrameworkUiState(
  val connected: Boolean = false,
  val snapshot: FrameworkSnapshot? = null,
  val readFailed: Boolean = false,
  val checking: Boolean = false,
)

internal fun FrameworkUiState.themeEffectsInScope(): Boolean =
  connected && !readFailed && snapshot?.let { it.systemInScope || it.systemUiInScope } == true

internal fun FrameworkUiState.splashAppearanceInScope(): Boolean =
  connected && !readFailed && snapshot?.systemUiInScope == true

internal fun FrameworkUiState.dockGlassInScope(): Boolean =
  launcherScopeGranted()

internal fun FrameworkUiState.launcherGlassInScope(): Boolean =
  launcherScopeGranted()

internal fun FrameworkUiState.launcherLabelsInScope(): Boolean =
  launcherScopeGranted()

private fun FrameworkUiState.launcherScopeGranted(): Boolean =
  connected && !readFailed && snapshot?.let {
    it.systemInScope && "com.miui.home" in it.grantedScope
  } == true

internal data class MonetUiState(
  val snapshot: MonetPaletteSnapshot? = null,
  val readFailed: Boolean = false,
  val action: MonetActionStatus = MonetActionStatus.IDLE,
  val submittedChoice: MonetCustomization? = null,
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
  val initialActivation = InitialActivationPreferences(application)
  val browserOptions: StateFlow<IconBrowserOptions>
    field = MutableStateFlow(preferences.readBrowserOptions())
  val packSort: StateFlow<Pair<Int, Boolean>>
    field = MutableStateFlow(preferences.readPackSort())

  fun updatePackSort(order: Int, reverse: Boolean) {
    packSort.value = order.coerceIn(0, 3) to reverse
    preferences.savePackSort(order, reverse)
  }

  fun updateBrowserOptions(value: IconBrowserOptions) {
    browserOptions.value = value
    preferences.saveBrowserOptions(value)
  }

  fun setRememberMenuSelections(value: Boolean) {
    preferences.setRememberMenuSelections(value)
    if (value) {
      preferences.saveBrowserOptions(browserOptions.value)
      preferences.savePackSort(packSort.value.first, packSort.value.second)
    }
  }
  val browserSource = MutableStateFlow<IconBrowserSource?>(null)
  val iconPackUpdateNeeded: StateFlow<Boolean>
    field = MutableStateFlow(false)
  private val packageInventoryRevision = MutableStateFlow(0)
  internal val importedPackRevision: StateFlow<Int>
    field = MutableStateFlow(0)
  val previews = IconPreviewRepository(application)
  private val scanner = InstalledIconScanner(application)
  private var previewCacheRevision = 0L
  val packPreviews = IconPackPreviewRepository(application)
  val customIcons by lazy {
    CustomIconsController(application, viewModelScope, packRepository, packPreviews,
      onCustomSaved = { pkg -> setPreservedIconPackages(preferences.state.value.preservedIconPackages - pkg) },
      onOriginalPreserved = { pkg -> setPreservedIconPackages(preferences.state.value.preservedIconPackages + pkg) })
  }
  val currentPreviews = CurrentIconPreviewRepository(application,
    { pack, entry -> packPreviews.loadApplied(pack, entry) })
  private val packRepository = IconPackRepository(application)
  private val appleRepository = os.kei.hyper.home.iconpack.apple.AppleIconRepository(application)
  val applePacks: ApplePackController = ApplePackController(viewModelScope, appleRepository,
    keepLatestGeneratedPacks = { preferences.state.value.keepLatestGeneratedPacks }) {
    iconPacksDirty = true
    viewModelScope.launch { readIconPacks() }
  }
  internal val perfectPacks = PerfectPackController(application, viewModelScope,
    os.kei.hyper.home.iconpack.perfect.PerfectIconRepository(application), packRepository,
    keepLatestGeneratedPacks = { preferences.state.value.keepLatestGeneratedPacks }) {
    importedPackRevision.value++
    iconPacksDirty = true
    viewModelScope.launch { readIconPacks() }
  }
  internal val iconMasks = IconMaskController(application, viewModelScope) {
    clearPreviewCache()
    perfectPacks.invalidateMaskPreview()
  }
  val iconPacks: StateFlow<IconPacksUiState>
    field = MutableStateFlow(IconPacksUiState())
  val framework: StateFlow<FrameworkUiState>
    field = MutableStateFlow(FrameworkUiState(checking = true))
  val scopeRequests = StaticScopeRequests(viewModelScope, onReconciled = { readFramework() })
  private var scopeClient: LibxposedStaticScopeClient? = null
  val runtime: StateFlow<RuntimeStatusUiState>
    field = MutableStateFlow(RuntimeStatusUiState(checking = true))
  val homeScreenRuntime: StateFlow<RuntimeStatusUiState>
    field = MutableStateFlow(RuntimeStatusUiState(checking = true))
  val monet: StateFlow<MonetUiState>
    field = MutableStateFlow(MonetUiState())
  val themeEffects: StateFlow<ThemeEffectsUiState>
    field = MutableStateFlow(ThemeEffectsUiState())
  val launcherLabels = LauncherLabelSettings(viewModelScope) { framework.value.launcherLabelsInScope() }

  val dockGlass = DockGlassSettings(viewModelScope) { framework.value.dockGlassInScope() }
  fun readDockGlassSettings() { viewModelScope.launch { dockGlass.read() } }
  val launcherGlass = LauncherGlassSettings(viewModelScope) { framework.value.launcherGlassInScope() }
  fun readLauncherGlassSettings() { viewModelScope.launch { launcherGlass.read() } }

  fun readLauncherLabelSettings() { viewModelScope.launch { launcherLabels.read() } }
  suspend fun refreshHomeScreenStatus() { readRuntimeStatus(includeIconTheme = false, force = true) }
  val splashAppearance: StateFlow<ThemeEffectsUiState>
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
  private val splashAppearanceMutex = Mutex()
  private var iconPacksDirty = true
  private val nativeIconAdaptations = os.kei.hyper.home.iconpack.NativeIconAdaptations(application)
  private var runtimeRead: Deferred<RuntimeStatusReport?>? = null
  private var browserSourceRead: Deferred<IconBrowserSource?>? = null
  private var browserBindingRevision = 0L
  private var browserSourceRequest = 0L
  private val launcherApps = application.getSystemService(LauncherApps::class.java)
  private var visiblePage: Int? = null
  private var pageObservation = 0L
  private var locale = ""
  private var scannedLocale: String? = null
  private var appsDirty = true
  private val pendingAppRefresh = PendingPackageRefresh().apply { add() }
  private var pendingAppFullPreviewRevision: Long? = null
  private var observedAppTheme: String? = null
  private var scanJob: Job? = null
  private var applyJob: Job? = null
  private var packageChangeJob: Job? = null
  private val pendingPackageRefresh = PendingPackageRefresh()
  private val packageObserver = os.kei.hyper.home.data.PackageChangeObserver(
    application, Handler(Looper.getMainLooper()),
    onPackageChanged = { name, user -> packageChanged(name, user) },
  ) {}
  private val overlayObserver = os.kei.hyper.home.data.IconOverlayObserver(
    application, Handler(Looper.getMainLooper()),
  ) { target -> packagesChanged(if (target == "android") null else PackageRefreshTarget(target)) }
  private val packageCallback = object : LauncherApps.Callback() {
    override fun onPackageAdded(packageName: String, user: UserHandle) = packageChanged(packageName, user)
    override fun onPackageRemoved(packageName: String, user: UserHandle) = packageChanged(packageName, user)
    // Real resource/component changes are observed through PackageManager broadcasts.
    override fun onPackageChanged(packageName: String, user: UserHandle) = Unit
    override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) {
      // Availability can change a whole profile without changing the profile serial set.
      if (!replacing) packagesChanged(null)
    }
    override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) {
      if (!replacing) packagesChanged(null)
    }
  }

  private var nativeImportGeneration = 0L
  private var iconPackPreparationJob: Job? = null
  private data class NativeReceiptVisibility(val observers: Int = 0, val generation: Long = 0)
  private val nativeReceiptVisibility = MutableStateFlow(NativeReceiptVisibility())
  private val nativeReceiptRetries = NativeImportReceiptRetries(SystemClock::elapsedRealtime)

  private suspend fun readImportState(): os.kei.hyper.home.status.IconImportLocalState? = try {
    withContext(Dispatchers.IO) { IconPackImportProtocol.state(getApplication()) }
  } catch (cancelled: CancellationException) { throw cancelled
  } catch (failure: Exception) {
    android.util.Log.e("KeiHomePackImport", "Local import receipt unavailable", failure)
    iconPacks.update { it.copy(error = failure.message.orEmpty()) }
    null
  }

  init {
    viewModelScope.launch {
      browserSource.filterNotNull().first()
      val active = runtime.value.report?.iconTheme?.localId ?: return@launch
      val managed = active in (readImportState() ?: return@launch).allSources
      if (managed && !iconPacks.value.scanned) readIconPacks()
    }
    viewModelScope.launch {
      preferences.state.map { Triple(it.completeSystemIconConfig, it.recommendedIconMappings, it.systemAppNameMatching) }.distinctUntilChanged().collect {
        clearPreviewCache()
        perfectPacks.invalidateMaskPreview()
        if (iconPacks.value.scanned) readIconPacks()
      }
    }
    viewModelScope.launch {
      combine(browserSource,
        iconPacks.map { Triple(it.packs, it.nativeSources, it.nativeImporting) }.distinctUntilChanged(),
        preferences.state.map { it.systemIconFallback }.distinctUntilChanged(),
        preferences.state.map { Triple(it.systemAppNameMatching, it.colorOsForOtherPacks,
          it.installedIconsOnly to it.completeSystemIconConfig) }.distinctUntilChanged(),
        packageInventoryRevision) { _, _, _, _, _ -> Unit }
        .combine(perfectPacks.state.map { it.catalog }.distinctUntilChanged()) { _, _ -> Unit }
        .combine(preferences.state.map { it.preservedIconPackages to it.recommendedIconMappings }.distinctUntilChanged()) { _, _ -> Unit }
        .combine(iconMasks.state.map { it.custom?.digest }.distinctUntilChanged()) { _, _ -> Unit }
        .combine(os.kei.hyper.home.iconpack.CustomIconStore.revision) { _, _ -> Unit }.collectLatest {
        val needed = try { withContext(Dispatchers.IO) {
          val source = browserSource.value ?: return@withContext false
          if (iconPacks.value.nativeImporting != null) return@withContext false
          val selected = preferences.state.value
          val mappings = selected.recommendedIconMappings
          val pack = os.kei.hyper.home.iconpack.BuiltinColorOsMappings.selected(
            currentFallbackSource() ?: return@withContext false, mappings.colorOsThemeStores)
          val targets = launcherIconTargets()
          val system = application.packageManager.getInstalledApplications(
            android.content.pm.PackageManager.ApplicationInfoFlags.of(0)).filter {
            it.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
              android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
          }.mapTo(hashSetOf()) { it.packageName }
          val fallback = preferences.state.value.systemIconFallback
          val perfectSource = if (fallback.isPerfect)
            os.kei.hyper.home.iconpack.perfect.PerfectIconRepository(application).catalog()?.sourceHash else null
          val nameMatching = selected.systemAppNameMatching &&
            pack.format != IconPackFormat.MIUI_MTZ && pack.format != IconPackFormat.MIUI_ICONS
          val caller = currentCoroutineContext()
          val colorOsExtra = selected.colorOsForOtherPacks
          val colorOsFallback = if (colorOsExtra && pack.colorOsProfile == null) packRepository.runtimeFallback() else null
          val exporter = os.kei.hyper.home.iconpack.IconPackExporter(application,
            os.kei.hyper.home.iconpack.IconPackLimits())
          val nameMatcher = os.kei.hyper.home.iconpack.SystemAppNameMatcher(application,
            pack.entries, nameMatching,
            os.kei.hyper.home.iconpack.BuiltinColorOsMappings.excludedNameMatches(pack, mappings.colorOsThemeStores))
          val fingerprints = nativeIconAdaptations.read(source.generation?.nativeAdaptations?.keys.orEmpty()) {
            caller.ensureActive()
          }
          val custom = os.kei.hyper.home.iconpack.CustomIconSnapshot(
            os.kei.hyper.home.iconpack.CustomIconStore.selections(application), emptyMap())
          iconPackNeedsUpdate(pack, source, targets, fallback, system, nameMatching, colorOsExtra, fingerprints,
            installedOnly = selected.installedIconsOnly, perfectSource = perfectSource,
            completeSystemIconConfig = selected.completeSystemIconConfig,
            iconMaskIdentity = os.kei.hyper.home.iconpack.IconMaskPolicy.snapshot(application).identity,
            preservedPackages = selected.preservedIconPackages, recommendedMappings = mappings,
            customizedPackages = custom.packages, customIconIdentity = custom.identity) {
            exporter.canComplete(pack, it, fallback, nameMatching, nameMatcher, colorOsFallback, mappings) { caller.ensureActive() }
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
      if (readImportState() == null) return@launch
      IconPackImportProtocol.states(application).filterNotNull().collect { stored ->
        stored.result?.takeIf { it.imported }?.let { result ->
          val id = requireNotNull(result.localId)
          val palette = stored.componentPalette[id]
          preferences.recordColorOsComponent(result.sourceId, id, confirmedPalette = palette, onlyIfMissing = palette == null)
        }
        iconPacks.update { it.copy(nativeAssociations = stored.associations, nativeSources = stored.allSources,
          nativeResult = stored.presentationResult(Process.myUid() / 100_000),
          nativeImporting = stored.pendingSource ?: it.nativeImporting.takeIf { _ -> it.preparing }) }
      }
    }
    launcherApps?.registerCallback(packageCallback, Handler(Looper.getMainLooper()))
    viewModelScope.launch {
      combine(IconPackImportProtocol.states(application).map { it?.pendingReceiptOperation(Process.myUid() / 100_000) },
        FrameworkConnection.service, nativeReceiptVisibility) { operation, service, visibility ->
        if (visibility.observers > 0 && service != null && operation != null) Triple(operation, service, visibility) else null
      }.distinctUntilChanged().collectLatest { observation ->
        val (operation, service, visibility) = observation ?: return@collectLatest
        nativeReceiptRetries.observe(operation) {
          IconPackImportProtocol.recheck(getApplication(), service, operation) {
            nativeReceiptVisibility.value == visibility && FrameworkConnection.service.value === service
          }
        }
      }
    }
    viewModelScope.launch {
      // Do not wait for collectLatest to join a slow, non-interruptible framework read.
      FrameworkConnection.service.collect {
        browserBindingRevision++
        clearBrowserSource()
      }
    }
    viewModelScope.launch {
      os.kei.hyper.home.status.frameworkBindings(FrameworkConnection.service).collectLatest { binding ->
        val service = binding.service
        scopeClient = service?.let { LibxposedStaticScopeClient(application, it) }
        scopeRequests.connect(scopeClient, checking = binding.waiting || service != null)
        framework.value = FrameworkUiState(connected = service != null, checking = binding.waiting || service != null)
        runtime.value = RuntimeStatusUiState(checking = service != null)
        homeScreenRuntime.value = RuntimeStatusUiState(checking = service != null)
        themeEffects.value = ThemeEffectsUiState()
        launcherLabels.reset()
        dockGlass.reset()
        launcherGlass.reset()
        splashAppearance.value = ThemeEffectsUiState()
        if (service == null && !binding.waiting) initialActivation.observe(null)
        if (service != null) coroutineScope {
          if (visiblePage != null && visiblePage != 4)
            launch { readRuntimeStatus(includeIconTheme = visiblePage != 5) }
          readFramework()
          readThemeEffects()
          launcherLabels.read()
          dockGlass.read()
          launcherGlass.read()
          readSplashAppearance()
          delay(1_500)
          withContext(Dispatchers.IO) {
            try { service.reloadMismatchedSystemTargets() }
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
    launch { observeNativeImportReceipts() }
    locale = localeTags
    if (page in 0..1 && (appsDirty || scannedLocale != locale)) scanApps()
    if (page in 2..3 && iconPacksDirty) launch { readIconPacks() }
    try {
      if (page == 4) launch {
        readFramework()
        readSplashAppearance()
        while (true) { readThemeEffects(); delay(5_000) }
      }
      if (page == 5) launch { observeDesktopStatus(DesktopSection.OVERVIEW) }
      if (page in 0..3) launch {
        while (true) {
          readFramework()
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

  /** Keep the fallback controls current only while they are visible. */
  suspend fun observeFallbackResources(pageObserved: Boolean = false): Unit = coroutineScope {
    if (!pageObserved) launch { observeNativeImportReceipts() }
    if (!pageObserved) launch { if (iconPacksDirty) readIconPacks() }
    while (true) {
      if (appsDirty) scanApps()
      if (!pageObserved) readRuntimeStatus(true)
      delay(10_000)
    }
  }

  private suspend fun observeNativeImportReceipts(): Nothing {
    nativeReceiptVisibility.update { it.copy(observers = it.observers + 1, generation = it.generation + 1) }
    try { awaitCancellation() }
    finally { nativeReceiptVisibility.update { it.copy(observers = it.observers - 1, generation = it.generation + 1) } }
  }

  suspend fun observeScopeStatus() {
    while (true) {
      readFramework()
      delay(5_000)
    }
  }

  /** Receipts omit the icon inventory; only the visible category's settings are polled. */
  suspend fun observeDesktopStatus(section: DesktopSection? = null): Unit = coroutineScope {
    launch {
      while (true) {
        readFramework()
        readDesktopSettings(section)
        delay(5_000)
      }
    }
    if (section != DesktopSection.ICONS) {
      while (true) {
        refreshHomeScreenStatus()
        delay(5_000)
      }
    } else awaitCancellation()
  }

  private suspend fun readDesktopSettings(section: DesktopSection?) {
    if (section == null || section == DesktopSection.APPEARANCE) {
      launcherLabels.read()
    }
    if (section == null || section == DesktopSection.APPEARANCE || section == DesktopSection.DOCK) launcherGlass.read()
    if (section == null || section == DesktopSection.DOCK) dockGlass.read()
    if (section == DesktopSection.ICONS) {
      readThemeEffects()
      readSplashAppearance()
    }
  }

  suspend fun observeAboutIcon(): Unit = coroutineScope {
    launch { while (true) { readRuntimeStatus(true); delay(10_000) } }
    launch { while (true) { readPalette(); delay(5_000) } }
  }

  fun setFilter(value: Int) { filter.value = value }

  fun refresh(page: Int, desktopSection: DesktopSection = DesktopSection.OVERVIEW) {
    if (page in refreshing.value) return
    refreshing.update { it + page }
    viewModelScope.launch {
      try {
        readFramework()
        if (page == 4) { readSplashAppearance(); readThemeEffects() }
        if (page == 5) {
          readDesktopSettings(desktopSection)
          if (desktopSection != DesktopSection.ICONS) refreshHomeScreenStatus()
          val failed = framework.value.readFailed || when (desktopSection) {
            DesktopSection.ICONS -> themeEffects.value.readFailed || splashAppearance.value.readFailed
            else -> homeScreenRuntime.value.readFailed || homeScreenRuntime.value.timedOut ||
              when (desktopSection) {
                DesktopSection.DOCK -> dockGlass.state.value.failed || launcherGlass.state.value.failed
                DesktopSection.APPEARANCE -> launcherLabels.state.value.failed || launcherGlass.state.value.failed
                else -> false
              }
          }
          refreshResults.update { it + (page to PageRefreshResult(System.currentTimeMillis(), failed)) }
          return@launch
        }
        if (page in 0..3) readRuntimeStatus(true, force = true)
        if (page == 3) {
          readThemeEffects()
          packPreviews.clear()
          currentPreviews.clear()
          packRepository.invalidateInstalled()
          readIconPacks()
        }
        val wallpaperRefreshed = if (page == 2) refreshWallpaper() else true
        readPalette()
        if (page == 0 || page == 1) {
          pendingAppRefresh.add()
          scanner.invalidateAll()
          appsDirty = true
          clearPreviewCache()
          pendingAppFullPreviewRevision = previewCacheRevision
          scanApps()
          while ((appsDirty || scanJob?.isActive == true) && apps.value.error == null) {
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
    previewCacheRevision++
    previews.clear()
    packPreviews.clear()
    currentPreviews.clear()
    apps.update { it.copy(previewGeneration = it.previewGeneration + 1) }
  }

  internal suspend fun appleDownloadCacheBytes(): Long = appleRepository.cacheBytes()
  internal suspend fun perfectDownloadCacheBytes(): Long = perfectPacks.cacheBytes()
  internal suspend fun perfectStorageUsage() = perfectPacks.storageUsage()

  internal fun previewCacheBytes(): Long =
    previews.sizeBytes().toLong() + packPreviews.sizeBytes() + currentPreviews.sizeBytes()

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

  private fun packageChanged(packageName: String, user: UserHandle) {
    val serial = getApplication<Application>().getSystemService(android.os.UserManager::class.java)
      ?.getSerialNumberForUser(user)?.takeIf { it >= 0 }
    packagesChanged(PackageRefreshTarget(packageName, serial))
  }

  private fun packagesChanged(target: PackageRefreshTarget?) {
    pendingPackageRefresh.add(target)
    if (packageChangeJob?.isActive == true) return
    packageChangeJob = viewModelScope.launch {
      while (pendingPackageRefresh.isPending) {
        val revision = pendingPackageRefresh.revision
        delay(350)
        if (revision != pendingPackageRefresh.revision) continue
        val batch = pendingPackageRefresh.drain()
        pendingAppRefresh.restore(batch)
        appsDirty = true
        iconPacksDirty = true
        val installedPackChanged = batch.global || iconPacks.value.packs.any { card ->
          val installed = card.pack.source as? os.kei.hyper.home.iconpack.IconPackSource.Installed
          installed != null && batch.targets.any { it.packageName == installed.packageName }
        }
        if (batch.global) {
          scanner.invalidateAll()
          clearPreviewCache()
          pendingAppFullPreviewRevision = previewCacheRevision
        } else {
          scanner.invalidate(batch.targets)
          previews.invalidatePackages(batch.targets)
          currentPreviews.invalidatePackages(batch.targets)
          if (installedPackChanged) packPreviews.clear()
          apps.update { it.copy(previewGeneration = it.previewGeneration + 1) }
        }
        if (installedPackChanged) packRepository.invalidateInstalled()
        if (visiblePage in 0..1) scanApps()
        packageInventoryRevision.value++
        // A previously visited catalogue is not a reason to rescan hidden families.
        if (visiblePage in 2..3) readIconPacks()
      }
    }
  }

  fun importIconPack(uri: Uri) {
    if (iconPacks.value.importing || iconPacks.value.removing != null) return
    iconPacks.update { it.copy(importing = true, error = null, importName = null) }
    viewModelScope.launch {
      try {
        when (val result = packRepository.import(uri)) {
          is IconPackImportResult.Success -> {
            importedPackRevision.value++
            readIconPacks()
            iconPacks.update { it.copy(importName = result.pack.displayName) }
          }
          is IconPackImportResult.Failure -> iconPacks.update { it.copy(error = result.message) }
        }
      } finally { iconPacks.update { it.copy(importing = false) } }
    }
  }

  fun removeImportedIconPack(pack: IconPackDescriptor) {
    val state = iconPacks.value
    if (pack.source !is os.kei.hyper.home.iconpack.IconPackSource.Imported ||
      state.removing != null || state.importing || state.nativeImporting != null) return
    iconPacks.update { it.copy(removing = pack.id, error = null) }
    viewModelScope.launch {
      try {
        iconPacksMutex.withLock {
          packRepository.removeImported(pack)
          importedPackRevision.value++
          packPreviews.clear()
          // Keep native associations: the system's imported/applied copy still exists.
          iconPacks.update { it.copy(packs = it.packs.filterNot { card -> card.pack.id == pack.id },
            importName = null) }
        }
      } catch (cancelled: CancellationException) {
        iconPacksDirty = true
        throw cancelled
      } catch (failure: Exception) {
        readIconPacks()
        iconPacks.update { it.copy(error = failure.message.orEmpty()) }
      } finally {
        iconPacks.update { it.copy(removing = null) }
      }
    }
  }

  fun importNativeIconPack(original: IconPackDescriptor, includePalette: Boolean = true,
    appearanceOverride: IconPackAppearance? = null) {
    if (iconPacks.value.removing != null) return
    if (original.source is os.kei.hyper.home.iconpack.IconPackSource.Generated) {
      if (iconPacks.value.nativeImporting != null) return
      applePacks.generate { fresh -> importPreparedIconPack(fresh, includePalette, appearanceOverride) }
      return
    }
    importPreparedIconPack(original, includePalette, appearanceOverride)
  }

  private fun importPreparedIconPack(original: IconPackDescriptor, includePalette: Boolean,
    appearanceOverride: IconPackAppearance?) {
    if (iconPacks.value.nativeImporting != null) return
    val service = FrameworkConnection.service.value ?: return
    val generation = ++nativeImportGeneration
    val selected = preferences.state.value
    val appearance = appearanceOverride ?: selected.iconPackAppearance
    val pack = original.copy(id = selected.systemIconFallback.sourceId(original.id))
    // Retain the previous receipt while replacing it so detail rows keep their height.
    iconPacks.update { it.copy(nativeImporting = pack.id, preparing = true, error = null,
      colorOsProgress = os.kei.hyper.home.iconpack.ColorOsGenerationProgress(
        os.kei.hyper.home.iconpack.ColorOsGenerationStage.APPLICATIONS)) }
    val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
      var handoff: os.kei.hyper.home.status.IconPackHandoff? = null
      try {
        if (pack.builtinKey != null) preferences.prepareColorOsComponent(pack.id, includePalette)
        val caller = currentCoroutineContext()
        val progress = os.kei.hyper.home.iconpack.ColorOsProgressReporter({ value ->
          caller.ensureActive()
          if (generation == nativeImportGeneration) {
            iconPacks.update { it.copy(colorOsProgress = value) }
          }
        })
        handoff = IconPackHandoffs.prepare(getApplication(), packRepository, packPreviews, pack,
          launcherIconTargets(), appearance, includePalette, selected.systemIconFallback,
          selected.systemAppNameMatching, selected.colorOsForOtherPacks, selected.installedIconsOnly,
          selected.completeSystemIconConfig, selected.recommendedIconMappings, onProgress = progress::update)
        caller.ensureActive()
        if (generation == nativeImportGeneration) iconPacks.update { it.copy(preparing = false, colorOsProgress = null) }
        val result = IconPackImportProtocol.submit(getApplication(), service, handoff, selected.autoApplyIconPack,
          preparedPalette = if (pack.builtinKey != null) includePalette else null)
          ?: NativeIconImportReport(handoff.token, Process.myUid() / 100_000, pack.id, "UNCERTAIN")
        if (!result.imported && !result.pending) android.util.Log.e("KeiHomePackImport",
          "Native icon import ${result.status}: ${result.message}")
        readIconPacks()
      } catch (cancelled: CancellationException) {
        if (generation == nativeImportGeneration) iconPacks.update {
          it.copy(error = getApplication<Application>().getString(R.string.coloros_generation_cancelled))
        }
        throw cancelled
      } catch (failure: Exception) {
        android.util.Log.e("KeiHomePackImport", "Icon pack preparation or import failed: ${pack.id}", failure)
        val stored = readImportState()
        if (generation != nativeImportGeneration) return@launch
        if (stored?.pendingSource == pack.id) iconPacks.update { it.copy(error = failure.message.orEmpty()) }
        else iconPacks.update { it.copy(nativeResult = NativeIconImportReport(handoff?.token.orEmpty(),
          Process.myUid() / 100_000, pack.id, "FAILED", message = failure.message)) }
      } finally {
        val pending = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
          // Unknown storage state must retain the handoff, never turn into a false failure/cleanup.
          val stored = readImportState()
          if (stored == null && handoff != null) pack.id else stored?.pendingSource
        }
        if (pending != pack.id) handoff?.file?.delete()
        if (generation == nativeImportGeneration) {
          // The IO snapshot is for handoff ownership only; use the current ordered state
          // for UI, since a newer receipt may have arrived while returning to Main.
          val current = IconPackImportProtocol.states(getApplication()).value
          iconPacks.update { it.copy(nativeImporting = if (current == null) pending else current.pendingSource,
            preparing = false, colorOsProgress = null) }
          iconPackPreparationJob = null
        }
      }
    }
    iconPackPreparationJob = job
    job.start()
  }

  fun cancelIconPackPreparation() {
    if (iconPacks.value.preparing && iconPacks.value.colorOsProgress != null) iconPackPreparationJob?.cancel()
  }

  fun setSystemIconFallback(value: os.kei.hyper.home.data.SystemIconFallback) {
    preferences.setSystemIconFallback(value)
  }

  fun setPerfectIconCompletion(style: String?) {
    preferences.setPerfectIconCompletion(style)
  }

  fun setPreservedIconPackages(packages: Set<String>) {
    preferences.setPreservedIconPackages(packages)
    packPreviews.clear()
  }

  fun currentFallbackSource(
    theme: os.kei.hyper.home.status.SystemIconThemeReport? = runtime.value.report?.iconTheme,
    catalog: IconPacksUiState = iconPacks.value,
    ownedColorOs: Map<String, Boolean> = preferences.state.value.colorOsComponents,
  ): IconPackDescriptor? {
    val active = theme?.localId ?: return null
    return currentIconPackSource(active, theme.title, catalog, ownedColorOs, ::colorOsDisplayName)
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
    val operation = IconPackImportProtocol.states(getApplication()).value
      ?.pendingReceiptOperation(Process.myUid() / 100_000) ?: return
    viewModelScope.launch {
      IconPackImportProtocol.recheck(getApplication(), service, operation) {
        FrameworkConnection.service.value === service
      }
    }
  }

  private suspend fun launcherIconTargets(): List<IconPackComponent> = traceIconInformation("targets") {
    withContext(Dispatchers.IO) {
      val caller = currentCoroutineContext()
      os.kei.hyper.home.data.iconPackTargets(getApplication()) { caller.ensureActive() }
    }
  }

  private suspend fun readIconPacks() = iconPacksMutex.withLock {
    iconPacksDirty = false
    applePacks.refreshTargetsIfLoaded()
    iconPacks.update { it.copy(loading = true, loadingSections = iconPackCatalogFamilies,
      failedSources = 0, error = null) }
    try {
      // Receipts are cheap and needed by cards as soon as the first family is published.
      readImportState()?.let { stored ->
        iconPacks.update { it.copy(nativeAssociations = stored.associations, nativeSources = stored.allSources) }
      }
      val catalog = ProgressiveIconPackCatalog(iconPacks.value.packs)
      val failures = mutableMapOf<String, Int>()
      val errors = mutableMapOf<String, String>()
      val loaders: Map<String, suspend () -> os.kei.hyper.home.iconpack.IconPackScanResult> = linkedMapOf(
        "builtin" to { packRepository.listBuiltins() },
        "installed" to { packRepository.listInstalled() },
        "imported" to { packRepository.listImported() },
        "apple" to { os.kei.hyper.home.iconpack.IconPackScanResult(
          listOfNotNull(appleRepository.descriptor()), emptyList()) },
      )
      coroutineScope {
        val targets = async { launcherIconTargets() }
        publishCatalogFamilies(loaders.mapValues { (family, load) -> suspend {
          val scan = traceIconInformation(family, load)
          val resolvedTargets = targets.await()
          val cards = withContext(Dispatchers.Default) {
            val caller = currentCoroutineContext()
            scan.packs.map { caller.ensureActive(); iconPackCardData(it, resolvedTargets) }
          }
          cards to scan.failures.size
        } }) { family, result ->
          val cards = result.getOrNull()?.let { (cards, count) ->
            failures[family] = count
            catalog.replace(family, cards)
          }
          result.exceptionOrNull()?.let { failure ->
            failures[family] = 1
            errors[family] = failure.message.orEmpty()
          }
          iconPacks.update { state -> state.copy(packs = cards ?: state.packs,
            loadingSections = state.loadingSections - family,
            failedSources = failures.values.sum(),
            error = if (errors.isEmpty()) state.error else
              iconPackCatalogFamilies.mapNotNull { errors[it] }.joinToString("\n")) }
        }
      }
      iconPacks.update { it.copy(scanned = true) }
    } catch (cancelled: CancellationException) { iconPacksDirty = true; throw cancelled
    } catch (failure: Exception) { iconPacks.update { it.copy(error = failure.message.orEmpty()) }
    } finally { iconPacks.update { it.copy(loading = false, loadingSections = emptySet()) } }
  }

  private fun scanApps() {
    if (scanJob?.isActive == true) return
    if (scannedLocale != locale || appsDirty && !pendingAppRefresh.isPending) pendingAppRefresh.add()
    if (!pendingAppRefresh.isPending) return
    val request = pendingAppRefresh.drain()
    val previewsAlreadyInvalidated = request.global && pendingAppFullPreviewRevision != null
    pendingAppFullPreviewRevision = null
    val previewRevisionAtStart = previewCacheRevision
    appsDirty = false
    val requestedLocale = locale
    val previousCapabilities = apps.value.capabilities
    apps.update { it.copy(loading = true, error = null) }
    scanJob = viewModelScope.launch {
      var continueQueuedWork = true
      try {
        val result = withContext(Dispatchers.Default) {
          val caller = kotlinx.coroutines.currentCoroutineContext()
          scanner.refresh(request) { caller.ensureActive() }.let {
            it.copy(capabilities = it.capabilities.preserveUnchangedReference(previousCapabilities))
          }
        }
        if (result.fullRefresh) {
          if (!previewsAlreadyInvalidated && previewCacheRevision == previewRevisionAtStart) clearPreviewCache()
          packRepository.invalidateInstalled()
          iconPacksDirty = true
          if (visiblePage in 2..3) viewModelScope.launch { readIconPacks() }
        }
        scannedLocale = requestedLocale
        apps.update { it.copy(capabilities = result.capabilities, loading = false) }
      } catch (cancellation: CancellationException) {
        pendingAppRefresh.restore(request)
        if (previewsAlreadyInvalidated && pendingAppFullPreviewRevision == null)
          pendingAppFullPreviewRevision = previewCacheRevision
        appsDirty = true
        continueQueuedWork = false
        apps.update { it.copy(loading = false) }
        throw cancellation
      } catch (failure: Exception) {
        continueQueuedWork = pendingAppRefresh.isPending
        pendingAppRefresh.add()
        appsDirty = true
        apps.update { it.copy(loading = false, error = failure.message.orEmpty()) }
      } finally {
        scanJob = null
        appsDirty = appsDirty || pendingAppRefresh.isPending
        if (continueQueuedWork && (appsDirty || requestedLocale != locale) && visiblePage in 0..1) scanApps()
      }
    }
  }

  private suspend fun readFramework() {
    val service = FrameworkConnection.service.value ?: return
    val client = scopeClient?.takeIf { it.identity === service } ?: return
    val scopeRead = scopeRequests.beginRead(service) ?: return
    try {
      val (result, scopePlan) = withContext(Dispatchers.IO) {
        val snapshot = service.readFrameworkSnapshot()
        snapshot to client.plan(snapshot.grantedScope, snapshot.frameworkProperties)
      }
      if (FrameworkConnection.service.value === service && scopeClient === client) {
        val accepted = scopeRequests.observePlan(service, scopeRead, scopePlan)
        if (accepted) {
          framework.value = FrameworkUiState(true, result)
          initialActivation.observe(result)
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      if (FrameworkConnection.service.value === service && scopeClient === client) {
        if (scopeRequests.readFailed(service, scopeRead)) {
          framework.update { it.copy(readFailed = true, checking = false) }
          initialActivation.observe(null)
        }
      }
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

  private suspend fun readSplashAppearance() = splashAppearanceMutex.withLock {
    val service = FrameworkConnection.service.value ?: return@withLock
    if (!framework.value.splashAppearanceInScope() || splashAppearance.value.saving ||
      splashAppearance.value.saveFailed) return@withLock
    splashAppearance.update { it.copy(loading = it.savedChoice == null) }
    try {
      val saved = SplashAppearancePreferences.read(service)
      if (FrameworkConnection.service.value === service && !splashAppearance.value.saving) {
        splashAppearance.value = ThemeEffectsUiState(savedChoice = saved)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: UnsupportedOperationException) {
      if (FrameworkConnection.service.value === service && !splashAppearance.value.saving) {
        splashAppearance.value = ThemeEffectsUiState(unavailable = true)
      }
    } catch (_: Exception) {
      if (FrameworkConnection.service.value === service && !splashAppearance.value.saving) {
        splashAppearance.value = ThemeEffectsUiState(readFailed = true)
      }
    } finally {
      if (FrameworkConnection.service.value === service) splashAppearance.update { it.copy(loading = false) }
    }
  }

  fun setSplashAppearance(enabled: Boolean) {
    val service = FrameworkConnection.service.value ?: return
    if (!framework.value.splashAppearanceInScope() ||
      !splashAppearance.value.canSaveThemeEffects(enabled)) return
    splashAppearance.update { it.copy(saving = true) }
    viewModelScope.launch {
      splashAppearanceMutex.withLock {
        try {
          if (FrameworkConnection.service.value !== service ||
            !framework.value.splashAppearanceInScope()) return@withLock
          SplashAppearancePreferences.save(service, enabled)
          if (FrameworkConnection.service.value === service) {
            splashAppearance.value = ThemeEffectsUiState(savedChoice = enabled)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: UnsupportedOperationException) {
          if (FrameworkConnection.service.value === service) splashAppearance.update { it.copy(unavailable = true) }
        } catch (_: Exception) {
          if (FrameworkConnection.service.value === service) splashAppearance.update { it.copy(saveFailed = true) }
        } finally {
          if (FrameworkConnection.service.value === service) splashAppearance.update { it.copy(saving = false) }
        }
      }
    }
  }

  private fun clearBrowserSource() {
    browserSourceRequest++
    browserSourceRead?.cancel()
    browserSourceRead = null
    browserSource.value = null
  }

  private suspend fun readRuntimeStatus(includeIconTheme: Boolean = true, force: Boolean = false) {
    val requestedAt = SystemClock.elapsedRealtime()
    runtimeMutex.withLock {
      val service = FrameworkConnection.service.value ?: return
      val bindingRevision = browserBindingRevision
      // A home-screen preference receipt deliberately omits the icon inventory. Keep its
      // lifecycle/failures separate from the complete theme report and freshness.
      val status: MutableStateFlow<RuntimeStatusUiState> = if (includeIconTheme) runtime else homeScreenRuntime
      // Rapid pager changes reuse a recent receipt; manual refresh still requests a new one.
      val previous = status.value.report
      if (!force && !status.value.readFailed && !status.value.timedOut && previous != null &&
        requestedAt - previous.checkedAtElapsed in 0..3_000 && (!includeIconTheme || previous.iconTheme != null)) return
      // Share a probe that completed while a manual refresh waited for the periodic one.
      if ((status.value.report?.checkedAtElapsed ?: -1L) >= requestedAt &&
        (!includeIconTheme || status.value.report?.iconTheme != null)) return
      // Binder calls cannot always be interrupted. Keep at most one outstanding I/O task,
      // while allowing the page/refresh to finish at its deadline and cancel its observation.
      if (runtimeRead?.isCompleted == false) {
        val fresh = status.value.report?.takeIf { it.isFresh(requestedAt) }
        status.value = RuntimeStatusUiState(report = fresh, checking = true)
        if (includeIconTheme && fresh == null) clearBrowserSource()
        return
      }
      status.update {
        RuntimeStatusUiState(report = it.report?.takeIf { report ->
          report.isFresh(SystemClock.elapsedRealtime())
        }, checking = true)
      }
      val operation = viewModelScope.async(Dispatchers.IO) { RuntimeStatusProtocol.query(service, includeIconTheme) }
      runtimeRead = operation
      try {
        val report = kotlinx.coroutines.withTimeoutOrNull(6_000L) { operation.await() }
        if (FrameworkConnection.service.value === service && browserBindingRevision == bindingRevision) {
          status.value = RuntimeStatusUiState(report = report, timedOut = report == null)
          if (includeIconTheme) {
            val theme = report?.iconTheme
            if (theme != null && theme.source != os.kei.hyper.home.status.SystemIconThemeReport.Source.UNAVAILABLE &&
                (theme.source == os.kei.hyper.home.status.SystemIconThemeReport.Source.DEFAULT || theme.complete)) {
              // previewKey also includes the installed-package selection, so it is not a theme identity.
              val identity = listOf(theme.source.name, theme.localId, theme.nativeHash, theme.revision).joinToString("|")
              val previous = observedAppTheme
              observedAppTheme = identity
              if (previous != null && previous != identity) {
                pendingAppRefresh.add()
                scanner.invalidateAll()
                appsDirty = true
                clearPreviewCache()
                pendingAppFullPreviewRevision = previewCacheRevision
                if (visiblePage in 0..1) scanApps()
              }
            }
            if (theme?.previewKey == null) clearBrowserSource()
            else if (browserSource.value?.revision != theme.previewKey) {
              clearBrowserSource()
              val request = browserSourceRequest
              val sourceJob = viewModelScope.async(start = CoroutineStart.LAZY) {
                readBrowserSource(getApplication(), theme)
              }
              browserSourceRead = sourceJob
              sourceJob.start()
              try {
                val loaded = sourceJob.await()
                if (FrameworkConnection.service.value === service && browserBindingRevision == bindingRevision &&
                    browserSourceRequest == request && browserSourceRead === sourceJob &&
                    runtime.value.report?.iconTheme?.previewKey == theme.previewKey) browserSource.value = loaded
              } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (FrameworkConnection.service.value !== service || browserBindingRevision != bindingRevision ||
                    browserSourceRequest != request) return@withLock
                throw cancelled
              } catch (failure: Exception) {
                android.util.Log.w("KeiHomeBrowser", "Active icon resources unavailable", failure)
              } finally {
                if (browserSourceRead === sourceJob) browserSourceRead = null
                sourceJob.cancel()
              }
            }
          }
        }
      } catch (cancellation: CancellationException) {
        if (FrameworkConnection.service.value === service && browserBindingRevision == bindingRevision)
          status.update { it.copy(checking = false) }
        throw cancellation
      } catch (_: Exception) {
        if (FrameworkConnection.service.value === service && browserBindingRevision == bindingRevision) {
          status.value = RuntimeStatusUiState(readFailed = true)
          if (includeIconTheme) clearBrowserSource()
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
    submitMonet(choice, preserveEnabled = true)
  }

  fun setMonetEnabled(enabled: Boolean) {
    if (enabled && colorOsPaletteUnavailable()) return
    val choice = monet.value.snapshot?.customization ?: MonetCustomization()
    val active = runtime.value.report?.iconTheme?.localId
    val inspiration = preferences.state.value.monetInspiration ?:
      (active != null && preferences.state.value.colorOsComponents[active] == true)
    submitMonet(choice.copy(style = if (inspiration) os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION
      else choice.style.takeUnless { it == os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION }
        ?: os.kei.hyper.home.data.MonetSchemeStyle.TONAL_SPOT), enabled)
  }

  private fun colorOsPaletteUnavailable(): Boolean {
    val active = runtime.value.report?.iconTheme?.localId ?: return false
    val current = browserSource.value.takeIf { it?.revision == runtime.value.report?.iconTheme?.previewKey }
    return colorOsPaletteLocked(preferences.state.value.colorOsComponents[active] == false, current?.paletteNames)
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

  private fun submitMonet(choice: MonetCustomization, enabled: Boolean = true, preserveEnabled: Boolean = false) {
    if (enabled && colorOsPaletteUnavailable()) return
    val service = FrameworkConnection.service.value ?: return
    applyJob?.cancel()
    val submitted = choice.copy(iconAppearance = preferences.state.value.iconPackAppearance)
    monet.update { it.copy(action = MonetActionStatus.APPLYING, submittedChoice = submitted) }
    applyJob = viewModelScope.launch {
      try {
        val request = withContext(Dispatchers.IO) {
          MonetAction.submit(service, submitted, enabled,
            preserveEnabled = preserveEnabled)
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
    overlayObserver.close()
    launcherApps?.unregisterCallback(packageCallback)
    previews.clear()
  }
}
