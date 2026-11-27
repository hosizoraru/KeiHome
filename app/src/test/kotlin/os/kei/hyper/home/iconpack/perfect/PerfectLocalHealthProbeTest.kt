package os.kei.hyper.home.iconpack.perfect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PerfectLocalHealthProbeTest {
  @Test fun unchangedStampSkipsProbeAndEveryIdentityChangeInvalidatesSingleEntry() = runBlocking {
    val cache = PerfectLocalHealthProbe()
    val stamp = PerfectLocalProbeStamp(true, 10, 20, "source", true, 30, 40, null, "source", 50)
    var probes = 0
    suspend fun read(key: PerfectLocalProbeStamp, result: Boolean = true) =
      cache.healthy(key) { probes++; result }
    repeat(10) { assertTrue(read(stamp)) }
    assertEquals(1, probes)
    val changed = listOf(
      stamp.copy(pointerExists = false), stamp.copy(pointerLength = 11),
      stamp.copy(pointerModified = 21), stamp.copy(sourceHash = "new"),
      stamp.copy(archiveExists = false), stamp.copy(archiveLength = 31),
      stamp.copy(archiveModified = 41), stamp.copy(corruptSourceHash = "source"),
      stamp.copy(knownSourceHash = "new"), stamp.copy(sourceDirectoryModified = 51),
      stamp.copy(gitStamp = "pack-v2"),
    )
    changed.forEach { key ->
      val before = probes
      assertFalse(read(key, false))
      assertFalse(read(key))
      assertEquals(before + 1, probes)
    }
    // Only the most recent stamp is retained.
    assertTrue(read(stamp))
    assertEquals(changed.size + 2, probes)
    cache.invalidate()
    assertTrue(read(stamp))
    assertEquals(changed.size + 3, probes)
  }

  @Test fun cancelledProbeIsNotCached() = runBlocking {
    val cache = PerfectLocalHealthProbe()
    val stamp = PerfectLocalProbeStamp(true, 10, 20, "source", true, 30, 40, null, "source", 50)
    try {
      cache.healthy(stamp) { throw CancellationException("cancel") }
      fail("Expected cancellation")
    } catch (_: CancellationException) { }
    var probed = false
    assertTrue(cache.healthy(stamp) { probed = true; true })
    assertTrue(probed)
  }
}
