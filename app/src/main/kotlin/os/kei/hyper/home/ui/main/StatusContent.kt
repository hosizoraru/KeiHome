package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.HookedTarget
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.status.FrameworkSnapshot
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
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
  installedApps: Int? = null,
  nightApps: Int? = null,
  monetApps: Int? = null,
  onOpenApps: () -> Unit = {},
) {
  val liveSnapshot = snapshot.takeIf { connected && !readFailed }
  val framework = FrameworkCardState(
    connected, readFailed, liveSnapshot?.name, liveSnapshot?.apiVersion,
    liveSnapshot?.systemInScope,
    loadedState(liveSnapshot?.target?.state, liveSnapshot?.target?.loadedVersionCode),
    liveSnapshot?.target?.loadedVersionCode,
    loadedState(liveSnapshot?.launcherTarget?.state, liveSnapshot?.launcherTarget?.loadedVersionCode),
    liveSnapshot?.launcherTarget?.loadedVersionCode,
  )
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SmallTitle(
      text = stringResource(R.string.polish_status_module),
      modifier = Modifier.semantics { heading() },
    )
    FrameworkHero(framework, onRefresh)
    SmallTitle(
      text = stringResource(R.string.polish_status_icons),
      modifier = Modifier.semantics { heading() },
    )
    NightRefreshCard(framework.systemInScope, framework.systemState)
    MonetSummaryCard(
      snapshot = monetSnapshot,
      readFailed = monetReadFailed,
      onClick = onOpenMonet,
    )
    SmallTitle(
      text = stringResource(R.string.polish_status_apps),
      modifier = Modifier.semantics { heading() },
    )
    AppCapabilitiesCard(installedApps, nightApps, monetApps, onOpenApps)
    FrameworkDetails(framework)
  }
}

@Immutable
private data class FrameworkCardState(
  val connected: Boolean,
  val readFailed: Boolean,
  val name: String?,
  val apiVersion: Int?,
  val systemInScope: Boolean?,
  val systemState: HookedTarget.State?,
  val systemVersion: Long?,
  val launcherState: HookedTarget.State?,
  val launcherVersion: Long?,
)

private fun loadedState(state: HookedTarget.State?, version: Long?): HookedTarget.State? =
  if (state == HookedTarget.State.UP_TO_DATE && version != null &&
    version != BuildConfig.VERSION_CODE.toLong()
  ) HookedTarget.State.STALE else state

@Composable
private fun FrameworkHero(state: FrameworkCardState, onRefresh: () -> Unit) {
  val title = when {
    !state.connected || state.readFailed -> R.string.status_overview_error_title
    state.systemInScope == false -> R.string.polish_status_scope_needed
    state.systemState == HookedTarget.State.FAILED -> R.string.status_target_failed
    state.launcherState == HookedTarget.State.FAILED -> R.string.polish_status_launcher_failed
    state.systemState == HookedTarget.State.RELOADING ||
      state.launcherState == HookedTarget.State.RELOADING -> R.string.status_target_reloading
    state.systemState == HookedTarget.State.STALE ||
      state.launcherState == HookedTarget.State.STALE -> R.string.polish_status_update_pending
    state.systemState == HookedTarget.State.UP_TO_DATE -> R.string.polish_status_loaded
    else -> R.string.polish_status_waiting
  }
  val summary = when {
    !state.connected -> stringResource(R.string.status_framework_disconnected)
    state.readFailed -> stringResource(R.string.status_framework_error)
    state.name != null && state.apiVersion != null -> stringResource(
      R.string.polish_status_framework_api, state.name, state.apiVersion,
    )
    else -> stringResource(R.string.status_framework_pending)
  }
  StatusOverviewCard(
    title = stringResource(title),
    summary = summary,
    details = emptyList(),
    tone = statusTone(
      state.connected, state.readFailed, state.systemInScope,
      state.systemState, state.launcherState,
    ),
    onRefresh = onRefresh,
  )
}

@Composable
private fun NightRefreshCard(systemInScope: Boolean?, state: HookedTarget.State?) {
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    BasicComponent(
      title = stringResource(R.string.polish_night_title),
      summary = if (systemInScope == false) {
        stringResource(R.string.polish_status_scope_needed)
      } else {
        targetLabel(state)
      },
      bottomAction = {
        Text(
          text = stringResource(R.string.polish_night_recent),
          style = MiuixTheme.textStyles.footnote1,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
      },
    )
  }
}

@Composable
private fun AppCapabilitiesCard(
  installedApps: Int?,
  nightApps: Int?,
  monetApps: Int?,
  onOpenApps: () -> Unit,
) {
  val openLabel = stringResource(R.string.polish_apps_open)
  Card(
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(16.dp),
    onClick = onOpenApps,
    showIndication = true,
  ) {
    Row(
      modifier = Modifier.semantics { onClick(label = openLabel, action = null) },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.polish_apps_detected), style = MiuixTheme.textStyles.body1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          CapabilityCount(installedApps, stringResource(R.string.polish_apps_total), Modifier.weight(1f))
          CapabilityCount(nightApps, stringResource(R.string.polish_apps_night), Modifier.weight(1f))
          CapabilityCount(monetApps, stringResource(R.string.polish_apps_monet), Modifier.weight(1f))
        }
      }
      Icon(
        MiuixIcons.Basic.ArrowRight,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onSurfaceSecondary,
      )
    }
  }
}

@Composable
private fun CapabilityCount(count: Int?, label: String, modifier: Modifier) {
  Column(modifier) {
    Text(
      text = count?.let { stringResource(R.string.polish_apps_count, it) }
        ?: stringResource(R.string.polish_value_pending),
      style = MiuixTheme.textStyles.title2,
      color = MiuixTheme.colorScheme.primary,
    )
    Text(label, style = MiuixTheme.textStyles.footnote1,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
}

@Composable
private fun FrameworkDetails(state: FrameworkCardState) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val expansionLabel = stringResource(
    if (expanded) R.string.polish_details_collapse else R.string.polish_details_expand,
  )
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    BasicComponent(
      title = stringResource(R.string.polish_status_details),
      modifier = Modifier.semantics { stateDescription = expansionLabel },
      onClick = { expanded = !expanded },
      onClickLabel = expansionLabel,
      endActions = {
        Icon(
          MiuixIcons.ExpandMore,
          modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f },
          contentDescription = null,
          tint = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
      },
    )
    if (expanded) {
      BasicComponent(
        title = stringResource(R.string.status_scope),
        summary = when (state.systemInScope) {
          true -> stringResource(R.string.polish_scope_enabled)
          false -> stringResource(R.string.polish_scope_disabled)
          null -> stringResource(R.string.status_unknown)
        },
      )
      TargetDetail("system_server", state.systemState, state.systemVersion)
      TargetDetail("com.miui.home", state.launcherState, state.launcherVersion)
      BasicComponent(
        title = stringResource(R.string.polish_installed_version),
        summary = stringResource(
          R.string.polish_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
        ),
      )
    }
  }
}

@Composable
private fun TargetDetail(name: String, state: HookedTarget.State?, loadedVersion: Long?) {
  val status = targetLabel(state)
  BasicComponent(
    title = name,
    summary = loadedVersion?.let {
      stringResource(R.string.polish_target_version, status, it)
    } ?: status,
  )
}

@Composable
private fun targetLabel(state: HookedTarget.State?): String = stringResource(when (state) {
  HookedTarget.State.UP_TO_DATE -> R.string.polish_status_loaded
  HookedTarget.State.STALE -> R.string.status_target_stale
  HookedTarget.State.RELOADING -> R.string.status_target_reloading
  HookedTarget.State.FAILED -> R.string.status_target_failed
  null -> R.string.status_target_unreported
})

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
    StatusTone.WARNING -> MiuixTheme.colorScheme.surfaceVariant
    StatusTone.ERROR -> MiuixTheme.colorScheme.errorContainer
  }
  val contentColor = when (tone) {
    StatusTone.READY -> MiuixTheme.colorScheme.onSecondaryContainer
    StatusTone.WARNING -> MiuixTheme.colorScheme.onSurface
    StatusTone.ERROR -> MiuixTheme.colorScheme.onErrorContainer
  }
  val accentColor = when (tone) {
    StatusTone.READY -> MiuixTheme.colorScheme.primary
    StatusTone.WARNING -> MiuixTheme.colorScheme.onSurfaceSecondary
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
      if (details.isNotEmpty()) {
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
}
