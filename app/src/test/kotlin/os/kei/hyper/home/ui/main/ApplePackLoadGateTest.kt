package os.kei.hyper.home.ui.main

import org.junit.Assert.*
import org.junit.Test

class ApplePackLoadGateTest {
  @Test fun completedEmptySnapshotDoesNotReloadOnEntryButExplicitRefreshDoes() {
    val gate = ApplePackLoadGate()
    assertTrue(gate.begin(force = false, busy = false))
    gate.finish(success = true)
    assertFalse(gate.begin(force = false, busy = false))
    assertTrue(gate.begin(force = true, busy = false))
  }

  @Test fun overlappingEntriesDoNotScheduleAnotherLoad() {
    val gate = ApplePackLoadGate()
    assertTrue(gate.begin(force = false, busy = false))
    repeat(3) { assertFalse(gate.begin(force = false, busy = false)) }
    gate.finish(success = true)
    assertFalse(gate.queued)
  }

  @Test fun explicitInvalidationsDuringLoadCoalesceIntoOneFollowup() {
    val gate = ApplePackLoadGate()
    assertTrue(gate.begin(force = false, busy = false))
    repeat(3) { assertFalse(gate.begin(force = true, busy = false)) }
    gate.finish(success = true)
    assertTrue(gate.queued)
    assertTrue(gate.begin(force = true, busy = false))
    gate.finish(success = true)
    assertFalse(gate.queued)
  }

  @Test fun busyOperationRetainsRefreshUntilItCanStart() {
    val gate = ApplePackLoadGate()
    assertFalse(gate.begin(force = false, busy = true))
    assertTrue(gate.queued)
    assertTrue(gate.begin(force = true, busy = false))
    gate.finish(success = true)
    assertFalse(gate.queued)
  }

  @Test fun failedOrCancelledFirstLoadCanRetryWithoutDiscardingAnOlderSuccess() {
    val gate = ApplePackLoadGate()
    assertTrue(gate.begin(force = false, busy = false))
    gate.finish(success = false)
    assertFalse(gate.running)
    assertTrue(gate.begin(force = false, busy = false))
    gate.finish(success = true)
    assertTrue(gate.begin(force = true, busy = false))
    gate.finish(success = false)
    assertTrue(gate.loaded)
    assertFalse(gate.begin(force = false, busy = false))
  }

  @Test fun sameLocaleIsReusedButChangingAndReturningLocaleEachReloadOnce() {
    val gate = ApplePackLoadGate()
    for (locale in listOf("en-US", "zh-CN", "en-US")) {
      assertTrue(gate.begin(force = false, busy = false, localeTags = locale))
      gate.finish(success = true)
      repeat(3) { assertFalse(gate.begin(force = false, busy = false, localeTags = locale)) }
      assertEquals(locale, gate.loadedLocaleTags)
      assertFalse(gate.queued)
    }
  }

  @Test fun localeChangeDuringLoadDiscardsOldResultAndQueuesOneLatestLoad() {
    val gate = ApplePackLoadGate()
    assertTrue(gate.begin(force = false, busy = false, localeTags = "en-US"))
    assertFalse(gate.begin(force = false, busy = false, localeTags = "zh-CN"))
    repeat(3) { assertFalse(gate.begin(force = false, busy = false, localeTags = "ja-JP")) }
    assertFalse(gate.acceptsResult)
    gate.finish(success = true)
    assertFalse(gate.loaded)
    assertTrue(gate.queued)
    assertTrue(gate.begin(force = true, busy = false))
    repeat(3) { assertFalse(gate.begin(force = false, busy = false, localeTags = "ja-JP")) }
    gate.finish(success = true)
    assertEquals("ja-JP", gate.loadedLocaleTags)
    assertFalse(gate.queued)
  }

  @Test fun busyLocaleChangesCoalesceWithoutLosingRequestedLanguage() {
    val gate = ApplePackLoadGate()
    assertTrue(gate.begin(force = false, busy = false, localeTags = "en-US"))
    gate.finish(success = true)
    assertFalse(gate.begin(force = false, busy = true, localeTags = "zh-CN"))
    assertFalse(gate.begin(force = false, busy = true, localeTags = "en-US"))
    assertFalse(gate.queued)
    repeat(3) { assertFalse(gate.begin(force = false, busy = true, localeTags = "zh-CN")) }
    assertEquals("zh-CN", gate.requestedLocaleTags)
    assertTrue(gate.queued)
    assertTrue(gate.begin(force = true, busy = false))
    gate.finish(success = true)
    assertEquals("zh-CN", gate.loadedLocaleTags)
    assertFalse(gate.queued)
    assertFalse(gate.begin(force = false, busy = false))
  }

  @Test fun returningToRunningLocaleDropsOnlyObsoleteLocaleRefresh() {
    val gate = ApplePackLoadGate()
    assertTrue(gate.begin(force = false, busy = false, localeTags = "en-US"))
    assertFalse(gate.begin(force = false, busy = false, localeTags = "zh-CN"))
    assertFalse(gate.begin(force = false, busy = false, localeTags = "en-US"))
    assertFalse(gate.queued)
    assertTrue(gate.acceptsResult)
    assertFalse(gate.begin(force = true, busy = false))
    assertFalse(gate.begin(force = false, busy = false, localeTags = "zh-CN"))
    assertFalse(gate.begin(force = false, busy = false, localeTags = "en-US"))
    assertTrue(gate.queued)
    gate.finish(success = true)
    assertEquals("en-US", gate.loadedLocaleTags)
    assertTrue(gate.begin(force = true, busy = false))
  }

  @Test fun failedLocaleRefreshRetainsOldSnapshotAndNewLocaleCanRetry() {
    val gate = ApplePackLoadGate()
    assertTrue(gate.begin(force = false, busy = false, localeTags = "en-US"))
    gate.finish(success = true)
    assertTrue(gate.begin(force = false, busy = false, localeTags = "zh-CN"))
    gate.finish(success = false)
    assertEquals("en-US", gate.loadedLocaleTags)
    assertTrue(gate.begin(force = false, busy = false, localeTags = "zh-CN"))
  }
}
