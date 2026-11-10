package os.kei.hyper.home.ui.main

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import java.time.ZoneId
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun compactSyncTime(millis: Long): String {
  val context = LocalContext.current
  val locale = LocalConfiguration.current.locales[0]
  return compactPackEventTime(millis, System.currentTimeMillis(), locale,
    ZoneId.systemDefault(), DateFormat.is24HourFormat(context))
}

/** Keep the last successful time alongside the persistent last-attempt outcome. */
@Composable
internal fun PerfectSyncBadges(state: PerfectPackState, color: Color) {
  val successfulAt = state.lastSuccessfulSyncAtMillis.takeIf { it > 0 } ?: state.catalog?.updatedAt ?: 0
  if (successfulAt > 0) {
    val fullLabel = stringResource(R.string.perfect_icons_catalog_detail, perfectSyncTime(successfulAt))
    MetadataPill(stringResource(R.string.sync_badge_success, compactSyncTime(successfulAt)), color,
      Modifier.testTag("perfect.sync.success").clearAndSetSemantics { contentDescription = fullLabel })
  }
  state.syncIssue?.let { issue ->
    val label = stringResource(when (issue) {
      PerfectSyncIssue.FAILED -> R.string.sync_badge_failed
      PerfectSyncIssue.INTERRUPTED -> R.string.sync_badge_interrupted
      PerfectSyncIssue.CORRUPT -> R.string.sync_badge_corrupt
    })
    val fullLabel = if (state.syncIssueAtMillis > 0) {
      stringResource(R.string.sync_badge_issue_time, label, perfectSyncTime(state.syncIssueAtMillis))
    } else label
    MetadataPill(label, MiuixTheme.colorScheme.error,
      Modifier.testTag("perfect.sync.issue").clearAndSetSemantics { contentDescription = fullLabel })
  }
}
