package os.kei.hyper.home.ui.main

import android.text.format.DateFormat
import android.text.format.DateUtils
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.ZoneId
import kotlinx.coroutines.delay
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun compactSyncTime(millis: Long, nowMillis: Long): String {
  val context = LocalContext.current
  val locale = LocalConfiguration.current.locales[0]
  return compactPackEventTime(millis, nowMillis, locale,
    ZoneId.systemDefault(), DateFormat.is24HourFormat(context))
}

/** Keep the last successful time alongside the persistent last-attempt outcome. */
@Composable
internal fun PerfectSyncBadges(state: PerfectPackState, color: Color) {
  val locale = LocalConfiguration.current.locales[0]
  val nowMillis = perfectBadgeTime(state.remote != null || state.lastSuccessfulSyncAtMillis > 0 ||
    state.catalog != null || state.syncIssue != null)
  state.remote?.let { remote ->
    val label = stringResource(when {
      state.catalog == null -> R.string.perfect_git_remote_available
      state.updateAvailable -> R.string.perfect_git_update_available
      else -> R.string.perfect_git_current
    })
    MetadataPill(label, if (state.updateAvailable) MiuixTheme.colorScheme.primary else color,
      Modifier.testTag("perfect.sync.remote-status"))
    MetadataPill(stringResource(R.string.perfect_git_remote_revision, remote.revision.take(7)), color,
      Modifier.testTag("perfect.sync.remote-revision"))
    if (remote.committedAt > 0) {
      val elapsed = nowMillis - remote.committedAt
      val relative = if (elapsed < DateUtils.WEEK_IN_MILLIS) {
        DateUtils.getRelativeTimeSpanString(remote.committedAt, nowMillis,
          DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
      } else {
        val (interval, unit) = when {
          elapsed >= 365 * DateUtils.DAY_IN_MILLIS -> 365 * DateUtils.DAY_IN_MILLIS to RelativeDateTimeUnit.YEAR
          elapsed >= 30 * DateUtils.DAY_IN_MILLIS -> 30 * DateUtils.DAY_IN_MILLIS to RelativeDateTimeUnit.MONTH
          else -> DateUtils.WEEK_IN_MILLIS to RelativeDateTimeUnit.WEEK
        }
        remember(locale) { RelativeDateTimeFormatter.getInstance(locale) }
          .formatNumeric(-(elapsed / interval).toDouble(), unit)
      }
      val label = stringResource(R.string.perfect_git_commit_time,
        compactSyncTime(remote.committedAt, nowMillis), relative)
      val fullLabel = stringResource(R.string.perfect_git_commit_time,
        perfectSyncTime(remote.committedAt), relative)
      MetadataPill(label, color, Modifier.testTag("perfect.sync.remote-time")
        .clearAndSetSemantics { contentDescription = fullLabel })
    }
  }
  val successfulAt = state.lastSuccessfulSyncAtMillis.takeIf { it > 0 } ?: state.catalog?.updatedAt ?: 0
  if (successfulAt > 0) {
    val fullLabel = stringResource(R.string.perfect_icons_catalog_detail, perfectSyncTime(successfulAt))
    MetadataPill(stringResource(R.string.sync_badge_success, compactSyncTime(successfulAt, nowMillis)), color,
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

@Composable
private fun perfectBadgeTime(active: Boolean): Long {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
  // Keep clock reads inside the badges and stop ticking outside the foreground.
  LaunchedEffect(lifecycle, active) {
    if (!active) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        val now = System.currentTimeMillis()
        nowMillis = now
        delay(DateUtils.MINUTE_IN_MILLIS - now % DateUtils.MINUTE_IN_MILLIS)
      }
    }
  }
  return nowMillis
}
