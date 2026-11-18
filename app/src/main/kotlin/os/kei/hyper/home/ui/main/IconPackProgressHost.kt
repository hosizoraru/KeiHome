package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.Os4Dialog
import os.kei.hyper.home.ui.components.Os4DialogButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun IconPackProgressHost(model: HomeViewModel, applyStep: IconPackStep?) {
  val catalog by model.iconPacks.collectAsStateWithLifecycle()
  val apple by model.applePacks.state.collectAsStateWithLifecycle()
  val progress = iconPackProgress(catalog, apple.progress, apple.generatingForApply, applyStep)
  val waiting = progress?.step == IconPackStep.WAITING_FOR_SYSTEM || progress?.step == IconPackStep.SYSTEM_IMPORT ||
    progress?.step == IconPackStep.APPLYING
  var backgrounded by remember(catalog.nativeImporting, progress?.operation) { mutableStateOf(false) }
  var cancelling by remember(progress?.operation, progress?.cancellable) { mutableStateOf(false) }
  if (progress != null && !(waiting && backgrounded)) {
    IconPackProgressDialog(
      if (cancelling) progress.advance(IconPackStep.CANCELLING, progress.percent) else progress,
      onCancel = {
        cancelling = true
        if (catalog.preparing) model.cancelIconPackPreparation() else model.applePacks.cancel()
      },
      onBackground = { backgrounded = true }.takeIf { waiting },
    )
  }
}

@Composable
internal fun IconPackProgressDialog(
  progress: IconPackOperationProgress,
  onCancel: () -> Unit,
  onBackground: (() -> Unit)? = null,
) {
  val title = stringResource(when (progress.operation) {
    IconPackOperation.LOCAL_IMPORT -> R.string.pack_progress_local_title
    IconPackOperation.GENERATE -> R.string.apple_generate
    IconPackOperation.SYSTEM_IMPORT -> R.string.pack_progress_system_title
    IconPackOperation.AUTO_APPLY -> R.string.pack_progress_apply_title
  })
  Scaffold(containerColor = Color.Transparent) {
    Os4Dialog(show = true, title = title, onDismissRequest = {}, centered = true, dismissible = false,
      sampleBackdrop = false,
      modifier = Modifier.testTag("icon-pack.progress")) {
      Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(if (progress.step == IconPackStep.COMPLETE &&
              progress.operation != IconPackOperation.AUTO_APPLY) R.string.pack_progress_local_complete
            else progress.step.label()), Modifier.weight(1f).testTag("icon-pack.progress.step")
            .semantics { liveRegion = LiveRegionMode.Polite }, style = MiuixTheme.textStyles.body1)
          Text(stringResource(R.string.pack_progress_percentage, progress.percent),
            modifier = Modifier.testTag("icon-pack.progress.percent"), style = MiuixTheme.textStyles.subtitle)
        }
        val fraction = progress.measuredPercent / 100f
        LinearProgressIndicator(Modifier.fillMaxWidth().semantics {
          progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        }, progress = fraction)
        if (progress.total > 0 || (progress.inKiB && progress.done > 0)) {
          val count = when {
            !progress.inKiB -> stringResource(R.string.coloros_generation_count, progress.done, progress.total)
            progress.total > 0 -> stringResource(R.string.pack_progress_kib, progress.done, progress.total)
            else -> stringResource(R.string.pack_progress_kib_unknown, progress.done)
          }
          Text(count,
            modifier = Modifier.testTag("icon-pack.progress.count"),
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        progress.item?.takeIf { it.isNotBlank() }?.let {
          Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        if (progress.cancellable) Os4DialogButton(stringResource(R.string.coloros_generation_cancel), onCancel,
          modifier = Modifier.fillMaxWidth().testTag("icon-pack.progress.cancel"))
        if (onBackground != null) Os4DialogButton(stringResource(R.string.pack_progress_background), onBackground,
          modifier = Modifier.fillMaxWidth().testTag("icon-pack.progress.background"))
      }
    }
  }
}

private fun IconPackStep.label(): Int = when (this) {
  IconPackStep.READING_FILE -> R.string.pack_progress_reading_file
  IconPackStep.VALIDATING_FILE -> R.string.pack_progress_validating
  IconPackStep.APPLICATIONS -> R.string.coloros_generation_applications
  IconPackStep.METADATA -> R.string.apple_fetch_metadata
  IconPackStep.ARTWORK -> R.string.pack_progress_artwork
  IconPackStep.APPLE_PACKAGING, IconPackStep.PACKAGING -> R.string.coloros_generation_packaging
  IconPackStep.LIGHT -> R.string.coloros_generation_light
  IconPackStep.DARK -> R.string.coloros_generation_dark
  IconPackStep.PAIRING -> R.string.coloros_generation_pairing
  IconPackStep.ADAPTATIONS -> R.string.coloros_generation_adaptations
  IconPackStep.STAMPING -> R.string.pack_progress_resources
  IconPackStep.MASKING -> R.string.coloros_generation_masking
  IconPackStep.MONET -> R.string.pack_progress_monet
  IconPackStep.MONET_WRITING -> R.string.pack_progress_monet_writing
  IconPackStep.CUSTOM_ICONS -> R.string.pack_progress_custom
  IconPackStep.SYSTEM_IMPORT -> R.string.pack_progress_system_import
  IconPackStep.WAITING_FOR_SYSTEM -> R.string.pack_progress_system_wait
  IconPackStep.APPLYING -> R.string.pack_progress_system_apply
  IconPackStep.FINISHING -> R.string.pack_progress_finishing
  IconPackStep.CANCELLING -> R.string.pack_progress_cancelling
  IconPackStep.GENERATED -> R.string.pack_progress_generated
  IconPackStep.COMPLETE -> R.string.pack_progress_complete
}
