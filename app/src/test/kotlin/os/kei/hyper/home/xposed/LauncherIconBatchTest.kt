package os.kei.hyper.home.xposed

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherIconBatchTest {
  private fun entry(name: String, suspended: Boolean = false) = LauncherIconBatch.Entry(name, suspended)

  @Test fun batchesOnlySingleEntryPackagesThatAreNotSuspended() {
    val batch = LauncherIconBatch()
    val eligible = batch.eligible(listOf(
      entry("b.app"), entry("a.app"),
      entry("multi.app"), entry("multi.app"), // Home would refresh only its first entry.
      entry("paused.app", suspended = true),  // Unsuspending would clear its real state.
    ))
    assertEquals(listOf("a.app", "b.app"), eligible.toList())
  }

  @Test fun consecutiveBatchesNeverRepeatTheSameOrder() {
    val batch = LauncherIconBatch()
    val packages = setOf("c", "a", "b")
    val first = batch.order(0, packages)
    val second = batch.order(0, packages)
    val third = batch.order(0, packages)
    // Home drops a batch identical to its previous one for about five seconds.
    assertArrayEquals(arrayOf("c", "b", "a"), first)
    assertArrayEquals(arrayOf("a", "b", "c"), second)
    assertArrayEquals(first, third)
  }

  @Test fun eachProfileAlternatesOnItsOwn() {
    val batch = LauncherIconBatch()
    val packages = setOf("a", "b")
    // One refresh covers the owner and a dual-app profile; the next refresh must change both.
    val owner = batch.order(0, packages)
    val dual = batch.order(999, packages)
    assertArrayEquals(arrayOf("b", "a"), owner)
    assertArrayEquals(arrayOf("b", "a"), dual)
    assertArrayEquals(arrayOf("a", "b"), batch.order(0, packages))
    assertArrayEquals(arrayOf("a", "b"), batch.order(999, packages))
  }
}
