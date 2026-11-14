package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppIconPreviews
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.data.IconPreviewRepository
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.MonetIconRole
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask
import os.kei.hyper.home.ui.components.IconPreviewComparison
import os.kei.hyper.home.ui.components.IconPreviewArtworkFrame
import os.kei.hyper.home.ui.components.IconPreviewInspectionSheet
import os.kei.hyper.home.ui.components.IconPreviewMode
import os.kei.hyper.home.ui.components.os4Clickable
import os.kei.hyper.home.ui.components.Os4InfoPopup
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Help
import top.yukonga.miuix.kmp.icon.glass.Ok

@Composable
internal fun AppsPage(model: HomeViewModel, listState: LazyListState, padding: PaddingValues, modifier: Modifier) {
  val scan by model.apps.collectAsStateWithLifecycle()
  val options by model.browserOptions.collectAsStateWithLifecycle()
  val source by model.browserSource.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val monet by model.monet.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  var query by rememberSaveable { mutableStateOf("") }
  var focusedRow by remember { mutableStateOf<IconBrowserRow?>(null) }
  var showInspection by remember { mutableStateOf(false) }
  // Capture immutable inputs: background work must not read a newer delegated State.
  val capabilities = scan.capabilities
  val currentSource = source
  val currentOptions = options
  val currentQuery = query
  val rowsRequest = remember(capabilities, currentSource, currentOptions) { Any() }
  val rows by produceState<Pair<Any, List<IconBrowserRow>>?>(null, rowsRequest) {
    val apps = capabilities ?: return@produceState
    val computed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
      browserRows(apps, currentSource, currentOptions, "")
    }
    currentCoroutineContext().ensureActive()
    value = rowsRequest to computed
  }
  val currentRows = rows?.takeIf { it.first === rowsRequest }?.second
  val matchesRequest = remember(rowsRequest, currentQuery) { Any() }
  val matches by produceState<AppsBrowserResult?>(null, matchesRequest, currentRows) {
    val readyRows = currentRows ?: return@produceState
    val computed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
      computeAppsBrowserMatches(matchesRequest, readyRows, currentQuery, rowsRequest)
    }
    currentCoroutineContext().ensureActive()
    value = computed
  }
  val display = AppsBrowserDisplay(matchesRequest, matches, rowsRequest)
  val visible = display.rows
  val colors = remember(monet.snapshot?.activePalette, monet.snapshot?.customization,
    monet.snapshot?.customizationActive) { previewColors(monet.snapshot) }
  val configuration = LocalConfiguration.current.toString()
  LazyColumn(overscrollEffect = null, state = listState, modifier = modifier, contentPadding = padding,
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item(key = "search", contentType = "search") {
      Os4SearchField(query = query, onQueryChange = { query = it },
        modifier = Modifier.fillMaxWidth(), label = stringResource(R.string.search_apps))
    }
    item(key = "summary", contentType = "summary") {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(browserScopeLabels()[options.scope]), style = MiuixTheme.textStyles.body2,
                  modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetadataPill(if (display.loading) stringResource(R.string.browser_loading)
                  else stringResource(R.string.browser_count_compact, visible.size, display.total),
                  MiuixTheme.colorScheme.onSurfaceSecondary)
                if (scan.loading || display.loading) CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
              }
              Text(runtime.report?.iconTheme?.title ?: stringResource(R.string.status_unknown),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
            }
            Os4InfoPopup(
              title = stringResource(R.string.browser_preview_help_title),
              summary = stringResource(if (source == null) R.string.browser_source_pending else R.string.browser_preview_note),
              modifier = Modifier.padding(start = 4.dp),
            )
          }
          RefreshFeedback(model, 1)
        }
      }
    }
    scan.error?.let { error -> item(key = "error", contentType = "message") {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Text(stringResource(R.string.scan_failed, error.ifEmpty { stringResource(R.string.unknown_error) }),
          style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
        TextButton(stringResource(R.string.refresh), onClick = { model.refresh(1) })
      }
    } }
    if (display.showEmpty && !scan.loading) item(key = "empty", contentType = "message") {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
        Text(stringResource(R.string.no_matching_apps), style = MiuixTheme.textStyles.body1)
      }
    }
    items(visible, key = { it.key }, contentType = { "application" }) { row ->
      AppCapabilityCard(row.app, model.previews, scan.previewGeneration, configuration, colors,
        preferences.showIconPreviews, row, source, model,
        onInspect = { focusedRow = row; showInspection = true })
    }
  }
  focusedRow?.let { focused ->
    androidx.compose.runtime.key(focused.key) {
      val previews by androidx.compose.runtime.key(focused, scan.previewGeneration, configuration, source?.revision) {
        produceState<AppIconPreviews?>(null) { value = browserPreview(model, focused, source) }
      }
      IconPreviewInspectionSheet(show = showInspection, onDismissRequest = { showInspection = false },
        title = focused.app.label, modifier = Modifier.testTag("apps.preview.details"),
        onDismissFinished = { if (!showInspection) focusedRow = null },
        details = {
          Text(focused.app.packageName, style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
          if (focused.installed) MetadataPill(
            stringResource(if (focused.app.systemApp) R.string.browser_badge_system else R.string.browser_badge_user),
            MiuixTheme.colorScheme.onSurfaceSecondary)
          IconInfoDetails(focused.app, focused, source, previews)
        }) { mode ->
        AppIconInspectionComparison(previews, colors, mode)
      }
    }
  }
}

internal data class AppsBrowserResult(val request: Any, val rows: List<IconBrowserRow>, val total: Int,
  val rowsRequest: Any = request)

internal suspend fun computeAppsBrowserMatches(request: Any, rows: List<IconBrowserRow>,
  query: String, rowsRequest: Any = request): AppsBrowserResult {
  val context = currentCoroutineContext()
  val matches = rows.filter {
    context.ensureActive()
    it.app.matchesQuery(query)
  }
  context.ensureActive()
  return AppsBrowserResult(request, matches, rows.size, rowsRequest)
}

/** Only query changes may retain rows, with loading feedback; empty requires the exact current request. */
internal class AppsBrowserDisplay(request: Any, result: AppsBrowserResult?, rowsRequest: Any = request) {
  private val current = result?.takeIf { it.request === request && it.rowsRequest === rowsRequest }
  private val usable = current ?: result?.takeIf { it.rowsRequest === rowsRequest && it.rows.isNotEmpty() }
  val loading get() = current == null
  val rows get() = usable?.rows.orEmpty()
  val total get() = usable?.total ?: 0
  val showEmpty get() = current != null && current.rows.isEmpty()
}

internal data class IconPreviewColors(val lightBackground: Int, val lightForeground: Int,
  val darkBackground: Int, val darkForeground: Int)

internal fun previewColors(snapshot: MonetPaletteSnapshot?): IconPreviewColors? {
  fun tone(family: String, tone: Int) = snapshot?.families?.firstOrNull { it.name == family }
    ?.colors?.firstOrNull { it.tone == tone }?.value
  val choice = snapshot?.customization?.takeIf { snapshot.customizationActive }
  fun color(role: MonetIconRole) = role.color(choice, ::tone)
  return IconPreviewColors(color(MonetIconRole.LIGHT_BACKGROUND) ?: return null,
    color(MonetIconRole.LIGHT_FOREGROUND) ?: return null,
    color(MonetIconRole.DARK_BACKGROUND) ?: return null,
    color(MonetIconRole.DARK_FOREGROUND) ?: return null)
}

@Composable
internal fun AppCapabilityCard(app: InstalledIconCapability, repository: IconPreviewRepository,
  generation: Int, configuration: String, colors: IconPreviewColors?, showPreviews: Boolean,
  browser: IconBrowserRow? = null, source: IconBrowserSource? = null, model: HomeViewModel? = null,
  onInspect: () -> Unit,
) {
  val previews by androidx.compose.runtime.key(app, generation, configuration, source?.revision) {
    produceState<AppIconPreviews?>(null) {
      value = if (model != null && browser != null) browserPreview(model, browser, source) else repository.load(app)
    }
  }
  Card(modifier = Modifier.fillMaxWidth().os4Clickable(cornerRadius = CardDefaults.CornerRadius,
    onClickLabel = stringResource(R.string.preview_glass_inspect), onClick = onInspect),
    insideMargin = PaddingValues(12.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val dark = LocalKeiHomeDarkTheme.current
        val icon = previews?.let { if (dark) it.dark else it.light }
        PreviewIcon(icon, null, null, Modifier.size(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(app.label, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(app.packageName, style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (browser?.installed == true) MetadataPill(
          stringResource(if (app.systemApp) R.string.browser_badge_system else R.string.browser_badge_user),
          if (app.systemApp) MiuixTheme.colorScheme.onSurfaceSecondary else MiuixTheme.colorScheme.primary)
      }
      IconInfoDetails(app, browser, source, previews, includeFallbackNote = false)
      if (showPreviews) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconVariant(stringResource(R.string.monet_preview_light), previews?.light, modifier = Modifier.weight(1f))
        IconVariant(stringResource(R.string.monet_preview_dark), previews?.dark, modifier = Modifier.weight(1f))
        IconVariant(stringResource(R.string.browser_preview_monet_light),
          if (colors != null) previews?.lightMonochrome ?: previews?.light else null,
          foreground = colors?.lightForeground?.takeIf { previews?.lightMonochrome != null },
          background = colors?.lightBackground?.takeIf { previews?.lightMonochrome != null },
          backgroundMask = previews?.backgroundMask,
          maskPathData = previews?.maskPathData,
          original = previews != null && colors != null && previews?.lightMonochrome == null,
          modifier = Modifier.weight(1f))
        IconVariant(stringResource(R.string.browser_preview_monet_dark),
          if (colors != null) previews?.darkMonochrome ?: previews?.dark else null,
          foreground = colors?.darkForeground?.takeIf { previews?.darkMonochrome != null },
          background = colors?.darkBackground?.takeIf { previews?.darkMonochrome != null },
          backgroundMask = previews?.backgroundMask,
          maskPathData = previews?.maskPathData,
          original = previews != null && colors != null && previews?.darkMonochrome == null,
          modifier = Modifier.weight(1f))
      }
      if (app.monochromeFallbackRecommended) Text(stringResource(R.string.apps_fallback_note),
        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}

@Composable
private fun IconInfoDetails(app: InstalledIconCapability, browser: IconBrowserRow?, source: IconBrowserSource?,
  previews: AppIconPreviews?, includeFallbackNote: Boolean = true,
) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (browser != null) {
      val label = when {
        source == null -> R.string.browser_badge_pending
        browser.dynamic -> R.string.browser_badge_dynamic
        browser.covered -> R.string.browser_badge_redrawn
        else -> R.string.browser_badge_original
      }
      MetadataPill(if (browser.systemFallback != null) stringResource(
        if (browser.systemFallback.isPerfect) R.string.perfect_completion_badge else R.string.system_fallback_badge,
        browser.systemFallback.perfectStyle ?: systemFallbackName(browser.systemFallback)) else stringResource(label),
        if (source != null && browser.covered) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary)
      if (browser.installed && !app.hasLauncher) MetadataPill(stringResource(R.string.browser_badge_no_launcher),
        MiuixTheme.colorScheme.onSurfaceSecondary)
    }
    CapabilityPill(stringResource(R.string.apps_night_feature), app.nightMode,
      colorResource(if (LocalKeiHomeDarkTheme.current) R.color.status_hero_loaded_accent_dark else R.color.status_hero_loaded_accent))
    CapabilityPill("Monet", app.monochrome)
    if (previews != null && previews.light == null && previews.dark == null &&
      previews.lightMonochrome == null && previews.darkMonochrome == null) {
      MetadataPill(stringResource(R.string.browser_preview_unavailable), MiuixTheme.colorScheme.onSurfaceSecondary)
    }
    if (!app.personalProfile) MetadataPill(stringResource(R.string.profile_user, app.userId), MiuixTheme.colorScheme.primary)
  }
  if (includeFallbackNote && app.monochromeFallbackRecommended) Text(stringResource(R.string.apps_fallback_note),
    style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
private fun AppIconInspectionComparison(previews: AppIconPreviews?, colors: IconPreviewColors?,
  mode: IconPreviewMode,
) {
  IconPreviewComparison(mode) { dark, modifier ->
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(20.dp)) {
      IconVariant(stringResource(if (dark) R.string.preview_glass_dark else R.string.preview_glass_light),
        if (dark) previews?.dark else previews?.light, modifier = Modifier.fillMaxWidth(), size = 88.dp,
        loading = previews == null, surfaceDark = dark)
      val monochrome = if (dark) previews?.darkMonochrome else previews?.lightMonochrome
      val original = if (dark) previews?.dark else previews?.light
      IconVariant(stringResource(if (dark) R.string.browser_preview_monet_dark else R.string.browser_preview_monet_light),
        if (colors != null) monochrome ?: original else null,
        foreground = if (monochrome != null) {
          if (dark) colors?.darkForeground else colors?.lightForeground
        } else null,
        background = if (monochrome != null) {
          if (dark) colors?.darkBackground else colors?.lightBackground
        } else null,
        backgroundMask = previews?.backgroundMask, maskPathData = previews?.maskPathData,
        original = previews != null && colors != null && monochrome == null,
        modifier = Modifier.fillMaxWidth(), size = 88.dp, loading = previews == null, surfaceDark = dark)
    }
  }
}

@Composable
private fun CapabilityPill(label: String, status: IconCapabilityStatus, positiveColor: Color? = null) {
  val value = stringResource(when (status) {
    IconCapabilityStatus.DETECTED -> R.string.apps_supported
    IconCapabilityStatus.NOT_DETECTED -> R.string.apps_not_provided
    IconCapabilityStatus.UNKNOWN -> R.string.status_needs_verification
  })
  val color = when (status) {
    IconCapabilityStatus.DETECTED -> positiveColor ?: MiuixTheme.colorScheme.primary
    IconCapabilityStatus.NOT_DETECTED -> MiuixTheme.colorScheme.onSurfaceSecondary
    IconCapabilityStatus.UNKNOWN -> colorResource(
      if (LocalKeiHomeDarkTheme.current) R.color.status_hero_warning_accent_dark else R.color.status_hero_warning_accent)
  }
  Row(Modifier.clip(CircleShape).background(color.copy(alpha = 0.10f))
    .padding(horizontal = 7.dp, vertical = 3.dp).semantics(mergeDescendants = true) { stateDescription = value },
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(label, style = MiuixTheme.textStyles.footnote2, color = color)
    when (status) {
      IconCapabilityStatus.DETECTED -> Icon(MiuixIcons.Glass.Ok, null, Modifier.size(12.dp), tint = color)
      IconCapabilityStatus.UNKNOWN -> Icon(MiuixIcons.Glass.Help, null, Modifier.size(12.dp), tint = color)
      IconCapabilityStatus.NOT_DETECTED -> Text("\u2014", style = MiuixTheme.textStyles.footnote2, color = color)
    }
  }
}

@Composable
private fun IconVariant(label: String, bitmap: Bitmap?, modifier: Modifier,
  foreground: Int? = null, background: Int? = null, original: Boolean = false, backgroundMask: Bitmap? = null,
  maskPathData: String? = null, size: Dp = 44.dp, loading: Boolean = false, surfaceDark: Boolean? = null,
) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    val artwork: @Composable () -> Unit = {
      if (loading) androidx.compose.foundation.layout.Box(Modifier.size(size),
        contentAlignment = Alignment.Center) {
        CircularProgressIndicator(size = 24.dp, strokeWidth = 2.dp)
      } else PreviewIcon(bitmap, foreground, background, Modifier.size(size).semantics { contentDescription = label },
        backgroundMask, maskPathData)
    }
    if (surfaceDark == null) artwork()
    else IconPreviewArtworkFrame { artwork() }
    Text(if (original) stringResource(R.string.browser_preview_original, label) else label,
      style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary,
      textAlign = TextAlign.Center, maxLines = 2)
  }
}

@Composable
internal fun PreviewIcon(bitmap: Bitmap?, foreground: Int?, background: Int?, modifier: Modifier,
  backgroundMask: Bitmap? = null, maskPathData: String? = null) {
  val maskImage = remember(backgroundMask) { backgroundMask?.asImageBitmap() }
  val image = remember(bitmap) { bitmap?.asImageBitmap() }
  val publishedShape = remember(maskPathData) {
    maskPathData?.let {
      NativeAdaptiveIconMask.fromPathData("published-preview", it)
    }?.let { mask ->
      GenericShape { size, _ ->
        addPath(mask.pathForBounds(android.graphics.Rect(0, 0, size.width.toInt(), size.height.toInt())).asComposePath())
      }
    }
  }
  val shape = remember { AdaptiveIconDrawable(ColorDrawable(android.graphics.Color.WHITE), null).apply {
    setBounds(0, 0, 100, 100)
  }.iconMask.asComposePath() }
  val placeholder = MiuixTheme.colorScheme.surfaceVariant
  if (image != null && foreground == null) {
    Image(image, contentDescription = null,
      modifier = if (publishedShape != null) modifier.clip(publishedShape) else modifier)
  } else Canvas(if (publishedShape != null) modifier.clip(publishedShape) else modifier) {
    if (maskImage != null && background != null) drawImage(maskImage, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
      colorFilter = ColorFilter.tint(Color(background)))
    else if (publishedShape != null) drawRect(background?.let(::Color) ?: placeholder)
    else scale(size.width / 100f, size.height / 100f, pivot = Offset.Zero) {
      drawPath(shape, background?.let(::Color) ?: placeholder)
    }
    if (image != null && foreground != null) drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
      colorFilter = ColorFilter.tint(Color(foreground)))
  }
}

internal fun InstalledIconCapability.matchesFilter(filter: Int): Boolean = when (filter) {
  0 -> nightMode == IconCapabilityStatus.DETECTED || monochrome == IconCapabilityStatus.DETECTED
  1 -> nightMode == IconCapabilityStatus.DETECTED
  2 -> monochrome == IconCapabilityStatus.DETECTED
  3 -> nightMode == IconCapabilityStatus.UNKNOWN || monochrome == IconCapabilityStatus.UNKNOWN
  else -> true
}

internal fun InstalledIconCapability.matchesQuery(query: String): Boolean = query.trim().let {
  it.isEmpty() || label.contains(it, ignoreCase = true) || packageName.contains(it, ignoreCase = true)
}
