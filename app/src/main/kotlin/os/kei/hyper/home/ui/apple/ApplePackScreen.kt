package os.kei.hyper.home.ui.apple

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.PackGuidancePage
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.iconpack.apple.AppleAutoMatchPolicy
import os.kei.hyper.home.iconpack.apple.AppleCorners
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.apple.AppleMatchAttemptStatus
import os.kei.hyper.home.iconpack.apple.ApplePlatform
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.Os4PullToRefresh
import os.kei.hyper.home.ui.components.Os4ViewTabs
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.More
import os.kei.hyper.home.ui.components.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import os.kei.hyper.home.ui.main.HomeViewModel
import os.kei.hyper.home.ui.main.IconPackAppearancePreference
import os.kei.hyper.home.ui.main.IconPackCard
import os.kei.hyper.home.ui.main.LocalSearchError
import os.kei.hyper.home.ui.main.Os4SearchField
import os.kei.hyper.home.ui.main.PackHelpAction
import os.kei.hyper.home.ui.main.PackInfoCard
import os.kei.hyper.home.ui.main.PageActionFab
import os.kei.hyper.home.ui.main.PageActionIcon
import os.kei.hyper.home.ui.main.SnapshotIdentity
import os.kei.hyper.home.ui.main.actionIcon
import os.kei.hyper.home.ui.main.browserRows
import os.kei.hyper.home.ui.main.rememberLocalSearch

@Composable
internal fun ApplePackScreen(model: HomeViewModel, section: String, onBack: () -> Unit,
  onOpenSearch: () -> Unit, localLoadRequested: Boolean, backdrop: LayerBackdrop?,
  bottomBar: @Composable () -> Unit = {}, active: Boolean = true) {
  val controller = model.applePacks
  val state by controller.state.collectAsStateWithLifecycle()
  val currentPreviewProvider = if (section != "apple" && section != "apple-search")
    rememberAppleCurrentThemePreviewProvider(model) else null
  val packs by model.iconPacks.collectAsStateWithLifecycle()
  val prefs by model.preferences.state.collectAsStateWithLifecycle()
  val maskRevision by model.packPreviews.revision.collectAsStateWithLifecycle()
  val maskPolicy by produceState<IconMaskPolicy?>(null, model, maskRevision) {
    value = withContext(Dispatchers.IO) { model.packPreviews.maskSnapshot() }
  }
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  val main = section == "apple"
  val search = section == "apple-search"
  val matchedOnly = section == "apple-matched"
  val batchBrowser = !main && !search && !matchedOnly
  val guidanceNotes = buildList {
    if (main) {
      add(stringResource(R.string.apple_intro))
      add(stringResource(R.string.apple_source_note))
      add(stringResource(R.string.generated_pack_retention_note))
      if (state.options.platform == ApplePlatform.IPADOS) add(stringResource(R.string.apple_ipad_note))
      add(stringResource(R.string.apple_resolution_summary))
      add(stringResource(R.string.apple_corners_summary))
      if (state.options.corners == AppleCorners.ORIGINAL) add(stringResource(R.string.apple_original_note))
      add(stringResource(R.string.icon_pack_appearance_preview_note))
      add(stringResource(R.string.icon_pack_appearance_scope_note))
      if (prefs.iconPackAppearance == IconPackAppearance.SYSTEM) {
        add(stringResource(R.string.icon_pack_appearance_system_note))
      }
      if (prefs.iconPackAppearance == IconPackAppearance.DIM_AT_NIGHT) {
        add(stringResource(R.string.icon_pack_appearance_dim_note))
      }
      add(stringResource(R.string.icon_pack_appearance_snapshot_note))
      add(stringResource(R.string.icon_pack_effects_note))
    } else if (search) {
      add(stringResource(R.string.apple_search_intro, state.editing?.label.orEmpty()))
    } else {
      add(stringResource(if (matchedOnly) R.string.apple_matched_intro else R.string.apple_mapping_intro))
    }
  }
  val behavior = MiuixScrollBehavior()
  var actionHeight by remember { mutableIntStateOf(0) }
  val context = LocalContext.current
  val resources = LocalResources.current
  val uiScope = rememberCoroutineScope()
  var transferring by remember { mutableStateOf(false) }
  var transferMessage by rememberSaveable { mutableStateOf<String?>(null) }
  var exportingSelection by rememberSaveable { mutableStateOf(false) }
  var exportSelectionCount by rememberSaveable { mutableIntStateOf(0) }
  var selectedExportDocument by remember { mutableStateOf<ByteArray?>(null) }
  var showClearDownloads by rememberSaveable { mutableStateOf(false) }
  var mappingConfirmation by rememberSaveable(state.editing?.packageName) { mutableStateOf<String?>(null) }
  val exportMappings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    if (uri == null) {
      transferring = false
      exportingSelection = false
      selectedExportDocument = null
    } else uiScope.launch {
      try {
        withContext(Dispatchers.IO) {
          // A lost selection snapshot must fail safely, never silently export all mappings.
          val bytes = if (exportingSelection) requireNotNull(selectedExportDocument) else controller.exportMappings()
          (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open document"))
            .use { it.write(bytes) }
        }
        transferMessage = if (exportingSelection) resources.getString(R.string.apple_glass_exported, exportSelectionCount)
          else resources.getString(R.string.apple_mapping_exported)
      } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
      } catch (_: Exception) { transferMessage = resources.getString(R.string.apple_mapping_export_failed)
      } finally {
        transferring = false
        exportingSelection = false
        selectedExportDocument = null
      }
    }
  }
  val importMappings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) transferring = false else uiScope.launch {
      try {
        val count = withContext(Dispatchers.IO) {
          (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open document"))
            .use { controller.importMappings(it) }
        }
        transferMessage = resources.getString(R.string.apple_mapping_imported, count)
      } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
      } catch (_: Exception) { transferMessage = resources.getString(R.string.apple_mapping_import_failed)
      } finally { transferring = false }
    }
  }
  val nativeBusy = packs.nativeImporting != null
  val localLoading = state.loading || (!localLoadRequested && state.rows.isEmpty() && state.pack == null)
  val enabled = !state.busy && !state.loading && !nativeBusy && !transferring
  var filter by rememberSaveable(section) { mutableStateOf("") }
  var filterName by rememberSaveable(section) { mutableStateOf(AppleMappingFilter.ALL.name) }
  var sortName by rememberSaveable(section) { mutableStateOf(AppleMappingSort.NAME.name) }
  var regionFilter by rememberSaveable(section) { mutableStateOf("") }
  var selecting by rememberSaveable(section, state.options.platform, state.options.country) { mutableStateOf(false) }
  var inspectionRow by remember(section, state.options.platform, state.options.country) {
    mutableStateOf<AppleMappingRow?>(null)
  }
  var showInspection by remember(section, state.options.platform, state.options.country) { mutableStateOf(false) }
  var selectedPackages by rememberSaveable(section, state.options.platform, state.options.country) {
    mutableStateOf(emptyList<String>())
  }
  var batchClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
  val now = maxOf(batchClock, System.currentTimeMillis())
  val rowsIdentity = SnapshotIdentity(state.rows)
  // Row retries advance batchClock below; unrelated progress updates need no eligibility scan.
  val eligiblePackages = remember(batchBrowser, rowsIdentity, batchClock) {
    if (batchBrowser) state.rows.asSequence().filter { appleBatchSelectable(it, now) }
      .mapTo(hashSetOf()) { it.target.packageName } else emptySet()
  }
  val locale = LocalConfiguration.current.locales[0]
  val savedFilter = AppleMappingFilter.entries.firstOrNull { it.name == filterName } ?: AppleMappingFilter.ALL
  val browserViews = if (matchedOnly) appleMatchedViews else appleMappingViews
  var browserView by rememberSaveable(section) {
    mutableIntStateOf(browserViews.indexOf(savedFilter).coerceAtLeast(0))
  }
  val selectedBrowserView = browserView.coerceIn(browserViews.indices)
  val mappingFilter = savedFilter.takeUnless { it in browserViews } ?: AppleMappingFilter.ALL
  val mappingSort = AppleMappingSort.entries.firstOrNull { it.name == sortName } ?: AppleMappingSort.NAME
  val browserScope = remember(rowsIdentity) { Any() }
  val browserOptions = remember(mappingFilter, mappingSort, state.options.country,
    regionFilter, matchedOnly, locales, batchClock) { Any() }
  val browserResult = if (!main && !search) {
    rememberLocalSearch(browserScope, filter, browserOptions) { request ->
      val context = currentCoroutineContext()
      appleMappingRows(state.rows, request.query, mappingFilter, mappingSort, state.options.country,
        regionFilter, matchedOnly, Locale.forLanguageTag(locales.substringBefore(',')), now,
        check = { context.ensureActive() }).let { appleMappingViewRows(it, matchedOnly) }
    }
  } else null
  val browserRows = browserResult?.value?.getOrNull(selectedBrowserView).orEmpty()
  val browserRowsIdentity = SnapshotIdentity(browserRows)
  val browserLoading = browserResult?.status?.let { it.pending || it.error != null } == true
  val regions = remember(main, search, rowsIdentity, state.options.country) {
    if (main || search) emptyList() else state.rows.mapNotNull {
      it.app?.let { app -> app.country ?: state.options.country } ?: it.attempt?.country
    }.distinct().sorted()
  }
  val mappedCount = remember(batchBrowser, rowsIdentity) {
    if (batchBrowser) state.rows.count { it.app != null } else 0
  }
  val visibleMatchEligible = remember(browserRowsIdentity, SnapshotIdentity(eligiblePackages)) {
    browserRows.filter { it.target.packageName in eligiblePackages }.mapTo(linkedSetOf()) { it.target.packageName }
  }
  val selection = remember(browserRowsIdentity, SnapshotIdentity(selectedPackages), batchBrowser, batchClock) {
    appleMappingSelection(browserRows, selectedPackages, batchBrowser, now)
  }
  val visibleEligible = selection.selectable
  val selectedVisible = selection.selected
  val selectedBatch = selection.match
  LaunchedEffect(batchBrowser, rowsIdentity, batchClock, mappingFilter) {
    if (!batchBrowser) return@LaunchedEffect
    val time = System.currentTimeMillis()
    val next = state.rows.asSequence().flatMap { row -> buildList {
      if (row.app == null && !row.custom) row.attempt?.retryAt?.let(::add)
      if (mappingFilter == AppleMappingFilter.RECENTLY_TRIED)
        row.attempt?.attemptedAt?.let { add(it + AppleAutoMatchPolicy.HISTORY_TTL_MS + 1) }
    } }.filter { it > time }.minOrNull() ?: return@LaunchedEffect
    kotlinx.coroutines.delay((next - time).coerceAtLeast(0) + 25)
    batchClock = System.currentTimeMillis()
  }
  LaunchedEffect(SnapshotIdentity(visibleEligible), selecting, localLoading, browserLoading, state.batchRunning) {
    if (selecting && !localLoading && !browserLoading && !state.batchRunning) {
      val retained = retainedAppleSelection(selectedPackages, visibleEligible)
      if (retained != selectedPackages) selectedPackages = retained
    }
  }
  var query by rememberSaveable(state.editing?.packageName, state.options.platform) {
    mutableStateOf(state.editing?.label.orEmpty())
  }
  val focus = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  fun searchNow() { focus.clearFocus(); keyboard?.hide(); controller.search(query) }
  val listState = rememberLazyListState()
  val loadingListState = rememberLazyListState()
  // A returning browser can reconstruct its projection after the layout has already measured.
  val awaitingRows = browserResult != null && (localLoading || (browserResult.value == null && browserLoading))
  fun exitSelection() { selecting = false; selectedPackages = emptyList() }
  NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = active && selecting, onBackCompleted = { exitSelection() })
  fun browserChanged(resetSelection: Boolean = false) {
    if (resetSelection) exitSelection()
    focus.clearFocus(); keyboard?.hide()
    listState.requestScrollToItem(0)
  }
  fun exportSelectedMappings() {
    if (!enabled || browserLoading || selection.export.isEmpty()) return
    val rows = selection.export.toList()
    val options = state.options
    transferring = true
    uiScope.launch {
      try {
        selectedExportDocument = withContext(Dispatchers.Default) { appleSelectedMappingDocument(rows, options) }
        exportSelectionCount = rows.size
        exportingSelection = true
        exportMappings.launch("keihome-apple-selected-" +
          LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".json")
        exitSelection()
      } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
      } catch (_: Exception) {
        transferring = false
        exportingSelection = false
        selectedExportDocument = null
        transferMessage = resources.getString(R.string.apple_mapping_export_failed)
      }
    }
  }
  LaunchedEffect(search, state.previewing, state.preview) {
    if (search && (state.previewing || state.preview != null)) {
      val index = listState.layoutInfo.totalItemsCount - state.candidates.size -
        (if (state.error != null) 1 else 0) - 1
      if (index >= 0) listState.animateScrollToItem(index)
    }
  }
  LaunchedEffect(search, state.editing) { if (search && state.editing == null) onBack() }
  DisposableEffect(controller, search) { onDispose { if (search) controller.closeSearch() } }
  val modifier = Modifier.fillMaxSize().testTag("icon-packs.$section")
  val selectionMode = !main && !search && selecting
  Scaffold(if (search) modifier.imePadding() else modifier, backdrop = backdrop, bottomBar = {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
      if (selectionMode) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
          AppleSelectionToolbar(selection, allowMatch = batchBrowser, enabled = enabled && !browserLoading,
            matchingEnabled = state.retryAtMillis <= now,
            onSelectAll = { selectedPackages = visibleEligible.toList() },
            onClear = { selectedPackages = emptyList() },
            onMatch = { val targets = selectedBatch; exitSelection(); controller.autoMatch(targets) },
            onExport = { exportSelectedMappings() }, onDone = { exitSelection() })
        }
      }
      bottomBar()
    }
  }, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = if (selecting) stringResource(R.string.apple_glass_selection_count, selectedVisible.size)
        else stringResource(if (main) R.string.apple_pack_title
        else if (search) R.string.apple_choose_icon
        else if (matchedOnly) R.string.apple_matched_title else R.string.apple_mappings),
        scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = { if (selecting) exitSelection() else onBack() }) {
          Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back))
        } },
        actions = {
          PackHelpAction(notes = guidanceNotes, page = when {
            main -> PackGuidancePage.APPLE
            search -> PackGuidancePage.APPLE_SEARCH
            matchedOnly -> PackGuidancePage.APPLE_MATCHED
            else -> PackGuidancePage.APPLE_MAPPINGS
          })
          if (!main && !search) {
            AppleMappingFilterMenu(mappingFilter, regionFilter, regions, matchedOnly,
              onFilter = { filterName = it.name; browserChanged(resetSelection = true) },
              onRegion = { regionFilter = it; browserChanged(resetSelection = true) },
              sort = mappingSort, onSort = { sortName = it.name; browserChanged() })
          }
          if (!main && !search && !selecting) AppleSelectionEntry(
            enabled = enabled && !browserLoading && visibleEligible.isNotEmpty(),
            onClick = { focus.clearFocus(); keyboard?.hide(); selectedPackages = emptyList(); selecting = true })
          if (!search && !selecting) {
            val transferActions = DropdownEntry(items = listOf(
              DropdownItem(text = stringResource(R.string.apple_mapping_export), enabled = enabled, onClick = {
                transferring = true
                exportingSelection = false
                selectedExportDocument = null
                try {
                  exportMappings.launch("keihome-apple-mappings-" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".json")
                } catch (_: Exception) {
                  transferring = false
                  transferMessage = resources.getString(R.string.apple_mapping_export_failed)
                }
              }),
              DropdownItem(text = stringResource(R.string.apple_mapping_import), enabled = enabled, onClick = {
                transferring = true
                try { importMappings.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                } catch (_: Exception) {
                  transferring = false
                  transferMessage = resources.getString(R.string.apple_mapping_import_failed)
                }
              }),
            ))
            OverlayIconCascadingDropdownMenu(entries = listOf(transferActions)) {
              Icon(MiuixIcons.Glass.More, stringResource(R.string.apple_mapping_transfer))
            }
          }
        }, bottomContent = {
          if (batchBrowser || matchedOnly) Os4ViewTabs(
            items = browserViews.mapIndexed { index, view ->
              val title = stringResource(
                if (matchedOnly && view == AppleMappingFilter.USER) R.string.browser_origin_user else view.label)
              val count = browserResult?.value?.get(index)?.size
              if (count != null) stringResource(R.string.browser_view_count, title, count) else title
            },
            selectedIndex = selectedBrowserView,
            onSelect = { browserView = it; browserChanged(resetSelection = true) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
              .testTag(if (matchedOnly) "apple.matched.views" else "apple.mapping.views"),
          )
        })
    }
  }, floatingActionButton = {
    if (!matchedOnly && !selecting) {
      val description = when {
        batchBrowser && selecting -> stringResource(R.string.apple_batch_match_selected,
          selectedBatch.size, selectedVisible.size)
        batchBrowser -> stringResource(R.string.apple_batch_next, AppleAutoMatchPolicy.BATCH_SIZE)
        search -> stringResource(R.string.apple_search)
        state.pack == null -> stringResource(R.string.apple_generate)
        else -> stringResource(R.string.apple_generate_incremental)
      }
      val actionBusy = if (search) state.searching else state.busy || localLoading
      val label = stringResource(when {
        actionBusy && localLoading -> R.string.action_label_preparing
        actionBusy -> R.string.action_label_processing
        search -> R.string.action_label_search
        batchBrowser -> R.string.action_label_match
        else -> R.string.action_label_generate
      })
      PageActionFab(label = label, description = description,
        icon = actionIcon(when {
          search -> PageActionIcon.SEARCH
          batchBrowser -> PageActionIcon.MATCH
          else -> PageActionIcon.GENERATE
        }),
        onClick = {
          when {
            search -> searchNow()
            main -> controller.generate()
            else -> if (visibleMatchEligible.isNotEmpty()) controller.autoMatch(visibleMatchEligible)
          }
        },
        modifier = Modifier.testTag("apple.action"),
        enabled = enabled && (!search || query.isNotBlank() && !state.searching && !state.previewing) &&
          (!batchBrowser || !browserLoading && state.retryAtMillis <= now &&
            visibleMatchEligible.isNotEmpty()),
        busy = actionBusy,
        onHeightChanged = { actionHeight = it })
    }
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      val actionSpacing =
        if (selectionMode) 16.dp else maxOf(100.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp)
      val listPadding = PaddingValues(start = 16.dp, end = 16.dp,
        top = insets.calculateTopPadding() + 4.dp,
        bottom = insets.calculateBottomPadding() + actionSpacing)
      val list: @Composable () -> Unit = {
      LazyColumn(overscrollEffect = null, modifier = Modifier.fillMaxSize()
        .keiHomeTopBarScroll(behavior, overScrollEnabled = main || search || enabled && !selecting && !localLoading),
        state = if (awaitingRows) loadingListState else listState,
        contentPadding = listPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (main && state.pack != null &&
          (state.pendingChanges || state.publishedOptions != state.options)) item("pending-options") {
          PackInfoCard(stringResource(R.string.apple_options_pending),
            Modifier.testTag("apple.pending-options"))
        }
        if (search && state.retryAtMillis > 0) item("request-cooldown") {
          PackInfoCard(stringResource(R.string.apple_cooldown_summary,
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(state.retryAtMillis))))
        }
        if (localLoading || (browserLoading && browserResult?.value == null &&
            browserResult?.status?.error == null)) item("local-loading") {
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(title = stringResource(when {
              localLoading && (state.rows.isNotEmpty() || state.pack != null) -> R.string.pack_apple_local_refreshing
              localLoading -> R.string.pack_apple_local_loading
              else -> R.string.pack_apple_list_loading
            }))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
          }
        }
        if (main) {
          if (state.retryAtMillis > 0) item("cooldown") {
            PackInfoCard(stringResource(R.string.apple_cooldown_summary,
              android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(state.retryAtMillis))))
          }
          if (state.batchRunning) item("batch-progress") {
            AppleBatchProgressCard(state, controller::cancelBatch)
          }
          item("options") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              OverlaySpinnerPreference(title = stringResource(R.string.apple_platform), enabled = enabled,
                items = listOf("iOS", "iPadOS", "macOS").map { DropdownItem(text = it) },
                selectedIndex = state.options.platform.ordinal,
                onSelectedIndexChange = { controller.options(state.options.copy(platform = ApplePlatform.entries[it])) })
              OverlaySpinnerPreference(title = stringResource(R.string.apple_resolution),
                enabled = enabled,
                items = listOf("256 px", "512 px", "1024 px").map { DropdownItem(text = it) },
                selectedIndex = listOf(256, 512, 1024).indexOf(state.options.resolution),
                onSelectedIndexChange = { controller.options(state.options.copy(resolution = listOf(256, 512, 1024)[it])) })
              OverlaySpinnerPreference(title = stringResource(R.string.apple_corners), enabled = enabled,
                items = listOf("Rounded", "Original").map { DropdownItem(text = it) },
                selectedIndex = state.options.corners.ordinal,
                onSelectedIndexChange = { controller.options(state.options.copy(corners = AppleCorners.entries[it])) })
              AppleStorefrontPreference(state.options.country, enabled) {
                controller.options(state.options.copy(country = it))
              }
            }
          }
          item("appearance") { IconPackAppearancePreference(prefs.iconPackAppearance,
            model.preferences::setIconPackAppearance, enabled, compact = true,
            staticAppearance = prefs.staticAppearanceIcons,
            onStaticAppearanceChange = model.preferences::setStaticAppearanceIcons) }
          item("cache") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.apple_download_cache),
                summary = stringResource(R.string.apple_cache_summary),
                endActions = { Text(Formatter.formatShortFileSize(context, state.cacheBytes)) })
            BasicComponent(title = stringResource(R.string.apple_check_updates),
              enabled = enabled && state.retryAtMillis == 0L,
                summary = stringResource(R.string.apple_check_updates_summary), onClick = { controller.generate(refresh = true) })
              BasicComponent(title = stringResource(R.string.apple_clear_downloads), enabled = enabled && !state.searching,
                onClick = { showClearDownloads = true })
            }
          }
          state.result?.let { result -> item("result") {
            PackInfoCard(stringResource(if (result.unchanged) R.string.apple_unchanged else R.string.apple_generated,
              result.mapped, result.dark, result.reused, result.failed))
          } }
          if ((state.result?.stale ?: 0) > 0) item("stale") {
            PackInfoCard(stringResource(R.string.apple_stale_metadata))
          }
          packs.packs.firstOrNull { it.pack.id == "apple" }?.let { card ->
            item("generated-pack") {
              val runtime by model.runtime.collectAsStateWithLifecycle()
              val framework by model.framework.collectAsStateWithLifecycle()
              val ready = framework.connected && framework.snapshot?.themesInScope == true &&
                runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong() &&
                !runtime.readFailed && !runtime.timedOut
              IconPackCard(card, model.packPreviews, prefs.iconPackAppearance,
                packs.nativeAssociations[prefs.systemIconFallback.sourceId(card.pack.id)],
                false, packs.nativeImporting == prefs.systemIconFallback.sourceId(card.pack.id),
                ready && enabled, packs.nativeResult?.takeIf {
                  it.sourceId == prefs.systemIconFallback.sourceId(card.pack.id)
                }, onImport = { model.importNativeIconPack(card.pack) }, onRecheck = model::recheckNativeIconImport,
                manualThemeFallback = prefs.manualThemeFallback)
            }
          }
        } else if (!search) {
          if (state.retryAtMillis > 0) item("cooldown") {
            PackInfoCard(stringResource(R.string.apple_cooldown_summary,
              android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(state.retryAtMillis))))
          }
          if (batchBrowser) item("batch-guidance") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              PackInfoCard(stringResource(R.string.apple_batch_intro, visibleMatchEligible.size) +
                if (selecting) "\n" + stringResource(R.string.apple_batch_selection_count,
                  selectedVisible.size, selectedBatch.size) else "")
              if (state.batchRunning) AppleBatchProgressCard(state, controller::cancelBatch)
            }
          }
          if (batchBrowser) state.batchResult?.takeIf { !state.batchRunning }?.let { result -> item("batch-result") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(if (state.batchRunning) R.string.apple_batch_results_running
                else R.string.apple_batch_results),
                summary = stringResource(R.string.apple_batch_result_summary, result.attempted, result.matched,
                  result.review, result.unmatched, result.failed, result.skipped))
              BasicComponent(title = stringResource(R.string.apple_batch_show_recent), onClick = {
                filterName = AppleMappingFilter.RECENTLY_TRIED.name
                browserChanged(resetSelection = true)
              })
              if (result.review > 0) BasicComponent(title = stringResource(R.string.apple_batch_show_review), onClick = {
                filterName = AppleMappingFilter.NEEDS_REVIEW.name
                browserChanged(resetSelection = true)
              })
            }
          } }
          item("filter") {
            Column {
              Os4SearchField(query = filter, onQueryChange = { filter = it },
                modifier = Modifier.fillMaxWidth(), inputModifier = Modifier.testTag("apple.browser.query"),
                label = stringResource(R.string.apple_browser_search),
                searching = browserResult?.status?.showProgress == true)
              browserResult?.status?.let { LocalSearchError(it) }
            }
          }
          if (batchBrowser) item("count") {
            Text(stringResource(R.string.apple_browser_count, browserRows.size, mappedCount, state.rows.size),
              Modifier.padding(horizontal = 12.dp), style = MiuixTheme.textStyles.footnote2,
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
          }
          if (browserRows.isNotEmpty() || (!localLoading && !browserLoading)) {
            appleMappingCards(browserRows, state, controller, enabled && (!selecting || !browserLoading),
              maskPolicy = maskPolicy,
              currentPreviewProvider = currentPreviewProvider,
              selectionMode = selecting, selectedPackages = selectedVisible,
              selectablePackages = visibleEligible, onToggleSelection = { packageName ->
                if (enabled && !browserLoading && packageName in visibleEligible) selectedPackages =
                  if (packageName in selectedPackages) selectedPackages - packageName else selectedPackages + packageName
              }) { row ->
              focus.clearFocus(); keyboard?.hide()
              if (row.app != null) {
                inspectionRow = row
                showInspection = true
              } else {
                controller.edit(row.target); onOpenSearch()
              }
            }
          }
        } else {
          state.editing?.packageName?.let { packageName ->
            item("editing-package") {
              Text(packageName, Modifier.padding(horizontal = 12.dp),
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
          }
          val current = state.rows.firstOrNull { it.target.packageName == state.editing?.packageName }
          if (current?.attempt?.status == AppleMatchAttemptStatus.REVIEW && state.candidates.isNotEmpty()) {
            item("batch-review-guidance") { PackInfoCard(stringResource(R.string.apple_batch_review_intro)) }
          }
          if (current?.app != null) item("current-mapping") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.apple_current_mapping),
                summary = "${current.app.name} · ${appleRegionName(current.app.country ?: state.options.country, locale)}")
              state.currentPreview?.let { AppleArtworkPair(it, compact = true, maskPolicy = maskPolicy) }
              if (state.currentPreview == null) BasicComponent(title = stringResource(R.string.apple_get_current_preview),
                summary = stringResource(R.string.apple_no_cached_preview), enabled = enabled && !state.previewing,
                onClick = { focus.clearFocus(); keyboard?.hide()
                  controller.preview(current.app.copy(country = current.app.country ?: state.options.country)) })
              if (!current.app.id.startsWith("system_")) BasicComponent(
                title = stringResource(R.string.apple_refresh_one),
                summary = stringResource(R.string.apple_refresh_one_summary),
                enabled = enabled && !state.previewing && state.retryAtMillis == 0L,
                onClick = {
                  focus.clearFocus(); keyboard?.hide()
                  controller.preview(current.app.copy(country = current.app.country ?: state.options.country), refresh = true)
                })
            }
          }
          item("search-region") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              AppleStorefrontPreference(state.searchCountry, enabled, search = true, onCountryChange = {
                focus.clearFocus(); keyboard?.hide(); controller.searchCountry(it)
              })
            }
          }
          item("query") {
            Os4SearchField(query = query, onQueryChange = { query = it.take(100) },
              onSearch = { searchNow() },
              modifier = Modifier.fillMaxWidth(), inputModifier = Modifier.testTag("apple.search.query"),
              label = stringResource(R.string.apple_search_label))
          }
          if (state.searching) item("search-progress") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.apple_searching))
              LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
              BasicComponent(title = stringResource(R.string.apple_cancel), onClick = controller::closeSearch)
            }
          }
          item("reset") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.apple_restore_mapping), enabled = enabled,
                onClick = { mappingConfirmation = "reset" })
              BasicComponent(title = stringResource(R.string.apple_disable_mapping), enabled = enabled,
                onClick = { mappingConfirmation = "exclude" })
            }
          }
          if (state.previewing) item("preview-loading") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.apple_preview_loading))
              LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
              BasicComponent(title = stringResource(R.string.apple_cancel), onClick = controller::closeSearch)
            }
          }
          state.preview?.let { preview -> item("preview") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = preview.app.name,
                summary = "${preview.app.publisher} · ${appleRegionName(preview.app.country ?: state.searchCountry, locale)}")
              AppleArtworkPair(preview, maskPolicy = maskPolicy)
              BasicComponent(title = stringResource(R.string.apple_use_mapping),
                onClick = { controller.mapping(preview.app); onBack() })
            }
          } }
          items(state.candidates, key = { it.id }, contentType = { "candidate" }) { app ->
            val cached by produceState<os.kei.hyper.home.iconpack.apple.AppleArtworkPreview?>(null,
              app, state.options, state.previewRevision) {
              value = try { controller.cachedPreview(app) }
              catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) { null }
            }
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = app.name, summary = "${app.publisher}\n${app.bundleId} · ${app.id}\n" +
                appleRegionName(app.country ?: state.searchCountry, locale),
                enabled = enabled && !state.previewing, onClick = { focus.clearFocus(); keyboard?.hide(); controller.preview(app) })
              cached?.let { AppleArtworkPair(it, compact = true, maskPolicy = maskPolicy) }
            }
          }
          if (state.searched && state.candidates.isEmpty()) item("empty") {
            PackInfoCard(stringResource(R.string.apple_search_empty))
          }
        }
        state.error?.let { error -> item("error") {
          PackInfoCard(stringResource(when (error) {
            ApplePackError.LIMITED -> R.string.apple_error_limited
            ApplePackError.EMPTY -> R.string.apple_error_empty
            ApplePackError.NETWORK -> R.string.apple_error_network
            ApplePackError.GENERATION -> R.string.apple_error_generation
            ApplePackError.SIZE -> R.string.apple_error_size
          }))
        } }
      }
    }
      if (main || search) list() else {
        Os4PullToRefresh(refreshing = localLoading, enabled = enabled && !selecting,
          onRefresh = controller::load, contentPadding = listPadding,
          refreshLabel = stringResource(R.string.apple_refresh_mappings),
          modifier = Modifier.testTag("apple.$section.pull-to-refresh")) { list() }
      }
    }
    inspectionRow?.let { row ->
      key(row.target.packageName, state.options.platform, state.options.country) {
        AppleMappingInspection(row, state, controller, maskPolicy,
          show = active && showInspection, enabled = enabled,
          onDismiss = { showInspection = false },
          onDismissFinished = { if (!showInspection) inspectionRow = null },
          onEdit = {
            showInspection = false
            controller.edit(row.target)
            onOpenSearch()
          })
      }
    }
    Os4ActionDialog(show = active && showClearDownloads, title = stringResource(R.string.apple_clear_downloads),
      summary = stringResource(R.string.apple_glass_clear_confirm),
      icon = actionIcon(PageActionIcon.CLEAR), onDismissRequest = { showClearDownloads = false },
      actions = listOf(
        Os4DialogAction(stringResource(R.string.logs_cancel), onClick = { showClearDownloads = false }),
        Os4DialogAction(stringResource(R.string.apple_clear_downloads), enabled = enabled && !state.searching,
          role = GlassDialogButtonRole.Destructive, tag = "apple.cache.confirm-clear",
          onClick = { showClearDownloads = false; controller.clearDownloads() })))
    val resetMapping = mappingConfirmation == "reset"
    Os4ActionDialog(show = mappingConfirmation != null,
      title = stringResource(if (resetMapping) R.string.apple_restore_mapping else R.string.apple_disable_mapping),
      summary = stringResource(if (resetMapping) R.string.apple_glass_restore_confirm else R.string.apple_glass_exclude_confirm,
        state.editing?.label.orEmpty()), icon = actionIcon(PageActionIcon.CLEAR),
      onDismissRequest = { mappingConfirmation = null }, actions = listOf(
        Os4DialogAction(stringResource(R.string.logs_cancel), onClick = { mappingConfirmation = null }),
        Os4DialogAction(stringResource(if (resetMapping) R.string.apple_restore_mapping else R.string.apple_disable_mapping),
          enabled = enabled && state.editing != null, role = GlassDialogButtonRole.Destructive,
          tag = "apple.mapping.confirm-change", onClick = {
            mappingConfirmation = null
            controller.mapping(null, reset = resetMapping)
            onBack()
          })))
    Os4ActionDialog(show = active && transferMessage != null, onDismissRequest = { transferMessage = null },
      title = stringResource(R.string.apple_mapping_transfer), summary = transferMessage.orEmpty(),
      icon = actionIcon(PageActionIcon.IMPORT), actions = listOf(
        Os4DialogAction(stringResource(R.string.apple_mapping_transfer_done),
          role = GlassDialogButtonRole.Primary, onClick = { transferMessage = null })))
  }
}

@Composable
internal fun AppleArtworkThumb(file: java.io.File, label: String, size: Dp = 76.dp, dark: Boolean = false,
  maskPolicy: IconMaskPolicy?) {
  val sizePx = with(LocalDensity.current) { size.roundToPx() }
  val cacheKey = "${file.path}:$sizePx:${maskPolicy?.identity}"
  val cached = remember(cacheKey) { ApplePreviewImages.cache.get(cacheKey) }
  val image by produceState<ImageBitmap?>(cached, cacheKey) {
    if (maskPolicy == null) {
      value = null
      return@produceState
    }
    value = withContext(Dispatchers.IO) {
      ApplePreviewImages.cache.get(cacheKey)?.let { return@withContext it }
      val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
      android.graphics.BitmapFactory.decodeFile(file.path, bounds)
      var sample = 1
      while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
      val bitmap = android.graphics.BitmapFactory.decodeFile(file.path,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
      val clipped = IconMaskPolicy.clip(bitmap, maskPolicy.resolve(null))
      if (clipped !== bitmap) bitmap.recycle()
      clipped.asImageBitmap().also { ApplePreviewImages.cache.put(cacheKey, it) }
    }
  }
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.size(size)) {
      Crossfade(targetState = image, animationSpec = tween(160), label = "apple-artwork") { bitmap ->
        if (bitmap != null) Image(bitmap, label, Modifier.fillMaxSize())
      }
    }
    Text(label, style = MiuixTheme.textStyles.footnote1, maxLines = 2,
      overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
      textAlign = androidx.compose.ui.text.style.TextAlign.Center)
  }
}

@Composable
private fun AppleBatchProgressCard(state: ApplePackUiState, onCancel: () -> Unit) {
  val progress = state.batchProgress
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    BasicComponent(title = stringResource(R.string.apple_batch_progress, progress?.done ?: 0, progress?.total ?: 0),
      summary = progress?.label?.takeIf { it.isNotBlank() })
    BasicComponent(title = stringResource(R.string.apple_batch_cancel), onClick = onCancel)
  }
}

private object ApplePreviewImages {
  val cache = object : android.util.LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
  }
}
