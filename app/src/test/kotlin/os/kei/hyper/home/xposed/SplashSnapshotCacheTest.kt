package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test

class SplashSnapshotCacheTest {
  @Test fun frequentAppsSurviveEntryAndBytePressure() {
    val cache = SplashSnapshotCache<String, Any>(maxEntries = 3, maxBytes = 10)
    val first = Any()
    cache.put("settings", first, 4, Long.MAX_VALUE)
    cache.put("contacts", Any(), 4, Long.MAX_VALUE)
    assertSame(first, cache.get("settings", 0, 0)?.value)
    cache.put("calculator", Any(), 4, Long.MAX_VALUE)
    assertNull(cache.get("contacts", 0, 0))
    assertSame(first, cache.get("settings", 0, 0)?.value)
    assertEquals(8, cache.bytes)
  }

  @Test fun missedIconsCannotGrowWithoutLimit() {
    val cache = SplashSnapshotCache<Int, String>(maxEntries = 2)
    repeat(100) { cache.put(it, null, 0, 1_000) }
    assertNull(cache.get(97, 0, 0))
    assertNotNull(cache.get(98, 0, 0))
    assertNull(cache.get(99, 0, 0)!!.value)
    assertEquals(0, cache.bytes)
  }

  @Test fun missingIconRetriesAfterItsCooldown() {
    val cache = SplashSnapshotCache<String, String>()
    cache.put("missing", null, 0, 30_000)
    assertNotNull(cache.get("missing", 29_999, 0))
    assertNull(cache.get("missing", 30_000, 0))
  }

  @Test fun clockAdvancesAndWallClockCorrectionsInvalidateOnlyDynamicArtwork() {
    val cache = SplashSnapshotCache<String, String>()
    cache.put("clock", "12:00", 1, Long.MAX_VALUE, minute = 720)
    cache.put("settings", "gear", 1, Long.MAX_VALUE)
    assertEquals("12:00", cache.get("clock", 0, 720)?.value)
    assertNull(cache.get("clock", 1, 721))
    assertEquals("gear", cache.get("settings", 1, 721)?.value)
    cache.put("clock", "12:01", 1, Long.MAX_VALUE, minute = 721)
    assertNull(cache.get("clock", 2, 719))
    assertEquals(1, cache.bytes)
  }

  @Test fun replacingAnEntryAndClearingReleaseAllRetainedWeight() {
    val cache = SplashSnapshotCache<String, Any>(maxBytes = 10)
    cache.put("settings", Any(), 8, Long.MAX_VALUE)
    cache.put("settings", Any(), 3, Long.MAX_VALUE)
    assertEquals(3, cache.bytes)
    cache.clear()
    assertEquals(0, cache.bytes)
    assertNull(cache.get("settings", 0, 0))
  }

  @Test fun oversizedArtworkCannotEvictOtherAppsOrExceedBudget() {
    val cache = SplashSnapshotCache<String, String>(maxBytes = 10)
    cache.put("settings", "gear", 3, Long.MAX_VALUE)
    cache.put("oversized", "image", 11, Long.MAX_VALUE)
    assertNull(cache.get("oversized", 0, 0))
    assertEquals("gear", cache.get("settings", 0, 0)?.value)
    assertEquals(3, cache.bytes)
  }

}
