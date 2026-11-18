package os.kei.hyper.home.ui.main

import os.kei.hyper.home.iconpack.ColorOsGenerationProgress
import os.kei.hyper.home.iconpack.ColorOsGenerationStage
import os.kei.hyper.home.iconpack.apple.AppleGenerationProgress
import os.kei.hyper.home.iconpack.apple.AppleGenerationStage

internal enum class IconPackOperation { LOCAL_IMPORT, GENERATE, SYSTEM_IMPORT, AUTO_APPLY }

internal enum class IconPackStep {
  READING_FILE, VALIDATING_FILE, APPLICATIONS, METADATA, ARTWORK, APPLE_PACKAGING, LIGHT, DARK, PAIRING,
  ADAPTATIONS, STAMPING, MASKING, MONET, MONET_WRITING, CUSTOM_ICONS, PACKAGING, SYSTEM_IMPORT, APPLYING,
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
  val measuredPercent: Float = percent.toFloat(),
) {
  fun advance(step: IconPackStep, percent: Int, cancellable: Boolean = false) =
    IconPackOperationProgress(operation, step, maxOf(this.percent, percent), cancellable = cancellable,
      measuredPercent = maxOf(measuredPercent, percent.toFloat()))
}

internal class IconPackGenerationProgressPlan(
  nativeArchive: Boolean = false,
  monet: Boolean = false,
) {
  private val weights = listOf(
    2, if (nativeArchive) 16 else 30, if (nativeArchive) 0 else 26,
    if (nativeArchive) 0 else 12, 3, 5, 7,
    if (monet) 48 else 0, if (monet) 10 else 0, 3, 9,
  )
  private val offsets = weights.runningFold(0, Int::plus)

  fun percent(progress: ColorOsGenerationProgress, afterApple: Boolean): Float {
    val index = progress.stage.ordinal
    val fraction = if (progress.total > 0) {
      progress.done.coerceIn(0, progress.total).toDouble() / progress.total
    } else 0.0
    val completed = offsets[index] + weights[index] * fraction
    val start = if (afterApple) 40 else 0
    return (start + completed * (91 - start) / offsets.last()).toFloat()
  }
}

internal fun ColorOsGenerationProgress.operationProgress(
  previous: IconPackOperationProgress,
  afterAppleGeneration: Boolean,
  plan: IconPackGenerationProgressPlan = IconPackGenerationProgressPlan(),
): IconPackOperationProgress {
  val step = when (stage) {
    ColorOsGenerationStage.APPLICATIONS -> IconPackStep.APPLICATIONS
    ColorOsGenerationStage.LIGHT -> IconPackStep.LIGHT
    ColorOsGenerationStage.DARK -> IconPackStep.DARK
    ColorOsGenerationStage.PAIRING -> IconPackStep.PAIRING
    ColorOsGenerationStage.ADAPTATIONS -> IconPackStep.ADAPTATIONS
    ColorOsGenerationStage.STAMPING -> IconPackStep.STAMPING
    ColorOsGenerationStage.MASKING -> IconPackStep.MASKING
    ColorOsGenerationStage.MONET -> IconPackStep.MONET
    ColorOsGenerationStage.MONET_WRITING -> IconPackStep.MONET_WRITING
    ColorOsGenerationStage.CUSTOM_ICONS -> IconPackStep.CUSTOM_ICONS
    ColorOsGenerationStage.PACKAGING -> IconPackStep.PACKAGING
  }
  val measured = maxOf(previous.measuredPercent, plan.percent(this, afterAppleGeneration))
  return IconPackOperationProgress(previous.operation, step, measured.toInt(),
    done.coerceIn(0, total.coerceAtLeast(0)), total, packageName, cancellable = true, inKiB = inKiB,
    measuredPercent = measured)
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
