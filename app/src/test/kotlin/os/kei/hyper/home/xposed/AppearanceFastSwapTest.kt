package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceFastSwapTest {
  private val active = AppearanceFileKey(1, 10, 100, 1_000)
  private val staged = AppearanceFileKey(1, 11, 90, 2_000)
  private val stagedPath = "/data/system/theme/.keihome-appearance-dark.icons"
  private val ready = AppearanceFastSwap.State(active, activeNight = false, stagedPath, staged, stagedNight = true)

  private class Disk(val files: MutableMap<String, AppearanceFileKey>) {
    val renames = mutableListOf<Pair<String, String>>()
    fun swap() = AppearanceFastSwap(stat = { files[it] }, rename = { from, to ->
      renames += from to to
      files[to] = requireNotNull(files.remove(from))
    })
  }

  private fun disk() = Disk(mutableMapOf(AppearanceFastSwap.ACTIVE to active, stagedPath to staged))

  @Test fun renamesTheVerifiedStagedCopyOnceForTheMatchingTransition() {
    val disk = disk()
    val swap = disk.swap()
    swap.publish(ready)
    assertTrue(swap.beforeNightChange(night = true))
    assertEquals(listOf(stagedPath to AppearanceFastSwap.ACTIVE), disk.renames)
    assertEquals(staged, disk.files[AppearanceFastSwap.ACTIVE])
    // The staged copy is consumed; a quick second transition waits for the worker.
    assertFalse(swap.beforeNightChange(night = false))
    assertFalse(swap.beforeNightChange(night = true))
    assertEquals(1, disk.renames.size)
  }

  @Test fun ignoresTransitionsTheStagedCopyDoesNotServe() {
    val disk = disk()
    val swap = disk.swap()
    swap.publish(ready)
    assertFalse(swap.beforeNightChange(night = false))
    assertTrue(disk.renames.isEmpty())
    // A mismatched request does not consume the prepared copy.
    assertTrue(swap.beforeNightChange(night = true))
  }

  @Test fun leavesForeignOrChangedFilesAlone() {
    for (change in listOf<(MutableMap<String, AppearanceFileKey>) -> Unit>(
      { it[AppearanceFastSwap.ACTIVE] = active.copy(modifiedNanos = 5_000) }, // Themes applied another archive.
      { it[stagedPath] = staged.copy(size = 91) },                            // Staged bytes changed.
      { it.remove(stagedPath) },                                              // Staged copy removed.
      { it.remove(AppearanceFastSwap.ACTIVE) },
    )) {
      val disk = disk()
      change(disk.files)
      val swap = disk.swap()
      swap.publish(ready)
      assertFalse(swap.beforeNightChange(night = true))
      assertTrue(disk.renames.isEmpty())
    }
  }

  @Test fun rejectsUnexpectedStagingPaths() {
    for (path in listOf("/data/system/theme/icons", "/data/system/theme/.keihome-appearance-primary.icons",
      "/data/system/theme/../theme/.keihome-appearance-dark.icons", "/sdcard/.keihome-appearance-dark.icons")) {
      val disk = Disk(mutableMapOf(AppearanceFastSwap.ACTIVE to active, path to staged))
      val swap = disk.swap()
      swap.publish(ready.copy(stagedPath = path))
      assertFalse(swap.beforeNightChange(night = true))
      assertTrue(disk.renames.isEmpty())
    }
    val disk = disk()
    val swap = disk.swap()
    swap.publish(ready.copy(stagedNight = false))
    assertFalse(swap.beforeNightChange(night = false))
    assertFalse(swap.beforeNightChange(night = true))
  }
}
