package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.iconpack.IconPackArtwork
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.DropdownEntry
import os.kei.hyper.home.ui.components.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.basic.Icon
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.Text
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Filter
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.SwitchPreference

private val IconPackEntry.quietSnapshot: Boolean
  get() = (light as? IconPackArtwork.Archive)?.snapshot == true ||
    (dark as? IconPackArtwork.Archive)?.snapshot == true

@Composable
internal fun PerfectPackPreviewScreen(vm: HomeViewModel, onBack: () -> Unit) {
  val state by vm.perfectPacks.state.collectAsStateWithLifecycle()
  val preview by vm.perfectPacks.previewState.collectAsStateWithLifecycle()
  val apps by vm.apps.collectAsStateWithLifecycle()
  val labels = remember(apps.capabilities) {
    apps.capabilities.orEmpty().groupBy { it.packageName }.mapValues { (_, values) ->
      (values.firstOrNull { it.personalProfile } ?: values.first()).label
    }
  }
  var query by rememberSaveable(state.options.style) { mutableStateOf("") }
  var filter by rememberSaveable { mutableIntStateOf(0) }
  val style = state.options.style.takeUnless { it.isBlank() || it == "default" }
    ?: stringResource(R.string.perfect_icons_style_default)
  val pack = preview.pack?.takeIf {
    preview.options == state.options && preview.sourceHash == state.catalog?.sourceHash
  }
  val filters = listOf(R.string.perfect_icons_preview_all, R.string.perfect_icons_preview_static,
    R.string.perfect_icons_preview_dynamic_filter, R.string.perfect_icons_preview_dark)
  val visible = remember(pack, query, filter, labels) {
    val search = query.trim()
    pack?.entries.orEmpty().filter { entry ->
      (search.isEmpty() || listOfNotNull(entry.nativeName, entry.component?.packageName,
        entry.component?.className, entry.key, labels[entry.nativeName ?: entry.component?.packageName ?: entry.key])
        .any { it.contains(search, ignoreCase = true) }) &&
        when (filter) {
          1 -> !entry.quietSnapshot
          2 -> entry.quietSnapshot
          3 -> entry.dark != null && entry.dark != entry.light
          else -> true
        }
    }
  }
  LaunchedEffect(vm, state.options, state.catalog?.sourceHash, state.busy) {
    if (!state.busy && state.ready) vm.perfectPacks.loadPreview()
  }
  PerfectSubpage(stringResource(R.string.perfect_icons_preview_title), onBack,
    onRefresh = { vm.perfectPacks.loadPreview(refresh = true) },
    refreshEnabled = !state.busy && state.ready,
    actions = {
      if (pack != null) {
      OverlayIconCascadingDropdownMenu(entries = listOf(DropdownEntry(items = filters.mapIndexed { index, label ->
        DropdownItem(text = stringResource(label), selected = filter == index, onClick = { filter = index })
      }))) {
        Icon(MiuixIcons.Glass.Filter, stringResource(R.string.perfect_icons_preview_filter),
          tint = if (filter == 0) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.primary)
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
              MetadataPill(stringResource(filters[filter.coerceIn(filters.indices)]), MiuixTheme.colorScheme.primary)
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
        SwitchPreference(title = stringResource(R.string.perfect_icons_dynamic),
          summary = stringResource(R.string.perfect_icons_dynamic_summary),
          checked = state.options.dynamic, enabled = !state.busy,
          onCheckedChange = { vm.perfectPacks.setOptions(state.options.copy(dynamic = it)) })
        SwitchPreference(title = stringResource(R.string.perfect_icons_static_night),
          summary = stringResource(if (state.options.staticNight) R.string.perfect_icons_night_enabled
            else R.string.perfect_icons_night_disabled),
          checked = state.options.staticNight, enabled = !state.busy,
          onCheckedChange = { vm.perfectPacks.setOptions(state.options.copy(staticNight = it)) })
      }
      if (preview.loading || !state.ready || preview.failed) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
          if (preview.loading) LinearProgressIndicator(Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp))
          if (!state.ready || preview.failed) BasicComponent(
            title = stringResource(if (!state.ready) R.string.perfect_git_style_missing else R.string.perfect_icons_error),
            summary = stringResource(if (!state.ready) R.string.perfect_git_style_needed
              else R.string.perfect_icons_preview_error))
        }
      }
    }
    if (pack != null) item("search") {
      InputField(query = query, onQueryChange = { query = it }, onSearch = {}, expanded = false,
        onExpandedChange = {}, label = stringResource(R.string.perfect_icons_preview_search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
    }
    if (pack != null) {
      items(visible, key = { it.key }) { entry ->
        Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
          Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PackIconPair(pack, entry, vm.packPreviews, IconPackAppearance.SYSTEM,
              Modifier.width(58.dp), distinctOnly = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              val identity = entry.nativeName ?: entry.component?.packageName ?: entry.key
              val label = labels[identity]
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
      if (visible.isEmpty()) item("empty") {
        BasicComponent(title = stringResource(R.string.perfect_icons_preview_empty))
      }
    }
  }
}

/** Shared MIUIX chrome for the local preview and source/storage detail pages. */
@Composable
internal fun PerfectSubpage(
  title: String,
  onBack: () -> Unit,
  onRefresh: (() -> Unit)? = null,
  refreshEnabled: Boolean = true,
  actions: @Composable RowScope.() -> Unit = {},
  floatingActionButton: @Composable () -> Unit = {},
  floatingToolbar: @Composable () -> Unit = {},
  bottomSpacing: androidx.compose.ui.unit.Dp = 24.dp,
  content: LazyListScope.() -> Unit,
) {
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = title, scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back)) }
        },
        actions = {
          actions()
          if (onRefresh != null) IconButton(onClick = onRefresh, enabled = refreshEnabled) {
            Icon(MiuixIcons.Glass.Refresh, stringResource(R.string.perfect_icons_preview_refresh))
          }
        })
    }
  }, floatingActionButton = floatingActionButton, floatingToolbar = floatingToolbar) { padding ->
    LazyColumn(Modifier.fillMaxSize()
      .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
      .nestedScroll(behavior.nestedScrollConnection),
      contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp,
        bottom = padding.calculateBottomPadding() + bottomSpacing),
      content = content)
  }
}
