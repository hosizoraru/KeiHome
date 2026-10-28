package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libxposed.service.HookedTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.readFrameworkSnapshot
import os.kei.hyper.home.xposed.IconRefreshReport
import os.kei.hyper.home.xposed.IconRefreshReportBoot
import os.kei.hyper.home.xposed.IconRefreshReportKind
import os.kei.hyper.home.xposed.IconRefreshReportReadResult
import os.kei.hyper.home.xposed.IconRefreshReportStore
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle

@Composable
internal fun StatusContent(refreshGeneration: Int) {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val service by FrameworkConnection.service.collectAsStateWithLifecycle()
  var snapshot by remember(service) { mutableStateOf<FrameworkSnapshot?>(null) }
  var failed by remember(service) { mutableStateOf(false) }
  var reports by remember {
    mutableStateOf<IconRefreshReportReadResult>(IconRefreshReportReadResult.Unknown)
  }
  LaunchedEffect(service, refreshGeneration) {
    val current = service ?: return@LaunchedEffect
    while (true) {
      try {
        snapshot = withContext(Dispatchers.IO) { current.readFrameworkSnapshot() }
        failed = false
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        snapshot = null
        failed = true
      }
      delay(5_000)
    }
  }
  LaunchedEffect(context, refreshGeneration) {
    while (true) {
      reports = withContext(Dispatchers.IO) { IconRefreshReportStore.read(context) }
      delay(3_000)
    }
  }

  val framework = when {
    service == null -> stringResource(R.string.status_framework_disconnected)
    failed -> stringResource(R.string.status_framework_error)
    snapshot != null -> stringResource(
      R.string.status_framework_connected,
      snapshot!!.name,
      snapshot!!.apiVersion,
    )
    else -> stringResource(R.string.status_unknown)
  }
  val scope = when (snapshot?.systemInScope) {
    true -> stringResource(R.string.status_scope_enabled)
    false -> stringResource(R.string.status_scope_missing)
    null -> stringResource(R.string.status_unknown)
  }
  val target = when (snapshot?.target?.state) {
    HookedTarget.State.UP_TO_DATE -> R.string.status_target_current
    HookedTarget.State.STALE -> R.string.status_target_stale
    HookedTarget.State.RELOADING -> R.string.status_target_reloading
    HookedTarget.State.FAILED -> R.string.status_target_failed
    null -> if (snapshot != null) R.string.status_target_missing else R.string.status_unknown
  }
  val history = reports as? IconRefreshReportReadResult.History
  val dateFormat = remember(configuration) {
    DateFormat.getDateTimeInstance(
      DateFormat.SHORT,
      DateFormat.SHORT,
      configuration.locales[0],
    )
  }
  fun formatTime(report: IconRefreshReport): String =
    dateFormat.format(Date(report.completedWallMillis))
  val hookSummary = when {
    reports is IconRefreshReportReadResult.Unavailable -> stringResource(R.string.status_report_error)
    history?.report?.hook?.kind == IconRefreshReportKind.HOOK_INSTALLED -> stringResource(
      R.string.status_hook_installed, formatTime(history.report.hook),
    )
    history?.report?.hook?.kind == IconRefreshReportKind.HOOK_FAILED -> stringResource(
      R.string.status_hook_failed, formatTime(history.report.hook),
    )
    else -> stringResource(R.string.status_hook_unknown)
  }
  val latest = history?.report?.refreshes?.lastOrNull()
  val recentSummary = when {
    reports is IconRefreshReportReadResult.Unavailable -> stringResource(R.string.status_report_error)
    latest == null -> stringResource(R.string.status_recent_unknown)
    latest.kind == IconRefreshReportKind.REFRESH_FINISHED && latest.callbackCount == 0 ->
      stringResource(R.string.status_refresh_no_callbacks, formatTime(latest))
    latest.kind == IconRefreshReportKind.REFRESH_FINISHED ->
      stringResource(R.string.status_refresh_dispatched, latest.callbackCount ?: 0, formatTime(latest))
    latest.kind == IconRefreshReportKind.REFRESH_PARTIAL ->
      stringResource(R.string.status_refresh_partial, latest.callbackCount ?: 0, formatTime(latest))
    latest.kind == IconRefreshReportKind.REFRESH_CANCELLED ->
      stringResource(R.string.status_refresh_cancelled, formatTime(latest))
    else -> stringResource(R.string.status_refresh_failed, formatTime(latest))
  }
  val historyLabel = when (history?.boot) {
    IconRefreshReportBoot.SAME_BOOT -> stringResource(R.string.status_report_history)
    IconRefreshReportBoot.OTHER_BOOT -> stringResource(R.string.status_report_previous_boot)
    IconRefreshReportBoot.UNKNOWN -> stringResource(R.string.status_report_boot_unknown)
    null -> null
  }

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SmallTitle(text = stringResource(R.string.settings_module_status))
    Card(modifier = Modifier.fillMaxWidth()) {
      Column {
        BasicComponent(title = stringResource(R.string.status_framework), summary = framework)
        HorizontalDivider()
        BasicComponent(title = stringResource(R.string.status_scope), summary = scope)
        HorizontalDivider()
        BasicComponent(
          title = stringResource(R.string.status_target),
          summary = stringResource(target),
        )
      }
    }
    SmallTitle(text = stringResource(R.string.status_runtime_title))
    Card(modifier = Modifier.fillMaxWidth()) {
      Column {
        BasicComponent(
          title = stringResource(R.string.status_hook),
          summary = if (history?.report?.hook != null && historyLabel != null) {
            "$hookSummary\n$historyLabel"
          } else {
            hookSummary
          },
        )
        HorizontalDivider()
        BasicComponent(
          title = stringResource(R.string.status_recent),
          summary = if (latest != null && historyLabel != null) {
            "$recentSummary\n$historyLabel"
          } else {
            recentSummary
          },
        )
        HorizontalDivider()
        BasicComponent(
          title = stringResource(R.string.settings_monet_title),
          summary = stringResource(R.string.settings_monet_value),
        )
      }
    }
  }
}
