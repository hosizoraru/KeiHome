package os.kei.hyper.home.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import os.kei.hyper.home.ui.components.Os4LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextAlign
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
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.squircle.squircleBackground
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
  var showCandidate by remember { mutableStateOf(false) }
  var removing by remember { mutableStateOf<CustomIconSelection?>(null) }
  var showRemoving by remember { mutableStateOf(false) }
  var inspecting by remember { mutableStateOf<CustomIconSelection?>(null) }
  var inspectionVisible by remember { mutableStateOf(false) }
  var afterInspection by remember { mutableStateOf<(() -> Unit)?>(null) }
  val pack = catalog.packs.firstOrNull { it.pack.id == packId }?.pack
  val target = state.apps.firstOrNull { it.packageName == targetPackage }
  val root = targetPackage == null
  val query = when { root -> rootQuery; packId == null -> sourceQuery; else -> iconQuery }
  fun back() {
    dismissSearch()
    showCandidate = false
    when {
      packId != null -> packId = null
      targetPackage != null -> targetPackage = null
      else -> onBack()
    }
  }
  BackHandler(enabled = !root) { back() }
  LaunchedEffect(state.saved) {
    if (state.saved > 0) {
      showCandidate = false; targetPackage = null; packId = null; uncustomized = false; rootQuery = ""
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
          Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back))
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
    val depth = when {
      root -> 0
      packId == null -> 1
      else -> 2
    }
    AnimatedContent(
      targetState = depth,
      modifier = Modifier.fillMaxSize()
        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
      transitionSpec = {
        val forward = targetState > initialState
        val enterOffset: (Int) -> Int = { if (forward) it / 5 else -it / 5 }
        val exitOffset: (Int) -> Int = { if (forward) -it / 6 else it / 6 }
        (slideInHorizontally(tween(220), enterOffset) + fadeIn(tween(200))) togetherWith
          (slideOutHorizontally(tween(180), exitOffset) + fadeOut(tween(140)))
      },
      label = "custom-icons-depth",
    ) { level ->
      val levelRoot = level == 0
      val levelList = when (level) {
        0 -> rootList
        1 -> sourceList
        else -> iconList
      }
      Os4LazyColumn(state = levelList,
        modifier = Modifier.fillMaxSize().keiHomeTopBarScroll(behavior),
        listModifier = Modifier.testTag("custom-icons.list"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp,
          top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        topAppBarScrollBehavior = behavior,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (levelRoot) item("origins", contentType = "origins") {
          val origins = if (uncustomized) newApps.map { it.systemApp }
            else searchedSaved.map { state.origins[it.packageName] }
          CustomIconOriginTabs(listOf(origins.size, origins.count { it == false }, origins.count { it == true }),
            appOrigin, onSelect = { appOrigin = it })
      }
      item("search", contentType = "search") {
        Column {
          Os4SearchField(query = query, onQueryChange = {
            when { levelRoot -> rootQuery = it; level == 1 -> sourceQuery = it; else -> iconQuery = it }
          },
            modifier = Modifier.fillMaxWidth(), inputModifier = Modifier.testTag("custom-icons.search"),
            label = stringResource(if (levelRoot) R.string.preserved_icons_search else R.string.custom_icons_search),
            searching = matches.status.showProgress)
          if (level == 2) LocalSearchError(matches.status)
        }
      }
      if (levelRoot) {
        if (uncustomized) items(apps, key = { it.packageName }, contentType = { "app" }) { app ->
          val pressSource = rememberOs4PressSource()
          Card(Modifier.fillMaxWidth().animateItem(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(title = app.label, summary = app.packageName, enabled = state.ready && !state.busy,
              interactionSource = pressSource,
              modifier = Modifier.glassPress(pressSource, state.ready && !state.busy)
                .testTag("custom-icons.app.${app.packageName}"),
              startAction = { OriginalPreservedIcon(app, controller.inventory, configuration) },
              endActions = { Os4TrailingChevron() },
              onClick = { dismissSearch(); targetPackage = app.packageName })
          }
        } else items(savedRows, key = { it.packageName }, contentType = { "custom" }) { item ->
          val sourcePack = catalog.packs.firstOrNull { it.pack.id == item.sourceId }?.pack
          val sourceTitle = if (sourcePack?.source is IconPackSource.Builtin && sourcePack.colorOsProfile != null)
            "ColorOS · ${customIconSourceTitle(sourcePack)}" else item.sourceTitle
          Card(Modifier.fillMaxWidth().animateItem(), insideMargin = PaddingValues(0.dp)) {
            Row(Modifier.fillMaxWidth().testTag("custom-icons.saved.${item.packageName}")
              .os4Clickable(enabled = !state.busy, cornerRadius = CardDefaults.CornerRadius) {
                dismissSearch(); inspecting = item; inspectionVisible = true
              }
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
              IconButton(onClick = { dismissSearch(); removing = item; showRemoving = true }, enabled = !state.busy) {
                Icon(actionIcon(PageActionIcon.CLEAR), stringResource(R.string.custom_icons_remove))
              }
            }
          }
        }
        if ((if (uncustomized) apps.isEmpty() else savedRows.isEmpty()) && !state.loading) item("empty") {
          val emptySaved = !uncustomized && state.selections.isEmpty()
          Card(Modifier.fillMaxWidth().animateItem(), insideMargin = PaddingValues(24.dp)) {
            Column(
              Modifier.fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              os.kei.hyper.home.ui.components.Os4IconBadge(
                icon = actionIcon(PageActionIcon.CUSTOMIZE),
                tint = os.kei.hyper.home.ui.components.Os4BadgePalette.Purple,
                size = 52.dp,
                cornerRadius = 16.dp,
                iconSize = 26.dp,
              )
              Text(
                stringResource(if (emptySaved) R.string.custom_icons_empty else R.string.custom_icons_no_results),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center,
              )
              if (emptySaved) {
                top.yukonga.miuix.kmp.basic.TextButton(
                  text = stringResource(R.string.custom_icons_choose_app),
                  onClick = { uncustomized = true },
                  colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                  cornerRadius = 20.dp,
                  insideMargin = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                )
              }
            }
          }
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
          level == 1 -> {
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
              val pressSource = rememberOs4PressSource()
              Card(Modifier.fillMaxWidth().animateItem(), insideMargin = PaddingValues(0.dp)) {
                BasicComponent(title = customIconSourceTitle(source),
                  summary = origin + stringResource(R.string.custom_icons_count, source.entries.size) + "\n" + explanation,
                  interactionSource = pressSource,
                  modifier = Modifier.glassPress(pressSource, !state.busy),
                  startAction = {
                    Row(Modifier.width(116.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                      verticalAlignment = Alignment.CenterVertically) {
                      if (preview?.match != null) SourceIconArtwork(controller, source, preview.match, dark, 56.dp)
                      else if (preview != null) preview.examples.forEach {
                        SourceIconArtwork(controller, source, it, dark, 36.dp)
                      } else Os4ShimmerPlaceholder(Modifier.size(36.dp), cornerRadius = 10.dp)
                    }
                  },
                  endActions = { Os4TrailingChevron() },
                  enabled = !state.busy, onClick = { dismissSearch(); packId = source.id })
              }
            }
            item("get-packs") {
              val morePress = rememberOs4PressSource()
              Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                BasicComponent(title = stringResource(R.string.custom_icons_more),
                  interactionSource = morePress,
                  modifier = Modifier.glassPress(morePress),
                  endActions = { Os4TrailingChevron() },
                  onClick = { dismissSearch(); onNavigate("library") })
              }
            }
          }
          pack != null -> {
            items(entryRows, key = { it.first().key }, contentType = { "icons" }) { row ->
              Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { entry ->
                  Column(Modifier.weight(1f)
                    .squircleBackground(MiuixTheme.colorScheme.surfaceContainer, 20.dp)
                    .os4Clickable(enabled = !state.busy, cornerRadius = 20.dp) {
                      dismissSearch(); candidate = entry; showCandidate = true
                    }.padding(12.dp),
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
      if (!state.ready || state.loading || state.busy || catalog.loading && !levelRoot) item("loading") {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
          Text(stringResource(if (state.busy) R.string.custom_icons_saving else R.string.custom_icons_loading),
            style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
      if (state.failed) item("error") {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          BasicComponent(title = stringResource(R.string.custom_icons_error), onClick = controller::load)
        }
      }
    }
    }
    val inspectedItem = inspecting
    Os4DetailsSheet(
      show = inspectionVisible && inspectedItem != null,
      onDismissRequest = { inspectionVisible = false },
      onDismissFinished = {
        if (!inspectionVisible) {
          inspecting = null
          val next = afterInspection
          afterInspection = null
          next?.invoke()
        }
      },
      title = inspectedItem?.appLabel.orEmpty(),
      summary = stringResource(R.string.custom_icons_reapply),
      modifier = Modifier.testTag("custom-icons.details"),
    ) {
      inspectedItem?.let { item ->
        val sourcePack = catalog.packs.firstOrNull { it.pack.id == item.sourceId }?.pack
        val sourceTitle = if (sourcePack?.source is IconPackSource.Builtin && sourcePack.colorOsProfile != null)
          "ColorOS · ${customIconSourceTitle(sourcePack)}" else item.sourceTitle
        top.yukonga.miuix.kmp.glass.GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
              Modifier.weight(1f)
                .squircleBackground(MiuixTheme.colorScheme.surfaceContainerHigh, 16.dp)
                .padding(vertical = 14.dp, horizontal = 8.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OriginalPreservedIcon(
                state.apps.firstOrNull { it.packageName == item.packageName },
                controller.inventory,
                configuration,
                64.dp,
              )
              Text(
                stringResource(R.string.custom_icons_original),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center,
              )
            }
            Column(
              Modifier.weight(1f)
                .squircleBackground(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f), 16.dp)
                .padding(vertical = 14.dp, horizontal = 8.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              SavedIconArtwork(controller, item, dark, 64.dp)
              Text(
                stringResource(R.string.custom_icons_current),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
              )
            }
          }
        }
        top.yukonga.miuix.kmp.glass.GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sourceTitle, style = MiuixTheme.textStyles.body2)
            Text(
              item.packageName,
              style = MiuixTheme.textStyles.footnote2,
              color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
          }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Os4DialogButton(
            text = stringResource(R.string.custom_icons_remove),
            onClick = {
              afterInspection = { removing = item; showRemoving = true }
              inspectionVisible = false
            },
            enabled = inspectionVisible && !state.busy,
            role = GlassDialogButtonRole.Destructive,
            modifier = Modifier.weight(1f),
          )
          Os4DialogButton(
            text = stringResource(R.string.custom_icons_edit),
            onClick = {
              afterInspection = { targetPackage = item.packageName }
              inspectionVisible = false
            },
            enabled = inspectionVisible && !state.busy,
            role = GlassDialogButtonRole.Primary,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
    val selected = candidate
    Os4DetailsSheet(showCandidate && selected != null && pack != null,
      onDismissRequest = { if (!state.busy) showCandidate = false },
      onDismissFinished = { if (!showCandidate) candidate = null },
      title = stringResource(R.string.custom_icons_confirm),
      summary = stringResource(R.string.custom_icons_confirm_summary)) {
      if (selected != null && pack != null) {
        val appTitle = target?.label ?: state.selections.firstOrNull { it.packageName == targetPackage }?.appLabel
          ?: targetPackage.orEmpty()
        val sourceTitle = (if (pack.source is IconPackSource.Builtin && pack.colorOsProfile != null) "ColorOS · " else "") +
          customIconSourceTitle(pack)
        top.yukonga.miuix.kmp.glass.GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
              Modifier.weight(1f)
                .squircleBackground(MiuixTheme.colorScheme.surfaceContainerHigh, 16.dp)
                .padding(vertical = 12.dp, horizontal = 6.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OriginalPreservedIcon(target, controller.inventory, configuration, 56.dp)
              Text(
                stringResource(R.string.custom_icons_original),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
              )
            }
            Column(
              Modifier.weight(1f)
                .squircleBackground(Color(0xFFF2F3F7), 16.dp)
                .padding(vertical = 12.dp, horizontal = 6.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              SourceIconArtwork(controller, pack, selected, false, 56.dp)
              Text(
                stringResource(R.string.monet_preview_light),
                style = MiuixTheme.textStyles.footnote2,
                color = Color(0xFF3C4048),
              )
            }
            Column(
              Modifier.weight(1f)
                .squircleBackground(Color(0xFF1E2026), 16.dp)
                .padding(vertical = 12.dp, horizontal = 6.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              SourceIconArtwork(controller, pack, selected, true, 56.dp)
              Text(
                stringResource(R.string.monet_preview_dark),
                style = MiuixTheme.textStyles.footnote2,
                color = Color(0xFFD2D5DC),
              )
            }
          }
        }
        top.yukonga.miuix.kmp.glass.GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(appTitle, style = MiuixTheme.textStyles.body1)
            Text(
              sourceTitle,
              style = MiuixTheme.textStyles.footnote1,
              color = MiuixTheme.colorScheme.primary,
            )
            Text(
              targetPackage.orEmpty(),
              style = MiuixTheme.textStyles.footnote2,
              color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
          }
        }
        if (targetPackage in preferences.preservedIconPackages) {
          top.yukonga.miuix.kmp.glass.GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
            Text(
              stringResource(R.string.custom_icons_conflict_preserved),
              style = MiuixTheme.textStyles.footnote1,
              color = os.kei.hyper.home.ui.components.Os4BadgePalette.Orange,
            )
          }
        }
        if (state.failed) Text(
          stringResource(R.string.custom_icons_error),
          style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.error,
        )
        Os4DialogButton(
          text = stringResource(when {
            state.busy -> R.string.custom_icons_saving
            targetPackage in preferences.preservedIconPackages -> R.string.custom_icons_conflict_save_action
            else -> R.string.custom_icons_confirm
          }),
          onClick = { targetPackage?.let { name ->
            controller.save(name, appTitle, pack, selected, sourceTitle)
          } },
          enabled = state.ready && !state.busy && !state.loading && targetPackage != null,
          role = GlassDialogButtonRole.Primary,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
    Os4ActionDialog(
      show = showRemoving && removing != null,
      onDismissRequest = { showRemoving = false },
      onDismissFinished = { if (!showRemoving) removing = null },
      title = stringResource(R.string.custom_icons_remove),
      summary = removing?.appLabel.orEmpty(),
      icon = actionIcon(PageActionIcon.CLEAR),
      details = {
        Text(
          stringResource(R.string.custom_icons_remove_summary),
          style = MiuixTheme.textStyles.footnote1,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
      },
      actions = listOf(
        Os4DialogAction(stringResource(R.string.logs_cancel), onClick = { showRemoving = false }),
        Os4DialogAction(
          stringResource(R.string.custom_icons_remove),
          role = GlassDialogButtonRole.Destructive,
          enabled = !state.busy,
          onClick = { removing?.let { controller.remove(it.packageName) }; showRemoving = false },
        ),
      ),
    )
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
  val matched = result?.takeIf { it.first == key }
  CustomIconArtwork(matched?.second, loading = matched == null, iconSize = iconSize)
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
  val matched = result?.takeIf { it.first == key }
  CustomIconArtwork(matched?.second, loading = matched == null, iconSize = iconSize)
}

@Composable
private fun CustomIconArtwork(
  bitmap: android.graphics.Bitmap?,
  loading: Boolean = false,
  iconSize: androidx.compose.ui.unit.Dp = 60.dp,
) {
  val image = remember(bitmap) { bitmap?.asImageBitmap() }
  Crossfade(
    targetState = image to loading,
    modifier = Modifier.size(iconSize),
    animationSpec = tween(180),
    label = "custom-icon-artwork",
  ) { (img, isLoading) ->
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      when {
        img != null -> Image(img, contentDescription = null, modifier = Modifier.fillMaxSize())
        isLoading -> Os4ShimmerPlaceholder(Modifier.fillMaxSize(), cornerRadius = 12.dp)
        else -> Icon(actionIcon(PageActionIcon.PACKS), contentDescription = null,
          tint = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
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
