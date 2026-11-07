package os.kei.hyper.home.ui.main

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.apple.AppleCorners
import os.kei.hyper.home.iconpack.apple.ApplePlatform
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ApplePackScreen(model: HomeViewModel, section: String, onBack: () -> Unit,
  onNavigate: (String) -> Unit) {
  val controller = model.applePacks
  val state by controller.state.collectAsStateWithLifecycle()
  val packs by model.iconPacks.collectAsStateWithLifecycle()
  val prefs by model.preferences.state.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  val main = section == "apple"
  val search = section == "apple-search"
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  var actionHeight by remember { mutableIntStateOf(0) }
  val context = LocalContext.current
  val nativeBusy = packs.nativeImporting != null
  val enabled = !state.busy && !state.loading && !nativeBusy
  var filter by rememberSaveable(section) { mutableStateOf("") }
  var query by rememberSaveable(state.editing?.packageName, state.options.platform) {
    mutableStateOf(state.editing?.label.orEmpty())
  }
  val focus = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  fun searchNow() { focus.clearFocus(); keyboard?.hide(); controller.search(query) }
  val listState = rememberLazyListState()
  LaunchedEffect(search, state.previewing, state.preview) {
    if (search && (state.previewing || state.preview != null)) listState.animateScrollToItem(3)
  }
  LaunchedEffect(controller, section) { controller.load() }
  LaunchedEffect(search, state.editing) { if (search && state.editing == null) onBack() }
  DisposableEffect(controller, search) { onDispose { if (search) controller.closeSearch() } }
  LaunchedEffect(model, lifecycle, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      coroutineScope { launch { model.observePage(3, locales) } }
    }
  }
  val ready = framework.connected && framework.snapshot?.themesInScope == true &&
    runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong() && !runtime.readFailed && !runtime.timedOut
  Scaffold(Modifier.fillMaxSize().imePadding().testTag("icon-packs.$section"), topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(if (main) R.string.apple_pack_title
        else if (search) R.string.apple_choose_icon else R.string.apple_mappings),
        scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.pack_back)) } })
    }
  }, floatingActionButton = {
    if (main || search) PageActionFab(
      label = stringResource(if (search) R.string.apple_search else if (state.pack == null)
        R.string.apple_generate else R.string.apple_generate_incremental),
      icon = if (search) MiuixIcons.Search else MiuixIcons.Refresh,
      onClick = { if (search) searchNow() else controller.generate() },
      modifier = Modifier.testTag("apple.action"),
      enabled = enabled && (!search || query.isNotBlank()),
      busy = if (search) state.searching else state.busy,
      onHeightChanged = { actionHeight = it })
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      LazyColumn(Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection), state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
          top = insets.calculateTopPadding() + 4.dp,
          bottom = insets.calculateBottomPadding() + if (main || search)
            maxOf(100.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp) else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (main) {
          item("intro") { PackInfoCard(stringResource(R.string.apple_intro)) }
          item("options") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              OverlaySpinnerPreference(title = stringResource(R.string.apple_platform), enabled = enabled,
                items = listOf("iOS", "iPadOS", "macOS").map { DropdownItem(text = it) },
                selectedIndex = state.options.platform.ordinal,
                onSelectedIndexChange = { controller.options(state.options.copy(platform = ApplePlatform.entries[it])) })
              OverlaySpinnerPreference(title = stringResource(R.string.apple_resolution),
                summary = stringResource(R.string.apple_resolution_summary), enabled = enabled,
                items = listOf("256 px", "512 px", "1024 px").map { DropdownItem(text = it) },
                selectedIndex = listOf(256, 512, 1024).indexOf(state.options.resolution),
                onSelectedIndexChange = { controller.options(state.options.copy(resolution = listOf(256, 512, 1024)[it])) })
              OverlaySpinnerPreference(title = stringResource(R.string.apple_corners), enabled = enabled,
                summary = stringResource(R.string.apple_corners_summary),
                items = listOf("Rounded", "Original").map { DropdownItem(text = it) },
                selectedIndex = state.options.corners.ordinal,
                onSelectedIndexChange = { controller.options(state.options.copy(corners = AppleCorners.entries[it])) })
              OverlaySpinnerPreference(title = stringResource(R.string.apple_region), enabled = enabled,
                items = listOf(stringResource(R.string.apple_region_us), stringResource(R.string.apple_region_cn),
                  stringResource(R.string.apple_region_jp), stringResource(R.string.apple_region_hk)).map { DropdownItem(text = it) },
                selectedIndex = listOf("us", "cn", "jp", "hk").indexOf(state.options.country),
                onSelectedIndexChange = { controller.options(state.options.copy(country = listOf("us", "cn", "jp", "hk")[it])) })
            }
          }
          item("appearance") { IconPackAppearancePreference(prefs.iconPackAppearance,
            model.preferences::setIconPackAppearance, enabled, compact = true) }
          item("mappings") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.apple_mappings),
                summary = stringResource(R.string.apple_mapping_count, state.rows.count { it.app != null }, state.rows.size),
                onClick = { onNavigate("apple-mappings") })
            }
          }
          if (state.options.platform == ApplePlatform.IPADOS) item("ipad-note") {
            PackInfoCard(stringResource(R.string.apple_ipad_note))
          }
          if (state.options.corners == AppleCorners.ORIGINAL) item("original-note") {
            PackInfoCard(stringResource(R.string.apple_original_note))
          }
          item("source-note") { PackInfoCard(stringResource(R.string.apple_source_note)) }
          if (state.pack != null && (state.pendingChanges || state.publishedOptions != state.options)) item("pending-options") {
            PackInfoCard(stringResource(R.string.apple_options_pending))
          }
          item("cache") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.apple_download_cache),
                summary = stringResource(R.string.apple_cache_summary),
                endActions = { Text(Formatter.formatShortFileSize(context, state.cacheBytes)) })
              BasicComponent(title = stringResource(R.string.apple_check_updates), enabled = enabled,
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
              IconPackCard(card, model.packPreviews, prefs.iconPackAppearance,
                packs.nativeAssociations[prefs.systemIconFallback.sourceId(card.pack.id)],
                false, packs.nativeImporting == prefs.systemIconFallback.sourceId(card.pack.id),
                ready && enabled, packs.nativeResult?.takeIf {
                  it.sourceId == prefs.systemIconFallback.sourceId(card.pack.id)
                }, onImport = { model.importNativeIconPack(card.pack) }, onRecheck = model::recheckNativeIconImport)
            }
          }
        } else if (!search) {
          item("intro") { PackInfoCard(stringResource(R.string.apple_mapping_intro)) }
          item("filter") {
            InputField(query = filter, onQueryChange = { filter = it }, onSearch = {}, expanded = false,
              onExpandedChange = {}, modifier = Modifier.fillMaxWidth(), label = stringResource(R.string.apple_filter_apps))
          }
          val needle = filter.trim().lowercase(java.util.Locale.ROOT)
          items(state.rows.filter { needle.isBlank() || it.target.label.lowercase(java.util.Locale.ROOT).contains(needle) ||
            it.target.packageName.contains(needle) }, key = { it.target.packageName }, contentType = { "mapping" }) { row ->
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = row.target.label,
                summary = row.target.packageName + "\n" + (row.app?.name ?: stringResource(R.string.apple_unmatched)) +
                  if (row.custom) " · " + stringResource(R.string.apple_custom) else "",
                enabled = enabled, onClick = { controller.edit(row.target); onNavigate("apple-search") })
            }
          }
        } else {
          item("intro") { PackInfoCard(stringResource(R.string.apple_search_intro, state.editing?.label.orEmpty())) }
          item("query") {
            InputField(query = query, onQueryChange = { query = it.take(100) },
              onSearch = { searchNow() }, expanded = false, onExpandedChange = {},
              modifier = Modifier.fillMaxWidth().testTag("apple.search.query"),
              label = stringResource(R.string.apple_search_label))
          }
          if (state.searching) item("search-progress") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.apple_searching))
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
              BasicComponent(title = stringResource(R.string.apple_cancel), onClick = controller::closeSearch)
            }
          }
          state.preview?.let { preview -> item("preview") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = preview.app.name, summary = preview.app.publisher)
              Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                AppleArtworkThumb(preview.light, stringResource(R.string.apple_preview_light))
                AppleArtworkThumb(preview.dark ?: preview.light, stringResource(
                  if (preview.dark == null) R.string.apple_preview_same else R.string.apple_preview_dark))
              }
              BasicComponent(title = stringResource(R.string.apple_use_mapping),
                onClick = { controller.mapping(preview.app); onBack() })
            }
          } }
          items(state.candidates, key = { it.id }, contentType = { "candidate" }) { app ->
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = app.name, summary = "${app.publisher}\n${app.bundleId} · ${app.id}",
                enabled = enabled, onClick = { focus.clearFocus(); keyboard?.hide(); controller.preview(app) })
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
  }
}

@Composable
private fun AppleArtworkThumb(file: java.io.File, label: String) {
  val sizePx = with(LocalDensity.current) { 76.dp.roundToPx() }
  val image by produceState<ImageBitmap?>(null, file, sizePx) {
    value = withContext(Dispatchers.IO) {
      val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
      android.graphics.BitmapFactory.decodeFile(file.path, bounds)
      var sample = 1
      while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
      android.graphics.BitmapFactory.decodeFile(file.path,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
  }
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.size(76.dp)) { image?.let { Image(it, label, Modifier.fillMaxSize()) } }
    Text(label, style = MiuixTheme.textStyles.footnote1)
  }
}
