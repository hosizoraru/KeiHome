package os.kei.hyper.home.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.data.MonetColorFamily
import os.kei.hyper.home.data.MonetPaletteSource
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.MonetReadState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MonetContent(
  snapshot: MonetPaletteSnapshot?,
  readFailed: Boolean,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SmallTitle(
      text = stringResource(R.string.monet_overview_title),
      modifier = Modifier.semantics { heading() },
    )
    MonetOverviewCard(snapshot, readFailed)
    SmallTitle(
      text = stringResource(R.string.monet_detail_section),
      modifier = Modifier.semantics { heading() },
    )
    MonetDetailCard(snapshot, readFailed)
  }
}

@Composable
internal fun MonetSummaryCard(
  snapshot: MonetPaletteSnapshot?,
  readFailed: Boolean,
  onClick: () -> Unit,
) {
  val openDetailsLabel = stringResource(R.string.monet_open_details)
  Card(
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(0.dp),
    onClick = onClick,
    showIndication = true,
  ) {
    Row(
      modifier = Modifier
        .semantics { this.onClick(label = openDetailsLabel, action = null) }
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        imageVector = MiuixIcons.Theme,
        modifier = Modifier.size(26.dp),
        tint = MiuixTheme.colorScheme.primary,
        contentDescription = null,
      )
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = stringResource(R.string.settings_monet_title),
          style = MiuixTheme.textStyles.body1,
        )
        Text(
          text = paletteStatus(snapshot, readFailed),
          style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
      }
    }
  }
}

@Composable
private fun MonetOverviewCard(
  snapshot: MonetPaletteSnapshot?,
  readFailed: Boolean,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(0.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        imageVector = MiuixIcons.Theme,
        modifier = Modifier.size(28.dp),
        tint = MiuixTheme.colorScheme.primary,
        contentDescription = null,
      )
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = paletteStatus(snapshot, readFailed),
          style = MiuixTheme.textStyles.title3,
        )
        Text(
          text = stringResource(R.string.monet_experimental_note),
          style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
      }
    }
  }
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
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(0.dp),
  ) {
    if (snapshot == null || readFailed) {
      Text(
        text = paletteStatus(snapshot, readFailed),
        modifier = Modifier.padding(16.dp),
        style = MiuixTheme.textStyles.body1,
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
      )
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
                .clip(RoundedCornerShape(4.dp))
                .background(Color(color))
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
        val source = if (snapshot.launcherPalette.state == MonetReadState.AVAILABLE) {
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
          snapshot.style ?: stringResource(R.string.monet_unknown),
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
        .filterIndexed { index, _ -> index % 4 == 0 }
        .take(6)
        .forEach { color ->
          val hex = "#%08X".format(color.value)
          val description = color.tone?.let { tone ->
            stringResource(R.string.monet_swatch_tone_description, name, tone, hex)
          } ?: stringResource(R.string.monet_swatch_description, name, hex)
          Box(
            modifier = Modifier
              .size(18.dp)
              .clip(RoundedCornerShape(4.dp))
              .background(Color(color.value))
              .semantics { contentDescription = description },
          )
        }
    }
  }
}
