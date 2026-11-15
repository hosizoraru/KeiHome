package os.kei.hyper.home.iconpack

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ColorOsGenerationProgressTest {
  @Test fun reportsStageAndKnownTotalImmediately() {
    val values = mutableListOf<ColorOsGenerationProgress>()
    val reporter = ColorOsProgressReporter(values::add) { 0L }
    reporter.update(ColorOsGenerationProgress(ColorOsGenerationStage.APPLICATIONS))
    reporter.update(ColorOsGenerationProgress(ColorOsGenerationStage.LIGHT))
    reporter.update(ColorOsGenerationProgress(ColorOsGenerationStage.LIGHT, total = 4000))
    assertEquals(3, values.size)
    assertEquals(4000, values.last().total)
  }

  @Test fun throttlesLargeArchiveWithoutDroppingCompletionOrNextStage() {
    var clock = 0L
    val values = mutableListOf<ColorOsGenerationProgress>()
    val reporter = ColorOsProgressReporter(values::add) { clock }
    for (done in 0..40_000) {
      clock += 100_000L
      reporter.update(ColorOsGenerationProgress(ColorOsGenerationStage.MASKING, done, 40_000))
    }
    assertTrue(values.size in 2..42)
    assertEquals(40_000, values.last().done)
    reporter.update(ColorOsGenerationProgress(ColorOsGenerationStage.PACKAGING))
    assertEquals(ColorOsGenerationStage.PACKAGING, values.last().stage)
  }

  @Test fun doesNotRepeatIdenticalProgress() {
    var count = 0
    val reporter = ColorOsProgressReporter({ count++ }) { 0L }
    repeat(100) { reporter.update(ColorOsGenerationProgress(ColorOsGenerationStage.PACKAGING)) }
    assertEquals(1, count)
  }

  @Test fun cancellationFromOwnerIsNotSwallowed() {
    val cancelled = CancellationException("owner cancelled")
    val reporter = ColorOsProgressReporter({ throw cancelled })
    try {
      reporter.update(ColorOsGenerationProgress(ColorOsGenerationStage.LIGHT))
      fail("Cancellation must escape into conversion")
    } catch (actual: CancellationException) { assertSame(cancelled, actual) }
  }

  @Test fun cacheReusesAnAbsentActivity() {
    val cache = ColorOsGenerationCache<String, String?>(2)
    var loads = 0
    repeat(5) { assertNull(cache.get("removed.activity") { loads++; null }) }
    assertEquals(1, loads)
  }

  @Test fun cacheIsBoundedAndKeepsRecentlyUsedSource() {
    val cache = ColorOsGenerationCache<String, Int>(2)
    cache.get("a") { 1 }
    cache.get("b") { 2 }
    assertEquals(1, cache.get("a") { error("Already loaded") })
    cache.get("c") { 3 }
    assertEquals(1, cache.get("a") { error("Recently used source evicted") })
    assertEquals(20, cache.get("b") { 20 })
  }

  @Test fun cacheDoesNotRetainCancelledLookupOrCrossGenerationState() {
    val cache = ColorOsGenerationCache<String, Int>(2)
    try {
      cache.get("apk") { throw CancellationException("stop lookup") }
      fail("Cancellation must escape")
    } catch (_: CancellationException) { }
    assertEquals(1, cache.get("apk") { 1 })
    assertEquals(2, ColorOsGenerationCache<String, Int>(2).get("apk") { 2 })
  }
}
