package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.data.MonetColorFamily
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.MonetPaletteSource
import os.kei.hyper.home.data.MonetReadState
import os.kei.hyper.home.ui.components.Os4ShimmerPlaceholder
import os.kei.hyper.home.ui.components.os4ContentSize
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun monetStyleLabel(style: String?): String = when (style) {
  "TONAL_SPOT" -> stringResource(R.string.monet_style_tonal)
  "VIBRANT" -> stringResource(R.string.monet_style_vibrant)
  "NEUTRAL", "SPRITZ" -> stringResource(R.string.monet_style_neutral)
  "MONOCHROME", "MONOCHROMATIC" -> stringResource(R.string.monet_style_mono)
  "EXPRESSIVE" -> stringResource(R.string.monet_style_expressive)
  "RAINBOW" -> stringResource(R.string.monet_style_rainbow)
  "FRUIT_SALAD" -> stringResource(R.string.monet_style_fruit_salad)
  "COLOROS_INSPIRATION" -> stringResource(R.string.pack_style_inspiration)
  else -> style?.takeIf { it.isNotBlank() } ?: stringResource(R.string.monet_unknown)
}

@Composable
private fun paletteStatus(snapshot: MonetPaletteSnapshot?, readFailed: Boolean): String = when {
  readFailed -> stringResource(R.string.monet_unavailable)
  snapshot == null -> stringResource(R.string.monet_loading)
  snapshot.launcherPalette.state == MonetReadState.AVAILABLE ||
    snapshot.frameworkPalette.state == MonetReadState.AVAILABLE ->
    stringResource(R.string.monet_palette_available)
  else -> stringResource(R.string.monet_unavailable)
}

@Composable
internal fun MonetDetailCard(
  snapshot: MonetPaletteSnapshot?,
  readFailed: Boolean,
) {
  Card(
    modifier = Modifier.fillMaxWidth().os4ContentSize(),
    insideMargin = PaddingValues(0.dp),
  ) {
    if (snapshot == null || readFailed) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = paletteStatus(snapshot, readFailed),
          style = MiuixTheme.textStyles.body1,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
        if (snapshot == null && !readFailed) {
          Os4ShimmerPlaceholder(Modifier.fillMaxWidth(0.6f).height(14.dp), cornerRadius = 6.dp)
          Os4ShimmerPlaceholder(Modifier.fillMaxWidth(0.85f).height(14.dp), cornerRadius = 6.dp)
        }
      }
      return@Card
    }

    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      MonetMetric(
        label = stringResource(R.string.monet_wallpaper_colors),
        value = wallpaperColorsValue(snapshot),
      )
      if (snapshot.wallpaper.state == MonetReadState.AVAILABLE) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          snapshot.wallpaper.colors.forEach { color ->
            val hex = "#%06X".format(color and 0xFFFFFF)
            val description = stringResource(R.string.monet_wallpaper_color_description, hex)
            Box(
              modifier = Modifier
                .size(24.dp)
                .squircleBackground(Color(color), 6.dp)
                .semantics { contentDescription = description },
            )
          }
        }
      }
      MonetMetric(
        label = stringResource(R.string.monet_launcher_palette),
        value = paletteValue(snapshot.launcherPalette),
      )
      MonetMetric(
        label = stringResource(R.string.monet_framework_palette),
        value = paletteValue(snapshot.frameworkPalette),
      )
      MonetMetric(
        label = stringResource(R.string.monet_themed_icons),
        value = themedIconValue(snapshot),
      )
      if (snapshot.activePalette.state == MonetReadState.AVAILABLE) {
        val source = if (snapshot.selectedPalette != null) {
          stringResource(R.string.monet_selected_palette)
        } else if (snapshot.launcherPalette.state == MonetReadState.AVAILABLE) {
          stringResource(R.string.monet_launcher_palette)
        } else {
          stringResource(R.string.monet_framework_palette)
        }
        Text(
          text = stringResource(R.string.monet_source, source),
          style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          snapshot.families.forEach { family ->
            MonetFamilySwatch(family)
          }
        }
      }
      Text(
        text = stringResource(
          R.string.monet_current_scheme,
          monetStyleLabel(snapshot.style),
        ),
        style = MiuixTheme.textStyles.body2,
      )
      snapshot.monochrome.colorHex?.let { color ->
        Text(
          text = stringResource(R.string.monet_icon_color, color),
          style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
      }
      Text(
        text = stringResource(R.string.monet_icon_rendering_note),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
      )
    }
  }
}

@Composable
private fun wallpaperColorsValue(snapshot: MonetPaletteSnapshot): String =
  when (snapshot.wallpaper.state) {
    MonetReadState.AVAILABLE -> stringResource(
      R.string.monet_wallpaper_colors_value,
      snapshot.wallpaper.colors.size,
    )
    MonetReadState.NOT_PROVIDED -> stringResource(R.string.monet_wallpaper_not_provided)
    MonetReadState.UNAVAILABLE -> stringResource(R.string.monet_wallpaper_unavailable)
  }

@Composable
private fun paletteValue(palette: MonetPaletteSource): String =
  when (palette.state) {
    MonetReadState.AVAILABLE -> stringResource(
      R.string.monet_state_available,
      palette.generatedColorCount,
    )
    MonetReadState.NOT_PROVIDED -> stringResource(R.string.monet_state_not_provided)
    MonetReadState.UNAVAILABLE -> stringResource(R.string.monet_state_unavailable)
  }

@Composable
private fun themedIconValue(snapshot: MonetPaletteSnapshot): String =
  when (snapshot.monochrome.enabled) {
    true -> stringResource(R.string.monet_themed_icons_on)
    false -> stringResource(R.string.monet_themed_icons_off)
    null -> stringResource(R.string.monet_themed_icons_unknown)
  }

@Composable
private fun MonetMetric(label: String, value: String) {
  Column {
    Text(
      text = label,
      style = MiuixTheme.textStyles.footnote1,
      color = MiuixTheme.colorScheme.onSurfaceSecondary,
    )
    Text(text = value, style = MiuixTheme.textStyles.body1)
  }
}

@Composable
private fun MonetFamilySwatch(family: MonetColorFamily) {
  val name = when (family.name) {
    "primary" -> stringResource(R.string.monet_family_primary)
    "secondary" -> stringResource(R.string.monet_family_secondary)
    "tertiary" -> stringResource(R.string.monet_family_tertiary)
    "neutral" -> stringResource(R.string.monet_family_neutral)
    "neutral-variant" -> stringResource(R.string.monet_family_neutral_variant)
    else -> family.name
  }
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = name,
      modifier = Modifier.weight(1f),
      style = MiuixTheme.textStyles.footnote1,
      color = MiuixTheme.colorScheme.onSurfaceSecondary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      family.colors
        .filter { it.tone in listOf(0, 20, 40, 60, 80, 100) }
        .take(6)
        .forEach { color ->
          val hex = "#%08X".format(color.value)
          val description = color.tone?.let { tone ->
            stringResource(R.string.monet_swatch_tone_description, name, tone, hex)
          } ?: stringResource(R.string.monet_swatch_description, name, hex)
          Box(
            modifier = Modifier
              .size(18.dp)
              .squircleBackground(Color(color.value), 5.dp)
              .semantics { contentDescription = description },
          )
        }
    }
  }
}
