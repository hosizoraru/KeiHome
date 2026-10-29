package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.HookedTarget
import os.kei.hyper.home.R
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.status.FrameworkSnapshot
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

internal enum class StatusTone {
  READY,
  WARNING,
  ERROR,
}

internal data class StatusDetail(
  val label: String,
  val value: String,
)

internal fun statusTone(
  connected: Boolean,
  readFailed: Boolean,
  systemInScope: Boolean?,
  systemTarget: HookedTarget.State?,
  launcherTarget: HookedTarget.State?,
): StatusTone = when {
  !connected || readFailed ||
    systemInScope == false || systemTarget == HookedTarget.State.FAILED -> StatusTone.ERROR
  systemInScope == true &&
    systemTarget == HookedTarget.State.UP_TO_DATE &&
    launcherTarget == HookedTarget.State.UP_TO_DATE -> StatusTone.READY
  else -> StatusTone.WARNING
}

@Composable
internal fun StatusBody(
  connected: Boolean,
  snapshot: FrameworkSnapshot?,
  readFailed: Boolean,
  monetSnapshot: MonetPaletteSnapshot?,
  monetReadFailed: Boolean,
  onRefresh: () -> Unit,
  onOpenMonet: () -> Unit,
) {
  val liveSnapshot = snapshot.takeIf { connected && !readFailed }
  val framework = when {
    !connected -> stringResource(R.string.status_framework_disconnected)
    readFailed -> stringResource(R.string.status_framework_error)
    liveSnapshot != null -> stringResource(
      R.string.status_framework_connected,
      liveSnapshot.name,
    )
    else -> stringResource(R.string.status_framework_pending)
  }
  val scope = when (liveSnapshot?.systemInScope) {
    true -> stringResource(R.string.status_scope_enabled)
    false -> stringResource(R.string.status_scope_missing)
    null -> stringResource(R.string.status_unknown)
  }
  val targetResource = when (liveSnapshot?.target?.state) {
    HookedTarget.State.UP_TO_DATE -> R.string.status_target_current
    HookedTarget.State.STALE -> R.string.status_target_stale
    HookedTarget.State.RELOADING -> R.string.status_target_reloading
    HookedTarget.State.FAILED -> R.string.status_target_failed
    null -> if (liveSnapshot != null) R.string.status_target_missing else R.string.status_unknown
  }
  val target = stringResource(targetResource)
  val launcherTargetResource = when (liveSnapshot?.launcherTarget?.state) {
    HookedTarget.State.UP_TO_DATE -> R.string.status_target_current
    HookedTarget.State.STALE -> R.string.status_target_stale
    HookedTarget.State.RELOADING -> R.string.status_target_reloading
    HookedTarget.State.FAILED -> R.string.status_target_failed
    null -> if (liveSnapshot != null) {
      R.string.status_target_unreported
    } else {
      R.string.status_unknown
    }
  }
  val launcherTarget = stringResource(launcherTargetResource)
  val tone = statusTone(
    connected = connected,
    readFailed = readFailed,
    systemInScope = liveSnapshot?.systemInScope,
    systemTarget = liveSnapshot?.target?.state,
    launcherTarget = liveSnapshot?.launcherTarget?.state,
  )
  val titleResource = when (tone) {
    StatusTone.READY -> R.string.status_overview_ready_title
    StatusTone.WARNING -> R.string.status_overview_warning_title
    StatusTone.ERROR -> R.string.status_overview_error_title
  }
  val summaryResource = when (tone) {
    StatusTone.READY -> R.string.status_overview_ready_summary
    StatusTone.WARNING -> R.string.status_overview_warning_summary
    StatusTone.ERROR -> R.string.status_overview_error_summary
  }
  val details = listOf(
    StatusDetail(stringResource(R.string.status_framework), framework),
    StatusDetail(stringResource(R.string.status_scope), scope),
    StatusDetail(stringResource(R.string.status_target_system), target),
    StatusDetail(stringResource(R.string.status_target_launcher), launcherTarget),
    StatusDetail(
      stringResource(R.string.status_recent),
      stringResource(R.string.status_recent_unreported),
    ),
  )

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SmallTitle(
      text = stringResource(R.string.settings_module_status),
      modifier = Modifier.semantics { heading() },
    )
    StatusOverviewCard(
      title = stringResource(titleResource),
      summary = stringResource(summaryResource),
      details = details,
      tone = tone,
      onRefresh = onRefresh,
    )
    SmallTitle(
      text = stringResource(R.string.monet_section_title),
      modifier = Modifier.semantics { heading() },
    )
    MonetSummaryCard(
      snapshot = monetSnapshot,
      readFailed = monetReadFailed,
      onClick = onOpenMonet,
    )
  }
}

@Composable
internal fun StatusOverviewCard(
  title: String,
  summary: String,
  details: List<StatusDetail>,
  tone: StatusTone,
  onRefresh: () -> Unit,
) {
  val refreshLabel = stringResource(R.string.status_refresh)
  val backgroundColor = when (tone) {
    StatusTone.READY -> MiuixTheme.colorScheme.secondaryContainer
    StatusTone.WARNING -> MiuixTheme.colorScheme.tertiaryContainer
    StatusTone.ERROR -> MiuixTheme.colorScheme.errorContainer
  }
  val contentColor = when (tone) {
    StatusTone.READY -> MiuixTheme.colorScheme.onSecondaryContainer
    StatusTone.WARNING -> MiuixTheme.colorScheme.onTertiaryContainer
    StatusTone.ERROR -> MiuixTheme.colorScheme.onErrorContainer
  }
  val accentColor = when (tone) {
    StatusTone.READY -> MiuixTheme.colorScheme.primary
    StatusTone.WARNING -> MiuixTheme.colorScheme.onTertiaryContainer
    StatusTone.ERROR -> MiuixTheme.colorScheme.error
  }
  val icon = when (tone) {
    StatusTone.READY -> MiuixIcons.Ok
    StatusTone.WARNING -> MiuixIcons.Info
    StatusTone.ERROR -> MiuixIcons.Report
  }

  Card(
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(0.dp),
    colors = CardDefaults.defaultColors(color = backgroundColor),
    onClick = onRefresh,
    showIndication = true,
    pressFeedbackType = PressFeedbackType.Tilt,
  ) {
    Column(
      modifier = Modifier
        .semantics { this.onClick(label = refreshLabel, action = null) }
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = title,
            style = MiuixTheme.textStyles.title2,
            color = contentColor,
          )
          Text(
            text = summary,
            style = MiuixTheme.textStyles.body2,
            color = contentColor.copy(alpha = 0.84f),
          )
        }
        Icon(
          imageVector = icon,
          modifier = Modifier.size(28.dp),
          tint = accentColor,
          contentDescription = null,
        )
      }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        details.forEach { detail ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = detail.label,
              modifier = Modifier.weight(1f),
              style = MiuixTheme.textStyles.body2,
              color = contentColor.copy(alpha = 0.7f),
            )
            Text(
              text = detail.value,
              modifier = Modifier.weight(1.2f),
              style = MiuixTheme.textStyles.body2,
              color = contentColor,
            )
          }
        }
      }
    }
  }
}
