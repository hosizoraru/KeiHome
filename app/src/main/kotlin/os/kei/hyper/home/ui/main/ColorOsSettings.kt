package os.kei.hyper.home.ui.main

import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.ColorOsStyle
import os.kei.hyper.home.ui.components.os4Clickable
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
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
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (!inspiration) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 16.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
          items(ColorOsStyle.entries.filterNot { it.usesMonet }, key = { it.key }) { option ->
            val preview = state.packs.firstOrNull {
              it.pack.builtinKey == "coloros-${option.key}-art-${if (art) "on" else "off"}"
            }
            val selected = option == style
            Column(Modifier.width(82.dp).border(2.dp,
              if (selected) MiuixTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(18.dp))
              .os4Clickable(enabled = !busy) { model.preferences.setColorOsStyle(option) }
              .padding(6.dp).semantics { this.selected = selected },
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(6.dp)) {
              (preview?.pack?.entries?.firstOrNull { it.component?.packageName == "com.android.camera" }
                ?: preview?.previewEntries?.firstOrNull())?.let {
                PackStyleThumbnail(preview!!.pack, it, model.packPreviews, preferences.iconPackAppearance)
              }
              Text(stringResource(colorOsStyleLabel(option)), style = MiuixTheme.textStyles.footnote2)
            }
          }
        }
      }
    }
    if (card != null && !inspiration) Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ColorOS · ${stringResource(colorOsStyleLabel(style))}", style = MiuixTheme.textStyles.body1)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          card.previewEntries.forEach { entry ->
            PackIconPair(card.pack, entry, model.packPreviews, preferences.iconPackAppearance, Modifier.weight(1f), compact = true)
          }
        }
      }
    }
    if (!inspiration) IconPackAppearancePreference(preferences.iconPackAppearance,
      model.preferences::setIconPackAppearance, !busy, compact = true)
    if (!inspiration) Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      SwitchPreference(title = stringResource(R.string.pack_art_plus),
        summary = stringResource(R.string.pack_art_summary), checked = preferences.colorOsArtPlus,
        enabled = !busy, onCheckedChange = model.preferences::setColorOsArtPlus)
    }
    state.colorOsProgress?.takeIf { state.preparing }?.let { progress ->
      PackGenerationStatus(progress, model::cancelIconPackPreparation)
    }
    state.error?.takeIf { it.isNotBlank() }?.let { message ->
      Text(message, modifier = Modifier.testTag("coloros.generation.error")
        .semantics { liveRegion = LiveRegionMode.Polite }, style = MiuixTheme.textStyles.footnote1)
    }
    val result = state.nativeResult?.takeIf { !busy }?.takeIf {
      if (card == null) {
        it.sourceId.startsWith(style.importSourceId("", preferences.iconPackAppearance))
      } else it.sourceId == card.pack.id.let { id ->
        preferences.systemIconFallback.sourceId(style.importSourceId(id, preferences.iconPackAppearance))
      }
    }
    if (result != null) Card(Modifier.fillMaxWidth(),
      insideMargin = PaddingValues(if (result?.imported == true) 0.dp else 16.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (result?.imported == true) {
          NativeThemeLink(result, preferences.autoApplyIconPack, enabled = !busy)
        } else if (result != null) {
          Text(stringResource(if (result.pending) R.string.icon_pack_native_uncertain
            else R.string.icon_pack_native_failed),
            color = if (result.pending) MiuixTheme.colorScheme.onSurfaceSecondary else MiuixTheme.colorScheme.error)
          if (result.pending) TextButton(stringResource(R.string.icon_pack_recheck),
            onClick = model::recheckNativeIconImport)
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
