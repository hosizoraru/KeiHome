package os.kei.hyper.home.ui.main

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.LauncherGlassStyle
import os.kei.hyper.home.data.LauncherLabelMode
import os.kei.hyper.home.status.LauncherRuntimeCapabilities
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DesktopPage(model: HomeViewModel, onOpenSection: (String) -> Unit) {
  Column(Modifier.fillMaxWidth().testTag("desktop.settings"),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    DesktopConnectionCard(model)
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      DesktopSection.entries.filter { it != DesktopSection.OVERVIEW }.forEach { section ->
        val pressSource = rememberOs4PressSource()
        val icon = actionIcon(when (section) {
          DesktopSection.DOCK -> PageActionIcon.DESKTOP
          DesktopSection.APPEARANCE, DesktopSection.TOOLS -> PageActionIcon.SETTINGS
          else -> PageActionIcon.PACKS
        })
        BasicComponent(title = stringResource(section.title), summary = stringResource(section.summary),
          onClick = { onOpenSection(section.route) }, role = Role.Button, interactionSource = pressSource,
          modifier = Modifier.testTag("desktop.open.${section.route}").glassPress(pressSource),
          startAction = { Icon(icon, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary) },
          endActions = { Icon(MiuixIcons.Glass.ChevronForward, null, tint = MiuixTheme.colorScheme.onSurfaceSecondary) })
      }
    }
  }
}

private data class DesktopStatusEntry(
  val feature: DesktopFeature, val title: Int, val value: String,
  val status: DesktopFeatureStatus, val capability: Boolean?,
)

@Composable
internal fun DesktopStatusCard(model: HomeViewModel, section: DesktopSection) {
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.homeScreenRuntime.collectAsStateWithLifecycle()
  val dock by model.dockGlass.state.collectAsStateWithLifecycle()
  val labels by model.launcherLabels.state.collectAsStateWithLifecycle()
  val glass by model.launcherGlass.state.collectAsStateWithLifecycle()
  val effects by model.themeEffects.collectAsStateWithLifecycle()
  val splash by model.splashAppearance.collectAsStateWithLifecycle()
  val splashIcons by model.splashIcons.state.collectAsStateWithLifecycle()
  val refreshes by model.refreshResults.collectAsStateWithLifecycle()
  val now = SystemClock.elapsedRealtime()
  val version = BuildConfig.VERSION_CODE.toLong()
  val scopeChecking = framework.desktopScopeChecking()
  val glassLabels = listOf(R.string.launcher_glass_system, R.string.launcher_glass_light, R.string.launcher_glass_dark)
  val searchChoice = glass.saved?.let { saved -> glass.savedSearch?.let { search ->
    os.kei.hyper.home.data.LauncherGlassChoice(saved, search)
  } }
  val entries = section.features.map { feature ->
    val raw = when (feature) {
      DesktopFeature.DOCK_MATERIAL -> DesktopStatusEntry(feature, R.string.desktop_glass_status_title,
        stringResource(glass.saved?.let { glassLabels[it.ordinal] } ?: R.string.theme_effects_loading),
        desktopGlassStatus(glass, runtime, framework.launcherGlassInScope(), now, version, scopeChecking),
        desktopCapability(runtime, now, version, LauncherRuntimeCapabilities.GLASS))
      DesktopFeature.DOCK_STYLE -> DesktopStatusEntry(feature, R.string.desktop_dock_status_title,
        dock.saved?.let { if (!it.enabled) stringResource(R.string.desktop_feature_off)
          else stringResource(R.string.desktop_dock_on, stringResource(it.cornerStyle.labelResource)) }
          ?: stringResource(R.string.theme_effects_loading),
        desktopDockStatus(dock, runtime, framework.dockGlassInScope(), now, version, scopeChecking),
        desktopCapability(runtime, now, version, LauncherRuntimeCapabilities.DOCK))
      DesktopFeature.SEARCH_MATERIAL -> DesktopStatusEntry(feature, R.string.search_glass_title,
        stringResource(when (glass.savedSearch) {
          os.kei.hyper.home.data.SearchGlassStyle.FOLLOW_DOCK -> R.string.search_glass_follow_dock
          os.kei.hyper.home.data.SearchGlassStyle.SYSTEM -> R.string.launcher_glass_system
          os.kei.hyper.home.data.SearchGlassStyle.LIGHT -> R.string.launcher_glass_light
          os.kei.hyper.home.data.SearchGlassStyle.DARK -> R.string.launcher_glass_dark
          null -> R.string.theme_effects_loading
        }), desktopSearchGlassStatus(glass, runtime, framework.launcherGlassInScope(), now, version, scopeChecking),
        desktopCapability(runtime, now, version, LauncherRuntimeCapabilities.GLASS_PAIR))
      DesktopFeature.LABELS -> DesktopStatusEntry(feature, R.string.desktop_labels_status_title,
        stringResource(when (labels.saved) {
          LauncherLabelMode.NATIVE -> R.string.launcher_labels_native
          LauncherLabelMode.MARQUEE -> R.string.launcher_labels_marquee
          LauncherLabelMode.TWO_LINES -> R.string.launcher_labels_two_lines
          null -> R.string.theme_effects_loading
        }), desktopLabelStatus(labels, runtime, framework.launcherLabelsInScope(), now, version, scopeChecking),
        desktopCapability(runtime, now, version, LauncherRuntimeCapabilities.LABELS))
      DesktopFeature.ICON_EFFECTS -> DesktopStatusEntry(feature, R.string.theme_effects_title,
        stringResource(if (effects.savedChoice == true) R.string.dashboard_on else R.string.dashboard_off),
        desktopStoredStatus(effects, framework.themeEffectsInScope(), scopeChecking), true)
      DesktopFeature.SPLASH_ICONS -> DesktopStatusEntry(feature, R.string.splash_icons_title,
        stringResource(if (splashIcons.savedChoice == true) R.string.dashboard_on else R.string.dashboard_off),
        desktopStoredStatus(splashIcons, framework.splashAppearanceInScope(), scopeChecking), true)
      DesktopFeature.SPLASH -> DesktopStatusEntry(feature, R.string.splash_adapt,
        stringResource(if (splash.savedChoice == true) R.string.dashboard_on else R.string.dashboard_off),
        desktopStoredStatus(splash, framework.splashAppearanceInScope(), scopeChecking), true)
    }
    val choiceKey: Any? = when (feature) {
      DesktopFeature.DOCK_MATERIAL -> glass.pending ?: glass.saved
      DesktopFeature.DOCK_STYLE -> dock.pending ?: dock.saved
      DesktopFeature.SEARCH_MATERIAL -> searchChoice
      DesktopFeature.LABELS -> labels.pending ?: labels.saved
      else -> null
    }
    raw.copy(status = rememberDesktopOperationStatus(
      if (framework.readFailed) DesktopFeatureStatus.READ_FAILED else raw.status, choiceKey, refreshes[5]))
  }
  val statuses = entries.map { it.status }
  val sync = desktopSyncSummary(statuses, entries.map { it.capability })
  val allUnsupported = entries.all { it.capability == false && it.status == DesktopFeatureStatus.UNAVAILABLE }
  val compatibilityUnknown = statuses.any { it == DesktopFeatureStatus.UNKNOWN }
  val tone = desktopAggregateTone(statuses, sync.ready, allUnsupported)
  val storedOnly = section == DesktopSection.ICONS
  StatusOverviewCard(title = stringResource(when {
    tone == StatusTone.ERROR -> R.string.desktop_error_title
    tone == StatusTone.CHECKING -> if (DesktopFeatureStatus.CHECKING in statuses)
      R.string.desktop_status_checking else R.string.theme_effects_loading
    storedOnly && sync.ready -> R.string.desktop_saved_title
    sync.ready -> R.string.desktop_ready_title
    allUnsupported -> R.string.desktop_compatibility_title
    else -> R.string.desktop_pending_title
  }), summary = stringResource(when {
    tone == StatusTone.CHECKING -> R.string.theme_effects_loading
    allUnsupported -> R.string.desktop_no_supported_settings
    compatibilityUnknown -> R.string.desktop_compatibility_pending
    storedOnly -> R.string.desktop_stored_count
    else -> R.string.desktop_synced_count
  }, sync.accepted, sync.supported), details = emptyList(), tone = tone,
    onRefresh = { model.refresh(5, section) }, modifier = Modifier.testTag("desktop.status.${section.route}"),
    badgeContent = {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { entry ->
          val value = if (storedOnly && entry.status == DesktopFeatureStatus.ACCEPTED)
            stringResource(R.string.desktop_stored_value, entry.value) else entry.value
          MetadataPill(desktopStatusBadge(stringResource(entry.title), value, entry.status), desktopPillColor(entry.status),
            Modifier.testTag("desktop.feature.${entry.feature.name.lowercase()}.status"))
        }
      }
    })
}

@Composable
private fun desktopPillColor(status: DesktopFeatureStatus) = when (status) {
  DesktopFeatureStatus.ACCEPTED -> desktopStatusColor(StatusTone.READY)
  DesktopFeatureStatus.ERROR, DesktopFeatureStatus.READ_FAILED, DesktopFeatureStatus.TIMED_OUT -> MiuixTheme.colorScheme.error
  DesktopFeatureStatus.UNAVAILABLE -> MiuixTheme.colorScheme.onSurfaceSecondary
  DesktopFeatureStatus.CHECKING, DesktopFeatureStatus.LOADING -> desktopStatusColor(StatusTone.CHECKING)
  else -> desktopStatusColor(StatusTone.WARNING)
}

@Composable
private fun desktopStatusBadge(title: String, value: String, status: DesktopFeatureStatus,
  unavailableMessage: Int = R.string.desktop_status_unavailable): String {
  val label = if (status == DesktopFeatureStatus.ACCEPTED) value else stringResource(when (status) {
    DesktopFeatureStatus.OUT_OF_SCOPE -> R.string.desktop_status_not_ready
    DesktopFeatureStatus.LOADING -> R.string.theme_effects_loading
    DesktopFeatureStatus.SAVING -> R.string.theme_effects_saving
    DesktopFeatureStatus.ERROR -> R.string.desktop_status_error
    DesktopFeatureStatus.UNAVAILABLE -> unavailableMessage
    DesktopFeatureStatus.UNKNOWN -> R.string.desktop_status_unknown
    DesktopFeatureStatus.CHECKING -> R.string.desktop_status_checking
    DesktopFeatureStatus.READ_FAILED -> R.string.desktop_status_read_failed
    DesktopFeatureStatus.TIMED_OUT -> R.string.desktop_status_timeout
    DesktopFeatureStatus.LAYOUT_UNAVAILABLE -> R.string.desktop_status_layout_unavailable
    DesktopFeatureStatus.PENDING -> R.string.launcher_glass_pending
    DesktopFeatureStatus.ACCEPTED -> R.string.launcher_glass_accepted
  })
  return stringResource(R.string.desktop_status_badge, title, label)
}
