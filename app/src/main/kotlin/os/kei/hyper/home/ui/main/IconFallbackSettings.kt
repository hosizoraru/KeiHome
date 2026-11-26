package os.kei.hyper.home.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.Os4InfoPopup
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.UsageHelp
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class FallbackPipelineStep(
  val id: String,
  val title: String,
  val summary: String,
  val accent: Color,
)

@Composable
internal fun IconFallbackSettingsEntry(onOpen: (String) -> Unit) {
  val pressSource = rememberOs4PressSource()
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    BasicComponent(title = stringResource(R.string.icon_fallback_title),
      summary = stringResource(R.string.icon_fallback_entry_summary),
      interactionSource = pressSource,
      startAction = { Icon(actionIcon(PageActionIcon.MATCH), null, tint = MiuixTheme.colorScheme.primary) },
      endActions = { Os4TrailingChevron() },
      modifier = Modifier.glassPress(pressSource).testTag("icon-fallback.entry"),
      onClick = { onOpen("fallback-settings") })
  }
}

@Composable
internal fun IconFallbackOrderCard(model: HomeViewModel) {
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val packs by model.iconPacks.collectAsStateWithLifecycle()
  val theme = runtime.report?.iconTheme
  val source = model.currentFallbackSource(theme, packs, preferences.colorOsComponents)
  val colorOs = source?.colorOsProfile != null || preferences.colorOsForOtherPacks
  val supplemental = source?.colorOsProfile == null && preferences.colorOsForOtherPacks
  val fallback = preferences.systemIconFallback
  val scheme = MiuixTheme.colorScheme
  val steps = buildList {
    add(FallbackPipelineStep(
      id = "primary",
      title = stringResource(R.string.icon_fallback_primary),
      summary = source?.displayName ?: theme?.title ?: stringResource(R.string.icon_fallback_primary_summary),
      accent = scheme.primary,
    ))
    if (supplemental) {
      add(FallbackPipelineStep(
        id = "coloros-artwork",
        title = stringResource(R.string.icon_fallback_coloros_artwork),
        summary = stringResource(R.string.icon_fallback_coloros_artwork_summary),
        accent = Color(0xFFFF963A),
      ))
    }
    add(FallbackPipelineStep(
      id = if (fallback.isPerfect) "perfect" else "system",
      title = if (fallback.isPerfect) systemFallbackName(fallback) else
        stringResource(R.string.icon_fallback_system_source, systemFallbackName(fallback)),
      summary = stringResource(if (fallback.isPerfect) R.string.icon_fallback_completion_summary
        else R.string.icon_fallback_system_summary),
      accent = if (fallback.isPerfect) Color(0xFFE95C88) else Color(0xFF3193FB),
    ))
    if (colorOs) {
      add(FallbackPipelineStep(
        id = "coloros-generic",
        title = stringResource(R.string.icon_fallback_coloros_generic),
        summary = stringResource(R.string.icon_fallback_coloros_generic_summary),
        accent = Color(0xFFFF963A),
      ))
    }
    add(FallbackPipelineStep(
      id = "original",
      title = stringResource(R.string.icon_fallback_original),
      summary = stringResource(R.string.icon_fallback_original_summary),
      accent = scheme.onSurfaceSecondary,
    ))
  }

  Card(Modifier.fillMaxWidth().os4ContentSize().testTag("icon-fallback.order"), insideMargin = PaddingValues(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            stringResource(R.string.icon_fallback_order_title),
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
          )
          Text(
            stringResource(R.string.icon_fallback_order_summary),
            style = MiuixTheme.textStyles.footnote1,
            color = scheme.primary,
          )
        }
        Os4InfoPopup(
          title = stringResource(R.string.icon_fallback_order_title),
          summary = stringResource(R.string.icon_fallback_priority_rules) + "\n\n" +
            stringResource(R.string.icon_fallback_pending_note),
          icon = MiuixIcons.Glass.UsageHelp,
        )
      }
      Column(Modifier.fillMaxWidth().os4ContentSize()) {
        steps.forEachIndexed { index, step ->
          val isLast = index == steps.lastIndex
          key(step.id) {
            Row(
              Modifier.fillMaxWidth().height(IntrinsicSize.Min),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Column(
                Modifier.width(28.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Box(
                  Modifier.size(28.dp).squircleBackground(step.accent.copy(alpha = 0.16f), 9.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    "${index + 1}",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.SemiBold,
                    color = step.accent,
                  )
                }
                if (!isLast) {
                  Box(
                    Modifier.width(2.dp).weight(1f)
                      .padding(vertical = 3.dp)
                      .background(scheme.outline.copy(alpha = 0.35f)),
                  )
                }
              }
              Column(
                Modifier.weight(1f).padding(top = 2.dp, bottom = if (isLast) 2.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
              ) {
                Text(step.title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                Text(
                  step.summary,
                  style = MiuixTheme.textStyles.footnote1,
                  color = scheme.onSurfaceSecondary,
                )
              }
            }
          }
        }
      }
    }
  }
}
