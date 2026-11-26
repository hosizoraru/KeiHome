package os.kei.hyper.home.ui.main

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.libxposed.service.HookedTarget
import kotlinx.coroutines.delay
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.InitialActivationStatus
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4BadgePalette
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4DialogButton
import os.kei.hyper.home.ui.components.Os4IconBadge
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.os4ContentSize
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import os.kei.hyper.home.ui.components.os4Clickable
import os.kei.hyper.home.R
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.MonetReadState
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.IconRefreshReceipt
import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.Link
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.icon.glass.Report
import top.yukonga.miuix.kmp.icon.glass.Theme
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

internal enum class StatusTone {
  CHECKING,
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
  coveredApps: Int? = null, onOpenPacks: () -> Unit = {}, onOpenEnvironment: (() -> Unit)? = null,
  scopeContent: @Composable () -> Unit = {},
  activation: InitialActivationStatus = InitialActivationStatus.COMPLETE,
  onOpenGuide: () -> Unit = {},
  frameworkChecking: Boolean = false,
) {
  val framework = frameworkCardState(connected, readFailed, snapshot, frameworkChecking)
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    RuntimeStatusSection(framework, runtime, onRefresh, onOpenEnvironment, activation, onOpenGuide)
    scopeContent()
    DashboardMonetCard(
      snapshot = monetSnapshot,
      readFailed = monetReadFailed,
      onOpenMonet = onOpenMonet,
    )
    DashboardActivePackCard(iconSource, coveredApps, onOpenPacks)
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
  val checking: Boolean = false,
)

internal fun frameworkCardState(
  connected: Boolean,
  readFailed: Boolean,
  snapshot: FrameworkSnapshot?,
  checking: Boolean = false,
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
    checking = !readFailed && current == null && (checking || connected),
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
  CAPABILITY_MISSING(R.string.runtime_capability_missing),
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
      NativeRuntimeState.UNAVAILABLE -> if (report.hasMissingMonetCapability) NativeStatusKind.CAPABILITY_MISSING
        else NativeStatusKind.UNAVAILABLE
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
    framework.checking -> R.string.runtime_checking
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
    framework.checking -> StatusTone.CHECKING
    frameworkTone == StatusTone.ERROR || runtime.kind == NativeStatusKind.READ_FAILED -> StatusTone.ERROR
    title == R.string.runtime_module_ready -> StatusTone.READY
    title == R.string.runtime_checking -> StatusTone.CHECKING
    else -> StatusTone.WARNING
  }
  return ModuleStatusPresentation(title, tone)
}

@Composable
private fun RuntimeStatusSection(
  framework: FrameworkCardState,
  runtime: RuntimeStatusUiState,
  onRefresh: () -> Unit,
  onOpenEnvironment: (() -> Unit)?,
  activation: InitialActivationStatus,
  onOpenGuide: () -> Unit,
) {
  val current = if (framework.connected && !framework.readFailed) runtime else RuntimeStatusUiState()
  val checkedAt = current.report?.checkedAtElapsed
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var elapsed by remember(checkedAt) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
  // Wake once at expiry, and immediately on resume. No dashboard-wide polling clock.
  LaunchedEffect(checkedAt, lifecycle) {
    if (checkedAt == null) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (true) {
        val now = SystemClock.elapsedRealtime()
        elapsed = now
        if (now < checkedAt) break
        val remaining = RuntimeStatusReport.FRESHNESS_MS - (now - checkedAt)
        if (remaining < 0L) break
        delay(remaining + 1L)
      }
    }
  }
  val native = runtimeCardState(current, maxOf(elapsed, SystemClock.elapsedRealtime()))
  FrameworkHero(framework, native, onRefresh, onOpenEnvironment, activation)
  Os4AnimatedVisibility(visible = activation != InitialActivationStatus.COMPLETE, label = "status-activation") {
    Card(Modifier.fillMaxWidth().testTag("status.activation"), insideMargin = PaddingValues(16.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(if (activation == InitialActivationStatus.UNKNOWN)
          R.string.activation_reboot_unknown else R.string.activation_reboot_note),
          style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Os4DialogButton(stringResource(R.string.activation_reboot_guide),
          modifier = Modifier.fillMaxWidth().testTag("status.activation.guide"), onClick = onOpenGuide)
      }
    }
  }
  RuntimeStatusCard(framework, native, activation)
}

@Composable
private fun FrameworkHero(state: FrameworkCardState, runtime: RuntimeCardState, onRefresh: () -> Unit,
  onOpenEnvironment: (() -> Unit)?, activation: InitialActivationStatus) {
  val context = LocalContext.current
  val environment by produceState<AboutDeviceDetails?>(null, context) {
    value = withContext(Dispatchers.IO) { loadAboutDeviceDetails(context) }
  }
  val device = environment
  val environmentDetails = if (device == null) emptyList() else listOfNotNull(
    "${device.marketName} · ${android.os.Build.VERSION.INCREMENTAL.substringBeforeLast('.', android.os.Build.VERSION.INCREMENTAL)}",
    listOfNotNull(device.launcherVersion?.let { version ->
      compactLauncherVersion(version) ?: stringResource(R.string.desktop_connection_version_unknown)
    },
      device.rustVersion?.takeIf(String::isNotBlank)?.let { "Rust $it" },
      device.flutterVersion?.takeIf(String::isNotBlank)?.let { "Flutter ${it.substringBefore("-mi-")}" })
      .takeIf { it.isNotEmpty() }?.joinToString(" · "),
  )
  val presentation = if (activation != InitialActivationStatus.COMPLETE)
    ModuleStatusPresentation(R.string.activation_reboot_title, StatusTone.WARNING)
  else moduleStatusPresentation(state, runtime)
  val summary = when {
    state.checking -> stringResource(R.string.dashboard_reading_status)
    !state.connected -> stringResource(R.string.status_framework_disconnected)
    state.readFailed -> stringResource(R.string.status_framework_error)
    !state.reported -> stringResource(R.string.dashboard_reading_status)
    else -> stringResource(
      R.string.dashboard_framework_identity,
      state.name ?: stringResource(R.string.status_unknown),
      frameworkVersionLabel(state.frameworkVersion, state.frameworkVersionCode),
    )
  }
  val connectionBadge = state.apiVersion?.takeIf {
    activation == InitialActivationStatus.COMPLETE && presentation.tone == StatusTone.READY &&
      state.launcherState != HookedTarget.State.STALE && state.launcherState != HookedTarget.State.RELOADING
  }?.let { stringResource(R.string.runtime_endpoint_verified, it) }
  StatusOverviewCard(
    title = stringResource(presentation.title),
    titleBadge = BuildConfig.VERSION_NAME.substringBefore('+'),
    modifier = Modifier.testTag("status.framework.card"),
    summary = summary,
    details = emptyList(),
    tone = presentation.tone,
    onRefresh = onRefresh,
    onOpen = onOpenEnvironment,
    actionLabel = if (onOpenEnvironment != null) stringResource(R.string.status_open_environment) else null,
    environmentDetails = environmentDetails,
    summaryBadge = connectionBadge,
    footer = when {
      activation != InitialActivationStatus.COMPLETE || connectionBadge != null -> null
      // Java hot reload is a separate observation from the resident native endpoint.
      state.launcherState == HookedTarget.State.STALE ||
        state.launcherState == HookedTarget.State.RELOADING -> stringResource(
          R.string.runtime_java_summary, targetLabel(state.launcherState, state.reported),
        )
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
  activation: InitialActivationStatus,
) {
  val report = runtime.report
  val loadedAccent = colorResource(if (LocalKeiHomeDarkTheme.current)
    R.color.status_hero_loaded_accent_dark else R.color.status_hero_loaded_accent)
  val warningAccent = colorResource(if (LocalKeiHomeDarkTheme.current)
    R.color.status_hero_warning_accent_dark else R.color.status_hero_warning_accent)
  val presentation = if (activation != InitialActivationStatus.COMPLETE)
    ModuleStatusPresentation(R.string.activation_reboot_title, StatusTone.WARNING)
  else moduleStatusPresentation(state, runtime)
  val badgeTint = when (presentation.tone) {
    StatusTone.READY -> loadedAccent
    StatusTone.WARNING -> warningAccent
    StatusTone.ERROR -> MiuixTheme.colorScheme.error
    StatusTone.CHECKING -> MiuixTheme.colorScheme.onSurfaceSecondary
  }
  val cardSubtitle = listOfNotNull(
    report?.launcherPid?.let { stringResource(R.string.runtime_launcher_pid, it) },
    state.apiVersion?.let { "API $it" },
  ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
  DashboardSheetCard(
    title = stringResource(R.string.dashboard_runtime),
    subtitle = cardSubtitle,
    icon = MiuixIcons.Glass.Link,
    iconTint = badgeTint,
    tag = "status.runtime",
    overview = {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Os4IconBadge(MiuixIcons.Glass.Link, badgeTint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            stringResource(presentation.title),
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            listOfNotNull(state.name, frameworkVersionLabel(state.frameworkVersion, state.frameworkVersionCode))
              .joinToString(" · "),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      DashboardPillRow {
        MetadataPill(BuildConfig.VERSION_NAME.substringBefore('+'), MiuixTheme.colorScheme.primary)
        state.apiVersion?.let {
          MetadataPill(stringResource(R.string.runtime_framework_api) + " $it", MiuixTheme.colorScheme.primary)
        }
      }
    },
    footer = { sheet ->
      val suffix = if (sheet) ".sheet" else ""
      DashboardPillRow {
        DashboardStatePill(stringResource(R.string.dashboard_pill_system),
          targetLabel(state.systemState, state.reported), when (state.systemState) {
            HookedTarget.State.UP_TO_DATE -> StatusTone.READY
            HookedTarget.State.FAILED -> StatusTone.ERROR
            HookedTarget.State.RELOADING, HookedTarget.State.STALE -> StatusTone.WARNING
            null -> null
          }, "status.runtime.system$suffix")
        DashboardStatePill(stringResource(R.string.dashboard_pill_launcher),
          stringResource(if (runtime.kind == NativeStatusKind.READY) R.string.dashboard_pill_ready
            else runtime.kind.label), when (runtime.kind) {
            NativeStatusKind.READY -> StatusTone.READY
            NativeStatusKind.CHECKING -> StatusTone.CHECKING
            NativeStatusKind.READ_FAILED, NativeStatusKind.TIMED_OUT -> StatusTone.ERROR
            NativeStatusKind.NOT_REPORTED, NativeStatusKind.INACTIVE_USER,
            NativeStatusKind.UNAVAILABLE -> null
            else -> StatusTone.WARNING
          }, "status.runtime.launcher$suffix")
        DashboardCallbackReceipt(report?.refresh, compact = !sheet)
      }
    },
    footnote = stringResource(R.string.runtime_native_note),
  ) {
    report?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
      DashboardDetail(title = stringResource(R.string.runtime_diagnostic), value = detail, stacked = true)
      DashboardDetailDivider()
    }
    if (activation != InitialActivationStatus.COMPLETE) {
      DashboardFootnote(stringResource(if (activation == InitialActivationStatus.UNKNOWN)
        R.string.activation_reboot_unknown else R.string.activation_reboot_note),
        Modifier.padding(bottom = 8.dp))
    } else if (runtime.kind == NativeStatusKind.CAPABILITY_MISSING) {
      DashboardFootnote(
        text = stringResource(R.string.runtime_capability_missing_note),
        modifier = Modifier.padding(bottom = 8.dp),
      )
    }
    DashboardPillRow(Modifier.padding(bottom = 6.dp)) {
      report?.launcherPid?.let { pid ->
        MetadataPill(stringResource(R.string.runtime_launcher_pid, pid), MiuixTheme.colorScheme.primary)
      }
      report?.let {
        MetadataPill(stringResource(R.string.runtime_reporting_generation) + " ${it.moduleVersion}",
          MiuixTheme.colorScheme.primary)
      }
      MetadataPill(stringResource(R.string.polish_installed_version) + " ${BuildConfig.VERSION_CODE}",
        MiuixTheme.colorScheme.onSurfaceSecondary)
    }
    DashboardDetail(
      title = stringResource(R.string.dashboard_system_scope),
      modifier = Modifier.testTag("status.runtime.scope"),
      value = stringResource(when (state.systemInScope) {
        true -> R.string.status_scope_enabled
        false -> R.string.status_scope_missing
        null -> R.string.status_unknown
      }),
      pillColor = if (state.systemInScope == true) loadedAccent else MiuixTheme.colorScheme.onSurfaceSecondary,
    )
    DashboardDetailDivider()
    TargetDetail("system_server", state.systemState, state.systemVersion, state.reported)
    DashboardDetailDivider()
    if (state.reported && state.launcherState == null && runtime.kind == NativeStatusKind.READY) {
      DashboardDetail(
        title = stringResource(R.string.runtime_java_load_report),
        value = stringResource(R.string.runtime_java_optional),
      )
    } else {
      TargetDetail(stringResource(R.string.runtime_java_load_report),
        state.launcherState, state.launcherVersion, state.reported)
    }
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
  onOpenMonet: () -> Unit,
) {
  val current = snapshot.takeUnless { readFailed }
  val palette = current?.activePalette
  val swatches = remember(current) {
    val fromFamilies = palette?.families.orEmpty().mapNotNull { family ->
      family.colors.getOrNull(family.colors.size / 2)?.value
    }.distinct().take(5)
    if (fromFamilies.isNotEmpty()) fromFamilies
    else current?.wallpaper?.colors.orEmpty().distinct().take(5)
  }
  val paletteLabel = stringResource(when {
    readFailed -> R.string.monet_state_unavailable
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
  val badgeTint = swatches.firstOrNull()?.let { Color(it) } ?: Os4BadgePalette.Purple
  DashboardSheetCard(
    title = stringResource(R.string.settings_monet_title),
    subtitle = current?.style?.takeIf { it.isNotBlank() }?.let { dashboardMonetStyle(it) },
    icon = actionIcon(PageActionIcon.MONET),
    iconTint = badgeTint,
    headerEnd = if (swatches.isNotEmpty()) {
      { MonetSwatchDots(swatches) }
    } else null,
    tag = "status.monet",
    onOpen = onOpenMonet,
    openLabel = stringResource(R.string.status_sheet_monet_settings),
    overview = {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Os4IconBadge(actionIcon(PageActionIcon.MONET), badgeTint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            stringResource(R.string.settings_monet_title),
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            "KeiHome " + BuildConfig.VERSION_NAME.substringBefore('+'),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
          )
        }
      }
      if (swatches.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          swatches.forEach { argb ->
            Box(
              Modifier.weight(1f)
                .height(20.dp)
                .clip(CircleShape)
                .background(Color(argb))
                .border(0.75.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.14f), CircleShape),
            )
          }
        }
      }
    },
    footer = { sheet ->
      val suffix = if (sheet) ".sheet" else ""
      DashboardPillRow {
        DashboardStatePill(stringResource(R.string.dashboard_pill_icons), iconsLabel,
          if (current?.monochrome?.enabled == true) StatusTone.READY else null, "status.monet.icons$suffix")
        DashboardStatePill(stringResource(R.string.dashboard_pill_palette), paletteLabel, when {
          readFailed || palette?.state == MonetReadState.UNAVAILABLE -> StatusTone.ERROR
          palette == null || palette.state == MonetReadState.NOT_PROVIDED ||
            palette.generatedColorCount == 0 -> null
          else -> StatusTone.READY
        }, "status.monet.palette$suffix")
        if (current != null) DashboardPaletteAge(current.lastAppliedRevision)
      }
    },
    footnote = stringResource(R.string.dashboard_monet_report_note),
  ) {
    DashboardDetail(
      title = stringResource(R.string.monet_seed_source),
      modifier = Modifier.testTag("status.monet.source"),
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
    DashboardDetailDivider()
    DashboardDetail(
      title = stringResource(R.string.dashboard_style),
      modifier = Modifier.testTag("status.monet.style"),
      value = dashboardMonetStyle(current?.style),
    )
    DashboardDetailDivider()
    DashboardDetail(
      title = stringResource(R.string.monet_palette),
      modifier = Modifier.testTag("status.monet.colors"),
      value = if (palette?.state == MonetReadState.AVAILABLE && palette.generatedColorCount > 0) {
        stringResource(R.string.monet_palette_value, palette.families.size, palette.generatedColorCount)
      } else paletteLabel,
      pillColor = if (palette?.state == MonetReadState.AVAILABLE && palette.generatedColorCount > 0)
        MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
    )
  }
}

@Composable
private fun MonetSwatchDots(swatches: List<Int>) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
    swatches.take(4).forEach { argb ->
      Box(
        Modifier.size(14.dp)
          .clip(CircleShape)
          .background(Color(argb))
          .border(0.75.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.16f), CircleShape),
      )
    }
  }
}

@Composable
private fun DashboardActivePackCard(
  iconSource: os.kei.hyper.home.status.SystemIconThemeReport?,
  coveredApps: Int?,
  onOpenPacks: () -> Unit,
) {
  val pressSource = rememberOs4PressSource()
  Card(
    Modifier.fillMaxWidth()
      .testTag("status.icon-pack.card")
      .os4ContentSize()
      .glassPress(pressSource)
      .os4Clickable(role = Role.Button, cornerRadius = CardDefaults.CornerRadius, onClick = onOpenPacks),
    insideMargin = PaddingValues(16.dp),
  ) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Os4IconBadge(MiuixIcons.Glass.Theme, Os4BadgePalette.Pink)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            stringResource(R.string.icon_pack_active),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
          )
          Text(
            iconSource?.title ?: stringResource(R.string.status_unknown),
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        DashboardArrow()
      }
      DashboardPillRow {
        MetadataPill(
          if (iconSource?.previewKey != null && coveredApps != null)
            stringResource(R.string.browser_dashboard_coverage, coveredApps)
          else stringResource(if (iconSource?.previewKey != null) R.string.browser_resources_ready
            else R.string.status_needs_verification),
          color = if (iconSource?.previewKey != null) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceSecondary,
        )
        iconSource?.version?.let {
          MetadataPill(stringResource(R.string.icon_pack_version_label, it), MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
    }
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
private fun DashboardCallbackReceipt(receipt: IconRefreshReceipt?, compact: Boolean) {
  if (receipt == null || receipt.callbacks < 0 || receipt.completedAtMillis <= 0) {
    MetadataPill(stringResource(if (compact) R.string.runtime_callbacks_no_record
      else R.string.runtime_callbacks_unreported), MiuixTheme.colorScheme.onSurfaceSecondary)
    return
  }
  val relative = dashboardRelativeTime(dashboardAgeMinutes(receipt.completedAtMillis))
  MetadataPill(pluralStringResource(
    if (compact) R.plurals.runtime_callbacks_compact else R.plurals.runtime_callbacks_dispatched,
    receipt.callbacks, receipt.callbacks, relative,
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
  val pressSource = rememberOs4PressSource()
  val loadedAccent = colorResource(if (LocalKeiHomeDarkTheme.current)
    R.color.status_hero_loaded_accent_dark else R.color.status_hero_loaded_accent)
  Card(
    Modifier.fillMaxWidth()
      .os4ContentSize()
      .glassPress(pressSource)
      .os4Clickable(
        role = Role.Button,
        cornerRadius = CardDefaults.CornerRadius,
        onClickLabel = stringResource(R.string.polish_apps_open),
        onClick = onOpenApps,
      ),
    insideMargin = PaddingValues(16.dp),
  ) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Os4IconBadge(actionIcon(PageActionIcon.APPS), Os4BadgePalette.Blue)
        Text(
          stringResource(if (installedApps == null) R.string.dashboard_apps_unscanned else R.string.dashboard_apps),
          modifier = Modifier.weight(1f),
          style = MiuixTheme.textStyles.body1,
          fontWeight = FontWeight.SemiBold,
        )
        DashboardArrow()
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CapabilityMetricTile(
          count = installedApps,
          label = stringResource(R.string.polish_apps_total),
          color = Os4BadgePalette.Blue,
          modifier = Modifier.weight(1f),
        )
        CapabilityMetricTile(
          count = nightApps,
          label = stringResource(R.string.polish_apps_night),
          color = loadedAccent,
          modifier = Modifier.weight(1f),
        )
        CapabilityMetricTile(
          count = monetApps,
          label = stringResource(R.string.polish_apps_monet),
          color = Os4BadgePalette.Purple,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun CapabilityMetricTile(
  count: Int?,
  label: String,
  color: Color,
  modifier: Modifier = Modifier,
) {
  val ready = count != null && count >= 0
  val value = count?.takeIf { it >= 0 }?.let { stringResource(R.string.polish_apps_count, it) }
    ?: stringResource(R.string.polish_value_pending)
  val accent = if (ready) color else MiuixTheme.colorScheme.onSurfaceSecondary
  Column(
    modifier
      .squircleBackground(accent.copy(alpha = 0.10f), 14.dp)
      .padding(horizontal = 10.dp, vertical = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
      value,
      style = MiuixTheme.textStyles.body1,
      fontWeight = FontWeight.Bold,
      color = accent,
      maxLines = 1,
    )
    Text(
      label,
      style = MiuixTheme.textStyles.footnote2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun DashboardSheetCard(
  title: String,
  icon: ImageVector,
  iconTint: Color,
  tag: String,
  subtitle: String? = null,
  headerEnd: (@Composable () -> Unit)? = null,
  onOpen: (() -> Unit)? = null,
  openLabel: String? = null,
  footnote: String? = null,
  overview: @Composable ColumnScope.() -> Unit = {},
  footer: @Composable ColumnScope.(Boolean) -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  var mounted by remember { mutableStateOf(false) }
  var showing by remember { mutableStateOf(false) }
  var openAfterExit by remember { mutableStateOf(false) }
  val openDestination by rememberUpdatedState(onOpen)
  val pressSource = rememberOs4PressSource()
  val actionLabel = stringResource(R.string.status_sheet_open, title)
  Card(
    Modifier.fillMaxWidth()
      .testTag("$tag.card")
      .os4ContentSize()
      .glassPress(pressSource)
      .os4Clickable(
        role = Role.Button,
        cornerRadius = CardDefaults.CornerRadius,
        onClickLabel = actionLabel,
      ) {
        if (!mounted) {
          openAfterExit = false
          mounted = true
          showing = true
        }
      },
    insideMargin = PaddingValues(16.dp),
  ) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Os4IconBadge(icon, iconTint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            title,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (!subtitle.isNullOrBlank()) {
            Text(
              subtitle,
              style = MiuixTheme.textStyles.footnote2,
              color = MiuixTheme.colorScheme.onSurfaceSecondary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        headerEnd?.invoke()
        DashboardArrow()
      }
      footer(false)
    }
  }
  if (mounted) Os4DetailsSheet(
    show = showing,
    title = title,
    modifier = Modifier.testTag("$tag.sheet"),
    onDismissRequest = { showing = false },
    onDismissFinished = {
      if (mounted) {
        mounted = false
        val open = openAfterExit
        openAfterExit = false
        if (open) openDestination?.invoke()
      }
    },
  ) {
    GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
      Column(
        Modifier.fillMaxWidth().testTag("$tag.overview"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        overview()
        footer(true)
      }
    }
    GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
      Column(Modifier.fillMaxWidth()) {
        content()
      }
    }
    if (!footnote.isNullOrBlank()) {
      DashboardFootnote(footnote, Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
    }
    if (onOpen != null && openLabel != null) Os4DialogButton(
      text = openLabel,
      modifier = Modifier.fillMaxWidth().testTag("$tag.settings"),
      role = GlassDialogButtonRole.Primary,
      enabled = showing,
      onClick = {
        if (showing && !openAfterExit) {
          openAfterExit = true
          showing = false
        }
      },
    )
  }
}

@Composable
private fun DashboardDetailDivider() {
  HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
}

@Composable
private fun DashboardDetail(
  title: String,
  value: String,
  stacked: Boolean = false,
  pillColor: Color? = null,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    val vertical = stacked || maxWidth < 260.dp || LocalDensity.current.fontScale > 1.3f
    val valueContent: @Composable () -> Unit = {
      if (pillColor != null) MetadataPill(value, pillColor)
      else Text(value, style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
        textAlign = if (vertical) TextAlign.Start else TextAlign.End)
    }
    if (vertical) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
        valueContent()
      }
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically) {
      Text(title, Modifier.weight(0.4f), style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
      Box(Modifier.weight(0.6f), contentAlignment = Alignment.CenterEnd) { valueContent() }
    }
  }
}

@Composable
private fun DashboardPillRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun DashboardStatePill(label: String, value: String, tone: StatusTone?, tag: String) {
  val color = when (tone) {
    StatusTone.READY -> colorResource(if (LocalKeiHomeDarkTheme.current)
      R.color.status_hero_loaded_accent_dark else R.color.status_hero_loaded_accent)
    StatusTone.WARNING -> colorResource(if (LocalKeiHomeDarkTheme.current)
      R.color.status_hero_warning_accent_dark else R.color.status_hero_warning_accent)
    StatusTone.ERROR -> MiuixTheme.colorScheme.error
    null, StatusTone.CHECKING -> MiuixTheme.colorScheme.onSurfaceSecondary
  }
  MetadataPill(stringResource(R.string.dashboard_state_pill, label, value), color, Modifier.testTag(tag))
}

@Composable
private fun DashboardFootnote(text: String, modifier: Modifier = Modifier) {
  Text(text, modifier = modifier, style = MiuixTheme.textStyles.footnote1,
    color = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
private fun DashboardArrow() {
  Os4TrailingChevron()
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
  environmentDetails: List<String> = emptyList(),
  modifier: Modifier = Modifier,
  badges: List<String> = emptyList(),
  badgeContent: (@Composable (Color) -> Unit)? = null,
  actionLabel: String? = null,
  titleBadge: String? = null,
  summaryBadge: String? = null,
) {
  val refreshLabel = stringResource(R.string.status_refresh)
  val dark = LocalKeiHomeDarkTheme.current
  val colors = MiuixTheme.colorScheme
  // Status colors stay semantic even when the app's accent comes from wallpaper.
  // Use the app appearance, which may differ from the system's light/dark mode.
  val accentColor by animateColorAsState(
    targetValue = when (tone) {
      StatusTone.READY -> colorResource(
        if (dark) R.color.status_hero_loaded_accent_dark else R.color.status_hero_loaded_accent,
      )
      StatusTone.WARNING -> colorResource(
        if (dark) R.color.status_hero_warning_accent_dark else R.color.status_hero_warning_accent,
      )
      StatusTone.ERROR -> colors.error
      StatusTone.CHECKING -> colors.onSurfaceSecondary
    },
    animationSpec = Os4Motion.colorSpring(),
    label = "status-hero-accent",
  )
  val backgroundColor by animateColorAsState(
    targetValue = when (tone) {
      StatusTone.READY -> colorResource(if (dark) R.color.status_hero_ready_container_dark else R.color.status_hero_ready_container)
      StatusTone.WARNING -> colorResource(if (dark) R.color.status_hero_warning_container_dark else R.color.status_hero_warning_container)
      StatusTone.ERROR -> colorResource(if (dark) R.color.status_hero_error_container_dark else R.color.status_hero_error_container)
      StatusTone.CHECKING -> colors.surfaceContainer
    },
    animationSpec = Os4Motion.colorSpring(),
    label = "status-hero-bg",
  )
  val contentColor by animateColorAsState(
    targetValue = when (tone) {
      StatusTone.READY -> colorResource(if (dark) R.color.status_hero_ready_content_dark else R.color.status_hero_ready_content)
      StatusTone.WARNING -> colorResource(if (dark) R.color.status_hero_warning_content_dark else R.color.status_hero_warning_content)
      StatusTone.ERROR -> colorResource(if (dark) R.color.status_hero_error_content_dark else R.color.status_hero_error_content)
      StatusTone.CHECKING -> colors.onSurface
    },
    animationSpec = Os4Motion.colorSpring(),
    label = "status-hero-content",
  )
  val hasBadges = badges.isNotEmpty() || badgeContent != null
  val hasLowerContent = environmentDetails.isNotEmpty() || hasBadges || details.isNotEmpty()
  // The 72.dp ornament overhangs the end edge by 12.dp.
  val symbolClearance = 64.dp
  val summaryEndPadding = if (hasLowerContent) symbolClearance - 18.dp else 0.dp

  Card(
    modifier = modifier.fillMaxWidth().os4ContentSize(),
    insideMargin = PaddingValues(0.dp),
    colors = CardDefaults.defaultColors(color = backgroundColor, contentColor = contentColor),
    onClick = onOpen ?: onRefresh,
    showIndication = true,
    pressFeedbackType = PressFeedbackType.Tilt,
  ) {
    // MIUIX owns the clickable inner column; its semantics merge this content label.
    Box(Modifier.fillMaxWidth().clipToBounds().semantics {
      this.onClick(label = actionLabel ?: refreshLabel, action = null)
    }) {
      StatusOverviewCornerSymbol(tone, accentColor)
      Column {
        Box(Modifier.fillMaxWidth().clipToBounds()) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(start = 18.dp, end = if (hasLowerContent) 18.dp else symbolClearance,
                top = 16.dp, bottom = if (hasLowerContent) 10.dp else 16.dp),
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Text(
                  text = title,
                  modifier = Modifier.align(Alignment.CenterVertically),
                  style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.SemiBold),
                  color = contentColor,
                )
                if (titleBadge != null) MetadataPill(
                  titleBadge, contentColor,
                  Modifier.align(Alignment.CenterVertically).testTag("status.overview.version"),
                )
              }
              FlowRow(modifier = Modifier.padding(end = summaryEndPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  text = summary,
                  modifier = Modifier.align(Alignment.CenterVertically),
                  style = MiuixTheme.textStyles.body2,
                  color = contentColor.copy(alpha = 0.84f),
                )
                if (summaryBadge != null) MetadataPill(
                  summaryBadge, contentColor, Modifier.align(Alignment.CenterVertically),
                )
              }
            }
            if (footer != null) {
              Text(
                text = footer,
                modifier = Modifier.padding(top = 6.dp, end = summaryEndPadding),
                style = MiuixTheme.textStyles.footnote1,
                color = contentColor,
              )
            }
          }
        }
        if (environmentDetails.isNotEmpty()) {
          FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp,
              end = symbolClearance, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            environmentDetails.forEach {
              Text(it, style = MiuixTheme.textStyles.footnote2, color = contentColor.copy(alpha = 0.86f))
            }
          }
        }
        if (hasBadges) {
          DashboardPillRow(Modifier.padding(start = 18.dp,
            end = symbolClearance, bottom = 16.dp)) {
            if (badgeContent != null) badgeContent(accentColor)
            else badges.forEach { MetadataPill(it, accentColor) }
          }
        }
        if (details.isNotEmpty()) {
          BoxWithConstraints(
            modifier = Modifier.padding(start = 18.dp, end = symbolClearance, bottom = 16.dp),
          ) {
            val stacked = maxWidth < 280.dp
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              details.forEach { detail ->
                if (stacked) {
                  Column(Modifier.fillMaxWidth()) {
                    Text(detail.label, modifier = Modifier.fillMaxWidth(), style = MiuixTheme.textStyles.body2,
                      color = contentColor.copy(alpha = 0.7f))
                    Text(detail.value, modifier = Modifier.fillMaxWidth(),
                      style = MiuixTheme.textStyles.body2, color = contentColor)
                  }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
  }
}

@Composable
internal fun BoxScope.StatusOverviewCornerSymbol(tone: StatusTone, color: Color) {
  val pulseAlpha = if (tone == StatusTone.CHECKING) {
    val transition = rememberInfiniteTransition(label = "status-checking-pulse")
    transition.animateFloat(
      initialValue = 0.45f,
      targetValue = 0.92f,
      animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
      label = "status-checking-alpha",
    )
  } else null
  // Only the card clips this square ornament; it never participates in content measurement.
  Box(Modifier.matchParentSize()) {
    AnimatedContent(
      targetState = tone,
      modifier = Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 18.dp)
        .graphicsLayer { alpha = pulseAlpha?.value ?: 1f },
      transitionSpec = {
        (fadeIn(tween(220)) + scaleIn(initialScale = 0.86f, animationSpec = Os4Motion.stateSpring())) togetherWith
          (fadeOut(tween(150)) + scaleOut(targetScale = 0.86f, animationSpec = tween(150)))
      },
      label = "status-overview-symbol",
    ) { target ->
      val icon = when (target) {
        StatusTone.READY -> MiuixIcons.Glass.Ok
        StatusTone.WARNING, StatusTone.CHECKING -> MiuixIcons.Glass.Info
        StatusTone.ERROR -> MiuixIcons.Glass.Report
      }
      Icon(
        imageVector = icon,
        modifier = Modifier.requiredSize(72.dp).testTag("status.overview.symbol")
          .then(if (target == StatusTone.READY) Modifier.padding(8.dp)
            .border(3.dp, color, CircleShape).padding(10.dp)
            else Modifier),
        tint = color,
        contentDescription = null,
      )
    }
  }
}
