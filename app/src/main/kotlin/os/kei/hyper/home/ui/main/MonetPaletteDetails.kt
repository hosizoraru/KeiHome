package os.kei.hyper.home.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import top.yukonga.miuix.kmp.icon.glass.Ok
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4CategoryTabs
import os.kei.hyper.home.ui.components.IconPreviewMode
import os.kei.hyper.home.ui.components.IconPreviewModeTabs
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberCopyFeedback
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

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
    Card(Modifier.fillMaxWidth().os4ContentSize(), insideMargin = PaddingValues(0.dp)) {
      Text(stringResource(R.string.monet_details_roles), modifier = Modifier.padding(16.dp),
        style = MiuixTheme.textStyles.body1)
      IconPreviewModeTabs(IconPreviewMode.entries[appearance], { appearance = it.ordinal },
        modifier = Modifier.padding(horizontal = 16.dp).testTag("monet.roles.views"))
      for (dark in listOf(false, true)) {
        val visible = !((appearance == IconPreviewMode.LIGHT.ordinal && dark) ||
          (appearance == IconPreviewMode.DARK.ordinal && !dark))
        val roles = remember(palette, dark, choice) { MonetSchemes.iconRoles(palette, dark, choice) }
        Os4AnimatedVisibility(visible = visible && roles != null, label = "monet-role-${if (dark) "dark" else "light"}") {
          val resolved = roles ?: return@Os4AnimatedVisibility
          val foregroundRole = if (dark) MonetIconRole.DARK_FOREGROUND else MonetIconRole.LIGHT_FOREGROUND
          val backgroundRole = if (dark) MonetIconRole.DARK_BACKGROUND else MonetIconRole.LIGHT_BACKGROUND
          Column {
            Text(stringResource(if (dark) R.string.monet_preview_dark else R.string.monet_preview_light),
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
              style = MiuixTheme.textStyles.body2)
            MonetCopyableColor(
              label = if (choice?.iconOverrides?.containsKey(foregroundRole) == true) roleName(foregroundRole)
                else stringResource(R.string.monet_details_foreground,
                  familyName(foregroundRole.family(choice)), foregroundRole.tone),
              color = resolved.foreground,
              modifier = Modifier.testTag("monet.role.${if (dark) "dark" else "light"}.foreground"),
            )
            MonetCopyableColor(
              label = if (choice?.iconOverrides?.containsKey(backgroundRole) == true) roleName(backgroundRole)
                else stringResource(R.string.monet_details_background,
                  familyName(backgroundRole.family(choice)), backgroundRole.tone),
              color = resolved.background,
              modifier = Modifier.testTag("monet.role.${if (dark) "dark" else "light"}.background"),
            )
          }
        }
      }
    }
    Card(Modifier.fillMaxWidth().os4ContentSize(), insideMargin = PaddingValues(0.dp)) {
      Text(stringResource(R.string.monet_details_family), modifier = Modifier.padding(16.dp),
        style = MiuixTheme.textStyles.body2)
      Os4CategoryTabs(items = familyNames, selectedIndex = familyIndex,
        onSelect = { familyIndex = it },
        modifier = Modifier.padding(horizontal = 16.dp).testTag("monet.tones.views"))
      Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.monet_details_tones_note),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Crossfade(targetState = familyIndex, animationSpec = tween(180), label = "monet-tones-family") { selectedFamily ->
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
            PaletteTones.forEach { tone ->
              val key = "${PaletteFamilies[selectedFamily]}$tone"
              palette[key]?.let { color ->
                MonetToneValue(familyNames[selectedFamily], tone, color,
                  modifier = Modifier.testTag("monet.tone.$key"))
              }
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
  val copyFeedback = rememberCopyFeedback()
  BasicComponent(
    modifier = modifier,
    title = label,
    summary = hex,
    onClickLabel = stringResource(R.string.monet_details_copy, label, hex),
    onClick = {
      context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(label, hex))
      copyFeedback.trigger()
    },
    endActions = {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MonetColorSample(color)
        Crossfade(targetState = copyFeedback.copied, animationSpec = tween(160), label = "monet-copy-icon") { copied ->
          Icon(
            imageVector = if (copied) MiuixIcons.Glass.Ok else MiuixIcons.Glass.Copy,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (copied) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
          )
        }
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
  val copyFeedback = rememberCopyFeedback()
  val borderColor by animateColorAsState(
    targetValue = if (copyFeedback.copied) MiuixTheme.colorScheme.primary else Color.Transparent,
    animationSpec = Os4Motion.colorSpring(),
    label = "monet-tone-copied",
  )
  Card(
    modifier = modifier.widthIn(min = 96.dp)
      .squircleBorder(1.5.dp, borderColor, 16.dp)
      .semantics { onClick(label = copyLabel, action = null) },
    insideMargin = PaddingValues(10.dp),
    onClick = {
      context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("$family · $label", hex))
      copyFeedback.trigger()
    },
    pressFeedbackType = PressFeedbackType.Sink,
    showIndication = true,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      MonetColorSample(color, Modifier.size(width = 60.dp, height = 20.dp))
      Text(label, style = MiuixTheme.textStyles.footnote1)
      Text(hex, style = MiuixTheme.textStyles.footnote1,
        color = if (copyFeedback.copied) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}

@Composable
private fun MonetColorSample(color: Int, modifier: Modifier = Modifier.size(28.dp)) {
  val animated by animateColorAsState(Color(color), Os4Motion.colorSpring(), label = "monet-sample")
  Box(modifier.squircleBackground(animated, 7.dp)
    .squircleBorder(1.dp, MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.2f), 7.dp))
}

// Keep copy values locale independent; preserve alpha if a non-opaque source is ever supplied.
private fun monetHex(color: Int): String = if (color ushr 24 == 0xff) {
  String.format(Locale.ROOT, "#%06X", color and 0xffffff)
} else {
  String.format(Locale.ROOT, "#%08X", color)
}
