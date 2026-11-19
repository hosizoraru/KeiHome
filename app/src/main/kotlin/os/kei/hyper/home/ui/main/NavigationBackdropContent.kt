package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.util.Locale
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.BILIBILI_PACKAGE
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.R
import os.kei.hyper.home.data.NavigationBackdropPreferences
import os.kei.hyper.home.status.NavigationBackdropStatus
import os.kei.hyper.home.status.BilibiliArtworkOutcome
import os.kei.hyper.home.status.BilibiliArtworkReceipt
import os.kei.hyper.home.status.BilibiliInstallation
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4LazyColumn
import os.kei.hyper.home.ui.components.Os4ViewTabs
import os.kei.hyper.home.ui.components.OverlayIconCascadingDropdownMenu
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import os.kei.hyper.home.ui.components.os4Toggleable
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.Filter
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.Sort
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class NavigationBackdropPage(val route: String, val title: Int) {
  OVERVIEW("", R.string.nav_backdrop_title),
  APPS("apps", R.string.nav_backdrop_apps),
}

@Composable
internal fun NavigationBackdropContent(
  page: NavigationBackdropPage,
  state: NavigationBackdropUiState,
  backend: NavigationBackdropStatus?,
  onBack: () -> Unit,
  onOpen: (NavigationBackdropPage) -> Unit,
  onEnabled: (Boolean) -> Unit,
  onToggle: (String) -> Unit,
  onRefresh: () -> Unit,
  onBilibiliArtwork: (Boolean) -> Unit = {},
  onOpenManager: () -> Unit = {},
  bilibili: BilibiliInstallation? = null,
  artworkReceipt: BilibiliArtworkReceipt? = null,
  appIcon: @Composable (InstalledIconCapability?) -> Unit = {},
) {
  val choice = state.choice
  val selected = choice?.selectedPackages.orEmpty()
  val currentBackend = backend?.takeIf { it.version == BuildConfig.VERSION_CODE.toLong() }
  val supported = currentBackend?.available == true
  val acknowledged = choice != null && currentBackend?.choice == choice.fingerprint()
  val editable = choice != null && !state.unavailable && !state.loading && !state.saving && !state.failed
  val statusText = when {
    state.unavailable -> R.string.nav_backdrop_scope_required
    state.loading -> R.string.nav_backdrop_reading
    state.failed -> R.string.nav_backdrop_failed
    currentBackend == null -> R.string.nav_backdrop_backend_pending
    !supported -> R.string.nav_backdrop_backend_unsupported
    !acknowledged || state.saving -> R.string.nav_backdrop_waiting
    else -> R.string.nav_backdrop_backend_ready
  }
  val statusSummary = when (statusText) {
    R.string.nav_backdrop_backend_ready -> R.string.nav_backdrop_ready_short
    R.string.nav_backdrop_backend_unsupported -> R.string.nav_backdrop_unsupported_short
    R.string.nav_backdrop_backend_pending -> R.string.nav_backdrop_pending_short
    R.string.nav_backdrop_waiting -> R.string.nav_backdrop_waiting_short
    R.string.nav_backdrop_scope_required -> R.string.nav_backdrop_scope_short
    else -> statusText
  }
  val isApps = page == NavigationBackdropPage.APPS
  var helpOpen by rememberSaveable { mutableStateOf(false) }
  var searchVisible by rememberSaveable { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }
  var selectedOnly by rememberSaveable { mutableStateOf(false) }
  var origin by rememberSaveable { mutableStateOf(AppOrigin.ALL) }
  var sort by rememberSaveable { mutableStateOf(AppSort.NAME) }
  var reverse by rememberSaveable { mutableStateOf(false) }
  val localeTag = androidx.compose.ui.text.intl.Locale.current.toLanguageTag()
  val collator = remember(localeTag) { Collator.getInstance(Locale.forLanguageTag(localeTag)) }
  val installed = remember(state.apps) { state.apps.mapTo(HashSet()) { it.packageName } }
  val artworkOn = choice?.bilibiliArtwork == true
  val bilibiliInstalled = bilibili != null || BILIBILI_PACKAGE in installed
  val apps = remember(state.apps, selected, installed) {
    state.apps + (selected - installed).map { NavigationBackdropApp(it, it) }
  }
  val sorted = remember(isApps, apps, collator, sort, reverse) {
    if (!isApps) emptyList() else {
      val byName = Comparator<NavigationBackdropApp> { a, b -> collator.compare(a.label, b.label) }
        .thenBy { it.packageName }
      val comparator = when (sort) {
        AppSort.NAME -> byName
        AppSort.PACKAGE -> compareBy { it.packageName }
        AppSort.UPDATED -> compareByDescending<NavigationBackdropApp> { it.artwork?.updatedAt ?: 0L }
          .then(byName)
      }
      apps.sortedWith(if (reverse) comparator.reversed() else comparator)
    }
  }
  val filtered = remember(sorted, selected, installed, query, selectedOnly, origin) {
    val text = query.trim()
    sorted.filter { app ->
      val matchesOrigin = when (origin) {
        AppOrigin.ALL -> true
        AppOrigin.USER -> app.packageName in installed && !app.systemApp
        AppOrigin.SYSTEM -> app.systemApp
      }
      matchesOrigin && (!selectedOnly || app.packageName in selected) &&
        (text.isEmpty() || app.label.contains(text, true) || app.packageName.contains(text, true))
    }
  }
  val list = rememberLazyListState()
  val revealSearch = {
    searchVisible = true
    list.requestScrollToItem(0)
  }
  val threshold = with(LocalDensity.current) { 64.dp.toPx() }
  val pullToSearch = remember(threshold, list) {
    object : NestedScrollConnection {
      private var pull = 0f

      override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (available.y <= 0f || list.canScrollBackward) {
          pull = 0f
          return Offset.Zero
        }
        pull += available.y
        if (pull >= threshold) {
          pull = 0f
          revealSearch()
        }
        return Offset(0f, available.y)
      }

      override suspend fun onPreFling(available: Velocity): Velocity {
        pull = 0f
        return Velocity.Zero
      }
    }
  }
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  KeiHomeScaffold(backdrop = backdrop,
    modifier = Modifier.testTag(if (page == NavigationBackdropPage.OVERVIEW) "desktop.page.navigation"
      else "nav-backdrop.page.${page.route}"), topBar = {
    BlurredBar(backdrop, backdrop != null) {
      KeiHomeTopAppBar(title = stringResource(page.title), scrollBehavior = behavior,
        expandable = true,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = onBack) {
          Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
        } },
        actions = {
          if (isApps) {
            IconButton(onClick = { revealSearch() }, modifier = Modifier.testTag("nav-backdrop.open.search")) {
              Icon(MiuixIcons.Glass.Search, stringResource(R.string.nav_backdrop_search))
            }
            NavigationBackdropAppMenus(origin, sort, reverse, canRefresh = !state.loading && !state.saving,
              onOrigin = { origin = it; list.requestScrollToItem(0) },
              onSort = { sort = it; list.requestScrollToItem(0) },
              onReverse = { reverse = !reverse; list.requestScrollToItem(0) }, onRefresh = onRefresh)
          } else IconButton(modifier = Modifier.testTag("nav-backdrop.help"), onClick = { helpOpen = true }) {
            Icon(MiuixIcons.Glass.Info, stringResource(R.string.nav_backdrop_help))
          }
        },
        bottomContent = {
          if (page == NavigationBackdropPage.APPS) Os4ViewTabs(
            items = listOf(stringResource(R.string.nav_backdrop_all, apps.size),
              stringResource(R.string.nav_backdrop_saved, selected.size)),
            selectedIndex = if (selectedOnly) 1 else 0,
            onSelect = { selectedOnly = it == 1; list.requestScrollToItem(0) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("nav-backdrop.views"))
        })
    }
  }) { padding ->
    Os4LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("nav-backdrop.list")
      .keiHomeTopBarScroll(behavior, overScrollEnabled = !isApps || searchVisible)
      .then(if (isApps && !searchVisible) Modifier.nestedScroll(pullToSearch) else Modifier)
      .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
      contentPadding = PaddingValues(start = 12.dp, end = 12.dp,
        top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)) {
      when (page) {
        NavigationBackdropPage.OVERVIEW -> {
          item("status", contentType = "status") {
            StatusOverviewCard(title = stringResource(
              if (statusSummary != statusText) statusSummary else R.string.nav_backdrop_backend),
              summary = stringResource(statusText),
              details = emptyList(), tone = when (statusText) {
                R.string.nav_backdrop_backend_ready -> StatusTone.READY
                R.string.nav_backdrop_reading, R.string.nav_backdrop_waiting -> StatusTone.CHECKING
                R.string.nav_backdrop_failed -> StatusTone.ERROR
                else -> StatusTone.WARNING
              }, titleBadge = if (statusSummary != statusText) stringResource(R.string.nav_backdrop_backend) else null,
              badgeContent = bilibili?.let { host ->
                { color ->
                  val receipt = artworkReceipt?.takeIf { it.matches(host) }
                  val label = when {
                    state.bilibiliInScope == false -> R.string.nav_backdrop_artwork_needs_scope
                    choice == null || state.bilibiliInScope == null -> R.string.nav_backdrop_artwork_waiting
                    !artworkOn -> R.string.nav_backdrop_artwork_off
                    receipt?.outcome == BilibiliArtworkOutcome.DRAWN -> R.string.nav_backdrop_artwork_drawn
                    receipt?.outcome == BilibiliArtworkOutcome.FALLBACK -> R.string.nav_backdrop_artwork_fallback
                    receipt?.outcome == BilibiliArtworkOutcome.UNSUPPORTED -> R.string.nav_backdrop_artwork_unsupported
                    else -> R.string.nav_backdrop_artwork_waiting
                  }
                  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetadataPill(stringResource(R.string.nav_backdrop_bilibili_version,
                      host.name?.takeIf(String::isNotBlank) ?: host.version.toString()), color,
                      Modifier.testTag("nav-backdrop.bilibili.version"))
                    MetadataPill(stringResource(label), color, Modifier.testTag("nav-backdrop.bilibili.outcome"))
                  }
                }
              },
              onRefresh = { if (!state.loading && !state.saving) onRefresh() },
              modifier = Modifier.testTag("nav-backdrop.status"))
          }
          section("experiment", R.string.nav_backdrop_experimental)
          item("enable", contentType = "setting") {
            val enabled = editable && (supported || choice.enabled)
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              SwitchPreference(title = stringResource(R.string.nav_backdrop_enable),
                summary = stringResource(R.string.nav_backdrop_enable_summary),
                checked = choice?.enabled == true, enabled = enabled,
                onCheckedChange = onEnabled, modifier = Modifier.testTag("nav-backdrop.enabled")
                  .semantics { if (!enabled) disabled() })
            }
          }
          hint("restart", R.string.nav_backdrop_restart_hint)
          item("settings", contentType = "settings") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              NavigationBackdropLink(R.string.nav_backdrop_apps,
                stringResource(R.string.nav_backdrop_selection_count, selected.size),
                "nav-backdrop.open.apps") { onOpen(NavigationBackdropPage.APPS) }
            }
          }
          if (bilibiliInstalled || artworkOn) {
            item("bilibili", contentType = "setting") {
              val hasRoom = BILIBILI_PACKAGE in selected || selected.size < NavigationBackdropPreferences.MAX_PACKAGES
              val enabled = editable && (artworkOn ||
                (supported && state.bilibiliInScope == true && hasRoom && bilibiliInstalled))
              val summary = when {
                state.bilibiliInScope == false -> R.string.nav_backdrop_bilibili_scope
                !hasRoom -> R.string.nav_backdrop_bilibili_limit
                else -> R.string.nav_backdrop_bilibili_summary
              }
              Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                SwitchPreference(title = stringResource(R.string.nav_backdrop_bilibili),
                  summary = stringResource(summary), checked = artworkOn, enabled = enabled,
                  onCheckedChange = onBilibiliArtwork, modifier = Modifier.testTag("nav-backdrop.bilibili")
                    .semantics { if (!enabled) disabled() })
                if (state.bilibiliInScope == false && bilibiliInstalled) {
                  NavigationBackdropLink(R.string.scope_open_manager, null, "nav-backdrop.bilibili.scope",
                    onOpenManager)
                }
              }
            }
          }
        }
        NavigationBackdropPage.APPS -> {
          if (searchVisible) item("search", contentType = "search") {
            Os4SearchField(query, onQueryChange = { query = it }, label = stringResource(R.string.nav_backdrop_search),
              inputModifier = Modifier.testTag("nav-backdrop.search"))
          }
          hint("selection", R.string.nav_backdrop_selected, NavigationBackdropPreferences.MAX_PACKAGES)
          if (!editable || (choice.enabled && !supported)) hint("status", statusText)
          if (filtered.isEmpty()) hint("empty", when {
            state.loading -> R.string.nav_backdrop_reading
            selectedOnly && selected.isEmpty() && query.isBlank() -> R.string.nav_backdrop_none_selected
            else -> R.string.nav_backdrop_empty
          })
          items(filtered, key = { it.packageName }, contentType = { "app" }) { app ->
            val checked = app.packageName in selected
            val bound = artworkOn && app.packageName == BILIBILI_PACKAGE
            val enabled = editable && !bound && (!choice.enabled || supported) &&
              (checked || selected.size < NavigationBackdropPreferences.MAX_PACKAGES)
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = app.label,
                summary = if (bound) stringResource(R.string.nav_backdrop_bilibili_bound) else app.packageName,
                modifier = Modifier.testTag("nav-backdrop.app:${app.packageName}")
                  .os4Toggleable(checked, enabled = enabled, role = Role.Checkbox,
                    onValueChange = { onToggle(app.packageName) }),
                startAction = { appIcon(app.artwork) },
                endActions = {
                  Checkbox(if (checked) ToggleableState.On else ToggleableState.Off, onClick = null,
                    enabled = enabled, modifier = Modifier.clearAndSetSemantics {})
                })
            }
          }
        }
      }
    }
    if (!isApps) Os4DetailsSheet(helpOpen, onDismissRequest = { helpOpen = false },
      title = stringResource(R.string.nav_backdrop_help),
      summary = stringResource(R.string.nav_backdrop_explanation),
      modifier = Modifier.testTag("nav-backdrop.help.sheet")) {
      SmallTitle(stringResource(R.string.nav_backdrop_how_to))
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        BasicComponent(title = stringResource(R.string.nav_backdrop_step_select),
          summary = stringResource(R.string.nav_backdrop_step_select_summary))
        BasicComponent(title = stringResource(R.string.nav_backdrop_step_enable),
          summary = stringResource(R.string.nav_backdrop_enable_summary))
        BasicComponent(title = stringResource(R.string.nav_backdrop_restart_title),
          summary = stringResource(R.string.nav_backdrop_restart))
      }
      SmallTitle(stringResource(R.string.nav_backdrop_limits))
      Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nav_backdrop_limits_summary), style = MiuixTheme.textStyles.body2)
        Text(stringResource(R.string.nav_backdrop_ack_hint), style = MiuixTheme.textStyles.body2)
      }
    }
  }
}

private enum class AppOrigin(val title: Int) {
  ALL(R.string.browser_origin_all), USER(R.string.browser_origin_user), SYSTEM(R.string.browser_origin_system),
}

private enum class AppSort(val title: Int) {
  NAME(R.string.browser_sort_name), PACKAGE(R.string.browser_sort_package), UPDATED(R.string.browser_sort_updated),
}

@Composable
private fun NavigationBackdropAppMenus(origin: AppOrigin, sort: AppSort, reverse: Boolean, canRefresh: Boolean,
  onOrigin: (AppOrigin) -> Unit, onSort: (AppSort) -> Unit, onReverse: () -> Unit, onRefresh: () -> Unit,
) {
  val origins = DropdownEntry(items = AppOrigin.entries.map { value ->
    DropdownItem(text = stringResource(value.title), selected = origin == value, onClick = { onOrigin(value) })
  })
  val refresh = DropdownEntry(items = listOf(DropdownItem(text = stringResource(R.string.nav_backdrop_refresh_apps),
    onClick = onRefresh)))
  OverlayIconCascadingDropdownMenu(entries = if (canRefresh) listOf(origins, refresh) else listOf(origins),
    modifier = Modifier.testTag("nav-backdrop.filter")) {
    Icon(MiuixIcons.Glass.Filter, stringResource(R.string.preserved_icons_filter, stringResource(origin.title)),
      tint = if (origin == AppOrigin.ALL) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.primary)
  }
  val orders = DropdownEntry(items = AppSort.entries.map { value ->
    DropdownItem(text = stringResource(value.title), selected = sort == value, onClick = { onSort(value) })
  })
  val direction = DropdownEntry(items = listOf(DropdownItem(text = stringResource(R.string.browser_sort_reverse),
    selected = reverse, onClick = onReverse)))
  OverlayIconCascadingDropdownMenu(entries = listOf(orders, direction), modifier = Modifier.testTag("nav-backdrop.sort")) {
    Icon(MiuixIcons.Glass.Sort, stringResource(R.string.preserved_icons_sort, stringResource(sort.title)),
      tint = if (sort == AppSort.NAME && !reverse) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.primary)
  }
}

@Composable
private fun NavigationBackdropLink(title: Int, summary: String?, tag: String, onClick: () -> Unit) {
  BasicComponent(title = stringResource(title), summary = summary, onClick = onClick,
    modifier = Modifier.testTag(tag),
    endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) })
}

private fun LazyListScope.section(key: String, title: Int) {
  item("section:$key", contentType = "section") {
    SmallTitle(stringResource(title), modifier = Modifier.semantics { heading() })
  }
}

private fun LazyListScope.hint(key: String, text: Int, vararg args: Any) {
  item("hint:$key", contentType = "hint") {
    Text(stringResource(text, *args), style = MiuixTheme.textStyles.footnote1,
      color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
  }
}
