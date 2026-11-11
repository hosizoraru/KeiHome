package os.kei.hyper.home.ui.main

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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.iconpack.apple.AppleAutoMatchPolicy
import os.kei.hyper.home.iconpack.apple.AppleCorners
import os.kei.hyper.home.iconpack.apple.AppleMatchAttemptStatus
import os.kei.hyper.home.iconpack.apple.ApplePlatform
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.OverlayIconCascadingDropdownMenu
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ApplePackScreen(model: HomeViewModel, section: String, onBack: () -> Unit,
  onNavigate: (String) -> Unit) {
  val controller = model.applePacks
  val state by controller.state.collectAsStateWithLifecycle()
  val currentPreviewProvider = if (section != "apple" && section != "apple-search")
    rememberAppleCurrentThemePreviewProvider(model) else null
  val packs by model.iconPacks.collectAsStateWithLifecycle()
  val prefs by model.preferences.state.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  val main = section == "apple"
  val search = section == "apple-search"
  val matchedOnly = section == "apple-matched"
  val batchBrowser = !main && !search && !matchedOnly
  val guidanceNotes = buildList {
    if (main) {
      add(stringResource(R.string.apple_intro))
      add(stringResource(R.string.apple_source_note))
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
  val backdrop = rememberKeiHomeBlurBackdrop()
  var actionHeight by remember { mutableIntStateOf(0) }
  val context = LocalContext.current
  val resources = LocalResources.current
  val uiScope = rememberCoroutineScope()
  var transferring by remember { mutableStateOf(false) }
  var transferMessage by rememberSaveable { mutableStateOf<String?>(null) }
  val exportMappings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    if (uri == null) transferring = false else uiScope.launch {
      try {
        withContext(Dispatchers.IO) {
          val bytes = controller.exportMappings()
          (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open document"))
            .use { it.write(bytes) }
        }
        transferMessage = resources.getString(R.string.apple_mapping_exported)
      } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
      } catch (_: Exception) { transferMessage = resources.getString(R.string.apple_mapping_export_failed)
      } finally { transferring = false }
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
  var localLoadRequested by remember(controller) { mutableStateOf(false) }
  val localLoading = state.loading || (!localLoadRequested && state.rows.isEmpty() && state.pack == null)
  val enabled = !state.busy && !state.loading && !nativeBusy && !transferring
  var filter by rememberSaveable(section) { mutableStateOf("") }
  var filterName by rememberSaveable(section) { mutableStateOf(AppleMappingFilter.ALL.name) }
  var sortName by rememberSaveable(section) { mutableStateOf(AppleMappingSort.NAME.name) }
  var regionFilter by rememberSaveable(section) { mutableStateOf("") }
  var selecting by rememberSaveable(section) { mutableStateOf(false) }
  var selectedPackages by rememberSaveable(section, state.options.platform, state.options.country) {
    mutableStateOf(emptyList<String>())
  }
  var batchClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
  val now = maxOf(batchClock, System.currentTimeMillis())
  // Row retries advance batchClock below; unrelated progress updates need no eligibility scan.
  val eligiblePackages = remember(batchBrowser, state.rows, batchClock) {
    if (batchBrowser) state.rows.asSequence().filter { appleBatchSelectable(it, now) }
      .mapTo(hashSetOf()) { it.target.packageName } else emptySet()
  }
  val locale = LocalConfiguration.current.locales[0]
  val mappingFilter = AppleMappingFilter.entries.firstOrNull { it.name == filterName } ?: AppleMappingFilter.ALL
  val mappingSort = AppleMappingSort.entries.firstOrNull { it.name == sortName } ?: AppleMappingSort.NAME
  val browserRequest = remember(state.rows, filter, mappingFilter, mappingSort, state.options.country,
    regionFilter, matchedOnly, locales, batchClock) { Any() }
  // Retain the previous rows until this exact filtering request finishes.
  val browserResult = if (!main && !search) {
    val result by produceState<Pair<Any, List<AppleMappingRow>>?>(null, browserRequest) {
      val rows = withContext(Dispatchers.Default) {
        appleMappingRows(state.rows, filter, mappingFilter, mappingSort, state.options.country,
          regionFilter, matchedOnly, Locale.forLanguageTag(locales.substringBefore(',')), now)
      }
      value = browserRequest to rows
    }
    result
  } else null
  val browserRows = browserResult?.second.orEmpty()
  val browserLoading = !main && !search && browserResult?.first !== browserRequest
  val regions = remember(main, search, state.rows, state.options.country) {
    if (main || search) emptyList() else state.rows.mapNotNull {
      it.app?.let { app -> app.country ?: state.options.country } ?: it.attempt?.country
    }.distinct().sorted()
  }
  val mappedCount = remember(state.rows) { state.rows.count { it.app != null } }
  val visibleEligible = remember(browserRows, eligiblePackages) {
    browserRows.filter { it.target.packageName in eligiblePackages }.mapTo(linkedSetOf()) { it.target.packageName }
  }
  val selectedPackageSet = remember(selectedPackages) { selectedPackages.toSet() }
  val selectedVisible = remember(visibleEligible, selectedPackageSet) {
    visibleEligible.filter { it in selectedPackageSet }
  }
  val selectedVisibleSet = remember(selectedVisible) { selectedVisible.toSet() }
  val selectedBatch = remember(selectedVisible) {
    selectedVisible.take(AppleAutoMatchPolicy.BATCH_SIZE).toSet()
  }
  LaunchedEffect(batchBrowser, state.rows, batchClock, mappingFilter) {
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
  LaunchedEffect(visibleEligible, selecting, localLoading, browserLoading, state.batchRunning) {
    if (selecting && !localLoading && !browserLoading && !state.batchRunning) {
      val retained = selectedPackages.filter { it in visibleEligible }
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
  fun browserChanged(resetSelection: Boolean = false) {
    if (resetSelection) selecting = false
    focus.clearFocus(); keyboard?.hide()
    uiScope.launch { listState.scrollToItem(0) }
  }
  LaunchedEffect(search, state.previewing, state.preview) {
    if (search && (state.previewing || state.preview != null)) {
      val index = listState.layoutInfo.totalItemsCount - state.candidates.size -
        (if (state.error != null) 1 else 0) - 1
      if (index >= 0) listState.animateScrollToItem(index)
    }
  }
  LaunchedEffect(controller, section) {
    controller.load()
    localLoadRequested = true
  }
  LaunchedEffect(state.retryAtMillis) {
    if (state.retryAtMillis > 0) {
      kotlinx.coroutines.delay((state.retryAtMillis - System.currentTimeMillis()).coerceAtLeast(0))
      controller.refreshCooldown()
    }
  }
  LaunchedEffect(search, state.editing) { if (search && state.editing == null) onBack() }
  DisposableEffect(controller, search) { onDispose { if (search) controller.closeSearch() } }
  LaunchedEffect(model, lifecycle, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      coroutineScope { launch { model.observePage(3, locales) } }
    }
  }
  Scaffold(Modifier.fillMaxSize().imePadding().testTag("icon-packs.$section"), backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(if (main) R.string.apple_pack_title
        else if (search) R.string.apple_choose_icon
        else if (matchedOnly) R.string.apple_matched_title else R.string.apple_mappings),
        scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back)) } },
        actions = {
          if (prefs.compactPackHints) PackHelpAction(notes = guidanceNotes)
          if (!main && !search) {
            AppleMappingFilterMenu(mappingFilter, regionFilter, regions, matchedOnly,
              onFilter = { filterName = it.name; browserChanged(resetSelection = true) },
              onRegion = { regionFilter = it; browserChanged(resetSelection = true) })
            AppleMappingSortMenu(mappingSort) { sortName = it.name; browserChanged() }
          }
          if (!search) {
            val browserActions = if (!main) listOf(DropdownEntry(items = buildList {
              add(DropdownItem(text = stringResource(R.string.apple_refresh_mappings), enabled = enabled,
                onClick = controller::load))
              if (batchBrowser) {
                add(DropdownItem(text = stringResource(if (selecting) R.string.apple_batch_exit_selection
                  else R.string.apple_batch_enter_selection), enabled = enabled, onClick = {
                  selecting = !selecting
                  if (!selecting) selectedPackages = emptyList()
                }))
                if (selecting) {
                  add(DropdownItem(text = stringResource(R.string.apple_batch_select_visible, visibleEligible.size),
                    enabled = enabled && visibleEligible.isNotEmpty(),
                    onClick = { selectedPackages = visibleEligible.toList() }))
                  add(DropdownItem(text = stringResource(R.string.apple_batch_clear_selection),
                    enabled = enabled && selectedPackages.isNotEmpty(),
                    onClick = { selectedPackages = emptyList() }))
                }
              }
            })) else emptyList()
            val transferActions = DropdownEntry(items = listOf(
              DropdownItem(text = stringResource(R.string.apple_mapping_export), enabled = enabled, onClick = {
                transferring = true
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
            OverlayIconCascadingDropdownMenu(entries = browserActions + transferActions) {
              Icon(MiuixIcons.Glass.More, stringResource(R.string.apple_mapping_transfer))
            }
          }
        })
    }
  }, floatingActionButton = {
    if (!matchedOnly) {
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
            selecting -> {
              if (selectedBatch.isNotEmpty()) {
                controller.autoMatch(selectedBatch)
              }
            }
            else -> if (visibleEligible.isNotEmpty()) controller.autoMatch(visibleEligible)
          }
        },
        modifier = Modifier.testTag("apple.action"),
        enabled = enabled && (!search || query.isNotBlank() && !state.searching && !state.previewing) &&
          (!batchBrowser || !browserLoading && state.retryAtMillis <= now &&
            (if (selecting) selectedBatch.isNotEmpty() else visibleEligible.isNotEmpty())),
        busy = actionBusy,
        onHeightChanged = { actionHeight = it })
    }
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      LazyColumn(Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection), state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
          top = insets.calculateTopPadding() + 4.dp,
          bottom = insets.calculateBottomPadding() +
            maxOf(100.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (search && state.retryAtMillis > 0) item("request-cooldown") {
          PackInfoCard(stringResource(R.string.apple_cooldown_summary,
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(state.retryAtMillis))))
        }
        if (localLoading || (!main && !search && browserLoading)) item("local-loading") {
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(title = stringResource(when {
              localLoading && (state.rows.isNotEmpty() || state.pack != null) -> R.string.pack_apple_local_refreshing
              localLoading -> R.string.pack_apple_local_loading
              browserRows.isNotEmpty() -> R.string.pack_apple_list_refreshing
              else -> R.string.pack_apple_list_loading
            }))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
          }
        }
        if (!prefs.compactPackHints) item("guidance") {
          PackGuidanceCard(notes = guidanceNotes)
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
            model.preferences::setIconPackAppearance, enabled, compact = true) }
          item("mappings") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.apple_matched_title),
                summary = stringResource(R.string.apple_mapping_count, mappedCount, state.rows.size),
                enabled = enabled, onClick = { onNavigate("apple-matched") })
              BasicComponent(title = stringResource(R.string.apple_mappings),
                summary = stringResource(R.string.apple_mapping_manage_summary),
                enabled = enabled, onClick = { onNavigate("apple-mappings") })
            }
          }
          if (state.pack != null && (state.pendingChanges || state.publishedOptions != state.options)) item("pending-options") {
            PackInfoCard(stringResource(R.string.apple_options_pending))
          }
          item("cache") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.apple_download_cache),
                summary = stringResource(R.string.apple_cache_summary),
                endActions = { Text(Formatter.formatShortFileSize(context, state.cacheBytes)) })
            BasicComponent(title = stringResource(R.string.apple_check_updates),
              enabled = enabled && state.retryAtMillis == 0L,
                summary = stringResource(R.string.apple_check_updates_summary), onClick = { controller.generate(refresh = true) })
              BasicComponent(title = stringResource(R.string.apple_clear_downloads), enabled = enabled && !state.searching,
                onClick = controller::clearDownloads)
            }
          }
          state.progress?.let { progress -> item("progress") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.apple_progress, progress.done, progress.total),
                summary = progress.label)
              BasicComponent(title = stringResource(R.string.apple_cancel), onClick = controller::cancel)
            }
          } }
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
                }, onImport = { model.importNativeIconPack(card.pack) }, onRecheck = model::recheckNativeIconImport)
            }
          }
        } else if (!search) {
          if (state.retryAtMillis > 0) item("cooldown") {
            PackInfoCard(stringResource(R.string.apple_cooldown_summary,
              android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(state.retryAtMillis))))
          }
          if (batchBrowser) item("batch-guidance") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              PackInfoCard(stringResource(R.string.apple_batch_intro, visibleEligible.size) +
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
            InputField(query = filter, onQueryChange = { filter = it },
              onSearch = { focus.clearFocus(); keyboard?.hide() }, expanded = false,
              onExpandedChange = {}, modifier = Modifier.fillMaxWidth(), label = stringResource(R.string.apple_browser_search))
          }
          item("count") {
            Text(stringResource(R.string.apple_browser_count, browserRows.size, mappedCount, state.rows.size),
              Modifier.padding(horizontal = 12.dp), style = MiuixTheme.textStyles.footnote2,
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
          }
          if (browserRows.isNotEmpty() || (!localLoading && !browserLoading)) {
            appleMappingCards(browserRows, state, controller, enabled && !browserLoading,
              currentPreviewProvider = currentPreviewProvider,
              selectionMode = batchBrowser && selecting, selectedPackages = selectedVisibleSet,
              selectablePackages = visibleEligible, onToggleSelection = { packageName ->
                if (enabled && !browserLoading && packageName in visibleEligible) selectedPackages =
                  if (packageName in selectedPackages) selectedPackages - packageName else selectedPackages + packageName
              }) { row ->
              focus.clearFocus(); keyboard?.hide()
              controller.edit(row.target); onNavigate("apple-search")
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
              state.currentPreview?.let { AppleArtworkPair(it, compact = true) }
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
            InputField(query = query, onQueryChange = { query = it.take(100) },
              onSearch = { searchNow() }, expanded = false, onExpandedChange = {},
              modifier = Modifier.fillMaxWidth().testTag("apple.search.query"),
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
                onClick = { controller.mapping(null, reset = true); onBack() })
              BasicComponent(title = stringResource(R.string.apple_disable_mapping), enabled = enabled,
                onClick = { controller.mapping(null); onBack() })
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
              AppleArtworkPair(preview)
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
              cached?.let { AppleArtworkPair(it, compact = true) }
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
    Os4ActionDialog(show = transferMessage != null, onDismissRequest = { transferMessage = null },
      title = stringResource(R.string.apple_mapping_transfer), summary = transferMessage.orEmpty(),
      icon = actionIcon(PageActionIcon.IMPORT), actions = listOf(
        Os4DialogAction(stringResource(R.string.apple_mapping_transfer_done),
          role = GlassDialogButtonRole.Primary, onClick = { transferMessage = null })))
  }
}

@Composable
internal fun AppleArtworkThumb(file: java.io.File, label: String, size: Dp = 76.dp, dark: Boolean = false) {
  val sizePx = with(LocalDensity.current) { size.roundToPx() }
  val cacheKey = "${file.path}:$sizePx"
  val cached = remember(cacheKey) { ApplePreviewImages.cache.get(cacheKey) }
  val image by produceState<ImageBitmap?>(cached, file, sizePx) {
    value = withContext(Dispatchers.IO) {
      val key = "${file.path}:$sizePx"
      ApplePreviewImages.cache.get(key)?.let { return@withContext it }
      val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
      android.graphics.BitmapFactory.decodeFile(file.path, bounds)
      var sample = 1
      while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
      android.graphics.BitmapFactory.decodeFile(file.path,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        ?.also { ApplePreviewImages.cache.put(key, it) }
    }
  }
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.size(size).background(if (dark) Color(0xff25262a) else Color(0xfff2f2f2),
      RoundedCornerShape(12.dp))) {
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
