package os.kei.hyper.home.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.icon.glass.Filter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.*
import os.kei.hyper.home.data.PackGuidancePage
import os.kei.hyper.home.data.ColorOsStyle
import os.kei.hyper.home.ui.components.*
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CustomIconsScreen(model: HomeViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
  val controller = model.customIcons
  val context = androidx.compose.ui.platform.LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = androidx.compose.ui.platform.LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(context, controller, lifecycle, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      val observer = os.kei.hyper.home.data.PackageChangeObserver(context,
        android.os.Handler(android.os.Looper.getMainLooper()), onChanged = controller::refreshApps)
      try { controller.load(); model.observePage(3, locales) } finally { observer.close() }
    }
  }
  val state by controller.state.collectAsStateWithLifecycle()
  val catalog by model.iconPacks.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val focus = androidx.compose.ui.platform.LocalFocusManager.current
  val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
  fun dismissSearch() { focus.clearFocus(); keyboard?.hide() }
  var targetPackage by rememberSaveable { mutableStateOf<String?>(null) }
  var packId by rememberSaveable { mutableStateOf<String?>(null) }
  var uncustomized by rememberSaveable { mutableStateOf(false) }
  var appOrigin by rememberSaveable { mutableIntStateOf(0) }
  var iconOrigin by rememberSaveable(targetPackage, packId) { mutableIntStateOf(0) }
  var rootQuery by rememberSaveable { mutableStateOf("") }
  var sourceQuery by rememberSaveable(targetPackage) { mutableStateOf("") }
  var iconQuery by rememberSaveable(targetPackage, packId) { mutableStateOf("") }
  var candidate by remember { mutableStateOf<IconPackEntry?>(null) }
  var removing by remember { mutableStateOf<CustomIconSelection?>(null) }
  var inspecting by remember { mutableStateOf<CustomIconSelection?>(null) }
  var inspectionVisible by remember { mutableStateOf(false) }
  val pack = catalog.packs.firstOrNull { it.pack.id == packId }?.pack
  val target = state.apps.firstOrNull { it.packageName == targetPackage }
  val root = targetPackage == null
  val query = when { root -> rootQuery; packId == null -> sourceQuery; else -> iconQuery }
  fun back() {
    dismissSearch()
    candidate = null
    when {
      packId != null -> packId = null
      targetPackage != null -> targetPackage = null
      else -> onBack()
    }
  }
  BackHandler(enabled = !root) { back() }
  LaunchedEffect(state.saved) {
    if (state.saved > 0) {
      candidate = null; targetPackage = null; packId = null; uncustomized = false; rootQuery = ""
    }
  }
  val title = stringResource(when {
    root -> R.string.custom_icons_title
    packId == null -> R.string.custom_icons_choose_pack
    else -> R.string.custom_icons_choose_icon
  })
  val rootList = rememberLazyListState()
  val sourceList = rememberLazyListState()
  val iconList = rememberLazyListState()
  val list = when { root -> rootList; packId == null -> sourceList; else -> iconList }
  LaunchedEffect(uncustomized, appOrigin) { rootList.scrollToItem(0) }
  LaunchedEffect(targetPackage) { sourceList.scrollToItem(0) }
  LaunchedEffect(targetPackage, packId, iconOrigin) { iconList.scrollToItem(0) }
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  val dark = LocalKeiHomeDarkTheme.current
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current.toString()
  val styleLabels = ColorOsStyle.entries.associate { it.key to stringResource(colorOsStyleLabel(it)) }
  val catalogIdentity = SnapshotIdentity(catalog.packs)
  val sources = remember(catalogIdentity, query, styleLabels) {
    catalog.packs.map { it.pack }.filter { it.entries.isNotEmpty() &&
      (it.displayName.contains(query, true) ||
        styleLabels[it.colorOsProfile?.style]?.contains(query, true) == true) }
      .sortedBy { it.source is IconPackSource.Builtin }
  }
  val launcherClass = target?.activeLauncherClass
  val previewScope = Triple(catalogIdentity, targetPackage, launcherClass)
  val sourceChoices by produceState(Pair<Any?, Map<String, CustomIconPreviewChoice>>(null, emptyMap()),
    catalogIdentity, targetPackage, launcherClass) {
    val name = targetPackage
    val index = withContext(Dispatchers.Default) {
      if (name == null) emptyMap() else catalog.packs.associate { source ->
        source.pack.id to customIconPreviewChoice(source.pack, IconPackComponent(name, launcherClass))
      }
    }
    value = previewScope to index
  }
  val choices = sourceChoices.second.takeIf { sourceChoices.first == previewScope }.orEmpty()
  val availableApps = remember(SnapshotIdentity(state.apps), SnapshotIdentity(state.selections),
    SnapshotIdentity(preferences.preservedIconPackages), state.showHidden, query) {
    val retained = preferences.preservedIconPackages + state.selections.map { it.packageName }
    state.apps.filter { (state.showHidden || !it.hiddenByDefault || it.packageName in retained) &&
      (it.label.contains(query, true) || it.packageName.contains(query, true)) }
  }
  val savedPackages = remember(SnapshotIdentity(state.selections)) { state.selections.mapTo(hashSetOf()) { it.packageName } }
  val newApps = remember(SnapshotIdentity(availableApps), SnapshotIdentity(savedPackages)) {
    availableApps.filter { it.packageName !in savedPackages }
  }
  val apps = remember(SnapshotIdentity(newApps), appOrigin) {
    newApps.filter { customIconMatchesOrigin(it.systemApp, appOrigin) }
  }
  val searchedSaved = remember(SnapshotIdentity(state.selections), query) {
    state.selections.filter { it.appLabel.contains(query, true) || it.packageName.contains(query, true) ||
      it.sourceTitle.contains(query, true) }
  }
  val savedRows = remember(SnapshotIdentity(searchedSaved), SnapshotIdentity(state.origins), appOrigin) {
    searchedSaved.filter { customIconMatchesOrigin(state.origins[it.packageName], appOrigin) }
  }
  val labels = remember(SnapshotIdentity(state.apps)) { state.apps.associate { it.packageName to it.label } }
  val entryIdentity = SnapshotIdentity(pack?.entries.orEmpty())
  val gridScope = remember(pack?.id, pack?.source, entryIdentity,
    SnapshotIdentity(state.origins), SnapshotIdentity(state.apps), targetPackage, launcherClass) { Any() }
  val matches = rememberLocalSearch(gridScope, query, enabled = !root && packId != null) { request ->
      val context = currentCoroutineContext()
      val origins = CustomIconOrigins(state.origins, state.apps)
      val filtered = pack?.entries.orEmpty().filter {
        context.ensureActive()
        val label = labels[it.component?.packageName ?: it.nativeName?.substringBefore('#')]
        it.key.contains(request.query, true) || it.component?.flattened?.contains(request.query, true) == true ||
          it.nativeName?.contains(request.query, true) == true || label?.contains(request.query, true) == true
      }
      val direct = targetPackage?.let { pkg -> pack?.let {
        customIconPreviewChoice(it, IconPackComponent(pkg, launcherClass)).match
      } }
      val ordered = if (direct != null && direct in filtered)
        listOf(direct) + filtered.filter { context.ensureActive(); it !== direct } else filtered
      val user = ArrayList<IconPackEntry>()
      val system = ArrayList<IconPackEntry>()
      ordered.forEach { entry -> context.ensureActive(); when (origins.system(entry)) {
        true -> system.add(entry)
        false -> user.add(entry)
        null -> Unit
      } }
      listOf(ordered, user, system)
  }
  // Retain search results while the worker computes; origin switching uses these cached buckets.
  val grid = matches.value ?: List(3) { emptyList() }
  val entries = grid[iconOrigin]
  val entryRows = remember(SnapshotIdentity(entries)) { entries.chunked(3) }
  Scaffold(backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = title, scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = { back() }) {
          Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
        } },
        actions = {
          if (root) OverlayIconCascadingDropdownMenu(entries = listOf(DropdownEntry(items = listOf(
            DropdownItem(text = stringResource(R.string.icon_inventory_show_hidden),
              selected = state.showHidden, onClick = { controller.setShowHidden(!state.showHidden) }),
          )))) {
            Icon(MiuixIcons.Glass.Filter, stringResource(R.string.icon_inventory_show_hidden),
              tint = if (state.showHidden) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground)
          }
          PackHelpAction(listOf(stringResource(R.string.custom_icons_summary),
            stringResource(R.string.custom_icons_help), stringResource(R.string.custom_icons_origin_help)),
            PackGuidancePage.CUSTOM_ICONS, revision = 1, onOpen = ::dismissSearch)
        }, bottomContent = {
          if (root) Os4ViewTabs(
            items = listOf(stringResource(R.string.custom_icons_saved, state.selections.size),
              stringResource(R.string.custom_icons_unset,
                state.apps.count { it.packageName !in savedPackages && (state.showHidden || !it.hiddenByDefault ||
                  it.packageName in preferences.preservedIconPackages) })),
            selectedIndex = if (uncustomized) 1 else 0, onSelect = { uncustomized = it == 1 },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("custom-icons.views"))
          else if (packId != null) CustomIconOriginTabs(grid.map { it.size }, iconOrigin,
            onSelect = { iconOrigin = it }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        })
    }
  }) { padding ->
    LazyColumn(state = list, overscrollEffect = null,
      modifier = Modifier.fillMaxSize().keiHomeTopBarScroll(behavior)
        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier).testTag("custom-icons.list"),
      contentPadding = PaddingValues(start = 18.dp, end = 18.dp,
        top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (root) item("origins", contentType = "origins") {
          val origins = if (uncustomized) newApps.map { it.systemApp }
            else searchedSaved.map { state.origins[it.packageName] }
          CustomIconOriginTabs(listOf(origins.size, origins.count { it == false }, origins.count { it == true }),
            appOrigin, onSelect = { appOrigin = it })
      }
      item("search", contentType = "search") {
        Column {
          Os4SearchField(query = query, onQueryChange = {
            when { root -> rootQuery = it; packId == null -> sourceQuery = it; else -> iconQuery = it }
          },
            modifier = Modifier.fillMaxWidth(), inputModifier = Modifier.testTag("custom-icons.search"),
            label = stringResource(if (root) R.string.preserved_icons_search else R.string.custom_icons_search),
            searching = matches.status.showProgress)
          if (!root && packId != null) LocalSearchError(matches.status)
        }
      }
      if (root) {
        if (uncustomized) items(apps, key = { it.packageName }, contentType = { "app" }) { app ->
          Card(Modifier.fillMaxWidth().animateItem(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(title = app.label, summary = app.packageName, enabled = state.ready && !state.busy,
              modifier = Modifier.testTag("custom-icons.app.${app.packageName}"),
              startAction = { OriginalPreservedIcon(app, controller.inventory, configuration) },
              onClick = { dismissSearch(); targetPackage = app.packageName })
          }
        } else items(savedRows, key = { it.packageName }, contentType = { "custom" }) { item ->
          val sourcePack = catalog.packs.firstOrNull { it.pack.id == item.sourceId }?.pack
          val sourceTitle = if (sourcePack?.source is IconPackSource.Builtin && sourcePack.colorOsProfile != null)
            "ColorOS · ${customIconSourceTitle(sourcePack)}" else item.sourceTitle
          Card(Modifier.fillMaxWidth().animateItem(), insideMargin = PaddingValues(0.dp)) {
            Row(Modifier.fillMaxWidth().testTag("custom-icons.saved.${item.packageName}")
              .clickable(enabled = !state.busy) { dismissSearch(); inspecting = item; inspectionVisible = true }
              .padding(14.dp), verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              SavedIconArtwork(controller, item, dark)
              Column(Modifier.weight(1f)) {
                Text(item.appLabel, style = MiuixTheme.textStyles.body1)
                Text(sourceTitle, style = MiuixTheme.textStyles.footnote1,
                  color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.packageName, style = MiuixTheme.textStyles.footnote2,
                  color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
              IconButton(onClick = { dismissSearch(); removing = item }, enabled = !state.busy && !state.loading) {
                Icon(actionIcon(PageActionIcon.CLEAR), stringResource(R.string.custom_icons_remove))
              }
            }
          }
        }
        if ((if (uncustomized) apps.isEmpty() else savedRows.isEmpty()) && !state.loading) item("empty") {
          Text(stringResource(if (!uncustomized && state.selections.isEmpty()) R.string.custom_icons_empty
            else R.string.custom_icons_no_results), Modifier.padding(12.dp),
            style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      } else {
        targetPackage?.let { name -> item("target") {
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(title = target?.label ?: name,
              summary = stringResource(R.string.custom_icons_original) + "\n" + name,
              startAction = { OriginalPreservedIcon(target, controller.inventory, configuration, 56.dp) })
          }
        } }
        when {
          packId == null -> {
            item("sources") {
              Text(stringResource(R.string.custom_icons_available), Modifier.padding(horizontal = 12.dp),
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            items(sources, key = { it.id }, contentType = { "source" }) { source ->
              val preview = choices[source.id]
              val explanation = stringResource(when {
                preview == null -> R.string.custom_icons_preview_loading
                preview.match != null -> R.string.custom_icons_source_matched
                else -> R.string.custom_icons_source_examples
              })
              val origin = if (source.colorOsProfile != null) "ColorOS · " else ""
              Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                BasicComponent(title = customIconSourceTitle(source),
                  summary = origin + stringResource(R.string.custom_icons_count, source.entries.size) + "\n" + explanation,
                  startAction = {
                    Row(Modifier.width(116.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                      verticalAlignment = Alignment.CenterVertically) {
                      if (preview?.match != null) SourceIconArtwork(controller, source, preview.match, dark, 56.dp)
                      else if (preview != null) preview.examples.forEach {
                        SourceIconArtwork(controller, source, it, dark, 36.dp)
                      } else Box(Modifier.size(36.dp))
                    }
                  },
                  enabled = !state.busy, onClick = { dismissSearch(); packId = source.id })
              }
            }
            item("get-packs") {
              Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                BasicComponent(title = stringResource(R.string.custom_icons_more),
                  onClick = { dismissSearch(); onNavigate("library") })
              }
            }
          }
          pack != null -> {
            items(entryRows, key = { it.first().key }, contentType = { "icons" }) { row ->
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { entry ->
                  Column(Modifier.weight(1f).background(MiuixTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .clickable(enabled = !state.busy) { dismissSearch(); candidate = entry }.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceIconArtwork(controller, pack, entry, dark)
                    Text(labels[entry.component?.packageName ?: entry.nativeName?.substringBefore('#')]
                      ?: entry.component?.flattened ?: entry.nativeName ?: entry.key,
                      style = MiuixTheme.textStyles.footnote2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                  }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
              }
            }
          }
          else -> item("unavailable") { Text(stringResource(R.string.custom_icons_source_missing)) }
        }
      }
      if (!state.ready || state.loading || state.busy || catalog.loading && !root) item("loading") {
        Text(stringResource(if (state.busy) R.string.custom_icons_saving else R.string.custom_icons_loading),
          Modifier.padding(12.dp), style = MiuixTheme.textStyles.body2)
      }
      if (state.failed) item("error") {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          BasicComponent(title = stringResource(R.string.custom_icons_error), onClick = controller::load)
        }
      }
    }
    inspecting?.let { item ->
      Os4DetailsSheet(show = inspectionVisible, title = item.appLabel,
        modifier = Modifier.testTag("custom-icons.details"), onDismissRequest = { inspectionVisible = false }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OriginalPreservedIcon(state.apps.firstOrNull { it.packageName == item.packageName }, controller.inventory,
              configuration, 64.dp)
            Text(stringResource(R.string.custom_icons_original), style = MiuixTheme.textStyles.footnote1)
          }
          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SavedIconArtwork(controller, item, dark, 64.dp)
            Text(stringResource(R.string.custom_icons_current), style = MiuixTheme.textStyles.footnote1)
          }
        }
        Text(item.packageName, Modifier.padding(top = 16.dp), style = MiuixTheme.textStyles.footnote1,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Text(stringResource(R.string.custom_icons_reapply), Modifier.padding(vertical = 12.dp),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Os4DialogButton(stringResource(R.string.custom_icons_edit), enabled = !state.busy,
          role = top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Primary, modifier = Modifier.fillMaxWidth(),
          onClick = { inspectionVisible = false; targetPackage = item.packageName })
        Os4DialogButton(stringResource(R.string.custom_icons_remove), enabled = !state.busy,
          modifier = Modifier.fillMaxWidth(), onClick = { inspectionVisible = false; removing = item })
      }
    }
    val selected = candidate
    Os4DetailsSheet(selected != null && pack != null, { if (!state.busy) candidate = null },
      title = stringResource(R.string.custom_icons_confirm),
      summary = stringResource(R.string.custom_icons_confirm_summary)) {
      if (selected != null && pack != null) {
        Text(target?.label ?: state.selections.firstOrNull { it.packageName == targetPackage }?.appLabel
          ?: targetPackage.orEmpty(), style = MiuixTheme.textStyles.body1)
        Text(targetPackage.orEmpty(), style = MiuixTheme.textStyles.footnote2)
        val sourceTitle = (if (pack.source is IconPackSource.Builtin && pack.colorOsProfile != null) "ColorOS · " else "") +
          customIconSourceTitle(pack)
        Text(sourceTitle, style = MiuixTheme.textStyles.footnote1)
        if (targetPackage in preferences.preservedIconPackages) {
          Text(stringResource(R.string.custom_icons_conflict_preserved), style = MiuixTheme.textStyles.body2)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OriginalPreservedIcon(target, controller.inventory, configuration, 60.dp)
            Text(stringResource(R.string.custom_icons_original), style = MiuixTheme.textStyles.footnote2)
          }
          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceIconArtwork(controller, pack, selected, false)
            Text(stringResource(R.string.monet_preview_light), style = MiuixTheme.textStyles.footnote2)
          }
          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceIconArtwork(controller, pack, selected, true)
            Text(stringResource(R.string.monet_preview_dark), style = MiuixTheme.textStyles.footnote2)
          }
        }
        if (state.failed) Text(stringResource(R.string.custom_icons_error), style = MiuixTheme.textStyles.body2)
        Button(onClick = { targetPackage?.let { name ->
          controller.save(name, target?.label ?: state.selections.firstOrNull { it.packageName == name }?.appLabel ?: name,
            pack, selected, sourceTitle)
        } }, enabled = state.ready && !state.busy && !state.loading && targetPackage != null,
          modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(when {
            state.busy -> R.string.custom_icons_saving
            targetPackage in preferences.preservedIconPackages -> R.string.custom_icons_conflict_save_action
            else -> R.string.custom_icons_confirm
          }))
        }
      }
    }
    Os4DetailsSheet(removing != null, { removing = null }, title = stringResource(R.string.custom_icons_remove),
      summary = stringResource(R.string.custom_icons_remove_summary)) {
      removing?.let { Text(it.appLabel, style = MiuixTheme.textStyles.body1) }
      Button(onClick = { removing?.let { controller.remove(it.packageName) }; removing = null },
        modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text(stringResource(R.string.custom_icons_remove)) }
    }
  }
}

@Composable
private fun CustomIconOriginTabs(counts: List<Int>, selected: Int, onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier) {
  Os4ViewTabs(items = listOf(R.string.browser_all, R.string.browser_badge_user, R.string.browser_badge_system)
    .mapIndexed { index, label -> "${stringResource(label)} (${counts[index]})" },
    selectedIndex = selected, onSelect = onSelect, modifier = modifier.fillMaxWidth().testTag("custom-icons.origins"))
}

@Composable
private fun SourceIconArtwork(controller: CustomIconsController, pack: IconPackDescriptor,
  entry: IconPackEntry, dark: Boolean, iconSize: androidx.compose.ui.unit.Dp = 60.dp) {
  val key = SourceIconPreviewKey(pack.id, pack.source, entry.key, dark)
  val result by produceState<Pair<SourceIconPreviewKey, android.graphics.Bitmap?>?>(null, key) {
    val bitmap = try { controller.preview(pack, entry, dark) }
      catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (_: Exception) { null }
    value = key to bitmap
  }
  CustomIconArtwork(result?.takeIf { it.first == key }?.second, iconSize)
}

private data class SourceIconPreviewKey(val pack: String, val source: IconPackSource, val entry: String, val dark: Boolean)

@Composable
private fun SavedIconArtwork(controller: CustomIconsController, item: CustomIconSelection, dark: Boolean,
  iconSize: androidx.compose.ui.unit.Dp = 60.dp) {
  val key = item.token to dark
  val result by produceState<Pair<Pair<String, Boolean>, android.graphics.Bitmap?>?>(null, key) {
    val bitmap = try { controller.preview(item, dark) }
      catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (_: Exception) { null }
    value = key to bitmap
  }
  CustomIconArtwork(result?.takeIf { it.first == key }?.second, iconSize)
}

@Composable
private fun CustomIconArtwork(bitmap: android.graphics.Bitmap?, iconSize: androidx.compose.ui.unit.Dp = 60.dp) {
  if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(iconSize))
  else Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
    Icon(actionIcon(PageActionIcon.PACKS), contentDescription = null)
  }
}

@Composable
private fun customIconSourceTitle(pack: IconPackDescriptor): String {
  val profile = pack.colorOsProfile ?: return pack.displayName
  if (pack.source !is IconPackSource.Builtin) return pack.displayName
  if (profile.builtin?.key == "coloros-runtime-fallback") return stringResource(R.string.system_fallback_title)
  val style = ColorOsStyle.entries.firstOrNull { it.key == profile.style } ?: return pack.displayName
  val title = stringResource(colorOsStyleLabel(style))
  return if (profile.artPlus == true) stringResource(R.string.custom_icons_style_art_on, title) else title
}
