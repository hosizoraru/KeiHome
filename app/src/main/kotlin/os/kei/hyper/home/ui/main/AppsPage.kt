package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppIconPreviews
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.data.IconPreviewRepository
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.MonetIconRole
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AppsPage(model: HomeViewModel, listState: LazyListState, padding: PaddingValues, modifier: Modifier) {
  val scan by model.apps.collectAsStateWithLifecycle()
  val options by model.browserOptions.collectAsStateWithLifecycle()
  val source by model.browserSource.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val monet by model.monet.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  var query by rememberSaveable { mutableStateOf("") }
  val matches by produceState(Pair(emptyList<IconBrowserRow>(), 0), scan.capabilities, source, options, query) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
      val rows = browserRows(scan.capabilities.orEmpty(), source, options, "")
      rows.filter { it.app.matchesQuery(query) } to rows.size
    }
  }
  val visible = matches.first
  val colors = remember(monet.snapshot?.activePalette, monet.snapshot?.customization,
    monet.snapshot?.customizationActive) { previewColors(monet.snapshot) }
  val configuration = LocalConfiguration.current.toString()
  LazyColumn(state = listState, modifier = modifier, contentPadding = padding,
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item(key = "search", contentType = "search") {
      InputField(query = query, onQueryChange = { query = it }, onSearch = {}, expanded = false,
        onExpandedChange = {}, modifier = Modifier.fillMaxWidth(), label = stringResource(R.string.search_apps))
    }
    item(key = "summary", contentType = "summary") {
      Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(browserScopeLabels()[options.scope]), style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
          if (scan.loading) CircularProgressIndicator(modifier = Modifier.size(18.dp))
        }
        Text(if (scan.capabilities == null) stringResource(R.string.scanning_installed_apps)
          else stringResource(R.string.browser_count, visible.size, matches.second),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Text(runtime.report?.iconTheme?.title ?: stringResource(R.string.icon_pack_system_default),
          style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary)
        Text(stringResource(if (source == null) R.string.browser_source_pending else R.string.browser_preview_note),
          style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        RefreshFeedback(model, 1)
      }
    }
    scan.error?.let { error -> item(key = "error", contentType = "message") {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Text(stringResource(R.string.scan_failed, error.ifEmpty { stringResource(R.string.unknown_error) }),
          style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
        TextButton(stringResource(R.string.refresh), onClick = { model.refresh(1) })
      }
    } }
    if (scan.capabilities != null && visible.isEmpty()) item(key = "empty", contentType = "message") {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
        Text(stringResource(R.string.no_matching_apps), style = MiuixTheme.textStyles.body1)
      }
    }
    items(visible, key = { it.key }, contentType = { "application" }) { row ->
      AppCapabilityCard(row.app, model.previews, scan.previewGeneration, configuration, colors, preferences.showIconPreviews, row, source, model)
    }
  }
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
) {
  val previews by produceState<AppIconPreviews?>(null, app, generation, configuration, source?.revision) {
    value = if (model != null && browser != null) browserPreview(model, browser, source) else repository.load(app)
  }
  Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val dark = LocalKeiHomeDarkTheme.current
        val icon = previews?.let { if (dark) it.dark else it.light }
        PreviewIcon(icon, null, null, Modifier.size(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(app.label, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(app.packageName, style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!app.personalProfile) Text(stringResource(R.string.profile_user, app.userId),
          style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.primary)
      }
      if (browser != null && browser.installed) Text(
        stringResource(if (app.systemApp) R.string.browser_origin_system else R.string.browser_origin_user) +
          if (!app.hasLauncher) " · " + stringResource(R.string.browser_no_launcher) else "",
        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      if (browser != null) Text(stringResource(when {
        source == null -> R.string.browser_source_pending
        browser.dynamic -> R.string.browser_dynamic
        browser.covered -> R.string.browser_covered
        else -> R.string.browser_original
      }), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CapabilityLabel(stringResource(R.string.apps_night_feature), app.nightMode, Modifier.weight(1f))
        CapabilityLabel("Monet", app.monochrome, Modifier.weight(1f))
      }
      if (showPreviews) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconVariant(stringResource(R.string.monet_preview_light), previews?.light, modifier = Modifier.weight(1f))
        IconVariant(stringResource(R.string.monet_preview_dark), previews?.dark, modifier = Modifier.weight(1f))
        IconVariant(stringResource(R.string.apps_monet_light),
          if (colors != null) previews?.lightMonochrome ?: previews?.light else null,
          foreground = colors?.lightForeground?.takeIf { previews?.lightMonochrome != null },
          background = colors?.lightBackground?.takeIf { previews?.lightMonochrome != null },
          backgroundMask = previews?.backgroundMask,
          original = previews != null && colors != null && previews?.lightMonochrome == null,
          modifier = Modifier.weight(1f))
        IconVariant(stringResource(R.string.apps_monet_dark),
          if (colors != null) previews?.darkMonochrome ?: previews?.dark else null,
          foreground = colors?.darkForeground?.takeIf { previews?.darkMonochrome != null },
          background = colors?.darkBackground?.takeIf { previews?.darkMonochrome != null },
          backgroundMask = previews?.backgroundMask,
          original = previews != null && colors != null && previews?.darkMonochrome == null,
          modifier = Modifier.weight(1f))
      }
      if (app.monochromeFallbackRecommended) Text(stringResource(R.string.apps_fallback_note),
        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}

@Composable
private fun CapabilityLabel(label: String, status: IconCapabilityStatus, modifier: Modifier) {
  val value = stringResource(when (status) {
    IconCapabilityStatus.DETECTED -> R.string.apps_supported
    IconCapabilityStatus.NOT_DETECTED -> R.string.apps_not_provided
    IconCapabilityStatus.UNKNOWN -> R.string.status_needs_verification
  })
  Text("$label · $value", modifier, style = MiuixTheme.textStyles.footnote1,
    color = if (status == IconCapabilityStatus.DETECTED) MiuixTheme.colorScheme.primary
      else MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
private fun IconVariant(label: String, bitmap: Bitmap?, modifier: Modifier,
  foreground: Int? = null, background: Int? = null, original: Boolean = false, backgroundMask: Bitmap? = null,
) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    PreviewIcon(bitmap, foreground, background, Modifier.size(44.dp).semantics { contentDescription = label }, backgroundMask)
    Text(label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary,
      textAlign = TextAlign.Center, maxLines = 2)
    if (original) Text(stringResource(R.string.apps_original_icon), style = MiuixTheme.textStyles.footnote2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
}

@Composable
private fun PreviewIcon(bitmap: Bitmap?, foreground: Int?, background: Int?, modifier: Modifier, backgroundMask: Bitmap? = null) {
  val maskImage = remember(backgroundMask) { backgroundMask?.asImageBitmap() }
  val image = remember(bitmap) { bitmap?.asImageBitmap() }
  val shape = remember { AdaptiveIconDrawable(ColorDrawable(android.graphics.Color.WHITE), null).apply {
    setBounds(0, 0, 100, 100)
  }.iconMask.asComposePath() }
  val placeholder = MiuixTheme.colorScheme.surfaceVariant
  if (image != null && foreground == null) {
    Image(image, contentDescription = null, modifier = modifier)
  } else Canvas(modifier) {
    if (maskImage != null && background != null) drawImage(maskImage, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
      colorFilter = ColorFilter.tint(Color(background)))
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
