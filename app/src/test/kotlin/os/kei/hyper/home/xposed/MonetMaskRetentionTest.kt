package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class MonetMaskRetentionTest {
  @Test fun failedProbeRetainsKnownBadSourceWithoutDenyingNewUnknownResources() {
    assertEquals(setOf("bad"), retainedUnusableMasks(
      mapOf("bad" to LauncherMaskStatus.UNKNOWN, "new" to LauncherMaskStatus.UNKNOWN),
      setOf("bad"), setOf("bad", "new"),
    ))
  }

  @Test fun usableOrAbsentResourceClearsOldDenial() {
    for (state in listOf(LauncherMaskStatus.USABLE, LauncherMaskStatus.ABSENT)) {
      assertEquals(emptySet<String>(), retainedUnusableMasks(mapOf("app" to state), setOf("app"), setOf("app")))
    }
  }

  @Test fun changedOrRemovedSourceDoesNotReuseOldResult() {
    assertEquals(setOf("bad"), retainedUnusableMasks(
      mapOf("bad" to LauncherMaskStatus.UNUSABLE, "updated" to LauncherMaskStatus.UNKNOWN),
      setOf("updated", "removed"), emptySet(),
    ))
  }
}
