package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.ColorOsGenerationProgress
import os.kei.hyper.home.iconpack.ColorOsGenerationStage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun packGenerationLabel(progress: ColorOsGenerationProgress): String {
  val stage = stringResource(when (progress.stage) {
    ColorOsGenerationStage.APPLICATIONS -> R.string.coloros_generation_applications
    ColorOsGenerationStage.LIGHT -> R.string.coloros_generation_light
    ColorOsGenerationStage.DARK -> R.string.coloros_generation_dark
    ColorOsGenerationStage.PAIRING -> R.string.coloros_generation_pairing
    ColorOsGenerationStage.ADAPTATIONS -> R.string.coloros_generation_adaptations
    ColorOsGenerationStage.PACKAGING -> R.string.coloros_generation_packaging
    ColorOsGenerationStage.MASKING -> R.string.coloros_generation_masking
  })
  return if (progress.total > 0) "$stage (${stringResource(
    R.string.coloros_generation_count, progress.done, progress.total)})" else stage
}

@Composable
internal fun PackGenerationStatus(progress: ColorOsGenerationProgress, onCancel: () -> Unit) {
  Card(Modifier.fillMaxWidth().testTag("packs.generation.progress"), insideMargin = PaddingValues(14.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(packGenerationLabel(progress), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
      LinearProgressIndicator(Modifier.fillMaxWidth(), progress = progress.total.takeIf { it > 0 }?.let {
        (progress.done.toFloat() / it).coerceIn(0f, 1f)
      })
      progress.packageName?.let { Text(it, maxLines = 1, style = MiuixTheme.textStyles.footnote1) }
      TextButton(stringResource(R.string.coloros_generation_cancel), onClick = onCancel,
        modifier = Modifier.testTag("packs.generation.cancel"))
    }
  }
}
