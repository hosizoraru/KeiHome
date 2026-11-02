package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test

class IconMaskCacheTest {
  private val usable = LauncherMaskStatus.USABLE
  private val unusable = LauncherMaskStatus.UNUSABLE
  private val unknown = LauncherMaskStatus.UNKNOWN

  @Test fun replacementReusesUnchangedPackagesAndProbesOnlyNewSources() {
    val cache = IconMaskCache<String, String>()
    cache.record("dark", mapOf("first" to "v1", "second" to "v1"), mapOf("first" to usable, "second" to unusable), 1)
    assertEquals(mapOf("second" to unusable),
      cache.reuse("dark", mapOf("first" to "v2", "second" to "v1", "third" to "v1"), 2))
  }

  @Test fun unavailableResultsRetryAfterDeadlineWithoutExpiringKnownVerdicts() {
    val cache = IconMaskCache<String, String>()
    val sources = mapOf("first" to "v1", "second" to "v1")
    cache.record("dark", sources, mapOf("first" to usable), 100)
    assertEquals(mapOf("first" to usable, "second" to unknown), cache.reuse("dark", sources, 60_099))
    assertEquals(mapOf("first" to usable), cache.reuse("dark", sources, 60_100))
  }

  @Test fun packageOverlayInvalidatesAllModesButLeavesOtherPackagesWarm() {
    val cache = IconMaskCache<String, String>()
    val sources = mapOf("first" to "v1", "second" to "v1")
    for (mode in listOf("light", "dark"))
      cache.record(mode, sources, mapOf("first" to usable, "second" to unusable), 1)
    cache.invalidate("first")
    for (mode in listOf("light", "dark"))
      assertEquals(mapOf("second" to unusable), cache.reuse(mode, sources, 2))
    cache.invalidate()
    assertTrue(cache.reuse("dark", sources, 2).isEmpty())
  }

  @Test fun splitFingerprintChangesCannotReuseBaseApkVerdict() {
    val cache = IconMaskCache<String, List<String>>()
    cache.record("dark", mapOf("first" to listOf("base:1", "split:1")), mapOf("first" to usable), 1)
    assertTrue(cache.reuse("dark", mapOf("first" to listOf("base:1", "split:2")), 2).isEmpty())
    assertTrue(cache.reuse("dark", mapOf("first" to listOf("base:1")), 2).isEmpty())
  }

  @Test fun configurationsAndUsersDoNotShareVerdictsAndOldConfigurationsAreEvicted() {
    val cache = IconMaskCache<String, String>(maxConfigurations = 2)
    val sources = mapOf("first" to "v1")
    cache.record("user0-light", sources, mapOf("first" to usable), 1)
    cache.record("user0-dark", sources, mapOf("first" to unusable), 1)
    assertEquals(mapOf("first" to usable), cache.reuse("user0-light", sources, 2))
    assertTrue(cache.reuse("user10-light", sources, 2).isEmpty())
    cache.record("user10-light", sources, mapOf("first" to unusable), 2)
    assertTrue(cache.reuse("user0-dark", sources, 3).isEmpty())
    assertEquals(mapOf("first" to usable), cache.reuse("user0-light", sources, 3))
  }

  @Test fun uninstalledPackagesAndEntryLimitsDoNotAccumulate() {
    val cache = IconMaskCache<String, String>(maxPackages = 2)
    cache.record("dark", mapOf("a" to "v1", "b" to "v1", "c" to "v1"),
      mapOf("a" to usable, "b" to usable, "c" to usable), 1)
    assertEquals(setOf("b", "c"), cache.reuse("dark", mapOf("a" to "v1", "b" to "v1", "c" to "v1"), 2).keys)
    assertEquals(setOf("c"), cache.reuse("dark", mapOf("c" to "v1"), 2).keys)
    assertTrue(cache.reuse("dark", mapOf("b" to "v1"), 3).isEmpty())
  }
}
