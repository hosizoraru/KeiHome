package os.kei.hyper.home.ui.main

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Date
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RefreshFeedback(model: HomeViewModel, page: Int) {
  val result by remember(model, page) { model.refreshResults.map { it[page] }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(initialValue = null)
  val value = result ?: return
  val context = LocalContext.current
  val time = remember(value.completedAt, context) { DateFormat.getTimeFormat(context).format(Date(value.completedAt)) }
  val message = if (value.failed) R.string.refresh_partial else when (page) {
    0 -> R.string.refresh_status_completed
    1 -> R.string.refresh_apps_completed
    3 -> R.string.refresh_icon_packs_completed
    5 -> R.string.desktop_refresh_completed
    else -> R.string.refresh_monet_completed
  }
  Text(stringResource(message, time),
    modifier = Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    style = MiuixTheme.textStyles.footnote2,
    color = if (value.failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceSecondary)
}

private data class IconPackRefreshFeedbackState(
  val loading: Boolean = false,
  val result: PageRefreshResult? = null,
  val error: String? = null,
  val failedSources: Int = 0,
)

private enum class RefreshPhase { LOADING, COMPLETE, FAILED }

@Composable
internal fun IconPackRefreshFeedback(model: HomeViewModel) {
  val feedback by remember(model) {
    combine(model.refreshing, model.refreshResults, model.iconPacks) { refreshing, results, packs ->
      IconPackRefreshFeedbackState(3 in refreshing || packs.loading, results[3], packs.error, packs.failedSources)
    }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = IconPackRefreshFeedbackState())
  val phase = when {
    feedback.loading -> RefreshPhase.LOADING
    feedback.error != null || feedback.failedSources > 0 || feedback.result?.failed == true -> RefreshPhase.FAILED
    else -> RefreshPhase.COMPLETE
  }
  val context = LocalContext.current
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  val use24HourTime = DateFormat.is24HourFormat(context)
  val time = remember(feedback.result?.completedAt, context, locales, use24HourTime) {
    feedback.result?.let { DateFormat.getTimeFormat(context).format(Date(it.completedAt)) }
  }
  AnimatedVisibility(
    visible = feedback.loading || feedback.result != null || phase == RefreshPhase.FAILED,
    enter = fadeIn(tween(160)) + expandVertically(tween(180), expandFrom = Alignment.Top),
    exit = fadeOut(tween(120)) + shrinkVertically(tween(180), shrinkTowards = Alignment.Top),
  ) {
    Card(Modifier.fillMaxWidth().testTag("icon-packs.refresh"),
      insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
      Column(Modifier.animateContentSize(tween(180)).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Crossfade(targetState = phase, modifier = Modifier.size(24.dp),
            animationSpec = tween(160), label = "icon-pack-refresh") { target ->
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
              if (target == RefreshPhase.LOADING) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
              else Icon(if (target == RefreshPhase.FAILED) MiuixIcons.Glass.Info else MiuixIcons.Glass.Ok,
                null, Modifier.size(20.dp), tint = if (target == RefreshPhase.FAILED) MiuixTheme.colorScheme.error
                  else MiuixTheme.colorScheme.primary)
            }
          }
          Text(stringResource(when (phase) {
            RefreshPhase.LOADING -> R.string.icon_pack_refresh_loading
            RefreshPhase.COMPLETE -> R.string.icon_pack_refresh_complete
            RefreshPhase.FAILED -> R.string.icon_pack_refresh_failed
          }), modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1)
          if (time != null) {
            val lastUpdate = stringResource(R.string.refresh_completed, time)
            MetadataPill(time, MiuixTheme.colorScheme.onSurfaceSecondary,
              Modifier.semantics { contentDescription = lastUpdate })
          }
        }
        if (phase == RefreshPhase.FAILED) {
          val details = listOfNotNull(
            feedback.error?.let { stringResource(R.string.icon_pack_read_failed, it) },
            if (feedback.failedSources > 0) stringResource(R.string.icon_pack_partial, feedback.failedSources) else null,
          ).joinToString("\n").ifEmpty { stringResource(R.string.icon_pack_refresh_retry) }
          Text(details, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
    }
  }
}
