package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
  LaunchedEffect(vm, state.options, state.catalog?.sourceHash, state.busy) {
    if (!state.busy && state.catalog != null) vm.perfectPacks.loadPreview()
  }
  PerfectSubpage(stringResource(R.string.perfect_icons_preview_title), onBack,
    onRefresh = { vm.perfectPacks.loadPreview(refresh = true) },
    refreshEnabled = !state.busy && state.catalog != null) {
    item("style") {
      Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        BasicComponent(title = stringResource(R.string.perfect_icons_preview_style, style),
          summary = stringResource(R.string.perfect_icons_preview_summary))
        if (preview.loading) LinearProgressIndicator(Modifier.fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp))
        if (state.catalog == null || preview.failed) BasicComponent(
          title = stringResource(R.string.perfect_icons_error),
          summary = stringResource(if (state.catalog == null) R.string.perfect_icons_error_catalog
            else R.string.perfect_icons_preview_error))
      }
    }
    item("search") {
      InputField(query = query, onQueryChange = { query = it }, onSearch = {}, expanded = false,
        onExpandedChange = {}, label = stringResource(R.string.perfect_icons_preview_search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
    }
    item("filter") {
      Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        OverlaySpinnerPreference(title = stringResource(R.string.perfect_icons_preview_filter),
          items = listOf(R.string.perfect_icons_preview_all, R.string.perfect_icons_preview_light,
            R.string.perfect_icons_preview_dark).map { DropdownItem(text = stringResource(it)) },
          selectedIndex = filter, onSelectedIndexChange = { filter = it })
      }
    }
    val visible = pack?.entries.orEmpty().filter { entry ->
      (query.isBlank() || listOfNotNull(entry.nativeName, entry.component?.packageName,
        entry.component?.className, entry.key, labels[entry.nativeName ?: entry.component?.packageName ?: entry.key])
        .any { it.contains(query.trim(), ignoreCase = true) }) &&
        (filter == 0 || if (filter == 1) entry.light != null else entry.dark != null && entry.dark != entry.light)
    }
    item("count") {
      Text(stringResource(R.string.perfect_icons_preview_count, visible.size, pack?.entries?.size ?: 0),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
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
              val snapshot = listOfNotNull(entry.light, entry.dark)
                .any { it is IconPackArtwork.Archive && it.snapshot }
              MetadataPill(stringResource(if (snapshot) R.string.perfect_icons_preview_snapshot
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
  content: LazyListScope.() -> Unit,
) {
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = title, scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.pack_back)) }
        },
        actions = {
          if (onRefresh != null) IconButton(onClick = onRefresh, enabled = refreshEnabled) {
            Icon(MiuixIcons.Refresh, stringResource(R.string.perfect_icons_preview_refresh))
          }
        })
    }
  }) { padding ->
    LazyColumn(Modifier.fillMaxSize()
      .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
      .nestedScroll(behavior.nestedScrollConnection),
      contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp,
        bottom = padding.calculateBottomPadding() + 24.dp),
      content = content)
  }
}
