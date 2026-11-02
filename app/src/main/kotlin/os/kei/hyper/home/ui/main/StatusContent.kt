package os.kei.hyper.home.ui.main

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import os.kei.hyper.home.status.IconRefreshReceipt
import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
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

/** Framework load severity only; the dashboard also requires a current native reply. */
internal fun statusTone(
  connected: Boolean,
  readFailed: Boolean,
  systemInScope: Boolean?,
  systemTarget: HookedTarget.State?,
  launcherTarget: HookedTarget.State?,
): StatusTone = when {
  !connected || readFailed ||
    systemInScope == false || systemTarget == HookedTarget.State.FAILED ||
    launcherTarget == HookedTarget.State.FAILED -> StatusTone.ERROR
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
  runtime: RuntimeStatusUiState = RuntimeStatusUiState(),
  iconSource: os.kei.hyper.home.status.SystemIconThemeReport? = null,
  coveredApps: Int? = null, onOpenPacks: () -> Unit = {}, onOpenEnvironment: () -> Unit = {},
) {
  // The containing page's SaveableStateProvider retains these across tab switches.
  var runtimeExpanded by rememberSaveable { mutableStateOf(false) }
  var monetExpanded by rememberSaveable { mutableStateOf(false) }
  val framework = frameworkCardState(connected, readFailed, snapshot)
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    RuntimeStatusSection(framework, runtime, runtimeExpanded,
      { runtimeExpanded = !runtimeExpanded }, onRefresh, onOpenEnvironment)
    DashboardMonetCard(
      snapshot = monetSnapshot,
      readFailed = monetReadFailed,
      expanded = monetExpanded,
      onToggle = { monetExpanded = !monetExpanded },
      onOpenMonet = onOpenMonet,
    )
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp), onClick = onOpenPacks) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(stringResource(R.string.icon_pack_active), style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
          Text(iconSource?.title ?: stringResource(R.string.status_unknown), style = MiuixTheme.textStyles.body1)
        }
        MetadataPill(if (iconSource?.previewKey != null && coveredApps != null)
          stringResource(R.string.browser_dashboard_coverage, coveredApps) else stringResource(if (iconSource?.previewKey != null) R.string.browser_resources_ready else R.string.status_needs_verification),
          color = MiuixTheme.colorScheme.primary)
      }
    }
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

internal enum class NativeStatusKind(@param:StringRes val label: Int) {
  READY(R.string.runtime_native_ready),
  CHECKING(R.string.runtime_checking),
  NOT_REPORTED(R.string.runtime_not_reported),
  EXPIRED(R.string.runtime_expired),
  GENERATION_MISMATCH(R.string.runtime_generation_mismatch),
  UNAVAILABLE(R.string.runtime_unavailable),
  INACTIVE_USER(R.string.runtime_inactive_user),
  TIMED_OUT(R.string.runtime_timed_out),
  READ_FAILED(R.string.runtime_read_failed),
}

@Immutable
internal data class RuntimeCardState(
  val kind: NativeStatusKind,
  val report: RuntimeStatusReport?,
)

internal fun runtimeCardState(
  runtime: RuntimeStatusUiState,
  now: Long,
  installedVersion: Long = BuildConfig.VERSION_CODE.toLong(),
): RuntimeCardState {
  // Authentication and process identity are checked by the transport. Time and reporting
  // generation must still be checked here, including on the first composition after resume.
  val report = runtime.report?.takeIf { it.isFresh(now) && !runtime.readFailed && !runtime.timedOut }
  val kind = when {
    runtime.readFailed -> NativeStatusKind.READ_FAILED
    runtime.timedOut -> NativeStatusKind.TIMED_OUT
    report == null -> when {
      runtime.checking -> NativeStatusKind.CHECKING
      runtime.report != null -> NativeStatusKind.EXPIRED
      else -> NativeStatusKind.NOT_REPORTED
    }
    else -> when (report.nativeState) {
      NativeRuntimeState.INACTIVE_USER -> NativeStatusKind.INACTIVE_USER
      NativeRuntimeState.UNAVAILABLE -> NativeStatusKind.UNAVAILABLE
      NativeRuntimeState.READY -> if (report.moduleVersion == installedVersion) NativeStatusKind.READY
        else NativeStatusKind.GENERATION_MISMATCH
    }
  }
  return RuntimeCardState(kind, report)
}

@Immutable
internal data class ModuleStatusPresentation(@param:StringRes val title: Int, val tone: StatusTone)

internal fun moduleStatusPresentation(
  framework: FrameworkCardState,
  runtime: RuntimeCardState,
): ModuleStatusPresentation {
  val title = when {
    !framework.connected || framework.readFailed -> R.string.status_overview_error_title
    framework.systemInScope == false -> R.string.polish_status_scope_needed
    framework.systemState == HookedTarget.State.FAILED -> R.string.status_target_failed
    framework.launcherState == HookedTarget.State.FAILED -> R.string.polish_status_launcher_failed
    runtime.kind == NativeStatusKind.READ_FAILED -> R.string.runtime_read_failed
    framework.systemState == HookedTarget.State.RELOADING -> R.string.status_target_reloading
    framework.systemState == HookedTarget.State.STALE -> R.string.polish_status_update_pending
    framework.systemInScope != true || framework.systemState != HookedTarget.State.UP_TO_DATE ->
      R.string.polish_status_waiting
    runtime.kind == NativeStatusKind.READY -> R.string.runtime_module_ready
    else -> runtime.kind.label
  }
  val frameworkTone = statusTone(framework.connected, framework.readFailed, framework.systemInScope,
    framework.systemState, framework.launcherState)
  val tone = when {
    frameworkTone == StatusTone.ERROR || runtime.kind == NativeStatusKind.READ_FAILED -> StatusTone.ERROR
    title == R.string.runtime_module_ready -> StatusTone.READY
    else -> StatusTone.WARNING
  }
  return ModuleStatusPresentation(title, tone)
}

@Composable
private fun RuntimeStatusSection(
  framework: FrameworkCardState,
  runtime: RuntimeStatusUiState,
  expanded: Boolean,
  onToggle: () -> Unit,
  onRefresh: () -> Unit,
  onOpenEnvironment: () -> Unit,
) {
  val current = if (framework.connected && !framework.readFailed) runtime else RuntimeStatusUiState()
  val checkedAt = current.report?.checkedAtElapsed
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var elapsed by remember(checkedAt) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
  // Wake once at expiry, and immediately on resume. No dashboard-wide polling clock.
  LaunchedEffect(checkedAt, lifecycle) {
    if (checkedAt == null) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      val now = SystemClock.elapsedRealtime()
      elapsed = now
      if (now >= checkedAt && now - checkedAt <= RuntimeStatusReport.FRESHNESS_MS) {
        delay(RuntimeStatusReport.FRESHNESS_MS - (now - checkedAt) + 1)
        elapsed = SystemClock.elapsedRealtime()
      }
    }
  }
  val native = runtimeCardState(current, maxOf(elapsed, SystemClock.elapsedRealtime()))
  FrameworkHero(framework, native, onRefresh, onOpenEnvironment)
  RuntimeStatusCard(framework, native, expanded, onToggle)
}

@Composable
private fun FrameworkHero(state: FrameworkCardState, runtime: RuntimeCardState, onRefresh: () -> Unit,
  onOpenEnvironment: () -> Unit) {
  val context = LocalContext.current
  val environment by produceState<AboutDeviceDetails?>(null, context) {
    value = withContext(Dispatchers.IO) { loadAboutDeviceDetails(context) }
  }
  val device = environment
  val environmentSummary = if (device == null) null else listOfNotNull(
    "${device.marketName} · ${android.os.Build.VERSION.INCREMENTAL.substringBeforeLast('.', android.os.Build.VERSION.INCREMENTAL)}",
    listOfNotNull(device.launcherVersion?.let { version ->
      "Home ${Regex("(?<![0-9])[0-9]{3,5}(?![0-9])").findAll(version).lastOrNull()?.value ?: version}"
    },
      device.rustVersion?.takeIf(String::isNotBlank)?.let { "Rust $it" },
      device.flutterVersion?.takeIf(String::isNotBlank)?.let { "Flutter ${it.substringBefore("-mi-")}" })
      .takeIf { it.isNotEmpty() }?.joinToString(" · "),
  ).joinToString("\n")
  val presentation = moduleStatusPresentation(state, runtime)
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
    title = stringResource(presentation.title),
    summary = summary,
    details = emptyList(),
    tone = presentation.tone,
    onRefresh = onRefresh,
    onOpen = onOpenEnvironment,
    environmentSummary = environmentSummary,
    footer = when {
      // Java hot reload is a separate observation from the resident native endpoint.
      state.launcherState == HookedTarget.State.STALE ||
        state.launcherState == HookedTarget.State.RELOADING -> stringResource(
          R.string.runtime_java_summary, targetLabel(state.launcherState, state.reported),
        )
      presentation.tone == StatusTone.READY && state.apiVersion != null ->
        stringResource(R.string.runtime_endpoint_verified, state.apiVersion)
      state.reported && state.apiVersion != null ->
        stringResource(R.string.status_hero_load_report_api, state.apiVersion)
      state.reported -> stringResource(R.string.status_hero_load_report)
      state.connected && !state.readFailed -> stringResource(R.string.status_framework_pending)
      else -> null
    },
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
private fun RuntimeStatusCard(
  state: FrameworkCardState,
  runtime: RuntimeCardState,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  val summary = if (runtime.kind == NativeStatusKind.READY) {
    stringResource(R.string.runtime_ready_summary, targetLabel(state.systemState, state.reported))
  } else stringResource(runtime.kind.label)
  val report = runtime.report
  val loadedAccent = colorResource(if (LocalKeiHomeDarkTheme.current)
    R.color.status_hero_loaded_accent_dark else R.color.status_hero_loaded_accent)
  DashboardExpandableCard(
    title = stringResource(R.string.dashboard_runtime),
    summary = summary,
    expanded = expanded,
    onToggle = onToggle,
    footer = { DashboardCallbackReceipt(report?.refresh) },
  ) {
    report?.launcherPid?.let { pid ->
      DashboardDetail(
        title = stringResource(R.string.runtime_launcher_process),
        value = stringResource(R.string.runtime_launcher_pid, pid),
        pillColor = MiuixTheme.colorScheme.primary,
      )
    }
    report?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
      DashboardDetail(title = stringResource(R.string.runtime_diagnostic), value = detail, stacked = true)
    }
    report?.let {
      DashboardDetail(
        title = stringResource(R.string.runtime_reporting_generation),
        value = stringResource(R.string.dashboard_version_code, it.moduleVersion),
        pillColor = MiuixTheme.colorScheme.primary,
      )
    }
    DashboardDetail(
      title = stringResource(R.string.dashboard_system_scope),
      value = stringResource(when (state.systemInScope) {
        true -> R.string.status_scope_enabled
        false -> R.string.status_scope_missing
        null -> R.string.status_unknown
      }),
      pillColor = if (state.systemInScope == true) loadedAccent else MiuixTheme.colorScheme.onSurfaceSecondary,
    )
    TargetDetail("system_server", state.systemState, state.systemVersion, state.reported)
    if (state.reported && state.launcherState == null && runtime.kind == NativeStatusKind.READY) {
      DashboardDetail(
        title = stringResource(R.string.runtime_java_load_report),
        value = stringResource(R.string.runtime_java_optional),
      )
    } else {
      TargetDetail(stringResource(R.string.runtime_java_load_report),
        state.launcherState, state.launcherVersion, state.reported)
    }
    DashboardDetail(
      title = stringResource(R.string.dashboard_framework_version),
      value = frameworkVersionLabel(state.frameworkVersion, state.frameworkVersionCode),
    )
    state.apiVersion?.let {
      DashboardDetail(title = stringResource(R.string.runtime_framework_api), value = it.toString())
    }
    DashboardDetail(
      title = stringResource(R.string.polish_installed_version),
      value = BuildConfig.VERSION_CODE.toString(),
      pillColor = MiuixTheme.colorScheme.primary,
    )
    DashboardFootnote(
      text = stringResource(R.string.runtime_native_note),
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
    pillColor = if (state == HookedTarget.State.UP_TO_DATE) colorResource(
      if (LocalKeiHomeDarkTheme.current) R.color.status_hero_loaded_accent_dark
      else R.color.status_hero_loaded_accent) else null,
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
        "SYSTEM_COLOR" -> R.string.pack_source_system
        else -> R.string.status_unknown
      }),
      pillColor = if (current?.source != null) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.onSurfaceSecondary,
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
      pillColor = if (palette?.state == MonetReadState.AVAILABLE && palette.generatedColorCount > 0)
        MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
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
  "COLOROS_INSPIRATION" -> stringResource(R.string.pack_style_inspiration)
  else -> style?.takeIf { it.isNotBlank() } ?: stringResource(R.string.status_unknown)
}

/** A future or invalid timestamp cannot establish how recently a palette was applied. */
internal fun dashboardPaletteAgeMinutes(timestamp: Long, now: Long): Long? =
  if (timestamp <= 0 || timestamp > now) null else (now - timestamp) / 60_000

@Composable
private fun dashboardAgeMinutes(timestamp: Long?): Long? {
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
  return minutes
}

@Composable
private fun dashboardRelativeTime(age: Long?): String = when {
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

@Composable
private fun DashboardCallbackReceipt(receipt: IconRefreshReceipt?) {
  if (receipt == null || receipt.callbacks < 0 || receipt.completedAtMillis <= 0) {
    DashboardFootnote(stringResource(R.string.runtime_callbacks_unreported))
    return
  }
  val relative = dashboardRelativeTime(dashboardAgeMinutes(receipt.completedAtMillis))
  MetadataPill(pluralStringResource(
    R.plurals.runtime_callbacks_dispatched, receipt.callbacks, receipt.callbacks, relative,
  ), MiuixTheme.colorScheme.primary)
}

@Composable
private fun DashboardPaletteAge(timestamp: Long?) {
  val relative = dashboardRelativeTime(dashboardAgeMinutes(timestamp))
  MetadataPill(
    if (timestamp == null) stringResource(R.string.dashboard_no_palette_record)
    else stringResource(R.string.dashboard_last_palette, relative),
    MiuixTheme.colorScheme.onSurfaceSecondary,
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
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)) {
          CapabilityCount(installedApps, stringResource(R.string.polish_apps_total), MiuixTheme.colorScheme.primary)
          CapabilityCount(nightApps, stringResource(R.string.polish_apps_night), colorResource(
            if (LocalKeiHomeDarkTheme.current) R.color.status_hero_loaded_accent_dark else R.color.status_hero_loaded_accent))
          CapabilityCount(monetApps, stringResource(R.string.polish_apps_monet), MiuixTheme.colorScheme.primary)
        }
      },
    )
  }
}

@Composable
private fun CapabilityCount(count: Int?, label: String, color: Color) {
  val value = count?.takeIf { it >= 0 }?.let { stringResource(R.string.polish_apps_count, it) }
    ?: stringResource(R.string.polish_value_pending)
  MetadataPill("$label $value", if (count != null && count >= 0) color else MiuixTheme.colorScheme.onSurfaceSecondary)
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
private fun DashboardDetail(title: String, value: String, stacked: Boolean = false, pillColor: Color? = null) {
  BasicComponent(
    title = title,
    summary = value.takeIf { stacked },
    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    endActions = if (stacked) null else ({
      if (pillColor != null) MetadataPill(value, pillColor)
      else Text(value, style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
        textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }),
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
  onOpen: (() -> Unit)? = null,
  environmentSummary: String? = null,
) {
  val refreshLabel = stringResource(R.string.status_refresh)
  val dark = LocalKeiHomeDarkTheme.current
  val colors = MiuixTheme.colorScheme
  // Status colors stay semantic even when the app's accent comes from wallpaper.
  // Use the app appearance, which may differ from the system's light/dark mode.
  val accentColor = when (tone) {
    StatusTone.READY -> colorResource(
      if (dark) R.color.status_hero_loaded_accent_dark else R.color.status_hero_loaded_accent,
    )
    StatusTone.WARNING -> colorResource(
      if (dark) R.color.status_hero_warning_accent_dark else R.color.status_hero_warning_accent,
    )
    StatusTone.ERROR -> colors.error
  }
  val backgroundColor = when (tone) {
    StatusTone.READY -> colorResource(if (dark) R.color.status_hero_ready_container_dark else R.color.status_hero_ready_container)
    StatusTone.WARNING -> colorResource(if (dark) R.color.status_hero_warning_container_dark else R.color.status_hero_warning_container)
    StatusTone.ERROR -> colorResource(if (dark) R.color.status_hero_error_container_dark else R.color.status_hero_error_container)
  }
  val contentColor = when (tone) {
    StatusTone.READY -> colorResource(if (dark) R.color.status_hero_ready_content_dark else R.color.status_hero_ready_content)
    StatusTone.WARNING -> colorResource(if (dark) R.color.status_hero_warning_content_dark else R.color.status_hero_warning_content)
    StatusTone.ERROR -> colorResource(if (dark) R.color.status_hero_error_content_dark else R.color.status_hero_error_content)
  }
  val icon = when (tone) {
    StatusTone.READY -> MiuixIcons.Ok
    StatusTone.WARNING -> MiuixIcons.Info
    StatusTone.ERROR -> MiuixIcons.Report
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .semantics { this.onClick(label = refreshLabel, action = null) },
    insideMargin = PaddingValues(0.dp),
    colors = CardDefaults.defaultColors(color = backgroundColor, contentColor = contentColor),
    onClick = onOpen ?: onRefresh,
    showIndication = true,
    pressFeedbackType = PressFeedbackType.Tilt,
  ) {
    Box(Modifier.fillMaxWidth().clipToBounds()) {
      // This layer follows the text's measured height; the decorative symbol never
      // expands the card or overlaps the text column, including at large font sizes.
      Box(Modifier.matchParentSize()) {
        Icon(
          imageVector = icon,
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 27.dp, y = 31.dp)
            .size(130.dp)
            .then(
              if (tone == StatusTone.READY) {
                Modifier.border(5.dp, accentColor, CircleShape).padding(26.dp)
              } else Modifier,
            ),
          tint = accentColor,
          contentDescription = null,
        )
      }
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 144.dp)
          .padding(start = 18.dp, end = 112.dp, top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
            text = title,
            style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
          )
          Text(
            text = summary,
            style = MiuixTheme.textStyles.body2,
            color = contentColor.copy(alpha = 0.84f),
          )
        }
        if (footer != null) {
          Text(
            text = footer,
            modifier = Modifier.padding(top = 16.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = contentColor,
          )
        }
      }
    }
    if (environmentSummary != null) Text(environmentSummary,
      modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
      style = MiuixTheme.textStyles.footnote2, color = contentColor.copy(alpha = 0.86f),
      maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (details.isNotEmpty()) {
      Column(
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
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
