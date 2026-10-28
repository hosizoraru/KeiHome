package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private enum class StatusTone {
  READY,
  WARNING,
  ERROR,
}

private data class StatusDetail(
  val label: String,
  val value: String,
)

@Composable
internal fun StatusContent(
  refreshGeneration: Int,
  onRefresh: () -> Unit,
) {
  val service by FrameworkConnection.service.collectAsStateWithLifecycle()
  var snapshot by remember(service) { mutableStateOf<FrameworkSnapshot?>(null) }
  var failed by remember(service) { mutableStateOf(false) }
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
  val targetResource = when (snapshot?.target?.state) {
    HookedTarget.State.UP_TO_DATE -> R.string.status_target_current
    HookedTarget.State.STALE -> R.string.status_target_stale
    HookedTarget.State.RELOADING -> R.string.status_target_reloading
    HookedTarget.State.FAILED -> R.string.status_target_failed
    null -> if (snapshot != null) R.string.status_target_missing else R.string.status_unknown
  }
  val target = stringResource(targetResource)
  val tone = when {
    service == null || failed || snapshot?.systemInScope == false ||
      snapshot?.target?.state == HookedTarget.State.FAILED -> StatusTone.ERROR
    snapshot?.systemInScope == true &&
      snapshot?.target?.state == HookedTarget.State.UP_TO_DATE -> StatusTone.READY
    else -> StatusTone.WARNING
  }
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
    StatusDetail(stringResource(R.string.status_target), target),
  )

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SmallTitle(text = stringResource(R.string.settings_module_status))
    StatusOverviewCard(
      title = stringResource(titleResource),
      summary = stringResource(summaryResource),
      details = details,
      tone = tone,
      onRefresh = onRefresh,
    )
    SmallTitle(text = stringResource(R.string.status_runtime_title))
    Card(
      modifier = Modifier.fillMaxWidth(),
      insideMargin = PaddingValues(0.dp),
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        RuntimeFeature(
          icon = MiuixIcons.Refresh,
          title = stringResource(R.string.status_hook),
          summary = stringResource(R.string.status_hook_live),
        )
        RuntimeFeature(
          icon = MiuixIcons.Info,
          title = stringResource(R.string.status_recent),
          summary = stringResource(R.string.status_recent_live),
        )
        RuntimeFeature(
          icon = MiuixIcons.Theme,
          title = stringResource(R.string.settings_monet_title),
          summary = stringResource(R.string.settings_monet_value),
        )
      }
    }
  }
}

@Composable
private fun StatusOverviewCard(
  title: String,
  summary: String,
  details: List<StatusDetail>,
  tone: StatusTone,
  onRefresh: () -> Unit,
) {
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
    StatusTone.WARNING -> MiuixTheme.colorScheme.tertiaryContainer
    StatusTone.ERROR -> MiuixTheme.colorScheme.error
  }
  val icon = when (tone) {
    StatusTone.READY -> MiuixIcons.Ok
    StatusTone.WARNING -> MiuixIcons.Info
    StatusTone.ERROR -> MiuixIcons.Report
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 176.dp),
    insideMargin = PaddingValues(0.dp),
    colors = CardDefaults.defaultColors(color = backgroundColor),
    onClick = onRefresh,
    showIndication = true,
    pressFeedbackType = PressFeedbackType.Tilt,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 176.dp),
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .offset(24.dp, 28.dp),
        contentAlignment = Alignment.BottomEnd,
      ) {
        Icon(
          imageVector = icon,
          modifier = Modifier.size(112.dp),
          tint = accentColor.copy(alpha = 0.28f),
          contentDescription = null,
        )
      }
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(start = 16.dp, top = 14.dp, end = 64.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = title,
            style = MiuixTheme.textStyles.title2,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = summary,
            style = MiuixTheme.textStyles.body2,
            color = contentColor.copy(alpha = 0.84f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          details.forEach { detail ->
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = detail.label,
                style = MiuixTheme.textStyles.footnote1,
                color = contentColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = detail.value,
                style = MiuixTheme.textStyles.body2,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun RuntimeFeature(
  icon: ImageVector,
  title: String,
  summary: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      imageVector = icon,
      modifier = Modifier.size(24.dp),
      tint = MiuixTheme.colorScheme.primary,
      contentDescription = null,
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MiuixTheme.textStyles.body1)
      Text(
        text = summary,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
      )
    }
  }
}
