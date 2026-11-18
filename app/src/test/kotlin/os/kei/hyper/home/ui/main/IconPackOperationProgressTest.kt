package os.kei.hyper.home.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.ColorOsGenerationProgress
import os.kei.hyper.home.iconpack.ColorOsGenerationStage
import os.kei.hyper.home.iconpack.apple.AppleGenerationProgress
import os.kei.hyper.home.iconpack.apple.AppleGenerationStage
import os.kei.hyper.home.status.NativeIconImportReport

class IconPackOperationProgressTest {
  @Test fun nativeStagesUseMeasuredCountsWithinOrderedMilestones() {
    var progress = IconPackOperationProgress(IconPackOperation.SYSTEM_IMPORT, IconPackStep.APPLICATIONS, 0)
    for (stage in ColorOsGenerationStage.entries) {
      val start = ColorOsGenerationProgress(stage, 0, 10).operationProgress(progress, false)
      val middle = ColorOsGenerationProgress(stage, 5, 10, "example.app").operationProgress(start, false)
      val end = ColorOsGenerationProgress(stage, 10, 10).operationProgress(middle, false)
      assertTrue(start.percent >= progress.percent)
      assertTrue(middle.percent in start.percent..end.percent)
      assertEquals("example.app", middle.item)
      assertEquals(5, middle.done)
      assertEquals(10, middle.total)
      progress = end
    }
    assertEquals(91, progress.percent)
  }

  @Test fun appleGenerationReservesRoomForSystemPreparationOnlyWhenNeeded() {
    val published = AppleGenerationProgress(AppleGenerationStage.PUBLISHED)
    val standalone = published.operationProgress(IconPackOperation.GENERATE)
    assertEquals(100, standalone.percent)
    assertFalse(standalone.cancellable)
    val intermediate = published.operationProgress(IconPackOperation.SYSTEM_IMPORT)
    assertEquals(39, intermediate.percent)
    val preparing = ColorOsGenerationProgress(ColorOsGenerationStage.APPLICATIONS)
      .operationProgress(intermediate, true)
    assertEquals(40, preparing.percent)
    val packaged = ColorOsGenerationProgress(ColorOsGenerationStage.PACKAGING, 1, 1)
      .operationProgress(preparing, true)
    assertEquals(91, packaged.percent)
  }

  @Test fun systemReceiptControlsWaitingAndCompletion() {
    val original = IconPacksUiState(nativeImporting = "source")
    val stale = original.copy(nativeResult = NativeIconImportReport("token", 0, "other", "UNCERTAIN"))
    assertEquals(IconPackStep.SYSTEM_IMPORT, iconPackProgress(stale, null, false, null)?.step)
    val waiting = original.copy(nativeResult = NativeIconImportReport("token", 0, "source", "UNCERTAIN"))
    assertEquals(IconPackStep.WAITING_FOR_SYSTEM, iconPackProgress(waiting, null, false, null)?.step)
    assertEquals(94, iconPackProgress(waiting, null, false, null)?.percent)
    assertEquals(97, iconPackProgress(IconPacksUiState(), null, false, IconPackStep.APPLYING)?.percent)
    assertEquals(100, iconPackProgress(IconPacksUiState(), null, false, IconPackStep.COMPLETE)?.percent)
    assertNull(iconPackProgress(IconPacksUiState(), null, false, null))
  }
}
