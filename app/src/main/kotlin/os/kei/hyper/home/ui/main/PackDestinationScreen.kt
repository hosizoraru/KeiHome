package os.kei.hyper.home.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import os.kei.hyper.home.R
import os.kei.hyper.home.data.PackGuidancePage
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.Os4BadgePalette
import os.kei.hyper.home.ui.components.Os4InfoPopup
import os.kei.hyper.home.ui.components.Os4InsetDivider
import os.kei.hyper.home.ui.components.Os4NavigationRow
import os.kei.hyper.home.ui.components.Os4ViewTabs
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.DisplayBrightness
import top.yukonga.miuix.kmp.icon.glass.Layers
import top.yukonga.miuix.kmp.icon.glass.UsageHelp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import os.kei.hyper.home.ui.apple.ApplePackDestination
import os.kei.hyper.home.ui.apple.ApplePackSearchDestination
import os.kei.hyper.home.ui.apple.ApplePackTab
import os.kei.hyper.home.ui.perfect.PerfectListSortMenu
import os.kei.hyper.home.ui.perfect.PerfectPackDestination
import os.kei.hyper.home.ui.perfect.PerfectPackTab

@Composable
internal fun PackDestinationScreen(model: HomeViewModel, section: String,
  onBack: () -> Unit, onNavigate: (String) -> Unit) {
  if (section == "custom-icons") {
    CustomIconsScreen(model, onBack, onNavigate)
    return
  }
  if (section == "settings") {
    PackSettingsScreen(model, onBack) { onNavigate("icon-mask") }
    return
  }
  if (section == "icon-mask") {
    IconMaskSettingsScreen(model, onBack)
    return
  }
  if (section == "preserved-icons") {
    PreservedIconsScreen(model, onBack)
    return
  }
  if (section == "recommended-mappings") {
    RecommendedMappingsScreen(model, onBack)
    return
  }
  val perfectTab = PerfectPackTab.forSection(section)
  if (perfectTab != null) {
    PerfectPackDestination(model, perfectTab, onBack)
    return
  }
  val appleTab = ApplePackTab.forSection(section)
  if (appleTab != null) {
    ApplePackDestination(model, appleTab, onBack) { onNavigate("apple-search") }
    return
  }
  if (section == "apple-search") {
    ApplePackSearchDestination(model, onBack)
    return
  }
  if (section == "monet") {
    MonetDestinationScreen(model, onBack, onOpenPacks = { onNavigate("library") })
    return
  }
  val prefs by model.preferences.state.collectAsStateWithLifecycle()
  val builtin = section == "builtin" || section == "coloros"
  var builtinTab by rememberSaveable(section) { mutableIntStateOf(if (section == "coloros") 1 else 0) }
  val contentSection = if (builtin) {
    if (builtinTab == 1) "coloros" else "builtin"
  } else section
  var filterStatus by rememberSaveable(section) { mutableIntStateOf(0) }
  var filterMatches by rememberSaveable(section) { mutableIntStateOf(0) }
  val filter = IconPackCatalogFilter(
    IconPackCatalogStatus.entries.getOrElse(filterStatus) { IconPackCatalogStatus.ALL },
    IconPackCatalogMatches.entries.getOrElse(filterMatches) { IconPackCatalogMatches.ALL },
  )
  val fallback = section == "fallback"
  val fallbackSettings = section == "fallback-settings"
  val extraIcons = section == "extra-icons"
  val fileImport = section == "installed" || section == "imported"
  var actionHeight by remember { mutableIntStateOf(0) }
  val actionBottomPadding = maxOf(100.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp)
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(model, lifecycle, locales, section) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      coroutineScope {
        launch { model.observePage(3, locales) }
        if (fallback || fallbackSettings) model.observeFallbackResources(pageObserved = true)
      }
    }
  }
  val defaultBehavior = MiuixScrollBehavior()
  val colorOsBehavior = MiuixScrollBehavior()
  val behavior = if (contentSection == "coloros") colorOsBehavior else defaultBehavior
  val list = rememberLazyListState()
  val hyperOsList = rememberLazyListState()
  val colorOsList = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val updateFilter: (IconPackCatalogFilter) -> Unit = {
    filterStatus = it.status.ordinal
    filterMatches = it.matches.ordinal
    scope.launch { list.scrollToItem(0) }
  }
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(backdrop = backdrop,
    modifier = Modifier.testTag("icon-packs.$section"),
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(title = stringResource(when (section) {
          "library" -> R.string.icon_pack_library
          "builtin", "coloros" -> R.string.pack_builtin
          "installed" -> R.string.pack_installed
          "imported" -> R.string.pack_imported
          "fallback" -> R.string.pack_completion_title
          "fallback-settings" -> R.string.icon_fallback_title
          "extra-icons" -> R.string.extra_icons_title
          else -> R.string.pack_coloros_title
        }), scrollBehavior = behavior,
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          navigationIcon = { IconButton(onClick = onBack) {
            Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back))
          } }, actions = {
            when (contentSection) {
              "installed", "imported" -> PackHelpAction(
                notes = packListGuidance(prefs.iconPackAppearance) +
                  if (section == "installed") listOf(stringResource(R.string.icon_pack_apk_file_guidance)) else emptyList(),
                page = if (section == "installed") PackGuidancePage.INSTALLED else PackGuidancePage.IMPORTED)
              "extra-icons" -> Os4InfoPopup(
                title = stringResource(R.string.pack_guidance_title),
                summary = stringResource(R.string.extra_icons_instructions),
                modifier = Modifier.testTag("extra-icons.help"),
                icon = MiuixIcons.Glass.UsageHelp)
              "coloros" -> PackHelpAction(colorOsPackGuidance(prefs.autoApplyIconPack), PackGuidancePage.COLOROS)
            }
            if (section == "installed" || section == "imported") {
              PackCatalogMenu(model, filter, updateFilter) {
                scope.launch { list.scrollToItem(0) }
              }
            }
            if (!fileImport) RefreshAction(model, 3)
          }, bottomContent = {
            if (builtin) Os4ViewTabs(listOf("HyperOS", "ColorOS"), builtinTab, { builtinTab = it },
              Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("icon-packs.builtin.tabs"))
          })
      }
    }, floatingActionButton = {
      if (fileImport) PackFileImportAction(model, section, onHeightChanged = { actionHeight = it })
      else PackActionFab(model, 3, contentSection, onHeightChanged = { actionHeight = it })
    },
  ) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      val padding = PaddingValues(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 4.dp,
        bottom = insets.calculateBottomPadding() +
          if (fallbackSettings || extraIcons || fileImport) actionBottomPadding + 24.dp
          else if (contentSection == "coloros" || fallback)
            actionBottomPadding + 12.dp else 24.dp)
      val contentModifier = Modifier.fillMaxSize().keiHomeTopBarScroll(behavior)
      if (extraIcons) {
        ExtraIconsPage(model, contentModifier, padding)
      } else if (fallbackSettings) {
        Column(contentModifier.verticalScroll(rememberScrollState(), overscrollEffect = null).padding(padding),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
          IconFallbackOrderCard(model)
          SystemIconFallbackCard(model, onOpenPerfectIcons = onNavigate)
          IconPackRefreshFeedback(model)
        }
      } else if (fallback) {
        val customState by model.customIcons.state.collectAsStateWithLifecycle()
        val preservedCount = prefs.preservedIconPackages.size
        val customCount = customState.selections.size
        val recommendedEnabled = listOf(
          prefs.systemAppNameMatching,
          prefs.telegramChannelMapping,
          prefs.phoneCompanionMapping,
          prefs.weiboLiteMapping,
          prefs.colorOsThemeStoreMapping,
        ).count { it }
        val extraCount = prefs.extraIconPackages.size
        val fallbackLabel = systemFallbackName(prefs.systemIconFallback)
        Column(contentModifier.verticalScroll(rememberScrollState(), overscrollEffect = null).padding(padding),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
          SmallTitle(stringResource(R.string.pack_completion_section_overrides))
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            Os4NavigationRow(
              title = stringResource(R.string.preserved_icons_title),
              summary = stringResource(R.string.preserved_icons_entry),
              icon = actionIcon(PageActionIcon.APPS),
              tint = Os4BadgePalette.Blue,
              badgeText = preservedCount.takeIf { it > 0 }?.toString(),
              modifier = Modifier.testTag("icon-packs.open-preserved-icons"),
              onClick = { onNavigate("preserved-icons") },
            )
            Os4InsetDivider()
            Os4NavigationRow(
              title = stringResource(R.string.custom_icons_title),
              summary = stringResource(R.string.custom_icons_entry),
              icon = actionIcon(PageActionIcon.CUSTOMIZE),
              tint = Os4BadgePalette.Purple,
              badgeText = customCount.takeIf { it > 0 }?.toString(),
              modifier = Modifier.testTag("icon-packs.open-custom-icons"),
              onClick = { onNavigate("custom-icons") },
            )
          }
          SmallTitle(stringResource(R.string.pack_completion_section_automatic))
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            Os4NavigationRow(
              title = stringResource(R.string.recommended_mappings_title),
              summary = stringResource(R.string.recommended_mappings_entry),
              icon = actionIcon(PageActionIcon.MATCH),
              tint = Os4BadgePalette.Teal,
              badgeText = "$recommendedEnabled/5",
              modifier = Modifier.testTag("icon-packs.open-recommended-mappings"),
              onClick = { onNavigate("recommended-mappings") },
            )
            Os4InsetDivider()
            Os4NavigationRow(
              title = stringResource(R.string.extra_icons_title),
              summary = stringResource(R.string.extra_icons_entry),
              icon = MiuixIcons.Glass.DisplayBrightness,
              tint = Os4BadgePalette.Orange,
              badgeText = extraCount.takeIf { it > 0 }?.toString(),
              modifier = Modifier.testTag("icon-packs.open-extra-icons"),
              onClick = { onNavigate("extra-icons") },
            )
            Os4InsetDivider()
            Os4NavigationRow(
              title = stringResource(R.string.icon_fallback_title),
              summary = stringResource(R.string.icon_fallback_entry_summary),
              icon = MiuixIcons.Glass.Layers,
              tint = Os4BadgePalette.Pink,
              badgeText = fallbackLabel,
              modifier = Modifier.testTag("icon-fallback.entry"),
              onClick = { onNavigate("fallback-settings") },
            )
          }
          IconPackRefreshFeedback(model)
        }
      } else if (fileImport) {
        PagePullToRefresh(model, 3, padding, Modifier.testTag("icon-packs.$section.pull-to-refresh")) { busy ->
          IconPacksPage(model, list, padding,
            Modifier.fillMaxSize().keiHomeTopBarScroll(behavior, overScrollEnabled = !busy), section, onNavigate,
            filter = filter, onFilterReset = { updateFilter(IconPackCatalogFilter()) })
        }
      } else if (builtin) {
        Crossfade(targetState = contentSection, animationSpec = tween(200), label = "builtin-pack-tab") { tab ->
          val tabList = if (tab == "coloros") colorOsList else hyperOsList
          val tabBehavior = if (tab == "coloros") colorOsBehavior else defaultBehavior
          IconPacksPage(model, tabList, padding, Modifier.fillMaxSize().keiHomeTopBarScroll(tabBehavior),
            tab, onNavigate, filter = filter, onFilterReset = { updateFilter(IconPackCatalogFilter()) })
        }
      } else {
        IconPacksPage(model, list, padding, contentModifier, contentSection, onNavigate,
          filter = filter, onFilterReset = { updateFilter(IconPackCatalogFilter()) })
      }
    }
  }
}

@Composable
private fun PackCatalogMenu(model: HomeViewModel, filter: IconPackCatalogFilter,
  onChange: (IconPackCatalogFilter) -> Unit, onOrderChange: () -> Unit) {
  val sort by model.packSort.collectAsStateWithLifecycle()
  val status = DropdownItem(text = stringResource(R.string.pack_list_filter_status),
    summary = stringResource(packListStatusLabel(filter.status)),
    children = IconPackCatalogStatus.entries.map { value ->
      DropdownItem(text = stringResource(packListStatusLabel(value)), selected = filter.status == value,
        onClick = { onChange(filter.copy(status = value)) })
    })
  val matches = DropdownItem(text = stringResource(R.string.pack_list_filter_matches),
    summary = stringResource(packListMatchesLabel(filter.matches)),
    children = IconPackCatalogMatches.entries.map { value ->
      DropdownItem(text = stringResource(packListMatchesLabel(value)), selected = filter.matches == value,
        onClick = { onChange(filter.copy(matches = value)) })
    })
  PerfectListSortMenu(
    labels = listOf(R.string.icon_pack_sort_name, R.string.icon_pack_sort_identity,
      R.string.icon_pack_sort_updated, R.string.icon_pack_sort_matched),
    selected = sort.first, reverse = sort.second,
    onSort = { index ->
      model.updatePackSort(index, model.packSort.value.second)
      onOrderChange()
    },
    onReverse = {
      model.updatePackSort(model.packSort.value.first, !model.packSort.value.second)
      onOrderChange()
    },
    modifier = Modifier.testTag("icon-packs.sort"), filtersActive = filter.isActive,
    filterEntries = listOf(
      DropdownEntry(items = listOf(status, matches)),
      DropdownEntry(items = listOf(DropdownItem(text = stringResource(R.string.pack_list_filter_reset),
        enabled = filter.isActive, onClick = { onChange(IconPackCatalogFilter()) }))),
    ),
  )
}

internal fun packListStatusLabel(status: IconPackCatalogStatus): Int = when (status) {
  IconPackCatalogStatus.ALL -> R.string.pack_list_filter_all
  IconPackCatalogStatus.CURRENT -> R.string.pack_list_filter_current
  IconPackCatalogStatus.OTHER -> R.string.pack_list_filter_other
}

internal fun packListMatchesLabel(matches: IconPackCatalogMatches): Int = when (matches) {
  IconPackCatalogMatches.ALL -> R.string.pack_list_filter_all
  IconPackCatalogMatches.MATCHED -> R.string.pack_list_filter_matched
  IconPackCatalogMatches.UNMATCHED -> R.string.pack_list_filter_unmatched
}

@Composable
internal fun packListFilterSummary(filter: IconPackCatalogFilter): String =
  listOfNotNull(
    filter.status.takeIf { it != IconPackCatalogStatus.ALL }?.let { stringResource(packListStatusLabel(it)) },
    filter.matches.takeIf { it != IconPackCatalogMatches.ALL }?.let { stringResource(packListMatchesLabel(it)) },
  ).joinToString(" · ").ifEmpty { stringResource(R.string.pack_list_filter_all) }
