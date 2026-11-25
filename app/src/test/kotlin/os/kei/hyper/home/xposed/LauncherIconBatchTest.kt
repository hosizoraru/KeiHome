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
    val first = batch.order(packages)
    val second = batch.order(packages)
    val third = batch.order(packages)
    // Home drops a batch identical to its previous one for about five seconds.
    assertArrayEquals(arrayOf("c", "b", "a"), first)
    assertArrayEquals(arrayOf("a", "b", "c"), second)
    assertArrayEquals(first, third)
  }
}
