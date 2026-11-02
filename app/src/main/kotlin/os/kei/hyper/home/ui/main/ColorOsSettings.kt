package os.kei.hyper.home.ui.main

import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.ColorOsStyle
import os.kei.hyper.home.status.IconPackImportProtocol
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ColorOsSettings(model: HomeViewModel, ready: Boolean, inspiration: Boolean = false) {
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val style = if (inspiration) ColorOsStyle.INSPIRATION else preferences.colorOsStyle
    .takeUnless { it.usesMonet } ?: ColorOsStyle.CLASSIC
  val art = inspiration || preferences.colorOsArtPlus
  val key = "coloros-${style.artworkKey}-art-${if (art) "on" else "off"}"
  val card = state.packs.firstOrNull { it.pack.builtinKey == key }
  val busy = state.nativeImporting != null
  var nativeExpanded by rememberSaveable { mutableStateOf(false) }
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
              .clickable(enabled = !busy) { model.preferences.setColorOsStyle(option) }
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
    if (card != null && !inspiration) Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ColorOS · ${stringResource(colorOsStyleLabel(style))}", style = MiuixTheme.textStyles.title3)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          card.previewEntries.forEach { entry ->
            PackIconPair(card.pack, entry, model.packPreviews, preferences.iconPackAppearance, Modifier.weight(1f))
          }
        }
        Text(stringResource(if (inspiration) R.string.pack_inspiration_summary else R.string.pack_coloros_plain),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
    IconPackAppearancePreference(preferences.iconPackAppearance, model.preferences::setIconPackAppearance, !busy,
      compact = inspiration)
    if (!inspiration) Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      SwitchPreference(title = stringResource(R.string.pack_art_plus),
        summary = stringResource(R.string.pack_art_summary), checked = preferences.colorOsArtPlus,
        enabled = !busy, onCheckedChange = model.preferences::setColorOsArtPlus)
    }
    if (inspiration) TextButton(stringResource(if (nativeExpanded) R.string.icon_pack_hide_details
      else R.string.pack_inspiration_install), onClick = { nativeExpanded = !nativeExpanded })
    if (!inspiration || nativeExpanded) Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (card == null) Text(stringResource(R.string.pack_local_unavailable))
        TextButton(stringResource(if (busy) R.string.icon_pack_importing else R.string.pack_generate),
          onClick = {
            if (!inspiration) model.preferences.setColorOsStyle(style)
            model.applyColorOs(inspiration)
          }, enabled = ready && card != null && !busy)
        val result = state.nativeResult?.takeIf {
          it.sourceId == card?.pack?.id?.let { id ->
            preferences.systemIconFallback.sourceId(style.importSourceId(id, preferences.iconPackAppearance))
          }
        }
        if (result?.imported == true) {
          result.localId?.let { nativeId ->
            TextButton(stringResource(R.string.pack_apply_native), onClick = {
              context.startActivity(IconPackImportProtocol.nativeDetail(nativeId))
            })
          }
        } else if (result != null) {
          Text(stringResource(if (result.status == "UNCERTAIN") R.string.icon_pack_native_uncertain
            else R.string.icon_pack_native_failed), color = MiuixTheme.colorScheme.error)
          if (result.status == "UNCERTAIN") TextButton(stringResource(R.string.icon_pack_recheck),
            onClick = model::recheckNativeIconImport)
        }
        Text(stringResource(R.string.pack_apply_note), style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
  }
}

@Composable
internal fun HyperOsPackCard() {
  val context = LocalContext.current
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    Column {
      Text(stringResource(R.string.pack_hyperos), modifier = Modifier.padding(16.dp),
        style = MiuixTheme.textStyles.body1)
      Text(stringResource(R.string.pack_hyperos_summary), modifier = Modifier.padding(horizontal = 16.dp),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      TextButton(stringResource(R.string.pack_hyperos_open), modifier = Modifier.padding(16.dp),
        onClick = {
          context.startActivity(Intent().setComponent(ComponentName("com.miui.home",
            "com.miui.home.settings.MiuiHomeSettingActivity")))
        })
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
