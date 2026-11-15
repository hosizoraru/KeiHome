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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.*
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
import top.yukonga.miuix.kmp.icon.glass.Help
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
  var targetPackage by rememberSaveable { mutableStateOf<String?>(null) }
  var packId by rememberSaveable { mutableStateOf<String?>(null) }
  var choosingApp by rememberSaveable { mutableStateOf(false) }
  var query by rememberSaveable(targetPackage, packId, choosingApp) {
    mutableStateOf(if (packId != null) targetPackage.orEmpty() else "")
  }
  var candidate by remember { mutableStateOf<IconPackEntry?>(null) }
  var removing by remember { mutableStateOf<CustomIconSelection?>(null) }
  var help by remember { mutableStateOf(false) }
  val pack = catalog.packs.firstOrNull { it.pack.id == packId }?.pack
  val target = state.apps.firstOrNull { it.packageName == targetPackage }
  val root = !choosingApp && targetPackage == null
  fun back() {
    candidate = null
    when {
      packId != null -> packId = null
      targetPackage != null -> { targetPackage = null; choosingApp = true }
      choosingApp -> choosingApp = false
      else -> onBack()
    }
  }
  BackHandler(enabled = !root) { back() }
  LaunchedEffect(state.saved) {
    if (state.saved > 0) { candidate = null; targetPackage = null; packId = null; choosingApp = false }
  }
  val title = stringResource(when {
    root -> R.string.custom_icons_title
    choosingApp -> R.string.custom_icons_choose_app
    packId == null -> R.string.custom_icons_choose_pack
    else -> R.string.custom_icons_choose_icon
  })
  val list = rememberLazyListState()
  LaunchedEffect(choosingApp, targetPackage, packId, query) { list.scrollToItem(0) }
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
  val apps = remember(SnapshotIdentity(state.apps), SnapshotIdentity(state.selections),
    SnapshotIdentity(preferences.preservedIconPackages), state.showHidden, query) {
    val retained = preferences.preservedIconPackages + state.selections.map { it.packageName }
    state.apps.filter { (state.showHidden || !it.hiddenByDefault || it.packageName in retained) &&
      (it.label.contains(query, true) || it.packageName.contains(query, true)) }
  }
  val labels = remember(SnapshotIdentity(state.apps)) { state.apps.associate { it.packageName to it.label } }
  val entryIdentity = SnapshotIdentity(pack?.entries.orEmpty())
  val matches by produceState(Triple<SnapshotIdentity?, String, List<IconPackEntry>>(null, "", emptyList()),
    entryIdentity, pack?.source, query, SnapshotIdentity(labels)) {
    val result = withContext(Dispatchers.Default) {
      pack?.entries.orEmpty().filter {
        val label = labels[it.component?.packageName ?: it.nativeName?.substringBefore('#')]
        it.key.contains(query, true) || it.component?.flattened?.contains(query, true) == true ||
          it.nativeName?.contains(query, true) == true || label?.contains(query, true) == true
      }
    }
    value = Triple(entryIdentity, query, result)
  }
  val entries = matches.third.takeIf { matches.first == entryIdentity && matches.second == query }.orEmpty()
  val entryRows = remember(SnapshotIdentity(entries)) { entries.chunked(3) }
  Scaffold(backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = title, scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = { back() }) {
          Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
        } },
        actions = {
          if (choosingApp) OverlayIconCascadingDropdownMenu(entries = listOf(DropdownEntry(items = listOf(
            DropdownItem(text = stringResource(R.string.icon_inventory_show_hidden),
              selected = state.showHidden, onClick = { controller.setShowHidden(!state.showHidden) }),
          )))) {
            Icon(MiuixIcons.Glass.Filter, stringResource(R.string.icon_inventory_show_hidden),
              tint = if (state.showHidden) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground)
          }
          IconButton(onClick = { help = true }) {
          Icon(MiuixIcons.Glass.Help, stringResource(R.string.custom_icons_title))
        } })
    }
  }) { padding ->
    LazyColumn(state = list, overscrollEffect = null,
      modifier = Modifier.fillMaxSize().keiHomeTopBarScroll(behavior)
        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
      contentPadding = PaddingValues(start = 18.dp, end = 18.dp,
        top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (root) {
        item("intro") {
          Text(stringResource(R.string.custom_icons_summary), Modifier.padding(horizontal = 12.dp),
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        item("add") {
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(title = stringResource(R.string.custom_icons_add),
              summary = stringResource(R.string.custom_icons_add_summary),
              enabled = state.ready && !state.busy && !state.loading, onClick = { choosingApp = true })
          }
        }
        if (state.selections.isNotEmpty()) item("pending") {
          Text(stringResource(R.string.custom_icons_reapply), Modifier.padding(horizontal = 12.dp),
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        items(state.selections, key = { it.packageName }, contentType = { "custom" }) { item ->
          val sourcePack = catalog.packs.firstOrNull { it.pack.id == item.sourceId }?.pack
          val sourceTitle = if (sourcePack?.source is IconPackSource.Builtin && sourcePack.colorOsProfile != null)
            "ColorOS · ${customIconSourceTitle(sourcePack)}" else item.sourceTitle
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { targetPackage = item.packageName }
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
              IconButton(onClick = { removing = item }, enabled = !state.busy && !state.loading) {
                Icon(actionIcon(PageActionIcon.CLEAR), stringResource(R.string.custom_icons_remove))
              }
            }
          }
        }
        if (state.selections.isEmpty() && !state.loading) item("empty") {
          Text(stringResource(R.string.custom_icons_empty), Modifier.padding(12.dp),
            style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      } else {
        item("search") {
          Os4SearchField(query = query, onQueryChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.custom_icons_search))
        }
        targetPackage?.let { name -> item("target") {
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(title = target?.label ?: name,
              summary = stringResource(R.string.custom_icons_original) + "\n" + name,
              startAction = { OriginalPreservedIcon(target, controller.inventory, configuration, 56.dp) })
          }
        } }
        when {
          choosingApp -> items(apps, key = { it.packageName }, contentType = { "app" }) { app ->
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = app.label, summary = app.packageName, enabled = !state.busy,
                startAction = { OriginalPreservedIcon(app, controller.inventory, configuration) },
                onClick = { targetPackage = app.packageName; choosingApp = false })
            }
          }
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
                  enabled = !state.busy, onClick = { packId = source.id })
              }
            }
            item("get-packs") {
              Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                BasicComponent(title = stringResource(R.string.custom_icons_more),
                  onClick = { onNavigate("library") })
              }
            }
          }
          pack != null -> {
            item("count") {
              Text(stringResource(R.string.custom_icons_count, entries.size), Modifier.padding(horizontal = 12.dp),
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
              if (query.isNotEmpty()) TextButton(stringResource(R.string.custom_icons_show_all), onClick = { query = "" })
            }
            items(entryRows, key = { it.first().key }, contentType = { "icons" }) { row ->
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { entry ->
                  Column(Modifier.weight(1f).background(MiuixTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .clickable(enabled = !state.busy) { candidate = entry }.padding(12.dp),
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
    Os4DetailsSheet(help, { help = false }, title = stringResource(R.string.custom_icons_title),
      summary = stringResource(R.string.custom_icons_help)) {}
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
private fun SavedIconArtwork(controller: CustomIconsController, item: CustomIconSelection, dark: Boolean) {
  val key = item.token to dark
  val result by produceState<Pair<Pair<String, Boolean>, android.graphics.Bitmap?>?>(null, key) {
    val bitmap = try { controller.preview(item, dark) }
      catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (_: Exception) { null }
    value = key to bitmap
  }
  CustomIconArtwork(result?.takeIf { it.first == key }?.second)
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
