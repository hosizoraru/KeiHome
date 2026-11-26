package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.ColorOsFallbackRenderer
import os.kei.hyper.home.iconpack.ExtraIcon
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.ui.components.IconPreviewArtworkFrame
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.components.Os4ShimmerPlaceholder
import os.kei.hyper.home.ui.components.os4Clickable
import os.kei.hyper.home.ui.components.os4ContentSize
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import os.kei.hyper.home.ui.apple.rememberAppleCurrentThemePreviewProvider

@Composable
internal fun ExtraIconsPage(model: HomeViewModel, modifier: Modifier, padding: PaddingValues) {
  val prefs by model.preferences.state.collectAsStateWithLifecycle()
  val packs by model.iconPacks.collectAsStateWithLifecycle()
  val custom by model.customIcons.state.collectAsStateWithLifecycle()
  val installed by model.apps.collectAsStateWithLifecycle()
  val masks by model.iconMasks.state.collectAsStateWithLifecycle()
  val context = LocalContext.current.applicationContext
  val current = rememberAppleCurrentThemePreviewProvider(model)
  val currentArtwork = remember(current) { mutableStateMapOf<ExtraIcon, ExtraCurrentArtwork>() }
  val extraArtwork = remember(context, installed.previewGeneration, prefs.completeSystemIconConfig,
    masks.custom?.digest) { mutableStateMapOf<ExtraIcon, Bitmap?>() }
  LaunchedEffect(currentArtwork, extraArtwork) {
    val mask = withContext(Dispatchers.IO) { IconMaskPolicy.snapshot(context).resolve(null) }
    val renderer = ColorOsFallbackRenderer(context)
    for (icon in ExtraIcon.entries) {
      val preview = current.load(icon.packageName)
      val packed = preview?.dark ?: preview?.light
      val original = if (packed == null) withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        renderer.renderExtra(icon, IconPackComponent(icon.packageName, icon.activity),
          192, mask, original = true) { caller.ensureActive() }
      } else null
      currentArtwork[icon] = ExtraCurrentArtwork(packed ?: original, original != null)
    }
  }
  LaunchedEffect(extraArtwork) {
    val mask = withContext(Dispatchers.IO) { IconMaskPolicy.snapshot(context).resolve(null) }
    val renderer = ColorOsFallbackRenderer(context)
    for (icon in ExtraIcon.entries) {
      extraArtwork[icon] = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        renderer.renderExtra(icon, IconPackComponent(icon.packageName, icon.activity), 192, mask) {
          caller.ensureActive()
        }
      }
    }
  }

  var query by rememberSaveable { mutableStateOf("") }
  val names = ExtraIcon.entries.associateWith { icon ->
    if (icon.labelRes != 0) stringResource(icon.labelRes) else icon.label
  }
  val term = query.trim()
  val visible = ExtraIcon.entries.filter { icon ->
    icon.label.contains(term, ignoreCase = true) || icon.packageName.contains(term, ignoreCase = true) ||
      names.getValue(icon).contains(term, ignoreCase = true)
  }
  Column(modifier.verticalScroll(rememberScrollState(), overscrollEffect = null).padding(padding),
    horizontalAlignment = Alignment.CenterHorizontally) {
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Os4SearchField(query = query, onQueryChange = { query = it }, label = stringResource(R.string.search_apps),
        inputModifier = Modifier.testTag("extra-icons.search"))
      if (visible.isEmpty()) {
        Text(stringResource(R.string.no_matching_apps), Modifier.padding(12.dp),
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
      } else {
        Card(Modifier.fillMaxWidth().os4ContentSize(), insideMargin = PaddingValues(0.dp)) {
          ExtraIconsRow(Modifier.padding(top = 12.dp, bottom = 6.dp),
            name = { ExtraColumnTitle(R.string.extra_icons_dark_preview, TextAlign.Start) },
            current = { ExtraColumnTitle(R.string.extra_icons_current) },
            extra = { ExtraColumnTitle(R.string.extra_icons_keihome) },
            toggle = { Spacer(Modifier.width(49.dp)) })
          visible.forEach { icon ->
            key(icon) {
              HorizontalDivider(Modifier.padding(horizontal = 12.dp))
              val name = names.getValue(icon)
              val enabled = icon.packageName in prefs.extraIconPackages
              val interactive = packs.nativeImporting == null
              val overridden = icon.packageName in prefs.preservedIconPackages ||
                custom.selections.any { it.packageName == icon.packageName }
              val currentAlpha by animateFloatAsState(if (enabled) 0.48f else 1f, Os4Motion.stateSpring(),
                label = "extra-current-alpha")
              val extraAlpha by animateFloatAsState(if (enabled) 1f else 0.72f, Os4Motion.stateSpring(),
                label = "extra-replacement-alpha")
              val extraScale by animateFloatAsState(if (enabled) 1.05f else 0.96f, Os4Motion.stateSpring(),
                label = "extra-replacement-scale")
              ExtraIconsRow(Modifier.testTag("extra-icons.row.${icon.packageName}")
                .os4Clickable(enabled = interactive) { model.preferences.setExtraIconEnabled(icon, !enabled) }
                .heightIn(min = 64.dp).padding(vertical = 8.dp), name = {
                  Text(name, style = MiuixTheme.textStyles.body2)
                  if (overridden) Text(stringResource(R.string.extra_icons_override),
                    style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }, current = {
                  val artwork = currentArtwork[icon]
                  Column(Modifier.graphicsLayer { alpha = currentAlpha },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    ExtraArtwork(
                      if (artwork?.original == true) R.string.extra_icons_original else R.string.extra_icons_current,
                      artwork?.bitmap, artwork == null)
                    if (artwork?.original == true) ExtraColumnTitle(R.string.extra_icons_original)
                  }
                }, extra = {
                  Box(Modifier.graphicsLayer {
                    alpha = extraAlpha
                    scaleX = extraScale
                    scaleY = extraScale
                  }) {
                    ExtraArtwork(R.string.extra_icons_title, extraArtwork[icon], icon !in extraArtwork)
                  }
                }, toggle = {
                  val description = stringResource(R.string.extra_icons_enable, name)
                  Switch(checked = enabled,
                    enabled = interactive,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("extra-icons.enable.${icon.packageName}")
                      .semantics { contentDescription = description },
                    onCheckedChange = { model.preferences.setExtraIconEnabled(icon, it) })
                })
            }
          }
        }
      }
    }
  }
}

private data class ExtraCurrentArtwork(val bitmap: Bitmap?, val original: Boolean)

@Composable
private fun ExtraIconsRow(
  modifier: Modifier,
  name: @Composable () -> Unit,
  current: @Composable () -> Unit,
  extra: @Composable () -> Unit,
  toggle: @Composable () -> Unit,
) {
  Row(modifier.fillMaxWidth().padding(horizontal = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) { name() }
    Box(Modifier.width(56.dp), contentAlignment = Alignment.Center, content = { current() })
    Box(Modifier.width(56.dp), contentAlignment = Alignment.Center, content = { extra() })
    Box(Modifier.width(49.dp), contentAlignment = Alignment.Center, content = { toggle() })
  }
}

@Composable
private fun ExtraColumnTitle(label: Int, alignment: TextAlign = TextAlign.Center) {
  Text(stringResource(label), textAlign = alignment, style = MiuixTheme.textStyles.footnote2,
    color = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
private fun ExtraArtwork(label: Int, bitmap: Bitmap?, loading: Boolean) {
  val name = stringResource(label)
  IconPreviewArtworkFrame(Modifier.size(40.dp)) {
    Crossfade(
      targetState = bitmap to loading,
      modifier = Modifier.fillMaxSize(),
      animationSpec = tween(180),
      label = "extra-artwork",
    ) { (image, isLoading) ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
          image != null -> Image(remember(image) { image.asImageBitmap() }, contentDescription = name,
            modifier = Modifier.fillMaxSize())
          isLoading -> {
            val status = stringResource(R.string.extra_icons_loading, name)
            Os4ShimmerPlaceholder(Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = status },
              cornerRadius = 10.dp)
          }
          else -> {
            val status = stringResource(R.string.extra_icons_unavailable, name)
            Text("\u2014", Modifier.clearAndSetSemantics { contentDescription = status },
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
          }
        }
      }
    }
  }
}
