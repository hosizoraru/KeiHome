package os.kei.hyper.home.ui.main

import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.iconpack.perfect.PerfectIconStyles
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.perfect.PerfectIconProgress
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PerfectDownloadStatusCard(state: PerfectPackState) {
  if (!state.refreshing) return

  var visible by remember(state.syncStartedAtElapsedMillis) { mutableStateOf(false) }
  val progress = state.progress
  val stage = when {
    progress == null -> R.string.perfect_transfer_preparing
    progress.label == "source.check" -> R.string.perfect_transfer_checking
    progress.label == "source.tree" -> R.string.perfect_transfer_tree
    progress.label == "source.verify" -> R.string.perfect_transfer_verifying
    progress.label == "source.download" || progress.label.startsWith("source.git:") ->
      R.string.perfect_transfer_receiving
    else -> R.string.perfect_transfer_processing
  }
  val count = progress?.takeIf {
    (it.label.startsWith("source.git:") || it.label == "source.verify") &&
      it.total > 0 && it.done in 0..it.total
  }
  val byteSample = progress?.transferBytes()
  val byteTotal = progress?.takeIf {
    it.label == "source.download" && byteSample != null &&
      it.total in 1 until Int.MAX_VALUE && byteSample <= it.total.toLong()
  }?.total
  // Git task counts are objects/work units; only source.download counts bytes.
  val fraction = when {
    count != null -> count.done.toFloat() / count.total
    byteTotal != null && byteSample != null -> byteSample.toFloat() / byteTotal
    else -> null
  }
  val taskProgress = stringResource(R.string.perfect_transfer_task_progress)
  val progressSemantics = if (fraction == null) Modifier.progressSemantics()
    else Modifier.progressSemantics(fraction)
  val color = MiuixTheme.colorScheme.onSurfaceSecondary

  Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
      .testTag("perfect.transfer")
      .onVisibilityChanged(minFractionVisible = 0.01f) { visible = it },
    insideMargin = PaddingValues(16.dp),
  ) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.perfect_transfer_title),
        style = MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.SemiBold))
      FlowRow(Modifier.fillMaxWidth().testTag("perfect.transfer.styles"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val styles = PerfectIconStyles.ordered(state.syncingStyles)
        if (styles.isEmpty()) {
          MetadataPill(stringResource(R.string.perfect_transfer_selected_styles), color)
        } else {
          styles.forEach { style ->
            val name = if (style == "default") stringResource(R.string.perfect_icons_style_default) else style
            MetadataPill(stringResource(R.string.perfect_transfer_style, name), color,
              Modifier.testTag("perfect.transfer.style.$style"))
          }
        }
      }
      Text(stringResource(stage), modifier = Modifier.testTag("perfect.transfer.stage")
        .semantics { liveRegion = LiveRegionMode.Polite },
        style = MiuixTheme.textStyles.body2)
      LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().testTag("perfect.transfer.progress")
          .then(progressSemantics).semantics { contentDescription = taskProgress },
        progress = fraction,
      )
      if (count != null) {
        Text(stringResource(R.string.perfect_transfer_count, count.done, count.total),
          modifier = Modifier.testTag("perfect.transfer.count"),
          style = MiuixTheme.textStyles.footnote1, color = color)
      }
      progress?.label?.takeIf { it.isNotBlank() && !it.startsWith("source.") }?.let { task ->
        Text(stringResource(R.string.perfect_transfer_task, task),
          modifier = Modifier.fillMaxWidth().testTag("perfect.transfer.task"),
          style = MiuixTheme.textStyles.footnote1, color = color)
      }
      PerfectTransferStatistics(progress, state.syncStartedAtElapsedMillis, visible)
    }
  }
}

@Composable
private fun PerfectTransferStatistics(
  progress: PerfectIconProgress?,
  startedAt: Long,
  visible: Boolean,
) {
  val context = LocalContext.current
  val color = MiuixTheme.colorScheme.onSurfaceSecondary
  val sample = progress?.transferBytes()
  var lastBytes by remember(startedAt) { mutableStateOf<Long?>(null) }
  var monotonicBytes by remember(startedAt) { mutableStateOf(true) }
  val regressed = sample != null && lastBytes?.let { sample < it } == true
  // Keep the last observed byte count during local verification, without any I/O.
  SideEffect {
    if (sample != null) {
      if (regressed) monotonicBytes = false
      lastBytes = sample
    }
  }
  val received = sample ?: lastBytes
  val elapsed = perfectTransferElapsed(startedAt, visible)
  FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)) {
    received?.let { bytes ->
      MetadataPill(stringResource(R.string.perfect_transfer_received,
        Formatter.formatFileSize(context, bytes)), color, Modifier.testTag("perfect.transfer.received"))
    }
    elapsed?.let { millis ->
      MetadataPill(stringResource(R.string.perfect_transfer_elapsed,
        DateUtils.formatElapsedTime(millis / 1000)), color, Modifier.testTag("perfect.transfer.elapsed"))
      // This is the whole sync's average, including setup, never an instantaneous rate.
      if (sample != null && sample > 0 && monotonicBytes && !regressed && millis >= 1000) {
        val average = (sample.toDouble() / millis * 1000).toLong()
        MetadataPill(stringResource(R.string.perfect_transfer_average,
          Formatter.formatFileSize(context, average)), color, Modifier.testTag("perfect.transfer.speed"))
      }
    }
  }
}

@Composable
private fun perfectTransferElapsed(startedAt: Long, visible: Boolean): Long? {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var now by remember(startedAt) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
  LaunchedEffect(lifecycle, startedAt, visible) {
    if (!visible || startedAt <= 0) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        val current = SystemClock.elapsedRealtime()
        // Visibility or lifecycle changes must not cause a faster-than-1-Hz clock.
        if (current - now >= 1000) now = current
        delay((1000 - (current - now)).coerceIn(1, 1000))
      }
    }
  }
  return if (startedAt > 0 && now >= startedAt) now - startedAt else null
}

private fun PerfectIconProgress.transferBytes(): Long? = when {
  label.startsWith("source.git:") -> label.removePrefix("source.git:").toLongOrNull()?.takeIf { it >= 0 }
  // Int.MAX_VALUE is a saturated counter, not an exact transfer size.
  label == "source.download" && done in 0 until Int.MAX_VALUE -> done.toLong()
  else -> null
}
