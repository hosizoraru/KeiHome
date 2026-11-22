package os.kei.hyper.home.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import os.kei.hyper.home.R
import os.kei.hyper.home.data.MonetSchemes
import os.kei.hyper.home.data.MonetCustomization
import os.kei.hyper.home.data.MonetIconRole
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Copy
import os.kei.hyper.home.ui.components.Os4CategoryTabs
import os.kei.hyper.home.ui.components.IconPreviewMode
import os.kei.hyper.home.ui.components.IconPreviewModeTabs
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val PaletteFamilies = listOf("primary", "secondary", "tertiary", "neutral", "neutral-variant")
// Android's 13 reference tones, expressed as HCT tone (not resource suffix 0..1000).
private val PaletteTones = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)

/** Inspect only the draft palette supplied by the picker; reported device colors stay separate. */
@Composable
internal fun MonetPaletteDetails(
  seed: Int, palette: Map<String, Int>, modifier: Modifier = Modifier, choice: MonetCustomization? = null,
) {
  var familyIndex by rememberSaveable { mutableIntStateOf(0) }
  var appearance by rememberSaveable { mutableIntStateOf(IconPreviewMode.COMPARE.ordinal) }
  val familyNames = listOf(
    stringResource(R.string.monet_family_primary),
    stringResource(R.string.monet_family_secondary),
    stringResource(R.string.monet_family_tertiary),
    stringResource(R.string.monet_family_neutral),
    stringResource(R.string.monet_family_neutral_variant),
  )
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(R.string.monet_details_draft_note),
      modifier = Modifier.padding(horizontal = 16.dp),
      style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      MonetCopyableColor(stringResource(R.string.monet_details_seed), seed,
        modifier = Modifier.testTag("monet.details.seed"))
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      Text(stringResource(R.string.monet_details_roles), modifier = Modifier.padding(16.dp),
        style = MiuixTheme.textStyles.body1)
      IconPreviewModeTabs(IconPreviewMode.entries[appearance], { appearance = it.ordinal },
        modifier = Modifier.padding(horizontal = 16.dp).testTag("monet.roles.views"))
      for (dark in listOf(false, true)) {
        if ((appearance == IconPreviewMode.LIGHT.ordinal && dark) ||
          (appearance == IconPreviewMode.DARK.ordinal && !dark)) continue
        val roles = remember(palette, dark, choice) { MonetSchemes.iconRoles(palette, dark, choice) } ?: continue
        val foregroundRole = if (dark) MonetIconRole.DARK_FOREGROUND else MonetIconRole.LIGHT_FOREGROUND
        val backgroundRole = if (dark) MonetIconRole.DARK_BACKGROUND else MonetIconRole.LIGHT_BACKGROUND
        Text(stringResource(if (dark) R.string.monet_preview_dark else R.string.monet_preview_light),
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          style = MiuixTheme.textStyles.body2)
        MonetCopyableColor(
          label = if (choice?.iconOverrides?.containsKey(foregroundRole) == true) roleName(foregroundRole)
            else stringResource(R.string.monet_details_foreground,
              familyName(foregroundRole.family(choice)), foregroundRole.tone),
          color = roles.foreground,
          modifier = Modifier.testTag("monet.role.${if (dark) "dark" else "light"}.foreground"),
        )
        MonetCopyableColor(
          label = if (choice?.iconOverrides?.containsKey(backgroundRole) == true) roleName(backgroundRole)
            else stringResource(R.string.monet_details_background,
              familyName(backgroundRole.family(choice)), backgroundRole.tone),
          color = roles.background,
          modifier = Modifier.testTag("monet.role.${if (dark) "dark" else "light"}.background"),
        )
      }
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      Text(stringResource(R.string.monet_details_family), modifier = Modifier.padding(16.dp),
        style = MiuixTheme.textStyles.body2)
      Os4CategoryTabs(items = familyNames, selectedIndex = familyIndex,
        onSelect = { familyIndex = it },
        modifier = Modifier.padding(horizontal = 16.dp).testTag("monet.tones.views"))
      Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.monet_details_tones_note),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
          PaletteTones.forEach { tone ->
            val key = "${PaletteFamilies[familyIndex]}$tone"
            palette[key]?.let { color ->
              MonetToneValue(familyNames[familyIndex], tone, color,
                modifier = Modifier.testTag("monet.tone.$key"))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MonetCopyableColor(label: String, color: Int, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val hex = monetHex(color)
  BasicComponent(
    modifier = modifier,
    title = label,
    summary = hex,
    onClickLabel = stringResource(R.string.monet_details_copy, label, hex),
    onClick = {
      context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(label, hex))
    },
    endActions = {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MonetColorSample(color)
        Icon(MiuixIcons.Glass.Copy, contentDescription = null, modifier = Modifier.size(20.dp),
          tint = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    },
  )
}

@Composable
private fun MonetToneValue(family: String, tone: Int, color: Int, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val label = stringResource(R.string.monet_details_tone, tone)
  val hex = monetHex(color)
  val copyLabel = stringResource(R.string.monet_details_copy, "$family · $label", hex)
  Card(
    modifier = modifier.widthIn(min = 96.dp)
      .semantics { onClick(label = copyLabel, action = null) },
    insideMargin = PaddingValues(10.dp),
    onClick = {
      context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("$family · $label", hex))
    },
    showIndication = true,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      MonetColorSample(color, Modifier.size(width = 60.dp, height = 20.dp))
      Text(label, style = MiuixTheme.textStyles.footnote1)
      Text(hex, style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}

@Composable
private fun MonetColorSample(color: Int, modifier: Modifier = Modifier.size(28.dp)) {
  Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(color))
    .border(1.dp, MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.2f), RoundedCornerShape(6.dp)))
}

// Keep copy values locale independent; preserve alpha if a non-opaque source is ever supplied.
private fun monetHex(color: Int): String = if (color ushr 24 == 0xff) {
  String.format(Locale.ROOT, "#%06X", color and 0xffffff)
} else {
  String.format(Locale.ROOT, "#%08X", color)
}
