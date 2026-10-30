package os.kei.hyper.home.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.libxposed.service.HookedTarget
import kotlinx.coroutines.delay
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.MonetReadState
import os.kei.hyper.home.status.FrameworkSnapshot
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
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
  // The containing page's SaveableStateProvider retains these across tab switches.
  var runtimeExpanded by rememberSaveable { mutableStateOf(false) }
  var monetExpanded by rememberSaveable { mutableStateOf(false) }
  val framework = frameworkCardState(connected, readFailed, snapshot)
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FrameworkHero(framework, onRefresh)
    RuntimeStatusCard(framework, runtimeExpanded, { runtimeExpanded = !runtimeExpanded })
    DashboardMonetCard(
      snapshot = monetSnapshot,
      readFailed = monetReadFailed,
      expanded = monetExpanded,
      onToggle = { monetExpanded = !monetExpanded },
      onOpenMonet = onOpenMonet,
    )
    AppCapabilitiesCard(installedApps, nightApps, monetApps, onOpenApps)
  }
}

@Immutable
internal data class FrameworkCardState(
  val connected: Boolean,
  val readFailed: Boolean,
  val reported: Boolean,
  val name: String?,
  val apiVersion: Int?,
  val frameworkVersion: String?,
  val frameworkVersionCode: Long?,
  val systemInScope: Boolean?,
  val systemState: HookedTarget.State?,
  val systemVersion: Long?,
  val launcherState: HookedTarget.State?,
  val launcherVersion: Long?,
)

internal fun frameworkCardState(
  connected: Boolean,
  readFailed: Boolean,
  snapshot: FrameworkSnapshot?,
): FrameworkCardState {
  // Connection/read failures make previous identity, scope and target reports unavailable.
  val current = snapshot.takeIf { connected && !readFailed }
  return FrameworkCardState(
    connected = connected,
    readFailed = readFailed,
    reported = current != null,
    name = current?.name?.trim()?.takeIf { it.isNotEmpty() },
    apiVersion = current?.apiVersion,
    frameworkVersion = current?.frameworkVersion?.trim()?.takeIf { it.isNotEmpty() },
    frameworkVersionCode = current?.frameworkVersionCode?.takeIf { it >= 0 },
    systemInScope = current?.systemInScope,
    systemState = loadedState(current?.target?.state, current?.target?.loadedVersionCode),
    systemVersion = current?.target?.loadedVersionCode,
    launcherState = loadedState(current?.launcherTarget?.state, current?.launcherTarget?.loadedVersionCode),
    launcherVersion = current?.launcherTarget?.loadedVersionCode,
  )
}

internal fun loadedState(
  state: HookedTarget.State?,
  version: Long?,
  installedVersion: Long = BuildConfig.VERSION_CODE.toLong(),
): HookedTarget.State? = when {
  state != HookedTarget.State.UP_TO_DATE -> state
  version == null -> null
  version != installedVersion -> HookedTarget.State.STALE
  else -> state
}

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
    state.systemInScope == true && state.systemState == HookedTarget.State.UP_TO_DATE &&
      state.launcherState == HookedTarget.State.UP_TO_DATE -> R.string.polish_status_loaded
    state.systemState == HookedTarget.State.UP_TO_DATE ||
      state.launcherState == HookedTarget.State.UP_TO_DATE -> R.string.dashboard_partly_loaded
    else -> R.string.polish_status_waiting
  }
  val summary = when {
    !state.connected -> stringResource(R.string.status_framework_disconnected)
    state.readFailed -> stringResource(R.string.status_framework_error)
    !state.reported -> stringResource(R.string.dashboard_reading_status)
    else -> stringResource(
      R.string.dashboard_framework_identity,
      state.name ?: stringResource(R.string.status_unknown),
      frameworkVersionLabel(state.frameworkVersion, state.frameworkVersionCode),
    )
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
    footer = if (state.reported && state.apiVersion != null) {
      stringResource(R.string.dashboard_connection_api, state.apiVersion)
    } else if (state.connected && !state.readFailed) {
      stringResource(R.string.status_framework_pending)
    } else null,
  )
}

@Composable
private fun frameworkVersionLabel(version: String?, code: Long?): String = when {
  version != null && code != null -> stringResource(R.string.dashboard_version_pair, version, code)
  version != null -> version
  code != null -> stringResource(R.string.dashboard_version_code, code)
  else -> stringResource(R.string.dashboard_version_unknown)
}

@Composable
private fun RuntimeStatusCard(state: FrameworkCardState, expanded: Boolean, onToggle: () -> Unit) {
  val summary = if (state.reported) {
    stringResource(
      R.string.dashboard_targets,
      targetLabel(state.systemState, reported = true),
      targetLabel(state.launcherState, reported = true),
    )
  } else stringResource(R.string.dashboard_current_unknown)
  DashboardExpandableCard(
    title = stringResource(R.string.dashboard_runtime),
    summary = summary,
    expanded = expanded,
    onToggle = onToggle,
    footer = { DashboardFootnote(stringResource(R.string.dashboard_refresh_unreported)) },
  ) {
    DashboardDetail(
      title = stringResource(R.string.dashboard_system_scope),
      value = stringResource(when (state.systemInScope) {
        true -> R.string.status_scope_enabled
        false -> R.string.status_scope_missing
        null -> R.string.status_unknown
      }),
    )
    TargetDetail("system_server", state.systemState, state.systemVersion, state.reported)
    TargetDetail("com.miui.home", state.launcherState, state.launcherVersion, state.reported)
    DashboardDetail(
      title = stringResource(R.string.dashboard_framework_version),
      value = frameworkVersionLabel(state.frameworkVersion, state.frameworkVersionCode),
    )
    DashboardDetail(
      title = stringResource(R.string.polish_installed_version),
      value = stringResource(R.string.polish_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
    )
    DashboardFootnote(
      text = stringResource(R.string.dashboard_load_report_note),
      modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    )
  }
}

@Composable
private fun TargetDetail(name: String, state: HookedTarget.State?, version: Long?, reported: Boolean) {
  val status = targetLabel(state, reported)
  DashboardDetail(
    title = name,
    value = version?.takeIf { it > 0 }?.let {
      stringResource(R.string.polish_target_version, status, it)
    } ?: status,
  )
}

@Composable
private fun targetLabel(state: HookedTarget.State?, reported: Boolean): String = stringResource(when (state) {
  HookedTarget.State.UP_TO_DATE -> R.string.status_target_current
  HookedTarget.State.STALE -> R.string.status_target_stale
  HookedTarget.State.RELOADING -> R.string.status_target_reloading
  HookedTarget.State.FAILED -> R.string.status_target_failed
  null -> if (reported) R.string.status_target_unreported else R.string.status_unknown
})

@Composable
private fun DashboardMonetCard(
  snapshot: MonetPaletteSnapshot?,
  readFailed: Boolean,
  expanded: Boolean,
  onToggle: () -> Unit,
  onOpenMonet: () -> Unit,
) {
  val current = snapshot.takeUnless { readFailed }
  val palette = current?.activePalette
  val paletteLabel = stringResource(when {
    palette == null -> R.string.status_unknown
    palette.state == MonetReadState.UNAVAILABLE -> R.string.monet_state_unavailable
    palette.state == MonetReadState.NOT_PROVIDED || palette.generatedColorCount == 0 ->
      R.string.monet_state_not_provided
    else -> R.string.dashboard_palette_available
  })
  val iconsLabel = stringResource(when (current?.monochrome?.enabled) {
    true -> R.string.dashboard_on
    false -> R.string.dashboard_off
    null -> R.string.status_unknown
  })
  DashboardExpandableCard(
    title = stringResource(R.string.settings_monet_title),
    summary = when {
      readFailed -> stringResource(R.string.status_framework_error)
      current == null -> stringResource(R.string.monet_loading)
      else -> stringResource(R.string.dashboard_monet_summary, iconsLabel, paletteLabel)
    },
    expanded = expanded,
    onToggle = onToggle,
    onOpen = onOpenMonet,
    openLabel = stringResource(R.string.monet_open_details),
    footer = {
      if (readFailed) DashboardFootnote(stringResource(R.string.dashboard_current_unknown))
      else if (current != null) DashboardPaletteAge(current.lastAppliedRevision)
    },
  ) {
    DashboardDetail(
      title = stringResource(R.string.monet_seed_source),
      value = stringResource(when (current?.source) {
        "HOME_WALLPAPER", "home_wallpaper" -> R.string.monet_source_home
        "LOCK_WALLPAPER", "lock_wallpaper" -> R.string.monet_source_lock
        "BASIC_COLOR", "preset" -> R.string.monet_source_basic
        else -> R.string.status_unknown
      }),
    )
    DashboardDetail(
      title = stringResource(R.string.dashboard_style),
      value = dashboardMonetStyle(current?.style),
    )
    DashboardDetail(
      title = stringResource(R.string.monet_palette),
      value = if (palette?.state == MonetReadState.AVAILABLE && palette.generatedColorCount > 0) {
        stringResource(R.string.monet_palette_value, palette.families.size, palette.generatedColorCount)
      } else paletteLabel,
    )
    DashboardFootnote(
      text = stringResource(R.string.dashboard_monet_report_note),
      modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
    )
    BasicComponent(
      title = stringResource(R.string.monet_open_details),
      insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      onClick = onOpenMonet,
      onClickLabel = stringResource(R.string.monet_open_details),
      role = Role.Button,
      endActions = { DashboardArrow() },
    )
  }
}

@Composable
private fun dashboardMonetStyle(style: String?): String = when (style) {
  "TONAL_SPOT" -> stringResource(R.string.monet_style_tonal)
  "VIBRANT" -> stringResource(R.string.monet_style_vibrant)
  "NEUTRAL", "SPRITZ" -> stringResource(R.string.monet_style_neutral)
  "MONOCHROME", "MONOCHROMATIC" -> stringResource(R.string.monet_style_mono)
  "EXPRESSIVE" -> stringResource(R.string.monet_style_expressive)
  "RAINBOW" -> stringResource(R.string.monet_style_rainbow)
  "FRUIT_SALAD" -> stringResource(R.string.monet_style_fruit_salad)
  else -> style?.takeIf { it.isNotBlank() } ?: stringResource(R.string.status_unknown)
}

/** A future or invalid timestamp cannot establish how recently a palette was applied. */
internal fun dashboardPaletteAgeMinutes(timestamp: Long, now: Long): Long? =
  if (timestamp <= 0 || timestamp > now) null else (now - timestamp) / 60_000

@Composable
private fun DashboardPaletteAge(timestamp: Long?) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var minutes by remember(timestamp) {
    mutableStateOf(timestamp?.let { dashboardPaletteAgeMinutes(it, System.currentTimeMillis()) })
  }
  // Only this leaf observes time; resume and minute boundaries both refresh its label.
  LaunchedEffect(timestamp, lifecycle) {
    if (timestamp == null || timestamp <= 0) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (true) {
        val now = System.currentTimeMillis()
        minutes = dashboardPaletteAgeMinutes(timestamp, now)
        val next = if (timestamp > now) (timestamp - now).coerceAtMost(60_000)
          else 60_000 - (now - timestamp) % 60_000
        delay(next.coerceAtLeast(1))
      }
    }
  }
  val age = minutes
  val relative = when {
    age == null -> stringResource(R.string.status_unknown)
    age == 0L -> stringResource(R.string.polish_time_now)
    age < 60 -> pluralStringResource(R.plurals.polish_time_minutes, age.toInt(), age.toInt())
    age < 1_440 -> (age / 60).toInt().let {
      pluralStringResource(R.plurals.polish_time_hours, it, it)
    }
    else -> (age / 1_440).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().let {
      pluralStringResource(R.plurals.polish_time_days, it, it)
    }
  }
  DashboardFootnote(
    if (timestamp == null) stringResource(R.string.dashboard_no_palette_record)
    else stringResource(R.string.dashboard_last_palette, relative),
  )
}

@Composable
private fun AppCapabilitiesCard(
  installedApps: Int?,
  nightApps: Int?,
  monetApps: Int?,
  onOpenApps: () -> Unit,
) {
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    BasicComponent(
      title = stringResource(
        if (installedApps == null) R.string.dashboard_apps_unscanned else R.string.dashboard_apps,
      ),
      insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      onClick = onOpenApps,
      onClickLabel = stringResource(R.string.polish_apps_open),
      role = Role.Button,
      endActions = { DashboardArrow() },
      bottomAction = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          CapabilityCount(installedApps, stringResource(R.string.polish_apps_total), Modifier.weight(1f))
          CapabilityCount(nightApps, stringResource(R.string.polish_apps_night), Modifier.weight(1f))
          CapabilityCount(monetApps, stringResource(R.string.polish_apps_monet), Modifier.weight(1f))
        }
      },
    )
  }
}

@Composable
private fun CapabilityCount(count: Int?, label: String, modifier: Modifier) {
  Column(modifier.semantics(mergeDescendants = true) {}) {
    Text(
      text = count?.takeIf { it >= 0 }?.let { stringResource(R.string.polish_apps_count, it) }
        ?: stringResource(R.string.polish_value_pending),
      style = MiuixTheme.textStyles.title2,
      color = if (count != null && count >= 0) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.onSurfaceSecondary,
    )
    DashboardFootnote(label)
  }
}

@Composable
private fun DashboardExpandableCard(
  title: String,
  summary: String,
  expanded: Boolean,
  onToggle: () -> Unit,
  onOpen: (() -> Unit)? = null,
  openLabel: String? = null,
  footer: @Composable () -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  val actionLabel = stringResource(
    if (expanded) R.string.dashboard_collapse else R.string.dashboard_expand, title,
  )
  val expansionState = stringResource(
    if (expanded) R.string.dashboard_expanded else R.string.dashboard_collapsed,
  )
  val rotation by animateFloatAsState(if (expanded) 270f else 90f, label = "statusExpansion")
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    BasicComponent(
      title = title,
      summary = summary,
      modifier = if (onOpen == null) Modifier.semantics { stateDescription = expansionState }
        else Modifier,
      insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      onClick = onOpen ?: onToggle,
      onClickLabel = if (onOpen == null) actionLabel else openLabel,
      role = Role.Button,
      endActions = {
        if (onOpen == null) {
          DashboardExpandIcon(rotation)
        } else {
          // Keep tapping the Monet summary as its existing page shortcut; the chevron
          // independently discloses details with a full touch target and its own state.
          IconButton(
            onClick = onToggle,
            modifier = Modifier.size(48.dp).semantics { stateDescription = expansionState },
          ) {
            DashboardExpandIcon(rotation, actionLabel)
          }
        }
      },
      bottomAction = footer,
    )
    AnimatedVisibility(
      visible = expanded,
      enter = expandVertically() + fadeIn(),
      exit = shrinkVertically() + fadeOut(),
    ) {
      Column(content = content)
    }
  }
}

@Composable
private fun DashboardExpandIcon(rotation: Float, description: String? = null) {
  Icon(
    MiuixIcons.Basic.ArrowRight,
    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
    contentDescription = description,
    tint = MiuixTheme.colorScheme.onSurfaceSecondary,
  )
}

@Composable
private fun DashboardDetail(title: String, value: String) {
  BasicComponent(
    title = title,
    summary = value,
    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
  )
}

@Composable
private fun DashboardFootnote(text: String, modifier: Modifier = Modifier) {
  Text(text, modifier = modifier, style = MiuixTheme.textStyles.footnote1,
    color = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
private fun DashboardArrow() {
  Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null,
    tint = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
internal fun StatusOverviewCard(
  title: String,
  summary: String,
  details: List<StatusDetail>,
  tone: StatusTone,
  onRefresh: () -> Unit,
  footer: String? = null,
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
      verticalArrangement = Arrangement.spacedBy(8.dp),
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
      if (footer != null) {
        Text(footer, style = MiuixTheme.textStyles.footnote1, color = contentColor)
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
