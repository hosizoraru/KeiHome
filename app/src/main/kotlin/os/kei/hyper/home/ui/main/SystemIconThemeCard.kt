package os.kei.hyper.home.ui.main

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.KeiHomePackVersion
import os.kei.hyper.home.status.RuntimeStatusUiState
import os.kei.hyper.home.status.SystemIconThemeReport

@Composable
internal fun SystemIconThemeCard(
  runtime: RuntimeStatusUiState,
  badges: List<String> = emptyList(),
  activePack: ActiveIconPackSummary = ActiveIconPackSummary(),
  onRefresh: () -> Unit,
) {
  val theme = runtime.report?.takeIf { it.isFresh(SystemClock.elapsedRealtime()) &&
    !runtime.readFailed && !runtime.timedOut }?.iconTheme
  val available = theme != null && theme.source != SystemIconThemeReport.Source.UNAVAILABLE
  val name = theme?.title ?: stringResource(when (theme?.source) {
    SystemIconThemeReport.Source.DEFAULT -> R.string.icon_pack_system_default
    SystemIconThemeReport.Source.THEME -> R.string.icon_pack_system_theme
    else -> R.string.status_unknown
  })
  val footer = if (theme?.complete == true) {
    stringResource(R.string.icon_pack_inventory, theme.resourceCount, theme.nightResourceCount)
  } else stringResource(if (available) R.string.icon_pack_inventory_incomplete else R.string.icon_pack_waiting_system)
  val sourceLabel = activePackSourceLabel(activePack.sourceSection, theme?.source)
  val resolvedVersion = activePack.version
    ?: theme?.version?.trim()?.takeUnless(KeiHomePackVersion::isPlaceholder)
  val appliedAt = activePack.appliedAtMillis ?: theme?.appliedAtMillis
  val appliedBadge = appliedAt?.let {
    stringResource(R.string.pack_applied_ago, dashboardRelativeTime(dashboardAgeMinutes(it)))
  }
  StatusOverviewCard(
    title = stringResource(R.string.icon_pack_active),
    summary = name,
    footer = footer,
    tone = if (theme?.complete == true && available) StatusTone.READY else StatusTone.WARNING,
    details = emptyList(),
    onRefresh = onRefresh,
    titleBadge = sourceLabel,
    summaryBadge = resolvedVersion?.let { stringResource(R.string.icon_pack_version_label, it) },
    badges = badges + listOfNotNull(appliedBadge),
  )
}
