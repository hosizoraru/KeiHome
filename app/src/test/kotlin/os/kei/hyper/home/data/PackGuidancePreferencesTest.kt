package os.kei.hyper.home.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackGuidancePreferencesTest {
  private class Store {
    val saved = mutableMapOf<String, Int>()
    var writes = 0
    fun open() = PackGuidancePreferences(
      { saved[it] ?: 0 },
      { key, revision -> saved[key] = revision; writes++; true },
    )
  }

  @Test fun firstVisitRequiresGuidanceWithoutWritingDuringRead() {
    val store = Store()
    PackGuidancePage.entries.forEach { assertFalse(store.open().hasSeen(it)) }
    assertTrue(store.saved.isEmpty())
    assertEquals(0, store.writes)
    assertEquals(PackGuidancePage.entries.size, PackGuidancePage.entries.map { it.storageKey }.distinct().size)
  }

  @Test fun seenRevisionSurvivesRecreationAndDoesNotConsumeOtherPages() {
    val store = Store()
    store.open().markSeen(PackGuidancePage.APPLE)
    assertTrue(store.open().hasSeen(PackGuidancePage.APPLE))
    PackGuidancePage.entries.filter { it != PackGuidancePage.APPLE }.forEach {
      assertFalse(store.open().hasSeen(it))
    }
    repeat(5) { store.open().markSeen(PackGuidancePage.APPLE) }
    assertEquals(1, store.writes)
  }

  @Test fun deliberateRevisionBumpRequiresGuidanceOnceAndNeverDowngradesSeenRevision() {
    val store = Store()
    val revision = PackGuidancePreferences.CURRENT_REVISION
    store.open().markSeen(PackGuidancePage.PERFECT, revision - 1)
    assertFalse(store.open().hasSeen(PackGuidancePage.PERFECT))
    store.open().markSeen(PackGuidancePage.PERFECT)
    assertTrue(store.open().hasSeen(PackGuidancePage.PERFECT))
    store.open().markSeen(PackGuidancePage.PERFECT, revision - 1)
    assertEquals(revision, store.saved[PackGuidancePage.PERFECT.storageKey])
    assertEquals(2, store.writes)
  }

  @Test fun allPagesKeepIndependentRevisions() {
    val store = Store()
    PackGuidancePage.entries.forEachIndexed { index, page -> store.open().markSeen(page, index + 1) }
    PackGuidancePage.entries.forEachIndexed { index, page ->
      assertTrue(store.open().hasSeen(page, index + 1))
      assertFalse(store.open().hasSeen(page, index + 2))
    }
  }
}
