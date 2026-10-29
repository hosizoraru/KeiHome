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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import os.kei.hyper.home.R
import os.kei.hyper.home.data.MonetColorFamily
import os.kei.hyper.home.data.MonetPaletteSource
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.MonetReadState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MonetSummaryCard(
  snapshot: MonetPaletteSnapshot?,
  readFailed: Boolean,
  onClick: () -> Unit,
) {
  val current = snapshot.takeUnless { readFailed }
  // Only scalar values cross into the card; the source palette contains ordinary lists.
  val summary = MonetSummaryState(
    loading = snapshot == null && !readFailed,
    readFailed = readFailed,
    source = current?.source,
    style = current?.style,
    iconsEnabled = current?.monochrome?.enabled,
    primary = current?.activePalette?.sample("primary", 60),
    secondary = current?.activePalette?.sample("secondary", 60),
    tertiary = current?.activePalette?.sample("tertiary", 60),
    neutral = current?.activePalette?.sample("neutral", 80),
    lastAppliedRevision = current?.lastAppliedRevision,
  )
  MonetSummaryContent(summary, onClick)
}

@Immutable
private data class MonetSummaryState(
  val loading: Boolean,
  val readFailed: Boolean,
  val source: String?,
  val style: String?,
  val iconsEnabled: Boolean?,
  val primary: Int?,
  val secondary: Int?,
  val tertiary: Int?,
  val neutral: Int?,
  val lastAppliedRevision: Long?,
)

private fun MonetPaletteSource.sample(family: String, tone: Int): Int? {
  if (state != MonetReadState.AVAILABLE) return null
  return families.firstOrNull { it.name == family }?.colors
    ?.minByOrNull { color -> color.tone?.let { kotlin.math.abs(it - tone) } ?: Int.MAX_VALUE }
    ?.value
}

@Composable
private fun MonetSummaryContent(summary: MonetSummaryState, onClick: () -> Unit) {
  val openDetailsLabel = stringResource(R.string.monet_open_details)
  Card(
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(16.dp),
    onClick = onClick,
    showIndication = true,
  ) {
    Column(
      modifier = Modifier.semantics { this.onClick(label = openDetailsLabel, action = null) },
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(
          imageVector = MiuixIcons.Theme,
          modifier = Modifier.size(24.dp),
          tint = MiuixTheme.colorScheme.primary,
          contentDescription = null,
        )
        Text(
          text = stringResource(R.string.settings_monet_title),
          modifier = Modifier.weight(1f),
          style = MiuixTheme.textStyles.body1,
        )
        Icon(
          MiuixIcons.Basic.ArrowRight,
          contentDescription = null,
          tint = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
      }
      if (summary.loading || summary.readFailed) {
        Text(
          text = stringResource(
            if (summary.readFailed) R.string.monet_unavailable else R.string.monet_loading,
          ),
          style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
      } else {
        Text(
          text = stringResource(
            R.string.polish_monet_scheme,
            monetSourceLabel(summary.source),
            monetStyleLabel(summary.style),
          ),
          style = MiuixTheme.textStyles.body2,
        )
        Text(
          text = stringResource(
            R.string.polish_monet_icons,
            stringResource(when (summary.iconsEnabled) {
              true -> R.string.monet_themed_icons_on
              false -> R.string.monet_themed_icons_off
              null -> R.string.monet_themed_icons_unknown
            }),
          ),
          style = MiuixTheme.textStyles.footnote1,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
        if (summary.primary != null || summary.secondary != null ||
          summary.tertiary != null || summary.neutral != null
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            SummarySwatch(summary.primary, stringResource(R.string.monet_family_primary))
            SummarySwatch(summary.secondary, stringResource(R.string.monet_family_secondary))
            SummarySwatch(summary.tertiary, stringResource(R.string.monet_family_tertiary))
            SummarySwatch(summary.neutral, stringResource(R.string.monet_family_neutral))
          }
        } else {
          Text(stringResource(R.string.polish_monet_no_palette),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        LastAppliedPalette(summary.lastAppliedRevision)
      }
    }
  }
}

@Composable
private fun SummarySwatch(color: Int?, label: String) {
  if (color == null) return
  val description = stringResource(
    R.string.monet_swatch_description, label, "#%08X".format(color),
  )
  Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(Color(color))
    .semantics { contentDescription = description })
}

@Composable
private fun monetSourceLabel(source: String?): String = stringResource(when (source) {
  "HOME_WALLPAPER", "home_wallpaper" -> R.string.monet_source_home
  "LOCK_WALLPAPER", "lock_wallpaper" -> R.string.monet_source_lock
  "BASIC_COLOR", "preset" -> R.string.monet_source_basic
  else -> R.string.polish_monet_source_unknown
})

@Composable
private fun monetStyleLabel(style: String?): String = when (style) {
  "TONAL_SPOT" -> stringResource(R.string.monet_style_tonal)
  "VIBRANT" -> stringResource(R.string.monet_style_vibrant)
  "NEUTRAL", "SPRITZ" -> stringResource(R.string.monet_style_neutral)
  "MONOCHROME", "MONOCHROMATIC" -> stringResource(R.string.monet_style_mono)
  "EXPRESSIVE" -> stringResource(R.string.monet_style_expressive)
  "RAINBOW" -> stringResource(R.string.monet_style_rainbow)
  "FRUIT_SALAD" -> stringResource(R.string.monet_style_fruit_salad)
  else -> style?.takeIf { it.isNotBlank() } ?: stringResource(R.string.monet_unknown)
}

@Immutable
private data class RelativePaletteAge(val unit: AgeUnit, val amount: Int)
private enum class AgeUnit { NOW, MINUTES, HOURS, DAYS }

private fun paletteAge(timestamp: Long, now: Long): RelativePaletteAge {
  val minutes = ((now - timestamp).coerceAtLeast(0) / 60_000).toInt()
  return when {
    minutes == 0 -> RelativePaletteAge(AgeUnit.NOW, 0)
    minutes < 60 -> RelativePaletteAge(AgeUnit.MINUTES, minutes)
    minutes < 1_440 -> RelativePaletteAge(AgeUnit.HOURS, minutes / 60)
    else -> RelativePaletteAge(AgeUnit.DAYS, minutes / 1_440)
  }
}

@Composable
private fun LastAppliedPalette(timestamp: Long?) {
  if (timestamp == null) {
    Text(stringResource(R.string.polish_monet_no_application),
      style = MiuixTheme.textStyles.footnote1,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
    return
  }
  val lifecycleOwner = LocalLifecycleOwner.current
  // Keep the clock in this leaf. Equal age buckets do not invalidate the surrounding cards.
  var age by remember(timestamp) { mutableStateOf(paletteAge(timestamp, System.currentTimeMillis())) }
  LaunchedEffect(timestamp, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        val now = System.currentTimeMillis()
        age = paletteAge(timestamp, now)
        delay(60_000 - (now - timestamp).coerceAtLeast(0) % 60_000)
      }
    }
  }
  val relative = when (age.unit) {
    AgeUnit.NOW -> stringResource(R.string.polish_time_now)
    AgeUnit.MINUTES -> pluralStringResource(R.plurals.polish_time_minutes, age.amount, age.amount)
    AgeUnit.HOURS -> pluralStringResource(R.plurals.polish_time_hours, age.amount, age.amount)
    AgeUnit.DAYS -> pluralStringResource(R.plurals.polish_time_days, age.amount, age.amount)
  }
  Text(
    text = stringResource(R.string.polish_monet_last_applied, relative),
    style = MiuixTheme.textStyles.footnote1,
    color = MiuixTheme.colorScheme.onSurfaceSecondary,
  )
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
              .clip(RoundedCornerShape(4.dp))
              .background(Color(color.value))
              .semantics { contentDescription = description },
          )
        }
    }
  }
}
