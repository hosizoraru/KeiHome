package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import os.kei.hyper.home.R
import os.kei.hyper.home.data.PackageChangeObserver
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.Os4ViewTabs
import os.kei.hyper.home.ui.components.OverlayIconCascadingDropdownMenu
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import os.kei.hyper.home.ui.components.os4Toggleable
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.Filter
import top.yukonga.miuix.kmp.icon.glass.GridView
import top.yukonga.miuix.kmp.icon.glass.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar

private data class PreservedAppsState(
  val apps: List<PreservedIconApp> = emptyList(),
  val loading: Boolean = true,
  val failed: Boolean = false,
)

@Composable
internal fun PreservedIconsScreen(model: HomeViewModel, onBack: () -> Unit) {
  val prefs by model.preferences.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val inventory = remember(context) { PreservedIconInventory(context) }
  val configuration = LocalConfiguration.current
  val configurationKey = configuration.toString()
  val localeTag = androidx.compose.ui.text.intl.Locale.current.toLanguageTag()
  val locale = remember(localeTag) { Locale.forLanguageTag(localeTag) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var retry by remember { mutableIntStateOf(0) }
  val state by produceState(PreservedAppsState(), inventory, lifecycle, configurationKey, retry) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      callbackFlow<Unit> {
        val observer = PackageChangeObserver(context, Handler(Looper.getMainLooper())) { trySend(Unit) }
        trySend(Unit)
        awaitClose { observer.close() }
      }.conflate().collect {
        value = value.copy(loading = true, failed = false)
        value = try {
          PreservedAppsState(inventory.read(locale), loading = false)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          value.copy(loading = false, failed = true)
        }
      }
    }
  }
  PreservedIconsPage(state.apps, prefs.preservedIconPackages, state.loading, state.failed,
    onToggle = { pkg ->
      val current = model.preferences.state.value.preservedIconPackages
      model.setPreservedIconPackages(if (pkg in current) current - pkg else current + pkg)
    }, onRetry = { retry++ }, onBack = onBack,
    appIcon = { app -> OriginalPreservedIcon(app, inventory, configurationKey) })
}

@Composable
internal fun PreservedIconsPage(
  apps: List<PreservedIconApp>,
  preserved: Set<String>,
  loading: Boolean,
  failed: Boolean,
  onToggle: (String) -> Unit,
  onRetry: () -> Unit,
  onBack: () -> Unit,
  appIcon: @Composable (PreservedIconApp?) -> Unit = { PreservedIconPlaceholder() },
) {
  var query by rememberSaveable { mutableStateOf("") }
  var allApps by rememberSaveable { mutableStateOf(false) }
  var origin by rememberSaveable { mutableIntStateOf(0) }
  var sort by rememberSaveable { mutableIntStateOf(0) }
  var reverse by rememberSaveable { mutableStateOf(false) }
  val localeTag = androidx.compose.ui.text.intl.Locale.current.toLanguageTag()
  val collator = remember(localeTag) { Collator.getInstance(Locale.forLanguageTag(localeTag)) }
  val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }
  val packages = remember(apps, preserved, allApps) {
    if (allApps) apps.map { it.packageName } + (preserved - appsByPackage.keys).sorted()
    else apps.filter { it.packageName in preserved }.map { it.packageName } +
      (preserved - appsByPackage.keys).sorted()
  }
  val filtered = remember(packages, appsByPackage, query, origin, sort, reverse, collator) {
    val search = query.trim()
    packages.filter { pkg ->
      val app = appsByPackage[pkg]
      val matchesOrigin = app == null || when (origin) {
        1 -> !app.systemApp
        2 -> app.systemApp
        else -> true
      }
      matchesOrigin && (search.isEmpty() || pkg.contains(search, ignoreCase = true) ||
        app?.label?.contains(search, ignoreCase = true) == true)
    }.sortedWith { left, right ->
      val a = appsByPackage[left]
      val b = appsByPackage[right]
      // Missing saved packages stay reachable at the end in either direction.
      when {
        a == null && b != null -> 1
        a != null && b == null -> -1
        else -> {
          val order = when (sort) {
            1 -> left.compareTo(right)
            2 -> (b?.updatedAt ?: 0L).compareTo(a?.updatedAt ?: 0L)
            3 -> (b?.installedAt ?: 0L).compareTo(a?.installedAt ?: 0L)
            else -> collator.compare(a?.label ?: left, b?.label ?: right)
          }
          val tied = order.takeIf { it != 0 }
            ?: collator.compare(a?.label ?: left, b?.label ?: right).takeIf { it != 0 }
            ?: left.compareTo(right)
          if (reverse) -tied.compareTo(0) else tied.compareTo(0)
        }
      }
    }
  }
  val list = rememberLazyListState()
  LaunchedEffect(list) {
    // Preserve restored child-return position; reset only for a new search/filter.
    snapshotFlow { Triple(query, allApps to origin, sort to reverse) }.drop(1)
      .collect { list.scrollToItem(0) }
  }
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(backdrop = backdrop, modifier = Modifier.testTag("preserved-icons.page"), topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(R.string.preserved_icons_title), scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = onBack) {
          Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
        } }, actions = {
          PreservedIconsFilter(origin, onOriginChange = { origin = it }, onReset = { origin = 0 })
          PreservedIconsSort(sort, reverse, onSortChange = { sort = it },
            onReverseChange = { reverse = !reverse })
        }, bottomContent = {
          Os4ViewTabs(
            items = listOf(stringResource(R.string.preserved_icons_saved, preserved.size),
              stringResource(R.string.preserved_icons_all)),
            selectedIndex = if (allApps) 1 else 0,
            onSelect = { allApps = it == 1 },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
              .testTag("preserved-icons.views"),
          )
        })
    }
  }) { padding ->
    LazyColumn(overscrollEffect = null, state = list, modifier = Modifier.fillMaxSize()
      .keiHomeTopBarScroll(behavior)
      .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
      .testTag("preserved-icons.list"),
      contentPadding = PaddingValues(start = 12.dp, end = 12.dp,
        top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)) {
      item("search", contentType = "search") {
        Os4SearchField(query = query, onQueryChange = { query = it },
          label = stringResource(R.string.preserved_icons_search),
          modifier = Modifier.fillMaxWidth(), inputModifier = Modifier.testTag("preserved-icons.search"))
      }
      item("guidance", contentType = "guidance") {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.preserved_icons_explanation), style = MiuixTheme.textStyles.footnote1,
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
            if (loading || failed) {
              Text(stringResource(if (loading) R.string.preserved_icons_loading else R.string.preserved_icons_error),
                modifier = Modifier.testTag("preserved-icons.status"), style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
              if (failed) TextButton(stringResource(R.string.preserved_icons_retry), onClick = onRetry,
                modifier = Modifier.testTag("preserved-icons.retry"))
            }
          }
        }
      }
      if (filtered.isEmpty() && !loading && !failed) item("empty", contentType = "empty") {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(when {
              query.isNotBlank() -> R.string.preserved_icons_no_matches
              allApps -> R.string.preserved_icons_no_apps
              else -> R.string.preserved_icons_empty
            }), modifier = Modifier.testTag("preserved-icons.empty"), style = MiuixTheme.textStyles.body2)
            if (!allApps && query.isBlank()) TextButton(stringResource(R.string.preserved_icons_add),
              onClick = { allApps = true }, modifier = Modifier.testTag("preserved-icons.add"))
          }
        }
      }
      items(filtered, key = { "app:$it" }, contentType = { "app" }) { pkg ->
        val app = appsByPackage[pkg]
        val checked = pkg in preserved
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          BasicComponent(title = app?.label ?: pkg,
            summary = if (app != null) pkg else stringResource(when {
              loading -> R.string.preserved_icons_checking
              failed -> R.string.preserved_icons_unavailable
              else -> R.string.preserved_icons_uninstalled
            }), modifier = Modifier.testTag("preserved-icons.app:$pkg")
              .os4Toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle(pkg) }),
            startAction = { appIcon(app) },
            endActions = { Checkbox(if (checked) ToggleableState.On else ToggleableState.Off, onClick = null,
              modifier = Modifier.clearAndSetSemantics {}) })
        }
      }
    }
  }
}

@Composable
private fun PreservedIconsFilter(origin: Int,
  onOriginChange: (Int) -> Unit, onReset: () -> Unit) {
  val origins = listOf(R.string.browser_origin_all, R.string.browser_origin_user, R.string.browser_origin_system)
  val sources = DropdownEntry(items = listOf(
    DropdownItem(text = stringResource(R.string.browser_origin),
      children = origins.mapIndexed { index, label ->
        DropdownItem(text = stringResource(label), selected = origin == index, onClick = { onOriginChange(index) })
      }),
  ))
  val reset = DropdownEntry(items = listOf(
    DropdownItem(text = stringResource(R.string.browser_reset), onClick = onReset),
  ))
  OverlayIconCascadingDropdownMenu(
    entries = listOf(sources, reset),
    modifier = Modifier.testTag("preserved-icons.filter"),
  ) {
    Icon(MiuixIcons.Glass.Filter,
      contentDescription = stringResource(R.string.preserved_icons_filter,
        stringResource(origins[origin.coerceIn(origins.indices)])),
      tint = if (origin == 0) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.primary)
  }
}

@Composable
private fun PreservedIconsSort(sort: Int, reverse: Boolean, onSortChange: (Int) -> Unit, onReverseChange: () -> Unit) {
  val labels = listOf(R.string.browser_sort_name, R.string.browser_sort_package,
    R.string.browser_sort_updated, R.string.preserved_icons_sort_installed)
  val choices = DropdownEntry(items = labels.mapIndexed { index, label ->
    DropdownItem(text = stringResource(label), selected = sort == index, onClick = { onSortChange(index) })
  })
  val direction = DropdownEntry(items = listOf(
    DropdownItem(text = stringResource(R.string.browser_sort_reverse), selected = reverse, onClick = onReverseChange),
  ))
  OverlayIconCascadingDropdownMenu(entries = listOf(choices, direction),
    modifier = Modifier.testTag("preserved-icons.sort")) {
    Icon(MiuixIcons.Glass.Sort,
      contentDescription = stringResource(R.string.preserved_icons_sort, stringResource(labels[sort.coerceIn(labels.indices)])),
      tint = if (sort != 0 || reverse) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground)
  }
}

@Composable
private fun OriginalPreservedIcon(app: PreservedIconApp?, inventory: PreservedIconInventory, configuration: String) {
  val size = with(LocalDensity.current) { 44.dp.roundToPx().coerceIn(48, 192) }
  val dark = LocalKeiHomeDarkTheme.current
  val bitmap by produceState<Bitmap?>(null, app, configuration, size, dark) {
    value = null
    if (app != null) value = inventory.icon(app, configuration, size, dark)
  }
  Box(Modifier.size(44.dp)) {
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
    else PreservedIconPlaceholder()
  }
}

@Composable
private fun PreservedIconPlaceholder() {
  Icon(MiuixIcons.Glass.GridView, contentDescription = null, modifier = Modifier.size(44.dp),
    tint = MiuixTheme.colorScheme.onSurfaceSecondary)
}
