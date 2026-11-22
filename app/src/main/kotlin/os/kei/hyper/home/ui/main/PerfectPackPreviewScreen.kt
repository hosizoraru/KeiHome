package os.kei.hyper.home.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import os.kei.hyper.home.ui.components.Os4LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import os.kei.hyper.home.ui.components.Os4PullToRefresh
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import os.kei.hyper.home.R
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.iconpack.IconPackArtwork
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackPreviews
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.nativeLauncherIconNames
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.IconPreviewComparison
import os.kei.hyper.home.ui.components.IconPreviewArtworkFrame
import os.kei.hyper.home.ui.components.IconPreviewInspectionSheet
import os.kei.hyper.home.ui.components.os4Clickable
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.DropdownEntry
import os.kei.hyper.home.ui.components.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.basic.Icon
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.Text
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.util.Locale

private val IconPackEntry.quietSnapshot: Boolean
  get() = (light as? IconPackArtwork.Archive)?.snapshot == true ||
    (dark as? IconPackArtwork.Archive)?.snapshot == true

@Composable
internal fun PerfectPackPreviewScreen(vm: HomeViewModel, onBack: () -> Unit, onOpenResources: () -> Unit,
  backdrop: LayerBackdrop?, bottomBar: @Composable () -> Unit, active: Boolean) {
  val state by vm.perfectPacks.state.collectAsStateWithLifecycle()
  val preview by vm.perfectPacks.previewState.collectAsStateWithLifecycle()
  val maskRevision by vm.packPreviews.revision.collectAsStateWithLifecycle()
  val apps by vm.apps.collectAsStateWithLifecycle()
  val labels = remember(apps.capabilities) {
    val preferred = apps.capabilities.orEmpty().groupBy { it.packageName }.mapValues { (_, values) ->
      (values.firstOrNull { it.personalProfile } ?: values.first()).label
    }
    buildMap {
      putAll(preferred)
      apps.capabilities.orEmpty().forEach { app ->
        nativeLauncherIconNames(IconPackComponent(app.packageName, app.componentName)).forEach {
          putIfAbsent(it, preferred.getValue(app.packageName))
        }
      }
    }
  }
  var query by key(state.options.style) { rememberSaveable { mutableStateOf("") } }
  var filter by rememberSaveable { mutableIntStateOf(0) }
  var nightOnly by rememberSaveable { mutableStateOf(filter == 3) }
  var installedOnly by rememberSaveable { mutableStateOf(false) }
  var sortName by rememberSaveable { mutableStateOf(PerfectPreviewSort.ORIGINAL.name) }
  var reverse by rememberSaveable { mutableStateOf(false) }
  var focusedIcon by remember { mutableStateOf<Pair<IconPackDescriptor, IconPackEntry>?>(null) }
  var showInspection by remember { mutableStateOf(false) }
  val sort = PerfectPreviewSort.entries.firstOrNull { it.name == sortName } ?: PerfectPreviewSort.ORIGINAL
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  val style = state.options.style.takeUnless { it.isBlank() || it == "default" }
    ?: stringResource(R.string.perfect_icons_style_default)
  val pack = preview.pack?.takeIf {
    preview.options == state.options && preview.sourceHash == state.catalog?.sourceHash
  }
  val filters = listOf(R.string.perfect_icons_preview_all, R.string.perfect_icons_preview_static,
    R.string.perfect_icons_preview_dynamic_filter, R.string.perfect_icons_preview_dark)
  val byKey = remember(SnapshotIdentity(pack)) { pack?.entries.orEmpty().associateBy { it.key } }
  val rows = remember(SnapshotIdentity(pack), labels) {
    val entries = pack?.entries.orEmpty()
    entries.map { entry ->
      val identity = entry.nativeName ?: entry.component?.packageName ?: entry.key
      val label = labels[identity] ?: entry.component?.packageName?.let(labels::get)
      PerfectPreviewListItem(entry.key, identity, label,
        listOfNotNull(entry.component?.packageName, entry.component?.className),
        label != null, entry.quietSnapshot, entry.dark != null && entry.dark != entry.light)
    }
  }
  val projectionScope = remember(SnapshotIdentity(pack), SnapshotIdentity(rows)) { Any() }
  val projectionOptions = remember(nightOnly, installedOnly, sort, reverse, locales) { Any() }
  val projection = rememberLocalSearch(projectionScope, query, projectionOptions) { request ->
    val context = currentCoroutineContext()
    perfectPreviewViews(rows, request.query, nightOnly, installedOnly, sort, reverse,
      Locale.forLanguageTag(locales.substringBefore(',')), check = { context.ensureActive() }).map { group ->
      group.map { context.ensureActive(); byKey.getValue(it.key) }
    }
  }
  val groups = projection.value
  val view = filter.takeIf { it in 0..2 } ?: 0
  val visible = groups?.get(view).orEmpty()
  val listState = key(state.options.style) { rememberLazyListState() }
  val loadingListState = rememberLazyListState()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(vm, lifecycle, active, state.options, state.catalog?.sourceHash, state.busy) {
    if (!active) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      if (!state.busy && state.ready) vm.perfectPacks.loadPreview()
      awaitCancellation()
    }
  }
  PerfectSubpage(stringResource(R.string.perfect_icons_preview_title), onBack,
    backdrop = backdrop,
    bottomBar = bottomBar,
    onRefresh = { vm.perfectPacks.loadPreview(refresh = true) },
    refreshEnabled = !state.busy && state.ready,
    // Keep the saved position until the asynchronous projection has restored the icon rows.
    listState = if (groups != null) listState else loadingListState,
    bottomContent = {
      if (pack != null) os.kei.hyper.home.ui.components.Os4ViewTabs(
        items = filters.take(3).mapIndexed { index, label ->
          val title = stringResource(label)
          groups?.get(index)?.size?.let { stringResource(R.string.browser_view_count, title, it) } ?: title
        }, selectedIndex = view, onSelect = { filter = it; listState.requestScrollToItem(0) },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("perfect.preview.views"))
    },
    actions = {
      if (pack != null) {
        val filterSummary = listOfNotNull(
          stringResource(R.string.perfect_icons_preview_dark).takeIf { nightOnly },
          stringResource(R.string.perfect_list_installed_only).takeIf { installedOnly },
        ).joinToString(" · ").ifEmpty { stringResource(R.string.pack_list_filter_all) }
        PerfectListSortMenu(listOf(R.string.perfect_list_original, R.string.perfect_list_name,
          R.string.perfect_list_identity), sort.ordinal, reverse,
          onSort = { sortName = PerfectPreviewSort.entries[it].name },
          onReverse = { reverse = !reverse }, modifier = Modifier.testTag("perfect.preview.sort"),
          filtersActive = nightOnly || installedOnly, filterEntries = listOf(
            DropdownEntry(items = listOf(DropdownItem(
              text = stringResource(R.string.perfect_icons_preview_filter), summary = filterSummary,
              children = listOf(
                DropdownItem(text = stringResource(R.string.perfect_icons_preview_dark), selected = nightOnly,
                  onClick = { nightOnly = !nightOnly }),
                DropdownItem(text = stringResource(R.string.perfect_list_installed_only), selected = installedOnly,
                  onClick = { installedOnly = !installedOnly }),
                DropdownItem(text = stringResource(R.string.browser_reset), enabled = nightOnly || installedOnly,
                  onClick = { nightOnly = false; installedOnly = false }),
              ),
            ))),
          ))
      }
    }, floatingActionButton = {
      // Overlay sheets need this Scaffold host, outside its recorded list layer.
      focusedIcon?.let { (focusedPack, entry) ->
        androidx.compose.runtime.key(focusedPack.id, entry.key) {
          val identity = entry.nativeName ?: entry.component?.packageName ?: entry.key
          val label = labels[identity] ?: entry.component?.packageName?.let(labels::get)
          val configuration = LocalConfiguration.current.toString()
          val previews by androidx.compose.runtime.key(focusedPack.id, focusedPack.source, entry, configuration, maskRevision) {
            produceState<IconPackPreviews?>(null) {
              value = vm.packPreviews.load(focusedPack, entry, sizePx = 256)
            }
          }
          IconPreviewInspectionSheet(show = showInspection, onDismissRequest = { showInspection = false },
            title = label ?: identity, modifier = Modifier.testTag("perfect.preview.details"),
            onDismissFinished = { if (!showInspection) focusedIcon = null },
            details = {
              Text(focusedPack.displayName, style = MiuixTheme.textStyles.body2)
              Text(identity, style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
              entry.component?.let {
                Text(it.flattened, style = MiuixTheme.textStyles.footnote2,
                  color = MiuixTheme.colorScheme.onSurfaceSecondary)
              }
              MetadataPill(stringResource(if (entry.quietSnapshot) R.string.perfect_icons_preview_snapshot
                else R.string.perfect_icons_preview_static), MiuixTheme.colorScheme.onSurfaceSecondary)
              if (entry.dark != null && entry.dark != entry.light)
                MetadataPill(stringResource(R.string.perfect_icons_preview_dark), MiuixTheme.colorScheme.primary)
            }) { mode ->
            IconPreviewComparison(mode) { dark, slotModifier ->
              val themeLabel = stringResource(if (dark) R.string.preview_glass_dark else R.string.preview_glass_light)
              val bitmap = previews?.let { if (dark) it.dark else it.light }
              val image = remember(bitmap) { bitmap?.asImageBitmap() }
              Column(slotModifier, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconPreviewArtworkFrame {
                  Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    when {
                      previews == null -> CircularProgressIndicator(size = 24.dp, strokeWidth = 2.dp)
                      image != null -> Image(image, contentDescription = themeLabel, modifier = Modifier.size(88.dp))
                      else -> Text(stringResource(R.string.browser_preview_unavailable),
                        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                  }
                }
                Text(themeLabel, style = MiuixTheme.textStyles.footnote2,
                  color = MiuixTheme.colorScheme.onSurfaceSecondary)
              }
            }
          }
        }
      }
    }) {
    item("style") {
      Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(style, style = MiuixTheme.textStyles.subtitle)
              Text(stringResource(R.string.perfect_icons_preview_count, visible.size, pack?.entries?.size ?: 0),
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
              MetadataPill(stringResource(if (state.options.staticNight) R.string.perfect_icons_night_mode_derived
                else R.string.perfect_icons_night_mode_original), MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            pack?.let { current ->
              (visible.firstOrNull { !it.quietSnapshot && it.dark != null && it.dark != it.light }
                ?: visible.firstOrNull { !it.quietSnapshot && it.light != null })?.let { sample ->
                PackIconPair(current, sample, vm.packPreviews, IconPackAppearance.SYSTEM,
                  Modifier.width(58.dp), distinctOnly = true)
              }
            }
          }
          Text(stringResource(R.string.perfect_icons_preview_note), style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
          Text(stringResource(R.string.perfect_icons_preview_options_scope),
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }

      }
      if (preview.loading || !state.ready || preview.failed) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
          if (preview.loading) LinearProgressIndicator(Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp))
          if (!state.ready || preview.failed) BasicComponent(
            title = stringResource(if (!state.ready) R.string.perfect_git_style_missing else R.string.perfect_icons_error),
            summary = stringResource(if (!state.ready) R.string.perfect_git_style_needed
              else R.string.perfect_icons_preview_error),
            onClick = if (!state.ready) onOpenResources else null)
        }
      }
    }
    if (pack != null) item("search") {
      Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Os4SearchField(query = query, onQueryChange = { query = it },
          label = stringResource(R.string.perfect_icons_preview_search),
          modifier = Modifier.fillMaxWidth(), searching = projection.status.showProgress)
        LocalSearchError(projection.status)
      }
    }
    if (pack != null) {
      items(visible, key = { it.key }, contentType = { "icon" }) { entry ->
        Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
          .animateItem(fadeInSpec = tween(180), fadeOutSpec = tween(120), placementSpec = null)
          .os4Clickable(cornerRadius = CardDefaults.CornerRadius,
          onClickLabel = stringResource(R.string.preview_glass_inspect),
          onClick = { focusedIcon = pack to entry; showInspection = true })) {
          Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PackIconPair(pack, entry, vm.packPreviews, IconPackAppearance.SYSTEM,
              Modifier.width(58.dp), distinctOnly = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              val identity = entry.nativeName ?: entry.component?.packageName ?: entry.key
              val label = labels[identity] ?: entry.component?.packageName?.let(labels::get)
              Text(label ?: identity, style = MiuixTheme.textStyles.body1)
              if (label != null) Text(identity, style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
              MetadataPill(stringResource(if (entry.quietSnapshot) R.string.perfect_icons_preview_snapshot
                else R.string.perfect_icons_preview_static), MiuixTheme.colorScheme.onSurfaceSecondary)
              if (entry.dark != null && entry.dark != entry.light)
                MetadataPill(stringResource(R.string.perfect_icons_preview_dark),
                  MiuixTheme.colorScheme.primary)
            }
          }
        }
      }
      if (groups == null) item("projecting") {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(size = 24.dp, strokeWidth = 2.dp)
        }
      }
      if (groups != null && visible.isEmpty()) item("empty") {
        BasicComponent(title = stringResource(R.string.perfect_icons_preview_empty))
      }
    }
  }

}

@Composable
internal fun PerfectListSortMenu(labels: List<Int>, selected: Int, reverse: Boolean,
  onSort: (Int) -> Unit, onReverse: () -> Unit, modifier: Modifier = Modifier,
  filterEntries: List<DropdownEntry> = emptyList(), filtersActive: Boolean = false) {
  val choices = DropdownEntry(items = labels.mapIndexed { index, label ->
    DropdownItem(text = stringResource(label), selected = selected == index, onClick = { onSort(index) })
  })
  val direction = DropdownEntry(items = listOf(
    DropdownItem(text = stringResource(R.string.browser_sort_reverse), selected = reverse, onClick = onReverse),
  ))
  val selectedLabel = labels.getOrNull(selected)?.let { stringResource(it) }.orEmpty()
  OverlayIconCascadingDropdownMenu(entries = listOf(choices, direction) + filterEntries, modifier = modifier) {
    Icon(MiuixIcons.Glass.Sort, "${stringResource(R.string.browser_sort)}: $selectedLabel",
      tint = if (selected != 0 || reverse || filtersActive)
        MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground)
  }
}

/** Shared MIUIX chrome for the local preview and resource browser. */
@Composable
internal fun PerfectSubpage(
  title: String,
  onBack: () -> Unit,
  onRefresh: (() -> Unit)? = null,
  refreshEnabled: Boolean = true,
  pullRefresh: Boolean = false,
  refreshing: Boolean = false,
  refreshLabel: String = stringResource(R.string.refresh),
  actions: @Composable RowScope.() -> Unit = {},
  bottomContent: @Composable () -> Unit = {},
  listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
  backdrop: LayerBackdrop? = rememberKeiHomeBlurBackdrop(),
  bottomBar: @Composable () -> Unit = {},
  floatingActionButton: @Composable () -> Unit = {},
  bottomSpacing: androidx.compose.ui.unit.Dp = 24.dp,
  content: LazyListScope.() -> Unit,
) {
  val behavior = MiuixScrollBehavior()
  Scaffold(backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = title, scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back)) }
        },
        actions = {
          actions()
          if (onRefresh != null && !pullRefresh) IconButton(onClick = onRefresh, enabled = refreshEnabled) {
            Icon(MiuixIcons.Glass.Refresh, stringResource(R.string.perfect_icons_preview_refresh))
          }
        }, bottomContent = bottomContent)
    }
  }, bottomBar = bottomBar, floatingActionButton = floatingActionButton) { padding ->
    val listPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp,
      bottom = padding.calculateBottomPadding() + bottomSpacing)
    val list: @Composable () -> Unit = {
    Os4LazyColumn(state = listState, modifier = Modifier.fillMaxSize()
      .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
      .keiHomeTopBarScroll(behavior, overScrollEnabled = !pullRefresh || refreshEnabled && !refreshing),
      contentPadding = listPadding,
      topAppBarScrollBehavior = behavior,
      content = content)
    }
    if (pullRefresh && onRefresh != null) {
      Os4PullToRefresh(refreshing = refreshing, enabled = refreshEnabled, onRefresh = onRefresh,
        refreshLabel = refreshLabel, contentPadding = listPadding,
        modifier = Modifier.testTag("perfect.resources.pull-to-refresh")) { list() }
    } else list()
  }
}
