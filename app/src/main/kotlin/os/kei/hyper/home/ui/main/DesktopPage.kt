package os.kei.hyper.home.ui.main

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.LauncherGlassStyle
import os.kei.hyper.home.data.LauncherLabelMode
import top.yukonga.miuix.kmp.basic.SmallTitle

@Composable
internal fun DesktopPage(model: HomeViewModel) {
  Column(Modifier.fillMaxWidth().testTag("desktop.settings"),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    DesktopStatusCard(model)
    SmallTitle(stringResource(R.string.desktop_appearance_section),
      modifier = Modifier.semantics { heading() })
    LauncherGlassSettingsCard(model)
    DockGlassSettingsCard(model)
    LauncherLabelSettingsCard(model)
  }
}

@Composable
private fun DesktopStatusCard(model: HomeViewModel) {
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.homeScreenRuntime.collectAsStateWithLifecycle()
  val dock by model.dockGlass.state.collectAsStateWithLifecycle()
  val labels by model.launcherLabels.state.collectAsStateWithLifecycle()
  val glass by model.launcherGlass.state.collectAsStateWithLifecycle()
  val now = SystemClock.elapsedRealtime()
  val version = BuildConfig.VERSION_CODE.toLong()
  val dockStatus = desktopDockStatus(dock, runtime, framework.dockGlassInScope(), now, version)
  val labelsStatus = desktopLabelStatus(labels, runtime, framework.launcherLabelsInScope(), now, version)
  val glassStatus = desktopGlassStatus(glass, runtime, framework.launcherGlassInScope(), now, version)
  val accepted = listOf(dockStatus, labelsStatus, glassStatus).count { it == DesktopFeatureStatus.ACCEPTED }
  val dockValue = when (val saved = dock.saved) {
    null -> stringResource(R.string.theme_effects_loading)
    else -> if (!saved.enabled) stringResource(R.string.desktop_feature_off) else
      stringResource(R.string.desktop_dock_on, stringResource(saved.cornerStyle.labelResource))
  }
  val labelValue = stringResource(when (labels.saved) {
    LauncherLabelMode.NATIVE -> R.string.launcher_labels_native
    LauncherLabelMode.MARQUEE -> R.string.launcher_labels_marquee
    LauncherLabelMode.TWO_LINES -> R.string.launcher_labels_two_lines
    null -> R.string.theme_effects_loading
  })
  val glassValue = stringResource(when (glass.saved) {
    LauncherGlassStyle.SYSTEM -> R.string.launcher_glass_system
    LauncherGlassStyle.LIGHT -> R.string.launcher_glass_light
    LauncherGlassStyle.DARK -> R.string.launcher_glass_dark
    null -> R.string.theme_effects_loading
  })
  val statuses = listOf(dockStatus, labelsStatus, glassStatus)
  val tone = when {
    statuses.any { it == DesktopFeatureStatus.ERROR } -> StatusTone.ERROR
    accepted == 3 -> StatusTone.READY
    else -> StatusTone.WARNING
  }
  StatusOverviewCard(
    title = stringResource(when (tone) {
      StatusTone.READY -> R.string.desktop_ready_title
      StatusTone.WARNING -> R.string.desktop_pending_title
      StatusTone.ERROR -> R.string.desktop_error_title
    }),
    summary = stringResource(R.string.desktop_synced_count, accepted, 3),
    details = emptyList(), tone = tone, onRefresh = { model.refresh(5) },
    modifier = Modifier.testTag("desktop.status"),
    badges = listOf(
      desktopStatusBadge(stringResource(R.string.desktop_dock_status_title), dockValue, dockStatus),
      desktopStatusBadge(stringResource(R.string.desktop_labels_status_title), labelValue, labelsStatus),
      desktopStatusBadge(stringResource(R.string.desktop_glass_status_title), glassValue, glassStatus),
    ),
  )
}

@Composable
private fun desktopStatusBadge(title: String, value: String, status: DesktopFeatureStatus): String {
  val label = if (status == DesktopFeatureStatus.ACCEPTED) value else stringResource(when (status) {
    DesktopFeatureStatus.OUT_OF_SCOPE -> R.string.desktop_status_not_ready
    DesktopFeatureStatus.LOADING -> R.string.theme_effects_loading
    DesktopFeatureStatus.SAVING -> R.string.theme_effects_saving
    DesktopFeatureStatus.ERROR -> R.string.desktop_status_error
    DesktopFeatureStatus.UNAVAILABLE -> R.string.desktop_status_unavailable
    DesktopFeatureStatus.PENDING -> R.string.launcher_glass_pending
    DesktopFeatureStatus.ACCEPTED -> R.string.launcher_glass_accepted
  })
  return stringResource(R.string.desktop_status_badge, title, label)
}
