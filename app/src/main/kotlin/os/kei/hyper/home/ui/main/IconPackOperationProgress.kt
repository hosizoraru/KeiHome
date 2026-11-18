package os.kei.hyper.home.ui.main

import os.kei.hyper.home.iconpack.ColorOsGenerationProgress
import os.kei.hyper.home.iconpack.ColorOsGenerationStage
import os.kei.hyper.home.iconpack.apple.AppleGenerationProgress
import os.kei.hyper.home.iconpack.apple.AppleGenerationStage

internal enum class IconPackOperation { LOCAL_IMPORT, GENERATE, SYSTEM_IMPORT, AUTO_APPLY }

internal enum class IconPackStep {
  READING_FILE, VALIDATING_FILE, APPLICATIONS, METADATA, ARTWORK, APPLE_PACKAGING, LIGHT, DARK, PAIRING,
  ADAPTATIONS, STAMPING, MASKING, MONET, CUSTOM_ICONS, PACKAGING, SYSTEM_IMPORT, APPLYING,
  WAITING_FOR_SYSTEM, FINISHING, CANCELLING, GENERATED, COMPLETE,
}

internal fun iconPackProgress(
  catalog: IconPacksUiState,
  apple: AppleGenerationProgress?,
  generatingForApply: Boolean,
  applyStep: IconPackStep?,
): IconPackOperationProgress? {
  val system = IconPackOperation.SYSTEM_IMPORT
  return when {
    applyStep != null -> IconPackOperationProgress(IconPackOperation.AUTO_APPLY, applyStep,
      if (applyStep == IconPackStep.COMPLETE) 100 else 97)
    catalog.generationProgress?.step == IconPackStep.COMPLETE -> catalog.generationProgress
    catalog.preparing -> catalog.generationProgress
      ?: IconPackOperationProgress(system, IconPackStep.APPLICATIONS, 0, cancellable = true)
    catalog.nativeImporting != null -> IconPackOperationProgress(system,
      if (catalog.nativeResult?.sourceId == catalog.nativeImporting && catalog.nativeResult.status == "UNCERTAIN")
        IconPackStep.WAITING_FOR_SYSTEM else IconPackStep.SYSTEM_IMPORT, 94)
    catalog.generationProgress != null -> catalog.generationProgress
    apple != null -> apple.operationProgress(if (generatingForApply) system else IconPackOperation.GENERATE)
    catalog.localImportProgress != null -> catalog.localImportProgress
    else -> null
  }
}

internal data class IconPackOperationProgress(
  val operation: IconPackOperation,
  val step: IconPackStep,
  val percent: Int,
  val done: Int = 0,
  val total: Int = 0,
  val item: String? = null,
  val cancellable: Boolean = false,
  val inKiB: Boolean = false,
) {
  fun advance(step: IconPackStep, percent: Int, cancellable: Boolean = false) =
    IconPackOperationProgress(operation, step, maxOf(this.percent, percent), cancellable = cancellable)
}

internal fun ColorOsGenerationProgress.operationProgress(
  previous: IconPackOperationProgress,
  afterAppleGeneration: Boolean,
): IconPackOperationProgress {
  val (step, window) = when (stage) {
    ColorOsGenerationStage.APPLICATIONS -> IconPackStep.APPLICATIONS to 0..4
    ColorOsGenerationStage.LIGHT -> IconPackStep.LIGHT to 5..32
    ColorOsGenerationStage.DARK -> IconPackStep.DARK to 33..55
    ColorOsGenerationStage.PAIRING -> IconPackStep.PAIRING to 56..63
    ColorOsGenerationStage.ADAPTATIONS -> IconPackStep.ADAPTATIONS to 64..69
    ColorOsGenerationStage.STAMPING -> IconPackStep.STAMPING to 70..74
    ColorOsGenerationStage.MASKING -> IconPackStep.MASKING to 75..79
    ColorOsGenerationStage.MONET -> IconPackStep.MONET to 80..84
    ColorOsGenerationStage.CUSTOM_ICONS -> IconPackStep.CUSTOM_ICONS to 85..88
    ColorOsGenerationStage.PACKAGING -> IconPackStep.PACKAGING to 89..91
  }
  val nativePercent = window.progress(done, total)
  val percent = if (afterAppleGeneration) 40 + nativePercent * 51 / 91 else nativePercent
  return previous.advance(step, percent, cancellable = true)
    .copy(done = done.coerceIn(0, total.coerceAtLeast(0)), total = total, item = packageName, inKiB = inKiB)
}

internal fun AppleGenerationProgress.operationProgress(operation: IconPackOperation): IconPackOperationProgress {
  val (step, window) = when (stage) {
    AppleGenerationStage.APPLICATIONS -> IconPackStep.APPLICATIONS to 0..4
    AppleGenerationStage.METADATA -> IconPackStep.METADATA to 5..24
    AppleGenerationStage.ARTWORK -> IconPackStep.ARTWORK to 25..87
    AppleGenerationStage.PACKAGING -> IconPackStep.APPLE_PACKAGING to 88..95
    AppleGenerationStage.PUBLISHED -> if (operation == IconPackOperation.GENERATE)
      IconPackStep.GENERATED to 100..100 else IconPackStep.FINISHING to 98..98
  }
  val percent = window.progress(done, total)
  return IconPackOperationProgress(operation, step,
    if (operation == IconPackOperation.GENERATE) percent else percent * 39 / 98,
    done.coerceIn(0, total.coerceAtLeast(0)), total, label,
    cancellable = stage != AppleGenerationStage.PUBLISHED)
}

private fun IntRange.progress(done: Int, total: Int): Int =
  if (total <= 0) first else first + (done.coerceIn(0, total).toLong() * (last - first) / total).toInt()
