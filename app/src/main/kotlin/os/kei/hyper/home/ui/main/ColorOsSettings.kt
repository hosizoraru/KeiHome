package os.kei.hyper.home.ui.main

import android.content.ComponentName
import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.ColorOsStyle
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.components.os4Clickable
import os.kei.hyper.home.ui.components.os4ContentSize
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ColorOsSettings(model: HomeViewModel, ready: Boolean, inspiration: Boolean = false) {
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val style = if (inspiration) ColorOsStyle.INSPIRATION else preferences.colorOsStyle
    .takeUnless { it.usesMonet } ?: ColorOsStyle.CLASSIC
  val art = inspiration || preferences.colorOsArtPlus
  val key = "coloros-${style.artworkKey}-art-${if (art) "on" else "off"}"
  val card = state.packs.firstOrNull { it.pack.builtinKey == key }
  val busy = state.nativeImporting != null
  Column(Modifier.os4ContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (!inspiration) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 16.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
          items(ColorOsStyle.entries.filterNot { it.usesMonet }, key = { it.key }) { option ->
            val preview = state.packs.firstOrNull {
              it.pack.builtinKey == "coloros-${option.key}-art-${if (art) "on" else "off"}"
            }
            val selected = option == style
            val borderColor by animateColorAsState(
              if (selected) MiuixTheme.colorScheme.primary else Color.Transparent,
              Os4Motion.colorSpring(),
              label = "coloros-style-border",
            )
            val labelColor by animateColorAsState(
              if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
              Os4Motion.colorSpring(),
              label = "coloros-style-label",
            )
            val tileScale by animateFloatAsState(
              if (selected) 1.03f else 1f,
              Os4Motion.stateSpring(),
              label = "coloros-style-scale",
            )
            Column(Modifier.width(82.dp).graphicsLayer { scaleX = tileScale; scaleY = tileScale }
              .squircleBorder(2.dp, borderColor, 18.dp)
              .os4Clickable(enabled = !busy, cornerRadius = 18.dp) { model.preferences.setColorOsStyle(option) }
              .padding(6.dp).semantics { this.selected = selected },
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(6.dp)) {
              (preview?.pack?.entries?.firstOrNull { it.component?.packageName == "com.android.camera" }
                ?: preview?.previewEntries?.firstOrNull())?.let {
                PackStyleThumbnail(preview!!.pack, it, model.packPreviews, preferences.iconPackAppearance)
              }
              Text(stringResource(colorOsStyleLabel(option)), style = MiuixTheme.textStyles.footnote2,
                color = labelColor)
            }
          }
        }
      }
    }
    Os4AnimatedVisibility(card != null && !inspiration) {
      if (card != null) Card(Modifier.fillMaxWidth().os4ContentSize(), insideMargin = PaddingValues(14.dp)) {
        Crossfade(targetState = Triple(style, art, card), animationSpec = tween(200), label = "coloros-preview-card") { (currentStyle, _, previewCard) ->
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ColorOS · ${stringResource(colorOsStyleLabel(currentStyle))}", style = MiuixTheme.textStyles.body1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              previewCard.previewEntries.forEach { entry ->
                PackIconPair(previewCard.pack, entry, model.packPreviews, preferences.iconPackAppearance, Modifier.weight(1f), compact = true)
              }
            }
          }
        }
      }
    }
    Os4AnimatedVisibility(card == null && state.scanned && !state.isSectionLoading("coloros")) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Text(stringResource(R.string.pack_local_unavailable), style = MiuixTheme.textStyles.footnote1,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
    if (!inspiration) IconPackAppearancePreference(preferences.iconPackAppearance,
      model.preferences::setIconPackAppearance, !busy, compact = true,
      staticAppearance = preferences.staticAppearanceIcons,
      onStaticAppearanceChange = model.preferences::setStaticAppearanceIcons)
    if (!inspiration) Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      SwitchPreference(title = stringResource(R.string.pack_art_plus),
        summary = stringResource(R.string.pack_art_summary), checked = preferences.colorOsArtPlus,
        enabled = !busy, onCheckedChange = model.preferences::setColorOsArtPlus)
    }
    val errorMessage = state.error?.takeIf { it.isNotBlank() }
    Os4AnimatedVisibility(errorMessage != null) {
      errorMessage?.let { message ->
        Text(message, modifier = Modifier.testTag("coloros.generation.error")
          .semantics { liveRegion = LiveRegionMode.Polite }, style = MiuixTheme.textStyles.footnote1,
          color = MiuixTheme.colorScheme.error)
      }
    }
    val result = state.nativeResult?.takeIf { !busy }?.takeIf {
      if (card == null) {
        it.sourceId.startsWith(style.importSourceId("", preferences.iconPackAppearance))
      } else it.sourceId == card.pack.id.let { id ->
        preferences.systemIconFallback.sourceId(style.importSourceId(id, preferences.iconPackAppearance))
      }
    }
    Os4AnimatedVisibility(result != null) {
      result?.let { currentResult ->
        Card(Modifier.fillMaxWidth().os4ContentSize(),
          insideMargin = PaddingValues(if (currentResult.imported) 0.dp else 16.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (currentResult.imported) {
              NativeThemeLink(currentResult, preferences.autoApplyIconPack, enabled = !busy,
                manualFallback = preferences.manualThemeFallback)
            } else {
              Text(stringResource(currentResult.failureLabel()),
                color = if (currentResult.pending) MiuixTheme.colorScheme.onSurfaceSecondary else MiuixTheme.colorScheme.error)
              if (currentResult.pending) TextButton(stringResource(R.string.icon_pack_recheck),
                onClick = model::recheckNativeIconImport)
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun HyperOsPackCard() {
  val context = LocalContext.current
  fun open(intent: Intent) {
    try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: android.content.ActivityNotFoundException) { }
  }
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.pack_hyperos), style = MiuixTheme.textStyles.body1)
        Text(stringResource(R.string.pack_hyperos_summary),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
      val colors = ButtonDefaults.textButtonColors(
        color = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f),
        textColor = MiuixTheme.colorScheme.primary)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(stringResource(R.string.pack_hyperos_theme_open),
          minHeight = 48.dp, cornerRadius = 24.dp, colors = colors,
          insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp), textStyle = MiuixTheme.textStyles.footnote1,
          onClick = { open(Intent().setComponent(ComponentName("com.android.thememanager",
            "com.android.thememanager.activity.ThemeTabActivity")).putExtra("REQUEST_RESOURCE_CODE", "icons")) })
        TextButton(stringResource(R.string.pack_hyperos_icons_open),
          minHeight = 48.dp, cornerRadius = 24.dp, colors = colors,
          insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp), textStyle = MiuixTheme.textStyles.footnote1,
          onClick = { open(Intent("com.miui.home.action.ICON_CUSTOMIZE").setPackage("com.miui.home")) })
      }
    }
  }
}

internal fun colorOsStyleLabel(style: ColorOsStyle): Int = when (style) {
  ColorOsStyle.CLASSIC -> R.string.pack_style_classic
  ColorOsStyle.DAYLIGHT -> R.string.pack_style_daylight
  ColorOsStyle.NIGHT_SHADOW -> R.string.pack_style_night
  ColorOsStyle.MATERIAL -> R.string.pack_style_material
  ColorOsStyle.PEBBLE -> R.string.pack_style_pebble
  ColorOsStyle.INSPIRATION -> R.string.pack_style_inspiration
}
