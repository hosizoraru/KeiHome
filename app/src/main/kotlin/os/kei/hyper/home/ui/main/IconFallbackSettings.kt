package os.kei.hyper.home.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
  val steps = buildList {
    add(stringResource(R.string.icon_fallback_primary) to
      (source?.displayName ?: theme?.title ?: stringResource(R.string.icon_fallback_primary_summary)))
    if (supplemental) add(stringResource(R.string.icon_fallback_coloros_artwork) to
      stringResource(R.string.icon_fallback_coloros_artwork_summary))
    add((if (fallback.isPerfect) systemFallbackName(fallback) else
      stringResource(R.string.icon_fallback_system_source, systemFallbackName(fallback))) to
      stringResource(if (fallback.isPerfect) R.string.icon_fallback_completion_summary
        else R.string.icon_fallback_system_summary))
    if (colorOs) add(stringResource(R.string.icon_fallback_coloros_generic) to
      stringResource(R.string.icon_fallback_coloros_generic_summary))
    add(stringResource(R.string.icon_fallback_original) to
      stringResource(R.string.icon_fallback_original_summary))
  }
  Card(Modifier.fillMaxWidth().os4ContentSize().testTag("icon-fallback.order"), insideMargin = PaddingValues(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Text(stringResource(R.string.icon_fallback_order_title), style = MiuixTheme.textStyles.title4)
      Text(stringResource(R.string.icon_fallback_priority_rules),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      Text(stringResource(R.string.icon_fallback_order_summary),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
      steps.forEachIndexed { index, (title, summary) ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
          Box(Modifier.size(26.dp).background(MiuixTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center) {
            Text("${index + 1}", style = MiuixTheme.textStyles.footnote1,
              color = MiuixTheme.colorScheme.primary)
          }
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MiuixTheme.textStyles.body1)
            Text(summary, style = MiuixTheme.textStyles.footnote1,
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
          }
        }
      }
      Text(stringResource(R.string.icon_fallback_pending_note),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}
