package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.iconpack.perfect.PerfectIconZipLimits
import os.kei.hyper.home.xposed.ConvertedIconArchive

class IconPackBudgetTest {
  @Test fun generationImportAndNativeAdmissionUseCompatibleBoundedBudgets() {
    val limits = IconPackLimits()
    val source = PerfectIconZipLimits()
    assertEquals(source.compressed, limits.maxInputBytes)
    assertEquals(source.expanded, limits.maxExpandedBytes)
    assertEquals(limits.maxInputBytes, ConvertedIconArchive.MAX_BYTES)
    assertTrue(limits.maxStoredBytes >= 2L * limits.maxInputBytes)
    assertEquals(96L * 1024 * 1024, limits.maxEntryBytes)
    assertEquals(100_000, limits.maxEntries)
  }
}
